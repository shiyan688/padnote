from __future__ import annotations

import json
import socket
import ssl
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

from padnote_assistant.bridge import BridgeService
from padnote_assistant.hermes import (
    HermesClient,
    HermesError,
    _BoundedResolver,
    _RequestDeadline,
)
from padnote_assistant.state import AuthorizationError


FIXTURES = Path(__file__).resolve().parent / "fixtures"


class _HermesFixtureHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args) -> None:
        pass

    def do_GET(self) -> None:
        self.server.request_count += 1
        self.server.request_started.set()
        self._respond({"object": "fixture", "ok": True})

    def do_POST(self) -> None:
        self.server.request_count += 1
        length = int(self.headers.get("Content-Length", "0"))
        if length:
            self.rfile.read(length)
        self.server.request_started.set()
        key = self.headers.get("Idempotency-Key")
        if key:
            self.server.idempotency_keys.append(key)
            self.server.run_ids.setdefault(key, "run-" + str(len(self.server.run_ids) + 1))
        if self.path.endswith("/stop"):
            value = {"run_id": self.path.split("/")[-2], "status": "stopping"}
        else:
            value = {"run_id": self.server.run_ids.get(key, "run-fixture"),
                     "status": "running"}
        self._respond(value)

    def _respond(self, value: dict) -> None:
        mode = self.server.mode
        data = json.dumps(value, separators=(",", ":")).encode("utf-8")
        if mode == "nonfinite-number":
            data = b'{"value":1e999}'
        elif mode == "long-number":
            data = b'{"value":' + (b"9" * 129) + b"}"
        elif mode == "deep-json":
            data = (b'{"value":' + (b"[" * 1200) + b"0"
                    + (b"]" * 1200) + b"}")
        if mode in {"slow-error-body", "slow-redirect-body"}:
            self.send_response(500 if mode == "slow-error-body" else 302)
            if mode == "slow-redirect-body":
                self.send_header("Location", "/redirect-target")
            self.send_header("Content-Length", "1000")
            self.end_headers()
            try:
                for _index in range(100):
                    self.wfile.write(b"x")
                    self.wfile.flush()
                    time.sleep(0.03)
            except OSError:
                pass
            return
        if mode == "slow-header":
            try:
                self.connection.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n")
                for index in range(30):
                    self.connection.sendall(f"X-Fixture-{index}: x\r\n".encode("ascii"))
                    time.sleep(0.03)
                self.connection.sendall(b"Content-Length: 2\r\n\r\n{}")
            except OSError:
                pass
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        if mode == "invalid-length":
            self.send_header("Content-Length", "not-a-number")
        elif mode == "duplicate-length":
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Content-Length", str(len(data)))
        elif mode == "truncated":
            self.send_header("Content-Length", str(len(data) + 50))
        elif mode != "missing-length":
            self.send_header("Content-Length", str(len(data)))
        if mode == "missing-length":
            self.send_header("Connection", "close")
            self.close_connection = True
        self.end_headers()
        try:
            if mode == "slow-body":
                for byte in data:
                    self.wfile.write(bytes((byte,)))
                    self.wfile.flush()
                    time.sleep(0.03)
            else:
                self.wfile.write(data if mode != "truncated" else data[:2])
                self.wfile.flush()
                if mode == "truncated":
                    self.close_connection = True
        except OSError:
            pass


class _FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, *, tls: bool = False) -> None:
        super().__init__(("127.0.0.1", 0), _HermesFixtureHandler)
        self.mode = "normal"
        self.request_started = threading.Event()
        self.idempotency_keys: list[str] = []
        self.run_ids: dict[str, str] = {}
        self.request_count = 0
        if tls:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(
                FIXTURES / "hermes-localhost-cert.pem",
                FIXTURES / "hermes-localhost-key.pem",
            )
            self.socket = context.wrap_socket(self.socket, server_side=True)
        self.thread = threading.Thread(target=self.serve_forever, daemon=True)
        self.thread.start()

    def close(self) -> None:
        self.shutdown()
        self.server_close()
        self.thread.join(2)


def _pair(service: BridgeService, port: int) -> tuple[dict, str, dict]:
    instance = service.add_instance(
        "hermes", "Deadline Fixture", f"http://127.0.0.1:{port}", "fixture-key")
    service.store.update_instance_check(
        instance["instance_id"], health="ready", detail="ready",
        features={"run_submission": True, "run_status": True, "run_stop": True},
        executable=True)
    code, _ = service.store.create_pair_code(
        instance["instance_id"], "https://computer.example.ts.net")
    request = service.store.request_pair(code, "deadline-device", "Tablet")
    service.store.decide_pair(request["request_id"], True)
    _, claim = service.store.claim_pair(request["request_id"], request["poll_token"])
    token = claim["connections"][0]["token"]
    return instance, token, service.store.authenticate(instance["instance_id"], token)


def _payload() -> dict:
    return {
        "client_task_id": "deadline-task-1",
        "title": "Deadline fixture",
        "input": "Only use this fixture",
        "source": {"note_id": "deadline-note", "note_revision": 1},
    }


class HermesDeadlineTests(unittest.TestCase):
    def setUp(self) -> None:
        self.server = _FixtureServer()

    def tearDown(self) -> None:
        self.server.close()

    def client(self, *, timeout: float = 0.25) -> HermesClient:
        return HermesClient(
            f"http://127.0.0.1:{self.server.server_port}", "fixture-secret",
            timeout=timeout)

    def assert_deadline_error(self, mode: str, *, method: str = "POST") -> HermesError:
        self.server.mode = mode
        started = time.monotonic()
        with self.assertRaises(HermesError) as caught:
            self.client()._request(method, "/v1/runs", {"secret": "body-secret"}
                                   if method == "POST" else None)
        self.assertLess(time.monotonic() - started, 0.9)
        self.assertEqual(method == "POST", caught.exception.uncertain)
        self.assertNotIn("fixture-secret", str(caught.exception))
        self.assertNotIn("body-secret", str(caught.exception))
        return caught.exception

    def test_total_deadline_interrupts_slow_headers_and_trickle_body(self) -> None:
        self.assertEqual("upstream_unreachable",
                         self.assert_deadline_error("slow-header", method="GET").code)
        self.server.request_started.clear()
        self.assertEqual("upstream_unreachable",
                         self.assert_deadline_error("slow-body").code)

    def test_content_length_and_truncation_are_safely_classified(self) -> None:
        for mode in ("invalid-length", "duplicate-length", "truncated"):
            with self.subTest(mode=mode):
                error = self.assert_deadline_error(mode)
                self.assertEqual("upstream_invalid", error.code)

    def test_error_and_redirect_bodies_are_closed_without_being_read(self) -> None:
        self.assertEqual(
            "upstream_error", self.assert_deadline_error("slow-error-body").code)
        self.assertEqual(
            "upstream_redirect", self.assert_deadline_error("slow-redirect-body").code)

    def test_normal_and_missing_length_responses_are_bounded_and_parse(self) -> None:
        self.server.mode = "normal"
        self.assertTrue(self.client(timeout=1)._request("GET", "/normal")["ok"])
        self.server.mode = "missing-length"
        self.assertTrue(self.client(timeout=1)._request("GET", "/normal")["ok"])

    def test_json_numbers_and_nesting_are_bounded(self) -> None:
        for mode in ("nonfinite-number", "long-number", "deep-json"):
            with self.subTest(mode=mode):
                error = self.assert_deadline_error(mode)
                self.assertEqual("upstream_invalid", error.code)

        for timeout in (0, -1, True, float("nan"), float("inf"), 10 ** 10000):
            with self.subTest(timeout=timeout):
                with self.assertRaises(ValueError):
                    HermesClient("http://127.0.0.1:1", None, timeout=timeout)

    def test_untrusted_local_tls_certificate_is_rejected(self) -> None:
        tls = _FixtureServer(tls=True)
        self.addCleanup(tls.close)
        client = HermesClient(f"https://localhost:{tls.server_port}", None, timeout=1)
        with self.assertRaises(HermesError) as caught:
            client._request("GET", "/normal")
        self.assertEqual("upstream_unreachable", caught.exception.code)
        self.assertFalse(caught.exception.uncertain)

    def test_dns_workers_are_bounded_and_an_expired_socket_is_closed(self) -> None:
        resolver = _BoundedResolver(2)
        release = threading.Event()
        entered = threading.Barrier(3)

        def blocked_lookup(*_args, **_kwargs):
            entered.wait(timeout=1)
            release.wait(timeout=2)
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", 80))]

        errors: list[BaseException] = []

        def resolve() -> None:
            try:
                resolver.resolve("fixture.invalid", 80, _RequestDeadline(1.5))
            except BaseException as error:
                errors.append(error)

        with mock.patch("padnote_assistant.hermes.socket.getaddrinfo", blocked_lookup):
            workers = [threading.Thread(target=resolve) for _ in range(2)]
            for worker in workers:
                worker.start()
            entered.wait(timeout=1)
            started = time.monotonic()
            with self.assertRaises(TimeoutError):
                resolver.resolve("third.invalid", 80, _RequestDeadline(0.15))
            self.assertLess(time.monotonic() - started, 0.6)
            release.set()
            for worker in workers:
                worker.join(2)
                self.assertFalse(worker.is_alive())
        self.assertEqual([], errors)

        deadline = _RequestDeadline(0.03)
        deadline.start()
        time.sleep(0.06)
        first, second = socket.socketpair()
        self.addCleanup(second.close)
        with self.assertRaises(TimeoutError):
            deadline.register_socket(first)
        self.assertEqual(-1, first.fileno())

    def test_bridge_retry_keeps_identity_and_stop_never_submits_unknown_task(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with BridgeService(Path(directory)) as service:
                instance, token, _ = _pair(service, self.server.server_port)
                factory = lambda url, key: HermesClient(url, key, timeout=0.25)
                connection_id = service.store.authenticate(
                    instance["instance_id"], token)["connection_id"]
                original_lock = service._operation_lock
                queued_condition = threading.Condition()
                queued_count = 0

                def observed_lock(key: str):
                    nonlocal queued_count
                    if (key == "connection:" + connection_id
                            and threading.current_thread().name.startswith("queued-get-")):
                        with queued_condition:
                            queued_count += 1
                            queued_condition.notify_all()
                    return original_lock(key)

                service._operation_lock = observed_lock
                self.server.mode = "slow-body"
                with mock.patch("padnote_assistant.bridge.HermesClient", side_effect=factory):
                    _, first = service.submit_run(
                        instance["instance_id"], token, "deadline-task-1", _payload())
                    self.assertEqual("submitting", first["status"])
                    self.assertIn("unknown", first["error"].lower())
                    before_stop = len(self.server.idempotency_keys)
                    _, stopped_unknown = service.stop_run(
                        instance["instance_id"], token, first["task_id"])
                    self.assertEqual("submitting", stopped_unknown["status"])
                    self.assertEqual(before_stop, len(self.server.idempotency_keys))

                    self.server.mode = "normal"
                    _, retried = service.submit_run(
                        instance["instance_id"], token, "deadline-task-1", _payload())
                    self.assertEqual("running", retried["status"])
                    self.assertEqual(2, len(self.server.idempotency_keys))
                    self.assertEqual(1, len(set(self.server.idempotency_keys)))
                    self.assertEqual(1, len(self.server.run_ids))

                    self.server.mode = "slow-body"
                    self.server.request_started.clear()
                    stop_result: list[dict] = []
                    stop_thread = threading.Thread(target=lambda: stop_result.append(
                        service.stop_run(instance["instance_id"], token,
                                         first["task_id"])[1]))
                    stop_thread.start()
                    self.assertTrue(self.server.request_started.wait(1))
                    requests_before_queue = self.server.request_count
                    queued_errors: list[BaseException] = []

                    def queued_get() -> None:
                        try:
                            service.get_run(
                                instance["instance_id"], token, first["task_id"])
                        except BaseException as error:
                            queued_errors.append(error)

                    queued_threads = [
                        threading.Thread(target=queued_get, name=f"queued-get-{index}")
                        for index in range(4)
                    ]
                    for worker in queued_threads:
                        worker.start()
                    with queued_condition:
                        self.assertTrue(queued_condition.wait_for(
                            lambda: queued_count == len(queued_threads), timeout=1))
                    revoke_thread = threading.Thread(
                        target=service.revoke_connection,
                        args=(connection_id,))
                    started = time.monotonic()
                    revoke_thread.start()
                    stop_thread.join(1)
                    revoke_thread.join(1)
                    for worker in queued_threads:
                        worker.join(1)
                        self.assertFalse(worker.is_alive())
                    self.assertFalse(stop_thread.is_alive())
                    self.assertFalse(revoke_thread.is_alive())
                    self.assertLess(time.monotonic() - started, 0.9)
                    self.assertEqual("stopping", stop_result[0]["status"])
                    self.assertEqual(requests_before_queue, self.server.request_count)
                    self.assertEqual(len(queued_threads), len(queued_errors))
                    self.assertTrue(all(isinstance(error, AuthorizationError)
                                        for error in queued_errors))
                    with self.assertRaises(AuthorizationError):
                        service.store.authenticate(instance["instance_id"], token)


if __name__ == "__main__":
    unittest.main()
