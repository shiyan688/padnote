from __future__ import annotations

import json
import math
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from padnote_assistant.hermes import HermesClient, HermesError


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args) -> None:
        pass

    def _respond(self, payload: dict) -> None:
        time.sleep(self.server.delay)
        data = json.dumps(payload).encode("utf-8")
        self.send_response(202 if self.path == "/v1/runs" else 200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
            self.wfile.flush()
        except OSError:
            pass

    def do_POST(self) -> None:
        self.server.post_count += 1
        length = int(self.headers.get("Content-Length", "0"))
        if length:
            self.rfile.read(length)
        if self.path.endswith("/stop"):
            self._respond({"run_id": "run-fixture", "status": "stopping"})
        else:
            self._respond({"run_id": "run-fixture", "status": "running"})

    def do_GET(self) -> None:
        self._respond({"run_id": "run-fixture", "status": "running"})


class _Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self) -> None:
        super().__init__(("127.0.0.1", 0), _Handler)
        self.delay = 0.0
        self.post_count = 0
        self.thread = threading.Thread(target=self.serve_forever, daemon=True)
        self.thread.start()

    def close(self) -> None:
        self.shutdown()
        self.server_close()
        self.thread.join(2)


class SubmissionTimeoutTests(unittest.TestCase):
    def setUp(self) -> None:
        self.server = _Server()
        self.addCleanup(self.server.close)
        self.client = HermesClient(
            f"http://127.0.0.1:{self.server.server_port}", "synthetic-key",
            timeout=0.05, submission_timeout=0.5)

    def test_delayed_202_run_creation_succeeds_once(self) -> None:
        self.server.delay = 0.15
        result = self.client.create_run({"input": "synthetic"}, "synthetic-idempotency-key")
        self.assertEqual("run-fixture", result["run_id"])
        self.assertEqual(1, self.server.post_count)

    def test_get_and_stop_keep_short_deadline(self) -> None:
        self.server.delay = 0.15
        for action in (lambda: self.client.get_run("run-fixture"),
                       lambda: self.client.stop_run("run-fixture")):
            with self.subTest(action=action):
                with self.assertRaises(HermesError) as caught:
                    action()
                self.assertEqual("upstream_unreachable", caught.exception.code)
        self.assertEqual(1, self.server.post_count)

    def test_submission_deadline_is_uncertain_and_never_retries(self) -> None:
        self.server.delay = 0.15
        client = HermesClient(
            f"http://127.0.0.1:{self.server.server_port}", "synthetic-key",
            timeout=0.05, submission_timeout=0.05)
        with self.assertRaises(HermesError) as caught:
            client.create_run({"input": "synthetic"}, "synthetic-idempotency-key")
        self.assertTrue(caught.exception.uncertain)
        self.assertEqual(1, self.server.post_count)

    def test_submission_timeout_validation_and_none_inheritance(self) -> None:
        for value in (True, 0, -1, float("nan"), float("inf"), -float("inf")):
            with self.subTest(value=value), self.assertRaises(ValueError):
                HermesClient("http://127.0.0.1:1", None, timeout=0.1, submission_timeout=value)
        inherited = HermesClient("http://127.0.0.1:1", None, timeout=0.1)
        self.assertEqual(inherited.timeout, inherited.submission_timeout)
        self.assertTrue(math.isfinite(inherited.submission_timeout))


if __name__ == "__main__":
    unittest.main()
