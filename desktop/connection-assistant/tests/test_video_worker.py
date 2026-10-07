from __future__ import annotations

import json
import hashlib
import io
import os
import signal
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
import padnote_assistant.video_worker as video_worker_module
from tests.fixtures.fake_node_runner import install_fake_node_runner
from tests.fixtures.process_test_utils import process_is_alive

from padnote_assistant.video_worker import (
    VideoWorker,
    VideoWorkerConfig,
    VideoWorkerError,
    _validate_inspection,
    _validate_reconcile_envelope,
    _validate_review_projection,
)


REPO = Path(__file__).resolve().parents[3]
SKILL = REPO / "agent-skills/padnote-video-explainer"
FAKE_NODE = Path(__file__).parent / "fixtures/fake_video_node.py"
WORKER_ID = "padnote-expected-worker-id"
SERVER_ID = "server-task-a"


class VideoWorkerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.node = self.root / "node.exe"
        self.node.write_bytes(FAKE_NODE.read_bytes())
        self.node.chmod(0o755)
        self.skill = SKILL.resolve()
        self.tasks = self.root / "tasks"
        self.tasks.mkdir()
        self.task = self.tasks / SERVER_ID
        self._make_task(self.task)
        self.worker = VideoWorker(VideoWorkerConfig(self.node, self.skill, self.tasks))
        install_fake_node_runner(self.worker, self.node, FAKE_NODE)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _make_task(self, task: Path) -> None:
        (task / "work").mkdir(parents=True)
        (task / ".fake-worker-id").write_text(WORKER_ID)

    def _mode(self, mode: str) -> None:
        (self.task / ".fake-worker-mode").write_text(mode)

    def test_windows_fake_node_shim_matches_only_the_verified_fixture_path(self) -> None:
        from types import SimpleNamespace
        from tests.fixtures.fake_node_runner import install_fake_node_runner

        received = []
        helper = SimpleNamespace(_run_process=lambda argv, *args, **kwargs: (received.append(list(argv)) or (b"", b"", 0)))
        install_fake_node_runner(helper, self.node, FAKE_NODE, platform_is_windows=lambda: True)
        helper._run_process([str(self.node), "--version"], self.root, {}, time.monotonic() + 1, "diagnose")
        self.assertEqual([sys.executable, str(self.node), "--version"], received[-1])
        real_node = self.root / "real-node.exe"
        real_node.write_bytes(b"a real executable placeholder")
        helper._run_process([str(real_node), "--version"], self.root, {}, time.monotonic() + 1, "diagnose")
        self.assertEqual([str(real_node), "--version"], received[-1])

    def test_diagnose_reports_node_configuration_only(self) -> None:
        diagnostic = self.worker.diagnose()
        self.assertTrue(diagnostic.configuration_valid)
        self.assertEqual(diagnostic.node_version, "22.22.0")

    def test_builtin_request_uses_fixed_script_and_bounded_stdin(self) -> None:
        request = {"action": "produce", "provider_key": "private-test-key",
                   "binding": {"task_id": WORKER_ID}}
        response = {"ok": True, "task_id": WORKER_ID, "status": "completed"}
        with patch.object(self.worker, "_require_node_version"), \
             patch.object(self.worker, "_run_process", return_value=(
                 json.dumps(response).encode(), b"diagnostic output", 0)) as run:
            result = self.worker.run_builtin(
                request, operation_id="11111111-1111-4111-8111-111111111111",
                attempt_id="22222222-2222-4222-8222-222222222222")
        self.assertEqual(response, result)
        argv, cwd, env, _deadline, operation = run.call_args.args
        self.assertEqual([str(self.node), "--import", "tsx",
                          str(self.skill / "scripts/builtin-engine.ts")], argv)
        self.assertEqual(self.skill, cwd)
        self.assertEqual("builtin", operation)
        self.assertNotIn("private-test-key", " ".join(argv))
        self.assertNotIn("private-test-key", repr(env))
        payload = run.call_args.kwargs["stdin_bytes"]
        self.assertIn(b"private-test-key", payload)
        self.assertLessEqual(len(payload), 64 * 1024)

    def test_builtin_request_bounds_input_and_marks_response_failure_unknown(self) -> None:
        with self.assertRaises(VideoWorkerError) as caught:
            self.worker.run_builtin(
                {"payload": "x" * (64 * 1024)},
                operation_id="11111111-1111-4111-8111-111111111111",
                attempt_id="22222222-2222-4222-8222-222222222222")
        self.assertEqual("invalid_request", caught.exception.code)
        self.assertFalse(caught.exception.unknown)
        with patch.object(self.worker, "_require_node_version"), \
             patch.object(self.worker, "_run_process", return_value=(b"not-json", b"", 0)):
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker.run_builtin(
                    {"action": "produce"},
                    operation_id="11111111-1111-4111-8111-111111111111",
                    attempt_id="22222222-2222-4222-8222-222222222222")
        self.assertTrue(caught.exception.unknown)

    def test_builtin_tts_error_envelope_preserves_safe_code_and_uncertainty(self) -> None:
        cases = [
            ("tts_provider_http_error", True, 429),
            ("tts_audio_download_http_error", True, 503),
            ("tts_audio_url_rejected", True, None),
            ("tts_input_invalid", False, None),
            ("tts_adapter_start_failed", False, None),
        ]
        for code, uncertain, status in cases:
            with self.subTest(code=code, uncertain=uncertain):
                response = {
                    "ok": False,
                    "code": code,
                    "external_effect_possible": uncertain,
                    "message": ("Provider outcome is uncertain; inspect before any retry"
                                if uncertain else "Built-in video operation failed"),
                }
                if status is not None:
                    response["http_status"] = status
                with patch.object(self.worker, "_require_node_version"), \
                     patch.object(self.worker, "_run_process", return_value=(
                         json.dumps(response).encode(), b"", 1)):
                    with self.assertRaises(VideoWorkerError) as caught:
                        self.worker.run_builtin(
                            {"action": "produce"},
                            operation_id="11111111-1111-4111-8111-111111111111",
                            attempt_id="22222222-2222-4222-8222-222222222222")
                self.assertEqual(code, caught.exception.code)
                self.assertEqual(uncertain, caught.exception.unknown)
                self.assertNotIn("429", str(caught.exception))

    def test_builtin_tts_error_envelope_rejects_leak_fields_and_bad_status(self) -> None:
        base = {
            "ok": False,
            "code": "tts_provider_http_error",
            "external_effect_possible": True,
            "http_status": 429,
            "message": "Provider outcome is uncertain; inspect before any retry",
        }
        hostile = [
            {**base, "provider_body": "private response"},
            {**base, "url": "https://example.invalid/?signature=secret"},
            {**base, "stack": "private stack"},
            {**base, "api_key": "test-secret"},
            {**base, "http_status": True},
            {**base, "http_status": 700},
            {**base, "message": "provider says: private response"},
            {**base, "stage": "provider_request"},
        ]
        for response in hostile:
            with self.subTest(response_keys=sorted(response)):
                with patch.object(self.worker, "_require_node_version"), \
                     patch.object(self.worker, "_run_process", return_value=(
                         json.dumps(response).encode(), b"", 1)):
                    with self.assertRaises(VideoWorkerError) as caught:
                        self.worker.run_builtin(
                            {"action": "produce"},
                            operation_id="11111111-1111-4111-8111-111111111111",
                            attempt_id="22222222-2222-4222-8222-222222222222")
                self.assertEqual("worker_result_invalid", caught.exception.code)
                self.assertTrue(caught.exception.unknown)

    def test_produce_reconcile_requires_bound_manifest_and_result_hash(self) -> None:
        from padnote_assistant.bundles import BUILTIN_VIDEO_ARTIFACTS
        metadata = {
            "operation_id": "11111111-1111-4111-8111-111111111111",
            "attempt_id": "22222222-2222-4222-8222-222222222222",
            "action": "produce", "payload_digest": "a" * 64,
            "source_snapshot_digest": "b" * 64, "revision": 1, "event_cursor": 4,
            "review_sha256": "c" * 64, "lesson_ir_sha256": "d" * 64,
        }
        result = {
            "protocol_version": 1, "task_id": WORKER_ID, "status": "completed",
            "phase": "completed", "event_cursor": 6, "revision": 1,
            "review_sha256": "c" * 64, "lesson_ir_sha256": "d" * 64,
            "approval": {"approval_id": "approval-1", "revision": 1,
                         "review_sha256": "c" * 64, "lesson_ir_sha256": "d" * 64,
                         "granted_at": "2026-09-30T12:00:00.000Z",
                         "consumed_at": "2026-09-30T12:01:00.000Z"},
        }
        artifacts = []
        for role, (artifact_id, path, media_type) in BUILTIN_VIDEO_ARTIFACTS.items():
            artifacts.append({"id": artifact_id, "role": role, "path": path,
                              "media_type": media_type, "size_bytes": 1, "sha256": "e" * 64})
        envelope = {
            "schema_version": 1, "outcome": "verified_completed", "reason": "receipt_match",
            "operation_id": metadata["operation_id"], "attempt_id": metadata["attempt_id"],
            "task_id": WORKER_ID, "action": "produce", "payload_digest": metadata["payload_digest"],
            "request_sha256": "f" * 64, "source_snapshot_digest": metadata["source_snapshot_digest"],
            "result": result, "artifacts": artifacts, "result_manifest_sha256": "9" * 64,
        }
        _validate_reconcile_envelope(envelope, metadata, WORKER_ID, "f" * 64)
        for invalid in (
            dict(envelope, provider_url="https://private.invalid/?signature=secret"),
            dict(envelope, result_manifest_sha256="z" * 64),
            dict(envelope, artifacts=[dict(artifacts[0], path="../outside.mp4"), *artifacts[1:]]),
        ):
            with self.subTest(keys=sorted(invalid)):
                with self.assertRaises(VideoWorkerError):
                    _validate_reconcile_envelope(invalid, metadata, WORKER_ID, "f" * 64)

    def test_windows_environment_includes_system_root_without_inheriting_secrets(self) -> None:
        with patch("padnote_assistant.video_worker._is_windows", return_value=True), \
                patch.dict(os.environ, {
                    "SystemRoot": r"C:\Windows", "WINDIR": r"C:\Windows",
                    "NODE_OPTIONS": "--require private-hook",
                    "PADNOTE_TEST_SECRET": "never-inherit", "USERPROFILE": r"C:\Users\private",
                }, clear=True):
            env = self.worker._environment(self.task)
        self.assertEqual(r"C:\Windows", env["SystemRoot"])
        self.assertEqual(r"C:\Windows", env["WINDIR"])
        self.assertEqual(str(Path(sys.executable).resolve()), env["PADNOTE_WINDOWS_READER_PYTHON"])
        self.assertEqual(str(Path(video_worker_module.__file__).with_name("windows_reader.py").resolve()),
                         env["PADNOTE_WINDOWS_READER_SCRIPT"])
        self.assertEqual(str(self.tasks.resolve()), env["PADNOTE_WINDOWS_READER_ROOT"])
        self.assertNotIn("NODE_OPTIONS", env)
        self.assertNotIn("PADNOTE_TEST_SECRET", env)
        self.assertNotIn("USERPROFILE", env)

    def test_two_ids_fixed_argv_and_directory_identity_survives_new_task(self) -> None:
        result = self.worker.storyboard(SERVER_ID, WORKER_ID, 2)
        self.assertEqual(result["task_id"], WORKER_ID)
        report = json.loads((self.task / ".fake-worker-report.json").read_text())
        self.assertIn("--internal-worker", report["argv"])
        self.assertIn(str(self.task), report["argv"])
        self.assertNotIn(WORKER_ID, report["argv"])
        self.assertNotIn("NODE_OPTIONS", report["environment"])
        self.assertIn("DISABLE_TELEMETRY", report["environment"])
        self._make_task(self.tasks / "server-task-b")
        self.assertEqual(self.worker.inspect(SERVER_ID, WORKER_ID)["task_id"], WORKER_ID)

    def test_mutation_preflight_blocks_wrong_identity_and_absent_state(self) -> None:
        with self.assertRaises(VideoWorkerError) as caught:
            self.worker.storyboard(SERVER_ID, "different-worker-id", 1)
        self.assertEqual(caught.exception.code, "preflight_identity_mismatch")
        report = json.loads((self.task / ".fake-worker-report.json").read_text())
        self.assertEqual(report["argv"][3], "inspect")

        self._mode("missing-state")
        with self.assertRaises(VideoWorkerError):
            self.worker.storyboard(SERVER_ID, WORKER_ID, 1)
        report = json.loads((self.task / ".fake-worker-report.json").read_text())
        self.assertEqual(report["argv"][3], "inspect")

        self._mode("malformed-inspection")
        with self.assertRaises(VideoWorkerError) as caught:
            self.worker.storyboard(SERVER_ID, WORKER_ID, 1)
        self.assertFalse(caught.exception.unknown)
        report = json.loads((self.task / ".fake-worker-report.json").read_text())
        self.assertEqual(report["argv"][3], "inspect")

    def test_mutation_preflight_and_action_share_one_deadline(self) -> None:
        original = self.worker._run_process
        deadlines: list[float] = []

        def capture(argv, cwd, env, deadline, operation):
            deadlines.append(deadline)
            return original(argv, cwd, env, deadline, operation)

        with patch.object(self.worker, "_run_process", capture):
            self.worker.storyboard(SERVER_ID, WORKER_ID, 1)
        self.assertEqual(len(deadlines), 3)  # Node probe, inspect preflight, mutation.
        self.assertEqual(len(set(deadlines)), 1)

    def test_exact_approval_requires_current_inspected_binding(self) -> None:
        result = self.worker.approve_exact(
            SERVER_ID, WORKER_ID, 2, "b" * 64, "a" * 64)
        self.assertEqual(result["status"], "approved")
        report = json.loads((self.task / ".fake-worker-report.json").read_text())
        self.assertEqual(report["argv"][3], "approve-exact")

        with self.assertRaises(VideoWorkerError) as caught:
            self.worker.approve_exact(
                SERVER_ID, WORKER_ID, 2, "c" * 64, "a" * 64)
        self.assertEqual(caught.exception.code, "preflight_binding_mismatch")
        report = json.loads((self.task / ".fake-worker-report.json").read_text())
        self.assertEqual(report["argv"][3], "inspect")

    def test_storyboard_exact_checks_full_binding_and_fixed_cli(self) -> None:
        request = json.dumps({"task_id": WORKER_ID}, separators=(",", ":")).encode()
        (self.task / "request.json").write_bytes(request)
        digest = hashlib.sha256(request).hexdigest()
        preflight = {"protocol_version": 1, "task_id": WORKER_ID,
                     "status": "initialized", "phase": "idle", "event_cursor": 1}
        result = {"protocol_version": 1, "task_id": WORKER_ID,
                  "status": "awaiting_storyboard_review", "phase": "awaiting_approval",
                  "event_cursor": 3, "revision": 1, "review_sha256": "a" * 64,
                  "lesson_ir_sha256": "b" * 64}
        calls = []
        def invoke(action, root, deadline, *args, operation):
            calls.append((action, args, deadline, operation))
            return preflight if action == "inspect" else result
        with patch.object(self.worker, "_require_node_version") as version, \
             patch.object(self.worker, "_invoke_action", side_effect=invoke):
            actual = self.worker.build_storyboard_exact(SERVER_ID, WORKER_ID, digest, 1, 1)
        self.assertEqual(actual, result)
        self.assertEqual(1, version.call_count)
        self.assertEqual("storyboard-exact", calls[-1][0])
        self.assertEqual(("--task-id", WORKER_ID, "--request-sha256", digest,
                          "--revision", "1", "--event-cursor", "1"), calls[-1][1])
        self.assertEqual(calls[0][2], calls[1][2])

        with patch.object(self.worker, "_require_node_version"), \
             patch.object(self.worker, "_invoke_action", side_effect=invoke):
            with self.assertRaises(VideoWorkerError):
                self.worker.build_storyboard_exact(SERVER_ID, WORKER_ID, digest, 1, 2)
        self.assertEqual("inspect", calls[-1][0])

    def test_approve_bound_checks_hashes_and_never_retries_uncertain_cli(self) -> None:
        request = json.dumps({"task_id": WORKER_ID}, separators=(",", ":")).encode()
        (self.task / "request.json").write_bytes(request)
        digest = hashlib.sha256(request).hexdigest()
        current = {"protocol_version": 1, "task_id": WORKER_ID,
                   "status": "awaiting_storyboard_review", "phase": "awaiting_approval",
                   "event_cursor": 3, "revision": 1, "review_sha256": "a" * 64,
                   "lesson_ir_sha256": "b" * 64}
        approved = {"protocol_version": 1, "task_id": WORKER_ID,
                    "status": "approved", "phase": "approval_pending", "event_cursor": 4,
                    "revision": 1, "review_sha256": "a" * 64, "lesson_ir_sha256": "b" * 64,
                    "approval": {"approval_id": "approval-1", "revision": 1,
                                 "review_sha256": "a" * 64, "lesson_ir_sha256": "b" * 64,
                                 "granted_at": "2026-09-27T12:00:00.000Z"}}
        calls = []
        def invoke(action, root, deadline, *args, operation):
            calls.append((action, args, operation))
            return current if action == "inspect" else approved
        with patch.object(self.worker, "_require_node_version"), \
             patch.object(self.worker, "_invoke_action", side_effect=invoke):
            result = self.worker.approve_bound(SERVER_ID, WORKER_ID, digest, 1, 3,
                                               "a" * 64, "b" * 64)
        self.assertEqual("approved", result["status"])
        self.assertEqual("approve-bound", calls[-1][0])
        self.assertEqual(("--task-id", WORKER_ID, "--request-sha256", digest,
                          "--revision", "1", "--event-cursor", "3",
                          "--review-sha256", "a" * 64,
                          "--lesson-ir-sha256", "b" * 64), calls[-1][1])
        calls.clear()
        with patch.object(self.worker, "_require_node_version"), \
             patch.object(self.worker, "_invoke_action", side_effect=invoke):
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker.approve_bound(SERVER_ID, WORKER_ID, digest, 1, 3,
                                          "c" * 64, "b" * 64)
        self.assertFalse(caught.exception.unknown)
        self.assertEqual(["inspect"], [call[0] for call in calls])
        with patch.object(self.worker, "_require_node_version"), \
             patch.object(self.worker, "_invoke_action", side_effect=[current, VideoWorkerError("worker_failed", "safe")]) as action:
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker.approve_bound(SERVER_ID, WORKER_ID, digest, 1, 3,
                                          "a" * 64, "b" * 64)
        self.assertTrue(caught.exception.unknown)
        self.assertEqual(2, action.call_count)

    def test_operation_init_cli_carries_persistent_operation_and_attempt_binding(self) -> None:
        request = json.dumps({"task_id": WORKER_ID}, separators=(",", ":")).encode()
        (self.task / "request.json").write_bytes(request)
        digest = hashlib.sha256(request).hexdigest()
        from padnote_assistant.video_operations import payload_digest
        action_payload_digest = payload_digest("initialize", {})
        result = {"protocol_version": 1, "task_id": WORKER_ID,
                  "status": "initialized", "phase": "idle", "event_cursor": 1}
        with patch.object(self.worker, "_require_node_version"), \
             patch.object(self.worker, "_invoke_action", return_value=result) as invoke:
            actual = self.worker.initialize_exact(
                SERVER_ID, WORKER_ID, digest,
                operation_id="11111111-1111-4111-8111-111111111111",
                attempt_id="22222222-2222-4222-8222-222222222222",
                payload_digest=action_payload_digest,
                source_snapshot_sha256="a" * 64)
        self.assertEqual(result, actual)
        args, kwargs = invoke.call_args
        self.assertEqual("initialize-operation", args[0])
        self.assertEqual(("--task-id", WORKER_ID, "--request-sha256", digest,
                          "--operation-id", "11111111-1111-4111-8111-111111111111",
                          "--attempt-id", "22222222-2222-4222-8222-222222222222",
                          "--payload-digest", action_payload_digest,
                          "--source-snapshot-sha256", "a" * 64), args[3:])
        self.assertEqual("initialize-operation", kwargs["operation"])

    def test_reconcile_cli_fixed_order_for_initialize_storyboard_and_approve(self) -> None:
        request = json.dumps({"task_id": WORKER_ID}, separators=(",", ":")).encode()
        (self.task / "request.json").write_bytes(request)
        request_sha = hashlib.sha256(request).hexdigest()
        from padnote_assistant.video_operations import payload_digest
        cases = [
            ("initialize", {}, ("--event-cursor", "0")),
            ("storyboard", {"revision": 2, "event_cursor": 7},
             ("--event-cursor", "7", "--revision", "2")),
            ("approve", {"revision": 2, "event_cursor": 9,
                          "review_sha256": "b" * 64, "lesson_ir_sha256": "c" * 64},
             ("--event-cursor", "9", "--revision", "2", "--review-sha256", "b" * 64,
              "--lesson-ir-sha256", "c" * 64)),
        ]
        for index, (action, parameters, action_options) in enumerate(cases, start=1):
            with self.subTest(action=action):
                operation = {
                    "operation_id": f"11111111-1111-4111-8111-{index:012d}",
                    "attempt_id": f"22222222-2222-4222-8222-{index:012d}",
                    "action": action, "parameters": parameters,
                    "payload_digest": payload_digest(action, parameters),
                    "source_snapshot_digest": "a" * 64,
                    "video_binding": {"worker_task_id": WORKER_ID,
                                       "request_sha256": request_sha},
                }
                envelope = {
                    "schema_version": 1, "outcome": "unconfirmed", "reason": "receipt_missing",
                    "operation_id": operation["operation_id"],
                    "attempt_id": operation["attempt_id"], "task_id": WORKER_ID,
                    "action": action, "payload_digest": operation["payload_digest"],
                    "request_sha256": request_sha, "source_snapshot_digest": "a" * 64,
                    "result": None,
                }
                with patch.object(self.worker, "_require_node_version"), \
                     patch.object(self.worker, "_invoke_action", return_value=envelope) as invoke:
                    actual = self.worker.reconcile_exact(SERVER_ID, WORKER_ID, request_sha, operation)
                self.assertEqual(envelope, actual)
                args, kwargs = invoke.call_args
                self.assertEqual("reconcile-operation", args[0])
                self.assertEqual(("--task-id", WORKER_ID, "--request-sha256", request_sha,
                                  *action_options,
                                  "--operation-id", operation["operation_id"],
                                  "--attempt-id", operation["attempt_id"],
                                  "--action", action, "--payload-digest", operation["payload_digest"],
                                  "--source-snapshot-sha256", "a" * 64), args[3:])
                self.assertEqual("reconcile-operation", kwargs["operation"])

    def test_reconcile_accepts_bound_receipt_and_rejects_wrong_attempt(self) -> None:
        request = json.dumps({"task_id": WORKER_ID}, separators=(",", ":")).encode()
        (self.task / "request.json").write_bytes(request)
        request_sha = hashlib.sha256(request).hexdigest()
        from padnote_assistant.video_operations import payload_digest
        operation = {
            "operation_id": "11111111-1111-4111-8111-111111111111",
            "attempt_id": "22222222-2222-4222-8222-222222222222",
            "action": "initialize", "parameters": {},
            "payload_digest": payload_digest("initialize", {}),
            "source_snapshot_digest": "a" * 64,
            "video_binding": {"worker_task_id": WORKER_ID, "request_sha256": request_sha},
        }
        result = {"protocol_version": 1, "task_id": WORKER_ID,
                  "status": "initialized", "phase": "idle", "event_cursor": 1}
        envelope = {
            "schema_version": 1, "outcome": "verified_completed", "reason": "receipt_match",
            "operation_id": operation["operation_id"], "attempt_id": operation["attempt_id"],
            "task_id": WORKER_ID, "action": "initialize",
            "payload_digest": operation["payload_digest"], "request_sha256": request_sha,
            "source_snapshot_digest": "a" * 64, "result": result,
        }
        with patch.object(self.worker, "_require_node_version"), \
             patch.object(self.worker, "_invoke_action", return_value=envelope):
            self.assertEqual(envelope, self.worker.reconcile_exact(
                SERVER_ID, WORKER_ID, request_sha, operation))

        wrong_attempt = dict(envelope, attempt_id="33333333-3333-4333-8333-333333333333")
        with patch.object(self.worker, "_require_node_version"), \
             patch.object(self.worker, "_invoke_action", return_value=wrong_attempt):
            with self.assertRaises(VideoWorkerError):
                self.worker.reconcile_exact(SERVER_ID, WORKER_ID, request_sha, operation)

    def test_cancel_running_storyboard_uses_exact_fixed_argv_and_envelope(self) -> None:
        request = json.dumps({"task_id": WORKER_ID}, separators=(",", ":")).encode()
        (self.task / "request.json").write_bytes(request)
        request_sha = hashlib.sha256(request).hexdigest()
        from padnote_assistant.video_operations import payload_digest
        operation = {
            "operation_id": "11111111-1111-4111-8111-111111111111",
            "attempt_id": "22222222-2222-4222-8222-222222222222",
            "action": "storyboard", "parameters": {"revision": 3, "event_cursor": 17},
            "payload_digest": payload_digest("storyboard", {"revision": 3, "event_cursor": 17}),
            "source_snapshot_digest": "a" * 64,
            "video_binding": {"worker_task_id": WORKER_ID, "request_sha256": request_sha},
            "cancel_request": {"attempt_id": "22222222-2222-4222-8222-222222222222",
                               "requested_at": 5,
                               "control_id": "33333333-3333-4333-8333-333333333333"},
        }
        envelope = {"object": "padnote.video.cancel", "protocol_version": 1,
                    "operation_id": operation["operation_id"], "attempt_id": operation["attempt_id"],
                    "status": "verified_cancelled", "reason": "cancel_receipt_match"}
        with patch.object(self.worker, "_require_node_version"), \
             patch.object(self.worker, "_run_process",
                          return_value=(json.dumps(envelope).encode(), b"", 0)) as run_process:
            result = self.worker.cancel_running_storyboard(SERVER_ID, WORKER_ID, request_sha, operation)
        self.assertEqual(envelope, result)
        argv = run_process.call_args.args[0]
        self.assertEqual("cancel-operation", argv[4])
        self.assertEqual(["--task-id", WORKER_ID,
                          "--request-sha256", request_sha,
                          "--event-cursor", "17",
                          "--revision", "3",
                          "--operation-id", operation["operation_id"],
                          "--attempt-id", operation["attempt_id"],
                          "--action", "storyboard",
                          "--payload-digest", operation["payload_digest"],
                          "--source-snapshot-sha256", "a" * 64,
                          "--internal-worker"], argv[6:])

    def test_windows_storyboard_cancel_commits_request_then_terminates_and_reconciles(self) -> None:
        request = json.dumps({"task_id": WORKER_ID}, separators=(",", ":")).encode()
        (self.task / "request.json").write_bytes(request)
        request_sha = hashlib.sha256(request).hexdigest()
        from padnote_assistant.video_operations import payload_digest
        operation = {
            "operation_id": "11111111-1111-4111-8111-111111111111",
            "attempt_id": "22222222-2222-4222-8222-222222222222",
            "action": "storyboard", "parameters": {"revision": 3, "event_cursor": 17},
            "payload_digest": payload_digest("storyboard", {"revision": 3, "event_cursor": 17}),
            "source_snapshot_digest": "a" * 64,
            "video_binding": {"worker_task_id": WORKER_ID, "request_sha256": request_sha},
            "cancel_request": {"attempt_id": "22222222-2222-4222-8222-222222222222"},
        }
        pending = {"object": "padnote.video.cancel", "protocol_version": 1,
                   "operation_id": operation["operation_id"], "attempt_id": operation["attempt_id"],
                   "status": "unconfirmed", "reason": "cancel_pending"}
        cancelled = dict(pending, status="verified_cancelled", reason="cancel_receipt_match")
        events = []

        def invoke(*args, **kwargs):
            events.append(("cancel-cli", args[0]))
            return pending if len([event for event in events if event[0] == "cancel-cli"]) == 1 else cancelled

        def terminate(operation_id, attempt_id):
            events.append(("job-terminate", operation_id, attempt_id))
            return True

        with patch("padnote_assistant.video_worker._is_windows", return_value=True), \
             patch.object(self.worker, "_require_node_version"), \
             patch.object(self.worker, "_invoke_action", side_effect=invoke), \
             patch.object(self.worker, "terminate_builtin", side_effect=terminate) as stop:
            result = self.worker.cancel_running_storyboard(SERVER_ID, WORKER_ID, request_sha, operation)
        self.assertEqual(cancelled, result)
        self.assertEqual(["cancel-cli", "job-terminate", "cancel-cli"],
                         [event[0] for event in events])
        stop.assert_called_once_with(operation["operation_id"], operation["attempt_id"])

        for wrong in (
            dict(cancelled, operation_id="44444444-4444-4444-8444-444444444444"),
            dict(cancelled, status="verified_cancelled", reason="cancel_pending"),
            dict(cancelled, extra="not allowed"),
        ):
            with patch.object(self.worker, "_require_node_version"), \
                 patch.object(self.worker, "_invoke_action", return_value=wrong):
                with self.assertRaises(VideoWorkerError):
                    self.worker.cancel_running_storyboard(SERVER_ID, WORKER_ID, request_sha, operation)

    def test_reconcile_can_verify_cancel_receipt_without_result(self) -> None:
        request = json.dumps({"task_id": WORKER_ID}, separators=(",", ":")).encode()
        (self.task / "request.json").write_bytes(request)
        request_sha = hashlib.sha256(request).hexdigest()
        from padnote_assistant.video_operations import payload_digest
        operation = {
            "operation_id": "11111111-1111-4111-8111-111111111111",
            "attempt_id": "22222222-2222-4222-8222-222222222222",
            "action": "storyboard", "parameters": {"revision": 3, "event_cursor": 17},
            "payload_digest": payload_digest("storyboard", {"revision": 3, "event_cursor": 17}),
            "source_snapshot_digest": "a" * 64,
            "video_binding": {"worker_task_id": WORKER_ID, "request_sha256": request_sha},
        }
        envelope = {"schema_version": 1, "outcome": "verified_cancelled",
                    "reason": "cancel_receipt_match", "operation_id": operation["operation_id"],
                    "attempt_id": operation["attempt_id"], "task_id": WORKER_ID,
                    "action": "storyboard", "payload_digest": operation["payload_digest"],
                    "request_sha256": request_sha, "source_snapshot_digest": "a" * 64,
                    "result": None}
        with patch.object(self.worker, "_require_node_version"), \
             patch.object(self.worker, "_invoke_action", return_value=envelope):
            self.assertEqual(envelope, self.worker.reconcile_exact(
                SERVER_ID, WORKER_ID, request_sha, operation))

    def test_invalid_arguments_do_not_start_worker(self) -> None:
        for action in (
            lambda: self.worker.storyboard(SERVER_ID, WORKER_ID, 2**53),
            lambda: self.worker.approve_exact(SERVER_ID, WORKER_ID, 1, "bad", "b" * 64),
            lambda: self.worker.inspect("../escape", WORKER_ID),
        ):
            with self.subTest(action=action), self.assertRaises(VideoWorkerError):
                action()
        self.assertFalse((self.task / ".fake-worker-report.json").exists())

    def test_uncertain_mutations_and_safe_errors(self) -> None:
        for mode in ("mutation-nonzero", "mutation-invalid", "mutation-multiple"):
            self._mode(mode)
            with self.subTest(mode=mode), self.assertRaises(VideoWorkerError) as caught:
                self.worker.storyboard(SERVER_ID, WORKER_ID, 1)
            self.assertTrue(caught.exception.unknown)
            self.assertNotIn("secret", str(caught.exception))

    def test_output_limit_and_deadline(self) -> None:
        self._mode("mutation-oversized-stdout")
        with self.assertRaises(VideoWorkerError) as caught:
            self.worker.storyboard(SERVER_ID, WORKER_ID, 1)
        self.assertTrue(caught.exception.unknown)
        self._mode("mutation-slow")
        with patch.object(VideoWorker, "ACTION_TIMEOUT_SECONDS", 4.0), \
                patch.object(VideoWorker, "CLEANUP_RESERVE_SECONDS", 1.0), \
                patch.object(VideoWorker, "_require_node_version", lambda _self, _deadline: None), \
                self.assertRaises(VideoWorkerError) as caught:
            self.worker.storyboard(SERVER_ID, WORKER_ID, 1)
        self.assertIn(caught.exception.code, {"worker_timeout", "worker_cleanup_failed"})
        self.assertTrue(caught.exception.unknown)
        parent_pid = int((self.task / ".fake-worker-parent-pid").read_text())
        for _ in range(60):
            if not process_is_alive(parent_pid):
                break
            time.sleep(0.05)
        else:
            self.fail("worker parent survived timeout cleanup")

    def test_process_group_cleanup_includes_term_ignoring_grandchild(self) -> None:
        self._mode("ignore-term-tree")
        with patch.object(VideoWorker, "ACTION_TIMEOUT_SECONDS", 4.0), \
                patch.object(VideoWorker, "CLEANUP_RESERVE_SECONDS", 1.0), \
                patch.object(VideoWorker, "TERMINATE_GRACE_SECONDS", 0.1), \
                patch.object(VideoWorker, "_require_node_version", lambda _self, _deadline: None), \
                self.assertRaises(VideoWorkerError):
            self.worker.storyboard(SERVER_ID, WORKER_ID, 1)
        pid_file = self.task / ".fake-worker-child-pid"
        self.assertTrue(pid_file.exists())
        child_pid = int(pid_file.read_text())
        for _ in range(60):
            if not process_is_alive(child_pid):
                break
            time.sleep(0.05)
        else:
            self.fail("worker grandchild survived process-tree cleanup")

    @unittest.skipUnless(os.name == "posix", "a missing POSIX process group has no Windows equivalent")
    def test_posix_missing_process_group_reaps_without_grace_sleep(self) -> None:
        process = Mock(pid=987654321)
        with patch("padnote_assistant.video_worker.os.killpg", side_effect=ProcessLookupError), \
             patch("padnote_assistant.video_worker.time.sleep") as sleep:
            self.worker._terminate_group(process, time.monotonic() + 2)
        sleep.assert_not_called()
        process.wait.assert_called_once()

    def test_windows_waits_for_job_after_root_process_exits(self) -> None:
        process = Mock(pid=123)

        class Job:
            def __init__(self):
                self.counts = iter((2, 1, 0))
                self.terminate_calls = 0

            def terminate(self):
                self.terminate_calls += 1

            def active_processes(self):
                return next(self.counts)

        job = Job()
        with patch.object(video_worker_module, "_is_windows", return_value=True), \
                patch.object(video_worker_module.time, "sleep") as sleep:
            self.worker._terminate_group(process, time.monotonic() + 2, job)
        process.wait.assert_called_once()
        self.assertEqual(1, job.terminate_calls)
        self.assertEqual(2, sleep.call_count)

    def test_windows_active_job_timeout_is_unknown_cleanup_failure(self) -> None:
        process = Mock(pid=123)
        job = Mock()
        job.active_processes.return_value = 1
        with patch.object(video_worker_module, "_is_windows", return_value=True), \
                patch.object(VideoWorker, "CLEANUP_RESERVE_SECONDS", 0):
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker._terminate_group(process, time.monotonic() + 2, job)
        self.assertEqual("worker_cleanup_failed", caught.exception.code)
        self.assertTrue(caught.exception.unknown)
        job.terminate.assert_called_once_with()
        process.wait.assert_not_called()

    def test_windows_query_error_does_not_wait_past_cleanup_budget(self) -> None:
        process = Mock(pid=123)
        process.stdout = io.BytesIO()
        process.stderr = io.BytesIO()
        process.stdin = None
        process.poll.return_value = None
        process.wait.return_value = None

        class QueryFailureJob:
            def __init__(self, _process):
                self.closed = False

            def terminate(self):
                pass

            def active_processes(self):
                raise OSError("query failed")

            def close(self):
                self.closed = True

        job = QueryFailureJob(None)
        cleanup_budget = 0.04
        with patch.object(video_worker_module, "_is_windows", return_value=True), \
                patch.object(video_worker_module, "_is_posix", return_value=False), \
                patch.object(video_worker_module, "popen_tree_kwargs", return_value={}), \
                patch.object(video_worker_module.subprocess, "Popen", return_value=process), \
                patch.object(video_worker_module, "WindowsJob", return_value=job), \
                patch.object(VideoWorker, "CLEANUP_RESERVE_SECONDS", cleanup_budget):
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker._run_process(
                    [str(self.node)], self.skill, {}, time.monotonic() + 0.15, "diagnose")
        self.assertEqual("worker_cleanup_failed", caught.exception.code)
        self.assertTrue(caught.exception.unknown)
        self.assertTrue(job.closed)
        self.assertGreaterEqual(process.wait.call_count, 1)
        for call in process.wait.call_args_list:
            self.assertGreaterEqual(call.kwargs["timeout"], 0)
            self.assertLessEqual(call.kwargs["timeout"], cleanup_budget)

    def test_unconfirmed_job_cleanup_keeps_attempt_identity_blocked(self) -> None:
        identity = ("11111111-1111-4111-8111-111111111111",
                    "22222222-2222-4222-8222-222222222222")

        class StuckJob:
            def __init__(self, _process):
                self.closed = False

            def terminate(self):
                pass

            def active_processes(self):
                return 1

            def close(self):
                self.closed = True

        with patch.object(video_worker_module, "_is_windows", return_value=True), \
                patch.object(video_worker_module, "popen_tree_kwargs", return_value={}), \
                patch.object(video_worker_module, "WindowsJob", StuckJob), \
                patch.object(VideoWorker, "CLEANUP_RESERVE_SECONDS", 0):
            self.assertTrue(self.worker._reserve_operation_job(identity))
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker._run_process(
                    [sys.executable, "-c", "pass"], self.skill,
                    self.worker._environment(self.task), time.monotonic() + 10,
                    "revise-operation", operation_identity=identity)
            self.assertEqual("worker_cleanup_failed", caught.exception.code)
            self.assertTrue(caught.exception.unknown)
            self.assertIs(video_worker_module._UNCONFIRMED_JOB,
                          self.worker._active_jobs[identity])
            self.worker._release_operation_job(identity)
            self.assertIs(video_worker_module._UNCONFIRMED_JOB,
                          self.worker._active_jobs[identity])
            self.assertFalse(self.worker.terminate_builtin(*identity))

    def test_cancel_cannot_use_job_while_owner_is_closing_it(self) -> None:
        identity = ("11111111-1111-4111-8111-111111111111",
                    "22222222-2222-4222-8222-222222222222")
        root_wait_entered = threading.Event()
        release_root_wait = threading.Event()
        final_wait_entered = threading.Event()
        cancel_terminate_entered = threading.Event()
        release_cancel = threading.Event()
        watch_close_lock = threading.Event()
        owner_close_waiting = threading.Event()
        job_closed = threading.Event()
        cancellation_done = threading.Event()
        cancellation_result = []
        errors = []
        process = Mock(pid=123)
        process.stdin = None
        process.stdout = io.BytesIO()
        process.stderr = io.BytesIO()
        process.poll.return_value = 0
        wait_calls = 0

        def wait_for_process(*, timeout):
            nonlocal wait_calls
            wait_calls += 1
            if wait_calls == 1:
                root_wait_entered.set()
                if not release_root_wait.wait(3):
                    raise TimeoutError("test release timed out")
            else:
                final_wait_entered.set()
            return 0

        process.wait.side_effect = wait_for_process

        class CoordinatedJob:
            def __init__(self, _process):
                self.terminate_calls = 0
                self.lock = threading.Lock()

            def terminate(self):
                with self.lock:
                    self.terminate_calls += 1
                if threading.current_thread().name == "cancel-owner":
                    cancel_terminate_entered.set()
                    if not release_cancel.wait(3):
                        raise OSError("test release timed out")

            def active_processes(self):
                return 0

            def close(self):
                job_closed.set()

        with patch.object(video_worker_module, "_is_windows", return_value=True), \
                patch.object(video_worker_module, "_is_posix", return_value=False), \
                patch.object(video_worker_module, "popen_tree_kwargs", return_value={}), \
                patch.object(video_worker_module.subprocess, "Popen", return_value=process), \
                patch.object(video_worker_module, "WindowsJob", CoordinatedJob):
            self.assertTrue(self.worker._reserve_operation_job(identity))
            underlying_lock = self.worker._active_jobs_lock

            class ObservedLock:
                def acquire(self, *args, **kwargs):
                    if watch_close_lock.is_set() and threading.current_thread().name == "job-owner":
                        owner_close_waiting.set()
                    return underlying_lock.acquire(*args, **kwargs)

                def release(self):
                    underlying_lock.release()

                def __enter__(self):
                    self.acquire()
                    return self

                def __exit__(self, _type, _value, _traceback):
                    self.release()

            self.worker._active_jobs_lock = ObservedLock()
            self.addCleanup(setattr, self.worker, "_active_jobs_lock", underlying_lock)

            def run_owner():
                try:
                    self.worker._run_process(
                        [str(self.node)], self.skill, {}, time.monotonic() + 10,
                        "revise-operation", operation_identity=identity)
                except BaseException as error:
                    errors.append(error)

            owner = threading.Thread(target=run_owner, name="job-owner")
            owner.start()
            self.assertTrue(root_wait_entered.wait(2))
            self.assertIsInstance(self.worker._active_jobs[identity], CoordinatedJob)

            def cancel():
                try:
                    cancellation_result.append(self.worker.terminate_builtin(*identity))
                except BaseException as error:
                    errors.append(error)
                finally:
                    cancellation_done.set()

            canceller = threading.Thread(target=cancel, name="cancel-owner")
            canceller.start()
            self.assertTrue(cancel_terminate_entered.wait(2),
                            f"errors={errors!r}, result={cancellation_result!r}, done={cancellation_done.is_set()}")
            watch_close_lock.set()
            release_root_wait.set()
            self.assertTrue(final_wait_entered.wait(2))
            self.assertTrue(owner_close_waiting.wait(2))
            # The cancel call holds _active_jobs_lock while operating on the
            # handle; the owner must block before closing it.
            self.assertFalse(job_closed.is_set())
            release_cancel.set()
            owner.join(3)
            canceller.join(3)
            self.assertFalse(owner.is_alive())
            self.assertFalse(canceller.is_alive())
            self.assertTrue(cancellation_done.is_set())
            self.assertEqual([True], cancellation_result)
            self.assertTrue(job_closed.is_set())
            self.assertEqual([], errors)
            self.worker._release_operation_job(identity)

    @unittest.skipUnless(os.name == "nt", "native Windows Job Object integration test")
    def test_windows_worker_waits_for_descendant_even_after_stdio_closes(self):
        marker = self.task / "detached-child-pid.txt"
        parent_code = (
            "import subprocess,sys,time\n"
            "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(90)',"
            "],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)\n"
            "open(sys.argv[1],'w',encoding='ascii').write(str(child.pid))\n"
            "print('parent-done',flush=True)\n"
        )
        stdout, stderr, code = self.worker._run_process(
            [sys.executable, "-c", parent_code, str(marker)], self.skill,
            self.worker._environment(self.task), time.monotonic() + 15, "diagnose")
        self.assertEqual(0, code)
        self.assertIn(stdout, (b"parent-done\n", b"parent-done\r\n"))
        self.assertEqual(b"", stderr)
        child_pid = int(marker.read_text(encoding="ascii"))
        for _ in range(100):
            if not process_is_alive(child_pid):
                break
            time.sleep(0.05)
        else:
            self.fail("descendant survived after the worker root exited and closed its stdio")

    def test_successful_parent_exit_still_cleans_remaining_child(self) -> None:
        self._mode("success-with-child")
        with patch.object(VideoWorker, "TERMINATE_GRACE_SECONDS", 0.1), \
                patch.object(VideoWorker, "_require_node_version", lambda _self, _deadline: None):
            result = self.worker.storyboard(SERVER_ID, WORKER_ID, 1)
        self.assertEqual(result["task_id"], WORKER_ID)
        child_pid = int((self.task / ".fake-worker-child-pid").read_text())
        for _ in range(60):
            if not process_is_alive(child_pid):
                break
            time.sleep(0.05)
        else:
            self.fail("worker child survived successful-parent cleanup")

    def test_inspection_rejects_inconsistent_projection_and_accepts_js_time(self) -> None:
        valid = {
            "protocol_version": 1, "task_id": WORKER_ID,
            "status": "approved", "phase": "approval_pending", "event_cursor": 5,
            "revision": 2, "lesson_ir_sha256": "a" * 64, "review_sha256": "b" * 64,
            "approval": {
                "approval_id": "approval1", "revision": 2,
                "lesson_ir_sha256": "a" * 64, "review_sha256": "b" * 64,
                "granted_at": "2026-09-27T12:13:14.123Z",
            },
        }
        _validate_inspection(valid, WORKER_ID)
        invalid = dict(valid, status="cancelled", phase="approval_pending")
        with self.assertRaises(VideoWorkerError):
            _validate_inspection(invalid, WORKER_ID)
        invalid = dict(valid, event_cursor=2**53)
        with self.assertRaises(VideoWorkerError):
            _validate_inspection(invalid, WORKER_ID)
        invalid = dict(valid, revision=2**53)
        with self.assertRaises(VideoWorkerError):
            _validate_inspection(invalid, WORKER_ID)
        invalid = dict(valid, approval=dict(valid["approval"], revision=2**53))
        with self.assertRaises(VideoWorkerError):
            _validate_inspection(invalid, WORKER_ID)

    def test_review_returns_strict_projection_without_mutation_preflight(self) -> None:
        result = self.worker.review(SERVER_ID, WORKER_ID)
        self.assertEqual(result["protocol_version"], 1)
        self.assertEqual(result["task_id"], WORKER_ID)
        self.assertEqual(result["status"], "awaiting_storyboard_review")
        self.assertEqual(result["episode"]["title"], "A Short Lesson")
        self.assertEqual(result["scenes"][0]["id"], "intro")
        self.assertEqual(result["scenes"][0]["preview"]["path"], "storyboard-intro.png")
        report = json.loads((self.task / ".fake-worker-report.json").read_text())
        self.assertEqual(report["argv"][3], "review")

    def test_review_rejects_wrong_identity_and_invalid_projection_shapes_read_only(self) -> None:
        self._mode("review-wrong-id")
        with self.assertRaises(VideoWorkerError) as caught:
            self.worker.review(SERVER_ID, WORKER_ID)
        self.assertFalse(caught.exception.unknown)

        with self.subTest(expected_id="wrong"):
            self._mode("valid")
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker.review(SERVER_ID, "different-worker-id")
            self.assertFalse(caught.exception.unknown)

        for mode in (
            "review-extra-field", "review-bad-path", "review-bad-dimensions",
            "review-bad-scene-id", "review-too-many-scenes", "review-total-too-large",
        ):
            with self.subTest(mode=mode):
                self._mode(mode)
                with self.assertRaises(VideoWorkerError) as caught:
                    self.worker.review(SERVER_ID, WORKER_ID)
                self.assertFalse(caught.exception.unknown)
                self.assertNotIn("secret", str(caught.exception))
                report = json.loads((self.task / ".fake-worker-report.json").read_text())
                self.assertEqual(report["argv"][3], "review")

    def test_review_allows_large_projection_but_enforces_two_mib_output_limit(self) -> None:
        self._mode("review-large")
        result = self.worker.review(SERVER_ID, WORKER_ID)
        self.assertGreater(len(json.dumps(result).encode("utf-8")), 128 * 1024)
        self.assertLessEqual(len(json.dumps(result).encode("utf-8")), 2 * 1024 * 1024)

        self._mode("review-oversized-stdout")
        with self.assertRaises(VideoWorkerError) as caught:
            self.worker.review(SERVER_ID, WORKER_ID)
        self.assertIn(caught.exception.code, {"worker_output_limit", "worker_cleanup_failed"})
        self.assertFalse(caught.exception.unknown)

    def test_review_cleanup_errors_are_normalized_as_known_read_only_failures(self) -> None:
        leaking = VideoWorkerError("worker_cleanup_failed", f"secret path {self.task}", unknown=True)
        with patch.object(self.worker, "_call", side_effect=leaking):
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker.review(SERVER_ID, WORKER_ID)
        self.assertEqual(caught.exception.code, "worker_cleanup_failed")
        self.assertFalse(caught.exception.unknown)
        self.assertNotIn(str(self.task), str(caught.exception))

    def test_review_projection_rejects_unknown_fields_and_accepts_safe_integer_limits(self) -> None:
        valid = {
            "protocol_version": 1, "task_id": WORKER_ID,
            "status": "awaiting_storyboard_review", "event_cursor": 2**53 - 1,
            "revision": 2**53 - 1, "review_sha256": "b" * 64,
            "lesson_ir_sha256": "a" * 64,
            "episode": {"title": "T", "audience": "A", "learning_goal": "G", "language": "zh"},
            "scenes": [{
                "id": "intro", "learning_objective": "Learn", "narration": "Say this",
                "screen_text": ["Text"], "visual_kind": "quantity_change",
                "preview": {"path": "storyboard-intro.png", "media_type": "image/png",
                            "size_bytes": 1, "sha256": "c" * 64,
                            "width": 4096, "height": 2048},
            }],
        }
        _validate_review_projection(valid, WORKER_ID)
        with self.assertRaises(VideoWorkerError):
            _validate_review_projection(dict(valid, extra=True), WORKER_ID)
        with self.assertRaises(VideoWorkerError):
            _validate_review_projection(dict(valid, event_cursor=2**53), WORKER_ID)
        with self.assertRaises(VideoWorkerError):
            _validate_review_projection(dict(valid, status="running"), WORKER_ID)
        invalid_episode = dict(valid["episode"], language="x")
        with self.assertRaises(VideoWorkerError):
            _validate_review_projection(dict(valid, episode=invalid_episode), WORKER_ID)

    def test_config_rejects_symlinked_scripts_directory(self) -> None:
        copied = self.root / "skill"
        copied.mkdir()
        (copied / "package.json").write_text(json.dumps({
            "name": "padnote-video-explainer", "engines": {"node": ">=22.22.0"},
            "scripts": {"task:inspect": "node"},
        }))
        (copied / "scripts").symlink_to(self.skill / "scripts", target_is_directory=True)
        with self.assertRaises(VideoWorkerError):
            VideoWorkerConfig(self.node, copied, self.tasks)

    def test_canonical_path_rejects_windows_reparse_point_components(self) -> None:
        from types import SimpleNamespace
        from padnote_assistant.video_worker import _validate_canonical_path
        regular = os.lstat(self.task)
        fields = {name: getattr(regular, name) for name in dir(regular) if name.startswith("st_")}
        fields["st_file_attributes"] = 0x400
        reparse = SimpleNamespace(**fields)
        with patch("padnote_assistant.video_worker.os.lstat", return_value=reparse):
            with self.assertRaises(ValueError):
                _validate_canonical_path(self.task / "child")


if __name__ == "__main__":
    unittest.main()
