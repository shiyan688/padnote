from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from padnote_assistant.bridge import BridgeService
from padnote_assistant.video_worker import (
    VideoWorker, VideoWorkerConfig, VideoWorkerError,
    _validate_runtime_diagnostics, not_configured_runtime_diagnostics,
)
from padnote_assistant.web import API_PREFIX, ApiHandler


REPO = Path(__file__).resolve().parents[3]
SKILL = REPO / "agent-skills/padnote-video-explainer"
FAKE_NODE = Path(__file__).parent / "fixtures/fake_video_node.py"


def valid_diagnostics() -> dict:
    names = ("worker_modules", "storyboard_browser", "render_browser", "ffmpeg", "ffprobe", "tts")
    values = {
        "worker_modules": {"status": "available", "reason": "modules_resolved"},
        "storyboard_browser": {"status": "missing", "reason": "executable_missing"},
        "render_browser": {"status": "unchecked", "reason": "configuration_unavailable"},
        "ffmpeg": {"status": "available", "reason": "installed_executable_found"},
        "ffprobe": {"status": "missing", "reason": "executable_missing"},
        "tts": {"status": "not_configured", "reason": "adapter_not_configured"},
    }
    return {"schema_version": "1.0", "runtime_verified": False, "video_ready": False,
            "checks": {name: values[name] for name in names}}


class _ProbeWorker:
    def __init__(self) -> None:
        self.calls = 0

    def runtime_diagnostics(self) -> dict:
        self.calls += 1
        return valid_diagnostics()


class VideoDiagnosticsWorkerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-video-diagnostics-")
        self.root = Path(self.temp.name).resolve()
        self.node = self.root / "node"
        self.node.write_bytes(FAKE_NODE.read_bytes())
        self.node.chmod(0o755)
        self.tasks = self.root / "tasks"
        self.tasks.mkdir()
        self.worker = VideoWorker(VideoWorkerConfig(self.node, SKILL.resolve(), self.tasks))

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_fixed_probe_uses_shared_deadline_controlled_environment_and_no_task_arguments(self) -> None:
        calls: list[tuple[list[str], Path, dict[str, str], float, str]] = []
        private_dirs: list[Path] = []
        output = json.dumps(valid_diagnostics()).encode()

        def run(argv, cwd, env, deadline, operation):
            calls.append((argv, cwd, dict(env), deadline, operation))
            if argv[-1] == "--version":
                return b"v22.22.0\n", b"", 0
            private_dir = Path(env["TMPDIR"])
            private_dirs.append(private_dir)
            (private_dir / "tsx-cache-fixture").write_text("cache")
            return output, b"", 0

        with patch.object(self.worker, "_run_process", side_effect=run):
            result = self.worker.runtime_diagnostics()
        self.assertEqual(result, valid_diagnostics())
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0][3], calls[1][3])
        self.assertEqual(calls[0][2]["TMPDIR"], calls[1][2]["TMPDIR"])
        self.assertFalse(self.tasks == private_dirs[0] or self.tasks in private_dirs[0].parents)
        self.assertFalse(private_dirs[0].exists())
        argv, cwd, env, _deadline, operation = calls[1]
        self.assertEqual(argv, [str(self.node), "--import", "tsx",
                                str(SKILL.resolve() / "scripts/diagnose-runtime.ts")])
        self.assertEqual(cwd, SKILL.resolve())
        self.assertEqual(operation, "diagnostics")
        expected_environment = {"PATH", "LANG", "LC_ALL", "DISABLE_TELEMETRY", "TMPDIR"}
        if os.name == "nt":
            expected_environment.update({"SystemRoot", "WINDIR", "TEMP", "TMP",
                                         "PADNOTE_WINDOWS_READER_PYTHON",
                                         "PADNOTE_WINDOWS_READER_SCRIPT",
                                         "PADNOTE_WINDOWS_READER_ROOT"})
        self.assertEqual(set(env), expected_environment)
        self.assertNotIn("PADNOTE_TTS_COMMAND", env)
        self.assertNotIn("API_KEY", env)

    def test_private_diagnostic_tmpdir_is_removed_after_probe_failure(self) -> None:
        private_dirs: list[Path] = []

        def run(argv, _cwd, env, _deadline, _operation):
            if argv[-1] == "--version":
                return b"v22.22.0\n", b"", 0
            private_dir = Path(env["TMPDIR"])
            private_dirs.append(private_dir)
            (private_dir / "tsx-cache-fixture").write_text("cache")
            raise VideoWorkerError("worker_timeout", "secret")

        with patch.object(self.worker, "_run_process", side_effect=run):
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker.runtime_diagnostics()
        self.assertEqual(caught.exception.code, "diagnostic_timeout")
        self.assertEqual(len(private_dirs), 1)
        self.assertFalse(self.tasks == private_dirs[0] or self.tasks in private_dirs[0].parents)
        self.assertFalse(private_dirs[0].exists())
        self.assertEqual(list(self.tasks.iterdir()), [])

    def test_invalid_or_extra_diagnostic_fields_are_rejected(self) -> None:
        for mutate in (
            lambda value: value.update(secret="never return"),
            lambda value: value["checks"]["tts"].update(path="/private/tool"),
            lambda value: value["checks"]["tts"].update(status="available", reason="adapter_not_configured"),
            lambda value: value.update(runtime_verified=True),
        ):
            value = valid_diagnostics()
            mutate(value)
            with self.subTest(value=value), self.assertRaises(VideoWorkerError):
                _validate_runtime_diagnostics(value)

    def test_timeout_and_invalid_output_are_normalized_to_fixed_codes(self) -> None:
        with patch.object(self.worker, "_run_process", side_effect=[(b"v22.22.0\n", b"", 0),
                                                                      VideoWorkerError("worker_timeout", "secret")]):
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker.runtime_diagnostics()
        self.assertEqual(caught.exception.code, "diagnostic_timeout")
        self.assertNotIn("secret", str(caught.exception))

        with patch.object(self.worker, "_run_process", side_effect=[(b"v22.22.0\n", b"", 0),
                                                                      (b'{"private":"data"}', b"secret", 0)]):
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker.runtime_diagnostics()
        self.assertEqual(caught.exception.code, "diagnostic_invalid")
        self.assertNotIn("private", str(caught.exception))


class VideoDiagnosticsHTTPTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-video-diagnostics-http-")
        self.service = BridgeService(Path(self.temp.name))
        self.instance = self.service.add_instance("hermes", "Fixture", "http://127.0.0.1:8642", "key")
        self.other = self.service.add_instance("hermes", "Other", "http://127.0.0.1:8643", "key2")
        for item in (self.instance, self.other):
            self.service.store.update_instance_check(
                item["instance_id"], health="ready", detail="ready",
                features={"run_submission": True}, executable=True)
        self.token = self._pair(self.instance["instance_id"])
        self.other_token = self._pair(self.other["instance_id"])
        self.worker = _ProbeWorker()
        self.service.video_worker = self.worker
    def tearDown(self) -> None:
        self.service.close()
        self.temp.cleanup()

    def _pair(self, instance_id: str) -> str:
        code, _ = self.service.store.create_pair_code(instance_id, "https://computer.example.ts.net")
        request = self.service.store.request_pair(code, "device-" + instance_id, "Test tablet")
        self.service.store.decide_pair(request["request_id"], True)
        _, result = self.service.store.claim_pair(request["request_id"], request["poll_token"])
        return result["connections"][0]["token"]

    def _request(self, instance_id: str, token: str) -> tuple[int, dict]:
        handler = ApiHandler.__new__(ApiHandler)
        handler.headers = {"Authorization": f"Bearer {token}"}
        handler.path = f"{API_PREFIX}/agents/{instance_id}/video/diagnostics"
        handler.server = SimpleNamespace(service=self.service)
        handler._finish_input = Mock()
        response = Mock()
        handler._json = response
        handler._dispatch("GET")
        return response.call_args.args[0], response.call_args.args[1]

    def test_authorization_precedes_probe_and_rejects_cross_instance_token(self) -> None:
        instance_id = self.instance["instance_id"]
        other_id = self.other["instance_id"]
        self.assertEqual(self._request(instance_id, "wrong")[0], 401)
        self.assertEqual(self._request(other_id, self.token)[0], 401)
        self.assertEqual(self.worker.calls, 0)
        status, body = self._request(instance_id, self.token)
        self.assertEqual(status, 200)
        self.assertEqual(body, valid_diagnostics())
        self.assertEqual(self.worker.calls, 1)

    def test_unconfigured_route_returns_structured_result_and_capabilities_stay_probe_free(self) -> None:
        self.service.video_worker = None
        instance_id = self.instance["instance_id"]
        status, body = self._request(instance_id, self.token)
        self.assertEqual(status, 200)
        self.assertEqual(body, not_configured_runtime_diagnostics())
        capabilities = self.service.capabilities(instance_id, self.token)
        self.assertEqual(capabilities["object"], "padnote.agent.capabilities")
        self.assertEqual(self.worker.calls, 0)


if __name__ == "__main__":
    unittest.main()
