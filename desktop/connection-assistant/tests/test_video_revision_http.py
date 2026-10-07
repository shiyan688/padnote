from __future__ import annotations

import base64
import hashlib
import http.client
import json
import tempfile
import threading
import time
import unittest
import zipfile
from pathlib import Path
from typing import Any

from padnote_assistant.bridge import BridgeService
from padnote_assistant.bundles import input_snapshot_digest, prepare_task_directory
from padnote_assistant.security import canonical_json
from padnote_assistant.video_preview import binding_from_submission
from padnote_assistant.video_worker import VideoWorkerError, _MAX_REVISE_FEEDBACK_BYTES
from padnote_assistant.web import API_PREFIX, ServerGroup


REPO = Path(__file__).resolve().parents[3]
BUNDLE = REPO / "desktop/connection-assistant/tests/fixtures/android-video-task.zip"

FEEDBACK = "把开场改得更快进入主题"
FEEDBACK_SHA = hashlib.sha256(FEEDBACK.encode("utf-8")).hexdigest()
REVIEW = "a" * 64
IR = "b" * 64

PARAMETERS = {
    "revision": 1,
    "review_sha256": REVIEW,
    "lesson_ir_sha256": IR,
    "feedback_sha256": FEEDBACK_SHA,
    "event_cursor": 1,
}


class StagingStubWorker:
    """Receives the staged revision call and records the exact plaintext."""

    def __init__(self) -> None:
        self.entered = threading.Event()
        self.calls: list[dict[str, Any]] = []
        self.mode = "success"

    def stage_revision(self, server_task_id, expected_worker_task_id, expected_request_sha256,
                       revision, event_cursor, review_sha256, lesson_ir_sha256, feedback,
                       expected_feedback_sha256, *, operation_id, attempt_id, payload_digest,
                       source_snapshot_sha256):
        self.calls.append({
            "worker_task_id": expected_worker_task_id,
            "revision": revision, "event_cursor": event_cursor,
            "feedback": feedback,
            "expected_feedback_sha256": expected_feedback_sha256,
            "operation_id": operation_id, "attempt_id": attempt_id,
        })
        self.entered.set()
        if self.mode == "fail":
            raise VideoWorkerError("invalid_request", "Video revision staging could not be verified")
        return {
            "schema_version": 1,
            "operation_id": operation_id, "attempt_id": attempt_id,
            "task_id": expected_worker_task_id, "action": "revise",
            "payload_digest": payload_digest,
            "source_snapshot_digest": source_snapshot_sha256,
            "request_sha256": expected_request_sha256,
            "input_event_cursor": event_cursor, "revision": revision,
            "review_sha256": review_sha256, "lesson_ir_sha256": lesson_ir_sha256,
            "feedback_sha256": expected_feedback_sha256,
            "candidate_path": f"work/revise/{operation_id}/candidate-lesson-ir.json",
        }


class RevisionHTTPTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-video-revision-http-")
        self.state_dir = Path(self.temp.name).resolve()
        self.service = BridgeService(self.state_dir)
        self.instance = self.service.add_instance(
            "hermes", "Fixture Hermes", "http://127.0.0.1:8642", "fixture-key")
        self.service.store.update_instance_check(
            self.instance["instance_id"], health="ready", detail="ready",
            features={"run_submission": True}, executable=True)
        code, _ = self.service.store.create_pair_code(
            self.instance["instance_id"], "https://computer.example.ts.net")
        request = self.service.store.request_pair(code, "revision-http-device", "Fixture Tablet")
        self.service.store.decide_pair(request["request_id"], True)
        _, claim = self.service.store.claim_pair(request["request_id"], request["poll_token"])
        self.token = claim["connections"][0]["token"]
        self.connection = self.service.store.authenticate(self.instance["instance_id"], self.token)

        raw_bundle = BUNDLE.read_bytes()
        with zipfile.ZipFile(BUNDLE) as archive:
            fixture_request = json.loads(archive.read("request.json"))
        payload = {
            "client_task_id": "revision-http-primary",
            "title": "Fixture video", "input": "Synthetic request, no Hermes call",
            "source": {"note_id": fixture_request["source"]["note_id"],
                       "note_revision": fixture_request["source"]["note_revision"]},
            "bundle_base64": base64.b64encode(raw_bundle).decode("ascii"),
            "bundle_sha256": hashlib.sha256(raw_bundle).hexdigest(),
        }
        run, _created = self.service.store.create_or_get_run(
            self.connection, payload["client_task_id"], payload)
        self.task_id = run["task_id"]
        task_root = prepare_task_directory(
            self.service.store.tasks_dir, self.task_id, payload,
            payload["bundle_base64"], payload["bundle_sha256"])
        self.binding = binding_from_submission(payload)
        assert self.binding is not None
        projection = {
            "protocol_version": 1, "task_id": self.binding["worker_task_id"],
            "status": "awaiting_storyboard_review", "event_cursor": 1, "revision": 1,
            "review_sha256": REVIEW, "lesson_ir_sha256": IR, "episode": {}, "scenes": [],
        }
        self.service.store.update_run(
            self.task_id, status="completed", session_id="synthetic-session",
            source_snapshot_digest=input_snapshot_digest(task_root / "input"),
            video_binding=self.binding,
            video_review_snapshot={"projection": projection, "state_sha256": REVIEW})

        self.worker = StagingStubWorker()
        self.service.video_worker = self.worker
        self.assertTrue(self.service._start_video_scheduler())
        self.group = ServerGroup(self.service, admin_port=0, api_port=0)
        self.group.start()

    def tearDown(self) -> None:
        self.group.close()
        self.service.close()
        self.temp.cleanup()

    def _request(self, method: str, path: str, *, body: dict[str, Any] | None = None,
                 key: str | None = None) -> tuple[int, dict[str, Any]]:
        port = self.group.api.server_address[1]
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=4)
        headers = {"Host": f"127.0.0.1:{port}"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if key is not None:
            headers["Idempotency-Key"] = key
        connection.request(method, path, body=None if body is None else canonical_json(body),
                           headers={**headers, "Authorization": f"Bearer {self.token}"})
        response = connection.getresponse()
        value = json.loads(response.read().decode("utf-8"))
        status = response.status
        connection.close()
        return status, value

    def _path(self, operation_id: str | None = None, suffix: str = "") -> str:
        path = (f"{API_PREFIX}/agents/{self.instance['instance_id']}/runs/"
                f"{self.task_id}/video/operations")
        if operation_id:
            path += f"/{operation_id}"
        return path + suffix

    def _wait_status(self, operation_id: str, expected: str, timeout: float = 3.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            status, value = self._request("GET", self._path(operation_id=operation_id))
            if status == 200 and value.get("status") == expected:
                return value
            time.sleep(0.01)
        raise AssertionError("video operation did not reach its expected state")

    def test_authenticated_revision_request_is_closed_without_reservation(self) -> None:
        body = {"action": "revise", "parameters": dict(PARAMETERS), "feedback": FEEDBACK}
        status, response = self._request("POST", self._path(), body=body, key="revision-rt-001")
        self.assertEqual(409, status)
        self.assertIn("error", response)
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_http_rejects_a_mismatching_plaintext_without_queueing(self) -> None:
        body = {"action": "revise", "parameters": dict(PARAMETERS), "feedback": "完全不同"}
        status, response = self._request("POST", self._path(), body=body, key="revision-mismatch")
        self.assertEqual(400, status)
        self.assertIn("error", response)
        status, response = self._request("POST", self._path(), body={
            "action": "revise", "parameters": dict(PARAMETERS)})
        self.assertEqual(400, status)
        self.assertIn("error", response)
        status, response = self._request("POST", self._path(), body={
            "action": "storyboard", "parameters": {"revision": 1, "event_cursor": 1},
            "feedback": FEEDBACK})
        self.assertEqual(400, status)
        self.assertIn("error", response)
        status, response = self._request("POST", self._path(), body={
            "action": "revise", "parameters": {**PARAMETERS, "feedback": FEEDBACK},
            "feedback": FEEDBACK})
        self.assertEqual(400, status)
        self.assertIn("error", response)
        status, response = self._request("POST", self._path(), body={
            "action": "revise", "parameters": dict(PARAMETERS),
            "feedback": "x" * (_MAX_REVISE_FEEDBACK_BYTES + 1)})
        self.assertEqual(400, status)
        self.assertIn("error", response)
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_revision_remains_closed_even_when_worker_would_fail(self) -> None:
        self.worker.mode = "fail"
        body = {"action": "revise", "parameters": dict(PARAMETERS), "feedback": FEEDBACK}
        status, response = self._request("POST", self._path(), body=body, key="revision-fail-001")
        self.assertEqual(409, status)
        self.assertIn("error", response)
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])


if __name__ == "__main__":
    unittest.main()
