from __future__ import annotations

import json
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from padnote_assistant.state import AuthorizationError, ConflictError, StateError, StateStore
from padnote_assistant.video_operations import MAX_VIDEO_OPERATIONS_PER_TASK, payload_digest


HASH_A = "a" * 64
HASH_B = "b" * 64


class VideoOperationStoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.now = [100]
        self.store = StateStore(self.root / "state", clock=lambda: self.now[0])
        self.instance = self.store.add_instance("hermes", "Fixture", "http://127.0.0.1:8642")
        self.store.update_instance_check(self.instance["instance_id"], health="ready",
                                         detail="ready", features={"run_submission": True},
                                         executable=True)
        self.conn = self._pair("device-a")
        self.run = self._make_run(self.conn, "client-a")

    def tearDown(self) -> None:
        self.store.close()
        self.temp.cleanup()

    def _pair(self, device: str) -> dict:
        code, _ = self.store.create_pair_code(
            self.instance["instance_id"], "https://computer.example.ts.net")
        request = self.store.request_pair(code, device, "Test tablet")
        self.store.decide_pair(request["request_id"], True)
        _, claimed = self.store.claim_pair(request["request_id"], request["poll_token"])
        return self.store.authenticate(self.instance["instance_id"], claimed["connections"][0]["token"])

    def _make_run(self, connection: dict, client_id: str) -> dict:
        run, _ = self.store.create_or_get_run(
            connection, client_id, {"client_task_id": client_id, "input": "fixture"})
        return self.store.update_run(
            run["task_id"], source_snapshot_digest=HASH_A,
            video_binding={"worker_task_id": "worker-" + client_id, "request_sha256": HASH_B})

    def _reserve(self, key: str = "op-1", *, run: dict | None = None,
                 connection: dict | None = None, action: str = "initialize",
                 parameters: dict | None = None):
        return self.store.reserve_video_operation(
            connection or self.conn, (run or self.run)["task_id"], key,
            action, {} if parameters is None else parameters)

    @staticmethod
    def _inspection(worker_id: str, *, status: str = "initialized", phase: str = "idle") -> dict:
        return {"protocol_version": 1, "task_id": worker_id, "status": status,
                "phase": phase, "event_cursor": 1}

    def test_idempotent_concurrent_reservation_and_deep_copies(self) -> None:
        barrier = threading.Barrier(2)

        def reserve():
            barrier.wait()
            return self._reserve("same-key")

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _index: reserve(), range(2)))
        self.assertEqual(sum(created for _record, created in results), 1)
        self.assertEqual(results[0][0]["operation_id"], results[1][0]["operation_id"])
        detached, _ = results[0]
        detached["parameters"]["corruption"] = True
        self.assertNotIn("corruption", self.store.snapshot()["video_operations"][detached["operation_id"]]["parameters"])
        retry, created = self._reserve("same-key")
        self.assertFalse(created)
        self.assertNotIn("corruption", retry["parameters"])
        with self.assertRaises(ConflictError):
            self._reserve("same-key", action="storyboard", parameters={"revision": 1, "event_cursor": 1})
        with self.assertRaises(ConflictError):
            self._reserve("different-key")

    def test_approval_parameters_must_match_saved_review_snapshot(self) -> None:
        self.store.update_run(self.run["task_id"], video_review_snapshot={
            "projection": {
                "protocol_version": 1, "task_id": "worker-client-a",
                "status": "awaiting_storyboard_review", "event_cursor": 8,
                "revision": 2, "review_sha256": HASH_A, "lesson_ir_sha256": HASH_B,
                "episode": {}, "scenes": [],
            },
            "state_sha256": HASH_A,
        })
        params = {"revision": 2, "review_sha256": HASH_A,
                  "lesson_ir_sha256": HASH_B, "event_cursor": 8}
        record, created = self._reserve("approve-key", action="approve", parameters=params)
        self.assertTrue(created)
        self.assertEqual(record["parameters"], params)

    def test_wrong_approval_snapshot_and_parameter_shapes_are_rejected(self) -> None:
        with self.assertRaises(ConflictError):
            self._reserve("approve", action="approve", parameters={
                "revision": 1, "review_sha256": HASH_A,
                "lesson_ir_sha256": HASH_B, "event_cursor": 1,
            })
        invalid = (
            ("initialize", {"extra": True}),
            ("storyboard", {"revision": True, "event_cursor": 1}),
            ("storyboard", {"revision": 2**53, "event_cursor": 1}),
            ("storyboard", {"revision": 1, "event_cursor": 2**53 - 2}),
            ("approve", {"revision": 1, "review_sha256": HASH_A,
                          "lesson_ir_sha256": HASH_B, "event_cursor": 0}),
            ("unknown", {}),
        )
        for action, parameters in invalid:
            with self.subTest(action=action, parameters=parameters), self.assertRaises(ValueError):
                self._reserve("invalid-" + str(action), action=action, parameters=parameters)

    def test_queued_cancel_releases_task_and_cancel_retry_is_idempotent(self) -> None:
        queued, _ = self._reserve("cancel-me")
        cancelled = self.store.cancel_queued_video_operation(
            self.conn, self.run["task_id"], queued["operation_id"])
        again = self.store.cancel_queued_video_operation(
            self.conn, self.run["task_id"], queued["operation_id"])
        self.assertEqual(cancelled, again)
        self.assertEqual("cancelled", cancelled["status"])
        next_op, created = self._reserve("after-cancel")
        self.assertTrue(created)
        self.assertEqual("queued", next_op["status"])

    def test_revoke_and_retire_cancel_queued_before_claim(self) -> None:
        revoked, _ = self._reserve("revoked")
        self.store.revoke_connection(self.conn["connection_id"])
        self.assertIsNone(self.store.claim_next_video_operation())
        self.assertEqual("cancelled", self.store.snapshot()["video_operations"][revoked["operation_id"]]["status"])
        self.assertEqual("authorization_revoked",
                         self.store.snapshot()["video_operations"][revoked["operation_id"]]["error"])

        # Use a fresh connection and task to cover retirement independently.
        fresh = self._pair("device-b")
        run = self._make_run(fresh, "client-b")
        queued, _ = self._reserve("retired", run=run, connection=fresh)
        self.store.retire_instance(self.instance["instance_id"], "retired")
        self.assertIsNone(self.store.claim_next_video_operation())
        saved = self.store.snapshot()["video_operations"][queued["operation_id"]]
        self.assertEqual(("cancelled", "instance_retired"), (saved["status"], saved["error"]))

    def test_finish_attempt_cas_terminal_idempotency_and_unknown_blocks_new_action(self) -> None:
        queued, _ = self._reserve("finish")
        running = self.store.claim_next_video_operation()
        self.assertEqual(queued["operation_id"], running["operation_id"])
        with self.assertRaises(ConflictError):
            self.store.finish_video_operation(running["operation_id"], "00000000-0000-0000-0000-000000000000",
                                              "failed", error="worker_failed")
        result = self._inspection("worker-client-a")
        done = self.store.finish_video_operation(
            running["operation_id"], running["attempt_id"], "succeeded", result=result)
        self.assertEqual("succeeded", done["status"])
        same = self.store.finish_video_operation(
            running["operation_id"], running["attempt_id"], "succeeded", result=result)
        self.assertEqual(done, same)
        with self.assertRaises(ConflictError):
            self.store.finish_video_operation(
                running["operation_id"], running["attempt_id"], "failed", error="worker_failed")

        another, _ = self._reserve("unknown")
        claimed = self.store.claim_next_video_operation()
        self.assertEqual(another["operation_id"], claimed["operation_id"])
        self.assertEqual(1, self.store.recover_video_operations())
        saved = self.store.owned_video_operation(self.conn, self.run["task_id"], another["operation_id"])
        self.assertEqual(("unknown", "worker_interrupted"), (saved["status"], saved["error"]))
        with self.assertRaises(ConflictError):
            self._reserve("must-wait")

    def test_safe_tts_codes_are_persisted_without_downgrading_unknown(self) -> None:
        unknown, _ = self._reserve("tts-http-unknown")
        running = self.store.claim_next_video_operation()
        self.assertEqual(unknown["operation_id"], running["operation_id"])
        saved = self.store.finish_video_operation(
            running["operation_id"], running["attempt_id"], "unknown",
            error="tts_provider_http_error")
        self.assertEqual(("unknown", "tts_provider_http_error"),
                         (saved["status"], saved["error"]))

        fresh_connection = self._pair("device-tts-known-failure")
        fresh_run = self._make_run(fresh_connection, "client-tts-known-failure")
        failed, _ = self._reserve("tts-invalid-known", run=fresh_run, connection=fresh_connection)
        running = self.store.claim_next_video_operation()
        self.assertEqual(failed["operation_id"], running["operation_id"])
        saved = self.store.finish_video_operation(
            running["operation_id"], running["attempt_id"], "failed",
            error="tts_input_invalid")
        self.assertEqual(("failed", "tts_input_invalid"),
                         (saved["status"], saved["error"]))

    def _unknown_operation(self, key: str = "reconcile") -> dict:
        queued, _ = self._reserve(key)
        claimed = self.store.claim_next_video_operation()
        self.assertEqual(queued["operation_id"], claimed["operation_id"])
        self.store.finish_video_operation(
            claimed["operation_id"], claimed["attempt_id"], "unknown", error="worker_unknown")
        return self.store.owned_video_operation(
            self.conn, self.run["task_id"], claimed["operation_id"])

    def test_reconcile_unknown_success_is_attempt_cas_and_idempotent_under_concurrency(self) -> None:
        expected = self._unknown_operation()
        result = self._inspection("worker-client-a")
        # Ordinary finish CAS cannot resolve an unknown record.
        with self.assertRaises(ConflictError):
            self.store.finish_video_operation(
                expected["operation_id"], expected["attempt_id"], "succeeded", result=result)
        barrier = threading.Barrier(2)

        def reconcile():
            barrier.wait()
            return self.store.reconcile_unknown_video_operation(self.conn, expected, result)

        with ThreadPoolExecutor(max_workers=2) as pool:
            completed = list(pool.map(lambda _index: reconcile(), range(2)))
        self.assertEqual(["succeeded", "succeeded"], [item["status"] for item in completed])
        self.assertEqual(completed[0], completed[1])
        self.assertEqual(result, completed[0]["result"])

    def test_reconcile_rejects_wrong_result_stale_record_and_changed_binding(self) -> None:
        expected = self._unknown_operation()
        with self.assertRaises(ValueError):
            self.store.reconcile_unknown_video_operation(
                self.conn, expected, self._inspection("another-worker"))
        altered = dict(expected)
        altered["error"] = "worker_interrupted"
        with self.assertRaises(ConflictError):
            self.store.reconcile_unknown_video_operation(
                self.conn, altered, self._inspection("worker-client-a"))
        self.store.update_run(self.run["task_id"], source_snapshot_digest=HASH_B)
        with self.assertRaises(ConflictError):
            self.store.reconcile_unknown_video_operation(
                self.conn, expected, self._inspection("worker-client-a"))
        saved = self.store.owned_video_operation(
            self.conn, self.run["task_id"], expected["operation_id"])
        self.assertEqual("unknown", saved["status"])
        with self.assertRaises(ConflictError):
            self._reserve("unknown-still-blocks")

    def test_reconcile_requires_current_live_connection_and_does_not_unlock_unknown(self) -> None:
        expected = self._unknown_operation()
        result = self._inspection("worker-client-a")
        self.store.revoke_connection(self.conn["connection_id"])
        with self.assertRaises(AuthorizationError):
            self.store.reconcile_unknown_video_operation(self.conn, expected, result)
        with self.assertRaises(ConflictError):
            self._reserve("cannot-unlock", connection=self.conn)
        saved = self.store.snapshot()["video_operations"][expected["operation_id"]]
        self.assertEqual("unknown", saved["status"])

    def test_finish_rejects_wrong_action_state_and_unbounded_or_path_result(self) -> None:
        queued, _ = self._reserve("storyboard", action="storyboard",
                                  parameters={"revision": 1, "event_cursor": 1})
        running = self.store.claim_next_video_operation()
        for bad in (
            self._inspection("worker-client-a"),
            {"task_id": "worker-client-a", "status": "awaiting_storyboard_review",
             "phase": "awaiting_approval", "event_cursor": 1, "path": "/secret"},
            {"task_id": "worker-client-a", "status": "awaiting_storyboard_review",
             "phase": "awaiting_approval", "event_cursor": 1, "detail": "x" * 20000},
            {"protocol_version": 1, "task_id": "worker-client-a",
             "status": "awaiting_storyboard_review", "phase": "awaiting_approval",
             "event_cursor": 3, "revision": 2, "review_sha256": HASH_A,
             "lesson_ir_sha256": HASH_B},
        ):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                self.store.finish_video_operation(
                    running["operation_id"], running["attempt_id"], "succeeded", result=bad)
        good = {"protocol_version": 1, "task_id": "worker-client-a",
                "status": "awaiting_storyboard_review", "phase": "awaiting_approval",
                "event_cursor": 3, "revision": 1, "review_sha256": HASH_A,
                "lesson_ir_sha256": HASH_B}
        completed = self.store.finish_video_operation(
            running["operation_id"], running["attempt_id"], "succeeded", result=good)
        self.assertEqual("succeeded", completed["status"])
        self.assertEqual(queued["operation_id"], completed["operation_id"])

    def test_approval_success_requires_full_inspection_matching_approved_binding(self) -> None:
        projection = {
            "protocol_version": 1, "task_id": "worker-client-a",
            "status": "awaiting_storyboard_review", "phase": "awaiting_approval",
            "event_cursor": 8, "revision": 2, "review_sha256": HASH_A,
            "lesson_ir_sha256": HASH_B, "episode": {}, "scenes": [],
        }
        self.store.update_run(self.run["task_id"], video_review_snapshot={
            "projection": projection, "state_sha256": HASH_A,
        })
        params = {"revision": 2, "review_sha256": HASH_A,
                  "lesson_ir_sha256": HASH_B, "event_cursor": 8}
        queued, _ = self._reserve("approve-success", action="approve", parameters=params)
        running = self.store.claim_next_video_operation()
        approved = {
            "protocol_version": 1, "task_id": "worker-client-a",
            "status": "approved", "phase": "approval_pending", "event_cursor": 9,
            "revision": 2, "review_sha256": HASH_A, "lesson_ir_sha256": HASH_B,
            "approval": {
                "approval_id": "00000000-0000-4000-8000-000000000001",
                "revision": 2, "review_sha256": HASH_A, "lesson_ir_sha256": HASH_B,
                "granted_at": "2026-09-27T12:00:00.000Z",
            },
        }
        altered = dict(approved, review_sha256="c" * 64, approval={
            **approved["approval"], "review_sha256": "c" * 64,
        })
        with self.assertRaises(ValueError):
            self.store.finish_video_operation(
                running["operation_id"], running["attempt_id"], "succeeded", result=altered)
        with self.assertRaises(ValueError):
            self.store.finish_video_operation(
                running["operation_id"], running["attempt_id"], "succeeded",
                result={"task_id": "worker-client-a", "status": "approved",
                        "phase": "approval_pending", "event_cursor": 9})
        saved = self.store.finish_video_operation(
            queued["operation_id"], running["attempt_id"], "succeeded", result=approved)
        self.assertEqual("succeeded", saved["status"])

    def test_failed_and_unknown_actions_cannot_persist_worker_result(self) -> None:
        queued, _ = self._reserve("no-result")
        running = self.store.claim_next_video_operation()
        with self.assertRaises(ValueError):
            self.store.finish_video_operation(
                queued["operation_id"], running["attempt_id"], "failed",
                result=self._inspection("worker-client-a"), error="worker_failed")
        saved = self.store.finish_video_operation(
            queued["operation_id"], running["attempt_id"], "failed", error="worker_failed")
        self.assertIsNone(saved["result"])

    def test_reservation_rejects_exhausted_queue_sequence(self) -> None:
        queued, _ = self._reserve("sequence-exhausted")
        path = self.store.path
        data = json.loads(path.read_text(encoding="utf-8"))
        data["video_operations"][queued["operation_id"]]["queue_sequence"] = 2**53 - 1
        path.write_text(json.dumps(data), encoding="utf-8")
        self.store._data = data
        with self.assertRaises(ConflictError):
            self._reserve("sequence-exhausted-2", run=self._make_run(self.conn, "client-third"))

    def test_reopen_recovers_running_to_unknown_and_preserves_queued(self) -> None:
        running_queued, _ = self._reserve("will-run")
        running = self.store.claim_next_video_operation()
        other_connection = self._pair("device-other")
        other_run = self._make_run(other_connection, "client-other")
        remains_queued, _ = self._reserve("stays-queued", run=other_run, connection=other_connection)
        state_file = self.store.path
        self.store.close()
        self.store = StateStore(state_file.parent, clock=lambda: self.now[0])
        self.assertEqual(1, self.store.recover_video_operations())
        snapshot = self.store.snapshot()["video_operations"]
        self.assertEqual("unknown", snapshot[running_queued["operation_id"]]["status"])
        self.assertEqual("queued", snapshot[remains_queued["operation_id"]]["status"])
        self.assertEqual(0, self.store.recover_video_operations())
        self.assertEqual(running["attempt_id"], snapshot[running["operation_id"]]["attempt_id"])

    def test_queue_order_survives_json_key_sorting_and_same_second_timestamps(self) -> None:
        first_run = self.run
        second_run = self._make_run(self.conn, "client-second")
        first, _ = self._reserve("first", run=first_run)
        second, _ = self._reserve("second", run=second_run)
        self.assertEqual(first["created_at"], second["created_at"])
        self.assertLess(first["queue_sequence"], second["queue_sequence"])
        path = self.store.path
        self.store.close()
        self.store = StateStore(path.parent, clock=lambda: self.now[0])
        claimed = self.store.claim_next_video_operation()
        self.assertEqual(first["operation_id"], claimed["operation_id"])
        result = self._inspection("worker-client-a")
        self.store.finish_video_operation(claimed["operation_id"], claimed["attempt_id"],
                                          "succeeded", result=result)
        claimed_second = self.store.claim_next_video_operation()
        self.assertEqual(second["operation_id"], claimed_second["operation_id"])

    def test_clock_rollback_does_not_make_updated_at_older_than_created(self) -> None:
        record, _ = self._reserve("clock")
        self.now[0] = 1
        cancelled = self.store.cancel_queued_video_operation(
            self.conn, self.run["task_id"], record["operation_id"])
        self.assertEqual(record["created_at"], cancelled["updated_at"])

    def test_persist_failure_rolls_back_reservation_and_claim_in_memory(self) -> None:
        before_file = self.store.path.read_bytes()
        before_memory = self.store.snapshot()
        original_save = self.store._save
        self.store._save = lambda _data=None: (_ for _ in ()).throw(OSError("disk full"))
        try:
            with self.assertRaises(OSError):
                self._reserve("disk-failure")
            self.assertEqual(before_memory, self.store.snapshot())
            self.assertEqual(before_file, self.store.path.read_bytes())
        finally:
            self.store._save = original_save
        queued, _ = self._reserve("claim-failure")
        before_file = self.store.path.read_bytes()
        before_memory = self.store.snapshot()
        self.store._save = lambda _data=None: (_ for _ in ()).throw(OSError("disk full"))
        try:
            with self.assertRaises(OSError):
                self.store.claim_next_video_operation()
            self.assertEqual(before_memory, self.store.snapshot())
            self.assertEqual(before_file, self.store.path.read_bytes())
        finally:
            self.store._save = original_save
        self.assertEqual("queued", self.store.snapshot()["video_operations"][queued["operation_id"]]["status"])

    def test_owner_checks_and_detached_owned_result(self) -> None:
        queued, _ = self._reserve("owned")
        other = self._pair("device-other")
        with self.assertRaises(AuthorizationError):
            self.store.owned_video_operation(other, self.run["task_id"], queued["operation_id"])
        returned = self.store.owned_video_operation(self.conn, self.run["task_id"], queued["operation_id"])
        returned["video_binding"]["worker_task_id"] = "mutated"
        self.assertEqual("worker-client-a", self.store.owned_video_operation(
            self.conn, self.run["task_id"], queued["operation_id"])["video_binding"]["worker_task_id"])

    def test_running_cancel_request_is_durable_idempotent_and_attempt_bound(self) -> None:
        queued, _ = self._reserve("cancel-storyboard", action="storyboard",
                                  parameters={"revision": 1, "event_cursor": 1})
        running = self.store.claim_next_video_operation()
        requested, created = self.store.request_running_video_cancel(
            self.conn, self.run["task_id"], running["operation_id"])
        self.assertTrue(created)
        self.assertEqual(running["attempt_id"], requested["cancel_request"]["attempt_id"])
        repeated, created_again = self.store.request_running_video_cancel(
            self.conn, self.run["task_id"], running["operation_id"])
        self.assertFalse(created_again)
        self.assertEqual(requested["cancel_request"], repeated["cancel_request"])
        self.assertEqual("running", repeated["status"])
        self.assertEqual(1, self.store.recover_video_operations())
        recovered = self.store.snapshot()["video_operations"][queued["operation_id"]]
        self.assertEqual("unknown", recovered["status"])
        self.assertEqual(requested["cancel_request"], recovered["cancel_request"])
        retry_after_restart, created_after_restart = self.store.request_running_video_cancel(
            self.conn, self.run["task_id"], queued["operation_id"])
        self.assertFalse(created_after_restart)
        self.assertEqual(requested["cancel_request"], retry_after_restart["cancel_request"])
        unconfirmed = self.store.mark_video_cancel_unconfirmed(self.conn, retry_after_restart)
        self.assertEqual("unconfirmed", unconfirmed["cancel_request"]["control_status"])
        self.assertEqual("unknown", unconfirmed["status"])
        self.assertEqual("unconfirmed", self.store.snapshot()["video_operations"][queued["operation_id"]]
                         ["cancel_request"]["control_status"])

    def test_verified_cancel_cas_wins_and_suppresses_late_same_attempt_finish(self) -> None:
        queued, _ = self._reserve("cancel-cas", action="storyboard",
                                  parameters={"revision": 1, "event_cursor": 1})
        running = self.store.claim_next_video_operation()
        requested, _ = self.store.request_running_video_cancel(
            self.conn, self.run["task_id"], running["operation_id"])
        cancelled = self.store.finish_cancelled_video_operation(self.conn, requested)
        self.assertEqual("cancelled", cancelled["status"])
        late = self.store.finish_video_operation(
            running["operation_id"], running["attempt_id"], "unknown", error="worker_interrupted")
        self.assertEqual(cancelled, late)
        self.assertEqual("cancelled", self.store.snapshot()["video_operations"][queued["operation_id"]]["status"])
        self.store.close()
        self.store = StateStore(self.root / "state", clock=lambda: self.now[0])
        reopened = self.store.snapshot()["video_operations"][queued["operation_id"]]
        self.assertEqual("cancelled", reopened["status"])
        self.assertEqual(cancelled["cancel_request"], reopened["cancel_request"])

    def test_cancel_finish_requires_full_expected_cas(self) -> None:
        queued, _ = self._reserve("cancel-cas-invalid", action="storyboard",
                                  parameters={"revision": 1, "event_cursor": 1})
        running = self.store.claim_next_video_operation()
        requested, _ = self.store.request_running_video_cancel(
            self.conn, self.run["task_id"], running["operation_id"])
        for mutate in (
            lambda record: record["cancel_request"].update(control_id="33333333-3333-4333-8333-333333333333"),
            lambda record: record.update(source_snapshot_digest="c" * 64),
            lambda record: record.update(payload_digest="d" * 64),
        ):
            altered = json.loads(json.dumps(requested))
            mutate(altered)
            with self.assertRaises((ConflictError, ValueError)):
                self.store.finish_cancelled_video_operation(self.conn, altered)
            self.assertEqual("running", self.store.snapshot()["video_operations"][queued["operation_id"]]["status"])
        self.assertEqual("cancelled", self.store.finish_cancelled_video_operation(
            self.conn, requested)["status"])

    def test_cancel_request_authorization_and_binding_are_rechecked(self) -> None:
        queued, _ = self._reserve("cancel-auth", action="storyboard",
                                  parameters={"revision": 1, "event_cursor": 1})
        running = self.store.claim_next_video_operation()
        revoked = self._pair("device-other")
        with self.assertRaises(AuthorizationError):
            self.store.request_running_video_cancel(revoked, self.run["task_id"], running["operation_id"])
        self.store.update_run(self.run["task_id"], source_snapshot_digest="c" * 64)
        with self.assertRaises(ConflictError):
            self.store.request_running_video_cancel(self.conn, self.run["task_id"], running["operation_id"])
        self.assertIsNone(self.store.snapshot()["video_operations"][queued["operation_id"]]["cancel_request"])

    def test_per_task_limit_never_discards_idempotency_records(self) -> None:
        for index in range(MAX_VIDEO_OPERATIONS_PER_TASK):
            record, _ = self._reserve(f"limit-{index}")
            running = self.store.claim_next_video_operation()
            self.store.finish_video_operation(
                running["operation_id"], running["attempt_id"], "succeeded",
                result=self._inspection("worker-client-a"))
        with self.assertRaises(ConflictError):
            self._reserve("limit-overflow")
        self.assertEqual(MAX_VIDEO_OPERATIONS_PER_TASK,
                         len(self.store.snapshot()["video_operations"]))

    def test_legacy_missing_table_migrates_but_corrupt_table_or_record_fails_startup(self) -> None:
        path = self.store.path
        self.store.close()
        state = json.loads(path.read_text(encoding="utf-8"))
        del state["video_operations"]
        del state["video_operations_version"]
        path.write_text(json.dumps(state), encoding="utf-8")
        self.store = StateStore(path.parent, clock=lambda: self.now[0])
        self.assertEqual({}, self.store.snapshot()["video_operations"])
        self.assertEqual(2, self.store.snapshot()["video_operations_version"])
        self.store.close()
        state = json.loads(path.read_text(encoding="utf-8"))
        state["video_operations"] = []
        path.write_text(json.dumps(state), encoding="utf-8")
        with self.assertRaises(StateError):
            StateStore(path.parent)

    def test_marked_missing_table_and_wrong_marker_fail_without_rewriting(self) -> None:
        path = self.store.path
        self.store.close()
        original = json.loads(path.read_text(encoding="utf-8"))
        missing_table = dict(original)
        del missing_table["video_operations"]
        path.write_text(json.dumps(missing_table), encoding="utf-8")
        before = path.read_bytes()
        with self.assertRaises(StateError):
            StateStore(path.parent)
        self.assertEqual(before, path.read_bytes())

        wrong_marker = dict(original, video_operations_version=3)
        path.write_text(json.dumps(wrong_marker), encoding="utf-8")
        before = path.read_bytes()
        with self.assertRaises(StateError):
            StateStore(path.parent)
        self.assertEqual(before, path.read_bytes())

    def test_unmarked_bad_existing_table_fails_without_adding_marker(self) -> None:
        path = self.store.path
        self.store.close()
        state = json.loads(path.read_text(encoding="utf-8"))
        del state["video_operations_version"]
        state["video_operations"] = []
        path.write_text(json.dumps(state), encoding="utf-8")
        before = path.read_bytes()
        with self.assertRaises(StateError):
            StateStore(path.parent)
        self.assertEqual(before, path.read_bytes())

    def test_unmarked_valid_existing_table_is_verified_then_marked(self) -> None:
        record, _ = self._reserve("unmarked-valid")
        path = self.store.path
        self.store.close()
        state = json.loads(path.read_text(encoding="utf-8"))
        del state["video_operations_version"]
        state["video_operations"][record["operation_id"]].pop("cancel_request", None)
        path.write_text(json.dumps(state), encoding="utf-8")
        self.store = StateStore(path.parent, clock=lambda: self.now[0])
        snapshot = self.store.snapshot()
        self.assertEqual(2, snapshot["video_operations_version"])
        self.assertEqual(record["operation_id"], next(iter(snapshot["video_operations"])))
        self.assertIsNone(snapshot["video_operations"][record["operation_id"]]["cancel_request"])
        self.assertEqual(record["payload_digest"], snapshot["video_operations"][record["operation_id"]]["payload_digest"])

    def test_v1_migration_save_failure_preserves_original_bytes(self) -> None:
        record, _ = self._reserve("v1-save-failure")
        path = self.store.path
        self.store.close()
        state = json.loads(path.read_text(encoding="utf-8"))
        state["video_operations_version"] = 1
        state["video_operations"][record["operation_id"]].pop("cancel_request", None)
        path.write_text(json.dumps(state), encoding="utf-8")
        before = path.read_bytes()
        with patch.object(StateStore, "_save", side_effect=OSError("synthetic migration failure")):
            with self.assertRaises(OSError):
                StateStore(path.parent)
        self.assertEqual(before, path.read_bytes())

    def test_v1_migration_rejects_corruption_before_rewrite(self) -> None:
        record, _ = self._reserve("v1-corrupt")
        path = self.store.path
        self.store.close()
        state = json.loads(path.read_text(encoding="utf-8"))
        state["video_operations_version"] = 1
        state["video_operations"][record["operation_id"]]["payload_digest"] = "0" * 64
        path.write_text(json.dumps(state), encoding="utf-8")
        before = path.read_bytes()
        with self.assertRaises(StateError):
            StateStore(path.parent)
        self.assertEqual(before, path.read_bytes())

    def test_v2_cancel_request_without_control_status_fails_closed(self) -> None:
        record, _ = self._reserve("v2-cancel-old-shape", action="storyboard",
                                  parameters={"revision": 1, "event_cursor": 1})
        running = self.store.claim_next_video_operation()
        requested, _ = self.store.request_running_video_cancel(
            self.conn, self.run["task_id"], running["operation_id"])
        path = self.store.path
        self.store.close()
        state = json.loads(path.read_text(encoding="utf-8"))
        cancel_request = state["video_operations"][record["operation_id"]]["cancel_request"]
        del cancel_request["control_status"]
        path.write_text(json.dumps(state), encoding="utf-8")
        before = path.read_bytes()
        with self.assertRaises(StateError):
            StateStore(path.parent)
        self.assertEqual(before, path.read_bytes())

    def test_legacy_storyboard_record_loads_without_rewriting_digest_or_cursor(self) -> None:
        record, _ = self._reserve("legacy-storyboard", action="storyboard",
                                  parameters={"revision": 1, "event_cursor": 1})
        path = self.store.path
        self.store.close()
        data = json.loads(path.read_text(encoding="utf-8"))
        persisted = data["video_operations"][record["operation_id"]]
        persisted["parameters"] = {"revision": 1}
        persisted["payload_digest"] = payload_digest("storyboard", {"revision": 1})
        path.write_text(json.dumps(data), encoding="utf-8")
        before = path.read_bytes()
        self.store = StateStore(path.parent, clock=lambda: self.now[0])
        loaded = self.store.snapshot()["video_operations"][record["operation_id"]]
        self.assertEqual({"revision": 1}, loaded["parameters"])
        self.assertEqual(payload_digest("storyboard", {"revision": 1}), loaded["payload_digest"])
        self.assertEqual(before, path.read_bytes())

    def test_legacy_max_cursor_approval_loads_but_new_admission_rejects_it(self) -> None:
        from padnote_assistant.video_operations import normalize_parameters

        params = {"revision": 1, "event_cursor": 8,
                  "review_sha256": HASH_A, "lesson_ir_sha256": HASH_B}
        self.store.update_run(self.run["task_id"], video_review_snapshot={
            "projection": {"protocol_version": 1, "task_id": "worker-client-a",
                           "status": "awaiting_storyboard_review", "event_cursor": 8,
                           "revision": 1, "review_sha256": HASH_A,
                           "lesson_ir_sha256": HASH_B, "episode": {}, "scenes": []},
            "state_sha256": HASH_A,
        })
        record, _ = self._reserve("max-legacy-approval", action="approve", parameters=params)
        with self.assertRaises(ValueError):
            normalize_parameters("approve", {**params, "event_cursor": 2**53 - 1})

        path = self.store.path
        self.store.close()
        data = json.loads(path.read_text(encoding="utf-8"))
        saved = data["video_operations"][record["operation_id"]]
        saved["parameters"]["event_cursor"] = 2**53 - 1
        saved["payload_digest"] = payload_digest("approve", saved["parameters"])
        path.write_text(json.dumps(data), encoding="utf-8")
        before = path.read_bytes()
        self.store = StateStore(path.parent, clock=lambda: self.now[0])
        loaded = self.store.snapshot()["video_operations"][record["operation_id"]]
        self.assertEqual(2**53 - 1, loaded["parameters"]["event_cursor"])
        self.assertEqual(before, path.read_bytes())

    def test_corrupt_persisted_record_digest_fails_startup(self) -> None:
        record, _ = self._reserve("corrupt")
        path = self.store.path
        self.store.close()
        data = json.loads(path.read_text(encoding="utf-8"))
        data["video_operations"][record["operation_id"]]["payload_digest"] = HASH_B
        path.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaises(StateError):
            StateStore(path.parent)


if __name__ == "__main__":
    unittest.main()
