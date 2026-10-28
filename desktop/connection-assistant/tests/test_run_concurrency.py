from __future__ import annotations

import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

from padnote_assistant.bridge import BridgeService
from padnote_assistant.hermes import HermesClient
from test_core import ready_instance, run_payload


class RunConcurrencyTests(unittest.TestCase):
    def test_blocked_run_get_does_not_block_other_task_submission(self):
        entered = threading.Event()
        release = threading.Event()
        result = {}
        errors = []

        class BlockingGetHermes:
            def __init__(self, *args, **kwargs):
                pass

            def get_run(self, run_id):
                entered.set()
                if not release.wait(timeout=5):
                    raise AssertionError("GET was not released")
                return {"run_id": run_id, "status": "running"}

            def create_run(self, input_text, *args, **kwargs):
                return {"run_id": "run_second_task", "status": "started"}

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", BlockingGetHermes):
            service = BridgeService(Path(directory))
            get_thread = submit_thread = None
            try:
                instance, token, _ = ready_instance(service)
                with mock.patch.object(HermesClient, "create_run", return_value={
                        "run_id": "run_first_task", "status": "started"}):
                    _, first = service.submit_run(
                        instance["instance_id"], token, "client-task-1", run_payload())
                get_thread = threading.Thread(target=lambda: self._capture(
                    lambda: service.get_run(instance["instance_id"], token, first["task_id"]),
                    result, "get", errors))
                get_thread.start()
                self.assertTrue(entered.wait(timeout=2))

                payload = {**run_payload(), "client_task_id": "client-task-2",
                           "input": "Different task"}
                def submit():
                    try:
                        result["submit"] = service.submit_run(
                            instance["instance_id"], token, "client-task-2", payload)
                    except Exception as error:
                        errors.append(error)
                submit_thread = threading.Thread(target=submit)
                started = time.monotonic()
                submit_thread.start()
                submit_thread.join(timeout=1)
                elapsed = time.monotonic() - started
                self.assertFalse(submit_thread.is_alive(), "submit waited behind blocked GET")
                self.assertLess(elapsed, 0.5)
                self.assertEqual(202, result["submit"][0])
                self.assertNotEqual(first["task_id"], result["submit"][1]["task_id"])
            finally:
                release.set()
                for thread in (get_thread, submit_thread):
                    if thread is not None:
                        thread.join(timeout=3)
                        self.assertFalse(thread.is_alive(), "worker thread leaked")
                service.close()
            self.assertEqual([], errors)

    @staticmethod
    def _capture(call, result, key, errors):
        try:
            result[key] = call()
        except Exception as error:
            errors.append(error)
