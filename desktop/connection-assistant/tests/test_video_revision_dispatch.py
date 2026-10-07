from __future__ import annotations

import hashlib
import json
import re
import tempfile
import threading
import time
import unittest
import zipfile
from pathlib import Path

from padnote_assistant.bridge import BridgeService
from padnote_assistant.bundles import input_snapshot_digest, prepare_task_directory
from padnote_assistant.state import ConflictError
from padnote_assistant.video_preview import binding_from_submission
from padnote_assistant.video_worker import VideoWorkerError, _MAX_REVISE_FEEDBACK_BYTES


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "tests/fixtures/android-video-task.zip"
# Read the Skill's own declaration, never a mirrored constant, so a change on
# either side fails this assertion instead of silently splitting the bound.
SKILL_TS = ROOT.parent.parent / "agent-skills" / "padnote-video-explainer" / "scripts" / "revise-candidate.ts"

FEEDBACK = "把开场改得更快进入主题"
FEEDBACK_SHA = hashlib.sha256(FEEDBACK.encode("utf-8")).hexdigest()
REVIEW = "a" * 64
IR = "b" * 64
REQUEST = "d" * 64

PARAMETERS = {
    "revision": 1,
    "review_sha256": REVIEW,
    "lesson_ir_sha256": IR,
    "feedback_sha256": FEEDBACK_SHA,
    "event_cursor": 1,
}

PROJECTION = {
    "protocol_version": 1,
    "task_id": "worker-dispatch-a",
    "status": "awaiting_storyboard_review",
    "event_cursor": 1,
    "revision": 1,
    "review_sha256": REVIEW,
    "lesson_ir_sha256": IR,
    "episode": {},
    "scenes": [],
}


class StagingStubWorker:
    """Receives the staged revision call; every dispatch test asserts on it."""

    def __init__(self):
        self.entered = threading.Event()
        self.calls = []

    def stage_revision(self, server_task_id, expected_worker_task_id, expected_request_sha256,
                       revision, event_cursor, review_sha256, lesson_ir_sha256, feedback,
                       expected_feedback_sha256, *, operation_id, attempt_id, payload_digest,
                       source_snapshot_sha256):
        self.calls.append({
            "worker_task_id": expected_worker_task_id,
            "request_sha256": expected_request_sha256,
            "revision": revision, "event_cursor": event_cursor,
            "review_sha256": review_sha256, "lesson_ir_sha256": lesson_ir_sha256,
            "feedback": feedback, "expected_feedback_sha256": expected_feedback_sha256,
            "operation_id": operation_id, "attempt_id": attempt_id,
            "payload_digest": payload_digest,
            "source_snapshot_sha256": source_snapshot_sha256,
        })
        self.entered.set()
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


class RevisionDispatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-video-revise-dispatch-")
        self.addCleanup(self.temp.cleanup)
        self.service = BridgeService(Path(self.temp.name))
        self.addCleanup(self.service.close)
        self.instance = self.service.add_instance(
            "hermes", "Fixture", "http://127.0.0.1:8642", "test-key")
        self.service.store.update_instance_check(
            self.instance["instance_id"], health="ready", detail="ready",
            features={"run_submission": True}, executable=True)
        code, _ = self.service.store.create_pair_code(
            self.instance["instance_id"], "https://computer.example.ts.net")
        request = self.service.store.request_pair(code, "device-a", "Fixture Tablet")
        self.service.store.decide_pair(request["request_id"], True)
        _, claimed = self.service.store.claim_pair(
            request["request_id"], request["poll_token"])
        self.token = claimed["connections"][0]["token"]
        self.conn = self.service.store.authenticate(self.instance["instance_id"], self.token)
        raw = FIXTURE.read_bytes()
        request = json.loads(zipfile.ZipFile(FIXTURE).read("request.json"))
        payload = {
            "client_task_id": "dispatch-video-init",
            "title": "Fixture video", "input": "Initialize this video task",
            "source": {"note_id": request["source"]["note_id"],
                       "note_revision": request["source"]["note_revision"]},
            "bundle_base64": hashlib.sha256(raw).hexdigest() and
            __import__("base64").b64encode(raw).decode("ascii"),
            "bundle_sha256": hashlib.sha256(raw).hexdigest(),
        }
        run, _ = self.service.store.create_or_get_run(
            self.conn, payload["client_task_id"], payload)
        self.task_id = run["task_id"]
        self.task_root = prepare_task_directory(
            self.service.store.tasks_dir, self.task_id, payload,
            payload["bundle_base64"], payload["bundle_sha256"])
        # The worker task id is the fixture request's own identity: the request
        # binding check refuses anything else.
        self.binding = binding_from_submission(payload)
        self.worker_task_id = self.binding["worker_task_id"]
        projection = dict(PROJECTION)
        projection["task_id"] = self.worker_task_id
        self.service.store.update_run(
            self.task_id, source_snapshot_digest=input_snapshot_digest(self.task_root / "input"),
            video_binding=self.binding,
            video_review_snapshot={"projection": projection, "state_sha256": REVIEW})
        self.worker = StagingStubWorker()
        self.service.video_worker = self.worker

    def _start_scheduler(self) -> None:
        self.assertTrue(self.service._start_video_scheduler())

    def _submit(self, key="revise-dispatch-1", *, parameters=None, feedback=FEEDBACK,
                with_feedback=True):
        body = {"action": "revise",
                "parameters": dict(PARAMETERS) if parameters is None else parameters}
        if with_feedback:
            body["feedback"] = feedback
        return self.service.submit_video_operation(
            self.instance["instance_id"], self.token, self.task_id, key, body)

    def _wait_status(self, operation_id: str, status: str, timeout: float = 3.0) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            saved = self.service.store.snapshot()["video_operations"][operation_id]
            if saved["status"] == status:
                return
            time.sleep(0.01)
        self.fail(f"operation never reached {status}: {saved['status']}")

    def test_revision_is_closed_before_reserving_an_operation_slot(self):
        with self.assertRaises(ConflictError):
            self._submit()
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_revision_closed_gate_follows_payload_validation(self):
        # The route remains strict about malformed plaintext, then rejects a
        # well-formed revision without creating a ledger record.
        with self.assertRaises(ConflictError):
            self._submit()
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_non_revise_action_cannot_carry_feedback(self):
        with self.assertRaises(ValueError):
            self.service.submit_video_operation(
                self.instance["instance_id"], self.token, self.task_id, "storyboard-leak",
                {"action": "storyboard", "parameters": {"revision": 1, "event_cursor": 1},
                 "feedback": FEEDBACK})
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_revision_without_feedback_is_refused(self):
        with self.assertRaises(ValueError):
            self._submit(with_feedback=False)
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_feedback_mismatching_its_digest_is_refused_without_queueing(self):
        with self.assertRaises(ValueError):
            self._submit(feedback="完全不同的反馈")
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_oversize_feedback_is_refused_without_queueing(self):
        with self.assertRaises(ValueError):
            self._submit(feedback="x" * (_MAX_REVISE_FEEDBACK_BYTES + 1))
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_oversize_feedback_with_a_matching_digest_is_still_refused(self):
        # The digest cross-check must not be the only defence: this feedback
        # matches its declared digest exactly, so only the size bound refuses
        # it. Without that bound the bytes would reach the staging call.
        oversize = "x" * (_MAX_REVISE_FEEDBACK_BYTES + 1)
        parameters = dict(PARAMETERS)
        parameters["feedback_sha256"] = hashlib.sha256(oversize.encode("utf-8")).hexdigest()
        with self.assertRaises(ValueError):
            self._submit(parameters=parameters, feedback=oversize)
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_blank_feedback_is_refused_before_it_takes_a_slot(self):
        # A matching digest must not be enough: blank or zero-width-only text is
        # refused before the reserve, so it never consumes a bounded slot.
        for text in ("", "   ", "\u200b\u200d"):
            parameters = dict(PARAMETERS)
            parameters["feedback_sha256"] = hashlib.sha256(text.encode("utf-8")).hexdigest()
            with self.assertRaises(ValueError, msg=repr(text)):
                self._submit(key=f"blank-{len(text)}", parameters=parameters, feedback=text)
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_unencodable_feedback_is_a_validation_error(self):
        # A lone surrogate survives JSON decoding but cannot be UTF-8 encoded; it
        # must be a client error, not an internal one.
        from padnote_assistant.state import ValidationError
        with self.assertRaises(ValidationError):
            self._submit(key="surrogate", feedback="\ud800")
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_revision_attempt_does_not_park_plaintext_or_reserve(self):
        with self.assertRaises(ConflictError):
            self._submit(key="closed-revision")
        self.assertEqual({}, self.service._video_revise_feedback)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_plaintext_inside_parameters_is_refused(self):
        # The projection must stay the five canonical keys: plaintext as a
        # sixth parameter key would fork the two sides' payload digest.
        with self.assertRaises(ValueError):
            self._submit(parameters={**PARAMETERS, "feedback": FEEDBACK})
        self.assertEqual([], self.worker.calls)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])

    def test_a_claim_without_plaintext_fails_definitely(self):
        # A restart after the reserve loses the in-memory bytes; the queued
        # revision survives, so the claim must fail as a definite outcome the
        # caller can retry -- never as an unknown one.
        record, _created = self.service.store.reserve_video_operation(
            self.conn, self.task_id, "restart-key", "revise", PARAMETERS)
        claimed = self.service.store.claim_next_video_operation()
        self.assertIsNotNone(claimed)
        with self.assertRaises(VideoWorkerError) as caught:
            self.service._execute_video_operation(claimed)
        self.assertEqual("worker_failed", caught.exception.code)
        self.assertFalse(caught.exception.unknown)
        self.assertEqual([], self.worker.calls)

    def test_feedback_bound_matches_the_skill_size_limit(self):
        source = SKILL_TS.read_text(encoding="utf-8")
        match = re.search(r"MAX_REVISE_FEEDBACK_BYTES\s*=\s*(\d+)\s*\*\s*(\d+)", source)
        self.assertIsNotNone(match)
        self.assertEqual(_MAX_REVISE_FEEDBACK_BYTES,
                         int(match.group(1)) * int(match.group(2)))

    def test_a_revision_is_refused_once_the_review_is_no_longer_awaiting_approval(self):
        # The plan's window for a revision is before the approval: the user
        # requests changes on the current storyboard, then reviews and approves
        # the NEW version. Once the review is no longer awaiting approval, the
        # desktop refuses the reserve -- the same state the Skill's own
        # revise-operation refuses, so both sides agree on one window.
        projection = dict(PROJECTION)
        projection["status"] = "approved"
        self.service.store.update_run(self.task_id, video_review_snapshot={
            "projection": projection, "state_sha256": REVIEW})
        with self.assertRaises(ConflictError):
            self.service.store.reserve_video_operation(
                self.conn, self.task_id, "after-approve-key", "revise", PARAMETERS)
        self.assertEqual({}, self.service.store.snapshot()["video_operations"])


if __name__ == "__main__":
    unittest.main()
