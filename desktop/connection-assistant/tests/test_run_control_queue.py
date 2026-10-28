from __future__ import annotations

import tempfile
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from padnote_assistant.bridge import BridgeService
from padnote_assistant.hermes import HermesError
from padnote_assistant.run_controls import RunControlQueue
from padnote_assistant.state import ConflictError
from test_core import ready_instance, run_payload


class RunControlQueueIntegrationTests(unittest.TestCase):
    def test_stop_projection_precedes_retryable_approval_projection(self):
        queue = RunControlQueue(SimpleNamespace(store=SimpleNamespace(snapshot=lambda: {"runs": {}})))
        run = {
            "status": "waiting_for_approval",
            "approval": {"approval_id": "pc1:1:prompt-a"},
            "control_actions": {
                "stop": {"kind": "stop", "state": "queued"},
                "approval:key": {"kind": "approval", "approval_id": "pc1:1:prompt-a",
                                  "state": "unknown"},
            },
        }
        projected = queue.project(run, {"status": run["status"], "approval": run["approval"]})
        self.assertEqual("queued", projected["stop_delivery"])
        self.assertEqual("stopping", projected["status"])
        self.assertEqual(run["approval"], projected["approval"])
        self.assertIn("stop", projected["error"].lower())

    def test_active_stop_blocks_new_approval_without_upstream_approval_call(self):
        stop_entered = threading.Event()
        release_stop = threading.Event()
        approval_calls = []

        class FakeHermes:
            def __init__(self, *args, **kwargs):
                pass

            def create_run(self, *args, **kwargs):
                return {"run_id": "run_stop_first", "status": "started"}

            def stop_run(self, _run_id):
                stop_entered.set()
                release_stop.wait(timeout=5)
                return {"status": "stopping"}

            def approve_run(self, *args):
                approval_calls.append(args)

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", FakeHermes):
            service = BridgeService(Path(directory))
            try:
                instance, token, _ = ready_instance(service)
                _, receipt = service.submit_run(
                    instance["instance_id"], token, "client-task-1", run_payload())
                service.store.update_run(
                    receipt["task_id"], status="waiting_for_approval",
                    approval={"approval_id": "pc1:1:prompt-a"})
                self.assertEqual(202, service.stop_run(
                    instance["instance_id"], token, receipt["task_id"])[0])
                self.assertTrue(stop_entered.wait(timeout=2))
                with self.assertRaises(ConflictError):
                    service.approve_run(
                        instance["instance_id"], token, receipt["task_id"],
                        {"approval_id": "pc1:1:prompt-a", "decision": "once"})
                self.assertEqual([], approval_calls)
            finally:
                release_stop.set()
                self.assertTrue(service._run_controls.close(timeout=3))
                service.close()

    def test_blocked_stop_is_accepted_once_and_other_task_submits(self):
        stop_entered = threading.Event()
        release_stop = threading.Event()
        calls = []
        calls_lock = threading.Lock()

        class FakeHermes:
            def __init__(self, *args, **kwargs):
                pass

            def create_run(self, input_text, *args, **kwargs):
                with calls_lock:
                    calls.append(("create", input_text))
                    run_id = f"run_{len([call for call in calls if call[0] == 'create'])}"
                return {"run_id": run_id, "status": "started"}

            def stop_run(self, run_id):
                with calls_lock:
                    calls.append(("stop", run_id))
                stop_entered.set()
                if not release_stop.wait(timeout=5):
                    raise AssertionError("test did not release fake stop request")
                return {"status": "stopping"}

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", FakeHermes):
            service = BridgeService(Path(directory))
            try:
                instance, token, _ = ready_instance(service)
                first_status, first = service.submit_run(
                    instance["instance_id"], token, "client-task-1", run_payload())
                self.assertEqual(202, first_status)

                started = time.monotonic()
                stop_status, stopped = service.stop_run(
                    instance["instance_id"], token, first["task_id"])
                self.assertLess(time.monotonic() - started, 0.25)
                self.assertEqual(202, stop_status)
                self.assertEqual("stopping", stopped["status"])
                self.assertIn("stop", stopped.get("error", "").lower())
                self.assertTrue(stop_entered.wait(timeout=2))

                duplicate_status, duplicate = service.stop_run(
                    instance["instance_id"], token, first["task_id"])
                self.assertEqual(202, duplicate_status)
                self.assertEqual("stopping", duplicate["status"])
                with calls_lock:
                    self.assertEqual(1, len([call for call in calls if call[0] == "stop"]))

                second_payload = {**run_payload(), "client_task_id": "client-task-2",
                                  "input": "A different task"}
                started = time.monotonic()
                second_status, second = service.submit_run(
                    instance["instance_id"], token, "client-task-2", second_payload)
                self.assertLess(time.monotonic() - started, 0.25)
                self.assertEqual(202, second_status)
                self.assertNotEqual(first["task_id"], second["task_id"])
                self.assertNotEqual("cancelled", stopped["status"])
            finally:
                release_stop.set()
                self.assertTrue(service._run_controls.close(timeout=3))
                service.close()

    def test_uncertain_approval_is_not_replayed_or_exposed_after_restart(self):
        calls = []

        class UncertainApproveHermes:
            def __init__(self, *args, **kwargs):
                pass

            def create_run(self, *args, **kwargs):
                return {"run_id": "run_approval_fixture", "status": "started"}

            def approve_run(self, run_id, approval_id, decision):
                calls.append((run_id, approval_id, decision))
                raise HermesError("upstream_timeout", "fixture timeout", uncertain=True)

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", UncertainApproveHermes):
            service = BridgeService(Path(directory))
            instance_id = token = task_id = None
            try:
                instance, token, connection = ready_instance(service)
                instance_id = instance["instance_id"]
                _, receipt = service.submit_run(
                    instance_id, token, "client-task-1", run_payload())
                task_id = receipt["task_id"]
                service.store.update_run(
                    task_id, status="waiting_for_approval",
                    approval={"approval_id": "pc1:1:prompt-a"})

                started = time.monotonic()
                status, accepted = service.approve_run(
                    instance_id, token, task_id,
                    {"approval_id": "pc1:1:prompt-a", "decision": "once"})
                self.assertLess(time.monotonic() - started, 0.25)
                self.assertEqual(202, status)
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline and service._run_controls._threads:
                    time.sleep(0.005)
                saved = service.store.snapshot()["runs"][task_id]
                action_key = next(key for key in saved["control_actions"] if key.startswith("approval:"))
                self.assertEqual("unknown", saved["control_actions"][action_key]["state"])
                projected = service._public_run(saved)
                self.assertNotIn("approval", projected)
                self.assertTrue(projected.get("error"))
                self.assertEqual(1, len(calls))

                repeated_status, repeated = service.approve_run(
                    instance_id, token, task_id,
                    {"approval_id": "pc1:1:prompt-a", "decision": "once"})
                self.assertEqual(202, repeated_status)
                self.assertNotIn("approval", repeated)
                with self.assertRaises(ConflictError):
                    service.approve_run(
                        instance_id, token, task_id,
                        {"approval_id": "pc1:1:prompt-a", "decision": "deny"})
            finally:
                self.assertTrue(service._run_controls.close(timeout=3))
                service.close()

            reopened = BridgeService(Path(directory))
            try:
                reopened.vault.set(instance_id, "fixture-key")
                saved = reopened.store.snapshot()["runs"][task_id]
                self.assertEqual("unknown", saved["control_actions"][action_key]["state"])
                self.assertEqual(202, reopened.approve_run(
                    instance_id, token, task_id,
                    {"approval_id": "pc1:1:prompt-a", "decision": "once"})[0])
                self.assertEqual(1, len(calls))
            finally:
                reopened.close()


if __name__ == "__main__":
    unittest.main()
