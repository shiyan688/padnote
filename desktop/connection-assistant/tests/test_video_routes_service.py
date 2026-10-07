from __future__ import annotations

import base64
import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from padnote_assistant.bridge import BridgeService
from padnote_assistant.bundles import input_snapshot_digest, prepare_task_directory
from padnote_assistant.security import canonical_json
from padnote_assistant.state import AuthorizationError, ConflictError
from padnote_assistant.video_preview import binding_from_submission


ROOT = Path(__file__).resolve().parents[1]
ANDROID_BUNDLE = ROOT / "tests/fixtures/android-video-task.zip"
LESSON_HASH = "a" * 64
REVIEW_HASH = "b" * 64


def png_1x1() -> bytes:
    import struct
    import zlib

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00\x10\x20\x30\xff"))
            + chunk(b"IEND", b""))


class StubVideoWorker:
    def __init__(self, projection: dict):
        self.projection = projection
        self.calls: list[tuple[str, str]] = []

    def review(self, server_task_id: str, worker_task_id: str) -> dict:
        self.calls.append((server_task_id, worker_task_id))
        return self.projection


class VideoRouteServiceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-video-service-")
        self.addCleanup(self.temp.cleanup)
        self.service = BridgeService(Path(self.temp.name))
        self.addCleanup(lambda: self.service.close())
        self.instance = self.service.add_instance(
            "hermes", "Fixture Hermes", "http://127.0.0.1:8642", "fixture-key")
        self.service.store.update_instance_check(
            self.instance["instance_id"], health="ready", detail="ready",
            features={"run_submission": True, "run_status": True}, executable=True)
        self.token, self.connection = self._pair("device-a")

        raw_bundle = ANDROID_BUNDLE.read_bytes()
        with zipfile.ZipFile(ANDROID_BUNDLE) as archive:
            request = json.loads(archive.read("request.json"))
        self.payload = {
            "client_task_id": "service-video-review-1",
            "title": "Fixture video review",
            "input": "Please prepare a review fixture",
            "source": {"note_id": request["source"]["note_id"],
                       "note_revision": request["source"]["note_revision"]},
            "bundle_base64": base64.b64encode(raw_bundle).decode("ascii"),
            "bundle_sha256": hashlib.sha256(raw_bundle).hexdigest(),
        }
        run, _created = self.service.store.create_or_get_run(
            self.connection, self.payload["client_task_id"], self.payload)
        self.task_id = run["task_id"]
        self.task_root = prepare_task_directory(
            self.service.store.tasks_dir, self.task_id, self.payload,
            self.payload["bundle_base64"], self.payload["bundle_sha256"])
        self.binding = binding_from_submission(self.payload)
        assert self.binding is not None

        self.preview = png_1x1()
        preview_hash = hashlib.sha256(self.preview).hexdigest()
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
                "id": "intro", "learning_objective": "Introduce the idea",
                "narration": "A short introduction.", "screen_text": ["Introduction"],
                "visual_kind": "title",
                "preview": {"path": "storyboard-intro.png", "media_type": "image/png",
                            "size_bytes": len(self.preview), "sha256": preview_hash,
                            "width": 1, "height": 1},
            }],
        }
        state = {
            "schema_version": "1.0", "task_id": self.binding["worker_task_id"],
            "request_sha256": self.binding["request_sha256"],
            "status": self.projection["status"], "phase": "awaiting_approval",
            "updated_at": "2026-09-27T12:13:14.123Z", "event_cursor": 7, "revision": 1,
            "lesson_ir_sha256": LESSON_HASH, "review_sha256": REVIEW_HASH,
        }
        (self.task_root / "work/task-state.json").write_bytes(canonical_json(state) + b"\n")
        (self.task_root / "output/storyboard-intro.png").write_bytes(self.preview)
        self.service.store.update_run(
            self.task_id, status="completed", upstream_run_id="fixture-upstream-run",
            session_id="fixture-session",
            source_snapshot_digest=input_snapshot_digest(self.task_root / "input"),
            video_binding=self.binding)
        self.worker = StubVideoWorker(self.projection)
        self.service.video_worker = self.worker

    def _pair(self, device_id: str) -> tuple[str, dict]:
        code, _ = self.service.store.create_pair_code(
            self.instance["instance_id"], "https://computer.example.ts.net")
        request = self.service.store.request_pair(code, device_id, "Fixture Tablet")
        self.service.store.decide_pair(request["request_id"], True)
        _, claim = self.service.store.claim_pair(request["request_id"], request["poll_token"])
        token = claim["connections"][0]["token"]
        return token, self.service.store.authenticate(self.instance["instance_id"], token)

    def test_review_is_authorized_bound_to_original_bundle_and_persists_only_snapshot(self) -> None:
        before = self.service.store.owned_run(self.connection, self.task_id)
        original_result = {key: before[key] for key in ("status", "output", "artifacts", "approval")}
        review = self.service.get_video_review(
            self.instance["instance_id"], self.token, self.task_id)
        self.assertEqual(review["task_id"], self.task_id)
        self.assertEqual(review["worker_task_id"], self.binding["worker_task_id"])
        self.assertNotIn("path", review["scenes"][0]["preview"])
        self.assertEqual(self.worker.calls, [(self.task_id, self.binding["worker_task_id"])])
        after = self.service.store.owned_run(self.connection, self.task_id)
        self.assertEqual({key: after[key] for key in original_result}, original_result)
        self.assertIn("narration", self.projection["scenes"][0])
        self.assertNotIn("narration", canonical_json(after["video_review_snapshot"]).decode())

        # A different device cannot use the same server task ID to trigger a worker call.
        other_token, _other_connection = self._pair("device-b")
        calls_before = list(self.worker.calls)
        with self.assertRaises(AuthorizationError):
            self.service.get_video_review(self.instance["instance_id"], other_token, self.task_id)
        with self.assertRaises(AuthorizationError):
            self.service.get_video_review("another-instance", self.token, self.task_id)
        self.assertEqual(self.worker.calls, calls_before)

    def test_cached_preview_is_served_without_worker_and_rejects_stale_or_changed_source(self) -> None:
        review = self.service.get_video_review(
            self.instance["instance_id"], self.token, self.task_id)
        preview_id = review["scenes"][0]["preview"]["id"]
        calls_before = list(self.worker.calls)
        # Reopen from durable state with default (unconfigured) worker settings.
        self.service.close()
        self.service = BridgeService(Path(self.temp.name))
        self.assertIsNone(self.service.video_worker)
        stream, item = self.service.get_video_preview(
            self.instance["instance_id"], self.token, self.task_id, preview_id)
        self.assertEqual(stream.read(), self.preview)
        self.assertEqual(item["media_type"], "image/png")
        self.assertEqual(self.worker.calls, calls_before)

        state_path = self.task_root / "work/task-state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["event_cursor"] += 1
        state_path.write_bytes(canonical_json(state) + b"\n")
        with self.assertRaises(ConflictError):
            self.service.get_video_preview(
                self.instance["instance_id"], self.token, self.task_id, preview_id)

    def test_legacy_unbound_and_source_changed_tasks_fail_before_worker(self) -> None:
        legacy_payload = {"input": "legacy video task", "source": {"note_id": "legacy", "note_revision": 1}}
        legacy, _created = self.service.store.create_or_get_run(
            self.connection, "legacy-video-task", legacy_payload)
        legacy_root = prepare_task_directory(
            self.service.store.tasks_dir, legacy["task_id"], legacy_payload, None, None)
        self.service.store.update_run(
            legacy["task_id"], source_snapshot_digest=input_snapshot_digest(legacy_root / "input"))
        with self.assertRaises(ConflictError):
            self.service.get_video_review(self.instance["instance_id"], self.token, legacy["task_id"])
        self.assertEqual(self.worker.calls, [])

        source_file = self.task_root / "input/content.md"
        source_file.write_bytes(source_file.read_bytes() + b"\nchanged")
        with self.assertRaises(ConflictError):
            self.service.get_video_review(self.instance["instance_id"], self.token, self.task_id)
        self.assertEqual(self.worker.calls, [])

    def test_revoked_connection_and_unknown_preview_do_not_reach_worker(self) -> None:
        self.service.get_video_review(self.instance["instance_id"], self.token, self.task_id)
        calls_before = list(self.worker.calls)
        with self.assertRaises(ConflictError):
            self.service.get_video_preview(
                self.instance["instance_id"], self.token, self.task_id, "f" * 64)
        self.service.revoke_connection(self.connection["connection_id"])
        with self.assertRaises(AuthorizationError):
            self.service.get_video_review(self.instance["instance_id"], self.token, self.task_id)
        self.assertEqual(self.worker.calls, calls_before)

    def test_binding_is_immutable_and_followup_run_does_not_inherit_it(self) -> None:
        with self.assertRaises(ConflictError):
            self.service.store.update_run(self.task_id, video_binding={
                "worker_task_id": self.binding["worker_task_id"], "request_sha256": "c" * 64})
        followup, _ = self.service.store.create_or_get_run(
            self.connection, "service-video-followup", {
                "input": "followup", "source": self.payload["source"]},
            parent_task_id=self.task_id)
        self.assertIsNone(followup["video_binding"])
        self.assertIsNone(followup["video_review_snapshot"])

    def test_submission_persists_binding_before_calling_hermes(self) -> None:
        payload = dict(self.payload, client_task_id="video-submit-order")
        clean = self.service._validate_run_payload(payload, payload["client_task_id"])
        expected = binding_from_submission(clean)
        observed: list[dict] = []

        class HermesStub:
            def create_run(inner_self, _input: dict, _key: str, *, session_id=None) -> dict:
                saved = self.service.store.find_idempotent_run(
                    self.connection, clean["client_task_id"], clean)
                observed.append(saved["video_binding"])
                request_path = self.service.store.tasks_dir / saved["task_id"] / "request.json"
                request_path.write_bytes(request_path.read_bytes() + b" ")
                return {"id": "fixture-submitted-run", "status": "running"}

        with mock.patch.object(self.service, "_hermes", return_value=HermesStub()):
            self.service.submit_run(
                self.instance["instance_id"], self.token, payload["client_task_id"], payload)
        self.assertEqual(observed, [expected])
        submitted = self.service.store.find_idempotent_run(
            self.connection, clean["client_task_id"], clean)
        self.assertEqual(submitted["video_binding"], expected)
        with self.assertRaises(ConflictError):
            self.service.get_video_review(
                self.instance["instance_id"], self.token, submitted["task_id"])


if __name__ == "__main__":
    unittest.main()
