from __future__ import annotations

import threading
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from padnote_assistant.bridge import BridgeService
from padnote_assistant.hermes import HermesError
from padnote_assistant.state import StateOwnershipError, StateStore
from test_core import ready_instance, run_payload


class AsyncSubmissionTests(unittest.TestCase):
    def test_worker_start_failure_returns_known_failure_and_releases_slot(self):
        calls = []

        class NeverCalledHermes:
            def __init__(self, *args, **kwargs):
                pass

            def create_run(self, *args, **kwargs):
                calls.append(args)
                return {"run_id": "unexpected", "status": "started"}

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", NeverCalledHermes), mock.patch(
                    "padnote_assistant.bridge.threading.Thread",
                    side_effect=RuntimeError("thread creation failed")):
            service = BridgeService(Path(directory), async_submission=True)
            try:
                instance, token, _ = ready_instance(service)
                status, receipt = service.submit_run(
                    instance["instance_id"], token, "client-task-1", run_payload())
                self.assertEqual(202, status)
                self.assertEqual("failed", receipt["status"])
                saved = service.store.snapshot()["runs"][receipt["task_id"]]
                self.assertFalse(saved["submission_uncertain"])
                self.assertFalse(service._submission_workers)
                self.assertTrue(service._submission_slots.acquire(blocking=False))
                self.assertTrue(service._submission_slots.acquire(blocking=False))
                self.assertFalse(service._submission_slots.acquire(blocking=False))
                service._submission_slots.release()
                service._submission_slots.release()
                self.assertEqual([], calls)
            finally:
                service.close()

    def test_definitive_upstream_error_clears_uncertain_submission_state(self):
        entered = threading.Event()

        class RejectingHermes:
            def __init__(self, *args, **kwargs):
                pass

            def create_run(self, *args, **kwargs):
                entered.set()
                raise HermesError("upstream_rejected", "fixture rejection", uncertain=False)

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", RejectingHermes):
            service = BridgeService(Path(directory), async_submission=True)
            try:
                instance, token, _ = ready_instance(service)
                status, receipt = service.submit_run(
                    instance["instance_id"], token, "client-task-1", run_payload())
                self.assertEqual(202, status)
                self.assertTrue(entered.wait(timeout=2))
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    saved = service.store.snapshot()["runs"][receipt["task_id"]]
                    if saved["status"] == "failed":
                        break
                    time.sleep(0.01)
                self.assertEqual("failed", saved["status"])
                self.assertFalse(saved["submission_uncertain"])
            finally:
                service.close()

    def test_uncertain_submission_is_not_replayed_after_restart(self):
        entered = threading.Event()
        release = threading.Event()
        calls = []

        class UncertainHermes:
            def __init__(self, *args, **kwargs):
                pass

            def create_run(self, *args, **kwargs):
                calls.append(args)
                entered.set()
                if not release.wait(timeout=2):
                    raise AssertionError("test did not release fake Hermes")
                raise HermesError("timeout", "ambiguous receipt", uncertain=True)

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", UncertainHermes):
            state = Path(directory)
            service = BridgeService(state, async_submission=True)
            instance, token, _ = ready_instance(service)
            bridge_id = service.store.bridge_id
            try:
                status, receipt = service.submit_run(
                    instance["instance_id"], token, "client-task-1", run_payload())
                self.assertEqual(202, status)
                self.assertTrue(entered.wait(timeout=2))
                release.set()
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    row = service.store.snapshot()["runs"][receipt["task_id"]]
                    if row.get("submission_uncertain"):
                        break
                    time.sleep(0.01)
                self.assertTrue(row["submission_uncertain"])
            finally:
                release.set()
                service.close()

            reopened = BridgeService(state, async_submission=True)
            try:
                self.assertEqual(bridge_id, reopened.store.bridge_id)
                reopened.vault.set(instance["instance_id"], "fixture-key")
                status, retry = reopened.submit_run(
                    instance["instance_id"], token, "client-task-1", run_payload())
                self.assertEqual(202, status)
                self.assertEqual(receipt["task_id"], retry["task_id"])
                saved = reopened.store.snapshot()["runs"][retry["task_id"]]
                self.assertTrue(saved["submission_uncertain"])
                self.assertEqual(1, len(calls))
            finally:
                reopened.close()

    def test_close_waits_for_blocked_submission_before_releasing_state_owner(self):
        entered = threading.Event()
        release = threading.Event()

        class BlockingHermes:
            def __init__(self, *args, **kwargs):
                pass

            def create_run(self, *args, **kwargs):
                entered.set()
                if not release.wait(timeout=2):
                    raise AssertionError("test did not release fake Hermes")
                return {"run_id": "run_shutdown", "status": "started"}

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", BlockingHermes):
            state = Path(directory)
            service = BridgeService(state, async_submission=True)
            instance, token, _ = ready_instance(service)
            service.submit_run(instance["instance_id"], token, "client-task-1", run_payload())
            self.assertTrue(entered.wait(timeout=2))
            closed = threading.Event()
            closer = threading.Thread(target=lambda: (service.close(), closed.set()))
            closer.start()
            try:
                self.assertFalse(closed.wait(timeout=0.05))
                with self.assertRaises(StateOwnershipError):
                    StateStore(state)
            finally:
                release.set()
            closer.join(timeout=2)
            self.assertFalse(closer.is_alive())
            self.assertTrue(closed.is_set())
            with StateStore(state):
                pass

    def test_submit_returns_while_upstream_is_blocked_and_persists_receipt(self):
        entered = threading.Event()
        release = threading.Event()

        class BlockingHermes:
            def __init__(self, *args, **kwargs):
                pass

            def create_run(self, *args, **kwargs):
                entered.set()
                if not release.wait(timeout=2):
                    raise AssertionError("test did not release fake Hermes")
                return {"run_id": "run_async", "status": "started"}

        with mock.patch("padnote_assistant.bridge.HermesClient", BlockingHermes):
            with tempfile.TemporaryDirectory() as directory:
                service = BridgeService(Path(directory), async_submission=True)
                try:
                    instance, token, _ = ready_instance(service)
                    started = time.monotonic()
                    status, receipt = service.submit_run(
                        instance["instance_id"], token, "client-task-1", run_payload())
                    submit_elapsed = time.monotonic() - started
                    self.assertEqual(202, status)
                    self.assertTrue(entered.wait(timeout=2))
                    self.assertLess(submit_elapsed, 0.25)

                    started = time.monotonic()
                    pending = service.get_run(
                        instance["instance_id"], token, receipt["task_id"])
                    self.assertLess(time.monotonic() - started, 0.25)
                    self.assertEqual("submitting", pending["status"])
                    self.assertEqual(receipt["task_id"], pending["task_id"])

                    release.set()
                    deadline = time.monotonic() + 2
                    while time.monotonic() < deadline:
                        persisted = service.store.snapshot()["runs"][receipt["task_id"]]
                        if persisted.get("upstream_run_id"):
                            break
                        time.sleep(0.01)
                    self.assertEqual("run_async", persisted["upstream_run_id"])
                    self.assertFalse(persisted.get("submission_uncertain", False))
                finally:
                    release.set()
                    service.close()


if __name__ == "__main__":
    unittest.main()
