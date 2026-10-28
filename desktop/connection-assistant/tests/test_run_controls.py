from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from padnote_assistant.run_controls import RunControlQueue
from padnote_assistant.state import StateStore


def record(kind: str, state: str, *, approval_id: str = "", decision: str = "") -> dict:
    return {"kind": kind, "approval_id": approval_id, "decision": decision,
            "state": state, "attempt_id": "a" * 32, "requested_at": 10, "updated_at": 11}


class RunControlQueueTests(unittest.TestCase):
    def _run(self, store: StateStore, control_actions: dict, *, status: str = "running",
             approval: dict | None = None) -> dict:
        instance = store.add_instance("hermes", "Fixture", "http://127.0.0.1:8642")
        store.update_instance_check(instance["instance_id"], health="ready", detail="ready",
                                    features={"run_submission": True}, executable=True)
        code, _ = store.create_pair_code(instance["instance_id"], "https://computer.example.ts.net")
        request = store.request_pair(code, "device-a", "Tablet")
        store.decide_pair(request["request_id"], True)
        _, claim = store.claim_pair(request["request_id"], request["poll_token"])
        connection = store.authenticate(instance["instance_id"], claim["connections"][0]["token"])
        run, _ = store.create_or_get_run(connection, "control-task", {"input": "test"})
        fields = {"control_actions": control_actions, "status": status}
        if approval is not None:
            fields["approval"] = approval
        return store.update_run(run["task_id"], **fields)

    def test_restart_recovers_queued_and_sending_without_network(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = StateStore(root)
            try:
                approval_id = "x"
                approval_key = "approval:" + hashlib.sha256(approval_id.encode("utf-8")).hexdigest()
                run = self._run(store, {"stop": record("stop", "queued"),
                                        approval_key: record("approval", "sending",
                                                             approval_id=approval_id, decision="deny")})
                task_id = run["task_id"]
            finally:
                store.close()
            with StateStore(root) as reopened:
                service = SimpleNamespace(store=reopened, clock=lambda: 100)
                RunControlQueue(service)
                actions = reopened._data["runs"][task_id]["control_actions"]
                self.assertEqual("not_sent", actions["stop"]["state"])
                self.assertEqual("unknown", actions[approval_key]["state"])
                self.assertEqual(100, actions["stop"]["updated_at"])
                self.assertEqual(100, actions[approval_key]["updated_at"])

    def test_confirmed_approval_is_hidden_with_explanation_without_mutating_run(self):
        with tempfile.TemporaryDirectory() as directory:
            with StateStore(Path(directory)) as store:
                approval = {"approval_id": "approval-1", "message": "Proceed?"}
                approval_id = "approval-1"
                approval_key = "approval:" + hashlib.sha256(approval_id.encode("utf-8")).hexdigest()
                run = self._run(store, {approval_key: record("approval", "confirmed",
                                                             approval_id=approval_id, decision="once")},
                                status="waiting_for_approval", approval=approval)
                original = dict(run)
                value = {"status": run["status"], "approval": approval}
                projected = RunControlQueue(SimpleNamespace(store=store)).project(run, value)
                self.assertNotIn("approval", projected)
                self.assertIn("delivered", projected["control_message"])
                self.assertEqual(projected["control_message"], projected["error"])
                self.assertEqual(original, run)

    def test_confirmed_stop_projects_stopping_but_terminal_run_stays_terminal(self):
        with tempfile.TemporaryDirectory() as directory:
            with StateStore(Path(directory)) as store:
                actions = {"stop": record("stop", "confirmed")}
                run = self._run(store, actions, status="running")
                queue = RunControlQueue(SimpleNamespace(store=store))
                self.assertEqual("stopping", queue.project(run, {"status": "running"})["status"])
                run["status"] = "completed"
                projected = queue.project(run, {"status": "completed"})
                self.assertEqual("completed", projected["status"])
                self.assertEqual("confirmed", projected["stop_delivery"])
                self.assertNotIn("error", projected)
                self.assertNotIn("control_message", projected)
                run["status"] = "failed"
                projected_failed = queue.project(
                    run, {"status": "failed", "error": "actual Hermes failure"})
                self.assertEqual("failed", projected_failed["status"])
                self.assertEqual("actual Hermes failure", projected_failed["error"])
                self.assertEqual("confirmed", projected_failed["stop_delivery"])


if __name__ == "__main__":
    unittest.main()
