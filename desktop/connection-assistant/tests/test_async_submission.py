from __future__ import annotations

import threading
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from padnote_assistant.bridge import BridgeService
from padnote_assistant.hermes import HermesError
from padnote_assistant.state import AuthorizationError, StateOwnershipError, StateStore
from test_core import ready_instance, run_payload


class AsyncSubmissionTests(unittest.TestCase):
    def test_accepted_worker_keeps_original_client_after_revoke_and_config_change(self):
        entered = threading.Event()
        release = threading.Event()
        clients = []
        calls = []

        class BoundHermes:
            def __init__(self, base_url, api_key, **kwargs):
                clients.append((base_url, api_key))

            def create_run(self, *args, **kwargs):
                calls.append("POST")
                entered.set()
                if not release.wait(timeout=3):
                    raise AssertionError("test did not release accepted request")
                return {"run_id": "run_original_identity", "status": "started"}

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", BoundHermes):
            service = BridgeService(Path(directory), async_submission=True)
            try:
                instance, token, connection = ready_instance(service)
                status, receipt = service.submit_run(
                    instance["instance_id"], token, "client-task-1", run_payload())
                self.assertEqual(202, status)
                self.assertTrue(entered.wait(timeout=2))
                self.assertEqual(("http://127.0.0.1:8642", "fixture-key"), clients[-1])
                client_count_at_accept = len(clients)

                service.store.attach_instance_config(
                    instance["instance_id"], "/tmp/replacement-config.toml", "replacement-fingerprint")
                service.store.revoke_connection(connection["connection_id"])
                with self.assertRaises(AuthorizationError):
                    service.submit_run(instance["instance_id"], token,
                                       "client-task-2", {**run_payload(),
                                                          "client_task_id": "client-task-2"})

                release.set()
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    saved = service.store.snapshot()["runs"][receipt["task_id"]]
                    if saved.get("upstream_run_id"):
                        break
                    time.sleep(0.01)
                self.assertEqual("run_original_identity", saved["upstream_run_id"])
                self.assertEqual(client_count_at_accept, len(clients))
                self.assertTrue(all(client == ("http://127.0.0.1:8642", "fixture-key")
                                    for client in clients))
                self.assertEqual(["POST"], calls)
            finally:
                release.set()
                service.close()

    def test_same_task_retry_reuses_receipt_while_another_task_is_admitted(self):
        first_entered = threading.Event()
        second_entered = threading.Event()
        release = threading.Event()
        calls = []
        calls_lock = threading.Lock()

        class BlockingHermes:
            def __init__(self, *args, **kwargs):
                pass

            def create_run(self, input_text, *args, **kwargs):
                with calls_lock:
                    calls.append(input_text)
                    count = len(calls)
                    (first_entered if count == 1 else second_entered).set()
                if not release.wait(timeout=3):
                    raise AssertionError("test did not release fake Hermes submissions")
                return {"run_id": f"run_duplicate_{count}", "status": "started"}

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", BlockingHermes):
            service = BridgeService(Path(directory), async_submission=True)
            try:
                instance, token, _ = ready_instance(service)
                status, first = service.submit_run(
                    instance["instance_id"], token, "client-task-1", run_payload())
                self.assertEqual(202, status)
                self.assertTrue(first_entered.wait(timeout=2))

                started = time.monotonic()
                retry_status, retry = service.submit_run(
                    instance["instance_id"], token, "client-task-1", run_payload())
                self.assertLess(time.monotonic() - started, 0.25)
                self.assertEqual(202, retry_status)
                self.assertEqual(first["task_id"], retry["task_id"])
                with calls_lock:
                    self.assertEqual(1, len(calls))

                other = {**run_payload(), "client_task_id": "client-task-2",
                         "input": "Different task"}
                other_status, second = service.submit_run(
                    instance["instance_id"], token, "client-task-2", other)
                self.assertEqual(202, other_status)
                self.assertNotEqual(first["task_id"], second["task_id"])
                self.assertTrue(second_entered.wait(timeout=2))

                release.set()
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    rows = service.store.snapshot()["runs"]
                    if all(rows[item["task_id"]].get("upstream_run_id") for item in (first, second)):
                        break
                    time.sleep(0.01)
                self.assertEqual("run_duplicate_1", rows[first["task_id"]]["upstream_run_id"])
                self.assertEqual("run_duplicate_2", rows[second["task_id"]]["upstream_run_id"])
                with calls_lock:
                    self.assertEqual(2, len(calls))
            finally:
                release.set()
                service.close()

    def test_second_task_is_admitted_while_first_upstream_post_is_blocked(self):
        entered = threading.Event()
        release = threading.Event()
        calls = []
        calls_lock = threading.Lock()

        class TwoCallHermes:
            def __init__(self, *args, **kwargs):
                pass

            def create_run(self, input_text, *args, **kwargs):
                with calls_lock:
                    calls.append(input_text)
                    call_number = len(calls)
                    if call_number >= 2:
                        entered.set()
                if not release.wait(timeout=3):
                    raise AssertionError("test did not release fake Hermes submissions")
                return {"run_id": f"run_parallel_{call_number}", "status": "started"}

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", TwoCallHermes):
            service = BridgeService(Path(directory), async_submission=True)
            try:
                instance, token, _ = ready_instance(service)
                first_status, first = service.submit_run(
                    instance["instance_id"], token, "client-task-1", run_payload())
                self.assertEqual(202, first_status)
                first_entered_deadline = time.monotonic() + 2
                while time.monotonic() < first_entered_deadline:
                    with calls_lock:
                        if calls:
                            break
                    time.sleep(0.005)
                with calls_lock:
                    self.assertEqual(1, len(calls))

                second_payload = {**run_payload(), "client_task_id": "client-task-2",
                                  "input": "Second fixture task"}
                started = time.monotonic()
                second_status, second = service.submit_run(
                    instance["instance_id"], token, "client-task-2", second_payload)
                elapsed = time.monotonic() - started
                self.assertEqual(202, second_status)
                self.assertLess(elapsed, 0.25)
                self.assertNotEqual(first["task_id"], second["task_id"])
                self.assertTrue(entered.wait(timeout=2))

                release.set()
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    rows = service.store.snapshot()["runs"]
                    if all(rows[item["task_id"]].get("upstream_run_id") for item in (first, second)):
                        break
                    time.sleep(0.01)
                self.assertEqual("run_parallel_1", rows[first["task_id"]]["upstream_run_id"])
                self.assertEqual("run_parallel_2", rows[second["task_id"]]["upstream_run_id"])
                with calls_lock:
                    self.assertEqual(2, len(calls))
            finally:
                release.set()
                service.close()

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
