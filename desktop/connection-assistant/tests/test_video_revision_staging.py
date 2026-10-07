from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import threading
import time
import unittest
from pathlib import Path
import sys
from unittest.mock import Mock, patch
import padnote_assistant.video_worker as video_worker_module
from tests.fixtures.fake_node_runner import install_fake_node_runner
from tests.fixtures.process_test_utils import process_is_alive

from padnote_assistant.video_operations import payload_digest
from padnote_assistant.video_operations import _REVISE_RESULT_FIELDS as LEDGER_REVISE_FIELDS
from padnote_assistant.video_worker import (
    _MAX_REVISE_FEEDBACK_BYTES,
    _REVISE_RESULT_FIELDS as WORKER_REVISE_FIELDS,
    VideoWorker,
    VideoWorkerConfig,
    VideoWorkerError,
)


REPO = Path(__file__).resolve().parents[3]
SKILL = REPO / "agent-skills/padnote-video-explainer"
FAKE_NODE = Path(__file__).parent / "fixtures/fake_video_node.py"
WORKER_ID = "padnote-expected-worker-id"
SERVER_ID = "server-task-a"
OPERATION_ID = "11111111-1111-4111-8111-111111111111"
ATTEMPT_ID = "22222222-2222-4222-8222-222222222222"
REVIEW = "a" * 64
IR = "b" * 64
SOURCE = "d" * 64
FEEDBACK = "please shorten scene two"
PARAMETERS = {"revision": 2, "event_cursor": 8, "review_sha256": REVIEW,
              "lesson_ir_sha256": IR, "feedback_sha256": "c" * 64}


def digest_of(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class RevisionStagingTests(unittest.TestCase):
    """The desktop adapter's half of the revision staging contract.

    The Skill already proves its own end through a real CLI: it pins the strict
    eleven-option order and the stdin feedback read in
    agent-skills/padnote-video-explainer/tests/revise-operation.test.ts. What this
    file binds is the adapter's side of that same contract -- the exact argv it
    emits, the bytes it puts on stdin and closes, and the receipt it accepts.
    """

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.node = self.root / "node.exe"
        self.node.write_bytes(FAKE_NODE.read_bytes())
        self.node.chmod(0o755)
        self.tasks = self.root / "tasks"
        self.tasks.mkdir()
        self.task = self.tasks / SERVER_ID
        (self.task / "work").mkdir(parents=True)
        (self.task / ".fake-worker-id").write_text(WORKER_ID)
        request = json.dumps({"task_id": WORKER_ID}, separators=(",", ":")).encode()
        (self.task / "request.json").write_bytes(request)
        self.request_sha = hashlib.sha256(request).hexdigest()
        self.worker = VideoWorker(VideoWorkerConfig(self.node, SKILL.resolve(), self.tasks))
        install_fake_node_runner(self.worker, self.node, FAKE_NODE)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _mode(self, mode: str) -> None:
        (self.task / ".fake-worker-mode").write_text(mode)

    def _stage(self, *, feedback: str = FEEDBACK, request_sha256: str | None = None,
               expected_feedback_sha256: str | None = None, **overrides):
        call = {
            "revision": 2, "event_cursor": 8, "review_sha256": REVIEW,
            "lesson_ir_sha256": IR,
            "operation_id": OPERATION_ID, "attempt_id": ATTEMPT_ID,
            "payload_digest": payload_digest("revise", PARAMETERS),
            "source_snapshot_sha256": SOURCE,
        }
        call.update(overrides)
        # Computed only when the caller did not pin one, so a deliberately wrong
        # or unencodable feedback value is still the subject of the call.
        if expected_feedback_sha256 is None:
            expected_feedback_sha256 = digest_of(feedback)
        return self.worker.stage_revision(
            SERVER_ID, WORKER_ID, request_sha256 or self.request_sha,
            call["revision"], call["event_cursor"],
            call["review_sha256"], call["lesson_ir_sha256"], feedback,
            expected_feedback_sha256, operation_id=call["operation_id"],
            attempt_id=call["attempt_id"], payload_digest=call["payload_digest"],
            source_snapshot_sha256=call["source_snapshot_sha256"])

    def _report(self) -> dict:
        return json.loads((self.task / ".fake-worker-report.json").read_text())

    def test_staging_sends_the_strict_argv_order_and_closes_stdin(self) -> None:
        result = self._stage()
        argv = self._report()["argv"]
        self.assertEqual("revise-operation", argv[3])
        self.assertEqual(str(self.task), argv[4])
        # The Skill's own CLI test pins this exact order; the feedback digest sits
        # between the review binding and the operation metadata, so it cannot be
        # appended at the end without breaking the grammar.
        self.assertEqual([
            "--task-id", WORKER_ID,
            "--request-sha256", self.request_sha,
            "--revision", "2",
            "--event-cursor", "8",
            "--review-sha256", REVIEW,
            "--lesson-ir-sha256", IR,
            "--feedback-sha256", digest_of(FEEDBACK),
            "--operation-id", OPERATION_ID,
            "--attempt-id", ATTEMPT_ID,
            "--payload-digest", payload_digest("revise", PARAMETERS),
            "--source-snapshot-sha256", SOURCE,
            "--internal-worker",
        ], argv[5:])
        # The fixture reads to EOF and digests what really arrived, so these two
        # assertions can only both hold if the adapter wrote and then closed the
        # pipe with exactly the caller's feedback bytes.
        self.assertEqual(FEEDBACK.encode("utf-8"),
                         (self.task / ".fake-worker-stdin.bin").read_bytes())
        self.assertEqual(digest_of(FEEDBACK), result["feedback_sha256"])
        self.assertEqual(f"work/revise/{OPERATION_ID}/candidate-lesson-ir.json",
                         result["candidate_path"])
        self.assertEqual("revise", result["action"])
        self.assertEqual(WORKER_ID, result["task_id"])

    def test_staging_asks_the_skill_only_for_the_revision_action(self) -> None:
        """No state-reconciling action may run first, or the park would be destroyed."""
        seen: list[str] = []
        original = self.worker._invoke_action

        def record(action, *args, **kwargs):
            seen.append(action)
            return original(action, *args, **kwargs)

        with patch.object(self.worker, "_invoke_action", record):
            self._stage()
        self.assertEqual(["revise-operation"], seen)

    def test_staging_rejects_receipts_that_are_not_this_operation(self) -> None:
        for mode, why in (
            ("revise-extra-field", "an unexpected field"),
            ("revise-wrong-candidate", "a different candidate path"),
            ("revise-wrong-cursor", "another observed cursor"),
            ("revise-wrong-operation", "another operation identity"),
            # 2.0 == 2, so these three can only be caught by an explicit int check.
            ("revise-float-revision", "revision 2.0 instead of 2"),
            ("revise-float-cursor", "cursor 8.0 instead of 8"),
            ("revise-bool-schema", "schema_version True instead of 1"),
        ):
            with self.subTest(mode=mode), self.assertRaises(VideoWorkerError) as caught:
                self._mode(mode)
                self._stage()
            self.assertEqual("worker_result_invalid", caught.exception.code, why)
            self.assertFalse(caught.exception.unknown)
            self.assertNotIn(str(self.task), str(caught.exception))

    def test_a_receipt_echoing_the_caller_digest_is_not_evidence_of_stdin(self) -> None:
        """Control for the stdin assertion above.

        This mode answers with the caller's own --feedback-sha256, so it is
        accepted even if the pipe carried nothing. It exists to show that the
        default mode's failure dependence on real bytes is deliberate rather
        than accidental.
        """
        self._mode("revise-echo-caller-digest")
        result = self._stage()
        self.assertEqual(digest_of(FEEDBACK), result["feedback_sha256"])

    def test_blank_oversize_and_mismatched_feedback_never_start_the_worker(self) -> None:
        cases = (
            ("empty", "", digest_of(""), "invalid_revise_feedback"),
            ("whitespace only", "   \n\t ", digest_of("   \n\t "), "invalid_revise_feedback"),
            ("zero width only", "\u200b\u200c\u200d", digest_of("\u200b\u200c\u200d"),
             "invalid_revise_feedback"),
            ("oversize", "x" * (16 * 1024 + 1), digest_of("x" * (16 * 1024 + 1)),
             "invalid_revise_feedback"),
            ("not a string", None, "e" * 64, "invalid_revise_feedback"),
            ("mismatched digest", FEEDBACK, "e" * 64, "preflight_binding_mismatch"),
        )
        for why, feedback, expected, code in cases:
            with self.subTest(case=why), self.assertRaises(VideoWorkerError) as caught:
                self._stage(feedback=feedback, expected_feedback_sha256=expected)
            self.assertEqual(code, caught.exception.code, why)
            self.assertFalse(caught.exception.unknown)
        self.assertFalse((self.task / ".fake-worker-report.json").exists())

    def test_feedback_at_the_shared_bound_is_accepted(self) -> None:
        exact = "x" * (16 * 1024)
        result = self._stage(feedback=exact)
        self.assertEqual(digest_of(exact), result["feedback_sha256"])

    def test_staging_refuses_a_stale_request_before_spawning(self) -> None:
        with self.assertRaises(VideoWorkerError) as caught:
            self._stage(request_sha256="f" * 64)
        self.assertEqual("invalid_request", caught.exception.code)
        self.assertFalse(caught.exception.unknown)
        self.assertFalse((self.task / ".fake-worker-report.json").exists())

    def test_staging_failures_are_never_unknown(self) -> None:
        for mode, code in (("mutation-nonzero", "worker_failed"),
                           ("mutation-invalid", "worker_result_invalid"),
                           ("mutation-multiple", "worker_result_invalid")):
            with self.subTest(mode=mode), self.assertRaises(VideoWorkerError) as caught:
                self._mode(mode)
                self._stage()
            self.assertEqual(code, caught.exception.code)
            # Staging takes no execution lease and writes only its own directory,
            # so even a failed staging leaves task state unambiguous.
            self.assertFalse(caught.exception.unknown)

    def test_the_worker_layer_alone_already_knows_a_staging_failure(self) -> None:
        """The lower classifier must agree with the outer staging guard.

        A staging failure is definite for a structural reason -- no execution
        lease, no task state, every write confined to the operation's own
        directory -- so the worker substrate already classifies it as known,
        before stage_revision restates that for callers. Pinning only the outer
        guard would leave that lower decision unverified and free to drift to
        unknown without any test noticing. This drives the substrate directly,
        so no normalization sits between the caller and the answer.
        """
        self._mode("mutation-nonzero")
        with self.assertRaises(VideoWorkerError) as caught:
            self.worker._invoke_action(
                "revise-operation", self.task, time.monotonic() + 30.0,
                "--operation-id", OPERATION_ID,
                "--attempt-id", ATTEMPT_ID,
                "--payload-digest", payload_digest("revise", PARAMETERS),
                "--source-snapshot-sha256", SOURCE,
                operation="revise-operation")
        self.assertEqual("worker_failed", caught.exception.code)
        self.assertFalse(caught.exception.unknown)

    def test_the_receipt_contract_is_one_contract_across_three_declarations(self) -> None:
        """The same contract is written three times; the copies must not drift.

        The Skill's ReviseOperationResult, the desktop ledger's constant and this
        adapter's copy are one field set declared in three places. If any one of
        them moves alone, every real staging call fails validation at runtime
        while each side still looks internally consistent. The Skill's own source
        is read here rather than a transcribed constant, so an edit on that side
        fails this test instead of slipping through. The feedback bound is checked
        the same way, for the same reason.
        """
        interface = (SKILL / "scripts/revise-operation.ts").read_text(encoding="utf-8")
        body = interface.split("interface ReviseOperationResult", 1)[1].split("}", 1)[0]
        declared = set(re.findall(r"^\s*([a-z_][a-z0-9_]*)\??:", body, re.M))
        self.assertEqual(LEDGER_REVISE_FIELDS, declared)
        self.assertEqual(WORKER_REVISE_FIELDS, declared)
        self.assertEqual(WORKER_REVISE_FIELDS, LEDGER_REVISE_FIELDS)

        candidate = (SKILL / "scripts/revise-candidate.ts").read_text(encoding="utf-8")
        bound = re.search(r"MAX_REVISE_FEEDBACK_BYTES\s*=\s*([^;]+);", candidate)
        self.assertIsNotNone(bound, "the Skill no longer declares the feedback bound")
        factors = [factor.strip() for factor in bound.group(1).split("*")]
        self.assertTrue(all(factor.isdigit() for factor in factors), bound.group(1))
        product = 1
        for factor in factors:
            product *= int(factor)
        self.assertEqual(_MAX_REVISE_FEEDBACK_BYTES, product)

    def test_staging_without_a_reserved_operation_is_refused(self) -> None:
        with self.assertRaises(VideoWorkerError) as caught:
            self._stage(operation_id=None, attempt_id=None,
                        payload_digest=None, source_snapshot_sha256=None)
        self.assertEqual("invalid_operation_binding", caught.exception.code)
        self.assertFalse(caught.exception.unknown)
        self.assertFalse((self.task / ".fake-worker-report.json").exists())

    def test_mutation_actions_reserve_exact_attempt_and_readonly_actions_do_not(self) -> None:
        identity = (OPERATION_ID, ATTEMPT_ID)
        options = ("--operation-id", OPERATION_ID, "--attempt-id", ATTEMPT_ID,
                   "--payload-digest", "c" * 64, "--source-snapshot-sha256", SOURCE)
        with patch.object(video_worker_module, "_is_windows", return_value=True):
            for action in ("initialize-operation", "storyboard-operation",
                           "approve-operation", "revise-operation"):
                observed = []

                def run(_argv, _cwd, _env, _deadline, _operation, **kwargs):
                    observed.append(kwargs.get("operation_identity"))
                    self.assertIn(identity, self.worker._active_jobs)
                    return b"{}", b"", 0

                with patch.object(self.worker, "_run_process", side_effect=run):
                    self.worker._invoke_action(action, self.task, time.monotonic() + 30,
                                               *options, operation=action)
                self.assertEqual([identity], observed)
                self.assertNotIn(identity, self.worker._active_jobs)

            for action in ("reconcile-operation", "cancel-operation"):
                observed = []

                def run_readonly(_argv, _cwd, _env, _deadline, _operation, **kwargs):
                    observed.append(kwargs.get("operation_identity"))
                    self.assertNotIn(identity, self.worker._active_jobs)
                    return b"{}", b"", 0

                with patch.object(self.worker, "_run_process", side_effect=run_readonly):
                    self.worker._invoke_action(action, self.task, time.monotonic() + 30,
                                               *options, operation=action)
                self.assertEqual([None], observed)
                self.assertNotIn(identity, self.worker._active_jobs)

    def test_duplicate_attempt_reservation_is_rejected_without_replacing_owner(self) -> None:
        identity = (OPERATION_ID, ATTEMPT_ID)
        options = ("--operation-id", OPERATION_ID, "--attempt-id", ATTEMPT_ID,
                   "--payload-digest", "c" * 64, "--source-snapshot-sha256", SOURCE)
        entered = threading.Event()
        release = threading.Event()
        failure = []

        def blocked(*_args, **_kwargs):
            entered.set()
            self.assertTrue(release.wait(3))
            return b"{}", b"", 0

        with patch.object(video_worker_module, "_is_windows", return_value=True), \
                patch.object(self.worker, "_run_process", side_effect=blocked):
            caller = threading.Thread(target=lambda: self._invoke_catching(options, failure))
            caller.start()
            self.assertTrue(entered.wait(2))
            self.assertIsNone(self.worker._active_jobs[identity])
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker._invoke_action("storyboard-operation", self.task,
                                           time.monotonic() + 30, *options,
                                           operation="storyboard-operation")
            self.assertEqual("worker_busy", caught.exception.code)
            self.assertIsNone(self.worker._active_jobs[identity])
            release.set()
            caller.join(3)
            self.assertFalse(caller.is_alive())
            self.assertEqual([], failure)
            self.assertNotIn(identity, self.worker._active_jobs)

    def test_finished_process_keeps_owner_reservation_until_outer_release(self) -> None:
        identity = (OPERATION_ID, ATTEMPT_ID)

        class FakeJob:
            def __init__(self, _process):
                pass

            def active_processes(self):
                return 0

            def terminate(self):
                pass

            def close(self):
                pass

        with patch.object(video_worker_module, "_is_windows", return_value=True), \
                patch.object(video_worker_module, "popen_tree_kwargs", return_value={}), \
                patch.object(video_worker_module, "WindowsJob", FakeJob):
            self.assertTrue(self.worker._reserve_operation_job(identity))
            stdout, stderr, code = self.worker._run_process(
                [sys.executable, "-c", "pass"], self.root,
                self.worker._environment(self.root), time.monotonic() + 10,
                "revise-operation", operation_identity=identity)
            self.assertEqual((b"", b"", 0), (stdout, stderr, code))
            self.assertIn(identity, self.worker._active_jobs)
            self.assertIsNone(self.worker._active_jobs[identity])
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker._reserve_operation_job(identity)
            self.assertEqual("worker_busy", caught.exception.code)
            self.worker._release_operation_job(identity)
            self.assertNotIn(identity, self.worker._active_jobs)
            self.assertTrue(self.worker._reserve_operation_job(identity))
            self.worker._release_operation_job(identity)

    def _invoke_catching(self, options, errors) -> None:
        try:
            self.worker._invoke_action("storyboard-operation", self.task,
                                       time.monotonic() + 30, *options,
                                       operation="storyboard-operation")
        except BaseException as error:
            errors.append(error)

    def test_job_setup_failure_closes_all_popen_pipes(self) -> None:
        process = Mock()
        process.stdin = Mock()
        process.stdout = Mock()
        process.stderr = Mock()
        with patch.object(video_worker_module, "_is_windows", return_value=True), \
                patch.object(video_worker_module, "popen_tree_kwargs", return_value={}), \
                patch.object(video_worker_module.subprocess, "Popen", return_value=process), \
                patch.object(video_worker_module, "WindowsJob", side_effect=OSError("fixture")):
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker._run_process([str(self.node)], self.root, {},
                                         time.monotonic() + 30, "revise-operation")
        self.assertEqual("worker_unavailable", caught.exception.code)
        self.assertFalse(caught.exception.unknown)
        process.kill.assert_called_once_with()
        process.wait.assert_called_once()
        self.assertLessEqual(process.wait.call_args.kwargs["timeout"],
                             self.worker.CLEANUP_RESERVE_SECONDS)
        process.stdin.close.assert_called_once_with()
        process.stdout.close.assert_called_once_with()
        process.stderr.close.assert_called_once_with()

    def test_job_registration_failure_waits_for_the_bound_tree_before_classifying(self) -> None:
        process = Mock()
        process.stdin = Mock()
        process.stdout = Mock()
        process.stderr = Mock()

        class Job:
            def __init__(self):
                self.active = iter((1, 0))
                self.closed = False

            def terminate(self):
                pass

            def active_processes(self):
                return next(self.active)

            def close(self):
                self.closed = True

        job = Job()
        with patch.object(video_worker_module, "_is_windows", return_value=True), \
                patch.object(video_worker_module, "popen_tree_kwargs", return_value={}), \
                patch.object(video_worker_module.subprocess, "Popen", return_value=process), \
                patch.object(video_worker_module, "WindowsJob", return_value=job):
            with self.assertRaises(VideoWorkerError) as caught:
                self.worker._run_process([str(self.node)], self.root, {},
                                         time.monotonic() + 30, "revise-operation",
                                         operation_identity=(OPERATION_ID, ATTEMPT_ID))
        self.assertEqual("worker_unavailable", caught.exception.code)
        self.assertFalse(caught.exception.unknown)
        process.wait.assert_called_once()
        self.assertTrue(job.closed)
        process.stdin.close.assert_called_once_with()
        process.stdout.close.assert_called_once_with()
        process.stderr.close.assert_called_once_with()

    def test_staging_cleanup_errors_stay_unknown(self) -> None:
        # A child that could not be stopped or reaped may still be writing into
        # the staging directory, so the ledger must not record a definite failure.
        leaking = VideoWorkerError("worker_cleanup_failed", f"secret path {self.task}", unknown=True)
        with patch.object(self.worker, "_invoke_action", side_effect=leaking):
            with self.assertRaises(VideoWorkerError) as caught:
                self._stage()
        self.assertEqual("worker_cleanup_failed", caught.exception.code)
        self.assertTrue(caught.exception.unknown)
        self.assertNotIn(str(self.task), str(caught.exception))

    def test_staging_timeout_is_known_and_reaps_the_process_group(self) -> None:
        self._mode("mutation-slow")
        with patch.object(VideoWorker, "ACTION_TIMEOUT_SECONDS", 4.0), \
                patch.object(VideoWorker, "CLEANUP_RESERVE_SECONDS", 1.0), \
                patch.object(VideoWorker, "_require_node_version", lambda _self, _deadline: None), \
                self.assertRaises(VideoWorkerError) as caught:
            self._stage()
        self.assertIn(caught.exception.code, {"worker_timeout", "worker_cleanup_failed"})
        self.assertEqual(caught.exception.code == "worker_cleanup_failed", caught.exception.unknown)
        parent_pid = int((self.task / ".fake-worker-parent-pid").read_text())
        for _ in range(60):
            if not process_is_alive(parent_pid):
                break
            time.sleep(0.05)
        else:
            self.fail("staging worker survived process-tree cleanup")


if __name__ == "__main__":
    unittest.main()
