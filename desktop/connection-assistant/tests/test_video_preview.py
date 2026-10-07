from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import stat
import subprocess
import tempfile
import time
import unittest
import zipfile
from pathlib import Path
from typing import Any

from padnote_assistant.security import ValidationError, canonical_json
from padnote_assistant.state import ConflictError
from padnote_assistant.video_preview import (
    binding_from_submission,
    capture_review_snapshot,
    open_preview,
    preview_id,
    public_review,
    verify_request_binding,
)


WORKER_ID = "video-worker-fixture"
SERVER_ID = "server-video-task"
REQUEST = {
    "schema_version": "1.0",
    "task_type": "video.explain.v1",
    "task_id": WORKER_ID,
    "source": {"note_id": "note-fixture", "note_revision": 7},
}
REVIEW_HASH = "b" * 64
IR_HASH = "a" * 64


class VideoPreviewTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        (self.root / "work").mkdir()
        (self.root / "output").mkdir()
        self.request_raw = json.dumps(REQUEST, separators=(",", ":")).encode()
        (self.root / "request.json").write_bytes(self.request_raw)
        self.binding = self._binding_for(self.request_raw)
        self.png = _png(4, 3)
        (self.root / "output/storyboard-intro.png").write_bytes(self.png)
        self.state = {
            "schema_version": "1.0",
            "task_id": WORKER_ID,
            "request_sha256": self.binding["request_sha256"],
            "status": "awaiting_storyboard_review",
            "phase": "awaiting_approval",
            "updated_at": "2026-09-27T10:00:00.000Z",
            "event_cursor": 8,
            "revision": 2,
            "lesson_ir_sha256": IR_HASH,
            "review_sha256": REVIEW_HASH,
        }
        self.state_path = self.root / "work/task-state.json"
        self._write_state()
        self.projection = _projection()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _binding_for(self, request_raw: bytes) -> dict[str, str]:
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
            bundle.writestr("request.json", request_raw)
        raw = archive.getvalue()
        return binding_from_submission({
            "bundle_base64": base64.b64encode(raw).decode("ascii"),
            "bundle_sha256": hashlib.sha256(raw).hexdigest(),
        }) or {}

    def _write_state(self) -> None:
        self.state_path.write_bytes(canonical_json(self.state))

    def _snapshot(self) -> dict[str, Any]:
        return capture_review_snapshot(self.root, self.binding, self.projection)

    def test_binding_comes_from_original_zip_and_rejects_bundle_tampering(self) -> None:
        self.assertEqual(self.binding["worker_task_id"], WORKER_ID)
        self.assertEqual(self.binding["request_sha256"], hashlib.sha256(self.request_raw).hexdigest())
        self.assertIsNone(binding_from_submission({"input": "ordinary task"}))
        non_video = json.dumps({**REQUEST, "task_type": "text"}, separators=(",", ":")).encode()
        self.assertIsNone(binding_from_submission({
            "bundle_base64": base64.b64encode(_zip_request(non_video)).decode(),
            "bundle_sha256": hashlib.sha256(_zip_request(non_video)).hexdigest(),
        }))
        with self.assertRaises(ValidationError):
            binding_from_submission({"bundle_base64": "bm90LWEtemlw", "bundle_sha256": "0" * 64})

    def test_binding_accepts_uppercase_bundle_digest_and_writestr_permission_mode(self) -> None:
        raw = _zip_request(self.request_raw)
        result = binding_from_submission({
            "bundle_base64": base64.b64encode(raw).decode("ascii"),
            "bundle_sha256": hashlib.sha256(raw).hexdigest().upper(),
        })
        self.assertEqual(result, self.binding)

    def test_binding_rejects_unsupported_zip_compression_safely(self) -> None:
        raw = bytearray(_zip_request(self.request_raw))
        local_header = raw.index(b"PK\x03\x04")
        central_header = raw.index(b"PK\x01\x02")
        raw[local_header + 8:local_header + 10] = (99).to_bytes(2, "little")
        raw[central_header + 10:central_header + 12] = (99).to_bytes(2, "little")
        raw_bytes = bytes(raw)
        with self.assertRaises(ValidationError):
            binding_from_submission({
                "bundle_base64": base64.b64encode(raw_bytes).decode("ascii"),
                "bundle_sha256": hashlib.sha256(raw_bytes).hexdigest(),
            })

    def test_capture_keeps_only_bounded_metadata_and_public_review_hides_path(self) -> None:
        state_before = self.state_path.read_bytes()
        snapshot = self._snapshot()
        compact = snapshot["projection"]
        self.assertEqual(set(compact), {
            "task_id", "status", "event_cursor", "revision", "review_sha256",
            "lesson_ir_sha256", "scenes",
        })
        self.assertNotIn("narration", canonical_json(snapshot).decode())
        self.assertLessEqual(len(canonical_json(snapshot)), 64 * 1024)
        self.assertEqual(self.state_path.read_bytes(), state_before)
        public = public_review(SERVER_ID, self.projection)
        self.assertEqual(public["task_id"], SERVER_ID)
        self.assertEqual(public["worker_task_id"], WORKER_ID)
        self.assertEqual(public["object"], "padnote.video.review")
        preview = public["scenes"][0]["preview"]
        self.assertNotIn("path", preview)
        self.assertEqual(preview["id"], preview_id(self.projection, self.projection["scenes"][0]))

    def test_preview_is_bound_to_request_state_and_immutable_bytes(self) -> None:
        snapshot = self._snapshot()
        requested_id = preview_id(self.projection, self.projection["scenes"][0])
        stream, item = open_preview(self.root, self.binding, snapshot, requested_id)
        self.assertEqual(stream.read(), self.png)
        self.assertEqual(item, {
            "name": "storyboard-intro.png", "media_type": "image/png",
            "size_bytes": len(self.png), "sha256": hashlib.sha256(self.png).hexdigest(),
        })
        (self.root / "output/storyboard-intro.png").write_bytes(b"modified after open")
        stream.seek(0)
        self.assertEqual(stream.read(), self.png)

    def test_open_rejects_unknown_id_changed_state_and_same_length_request_edit(self) -> None:
        snapshot = self._snapshot()
        requested_id = preview_id(self.projection, self.projection["scenes"][0])
        with self.assertRaises(ConflictError):
            open_preview(self.root, self.binding, snapshot, "f" * 64)
        self.state["event_cursor"] += 1
        self._write_state()
        with self.assertRaises(ConflictError):
            open_preview(self.root, self.binding, snapshot, requested_id)
        self.state["event_cursor"] -= 1
        self._write_state()
        changed = self.request_raw.replace(b"video-worker-fixture", b"video-worker-fixturE")
        self.assertEqual(len(changed), len(self.request_raw))
        (self.root / "request.json").write_bytes(changed)
        with self.assertRaises(ConflictError):
            verify_request_binding(self.root, self.binding)
        with self.assertRaises(ConflictError):
            open_preview(self.root, self.binding, snapshot, requested_id)

    def test_open_rejects_png_hash_and_dimension_mismatch(self) -> None:
        snapshot = self._snapshot()
        requested_id = preview_id(self.projection, self.projection["scenes"][0])
        (self.root / "output/storyboard-intro.png").write_bytes(_png(5, 3))
        with self.assertRaises(ConflictError):
            open_preview(self.root, self.binding, snapshot, requested_id)

    def test_symlink_and_fifo_preview_are_rejected_without_blocking(self) -> None:
        path = self.root / "output/storyboard-intro.png"
        saved = self.root / "saved.png"
        path.rename(saved)
        try:
            path.symlink_to(saved)
            snapshot = self._snapshot()
            requested_id = preview_id(self.projection, self.projection["scenes"][0])
            with self.assertRaises(ValidationError):
                open_preview(self.root, self.binding, snapshot, requested_id)
            path.unlink()
            if Path("/usr/bin/mkfifo").exists():
                subprocess.run(["/usr/bin/mkfifo", str(path)], check=True, timeout=2)
                started = time.monotonic()
                with self.assertRaises(ValidationError):
                    open_preview(self.root, self.binding, snapshot, requested_id)
                self.assertLess(time.monotonic() - started, 1.0)
        finally:
            if path.exists() or path.is_symlink():
                path.unlink()
            saved.rename(path)

    def test_missing_or_corrupt_request_and_state_fail_closed(self) -> None:
        snapshot = self._snapshot()
        (self.root / "request.json").unlink()
        with self.assertRaises(ValidationError):
            open_preview(self.root, self.binding, snapshot, preview_id(self.projection, self.projection["scenes"][0]))
        (self.root / "request.json").write_bytes(self.request_raw)
        self.state_path.write_text("{bad json")
        with self.assertRaises(ValidationError):
            capture_review_snapshot(self.root, self.binding, self.projection)


def _zip_request(request: bytes) -> bytes:
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        bundle.writestr("request.json", request)
    return archive.getvalue()


def _projection() -> dict[str, Any]:
    return {
        "protocol_version": 1,
        "task_id": WORKER_ID,
        "status": "awaiting_storyboard_review",
        "event_cursor": 8,
        "revision": 2,
        "review_sha256": REVIEW_HASH,
        "lesson_ir_sha256": IR_HASH,
        "episode": {
            "title": "A Short Lesson", "audience": "Students",
            "learning_goal": "Understand the core idea", "language": "zh-CN",
        },
        "scenes": [{
            "id": "intro",
            "learning_objective": "Explain the core idea",
            "narration": "A short narration.",
            "screen_text": ["Core idea"],
            "visual_kind": "quantity_change",
            "preview": {
                "path": "storyboard-intro.png", "media_type": "image/png",
                "size_bytes": len(_png(4, 3)), "sha256": hashlib.sha256(_png(4, 3)).hexdigest(),
                "width": 4, "height": 3,
            },
        }],
    }


def _png(width: int, height: int) -> bytes:
    return b"".join((
        b"\x89PNG\r\n\x1a\n",
        (13).to_bytes(4, "big"), b"IHDR",
        width.to_bytes(4, "big"), height.to_bytes(4, "big"),
        bytes([8, 6, 0, 0, 0]), b"\x00\x00\x00\x00",
    ))


if __name__ == "__main__":
    unittest.main()
