from __future__ import annotations

import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

from padnote_assistant.bridge import BridgeService
from padnote_assistant.state import ConflictError
from test_core import ready_instance


class RunControlAdversarialTests(unittest.TestCase):
    def _task(self, service, connection, label, status="running"):
        run, _ = service.store.create_or_get_run(
            connection, label, {"input": "synthetic " + label})
        return service.store.update_run(
            run["task_id"], status=status, upstream_run_id="run_" + label)

    def test_two_blocked_slots_reject_third_without_record_or_post(self):
        entered = [threading.Event(), threading.Event()]
        release = threading.Event()
        calls = []
        call_lock = threading.Lock()

        class BlockStop:
            def __init__(self, *args, **kwargs): pass
            def stop_run(self, run_id):
                with call_lock:
                    calls.append(run_id)
                    index = len(calls) - 1
                entered[index].set()
                if not release.wait(timeout=4):
                    raise AssertionError("blocked stop was not released")

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", BlockStop):
            service = BridgeService(Path(directory))
            try:
                instance, token, connection = ready_instance(service)
                runs = [self._task(service, connection, f"slot-{i}") for i in range(3)]
                for run in runs[:2]:
                    self.assertEqual(202, service.stop_run(instance["instance_id"], token,
                                                           run["task_id"])[0])
                self.assertTrue(entered[0].wait(2))
                self.assertTrue(entered[1].wait(2))
                with self.assertRaises(ConflictError):
                    service.stop_run(instance["instance_id"], token, runs[2]["task_id"])
                self.assertNotIn("control_actions", service.store.snapshot()["runs"][runs[2]["task_id"]])
                with call_lock:
                    self.assertEqual(2, len(calls))
            finally:
                release.set()
                self.assertTrue(service._run_controls.close(timeout=3))
                service.close()

    def test_thread_constructor_or_start_failure_marks_not_sent_and_releases_slots(self):
        class Client:
            def __init__(self, *args, **kwargs): pass
            def stop_run(self, run_id): raise AssertionError("must not reach network")

        for failure in ("construct", "start"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory, mock.patch(
                    "padnote_assistant.bridge.HermesClient", Client):
                service = BridgeService(Path(directory))
                try:
                    instance, token, connection = ready_instance(service)
                    run = self._task(service, connection, "thread-" + failure)
                    if failure == "construct":
                        thread_patch = mock.patch("padnote_assistant.run_controls.threading.Thread",
                                                  side_effect=RuntimeError("construct failed"))
                    else:
                        class BrokenThread:
                            def __init__(self, *args, **kwargs): pass
                            def start(self): raise RuntimeError("start failed")
                        thread_patch = mock.patch("padnote_assistant.run_controls.threading.Thread",
                                                  BrokenThread)
                    with thread_patch, self.assertRaises(RuntimeError):
                        service.stop_run(instance["instance_id"], token, run["task_id"])
                    saved = service.store.snapshot()["runs"][run["task_id"]]
                    action = saved["control_actions"]["stop"]
                    self.assertEqual("not_sent", action["state"])
                    self.assertTrue(service._run_controls._slots.acquire(blocking=False))
                    self.assertTrue(service._run_controls._slots.acquire(blocking=False))
                    self.assertFalse(service._run_controls._slots.acquire(blocking=False))
                    service._run_controls._slots.release()
                    service._run_controls._slots.release()
                finally:
                    self.assertTrue(service._run_controls.close(timeout=2))
                    service.close()

    def test_revocation_retirement_or_config_change_before_admission_prevents_post(self):
        calls = []
        class Client:
            def __init__(self, *args, **kwargs): pass
            def stop_run(self, run_id): calls.append(run_id)

        for invalidation in ("revoke", "retire", "config"):
            with self.subTest(invalidation=invalidation), tempfile.TemporaryDirectory() as directory, mock.patch(
                    "padnote_assistant.bridge.HermesClient", Client):
                service = BridgeService(Path(directory))
                try:
                    instance, token, connection = ready_instance(service)
                    run = self._task(service, connection, "invalid-" + invalidation)
                    task_lock = service._operation_lock("task:" + run["task_id"])
                    task_lock.acquire()
                    try:
                        self.assertEqual(202, service.stop_run(
                            instance["instance_id"], token, run["task_id"])[0])
                        time.sleep(0.08)
                        if invalidation == "revoke":
                            service.store.revoke_connection(connection["connection_id"])
                        elif invalidation == "retire":
                            service.store.retire_instance(instance["instance_id"], "fixture retired")
                        else:
                            service.store.attach_instance_config(
                                instance["instance_id"], "/fixture/new.env", "new-fingerprint")
                    finally:
                        task_lock.release()
                    deadline = time.monotonic() + 2
                    while time.monotonic() < deadline and service._run_controls._threads:
                        time.sleep(0.005)
                    saved = service.store.snapshot()["runs"][run["task_id"]]
                    self.assertEqual("not_sent", saved["control_actions"]["stop"]["state"])
                    self.assertEqual([], calls)
                finally:
                    self.assertTrue(service._run_controls.close(timeout=3))
                    service.close()

    def test_fixed_client_is_not_redirected_and_short_close_retains_store(self):
        entered = threading.Event()
        release = threading.Event()
        clients = []
        calls = []
        class BlockingClient:
            def __init__(self, base_url, api_key, **kwargs):
                clients.append((base_url, api_key))
            def stop_run(self, run_id):
                calls.append(run_id)
                entered.set()
                if not release.wait(timeout=4):
                    raise AssertionError("fake request was not released")

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", BlockingClient):
            service = BridgeService(Path(directory))
            try:
                instance, token, connection = ready_instance(service, key="fixture-key")
                run = self._task(service, connection, "fixed-client")
                self.assertEqual(202, service.stop_run(
                    instance["instance_id"], token, run["task_id"])[0])
                self.assertTrue(entered.wait(timeout=2))
                original = ("http://127.0.0.1:8642", "fixture-key")
                self.assertIn(original, clients)
                service.configure_key(instance["instance_id"], "replacement-key")
                self.assertFalse(service._run_controls.close(timeout=0.01))
                self.assertTrue(service.store.snapshot()["runs"])
                release.set()
                self.assertTrue(service._run_controls.close(timeout=3))
                self.assertEqual([run["upstream_run_id"]], calls)
                self.assertEqual(original, clients[0])
            finally:
                release.set()
                service.close()


if __name__ == "__main__":
    unittest.main()
