from __future__ import annotations

import copy
import threading
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from typing import Any
from unittest.mock import patch

from padnote_assistant.video_scheduler import VideoOperationScheduler
from padnote_assistant.bridge import BridgeService
from padnote_assistant.state import StateStore
from padnote_assistant.video_worker import VideoWorkerError


WORKER_TASK_ID = "worker-task-a"


def operation(status: str = "queued") -> dict[str, Any]:
    return {
        "operation_id": str(uuid.uuid4()), "owner_connection_id": "connection-a",
        "instance_id": "instance-a", "task_id": "server-task-a",
        "client_operation_id": str(uuid.uuid4()), "action": "initialize",
        "parameters": {}, "payload_digest": "a" * 64,
        "source_snapshot_digest": "b" * 64,
        "video_binding": {"worker_task_id": WORKER_TASK_ID},
        "status": status, "attempt_id": None, "created_at": 1, "updated_at": 1,
        "result": None, "error": None,
    }


def worker_result() -> dict[str, Any]:
    return {
        "protocol_version": 1, "task_id": WORKER_TASK_ID,
        "status": "initialized", "phase": "idle", "event_cursor": 1,
    }


class FakeStore:
    def __init__(self, queued: list[dict[str, Any]] | None = None,
                 running: list[dict[str, Any]] | None = None) -> None:
        self.lock = threading.Lock()
        self.queued = copy.deepcopy(queued or [])
        self.running = copy.deepcopy(running or [])
        self.recovered: list[dict[str, Any]] = []
        self.finished: list[tuple[str, str, str, dict[str, Any] | None, str | None]] = []
        self.recover_calls = 0
        self.claim_calls = 0
        self.closed = False
        self.fail_recover = False
        self.fail_claim = False
        self.fail_finish = False
        self.recover_entered: threading.Event | None = None
        self.recover_release: threading.Event | None = None
        self.claim_entered: threading.Event | None = None
        self.claim_release: threading.Event | None = None

    def recover_video_operations(self) -> int:
        with self.lock:
            self.recover_calls += 1
            if self.fail_recover:
                raise OSError("private disk path")
            if self.recover_entered is not None:
                self.recover_entered.set()
            if self.recover_release is not None and not self.recover_release.wait(2):
                raise TimeoutError("recovery test timed out")
            count = len(self.running)
            for old in self.running:
                uncertain = copy.deepcopy(old)
                uncertain["status"] = "unknown"
                uncertain["error"] = "worker_unknown"
                self.recovered.append(uncertain)
            self.running.clear()
            return count

    def claim_next_video_operation(self) -> dict[str, Any] | None:
        with self.lock:
            self.claim_calls += 1
            if self.fail_claim:
                raise OSError("private disk path")
            if self.claim_entered is not None:
                self.claim_entered.set()
            if self.claim_release is not None and not self.claim_release.wait(2):
                raise TimeoutError("claim test timed out")
            if not self.queued:
                return None
            item = self.queued.pop(0)
            item["status"] = "running"
            item["attempt_id"] = str(uuid.uuid4())
            self.running.append(item)
            return copy.deepcopy(item)

    def finish_video_operation(self, operation_id: str, attempt_id: str, status: str,
                               *, result: dict[str, Any] | None = None,
                               error: str | None = None) -> dict[str, Any]:
        with self.lock:
            if self.fail_finish:
                raise OSError("private disk path")
            found = next(item for item in self.running if item["operation_id"] == operation_id)
            if found["attempt_id"] != attempt_id:
                raise AssertionError("attempt mismatch")
            found["status"] = status
            found["result"] = copy.deepcopy(result)
            found["error"] = error
            self.running.remove(found)
            self.finished.append((operation_id, attempt_id, status, copy.deepcopy(result), error))
            return copy.deepcopy(found)


def wait_for(predicate, timeout: float = 2.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.005)
    raise AssertionError("condition did not become true before timeout")


class VideoOperationSchedulerTests(unittest.TestCase):
    def test_single_worker_drains_queued_operations_once_and_passes_a_copy(self) -> None:
        first, second = operation(), operation()
        store = FakeStore([first, second])
        entered = threading.Event()
        release = threading.Event()
        calls: list[str] = []

        def execute(record):
            calls.append(record["operation_id"])
            record["parameters"]["mutated"] = True
            if record["operation_id"] == first["operation_id"]:
                entered.set()
                self.assertTrue(release.wait(2))
            return worker_result()

        scheduler = VideoOperationScheduler(store, execute)
        self.assertTrue(scheduler.start())
        self.assertTrue(entered.wait(2))
        self.assertEqual(calls, [first["operation_id"]])
        release.set()
        wait_for(lambda: len(store.finished) == 2)
        self.assertEqual(calls, [first["operation_id"], second["operation_id"]])
        self.assertEqual([item[2] for item in store.finished], ["succeeded", "succeeded"])
        self.assertNotIn("mutated", first["parameters"])
        self.assertTrue(scheduler.start())
        self.assertEqual(store.recover_calls, 1)
        self.assertTrue(scheduler.close(2))
        self.assertTrue(scheduler.close(2))

    def test_wake_resumes_idle_scheduler_after_enqueue(self) -> None:
        store = FakeStore()
        calls: list[str] = []
        scheduler = VideoOperationScheduler(
            store, lambda record: calls.append(record["operation_id"]) or worker_result())
        self.assertTrue(scheduler.start())
        wait_for(lambda: store.claim_calls > 0)
        queued = operation()
        with store.lock:
            store.queued.append(copy.deepcopy(queued))
        scheduler.wake()
        wait_for(lambda: bool(store.finished))
        self.assertEqual(calls, [queued["operation_id"]])
        self.assertTrue(scheduler.close(2))

    def test_restart_marks_old_running_unknown_and_executes_only_queued_work(self) -> None:
        orphan = operation("running")
        orphan["attempt_id"] = str(uuid.uuid4())
        queued = operation()
        store = FakeStore([queued], [orphan])
        calls: list[str] = []
        scheduler = VideoOperationScheduler(store, lambda record: calls.append(record["operation_id"]) or worker_result())

        self.assertTrue(scheduler.start())
        wait_for(lambda: len(store.finished) == 1)
        self.assertEqual(calls, [queued["operation_id"]])
        self.assertEqual(store.recovered[0]["status"], "unknown")
        self.assertEqual(store.recovered[0]["error"], "worker_unknown")
        self.assertTrue(scheduler.close(2))

    def test_callback_failure_is_classified_without_persisting_exception_text(self) -> None:
        cases = (
            (VideoWorkerError("preflight_state_conflict", "secret token", unknown=False), "failed", "worker_failed"),
            (VideoWorkerError("worker_timeout", "secret path", unknown=True), "unknown", "worker_timeout"),
            (RuntimeError("secret path and token"), "unknown", "worker_unknown"),
        )
        for error, expected_status, expected_code in cases:
            with self.subTest(error=type(error).__name__, expected_status=expected_status):
                store = FakeStore([operation()])
                scheduler = VideoOperationScheduler(store, lambda _record, failure=error: (_ for _ in ()).throw(failure))
                self.assertTrue(scheduler.start())
                wait_for(lambda: bool(store.finished))
                self.assertEqual(store.finished[0][2], expected_status)
                self.assertEqual(store.finished[0][4], expected_code)
                self.assertNotIn("secret", repr(store.finished))
                self.assertTrue(scheduler.close(2))

    def test_safe_tts_failure_code_preserves_unknown_vs_known_classification(self) -> None:
        cases = (
            (VideoWorkerError("tts_provider_http_error", "generic provider failure", unknown=True),
             "unknown", "tts_provider_http_error"),
            (VideoWorkerError("tts_input_invalid", "generic provider failure", unknown=False),
             "failed", "tts_input_invalid"),
        )
        for error, expected_status, expected_code in cases:
            with self.subTest(code=error.code, expected_status=expected_status):
                store = FakeStore([operation()])
                scheduler = VideoOperationScheduler(
                    store, lambda _record, failure=error: (_ for _ in ()).throw(failure))
                self.assertTrue(scheduler.start())
                wait_for(lambda: bool(store.finished))
                self.assertEqual((expected_status, expected_code),
                                 (store.finished[0][2], store.finished[0][4]))
                self.assertEqual(None, store.finished[0][3])
                self.assertTrue(scheduler.close(2))

    def test_recovery_failure_never_starts_worker(self) -> None:
        store = FakeStore([operation()])
        store.fail_recover = True
        calls: list[bool] = []
        scheduler = VideoOperationScheduler(store, lambda _record: calls.append(True) or worker_result())

        self.assertFalse(scheduler.start())
        self.assertEqual(calls, [])
        self.assertEqual(scheduler.safe_failure_code, "recovery_failed")
        self.assertTrue(scheduler.halted)
        self.assertEqual(store.claim_calls, 0)
        self.assertTrue(scheduler.close(0))

    def test_thread_start_failure_is_fixed_and_close_is_safe(self) -> None:
        store = FakeStore([operation()])
        scheduler = VideoOperationScheduler(store, lambda _record: worker_result())
        with patch.object(threading.Thread, "start", side_effect=RuntimeError("private path")):
            self.assertFalse(scheduler.start())
        self.assertEqual(scheduler.safe_failure_code, "thread_start_failed")
        self.assertTrue(scheduler.halted)
        self.assertTrue(scheduler.close(0))
        self.assertEqual(store.claim_calls, 0)

    def test_close_waits_for_startup_recovery_before_store_ownership_can_end(self) -> None:
        store = FakeStore([operation()])
        store.recover_entered = threading.Event()
        store.recover_release = threading.Event()
        calls: list[bool] = []
        scheduler = VideoOperationScheduler(store, lambda _record: calls.append(True) or worker_result())
        started: list[bool] = []
        starter = threading.Thread(target=lambda: started.append(scheduler.start()))
        starter.start()
        self.assertTrue(store.recover_entered.wait(2))

        self.assertFalse(scheduler.close(0.01))
        self.assertFalse(store.closed)
        store.recover_release.set()
        starter.join(2)
        self.assertFalse(starter.is_alive())
        self.assertEqual(started, [False])
        self.assertTrue(scheduler.close(2))
        self.assertEqual(store.claim_calls, 0)
        self.assertEqual(calls, [])

    def test_close_during_claim_waits_for_claimed_operation_commit_then_stops(self) -> None:
        first, second = operation(), operation()
        store = FakeStore([first, second])
        store.claim_entered = threading.Event()
        store.claim_release = threading.Event()
        calls: list[str] = []
        scheduler = VideoOperationScheduler(
            store, lambda record: calls.append(record["operation_id"]) or worker_result())
        self.assertTrue(scheduler.start())
        self.assertTrue(store.claim_entered.wait(2))

        self.assertFalse(scheduler.close(0.01))
        self.assertFalse(store.closed)
        store.claim_release.set()
        self.assertTrue(scheduler.close(2))
        self.assertEqual(calls, [first["operation_id"]])
        self.assertEqual(len(store.finished), 1)
        self.assertEqual([item["operation_id"] for item in store.queued], [second["operation_id"]])
        self.assertFalse(store.closed)

    def test_store_has_one_scheduler_owner_until_close_completes(self) -> None:
        store = FakeStore([operation(), operation()])
        entered = threading.Event()
        release = threading.Event()
        first_calls: list[str] = []
        first = VideoOperationScheduler(store, lambda record: (
            first_calls.append(record["operation_id"]), entered.set(), release.wait(2), worker_result()
        )[-1])
        self.assertTrue(first.start())
        self.assertTrue(entered.wait(2))
        claimed_id = store.running[0]["operation_id"]

        second = VideoOperationScheduler(store, lambda _record: worker_result())
        self.assertFalse(second.start())
        self.assertEqual(second.safe_failure_code, "scheduler_already_active")
        self.assertEqual(store.recover_calls, 1)
        self.assertEqual(store.claim_calls, 1)
        self.assertEqual(store.running[0]["status"], "running")
        self.assertTrue(second.close(0))

        self.assertFalse(first.close(0.01))
        blocked_by_ownership = VideoOperationScheduler(store, lambda _record: worker_result())
        self.assertFalse(blocked_by_ownership.start())
        self.assertEqual(store.recover_calls, 1)
        self.assertTrue(blocked_by_ownership.close(0))
        release.set()
        self.assertTrue(first.close(2))
        self.assertEqual(first_calls, [claimed_id])

        third_calls: list[str] = []
        third = VideoOperationScheduler(
            store, lambda record: third_calls.append(record["operation_id"]) or worker_result())
        self.assertTrue(third.start())
        wait_for(lambda: len(third_calls) == 1)
        self.assertEqual(store.recover_calls, 2)
        self.assertTrue(third.close(2))

    def test_real_state_store_recovers_running_and_persists_queued_completion(self) -> None:
        with tempfile.TemporaryDirectory(prefix="padnote-video-scheduler-store-") as temporary:
            state_dir = Path(temporary) / "state"
            service = BridgeService(state_dir)
            instance = service.add_instance("hermes", "Fixture Hermes", "http://127.0.0.1:8642")
            service.store.update_instance_check(
                instance["instance_id"], health="ready", detail="ready",
                features={"run_submission": True, "run_status": True}, executable=True)
            code, _ = service.store.create_pair_code(
                instance["instance_id"], "https://computer.example.ts.net")
            pair = service.store.request_pair(code, "scheduler-device", "Scheduler Tablet")
            service.store.decide_pair(pair["request_id"], True)
            _, claim = service.store.claim_pair(pair["request_id"], pair["poll_token"])
            token = claim["connections"][0]["token"]
            connection = service.store.authenticate(instance["instance_id"], token)

            queued_ids: list[str] = []
            task_ids: list[str] = []
            for index in range(2):
                payload = {"input": f"video operation {index}", "source": {"note_id": f"note-{index}"}}
                run, _ = service.store.create_or_get_run(connection, f"scheduler-run-{index}", payload)
                task_ids.append(run["task_id"])
                worker_id = f"worker-task-{index}"
                service.store.update_run(
                    run["task_id"], status="completed", session_id=f"session-{index}",
                    source_snapshot_digest=str(index + 1) * 64,
                    video_binding={"worker_task_id": worker_id, "request_sha256": str(index + 3) * 64})
                operation_record, created = service.store.reserve_video_operation(
                    connection, run["task_id"], f"client-operation-{index}", "initialize", {})
                self.assertTrue(created)
                queued_ids.append(operation_record["operation_id"])

            running = service.store.claim_next_video_operation()
            self.assertIsNotNone(running)
            self.assertEqual(running["operation_id"], queued_ids[0])
            service.close()

            store = StateStore(state_dir)
            calls: list[str] = []

            def execute(record):
                calls.append(record["operation_id"])
                return {
                    "protocol_version": 1,
                    "task_id": record["video_binding"]["worker_task_id"],
                    "status": "initialized", "phase": "idle", "event_cursor": 1,
                }

            scheduler = VideoOperationScheduler(store, execute)
            try:
                self.assertTrue(scheduler.start())
                wait_for(lambda: len(calls) == 1)
                wait_for(lambda: store.owned_video_operation(
                    connection, task_ids[1], queued_ids[1])["status"] == "succeeded")
                first = store.owned_video_operation(connection, task_ids[0], queued_ids[0])
                second = store.owned_video_operation(connection, task_ids[1], queued_ids[1])
                self.assertEqual(first["status"], "unknown")
                self.assertEqual(first["error"], "worker_interrupted")
                self.assertEqual(second["status"], "succeeded")
                self.assertEqual(calls, [queued_ids[1]])
            finally:
                self.assertTrue(scheduler.close(2))
                store.close()

            reopened = StateStore(state_dir)
            try:
                records = [reopened.owned_video_operation(connection, task_id, operation_id)
                           for task_id, operation_id in zip(task_ids, queued_ids)]
                self.assertEqual(records[0]["status"], "unknown")
                self.assertEqual(records[1]["status"], "succeeded")
                self.assertEqual(records[1]["result"]["task_id"], "worker-task-1")
            finally:
                reopened.close()

    def test_claim_or_finish_storage_failure_halts_before_any_later_execution(self) -> None:
        for failure_point in ("claim", "finish"):
            with self.subTest(failure_point=failure_point):
                store = FakeStore([operation(), operation()])
                if failure_point == "claim":
                    store.fail_claim = True
                else:
                    store.fail_finish = True
                calls: list[str] = []
                scheduler = VideoOperationScheduler(
                    store, lambda record: calls.append(record["operation_id"]) or worker_result())
                self.assertTrue(scheduler.start())
                wait_for(lambda: scheduler.halted)
                self.assertEqual(len(calls), 0 if failure_point == "claim" else 1)
                self.assertEqual(store.claim_calls, 1 if failure_point == "claim" else 1)
                self.assertEqual(len(store.queued), 2 if failure_point == "claim" else 1)
                self.assertEqual(scheduler.safe_failure_code,
                                 "claim_failed" if failure_point == "claim" else "finish_failed")
                self.assertTrue(scheduler.close(2))

    def test_close_timeout_keeps_store_open_and_does_not_claim_next_operation(self) -> None:
        first, second = operation(), operation()
        store = FakeStore([first, second])
        entered = threading.Event()
        release = threading.Event()
        calls: list[str] = []

        def execute(record):
            calls.append(record["operation_id"])
            entered.set()
            release.wait(2)
            return worker_result()

        scheduler = VideoOperationScheduler(store, execute)
        self.assertTrue(scheduler.start())
        self.assertTrue(entered.wait(2))
        self.assertFalse(scheduler.close(0.01))
        self.assertFalse(store.closed)
        self.assertEqual(calls, [first["operation_id"]])
        release.set()
        self.assertTrue(scheduler.close(2))
        self.assertEqual(calls, [first["operation_id"]])
        self.assertEqual(len(store.finished), 1)
        self.assertEqual([item["operation_id"] for item in store.queued], [second["operation_id"]])
        self.assertFalse(store.closed)


if __name__ == "__main__":
    unittest.main()
