from __future__ import annotations

import io
import json
import re
import socket
import struct
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

from padnote_assistant.bridge import BridgeService
from padnote_assistant.web import (
    API_PREFIX,
    ServerGroup,
    _ConnectionDeadlines,
    _DeadlineRequestHandler,
    _Server,
)
from padnote_assistant.security import ValidationError


def _read_response(connection: socket.socket) -> bytes:
    connection.settimeout(2)
    chunks: list[bytes] = []
    while True:
        try:
            chunk = connection.recv(65536)
        except (ConnectionResetError, socket.timeout):
            break
        if not chunk:
            break
        chunks.append(chunk)
    return b"".join(chunks)


def _status(response: bytes) -> int:
    match = re.match(br"HTTP/1\.[01] ([0-9]{3}) ", response)
    if not match:
        raise AssertionError(f"missing HTTP status: {response[:100]!r}")
    return int(match.group(1))


class _LargeResponseHandler(_DeadlineRequestHandler):
    def log_message(self, *_args) -> None:
        pass

    def do_GET(self) -> None:
        self._finish_input()
        if self.path == "/small":
            body = b"ok"
            self._prepare_response()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
            return
        size = 64 * 1024 * 1024
        self._prepare_response(self.server.file_response_deadline(size))
        self.send_response(200)
        self.send_header("Content-Length", str(size))
        self.send_header("Connection", "close")
        self.end_headers()
        chunk = b"x" * (64 * 1024)
        for _index in range(size // len(chunk)):
            self.wfile.write(chunk)


class _DelayedBodyHandler(_DeadlineRequestHandler):
    def log_message(self, *_args) -> None:
        pass

    def do_POST(self) -> None:
        time.sleep(0.2)
        try:
            self._read_request_body(1024)
        except (ValidationError, TimeoutError):
            pass
        else:
            self.server.dispatched.set()
        finally:
            self.server.finished.set()


class WebDeadlineTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-web-deadline-")
        self.service = BridgeService(Path(self.temp.name))
        self.group = ServerGroup(
            self.service,
            admin_port=0,
            api_port=0,
            max_workers=2,
            input_deadline_seconds=0.25,
            response_deadline_seconds=0.25,
            socket_timeout_seconds=1.0,
        )
        self.group.start()

    def tearDown(self) -> None:
        self.group.close()
        self.temp.cleanup()

    @property
    def admin_port(self) -> int:
        return self.group.admin.server_address[1]

    @property
    def api_port(self) -> int:
        return self.group.api.server_address[1]

    def request(self, port: int, request: bytes, *, shutdown_write: bool = True) -> bytes:
        with socket.create_connection(("127.0.0.1", port), timeout=1) as connection:
            connection.sendall(request)
            if shutdown_write:
                connection.shutdown(socket.SHUT_WR)
            return _read_response(connection)

    def admin_get(self) -> bytes:
        return self.request(
            self.admin_port,
            (f"GET / HTTP/1.1\r\nHost: 127.0.0.1:{self.admin_port}\r\n"
             "Connection: close\r\n\r\n").encode("ascii"),
        )

    def test_slow_headers_release_all_workers_and_capacity_recovers(self) -> None:
        clients = [socket.create_connection(("127.0.0.1", self.admin_port), timeout=1)
                   for _ in range(2)]

        def trickle(connection: socket.socket) -> None:
            try:
                for value in b"GET / HTTP/1.1\r\nHost: 127.0.0.1":
                    connection.sendall(bytes((value,)))
                    time.sleep(0.03)
            except OSError:
                pass

        workers = [threading.Thread(target=trickle, args=(client,)) for client in clients]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join(1)
            self.assertFalse(worker.is_alive())
        for client in clients:
            client.close()

        started = time.monotonic()
        response = self.admin_get()
        self.assertLess(time.monotonic() - started, 0.8)
        self.assertEqual(200, _status(response))

    def test_trickle_and_short_bodies_are_bounded(self) -> None:
        connection = socket.create_connection(("127.0.0.1", self.api_port), timeout=1)
        headers = (
            f"POST {API_PREFIX}/pair/request HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{self.api_port}\r\n"
            "Content-Type: application/json\r\nContent-Length: 100\r\n\r\n"
        ).encode("ascii")
        connection.sendall(headers)
        started = time.monotonic()
        try:
            for _index in range(20):
                connection.sendall(b"x")
                time.sleep(0.04)
        except OSError:
            pass
        self.assertLess(time.monotonic() - started, 0.8)
        connection.close()
        self.assertEqual(200, _status(self.admin_get()))

        short = (
            f"POST {API_PREFIX}/pair/request HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{self.api_port}\r\n"
            "Content-Type: application/json\r\nContent-Length: 10\r\n\r\n{}"
        ).encode("ascii")
        self.assertEqual(400, _status(self.request(self.api_port, short)))

    def test_admin_and_api_share_strict_body_framing(self) -> None:
        api_path = API_PREFIX + "/pair/request"
        cases = {
            "missing": "",
            "invalid": "Content-Length: nope\r\n",
            "duplicate": "Content-Length: 2\r\nContent-Length: 2\r\n",
            "transfer": "Transfer-Encoding: chunked\r\n",
            "conflict": "Content-Length: 2\r\nTransfer-Encoding: chunked\r\n",
        }
        for name, framing in cases.items():
            with self.subTest(name=name, surface="api"):
                request = (
                    f"POST {api_path} HTTP/1.1\r\nHost: 127.0.0.1:{self.api_port}\r\n"
                    f"Content-Type: application/json\r\n{framing}\r\n{{}}"
                ).encode("ascii")
                self.assertEqual(400, _status(self.request(self.api_port, request)))
            with self.subTest(name=name, surface="admin"):
                request = (
                    f"POST /admin/discovery/refresh HTTP/1.1\r\n"
                    f"Host: 127.0.0.1:{self.admin_port}\r\n"
                    f"Origin: http://127.0.0.1:{self.admin_port}\r\n"
                    f"Content-Type: application/x-www-form-urlencoded\r\n{framing}\r\nxx"
                ).encode("ascii")
                self.assertEqual(400, _status(self.request(self.admin_port, request)))

    def test_complete_body_cancels_input_deadline_before_service_work(self) -> None:
        instance = self.service.add_instance(
            "hermes", "Fixture", "http://127.0.0.1:8642", "fixture-key")
        self.service.store.update_instance_check(
            instance["instance_id"], health="ready", detail="ready",
            features={"run_submission": True, "run_status": True, "run_stop": True},
            executable=True)
        code, _ = self.service.store.create_pair_code(
            instance["instance_id"], "https://computer.example.ts.net")
        original = self.service.request_pair

        def delayed(payload):
            time.sleep(0.4)
            return original(payload)

        self.service.request_pair = delayed
        body = json.dumps({
            "code": code,
            "device_id": "deadline-device",
            "device_name": "Deadline Tablet",
        }).encode("utf-8")
        request = (
            f"POST {API_PREFIX}/pair/request HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{self.api_port}\r\n"
            "Content-Type: application/json\r\n"
            f"Content-Length: {len(body)}\r\n\r\n"
        ).encode("ascii") + body
        started = time.monotonic()
        response = self.request(self.api_port, request)
        self.assertGreater(time.monotonic() - started, 0.35)
        self.assertEqual(202, _status(response))

    def test_response_deadline_releases_worker_when_client_does_not_read(self) -> None:
        server = _Server(
            ("127.0.0.1", 0),
            _LargeResponseHandler,
            max_workers=1,
            input_deadline_seconds=0.25,
            response_deadline_seconds=0.2,
            socket_timeout_seconds=1,
            file_response_max_seconds=0.2,
        )
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            blocked = socket.create_connection(server.server_address, timeout=1)
            blocked.sendall(b"GET /large HTTP/1.1\r\nHost: localhost\r\n\r\n")
            time.sleep(0.4)
            response = self.request(
                server.server_address[1],
                b"GET /small HTTP/1.1\r\nHost: localhost\r\n\r\n",
            )
            self.assertEqual(200, _status(response))
            blocked.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_artifact_budget_scales_with_size_and_has_hard_cap(self) -> None:
        server = _Server(("127.0.0.1", 0), _DelayedBodyHandler)
        try:
            small = server.file_response_deadline(0)
            largest = server.file_response_deadline(100 * 1024 * 1024)
            self.assertEqual(20.0, small)
            self.assertGreater(largest, 500.0)
            self.assertLessEqual(largest, 600.0)
            with self.assertRaises(ValidationError):
                server.file_response_deadline(100 * 1024 * 1024 + 1)
        finally:
            server.server_close()

    def test_expired_input_cannot_dispatch_even_when_complete_body_was_buffered(self) -> None:
        server = _Server(
            ("127.0.0.1", 0),
            _DelayedBodyHandler,
            input_deadline_seconds=0.08,
            response_deadline_seconds=0.2,
            socket_timeout_seconds=1,
        )
        server.dispatched = threading.Event()
        server.finished = threading.Event()
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with socket.create_connection(server.server_address, timeout=1) as connection:
                connection.sendall(
                    b"POST / HTTP/1.1\r\nHost: localhost\r\n"
                    b"Content-Length: 2\r\n\r\n{}")
                self.assertTrue(server.finished.wait(1))
            self.assertFalse(server.dispatched.is_set())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_timer_and_worker_start_failures_release_all_resources(self) -> None:
        server = _Server(("127.0.0.1", 0), _DelayedBodyHandler, max_workers=1)
        try:
            client = socket.create_connection(server.server_address, timeout=1)
            with mock.patch.object(_ConnectionDeadlines, "start",
                                   side_effect=RuntimeError("timer unavailable")):
                with self.assertRaisesRegex(RuntimeError, "timer unavailable"):
                    server.get_request()
            client.settimeout(1)
            self.assertEqual(b"", client.recv(1))
            client.close()
            self.assertEqual({}, server._deadlines)

            accepted, peer = socket.socketpair()
            deadlines = _ConnectionDeadlines(accepted, 5, 5, 5)
            deadlines.start()
            with server._deadline_lock:
                server._deadlines[accepted] = deadlines
            with mock.patch.object(threading.Thread, "start",
                                   side_effect=RuntimeError("worker unavailable")):
                with self.assertRaisesRegex(RuntimeError, "worker unavailable"):
                    server.process_request(accepted, ("local", 0))
            self.assertEqual({}, server._deadlines)
            self.assertTrue(server._workers.acquire(blocking=False))
            server._workers.release()
            peer.close()
        finally:
            server.server_close()

    def test_artifact_stream_closes_when_client_disconnects_during_headers(self) -> None:
        stream = io.BytesIO(b"fixture artifact")
        entered = threading.Event()
        release = threading.Event()

        def artifact(*_args):
            entered.set()
            release.wait(1)
            return stream, {
                "size_bytes": len(stream.getvalue()),
                "media_type": "application/octet-stream",
                "name": "fixture.bin",
                "sha256": "0" * 64,
            }

        self.service.get_artifact = artifact
        connection = socket.create_connection(("127.0.0.1", self.api_port), timeout=1)
        path = API_PREFIX + "/agents/fixture/runs/task/artifacts/artifact"
        connection.sendall(
            (f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{self.api_port}\r\n"
             "Authorization: Bearer fixture-token\r\n\r\n").encode("ascii"))
        self.assertTrue(entered.wait(1))
        connection.setsockopt(
            socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        connection.close()
        release.set()
        deadline = time.monotonic() + 1
        while not stream.closed and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(stream.closed)


if __name__ == "__main__":
    unittest.main()
