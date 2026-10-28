from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

from padnote_assistant.security import ValidationError
from padnote_assistant.state import StateStore


class RunControlStateTests(unittest.TestCase):
    def test_single_action_updates_merge_and_reject_stale_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            with StateStore(Path(directory)) as store:
                instance = store.add_instance("hermes", "Fixture", "http://127.0.0.1:8642")
                store.update_instance_check(instance["instance_id"], health="ready", detail="ready",
                                            features={"run_submission": True}, executable=True)
                code, _ = store.create_pair_code(instance["instance_id"], "https://computer.example.ts.net")
                request = store.request_pair(code, "device-a", "Tablet")
                store.decide_pair(request["request_id"], True)
                _, claim = store.claim_pair(request["request_id"], request["poll_token"])
                connection = store.authenticate(instance["instance_id"], claim["connections"][0]["token"])
                run, _ = store.create_or_get_run(connection, "merge-task", {"input": "test"})
                stop = {"kind": "stop", "approval_id": "", "decision": "", "state": "queued",
                        "attempt_id": "a" * 32, "requested_at": 10, "updated_at": 11}
                approval_id = "approval-merge"
                approval_key = "approval:" + hashlib.sha256(approval_id.encode("utf-8")).hexdigest()
                approval = {"kind": "approval", "approval_id": approval_id, "decision": "once",
                            "state": "queued", "attempt_id": "b" * 32,
                            "requested_at": 12, "updated_at": 13}
                store.update_control_action(run["task_id"], "stop", stop)
                store.update_control_action(run["task_id"], approval_key, approval)
                saved = store._data["runs"][run["task_id"]]
                self.assertEqual({"stop": stop, approval_key: approval}, saved["control_actions"])
                before = dict(saved)
                replacement = dict(stop, state="confirmed", updated_at=14)
                with self.assertRaises(Exception):
                    store.update_control_action(run["task_id"], "stop", replacement,
                                                expected_attempt_id="c" * 32)
                self.assertEqual(before, store._data["runs"][run["task_id"]])

    def test_control_actions_persist_and_invalid_updates_leave_run_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            state_dir = Path(directory)
            store = StateStore(state_dir)
            try:
                instance = store.add_instance("hermes", "Fixture", "http://127.0.0.1:8642")
                store.update_instance_check(instance["instance_id"], health="ready", detail="ready",
                                            features={"run_submission": True}, executable=True)
                code, _ = store.create_pair_code(instance["instance_id"], "https://computer.example.ts.net")
                request = store.request_pair(code, "device-a", "Tablet")
                store.decide_pair(request["request_id"], True)
                _, claim = store.claim_pair(request["request_id"], request["poll_token"])
                connection = store.authenticate(instance["instance_id"], claim["connections"][0]["token"])
                run, _ = store.create_or_get_run(connection, "control-task", {"input": "test"})
                approval_id = "approval-1"
                actions = {
                    "stop": {"kind": "stop", "approval_id": "", "decision": "", "state": "queued",
                             "attempt_id": "a" * 32, "requested_at": 100, "updated_at": 101},
                    "approval:" + hashlib.sha256(approval_id.encode("utf-8")).hexdigest(): {
                        "kind": "approval", "approval_id": approval_id, "decision": "once",
                        "state": "confirmed", "attempt_id": "b" * 32,
                        "requested_at": 102, "updated_at": 103},
                }
                store.update_run(run["task_id"], control_actions=actions)
            finally:
                store.close()
            with StateStore(state_dir) as reopened:
                saved = reopened._data["runs"][run["task_id"]]
                self.assertEqual(actions, saved["control_actions"])
                original = dict(saved)
                invalid_actions = []
                bad_phase = {key: dict(value) for key, value in actions.items()}
                bad_phase["stop"]["state"] = "waiting"
                invalid_actions.append(bad_phase)
                extra_field = {key: dict(value) for key, value in actions.items()}
                extra_field["stop"]["extra"] = True
                invalid_actions.append(extra_field)
                bool_time = {key: dict(value) for key, value in actions.items()}
                bool_time["stop"]["requested_at"] = True
                invalid_actions.append(bool_time)
                wrong_hash = {key: dict(value) for key, value in actions.items()}
                wrong_hash["approval:" + "0" * 64] = wrong_hash.pop(
                    "approval:" + hashlib.sha256(approval_id.encode("utf-8")).hexdigest())
                invalid_actions.append(wrong_hash)
                for invalid in invalid_actions:
                    with self.assertRaises(ValidationError):
                        reopened.update_run(run["task_id"], control_actions=invalid)
                    self.assertEqual(original, reopened._data["runs"][run["task_id"]])

    def test_legacy_run_reopen_does_not_add_control_actions(self):
        with tempfile.TemporaryDirectory() as directory:
            state_dir = Path(directory)
            with StateStore(state_dir) as store:
                instance = store.add_instance("hermes", "Fixture", "http://127.0.0.1:8642")
                store.update_instance_check(instance["instance_id"], health="ready", detail="ready",
                                            features={"run_submission": True}, executable=True)
                code, _ = store.create_pair_code(instance["instance_id"], "https://computer.example.ts.net")
                request = store.request_pair(code, "device-a", "Tablet")
                store.decide_pair(request["request_id"], True)
                _, claim = store.claim_pair(request["request_id"], request["poll_token"])
                connection = store.authenticate(instance["instance_id"], claim["connections"][0]["token"])
                run, _ = store.create_or_get_run(connection, "legacy-task", {"input": "test"})
            with StateStore(state_dir) as reopened:
                self.assertNotIn("control_actions", reopened._data["runs"][run["task_id"]])


if __name__ == "__main__":
    unittest.main()
