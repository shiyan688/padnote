from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from padnote_assistant.video_worker import VideoWorker, VideoWorkerConfig, VideoWorkerError


REPO = Path(__file__).resolve().parents[3]
SKILL = REPO / "agent-skills/padnote-video-explainer"
SERVER_ID = "server-task-init"
WORKER_ID = "padnote-worker-init"


class VideoInitializeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.node = self.root / "node"
        self.node.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        self.node.chmod(0o755)
        self.tasks = self.root / "tasks"
        self.tasks.mkdir()
        self.task = self.tasks / SERVER_ID
        self.task.mkdir()
        self.request_bytes = json.dumps({"task_id": WORKER_ID, "schema_version": "1.0"},
                                        separators=(",", ":")).encode()
        (self.task / "request.json").write_bytes(self.request_bytes)
        self.digest = hashlib.sha256(self.request_bytes).hexdigest()
        self.worker = VideoWorker(VideoWorkerConfig(self.node, SKILL.resolve(), self.tasks))
        self.projection = {
            "protocol_version": 1, "task_id": WORKER_ID,
            "status": "initialized", "phase": "idle", "event_cursor": 1,
        }

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_initializes_with_exact_fixed_argv_and_full_inspection(self) -> None:
        deadlines: list[float] = []

        def capture_node(_deadline):
            deadlines.append(_deadline)

        def invoke(action, task_root, deadline, *options, operation):
            deadlines.append(deadline)
            self.assertEqual(action, "init-exact")
            self.assertEqual(task_root, self.task)
            self.assertEqual(options, ("--task-id", WORKER_ID, "--request-sha256", self.digest))
            self.assertEqual(operation, "init-exact")
            return self.projection

        with patch.object(self.worker, "_require_node_version", capture_node), \
                patch.object(self.worker, "_invoke_action", invoke):
            result = self.worker.initialize_exact(SERVER_ID, WORKER_ID, self.digest)
        self.assertEqual(result, self.projection)
        self.assertEqual(deadlines[0], deadlines[1])

    def test_bad_identity_digest_and_request_fail_before_worker(self) -> None:
        with patch.object(self.worker, "_invoke_action") as invoke:
            for worker_id, digest in (("wrong-worker", self.digest), (WORKER_ID, "a" * 64)):
                with self.subTest(worker_id=worker_id, digest=digest), \
                        self.assertRaises(VideoWorkerError) as caught:
                    self.worker.initialize_exact(SERVER_ID, worker_id, digest)
                self.assertFalse(caught.exception.unknown)
            (self.task / "request.json").write_bytes(b'{"task_id":"other"}')
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker.initialize_exact(SERVER_ID, WORKER_ID, self.digest)
            self.assertEqual(caught.exception.code, "invalid_request")
            self.assertFalse(caught.exception.unknown)
            invoke.assert_not_called()

    def test_request_symlink_is_rejected_before_worker(self) -> None:
        request = self.task / "request.json"
        request.unlink()
        target = self.root / "request-target.json"
        target.write_bytes(self.request_bytes)
        request.symlink_to(target)
        with patch.object(self.worker, "_invoke_action") as invoke, \
                self.assertRaises(VideoWorkerError) as caught:
            self.worker.initialize_exact(SERVER_ID, WORKER_ID, self.digest)
        self.assertFalse(caught.exception.unknown)
        invoke.assert_not_called()

    def test_cli_failure_timeout_and_invalid_projection_are_unknown(self) -> None:
        cases = (
            lambda: (_ for _ in ()).throw(VideoWorkerError("worker_timeout", "timed out")),
            lambda: (_ for _ in ()).throw(VideoWorkerError("worker_failed", "failed")),
            lambda: {"protocol_version": 1, "task_id": "other", "status": "initialized",
                     "phase": "idle", "event_cursor": 1},
        )
        for invoke_result in cases:
            with self.subTest(invoke_result=invoke_result), \
                    patch.object(self.worker, "_require_node_version"), \
                    patch.object(self.worker, "_invoke_action", side_effect=invoke_result), \
                    self.assertRaises(VideoWorkerError) as caught:
                self.worker.initialize_exact(SERVER_ID, WORKER_ID, self.digest)
            self.assertTrue(caught.exception.unknown)


if __name__ == "__main__":
    unittest.main()
