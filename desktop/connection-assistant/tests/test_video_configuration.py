from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from padnote_assistant import app
from padnote_assistant.bridge import BridgeService
from padnote_assistant.state import StateStore
from padnote_assistant.video_worker import VideoWorkerConfig, VideoWorkerError


class VideoConfigurationTests(unittest.TestCase):
    def make_install(self, root: Path) -> tuple[Path, Path]:
        node = root / "runtime" / "node"
        node.parent.mkdir(parents=True)
        node.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        node.chmod(0o755)

        skill = root / "skill"
        scripts = skill / "scripts"
        scripts.mkdir(parents=True)
        (skill / "package.json").write_text(json.dumps({
            "name": "padnote-video-explainer",
            "engines": {"node": ">=22.22.0"},
            "scripts": {"task:inspect": "node scripts/task-worker.ts inspect"},
        }), encoding="utf-8")
        (scripts / "task-worker.ts").write_text("// fixture\n", encoding="utf-8")
        return node.resolve(strict=True), skill.resolve(strict=True)

    def test_default_service_keeps_existing_behavior_without_worker_startup(self):
        with tempfile.TemporaryDirectory() as temporary:
            with mock.patch("padnote_assistant.bridge.VideoWorker") as worker_type, \
                    mock.patch("padnote_assistant.bridge.VideoWorkerConfig") as config_type:
                with BridgeService(Path(temporary) / "state") as service:
                    self.assertIsNone(service.video_worker)
                worker_type.assert_not_called()
                config_type.assert_not_called()

    def test_video_configuration_uses_state_store_tasks_directory_without_probe(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            node, skill = self.make_install(root)
            state = root / "state"
            with mock.patch("padnote_assistant.bridge.VideoWorker") as worker_type:
                worker_value = mock.Mock()
                worker_type.return_value = worker_value
                with BridgeService(state, video_node=node,
                                   video_skill_root=skill) as service:
                    self.assertIs(service.video_worker, worker_value)
                    worker_type.assert_called_once()
                    config = worker_type.call_args.args[0]
                    self.assertIsInstance(config, VideoWorkerConfig)
                    self.assertEqual(node, config.node_path)
                    self.assertEqual(skill, config.skill_root)
                    self.assertEqual(service.store.tasks_dir, config.tasks_root)
                    worker_value.diagnose.assert_not_called()

                    instance = service.add_instance(
                        "hermes", "Fixture", "http://127.0.0.1:8642", "fixture-key")
                    service.store.update_instance_check(
                        instance["instance_id"], health="ready", detail="ready",
                        features={"run_submission": True}, executable=True)
                    code, _ = service.store.create_pair_code(
                        instance["instance_id"], "https://computer.example.ts.net")
                    request = service.store.request_pair(code, "device-a", "Tablet")
                    service.store.decide_pair(request["request_id"], True)
                    _, claim = service.store.claim_pair(request["request_id"], request["poll_token"])
                    token = claim["connections"][0]["token"]
                    capability = service.capabilities(instance["instance_id"], token)
                    self.assertNotIn("video_ready", capability["features"])
                    self.assertNotIn("video_worker", capability["features"])
                    worker_value.diagnose.assert_not_called()

    def test_unpaired_video_paths_are_rejected_before_state_or_server_creation(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary) / "state"
            with self.assertRaises(VideoWorkerError):
                BridgeService(state, video_node=Path("/not/a/node"))
            self.assertFalse(state.exists())

            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as raised:
                app.main(["--state-dir", str(state), "--no-browser", "--video-node", "/bad/node"])
            self.assertEqual(2, raised.exception.code)
            self.assertIn("must be provided together", stderr.getvalue())
            self.assertFalse(state.exists())

    def test_invalid_worker_configuration_releases_state_ownership_and_opens_no_server(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            state = root / "state"
            nonexistent_node = root / "missing-node"
            nonexistent_skill = root / "missing-skill"
            stdout = io.StringIO()
            stderr = io.StringIO()
            with mock.patch("padnote_assistant.app.ServerGroup") as server_type, \
                    contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = app.main([
                    "--state-dir", str(state), "--no-browser",
                    "--video-node", str(nonexistent_node),
                    "--video-skill-root", str(nonexistent_skill),
                ])
            self.assertEqual(1, code)
            server_type.assert_not_called()
            self.assertIn("could not configure the optional video worker", stderr.getvalue())
            self.assertNotIn(str(nonexistent_node), stderr.getvalue())

            # A fresh owner can acquire the same canonical state directory after
            # BridgeService rejected the optional worker configuration.
            with StateStore(state):
                pass
            self.assertEqual("", stdout.getvalue())

    def test_explicit_empty_video_paths_do_not_become_default_disabled(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary) / "state"
            stderr = io.StringIO()
            with mock.patch("padnote_assistant.app.ServerGroup") as server_type, \
                    contextlib.redirect_stderr(stderr):
                code = app.main([
                    "--state-dir", str(state), "--no-browser",
                    "--video-node", "", "--video-skill-root", "",
                ])
            self.assertEqual(1, code)
            server_type.assert_not_called()
            self.assertIn("could not configure the optional video worker", stderr.getvalue())
            with StateStore(state):
                pass

    def test_bridge_rejects_one_video_option_before_acquiring_ownership(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary) / "state"
            with self.assertRaises(VideoWorkerError):
                BridgeService(state, video_skill_root=Path("/not/a/skill"))
            self.assertFalse(state.exists())
            with StateStore(state):
                pass


if __name__ == "__main__":
    unittest.main()
