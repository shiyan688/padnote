from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from padnote_assistant.state import ConflictError, StateStore, ValidationError
from padnote_assistant.video_operations import (
    VideoOperationInvalid,
    normalize_parameters,
    payload_digest,
    validate_result,
    validate_video_operations,
)


REVIEW = "a" * 64
IR = "b" * 64
FEEDBACK = "c" * 64
REQUEST = "d" * 64

# The Skill hashes action + the same parameter projection into the payload
# digest, so the two languages have to canonicalise identically or every
# revision is refused. This value was produced by really running the Skill's
# own payloadDigest, not by mirroring the Python implementation:
#
#   node --import tsx /tmp/digest-check.ts
#   payloadDigest('revise', {revision: 2, event_cursor: 8,
#     review_sha256: 'a'*64, lesson_ir_sha256: 'b'*64, feedback_sha256: 'c'*64})
#
# It is pinned here so a change to either side's canonicalisation fails loudly
# instead of silently breaking reservations at runtime.
SKILL_PAYLOAD_DIGEST = "76384f6857f1d10b5cd268ee5ed288e557990ba1190c9158e10b91bf91d25649"

PARAMETERS = {
    "revision": 2,
    "review_sha256": REVIEW,
    "lesson_ir_sha256": IR,
    "feedback_sha256": FEEDBACK,
    "event_cursor": 8,
}

PROJECTION = {
    "protocol_version": 1,
    "task_id": "worker-client-a",
    "status": "awaiting_storyboard_review",
    "event_cursor": 8,
    "revision": 2,
    "review_sha256": REVIEW,
    "lesson_ir_sha256": IR,
    "episode": {},
    "scenes": [],
}


class RevisionLedgerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.now = [100]
        self.store = StateStore(self.root / "state", clock=lambda: self.now[0])
        self.instance = self.store.add_instance("hermes", "Fixture", "http://127.0.0.1:8642")
        self.store.update_instance_check(self.instance["instance_id"], health="ready",
                                         detail="ready", features={"run_submission": True},
                                         executable=True)
        code, _ = self.store.create_pair_code(
            self.instance["instance_id"], "https://computer.example.ts.net")
        request = self.store.request_pair(code, "device-a", "Test tablet")
        self.store.decide_pair(request["request_id"], True)
        _, claimed = self.store.claim_pair(request["request_id"], request["poll_token"])
        self.conn = self.store.authenticate(
            self.instance["instance_id"], claimed["connections"][0]["token"])
        run, _ = self.store.create_or_get_run(
            self.conn, "client-a", {"client_task_id": "client-a", "input": "fixture"})
        self.run = self.store.update_run(
            run["task_id"], source_snapshot_digest=REVIEW,
            video_binding={"worker_task_id": "worker-client-a", "request_sha256": REQUEST})
        self.store.update_run(self.run["task_id"], video_review_snapshot={
            "projection": dict(PROJECTION), "state_sha256": REVIEW})

    def tearDown(self) -> None:
        self.store.close()
        self.temp.cleanup()

    def _reserve(self, key: str = "revise-1", parameters: dict | None = None):
        return self.store.reserve_video_operation(
            self.conn, self.run["task_id"], key, "revise",
            PARAMETERS if parameters is None else parameters)

    def _running(self):
        queued, _ = self._reserve()
        return self.store.claim_next_video_operation()

    @staticmethod
    def receipt(record: dict, **overrides) -> dict:
        value = {
            "schema_version": 1,
            "operation_id": record["operation_id"],
            "attempt_id": record["attempt_id"],
            "task_id": record["video_binding"]["worker_task_id"],
            "action": "revise",
            "payload_digest": record["payload_digest"],
            "source_snapshot_digest": record["source_snapshot_digest"],
            "request_sha256": record["video_binding"]["request_sha256"],
            "input_event_cursor": record["parameters"]["event_cursor"],
            "revision": record["parameters"]["revision"],
            "review_sha256": record["parameters"]["review_sha256"],
            "lesson_ir_sha256": record["parameters"]["lesson_ir_sha256"],
            "feedback_sha256": record["parameters"]["feedback_sha256"],
            "candidate_path": f"work/revise/{record['operation_id']}/candidate-lesson-ir.json",
        }
        value.update(overrides)
        return value

    def test_revision_parameters_are_normalized_and_bad_shapes_rejected(self) -> None:
        normalized = normalize_parameters("revise", dict(PARAMETERS))
        self.assertEqual(
            {"revision", "review_sha256", "lesson_ir_sha256", "feedback_sha256", "event_cursor"},
            set(normalized))
        self.assertEqual(8, normalized["event_cursor"])

        missing = {key: value for key, value in PARAMETERS.items() if key != "feedback_sha256"}
        invalid = (
            ("missing feedback", missing),
            ("extra key", {**PARAMETERS, "feedback": "text"}),
            ("boolean revision", {**PARAMETERS, "revision": True}),
            ("oversized revision", {**PARAMETERS, "revision": 2**53}),
            ("zero cursor", {**PARAMETERS, "event_cursor": 0}),
            ("cursor without headroom", {**PARAMETERS, "event_cursor": 2**53 - 1}),
            ("short feedback digest", {**PARAMETERS, "feedback_sha256": "abc"}),
            ("uppercase digest", {**PARAMETERS, "feedback_sha256": "C" * 64}),
        )
        for label, parameters in invalid:
            with self.subTest(label=label), self.assertRaises(VideoOperationInvalid):
                normalize_parameters("revise", parameters)

    def test_reserved_payload_digest_matches_the_skill_projection(self) -> None:
        # End to end: the digest the ledger persists is the one the Skill
        # computes for the same action and projection.
        record, created = self._reserve()
        self.assertTrue(created)
        self.assertEqual(SKILL_PAYLOAD_DIGEST, record["payload_digest"])
        self.assertEqual(SKILL_PAYLOAD_DIGEST, payload_digest("revise", record["parameters"]))

    def test_reservation_refuses_a_review_it_cannot_still_be_applied_to(self) -> None:
        stale = (
            ("revision", {**PARAMETERS, "revision": 3}),
            ("review digest", {**PARAMETERS, "review_sha256": "e" * 64}),
            ("IR digest", {**PARAMETERS, "lesson_ir_sha256": "f" * 64}),
            ("event cursor", {**PARAMETERS, "event_cursor": 9}),
        )
        for label, parameters in stale:
            with self.subTest(label=label), self.assertRaises(ConflictError):
                self._reserve("stale-" + label.replace(" ", "-"), parameters)
        self.assertEqual({}, self.store.snapshot()["video_operations"])

    def test_an_approved_or_moving_review_cannot_be_revised(self) -> None:
        for status in ("approved", "storyboard", "initialized"):
            with self.subTest(status=status):
                self.store.update_run(self.run["task_id"], video_review_snapshot={
                    "projection": {**PROJECTION, "status": status}, "state_sha256": REVIEW})
                with self.assertRaises(ConflictError):
                    self._reserve("status-" + status)
        self.store.update_run(self.run["task_id"], video_review_snapshot=None)
        with self.assertRaises(ConflictError):
            self._reserve("no-snapshot")

    def test_reservation_retries_are_idempotent_and_keys_are_not_reusable(self) -> None:
        first, created = self._reserve("same-key")
        self.assertTrue(created)
        again, created_again = self._reserve("same-key")
        self.assertFalse(created_again)
        self.assertEqual(first["operation_id"], again["operation_id"])
        with self.assertRaises(ConflictError):
            self._reserve("same-key", {**PARAMETERS, "feedback_sha256": "e" * 64})

    def test_receipt_is_verified_against_the_reservation_field_by_field(self) -> None:
        running = self._running()
        self.assertEqual(running["operation_id"], self._reserve()[0]["operation_id"])

        tampered = (
            ("operation id", {"operation_id": "99999999-9999-4999-8999-999999999999"}),
            ("attempt id", {"attempt_id": "99999999-9999-4999-8999-999999999999"}),
            ("task id", {"task_id": "worker-other"}),
            ("payload digest", {"payload_digest": "0" * 64}),
            ("source digest", {"source_snapshot_digest": "0" * 64}),
            ("request digest", {"request_sha256": "0" * 64}),
            ("revision", {"revision": 3}),
            ("observed cursor", {"input_event_cursor": 9}),
            ("review digest", {"review_sha256": "0" * 64}),
            ("IR digest", {"lesson_ir_sha256": "0" * 64}),
            ("feedback digest", {"feedback_sha256": "0" * 64}),
            ("schema version", {"schema_version": 2}),
            ("action", {"action": "approve"}),
            ("extra field", {"inspection": True}),
            ("absolute candidate path", {"candidate_path": "/tmp/candidate-lesson-ir.json"}),
            ("traversing candidate path",
             {"candidate_path": "work/revise/../../candidate-lesson-ir.json"}),
            ("another operations directory",
             {"candidate_path": "work/revise/99999999-9999-4999-8999-999999999999/"
                                "candidate-lesson-ir.json"}),
            ("renamed candidate",
             {"candidate_path": f"work/revise/{running['operation_id']}/lesson.ir.json"}),
        )
        for label, override in tampered:
            with self.subTest(label=label), self.assertRaises(VideoOperationInvalid):
                validate_result(self.receipt(running, **override), "worker-client-a", "revise",
                                running["parameters"], expected_record=running)

        missing = self.receipt(running)
        missing.pop("feedback_sha256")
        with self.assertRaises(VideoOperationInvalid):
            validate_result(missing, "worker-client-a", "revise", running["parameters"],
                            expected_record=running)

        accepted = validate_result(self.receipt(running), "worker-client-a", "revise",
                                   running["parameters"], expected_record=running)
        self.assertEqual("revise", accepted["action"])

    def test_a_forged_reservation_digest_is_caught_by_recomputation(self) -> None:
        running = self._running()
        # A reservation whose digest disagrees with its own parameters cannot
        # vouch for a receipt: the digest is recomputed from the action and the
        # parameters rather than trusted from the record.
        forged = dict(running)
        forged["payload_digest"] = "0" * 64
        with self.assertRaises(VideoOperationInvalid):
            validate_result(self.receipt(forged), "worker-client-a", "revise",
                            running["parameters"], expected_record=forged)

    def test_receipt_is_refused_without_the_reservation_it_claims(self) -> None:
        running = self._running()
        with self.assertRaises(VideoOperationInvalid):
            validate_result(self.receipt(running), "worker-client-a", "revise",
                            running["parameters"])

    def test_finishing_a_revision_persists_only_a_matching_receipt(self) -> None:
        running = self._running()
        with self.assertRaises(ValidationError):
            self.store.finish_video_operation(
                running["operation_id"], running["attempt_id"], "succeeded",
                result=self.receipt(running, feedback_sha256="0" * 64))

        done = self.store.finish_video_operation(
            running["operation_id"], running["attempt_id"], "succeeded",
            result=self.receipt(running))
        self.assertEqual("succeeded", done["status"])
        self.assertEqual(SKILL_PAYLOAD_DIGEST, done["result"]["payload_digest"])

        # The persisted ledger must survive its own validator both ways.
        snapshot = self.store.snapshot()
        validate_video_operations(snapshot, version=2)
        tampered = self.store.snapshot()
        tampered["video_operations"][running["operation_id"]]["result"]["revision"] = 3
        with self.assertRaises(ValueError):
            validate_video_operations(tampered, version=2)

    def test_an_unknown_revision_reconciles_only_with_its_own_receipt(self) -> None:
        running = self._running()
        self.store.finish_video_operation(
            running["operation_id"], running["attempt_id"], "unknown", error="worker_unknown")
        unknown = self.store.owned_video_operation(
            self.conn, self.run["task_id"], running["operation_id"])
        self.assertEqual("unknown", unknown["status"])

        # Ordinary finish CAS cannot resolve unknown work, and a receipt for a
        # different revision must not be able to either.
        with self.assertRaises(ValidationError):
            self.store.reconcile_unknown_video_operation(
                self.conn, unknown, self.receipt(unknown, revision=3))
        done = self.store.reconcile_unknown_video_operation(
            self.conn, unknown, self.receipt(unknown))
        self.assertEqual("succeeded", done["status"])
        self.assertEqual(SKILL_PAYLOAD_DIGEST, done["result"]["payload_digest"])

    def test_revision_does_not_widen_the_other_actions(self) -> None:
        normalize_parameters("approve", {key: value for key, value in PARAMETERS.items()
                                         if key != "feedback_sha256"})
        with self.assertRaises(VideoOperationInvalid):
            normalize_parameters("approve", dict(PARAMETERS))
        with self.assertRaises(VideoOperationInvalid):
            normalize_parameters("storyboard", dict(PARAMETERS))
        with self.assertRaises(VideoOperationInvalid):
            normalize_parameters("revise-not-an-action", dict(PARAMETERS))

    def test_a_revision_receipt_is_not_accepted_for_another_action(self) -> None:
        running = self._running()
        for action in ("initialize", "storyboard", "approve"):
            with self.subTest(action=action), self.assertRaises(VideoOperationInvalid):
                validate_result(self.receipt(running), "worker-client-a", action,
                                running["parameters"], expected_record=running)


if __name__ == "__main__":
    unittest.main()
