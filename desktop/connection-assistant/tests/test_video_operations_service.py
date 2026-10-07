from __future__ import annotations

import base64
import hashlib
import json
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from padnote_assistant.bridge import BridgeService
from padnote_assistant.bundles import input_snapshot_digest, prepare_task_directory
from padnote_assistant.state import AuthorizationError, ConflictError
from padnote_assistant.video_preview import binding_from_submission
from padnote_assistant.state import AuthorizationError, StateStore


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "tests/fixtures/android-video-task.zip"


class StubWorker:
    def __init__(self):
        self.entered = threading.Event()
        self.release = threading.Event()
        self.calls = []
        self.reconcile_result = None
        self.reconcile_entered = None
        self.reconcile_release = None
        self.reconcile_error = None
        self.storyboard_entered = threading.Event()
        self.storyboard_release = threading.Event()
        self.storyboard_release.set()
        self.cancel_calls = []
        self.cancel_status = "verified_cancelled"
        self.cancel_entered = threading.Event()
        self.cancel_release = threading.Event()
        self.cancel_release.set()

    def initialize_exact(self, server_task_id, worker_task_id, request_sha256, **operation_binding):
        self.calls.append((server_task_id, worker_task_id, request_sha256))
        self.entered.set()
        if not self.release.wait(3):
            raise TimeoutError("test worker timed out")
        return {"protocol_version": 1, "task_id": worker_task_id,
                "status": "initialized", "phase": "idle", "event_cursor": 1}

    def build_storyboard_exact(self, server_task_id, worker_task_id, request_sha256, revision, event_cursor,
                               **operation_binding):
        self.calls.append(("storyboard", revision, event_cursor))
        return {"protocol_version": 1, "task_id": worker_task_id,
                "status": "awaiting_storyboard_review", "phase": "awaiting_approval",
                "event_cursor": event_cursor + 2, "revision": revision,
                "review_sha256": "a" * 64, "lesson_ir_sha256": "b" * 64}

    def approve_bound(self, server_task_id, worker_task_id, request_sha256, revision,
                      event_cursor, review_sha256, lesson_ir_sha256, **operation_binding):
        self.calls.append(("approve", revision, event_cursor, review_sha256, lesson_ir_sha256))
        return {"protocol_version": 1, "task_id": worker_task_id,
                "status": "approved", "phase": "approval_pending",
                "event_cursor": event_cursor + 1, "revision": revision,
                "review_sha256": review_sha256, "lesson_ir_sha256": lesson_ir_sha256,
                "approval": {"approval_id": "approval-1", "revision": revision,
                             "review_sha256": review_sha256, "lesson_ir_sha256": lesson_ir_sha256,
                             "granted_at": "2026-09-27T12:00:00.000Z"}}

    def reconcile_exact(self, server_task_id, worker_task_id, request_sha256, operation):
        self.calls.append(("reconcile", operation["operation_id"], operation["attempt_id"]))
        if self.reconcile_entered is not None:
            self.reconcile_entered.set()
        if self.reconcile_release is not None:
            self.reconcile_release.wait(3)
        if self.reconcile_error is not None:
            raise self.reconcile_error
        if not isinstance(self.reconcile_result, dict):
            return {
                "schema_version": 1, "outcome": "unconfirmed", "reason": "receipt_missing",
                "operation_id": operation["operation_id"], "attempt_id": operation["attempt_id"],
                "task_id": worker_task_id, "action": operation["action"],
                "payload_digest": operation["payload_digest"],
                "request_sha256": request_sha256,
                "source_snapshot_digest": operation["source_snapshot_digest"], "result": None,
            }
        return {
            "schema_version": 1, "outcome": "verified_completed", "reason": "receipt_verified",
            "operation_id": operation["operation_id"], "attempt_id": operation["attempt_id"],
            "task_id": worker_task_id, "action": operation["action"],
            "payload_digest": operation["payload_digest"],
            "request_sha256": request_sha256,
            "source_snapshot_digest": operation["source_snapshot_digest"],
            "result": self.reconcile_result,
        }

    def build_storyboard_exact(self, server_task_id, worker_task_id, request_sha256,
                               revision, event_cursor, **operation_binding):
        self.calls.append(("storyboard", revision, event_cursor))
        self.storyboard_entered.set()
        self.storyboard_release.wait(3)
        return {"protocol_version": 1, "task_id": worker_task_id,
                "status": "awaiting_storyboard_review", "phase": "awaiting_approval",
                "event_cursor": event_cursor + 2, "revision": revision,
                "review_sha256": "a" * 64, "lesson_ir_sha256": "b" * 64}

    def cancel_running_storyboard(self, server_task_id, worker_task_id, request_sha256, operation):
        self.cancel_calls.append((operation["operation_id"], operation["attempt_id"]))
        self.cancel_entered.set()
        self.cancel_release.wait(3)
        return {"object": "padnote.video.cancel", "protocol_version": 1,
                "operation_id": operation["operation_id"], "attempt_id": operation["attempt_id"],
                "status": self.cancel_status,
                "reason": "cancel_receipt_match" if self.cancel_status == "verified_cancelled"
                else "cancel_pending"}


class VideoOperationServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-video-ops-service-")
        self.addCleanup(self.temp.cleanup)
        self.service = BridgeService(Path(self.temp.name))
        self.addCleanup(self.service.close)
        self.instance = self.service.add_instance(
            "hermes", "Fixture", "http://127.0.0.1:8642", "test-key")
        self.service.store.update_instance_check(
            self.instance["instance_id"], health="ready", detail="ready",
            features={"run_submission": True}, executable=True)
        self.token, self.connection = self._pair()
        raw = FIXTURE.read_bytes()
        request = json.loads(__import__("zipfile").ZipFile(FIXTURE).read("request.json"))
        self.payload = {
            "client_task_id": "service-video-init",
            "title": "Fixture video", "input": "Initialize this video task",
            "source": {"note_id": request["source"]["note_id"],
                       "note_revision": request["source"]["note_revision"]},
            "bundle_base64": base64.b64encode(raw).decode("ascii"),
            "bundle_sha256": hashlib.sha256(raw).hexdigest(),
        }
        run, _ = self.service.store.create_or_get_run(
            self.connection, self.payload["client_task_id"], self.payload)
        self.task_id = run["task_id"]
        self.task_root = prepare_task_directory(
            self.service.store.tasks_dir, self.task_id, self.payload,
            self.payload["bundle_base64"], self.payload["bundle_sha256"])
        self.binding = binding_from_submission(self.payload)
        self.service.store.update_run(
            self.task_id, source_snapshot_digest=input_snapshot_digest(self.task_root / "input"),
            video_binding=self.binding)
        self.worker = StubWorker()
        self.service.video_worker = self.worker
        self.assertTrue(self.service._start_video_scheduler())

    def _pair(self):
        code, _ = self.service.store.create_pair_code(
            self.instance["instance_id"], "https://computer.example.ts.net")
        pair = self.service.store.request_pair(code, "service-device", "Fixture Tablet")
        self.service.store.decide_pair(pair["request_id"], True)
        _, claim = self.service.store.claim_pair(pair["request_id"], pair["poll_token"])
        token = claim["connections"][0]["token"]
        return token, self.service.store.authenticate(self.instance["instance_id"], token)

    def _submit(self, key="operation-key"):
        return self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, key,
            {"action": "initialize", "parameters": {}})

    def test_running_storyboard_cancel_is_prompt_and_exact_attempt_wins(self):
        self.worker.storyboard_release.clear()
        status, operation = self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, "storyboard-control-key",
            {"action": "storyboard", "parameters": {"revision": 1, "event_cursor": 1}})
        self.assertEqual(202, status)
        self.assertTrue(self.worker.storyboard_entered.wait(2))
        started = time.monotonic()
        response = self.service.cancel_running_video_operation(
            self.instance["instance_id"], self.token, self.task_id, operation["operation_id"])
        self.assertLess(time.monotonic() - started, 1.0)
        self.assertEqual("verified_cancelled", response["status"])
        self.assertEqual(1, len(self.worker.cancel_calls))
        projected = self.service.get_video_operation(
            self.instance["instance_id"], self.token, self.task_id, operation["operation_id"])
        self.assertEqual("cancelled", projected["status"])
        again = self.service.cancel_running_video_operation(
            self.instance["instance_id"], self.token, self.task_id, operation["operation_id"])
        self.assertEqual(response, again)
        self.assertEqual(1, len(self.worker.cancel_calls))
        self.worker.storyboard_release.set()
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            saved = self.service.store.snapshot()["video_operations"][operation["operation_id"]]
            if saved["status"] == "cancelled":
                break
            time.sleep(0.01)
        self.assertEqual("cancelled", saved["status"])

    def test_builtin_local_status_reads_do_not_wait_for_event_blocked_operation(self):
        from padnote_assistant.secure_store import BuiltinCredentialVault, SecureCredentialStore, _MemoryBackend
        self.service.store._commit(lambda data: data["instances"][self.instance["instance_id"]].update(
            kind="builtin_video", provider_region="china", executable=True, health="ready", features={}))
        self.service.builtin_vault = BuiltinCredentialVault(SecureCredentialStore(_MemoryBackend()))
        self.service.builtin_vault.set(self.instance["instance_id"], "unit-test-key")
        self.worker.release.clear()
        status, operation = self._submit("blocked-init-read-test")
        self.assertEqual(202, status)
        self.assertTrue(self.worker.entered.wait(1))
        started = time.monotonic()
        detail = self.service.get_run(self.instance["instance_id"], self.token, self.task_id)
        capabilities = self.service.capabilities(self.instance["instance_id"], self.token)
        self.assertLess(time.monotonic() - started, 1.0)
        self.assertEqual(self.task_id, detail["task_id"])
        self.assertEqual("builtin_video", capabilities["kind"])
        self.assertTrue(capabilities["features"]["video_operations"])
        self.worker.release.set()
        self._wait_status(operation["operation_id"], "succeeded")

    def test_produce_same_idempotency_key_replays_before_fresh_approval_check(self):
        from padnote_assistant.video_operations import make_record
        self.service.store._commit(lambda data: data["instances"][self.instance["instance_id"]].update(
            kind="builtin_video", provider_region="china", executable=True, health="ready", features={}))
        parameters = {"revision": 1, "event_cursor": 4,
                      "review_sha256": "a" * 64, "lesson_ir_sha256": "b" * 64,
                      "allow_cloud_tts": True}
        for saved_status in ("running", "succeeded"):
            key = "produce-idempotency-" + saved_status
            run = self.service.store.owned_run(self.connection, self.task_id)
            record = make_record(self.connection, self.task_id, key, "produce", parameters,
                                 run, 100 + len(saved_status), 123)
            record.update(status=saved_status, attempt_id="11111111-1111-4111-8111-111111111111")
            if saved_status == "succeeded":
                record["result"] = {
                    "protocol_version": 1, "task_id": self.binding["worker_task_id"],
                    "status": "completed", "phase": "completed", "event_cursor": 6,
                    "revision": 1, "review_sha256": "a" * 64, "lesson_ir_sha256": "b" * 64,
                    "approval": {"approval_id": "approval-1", "revision": 1,
                                 "review_sha256": "a" * 64, "lesson_ir_sha256": "b" * 64,
                                 "granted_at": "2026-09-27T12:00:00.000Z",
                                 "consumed_at": "2026-09-27T12:01:00.000Z"},
                }
            self.service.store._commit(
                lambda data, item=record: data["video_operations"].__setitem__(item["operation_id"], item))
            self.worker.inspect = lambda *_args: self.fail("duplicate submission must not re-check fresh approval")
            status, replay = self.service.submit_video_operation(
                self.instance["instance_id"], self.token, self.task_id, key,
                {"action": "produce", "parameters": parameters})
            self.assertEqual(202, status)
            self.assertEqual(record["operation_id"], replay["operation_id"])
            with self.assertRaises(ConflictError):
                self.service.submit_video_operation(
                    self.instance["instance_id"], self.token, self.task_id, key,
                    {"action": "produce", "parameters": dict(parameters, revision=2)})

    def test_non_china_produce_is_rejected_before_inspection_or_reservation(self):
        from padnote_assistant.secure_store import BuiltinCredentialVault, SecureCredentialStore, _MemoryBackend
        self.service.store._commit(lambda data: data["instances"][self.instance["instance_id"]].update(
            kind="builtin_video", provider_region="international", executable=True,
            health="ready", features={}))
        self.service.builtin_vault = BuiltinCredentialVault(SecureCredentialStore(_MemoryBackend()))
        self.service.builtin_vault.set(self.instance["instance_id"], "unit-test-key")
        parameters = {"revision": 1, "event_cursor": 4,
                      "review_sha256": "a" * 64, "lesson_ir_sha256": "b" * 64,
                      "allow_cloud_tts": True}
        self.worker.inspect = lambda *_args: self.fail("region rejection must precede Skill inspection")
        before = dict(self.service.store.snapshot()["video_operations"])
        with self.assertRaisesRegex(ConflictError, "only in the China region"):
            self.service.submit_video_operation(
                self.instance["instance_id"], self.token, self.task_id, "non-china-produce",
                {"action": "produce", "parameters": parameters})
        self.assertEqual(before, self.service.store.snapshot()["video_operations"])

    def test_builtin_approve_produce_and_running_finished_retries_use_one_operation(self):
        from padnote_assistant.secure_store import BuiltinCredentialVault, SecureCredentialStore, _MemoryBackend

        class BuiltinWorker:
            def __init__(self, task_id, root):
                self.task_id, self.root = task_id, root
                self.state = {"protocol_version": 1, "task_id": task_id,
                              "status": "initialized", "phase": "idle", "event_cursor": 1}
                self.produce_entered = threading.Event()
                self.produce_release = threading.Event()
                self.produce_calls = 0

            def inspect(self, _server_task_id, _worker_task_id):
                return dict(self.state)

            def reconcile_exact(self, server_task_id, worker_task_id, request_sha256, operation):
                if operation.get("action") != "produce" or self.state.get("status") != "completed":
                    return {"schema_version": 1, "outcome": "unconfirmed", "reason": "receipt_missing",
                            "operation_id": operation["operation_id"], "attempt_id": operation["attempt_id"],
                            "task_id": worker_task_id, "action": operation["action"],
                            "payload_digest": operation["payload_digest"], "request_sha256": request_sha256,
                            "source_snapshot_digest": operation["source_snapshot_digest"], "result": None}
                artifacts = self.artifacts
                return {"schema_version": 1, "outcome": "verified_completed", "reason": "receipt_match",
                        "operation_id": operation["operation_id"], "attempt_id": operation["attempt_id"],
                        "task_id": worker_task_id, "action": operation["action"],
                        "payload_digest": operation["payload_digest"], "request_sha256": request_sha256,
                        "source_snapshot_digest": operation["source_snapshot_digest"],
                        "result": dict(self.state), "artifacts": artifacts,
                        "result_manifest_sha256": self.result_manifest_sha256}

            def initialize_exact(self, *_args, **_kwargs):
                return dict(self.state)

            def approve_bound(self, _server, _worker, _request, revision, cursor,
                              review_sha, ir_sha, **_kwargs):
                self.state = {"protocol_version": 1, "task_id": self.task_id,
                              "status": "approved", "phase": "approval_pending",
                              "event_cursor": cursor + 1, "revision": revision,
                              "review_sha256": review_sha, "lesson_ir_sha256": ir_sha,
                              "approval": {"approval_id": "approval-1", "revision": revision,
                                           "review_sha256": review_sha, "lesson_ir_sha256": ir_sha,
                                           "granted_at": "2026-09-30T12:00:00.000Z"}}
                return dict(self.state)

            def run_builtin(self, request, *, operation_id, attempt_id, timeout_seconds):
                self.produce_calls += int(request["action"] == "produce")
                operation = request["operation"]
                action = request["action"]
                cursor = request["event_cursor"]
                receipt = {"operation_id": operation_id, "attempt_id": attempt_id,
                           "action": action, "payload_digest": operation["payload_digest"],
                           "source_snapshot_digest": operation["source_snapshot_digest"],
                           "request_sha256": request["request_sha256"],
                           "input_event_cursor": cursor, "result_event_cursor": cursor + 2,
                           "revision": request["revision"], "review_sha256": "a" * 64,
                           "lesson_ir_sha256": "b" * 64}
                if action == "storyboard":
                    self.state = {"protocol_version": 1, "task_id": self.task_id,
                                  "status": "awaiting_storyboard_review", "phase": "awaiting_approval",
                                  "event_cursor": cursor + 2, "revision": request["revision"],
                                  "review_sha256": "a" * 64, "lesson_ir_sha256": "b" * 64}
                    return {"ok": True, "task_id": self.task_id, "status": self.state["status"],
                            "phase": self.state["phase"], "event_cursor": cursor + 2,
                            "revision": request["revision"], "review_sha256": "a" * 64,
                            "lesson_ir_sha256": "b" * 64, "receipt": receipt}
                receipt["allow_cloud_tts"] = True
                self.produce_entered.set()
                if not self.produce_release.wait(3):
                    raise TimeoutError("test producer timeout")
                output = self.root / "output"
                output.mkdir(exist_ok=True)
                from padnote_assistant.bundles import BUILTIN_VIDEO_ARTIFACTS
                contents = {"explanation.mp4": b"fixture mp4", "captions.srt": b"1\nsubtitle\n",
                            "thumbnail.png": b"fixture png", "render.manifest.json": b"{}",
                            "qa-report.json": b'{"status":"passed"}'}
                self.artifacts = []
                for role, (artifact_id, path, media_type) in BUILTIN_VIDEO_ARTIFACTS.items():
                    data = contents[path]
                    (output / path).write_bytes(data)
                    self.artifacts.append({"id": artifact_id, "role": role, "path": path,
                                           "media_type": media_type, "size_bytes": len(data),
                                           "sha256": hashlib.sha256(data).hexdigest()})
                self.state = {"protocol_version": 1, "task_id": self.task_id,
                              "status": "completed", "phase": "completed", "event_cursor": cursor + 2,
                              "revision": request["revision"], "review_sha256": "a" * 64,
                              "lesson_ir_sha256": "b" * 64,
                              "approval": {"approval_id": "approval-1", "revision": request["revision"],
                                           "review_sha256": "a" * 64, "lesson_ir_sha256": "b" * 64,
                                           "granted_at": "2026-09-30T12:00:00.000Z",
                                           "consumed_at": "2026-09-30T12:01:00.000Z"}}
                result_bytes = json.dumps({"schema_version": "1.0", "task_id": self.task_id,
                                           "status": "completed", "artifacts": self.artifacts},
                                          separators=(",", ":")).encode()
                (output / "result.json").write_bytes(result_bytes)
                self.result_manifest_sha256 = hashlib.sha256(result_bytes).hexdigest()
                return {"ok": True, "inspection": dict(self.state), "receipt": receipt,
                        "artifacts": self.artifacts}

        self.service.store._commit(lambda data: data["instances"][self.instance["instance_id"]].update(
            kind="builtin_video", provider_region="china", executable=True, health="ready", features={}))
        self.service.builtin_vault = BuiltinCredentialVault(SecureCredentialStore(_MemoryBackend()))
        self.service.builtin_vault.set(self.instance["instance_id"], "integration-test-key")
        worker = BuiltinWorker(self.binding["worker_task_id"], self.task_root)
        self.service.video_worker = worker

        _, init = self._submit("builtin-flow-init")
        self._wait_status(init["operation_id"], "succeeded")
        _, storyboard = self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, "builtin-flow-storyboard",
            {"action": "storyboard", "parameters": {"revision": 1, "event_cursor": 1}})
        self._wait_status(storyboard["operation_id"], "succeeded")
        self.service.store.update_run(self.task_id, video_review_snapshot={
            "projection": dict(worker.state), "state_sha256": "d" * 64})
        _, approval = self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, "builtin-flow-approve",
            {"action": "approve", "parameters": {"revision": 1, "event_cursor": 3,
             "review_sha256": "a" * 64, "lesson_ir_sha256": "b" * 64}})
        self._wait_status(approval["operation_id"], "succeeded")
        parameters = {"revision": 1, "event_cursor": 4, "review_sha256": "a" * 64,
                      "lesson_ir_sha256": "b" * 64, "allow_cloud_tts": True}
        _, production = self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, "builtin-flow-produce",
            {"action": "produce", "parameters": parameters})
        self.assertTrue(worker.produce_entered.wait(2))
        _, running_replay = self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, "builtin-flow-produce",
            {"action": "produce", "parameters": parameters})
        self.assertEqual(production["operation_id"], running_replay["operation_id"])
        self.assertEqual(1, worker.produce_calls)
        worker.produce_release.set()
        self._wait_status(production["operation_id"], "succeeded")
        public_success = self.service.get_video_operation(
            self.instance["instance_id"], self.token, self.task_id, production["operation_id"])
        public_receipt = public_success["result"]["receipt"]
        self.assertEqual({
            "operation_id", "attempt_id", "action", "payload_digest",
            "source_snapshot_digest", "request_sha256", "input_event_cursor",
            "result_event_cursor", "revision", "review_sha256", "lesson_ir_sha256",
            "allow_cloud_tts",
        }, set(public_receipt))
        self.assertEqual(production["operation_id"], public_receipt["operation_id"])
        self.assertEqual("produce", public_receipt["action"])
        self.assertEqual(4, public_receipt["input_event_cursor"])
        self.assertEqual(public_success["result"]["event_cursor"],
                         public_receipt["result_event_cursor"])
        self.assertIs(public_receipt["allow_cloud_tts"], True)
        _, finished_replay = self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, "builtin-flow-produce",
            {"action": "produce", "parameters": parameters})
        self.assertEqual(production["operation_id"], finished_replay["operation_id"])
        self.assertEqual(1, worker.produce_calls)
        with self.assertRaises(ConflictError):
            self.service.submit_video_operation(
                self.instance["instance_id"], self.token, self.task_id, "builtin-flow-produce",
                {"action": "produce", "parameters": dict(parameters, revision=2)})
        # The scheduler commits the operation ledger before its finish hook
        # verifies and pins artifacts into the public run projection. Wait for
        # that observable state transition instead of assuming both commits are
        # visible in the same scheduler tick (Windows can expose the boundary).
        self._wait_run_status("completed")
        task = self.service.get_run(self.instance["instance_id"], self.token, self.task_id)
        self.assertEqual("completed", task["status"])
        self.assertTrue(any(item["media_type"] == "video/mp4" for item in task["artifacts"]))
        self.assertEqual({"explanation.mp4", "captions.srt", "thumbnail.png",
                          "render.manifest.json", "qa-report.json"},
                         {item["name"] for item in task["artifacts"]})

        from padnote_assistant.security import ValidationError

        def assert_existing_video_is_readable():
            latest = self.service.capabilities(self.instance["instance_id"], self.token)
            self.assertTrue(latest["features"]["run_status"])
            self.assertTrue(latest["features"]["artifacts"])
            self.assertFalse(latest["features"]["run_submission"])
            self.assertFalse(latest["features"]["video_task_submission"])
            self.assertFalse(latest["features"]["video_operations"])
            self.assertFalse(latest["features"]["video_production"])
            old_task = self.service.get_run(self.instance["instance_id"], self.token, self.task_id)
            self.assertEqual("completed", old_task["status"])
            self.assertEqual(5, len(old_task["artifacts"]))
            artifact = next(item for item in old_task["artifacts"] if item["name"] == "explanation.mp4")
            stream, metadata = self.service.get_artifact(
                self.instance["instance_id"], self.token, self.task_id, artifact["id"])
            with stream:
                data = stream.read()
            self.assertEqual(artifact["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(artifact["sha256"], metadata["sha256"])

        # Losing provider credentials disables paid/write workflows while
        # already-pinned local output remains readable by its paired owner.
        self.service.builtin_vault.clear(self.instance["instance_id"])
        assert_existing_video_is_readable()
        before = dict(self.service.store.snapshot()["video_operations"])
        with self.assertRaises(ConflictError):
            self.service.submit_video_operation(
                self.instance["instance_id"], self.token, self.task_id, "produce-without-key",
                {"action": "produce", "parameters": parameters})
        self.assertEqual(before, self.service.store.snapshot()["video_operations"])
        unavailable_payload = dict(self.payload, client_task_id="video-submit-without-key")
        with self.assertRaises(ConflictError):
            self.service.submit_run(self.instance["instance_id"], self.token,
                                    unavailable_payload["client_task_id"], unavailable_payload)

        # Restoring the provider key cannot make new operations available when
        # the engine worker itself has gone away, but old reads still work.
        self.service.builtin_vault.set(self.instance["instance_id"], "integration-test-key")
        self.service.video_worker = None
        assert_existing_video_is_readable()
        before = dict(self.service.store.snapshot()["video_operations"])
        with self.assertRaises(ConflictError):
            self.service.submit_video_operation(
                self.instance["instance_id"], self.token, self.task_id, "produce-without-worker",
                {"action": "produce", "parameters": parameters})
        self.assertEqual(before, self.service.store.snapshot()["video_operations"])
        unavailable_payload = dict(self.payload, client_task_id="video-submit-without-worker")
        with self.assertRaises(ConflictError):
            self.service.submit_run(self.instance["instance_id"], self.token,
                                    unavailable_payload["client_task_id"], unavailable_payload)

    def test_unconfirmed_control_is_persisted_and_get_does_not_claim_stop(self):
        self.worker.storyboard_release.clear()
        self.worker.cancel_status = "unconfirmed"
        status, operation = self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, "storyboard-pending-key",
            {"action": "storyboard", "parameters": {"revision": 1, "event_cursor": 1}})
        self.assertEqual(202, status)
        self.assertTrue(self.worker.storyboard_entered.wait(2))
        response = self.service.cancel_running_video_operation(
            self.instance["instance_id"], self.token, self.task_id, operation["operation_id"])
        self.assertEqual("unconfirmed", response["status"])
        stored = self.service.store.snapshot()["video_operations"][operation["operation_id"]]
        self.assertEqual("unconfirmed", stored["cancel_request"]["control_status"])
        self.assertEqual("running", stored["status"])
        observed = self.service.get_video_cancel_request(
            self.instance["instance_id"], self.token, self.task_id, operation["operation_id"])
        self.assertEqual("unconfirmed", observed["status"])
        self.assertEqual(stored["updated_at"], observed["updated_at"])
        self.worker.storyboard_release.set()

    def test_cancel_control_is_single_flight_per_operation(self):
        self.worker.storyboard_release.clear()
        self.worker.cancel_entered.clear()
        self.worker.cancel_release.clear()
        status, operation = self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, "storyboard-single-flight",
            {"action": "storyboard", "parameters": {"revision": 1, "event_cursor": 1}})
        self.assertEqual(202, status)
        self.assertTrue(self.worker.storyboard_entered.wait(2))
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(self.service.cancel_running_video_operation,
                                 self.instance["instance_id"], self.token, self.task_id,
                                 operation["operation_id"])
            self.assertTrue(self.worker.cancel_entered.wait(2))
            with self.assertRaises(ConflictError):
                self.service.cancel_running_video_operation(
                    self.instance["instance_id"], self.token, self.task_id,
                    operation["operation_id"])
            self.assertEqual(1, len(self.worker.cancel_calls))
            self.worker.cancel_release.set()
            self.assertEqual("verified_cancelled", future.result(timeout=2)["status"])
        self.worker.storyboard_release.set()

    def test_source_change_during_verified_cancel_preserves_unconfirmed_fact(self):
        self.worker.storyboard_release.clear()
        self.worker.cancel_entered.clear()
        self.worker.cancel_release.clear()
        status, operation = self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, "storyboard-source-cas",
            {"action": "storyboard", "parameters": {"revision": 1, "event_cursor": 1}})
        self.assertEqual(202, status)
        self.assertTrue(self.worker.storyboard_entered.wait(2))
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(self.service.cancel_running_video_operation,
                                 self.instance["instance_id"], self.token, self.task_id,
                                 operation["operation_id"])
            self.assertTrue(self.worker.cancel_entered.wait(2))
            self.service.store.update_run(self.task_id, source_snapshot_digest="c" * 64)
            self.worker.cancel_release.set()
            response = future.result(timeout=2)
        self.assertEqual("unconfirmed", response["status"])
        saved = self.service.store.snapshot()["video_operations"][operation["operation_id"]]
        self.assertEqual("running", saved["status"])
        self.assertEqual("unconfirmed", saved["cancel_request"]["control_status"])
        self.worker.storyboard_release.set()

    def test_close_waits_for_cancel_control_and_callback_before_releasing_store(self):
        self.worker.storyboard_release.clear()
        self.worker.cancel_entered.clear()
        self.worker.cancel_release.clear()
        status, operation = self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, "storyboard-close-cancel",
            {"action": "storyboard", "parameters": {"revision": 1, "event_cursor": 1}})
        self.assertEqual(202, status)
        self.assertTrue(self.worker.storyboard_entered.wait(2))
        pool = ThreadPoolExecutor(max_workers=2)
        self.addCleanup(pool.shutdown, wait=True)
        cancel_future = pool.submit(self.service.cancel_running_video_operation,
                                    self.instance["instance_id"], self.token, self.task_id,
                                    operation["operation_id"])
        self.assertTrue(self.worker.cancel_entered.wait(2))
        close_future = pool.submit(self.service.close)
        time.sleep(0.05)
        self.assertFalse(close_future.done())
        self.worker.cancel_release.set()
        self.assertEqual("verified_cancelled", cancel_future.result(timeout=2)["status"])
        self.worker.storyboard_release.set()
        close_future.result(timeout=2)
        self.assertTrue(self.service.store._closed)

    def test_submit_runs_init_and_public_result_hides_worker_binding(self):
        status, operation = self._submit()
        self.assertEqual(202, status)
        self.assertEqual("padnote.video.operation", operation["object"])
        self.assertEqual(1, operation["protocol_version"])
        self.assertEqual("queued", operation["status"])
        self.assertTrue(self.worker.entered.wait(2))
        # Read and cancel use only auth/ledger and do not wait behind the active worker.
        started = time.monotonic()
        visible = self.service.get_video_operation(
            self.instance["instance_id"], self.token, self.task_id, operation["operation_id"])
        self.assertLess(time.monotonic() - started, 0.5)
        self.assertEqual("running", visible["status"])
        with self.assertRaises(ConflictError):
            self.service.cancel_video_operation(
                self.instance["instance_id"], self.token, self.task_id, operation["operation_id"])
        self.worker.release.set()
        self._wait_status(operation["operation_id"], "succeeded")
        result = self.service.get_video_operation(
            self.instance["instance_id"], self.token, self.task_id, operation["operation_id"])
        self.assertEqual(self.task_id, result["result"]["task_id"])
        self.assertNotIn("owner_connection_id", result)
        self.assertNotIn("video_binding", result)
        self.assertNotIn("attempt_id", result)
        self.assertNotIn("source_snapshot_digest", result)
        self.assertEqual([(self.task_id, self.binding["worker_task_id"],
                           self.binding["request_sha256"])], self.worker.calls)

    def test_submit_rejects_other_actions_and_untrusted_or_wrong_owner_lookup(self):
        with self.assertRaises(ValueError):
            self.service.submit_video_operation(
                self.instance["instance_id"], self.token, self.task_id, "other",
                {"action": "storyboard", "parameters": {"revision": 1}})
        other_token, _other = self._pair()
        with self.assertRaises(AuthorizationError):
            self.service.get_video_operation(
                self.instance["instance_id"], other_token, self.task_id, "0" * 36)

    def test_submit_requires_original_source_binding_and_integrity(self):
        (self.task_root / "request.json").write_bytes(b'{"wrong":true}\n')
        with self.assertRaises(ConflictError):
            self._submit()
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_exact_storyboard_and_approval_dispatch_match_review_binding(self):
        _, init = self._submit("initialize-first")
        self.worker.release.set()
        self._wait_status(init["operation_id"], "succeeded")
        _, storyboard = self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, "storyboard-v1",
            {"action": "storyboard", "parameters": {"revision": 1, "event_cursor": 1}})
        self._wait_status(storyboard["operation_id"], "succeeded")
        self.assertEqual(("storyboard", 1, 1), self.worker.calls[-1])

        review = {"protocol_version": 1, "task_id": self.binding["worker_task_id"],
                  "status": "awaiting_storyboard_review", "phase": "awaiting_approval",
                  "event_cursor": 3, "revision": 1, "review_sha256": "a" * 64,
                  "lesson_ir_sha256": "b" * 64}
        self.service.store.update_run(self.task_id, video_review_snapshot={
            "projection": review, "state_sha256": "c" * 64})
        _, approval = self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, "approve-v1",
            {"action": "approve", "parameters": {
                "revision": 1, "event_cursor": 3, "review_sha256": "a" * 64,
                "lesson_ir_sha256": "b" * 64}})
        self._wait_status(approval["operation_id"], "succeeded")
        self.assertEqual(("approve", 1, 3, "a" * 64, "b" * 64), self.worker.calls[-1])

    def test_binding_change_after_preflight_is_rejected_atomically(self):
        from padnote_assistant.video_preview import verify_request_binding as verify

        def change_binding_after_check(task_root, binding):
            verify(task_root, binding)
            self.service.store.update_run(
                self.task_id, video_binding={"worker_task_id": "replacement-worker",
                                             "request_sha256": "f" * 64})

        with patch("padnote_assistant.bridge.verify_request_binding",
                   side_effect=change_binding_after_check):
            with self.assertRaises(ConflictError):
                self._submit()
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])
        # Restore the original binding for cleanup and later assertions.
        self.service.store.update_run(self.task_id, video_binding=self.binding)

    def test_worker_rechecks_source_after_claim_before_call(self):
        entered = threading.Event()
        release = threading.Event()
        scheduler = self.service._video_scheduler
        original_execute = scheduler._execute

        def paused_execute(operation):
            entered.set()
            if not release.wait(2):
                raise TimeoutError("test callback gate timed out")
            return original_execute(operation)

        scheduler._execute = paused_execute
        _, operation = self._submit()
        self.assertTrue(entered.wait(2))
        request_path = self.task_root / "request.json"
        request_path.write_bytes(request_path.read_bytes() + b" ")
        release.set()
        self._wait_status(operation["operation_id"], "failed")
        saved = self.service.store.snapshot()["video_operations"][operation["operation_id"]]
        self.assertEqual("binding_changed", saved["error"])
        self.assertEqual([], self.worker.calls)

    def test_scheduler_start_failure_rejects_without_persisting(self):
        self.service._video_scheduler.close()
        self.service._video_scheduler = None
        self.service._video_scheduler_healthy = False
        with patch("padnote_assistant.bridge.VideoOperationScheduler.start", return_value=False):
            self.assertFalse(self.service._start_video_scheduler())
        with self.assertRaises(ConflictError):
            self._submit()
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_no_worker_restart_preserves_queue_for_lookup_and_cancel(self):
        self.service._video_scheduler.close()
        self.service._video_scheduler = None
        self.service._video_scheduler_healthy = False
        record, created = self.service.store.reserve_video_operation(
            self.connection, self.task_id, "queued-before-restart", "initialize", {},
            expected_source_snapshot_digest=self.service.store.owned_run(
                self.connection, self.task_id)["source_snapshot_digest"],
            expected_video_binding=self.binding)
        self.assertTrue(created)
        state_dir = Path(self.temp.name)
        self.service.close()
        reopened = BridgeService(state_dir)
        try:
            visible = reopened.get_video_operation(
                self.instance["instance_id"], self.token, self.task_id, record["operation_id"])
            self.assertEqual("queued", visible["status"])
            cancelled = reopened.cancel_video_operation(
                self.instance["instance_id"], self.token, self.task_id, record["operation_id"])
            self.assertEqual("cancelled", cancelled["status"])
            self.assertEqual([], self.worker.calls)
        finally:
            reopened.close()

    def test_close_failure_keeps_store_open_for_retry(self):
        scheduler = self.service._video_scheduler
        actual_close = scheduler.close
        calls = [0]

        def fail_once(timeout=None):
            calls[0] += 1
            if calls[0] == 1:
                return False
            return actual_close(timeout)

        with patch.object(scheduler, "close", side_effect=fail_once):
            with self.assertRaises(RuntimeError):
                self.service.close()
            self.assertEqual(self.service.store.bridge_id, self.service.store.snapshot()["bridge_id"])
            self.service.close()

    def _unknown_record(self, key="reconcile-test"):
        if self.service._video_scheduler is not None:
            self.service._video_scheduler.close()
            self.service._video_scheduler = None
            self.service._video_scheduler_healthy = False
        queued, _ = self.service.store.reserve_video_operation(
            self.connection, self.task_id, key, "initialize", {},
            expected_source_snapshot_digest=self.service.store.owned_run(
                self.connection, self.task_id)["source_snapshot_digest"],
            expected_video_binding=self.binding)
        claimed = self.service.store.claim_next_video_operation()
        self.service.store.finish_video_operation(
            claimed["operation_id"], claimed["attempt_id"], "unknown", error="worker_timeout")
        return self.service.store.owned_video_operation(
            self.connection, self.task_id, queued["operation_id"])

    def test_reconcile_unconfirmed_keeps_unknown_and_verified_receipt_uses_cas(self):
        unknown = self._unknown_record()
        self.worker.reconcile_result = None
        pending = self.service.reconcile_video_operation(
            self.instance["instance_id"], self.token, self.task_id, unknown["operation_id"])
        self.assertEqual("unknown", pending["status"])
        with self.assertRaises(ConflictError):
            self.service.store.reserve_video_operation(
                self.connection, self.task_id, "must-stay-blocked", "initialize", {},
                expected_source_snapshot_digest=self.service.store.owned_run(
                    self.connection, self.task_id)["source_snapshot_digest"],
                expected_video_binding=self.binding)

        self.worker.reconcile_result = {
            "protocol_version": 1, "task_id": self.binding["worker_task_id"],
            "status": "initialized", "phase": "idle", "event_cursor": 1,
        }
        completed = self.service.reconcile_video_operation(
            self.instance["instance_id"], self.token, self.task_id, unknown["operation_id"])
        self.assertEqual("succeeded", completed["status"])
        self.assertEqual(self.task_id, completed["result"]["task_id"])
        self.assertEqual(2, len([call for call in self.worker.calls if call[0] == "reconcile"]))
        # Reads of an already completed operation do not start another check.
        again = self.service.reconcile_video_operation(
            self.instance["instance_id"], self.token, self.task_id, unknown["operation_id"])
        self.assertEqual("succeeded", again["status"])
        self.assertEqual(2, len([call for call in self.worker.calls if call[0] == "reconcile"]))

    def test_close_waits_for_active_reconcile_even_when_worker_raises(self):
        unknown = self._unknown_record("close-reconcile")
        entered, release = threading.Event(), threading.Event()
        self.worker.reconcile_entered = entered
        self.worker.reconcile_release = release
        self.worker.reconcile_error = RuntimeError("synthetic adapter failure")
        request_errors = []

        def reconcile():
            try:
                self.service.reconcile_video_operation(
                    self.instance["instance_id"], self.token, self.task_id, unknown["operation_id"])
            except BaseException as error:
                request_errors.append(error)

        requester = threading.Thread(target=reconcile)
        requester.start()
        self.assertTrue(entered.wait(2))
        closed = threading.Event()
        closer = threading.Thread(target=lambda: (self.service.close(), closed.set()))
        closer.start()
        time.sleep(0.05)
        self.assertFalse(closed.is_set())
        self.assertIn("bridge_id", self.service.store.snapshot())
        release.set()
        requester.join(2)
        closer.join(2)
        self.assertTrue(closed.is_set())
        self.assertEqual(1, len(request_errors))

    def test_reconcile_does_not_return_receipt_after_connection_revocation(self):
        unknown = self._unknown_record("revoke-during-reconcile")
        entered, release = threading.Event(), threading.Event()
        self.worker.reconcile_entered = entered
        self.worker.reconcile_release = release
        self.worker.reconcile_result = {
            "protocol_version": 1, "task_id": self.binding["worker_task_id"],
            "status": "initialized", "phase": "idle", "event_cursor": 1,
        }
        failures = []

        def reconcile():
            try:
                self.service.reconcile_video_operation(
                    self.instance["instance_id"], self.token, self.task_id,
                    unknown["operation_id"])
            except BaseException as error:
                failures.append(error)

        requester = threading.Thread(target=reconcile)
        requester.start()
        self.assertTrue(entered.wait(2))
        # Revocation persists before it waits for Bridge's connection lock.
        self.service.store.revoke_connection(self.connection["connection_id"])
        release.set()
        requester.join(2)
        self.assertFalse(requester.is_alive())
        self.assertEqual(1, len(failures))
        self.assertIsInstance(failures[0], AuthorizationError)
        saved = self.service.store.snapshot()["video_operations"][unknown["operation_id"]]
        self.assertEqual("unknown", saved["status"])

    def test_duplicate_key_is_idempotent_and_close_waits_for_callback(self):
        _, first = self._submit("stable-key")
        self.assertTrue(self.worker.entered.wait(2))
        _, again = self._submit("stable-key")
        self.assertEqual(first["operation_id"], again["operation_id"])
        close_done = threading.Event()
        close_errors = []

        def close_service():
            try:
                self.service.close()
            except BaseException as error:
                close_errors.append(error)
            finally:
                close_done.set()

        callers = [threading.Thread(target=close_service) for _ in range(2)]
        for caller in callers:
            caller.start()
        time.sleep(0.05)
        self.assertFalse(close_done.is_set())
        self.worker.release.set()
        for caller in callers:
            caller.join(2)
        self.assertTrue(close_done.is_set())
        self.assertEqual([], close_errors)
        saved = json.loads((Path(self.temp.name) / "state.json").read_text())
        self.assertEqual("succeeded", saved["video_operations"][first["operation_id"]]["status"])

    def _wait_status(self, operation_id, expected, timeout=2):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            saved = self.service.store.snapshot()["video_operations"].get(operation_id)
            if saved and saved["status"] == expected:
                return
            time.sleep(0.005)
        self.fail(f"operation did not reach {expected}")

    def _wait_run_status(self, expected, timeout=5):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            run = self.service.get_run(self.instance["instance_id"], self.token, self.task_id)
            if run["status"] == expected:
                return run
            time.sleep(0.005)
        self.fail(f"run did not reach {expected}")
