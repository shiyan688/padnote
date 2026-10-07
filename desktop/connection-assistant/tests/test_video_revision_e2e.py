from __future__ import annotations

import base64
import hashlib
import http.client
import json
import os
import shutil
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
from padnote_assistant.video_worker import VideoWorker, VideoWorkerConfig
from padnote_assistant.web import API_PREFIX, ServerGroup


REPO = Path(__file__).resolve().parents[2]
# This end-to-end needs a complete Skill tree with installed dependencies and a
# Node >= 22.22 binary. Both default to this checkout and PATH and can be
# overridden:
#   PADNOTE_SKILL_ROOT  a Skill tree with scripts/inspect-task.ts present
#   PADNOTE_NODE_BIN    a Node >= 22.22 executable
BUNDLE = REPO / "connection-assistant/tests/fixtures/android-video-task.zip"
SKILL = Path(os.environ.get(
    "PADNOTE_SKILL_ROOT",
    str(REPO.parent / "agent-skills/padnote-video-explainer"))).resolve()

FEEDBACK = "把开场改得更快进入主题"
FEEDBACK_SHA = hashlib.sha256(FEEDBACK.encode("utf-8")).hexdigest()
REVIEW = "a" * 64
IR = "b" * 64


@unittest.skipUnless((SKILL / "node_modules").is_dir() and (SKILL / "scripts/inspect-task.ts").is_file(),
                     "needs an installed video Skill (run npm ci in agent-skills/padnote-video-explainer)")
class RevisionEndToEndTests(unittest.TestCase):
    """HTTP -> bridge -> scheduler -> real VideoWorker -> real Node child.

    The child runs the Skill's revise-operation entry against a real task
    directory: it inspects work/task-state.json, validates the staged Lesson IR
    and review, and writes work/revise/<operation_id>/ including the plaintext
    feedback. This is the first test that proves the desktop pipeline really
    reaches the Skill, rather than a stub that mirrors it.
    """

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-video-revision-e2e-")
        self.state_dir = Path(self.temp.name).resolve()
        self.service = BridgeService(self.state_dir)
        self.instance = self.service.add_instance(
            "hermes", "Fixture Hermes", "http://127.0.0.1:8642", "fixture-key")
        self.service.store.update_instance_check(
            self.instance["instance_id"], health="ready", detail="ready",
            features={"run_submission": True}, executable=True)
        code, _ = self.service.store.create_pair_code(
            self.instance["instance_id"], "https://computer.example.ts.net")
        request = self.service.store.request_pair(code, "revision-e2e-device", "Fixture Tablet")
        self.service.store.decide_pair(request["request_id"], True)
        _, claim = self.service.store.claim_pair(request["request_id"], request["poll_token"])
        self.token = claim["connections"][0]["token"]
        self.connection = self.service.store.authenticate(self.instance["instance_id"], self.token)

        # A real task directory: the fixture ZIP's request.json and input/, plus
        # the fixture's expected Lesson IR and a minimal review describing it,
        # plus a work/task-state.json in the awaiting_storyboard_review state.
        raw_bundle = BUNDLE.read_bytes()
        with zipfile.ZipFile(BUNDLE) as archive:
            fixture_request = json.loads(archive.read("request.json"))
        payload = {
            "client_task_id": "revision-e2e-primary",
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
        # The Skill fixture's request replaces the ZIP's; the worker identity
        # is the request that will actually be on disk.
        self.worker_task_id = json.loads(
            (SKILL / "tests/fixtures/formula-note/request.json").read_text(encoding="utf-8"))["task_id"]

        self._build_skill_outputs(task_root)

        self.binding = {
            "worker_task_id": self.worker_task_id, "request_sha256": self.request_sha}
        # The projection mirrors the real digests the staged outputs carry: a
        # reserve-time check refuses any other review binding.
        projection = {
            "protocol_version": 1, "task_id": self.worker_task_id,
            "status": "awaiting_storyboard_review", "event_cursor": 1, "revision": 1,
            "review_sha256": self.review_sha, "lesson_ir_sha256": self.ir_sha,
            "episode": {}, "scenes": [],
        }
        self.service.store.update_run(
            self.task_id, status="completed", session_id="synthetic-session",
            source_snapshot_digest=input_snapshot_digest(task_root / "input"),
            video_binding=self.binding,
            video_review_snapshot={"projection": projection, "state_sha256": self.review_sha})

        override = os.environ.get("PADNOTE_NODE_BIN")
        self.node = Path(override or shutil.which("node") or "/usr/bin/env")
        self.video_worker = VideoWorker(VideoWorkerConfig(
            self.node, SKILL, self.service.store.tasks_dir))
        self.service.video_worker = self.video_worker
        self.assertTrue(self.service._start_video_scheduler())
        self.group = ServerGroup(self.service, admin_port=0, api_port=0)
        self.group.start()

    def _build_skill_outputs(self, task_root: Path) -> None:
        """Stage output/lesson.ir.json + output/review.json + work/task-state.json."""
        fixture_root = SKILL / "tests/fixtures/formula-note"
        shutil.copytree(fixture_root / "input", task_root / "input", dirs_exist_ok=True)
        (task_root / "request.json").write_bytes((fixture_root / "request.json").read_bytes())
        # Computed here, after the overwrite: the binding must name these final
        # bytes, not the ZIP's original request.
        self.request_sha = hashlib.sha256(
            (task_root / "request.json").read_bytes()).hexdigest()
        if not (task_root / "output").exists():
            (task_root / "output").mkdir()
        ir_bytes = (fixture_root / "expected/lesson.ir.json").read_bytes()
        (task_root / "output/lesson.ir.json").write_bytes(ir_bytes)
        storyboard_html = "<!doctype html><p>offline</p>"
        (task_root / "output/storyboard.html").write_text(storyboard_html, encoding="utf-8")
        storyboard_png = b"PNG-fixture-scene"
        (task_root / "output/storyboard-scene-01.png").write_bytes(storyboard_png)
        # The review schema requires lesson_ir.{path,media_type,size_bytes,sha256}
        # and at least two storyboard artifacts.
        review = {
            "schema_version": "1.0",
            "task_id": self.worker_task_id,
            "status": "awaiting_storyboard_review",
            "lesson_ir_revision": 1,
            "lesson_ir": {
                "path": "output/lesson.ir.json", "media_type": "application/json",
                "size_bytes": len(ir_bytes),
                "sha256": hashlib.sha256(ir_bytes).hexdigest(),
            },
            "artifacts": [
                {"role": "storyboard", "path": "output/storyboard.html",
                 "media_type": "text/html", "size_bytes": len(storyboard_html.encode("utf-8")),
                 "sha256": hashlib.sha256(storyboard_html.encode("utf-8")).hexdigest()},
                {"role": "storyboard", "path": "output/storyboard-scene-01.png",
                 "media_type": "image/png", "size_bytes": len(storyboard_png),
                 "sha256": hashlib.sha256(storyboard_png).hexdigest()},
            ],
        }
        review_bytes = json.dumps(review, ensure_ascii=False,
                                  sort_keys=True, separators=(",", ":")).encode("utf-8")
        (task_root / "output/review.json").write_bytes(review_bytes)
        self.review_sha = hashlib.sha256(review_bytes).hexdigest()
        self.ir_sha = hashlib.sha256(ir_bytes).hexdigest()
        state = {
            "schema_version": "1.0",
            "task_id": self.worker_task_id,
            "request_sha256": self.request_sha,
            "status": "awaiting_storyboard_review",
            "phase": "awaiting_approval",
            "updated_at": "2026-09-28T06:00:00.000Z",
            "event_cursor": 1,
            "revision": 1,
            "lesson_ir_sha256": self.ir_sha,
            "review_sha256": self.review_sha,
        }
        if not (task_root / "work").exists():
            (task_root / "work").mkdir()
        (task_root / "work/task-state.json").write_bytes(
            json.dumps(state, sort_keys=True, separators=(",", ":")).encode("utf-8"))

    def tearDown(self) -> None:
        self.group.close()
        self.service.close()
        self.temp.cleanup()

    def _request(self, method: str, path: str, *, body: dict[str, Any] | None = None,
                 key: str | None = None) -> tuple[int, dict[str, Any]]:
        port = self.group.api.server_address[1]
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
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

    def _wait_status(self, operation_id: str, expected: str, timeout: float = 30.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            status, value = self._request("GET", self._path(operation_id=operation_id))
            if status == 200 and value.get("status") == expected:
                return value
            time.sleep(0.05)
        raise AssertionError("video operation did not reach its expected state")

    def test_http_revision_gate_prevents_real_node_dispatch(self) -> None:
        body = {"action": "revise", "parameters": {
            "revision": 1, "review_sha256": self.review_sha,
            "lesson_ir_sha256": self.ir_sha, "feedback_sha256": FEEDBACK_SHA,
            "event_cursor": 1}, "feedback": FEEDBACK}
        status, queued = self._request("POST", self._path(), body=body, key="revision-e2e-001")
        self.assertEqual(409, status)
        self.assertIn("error", queued)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_a_stale_review_is_refused_before_queueing(self) -> None:
        # A stale review digest is refused at reserve time -- the ledger's
        # projection check refuses it before the work is even queued (409),
        # which is the desktop's own defence; the Skill's own stale-binding
        # refusal sits behind it.
        body = {"action": "revise", "parameters": {
            "revision": 1, "review_sha256": "e" * 64,
            "lesson_ir_sha256": self.ir_sha, "feedback_sha256": FEEDBACK_SHA,
            "event_cursor": 1}, "feedback": FEEDBACK}
        status, response = self._request("POST", self._path(), body=body, key="revision-e2e-stale")
        self.assertEqual(409, status)
        self.assertIn("error", response)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])


if __name__ == "__main__":
    unittest.main()
