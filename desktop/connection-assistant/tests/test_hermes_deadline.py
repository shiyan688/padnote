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
from padnote_assistant import admin_ui
from padnote_assistant.lan import load_or_create_identity
from padnote_assistant.hermes import (
    HermesClient,
    HermesError,
    _BoundedResolver,
    _RequestDeadline,
)
from padnote_assistant.state import AuthorizationError, ConflictError




TEST_HTTP_DEADLINE_SECONDS = 1.5
TEST_SCHEDULER_GRACE_SECONDS = 1.0


class _HermesFixtureHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args) -> None:
        pass

    def do_GET(self) -> None:
        self.server.request_count += 1
        self.server.request_started.set()
        self.server.get_paths.append(self.path)
        if self.path == "/v1/capabilities" and self.server.mode == "valid-capabilities":
            self._respond({
                "object": "hermes.api_server.capabilities", "platform": "hermes-agent",
                "features": {"run_submission": True, "run_status": True,
                              "run_stop": True, "run_approval_response": True},
                "endpoints": {
                    "runs": {"method": "POST", "path": "/v1/runs"},
                    "run_status": {"method": "GET", "path": "/v1/runs/{run_id}"},
                    "run_stop": {"method": "POST", "path": "/v1/runs/{run_id}/stop"},
                    "run_approval": {"method": "POST", "path": "/v1/runs/{run_id}/approval"},
                },
            })
        elif self.path.startswith("/v1/runs/"):
            run_id = self.path.rsplit("/", 1)[-1]
            value = {"run_id": run_id, "status": self.server.get_run_status}
            if self.server.get_run_error is not None:
                value["error"] = self.server.get_run_error
            self._respond(value)
        else:
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
            response_status = 200
        else:
            run_id = self.server.run_id_value or self.server.run_ids.get(key, "run-fixture")
            value = {"run_id": run_id,
                     "status": self.server.run_status}
            if self.server.run_error is not None:
                value["error"] = self.server.run_error
            response_status = self.server.response_status
        self._respond(value, status=response_status)

    def _respond(self, value: dict, *, status: int = 200) -> None:
        mode = self.server.mode
        if mode == "no-response":
            time.sleep(2)
            return
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
                    time.sleep(0.08)
                self.connection.sendall(b"Content-Length: 2\r\n\r\n{}")
            except OSError:
                pass
            return
        self.send_response(status)
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
                    time.sleep(0.08)
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
        self.run_status = "running"
        self.run_error: str | None = None
        self.run_id_value: str | None = None
        self.response_status = 200
        self.get_run_status = "running"
        self.get_run_error: str | None = None
        self.get_paths: list[str] = []
        if tls:
            # Any self-signed certificate is untrusted, which is all this needs.
            # Generated per run so no private key is ever committed.
            self._tls_dir = tempfile.TemporaryDirectory()
            identity = load_or_create_identity(Path(self._tls_dir.name))
            context = identity.server_context()
            self.socket = context.wrap_socket(self.socket, server_side=True)
        self.thread = threading.Thread(target=self.serve_forever, daemon=True)
        self.thread.start()

    def close(self) -> None:
        self.shutdown()
        self.server_close()
        self.thread.join(2)
        if hasattr(self, "_tls_dir"):
            self._tls_dir.cleanup()


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


def _payload(client_task_id: str = "deadline-task-1") -> dict:
    return {
        "client_task_id": client_task_id,
        "title": "Deadline fixture",
        "input": "Only use this fixture",
        "source": {"note_id": "deadline-note", "note_revision": 1},
    }


class HermesDeadlineTests(unittest.TestCase):
    def setUp(self) -> None:
        self.server = _FixtureServer()

    def tearDown(self) -> None:
        self.server.close()

    def client(self, *, timeout: float = TEST_HTTP_DEADLINE_SECONDS) -> HermesClient:
        return HermesClient(
            f"http://127.0.0.1:{self.server.server_port}", "fixture-secret",
            timeout=timeout)

    def assert_deadline_error(self, mode: str, *, method: str = "POST",
                              timeout: float = TEST_HTTP_DEADLINE_SECONDS) -> HermesError:
        self.server.mode = mode
        started = time.monotonic()
        with self.assertRaises(HermesError) as caught:
            self.client(timeout=timeout)._request(
                method, "/v1/runs", {"secret": "body-secret"} if method == "POST" else None)
        elapsed = time.monotonic() - started
        # This is a total request deadline, with one second reserved for Windows
        # scheduling/teardown load. It is not multiplied by headers or chunks.
        self.assertLess(elapsed, timeout + TEST_SCHEDULER_GRACE_SECONDS)
        self.assertEqual(method == "POST", caught.exception.uncertain)
        self.assertNotIn("fixture-secret", str(caught.exception))
        self.assertNotIn("body-secret", str(caught.exception))
        return caught.exception

    def test_total_deadline_interrupts_slow_headers_and_trickle_body(self) -> None:
        self.assertEqual("upstream_unresponsive",
                         self.assert_deadline_error("slow-header", method="GET").code)
        self.server.request_started.clear()
        error = self.assert_deadline_error("slow-body")
        self.assertEqual("upstream_unresponsive", error.code)
        self.assertTrue(error.uncertain)

    def test_listener_without_http_bytes_is_unresponsive_not_ready(self) -> None:
        self.server.mode = "no-response"
        result = self.client(timeout=0.25).check_capabilities()
        self.assertEqual("unresponsive", result.health)
        self.assertFalse(result.executable)
        self.assertIn("accepted the connection", result.detail)

    def test_refused_wrong_service_and_valid_capabilities_keep_distinct_states(self) -> None:
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            refused_port = probe.getsockname()[1]
        refused = HermesClient(f"http://127.0.0.1:{refused_port}", None, timeout=0.5).check_capabilities()
        self.assertEqual("not_running", refused.health)
        self.assertFalse(refused.executable)

        self.server.mode = "normal"
        wrong = self.client(timeout=1).check_capabilities()
        self.assertEqual("wrong_service", wrong.health)
        self.assertFalse(wrong.executable)

        self.server.mode = "valid-capabilities"
        valid = self.client(timeout=1).check_capabilities()
        self.assertEqual("ready", valid.health)
        self.assertTrue(valid.executable)

    def test_new_health_is_safe_in_admin_and_readiness_mappings(self) -> None:
        label, tone = admin_ui._health("unresponsive")
        self.assertEqual("服务已连接但无响应", label)
        self.assertEqual("bad", tone)
        self.assertIn("不会自动重启", admin_ui._health_guidance("unresponsive"))
        with tempfile.TemporaryDirectory() as directory:
            with BridgeService(Path(directory)) as service:
                value = service._readiness_snapshot({
                    "health": "unresponsive", "checked_at": int(service.clock()),
                    "executable": False,
                }, key_present=True)
        self.assertEqual("unavailable", value["agent_status"])
        self.assertEqual("unresponsive", value["reason"])

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
        self.assertTrue(self.client(timeout=2)._request("GET", "/normal")["ok"])
        self.server.mode = "missing-length"
        self.assertTrue(self.client(timeout=2)._request("GET", "/normal")["ok"])

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
        client = HermesClient(f"https://localhost:{tls.server_port}", None, timeout=2)
        with self.assertRaises(HermesError) as caught:
            client._request("GET", "/normal")
        self.assertEqual("upstream_unreachable", caught.exception.code)
        self.assertFalse(caught.exception.uncertain)

    def test_started_run_status_is_normalized_to_running(self) -> None:
        self.server.run_status = "started"
        self.server.response_status = 202
        with tempfile.TemporaryDirectory() as directory:
            with BridgeService(Path(directory)) as service:
                instance, token, _ = _pair(service, self.server.server_port)
                with mock.patch("padnote_assistant.bridge.HermesClient") as client_type:
                    client_type.return_value = self.client(timeout=2)
                    _, result = service.submit_run(
                        instance["instance_id"], token, "started-task-1", _payload("started-task-1"))
                self.assertEqual("running", result["status"])
                self.assertIsNone(result.get("error"))

    def test_unknown_status_keeps_provider_id_and_polls_that_same_run(self) -> None:
        self.server.run_status = "future_status"
        with tempfile.TemporaryDirectory() as directory:
            with BridgeService(Path(directory)) as service:
                instance, token, _ = _pair(service, self.server.server_port)
                with mock.patch("padnote_assistant.bridge.HermesClient") as client_type:
                    client_type.return_value = self.client(timeout=2)
                    _, submitted = service.submit_run(
                        instance["instance_id"], token, "unknown-status-task", _payload("unknown-status-task"))
                    stored = service.store.owned_run(
                        service.store.authenticate(instance["instance_id"], token),
                        submitted["task_id"])
                    self.assertEqual("run-1", stored["upstream_run_id"])
                    self.assertIn("运行编号已保存", submitted["error"])
                    post_count = len(self.server.idempotency_keys)
                    _, duplicate = service.submit_run(
                        instance["instance_id"], token, "unknown-status-task", _payload("unknown-status-task"))
                    self.assertEqual(submitted["task_id"], duplicate["task_id"])
                    self.assertEqual(post_count, len(self.server.idempotency_keys))

                    self.server.get_run_status = "running"
                    refreshed = service.get_run(
                        instance["instance_id"], token, submitted["task_id"])
                self.assertEqual("running", refreshed["status"])
                self.assertIsNone(refreshed.get("error"))
                self.assertEqual("/v1/runs/run-1", self.server.get_paths[-1])
                self.assertEqual(post_count, len(self.server.idempotency_keys))

    def test_failed_run_error_survives_polling_and_terminal_unknown_status(self) -> None:
        self.server.run_status = "failed"
        self.server.run_error = "Hermes execution failed"
        with tempfile.TemporaryDirectory() as directory:
            with BridgeService(Path(directory)) as service:
                instance, token, _ = _pair(service, self.server.server_port)
                with mock.patch("padnote_assistant.bridge.HermesClient") as client_type:
                    client_type.return_value = self.client(timeout=2)
                    _, submitted = service.submit_run(
                        instance["instance_id"], token, "failed-task-1", _payload("failed-task-1"))
                    self.assertEqual("failed", submitted["status"])
                    self.assertEqual("Hermes execution failed", submitted["error"])

                    self.server.get_run_status = "failed"
                    self.server.get_run_error = "Hermes execution failed"
                    refreshed = service.get_run(
                        instance["instance_id"], token, submitted["task_id"])
                    self.assertEqual("failed", refreshed["status"])
                    self.assertEqual("Hermes execution failed", refreshed["error"])

                    self.server.get_run_status = "future_terminal_status"
                    terminal_refresh = service.get_run(
                        instance["instance_id"], token, submitted["task_id"])
                self.assertEqual("failed", terminal_refresh["status"])
                self.assertEqual("Hermes execution failed", terminal_refresh["error"])

    def test_invalid_provider_run_id_is_uncertain_and_never_resubmitted(self) -> None:
        self.server.run_id_value = "invalid/run-id"
        with tempfile.TemporaryDirectory() as directory:
            with BridgeService(Path(directory)) as service:
                instance, token, _ = _pair(service, self.server.server_port)
                with mock.patch("padnote_assistant.bridge.HermesClient") as client_type:
                    client_type.return_value = self.client(timeout=2)
                    _, result = service.submit_run(
                        instance["instance_id"], token, "invalid-id-task", _payload("invalid-id-task"))
                    self.assertEqual("submitting", result["status"])
                    self.assertIn("不会自动重发", result["error"])
                    post_count = len(self.server.idempotency_keys)
                    _, duplicate = service.submit_run(
                        instance["instance_id"], token, "invalid-id-task", _payload("invalid-id-task"))
                self.assertEqual(result["task_id"], duplicate["task_id"])
                self.assertEqual(post_count, len(self.server.idempotency_keys))

    def test_dns_workers_are_bounded_and_an_expired_socket_is_closed(self) -> None:
        resolver = _BoundedResolver(2)
        release = threading.Event()
        entered = threading.Barrier(3)

        def blocked_lookup(*_args, **_kwargs):
            entered.wait(timeout=2)
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
            entered.wait(timeout=2)
            started = time.monotonic()
            with self.assertRaises(TimeoutError):
                resolver.resolve("third.invalid", 80, _RequestDeadline(0.75))
            self.assertLess(time.monotonic() - started, 1.25)
            release.set()
            for worker in workers:
                worker.join(2)
                self.assertFalse(worker.is_alive())
        self.assertEqual([], errors)

        deadline = _RequestDeadline(0.15)
        deadline.start()
        time.sleep(0.3)
        first, second = socket.socketpair()
        self.addCleanup(second.close)
        with self.assertRaises(TimeoutError):
            deadline.register_socket(first)
        self.assertEqual(-1, first.fileno())

    def test_uncertain_submission_is_not_resent_and_stop_never_submits_unknown_task(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with BridgeService(Path(directory)) as service:
                instance, token, _ = _pair(service, self.server.server_port)
                def factory(url, key, **_kwargs):
                    # Keep this fixture's deliberate short deadline to simulate an unknown
                    # submission; production's wider submission window is covered separately.
                    timeout = 3.0 if self.server.mode == "normal" else TEST_HTTP_DEADLINE_SECONDS
                    return HermesClient(url, key, timeout=timeout)
                connection_id = service.store.authenticate(
                    instance["instance_id"], token)["connection_id"]
                self.server.mode = "slow-body"
                with mock.patch("padnote_assistant.bridge.HermesClient", side_effect=factory):
                    _, first = service.submit_run(
                        instance["instance_id"], token, "deadline-task-1", _payload())
                    self.assertEqual("submitting", first["status"])
                    self.assertIn("不会自动重发", first["error"])
                    before_stop = len(self.server.idempotency_keys)
                    with self.assertRaises(ConflictError):
                        service.stop_run(instance["instance_id"], token, first["task_id"])
                    stopped_row = service.store.snapshot()["runs"][first["task_id"]]
                    self.assertEqual("submitting", stopped_row["status"])
                    self.assertNotIn("control_actions", stopped_row)
                    self.assertEqual(before_stop, len(self.server.idempotency_keys))

                    self.server.mode = "normal"
                    _, retried = service.submit_run(
                        instance["instance_id"], token, "deadline-task-1", _payload())
                    self.assertEqual(first["task_id"], retried["task_id"])
                    self.assertEqual("submitting", retried["status"])
                    self.assertEqual(1, len(self.server.idempotency_keys))
                    def remove_new_marker(data: dict) -> None:
                        data["runs"][first["task_id"]].pop("submission_uncertain", None)
                        data["runs"][first["task_id"]]["error"] = (
                            "Submission outcome is unknown; retry with the same task ID")
                    service.store._commit(remove_new_marker)
                    legacy_run = service.store.owned_run(
                        service.store.authenticate(instance["instance_id"], token), first["task_id"])
                    self.assertIsNone(legacy_run.get("submission_uncertain"))
                    _, legacy_retry = service.submit_run(
                        instance["instance_id"], token, "deadline-task-1", _payload())
                    self.assertEqual(first["task_id"], legacy_retry["task_id"])
                    self.assertEqual(1, len(self.server.idempotency_keys))

                    _, known = service.submit_run(
                        instance["instance_id"], token, "deadline-task-2", _payload("deadline-task-2"))
                    self.assertEqual("running", known["status"])
                    self.assertEqual(2, len(self.server.idempotency_keys))
                    self.assertEqual(2, len(self.server.run_ids))

                    self.server.mode = "slow-body"
                    self.server.request_started.clear()
                    stop_result: list[dict] = []
                    stop_thread = threading.Thread(target=lambda: stop_result.append(
                        service.stop_run(instance["instance_id"], token,
                                         known["task_id"])[1]))
                    stop_thread.start()
                    self.assertTrue(self.server.request_started.wait(2.5))
                    requests_before_queue = self.server.request_count
                    queued_errors: list[BaseException] = []
                    queued_results: list[dict] = []

                    def queued_get() -> None:
                        try:
                            queued_results.append(service.get_run(
                                instance["instance_id"], token, known["task_id"]))
                        except BaseException as error:
                            queued_errors.append(error)

                    queued_threads = [
                        threading.Thread(target=queued_get, name=f"queued-get-{index}")
                        for index in range(4)
                    ]
                    for worker in queued_threads:
                        worker.start()
                    for worker in queued_threads:
                        worker.join(2.5)
                        self.assertFalse(worker.is_alive())
                    self.assertEqual(len(queued_threads), len(queued_results))
                    self.assertTrue(all(item["status"] == "stopping" for item in queued_results))
                    revoke_thread = threading.Thread(
                        target=service.revoke_connection,
                        args=(connection_id,))
                    started = time.monotonic()
                    revoke_thread.start()
                    stop_thread.join(3.5)
                    revoke_thread.join(3.5)
                    self.assertFalse(stop_thread.is_alive())
                    self.assertFalse(revoke_thread.is_alive())
                    self.assertLess(time.monotonic() - started,
                                    TEST_HTTP_DEADLINE_SECONDS + TEST_SCHEDULER_GRACE_SECONDS)
                    self.assertEqual("stopping", stop_result[0]["status"])
                    self.assertEqual(requests_before_queue, self.server.request_count)
                    self.assertEqual([], queued_errors)
                    with self.assertRaises(AuthorizationError):
                        service.store.authenticate(instance["instance_id"], token)


if __name__ == "__main__":
    unittest.main()
