from __future__ import annotations

import hashlib
import http.client
import json
import struct
import tempfile
import unittest
import zlib
import base64
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
import zipfile

from padnote_assistant.bridge import BridgeService
from padnote_assistant.bundles import input_snapshot_digest, prepare_task_directory
from padnote_assistant.security import canonical_json
from padnote_assistant.video_preview import binding_from_submission
from padnote_assistant.video_worker import VideoWorker, VideoWorkerError
from padnote_assistant.web import API_PREFIX, ServerGroup


ROOT = Path(__file__).resolve().parents[1]
ANDROID_BUNDLE = ROOT / "tests/fixtures/android-video-task.zip"
LESSON_HASH = "a" * 64
REVIEW_HASH = "b" * 64


def png_1x1() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00\x10\x20\x30\xff"))
            + chunk(b"IEND", b""))


class _StubVideoWorker:
    def __init__(self, projection: dict) -> None:
        self.projection = projection
        self.calls = 0
        self.error: VideoWorkerError | None = None

    def review(self, _server_task_id: str, _worker_task_id: str) -> dict:
        self.calls += 1
        if self.error:
            raise self.error
        return self.projection


class VideoPreviewHTTPTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-video-preview-http-")
        self.service = BridgeService(Path(self.temp.name))
        self.instance = self.service.add_instance(
            "hermes", "Fixture Hermes", "http://127.0.0.1:8642", "fixture-key")
        self.service.store.update_instance_check(
            self.instance["instance_id"], health="ready", detail="ready",
            features={"run_submission": True, "run_status": True, "task_bundle": True}, executable=True)
        code, _ = self.service.store.create_pair_code(
            self.instance["instance_id"], "https://computer.example.ts.net")
        pair_request = self.service.store.request_pair(code, "fixture-device", "Fixture Tablet")
        self.service.store.decide_pair(pair_request["request_id"], True)
        _, claim = self.service.store.claim_pair(pair_request["request_id"], pair_request["poll_token"])
        self.token = claim["connections"][0]["token"]
        connection = self.service.store.authenticate(self.instance["instance_id"], self.token)

        raw_bundle = ANDROID_BUNDLE.read_bytes()
        with zipfile.ZipFile(ANDROID_BUNDLE) as archive:
            request = json.loads(archive.read("request.json"))
        self.payload = {
            "client_task_id": "http-video-review-1",
            "title": "Fixture video review",
            "input": "Please prepare a review fixture",
            "source": {"note_id": request["source"]["note_id"],
                       "note_revision": request["source"]["note_revision"]},
            "bundle_base64": base64.b64encode(raw_bundle).decode("ascii"),
            "bundle_sha256": hashlib.sha256(raw_bundle).hexdigest(),
        }
        run, _created = self.service.store.create_or_get_run(
            connection, self.payload["client_task_id"], self.payload)
        self.task_id = run["task_id"]
        self.task_root = prepare_task_directory(
            self.service.store.tasks_dir, self.task_id, self.payload,
            self.payload["bundle_base64"], self.payload["bundle_sha256"])
        self.binding = binding_from_submission(self.payload)
        assert self.binding is not None
        state = {
            "schema_version": "1.0",
            "task_id": self.binding["worker_task_id"],
            "request_sha256": self.binding["request_sha256"],
            "status": "awaiting_storyboard_review",
            "phase": "awaiting_approval",
            "updated_at": "2026-09-27T12:13:14.123Z",
            "event_cursor": 7,
            "revision": 1,
            "lesson_ir_sha256": LESSON_HASH,
            "review_sha256": REVIEW_HASH,
        }
        (self.task_root / "work/task-state.json").write_bytes(canonical_json(state) + b"\n")
        self.preview = png_1x1()
        preview_hash = hashlib.sha256(self.preview).hexdigest()
        (self.task_root / "output/storyboard-intro.png").write_bytes(self.preview)
        self.projection = {
            "protocol_version": 1,
            "task_id": self.binding["worker_task_id"],
            "status": "awaiting_storyboard_review",
            "event_cursor": 7,
            "revision": 1,
            "review_sha256": REVIEW_HASH,
            "lesson_ir_sha256": LESSON_HASH,
            "episode": {"title": "A Short Lesson", "audience": "Learners",
                        "learning_goal": "Understand the idea", "language": "en"},
            "scenes": [{
                "id": "intro",
                "learning_objective": "Introduce the idea",
                "narration": "A short introduction.",
                "screen_text": ["Introduction"],
                "visual_kind": "title",
                "preview": {"path": "storyboard-intro.png", "media_type": "image/png",
                            "size_bytes": len(self.preview), "sha256": preview_hash,
                            "width": 1, "height": 1},
            }],
        }
        self.worker = _StubVideoWorker(self.projection)
        self.service.video_worker = self.worker
        self.service.store.update_run(
            self.task_id, status="completed", upstream_run_id="fixture-upstream-run",
            session_id="fixture-session", source_snapshot_digest=input_snapshot_digest(self.task_root / "input"),
            video_binding=self.binding)
        self.group = ServerGroup(self.service, admin_port=0, api_port=0)
        self.group.start()

    def tearDown(self) -> None:
        self.group.close()
        self.temp.cleanup()

    def request(self, method: str, path: str, *, token: str | None = None) -> tuple[int, http.client.HTTPResponse, bytes]:
        connection = http.client.HTTPConnection("127.0.0.1", self.group.api.server_address[1], timeout=3)
        headers = {"Host": f"127.0.0.1:{self.group.api.server_address[1]}"}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        connection.request(method, path, headers=headers)
        response = connection.getresponse()
        body = response.read()
        status = response.status
        connection.close()
        return status, response, body

    def review_path(self) -> str:
        return f"{API_PREFIX}/agents/{self.instance['instance_id']}/runs/{self.task_id}/video/review"

    def test_review_route_uses_bridge_authorization_and_returns_projection_only(self) -> None:
        status, _response, body = self.request("GET", self.review_path(), token="wrong-token")
        self.assertEqual(status, 401)
        self.assertEqual(self.worker.calls, 0)

        status, response, body = self.request("GET", self.review_path(), token=self.token)
        self.assertEqual(status, 200)
        self.assertIn("application/json", response.getheader("Content-Type", ""))
        review = json.loads(body)
        self.assertEqual(review["object"], "padnote.video.review")
        self.assertEqual(review["task_id"], self.task_id)
        self.assertNotIn("storyboard.html", body.decode("utf-8"))
        self.assertNotIn("/output/", body.decode("utf-8"))
        self.assertEqual(self.worker.calls, 1)

    def test_preview_returns_exact_png_and_changed_snapshot_is_stale(self) -> None:
        status, _response, body = self.request("GET", self.review_path(), token=self.token)
        self.assertEqual(status, 200)
        preview_id = json.loads(body)["scenes"][0]["preview"]["id"]
        preview_path = (f"{API_PREFIX}/agents/{self.instance['instance_id']}/runs/{self.task_id}"
                        f"/video/previews/{preview_id}")

        status, response, body = self.request("GET", preview_path, token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(response.getheader("Content-Type"), "image/png")
        self.assertEqual(response.getheader("X-PadNote-SHA256"), hashlib.sha256(self.preview).hexdigest())
        self.assertEqual(body, self.preview)

        state_path = self.task_root / "work/task-state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["event_cursor"] += 1
        state_path.write_bytes(canonical_json(state) + b"\n")
        status, _response, _body = self.request("GET", preview_path, token=self.token)
        self.assertEqual(status, 409)

    def test_video_routes_reject_post_and_query_and_worker_errors_are_safe(self) -> None:
        status, _response, _body = self.request("POST", self.review_path(), token=self.token)
        self.assertEqual(status, 404)
        status, _response, _body = self.request("GET", self.review_path() + "?path=output/storyboard.html", token=self.token)
        self.assertEqual(status, 400)

        self.worker.error = VideoWorkerError("node_unavailable", "private stderr / token=secret")
        status, _response, body = self.request("GET", self.review_path(), token=self.token)
        self.assertEqual(status, 503)
        self.assertNotIn(b"secret", body)
        self.assertNotIn(b"private stderr", body)

    def test_review_uses_short_deadline_and_mutations_keep_action_deadline(self) -> None:
        worker = object.__new__(VideoWorker)
        worker._config = SimpleNamespace()
        worker_id = self.binding["worker_task_id"]
        deadlines: list[float] = []

        def invoke(action, _task_root, deadline, *_options, operation):
            deadlines.append(deadline)
            if action == "inspect":
                return {"protocol_version": 1, "task_id": worker_id,
                        "status": "initialized", "phase": "idle", "event_cursor": 1}
            if action == "storyboard":
                return {"task_id": worker_id, "status": "awaiting_storyboard_review",
                        "phase": "awaiting_approval", "event_cursor": 2}
            return self.projection

        with mock.patch.object(VideoWorker, "_verify_configuration"), \
                mock.patch.object(VideoWorker, "_task_root", return_value=Path("/tmp/task")), \
                mock.patch.object(VideoWorker, "_require_node_version", side_effect=lambda deadline: deadlines.append(deadline)), \
                mock.patch.object(VideoWorker, "_invoke_action", side_effect=invoke), \
                mock.patch("padnote_assistant.video_worker.time.monotonic", return_value=100.0):
            worker.review("server-fixture", worker_id)
            self.assertEqual(deadlines, [100.0 + VideoWorker.REVIEW_TIMEOUT_SECONDS] * 2)

            deadlines.clear()
            worker.storyboard("server-fixture", worker_id, 1)
            self.assertEqual(deadlines, [100.0 + VideoWorker.ACTION_TIMEOUT_SECONDS] * 3)

        self.worker.error = VideoWorkerError("worker_result_invalid", "private stderr / token=secret")
        status, _response, body = self.request("GET", self.review_path(), token=self.token)
        self.assertEqual(status, 409)
        self.assertNotIn(b"secret", body)
        self.assertNotIn(b"private stderr", body)


if __name__ == "__main__":
    unittest.main()
