"""Durable admission, projection, and bounded worker execution for run controls."""
from __future__ import annotations

import threading
import copy
import hashlib
import uuid
from typing import TYPE_CHECKING, Any
from contextlib import ExitStack

from .hermes import HermesError, decode_approval_token
from .security import validate_identifier, validate_text
from .state import ConflictError, ValidationError

if TYPE_CHECKING:
    from .bridge import BridgeService


_PROJECTED = {"queued", "sending", "unknown", "confirmed"}
_STOP_INTENT = {"queued", "sending", "unknown", "confirmed"}
_CONTROL_FIELDS = {
    "kind", "approval_id", "decision", "state", "attempt_id",
    "requested_at", "updated_at",
}


class RunControlQueue:
    """Two-slot background-control bookkeeping with restart-safe recovery."""

    def __init__(self, service: BridgeService):
        self.service = service
        self._condition = threading.Condition()
        self._slots = threading.BoundedSemaphore(2)
        self._threads: set[threading.Thread] = set()
        self._closed = False
        self._recover()

    def _recover(self) -> None:
        store = self.service.store
        rows = store.snapshot().get("runs", {})
        for task_id, run in rows.items():
            actions = run.get("control_actions")
            if not isinstance(actions, dict):
                continue
            changed = False
            revised = {}
            for key, action in actions.items():
                if not isinstance(action, dict):
                    revised[key] = action
                    continue
                item = dict(action)
                state = item.get("state")
                item_changed = False
                if state == "queued":
                    item["state"] = "not_sent"
                    item_changed = True
                elif state == "sending":
                    item["state"] = "unknown"
                    item_changed = True
                if item_changed:
                    item["updated_at"] = max(1, int(self.service.clock()))
                    changed = True
                revised[key] = item
            if changed:
                store.update_run(task_id, control_actions=revised)

    def close(self, timeout: float = 60.0) -> bool:
        import time
        deadline = time.monotonic() + max(0.0, timeout)
        with self._condition:
            self._closed = True
            while self._threads:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return False
                self._condition.wait(timeout=remaining)
            return True

    def project(self, run: dict[str, Any], value: dict[str, Any]) -> dict[str, Any]:
        """Apply unresolved control state to a public projection without mutating run."""
        result = dict(value)
        actions = run.get("control_actions")
        if not isinstance(actions, dict):
            return result
        current_approval = run.get("approval")
        current_id = current_approval.get("approval_id") if isinstance(current_approval, dict) else None
        stop = actions.get("stop")
        if isinstance(stop, dict):
            phase = stop.get("state")
            result["stop_delivery"] = phase
            if run.get("status") in {"completed", "failed", "cancelled", "interrupted"}:
                return result
            messages = {
                "queued": "Stop request is queued; the task may still be running.",
                "sending": "Stop request is being sent; the task may still be running.",
                "unknown": "Stop result is not confirmed; the task may still be running.",
                "confirmed": "Stop was delivered; the task has not yet reached a terminal state.",
                "not_sent": "Stop request was not sent; you can retry.",
                "rejected": "Hermes rejected the stop request; you can retry.",
                "superseded": "Stop request was not sent because the task state changed.",
            }
            if phase in messages:
                result["control_message"] = messages[phase]
                result["error"] = result["control_message"]
            if phase in _STOP_INTENT:
                result["status"] = "stopping"
                return result
        if run.get("status") == "waiting_for_approval":
            for action in actions.values():
                if (isinstance(action, dict) and action.get("kind") == "approval"
                        and action.get("approval_id") == current_id
                        and action.get("state") in _PROJECTED):
                    result.pop("approval", None)
                    result["control_message"] = {
                        "queued": "Approval response is queued; do not submit the decision again.",
                        "sending": "Approval response is being sent; its result is not yet confirmed.",
                        "unknown": "Approval result is not confirmed; the decision will not be resent automatically.",
                        "confirmed": "Approval response was delivered; refresh the task for its updated state.",
                    }[action["state"]]
                    result["error"] = result["control_message"]
                    break
        return result

    def submit(self, instance_id: str, bearer: str, task_id: str, kind: str,
               approval_id: str = "", decision: str = "") -> tuple[int, dict[str, Any]]:
        task_id = validate_identifier(task_id, "task_id")
        if kind not in {"stop", "approval"}:
            raise ValidationError("invalid control action kind")
        if kind == "approval":
            approval_id = validate_text(approval_id, "approval_id", 256)
            decision = validate_text(decision, "decision", 20)
            if decision not in {"once", "deny"}:
                raise ValidationError("decision must be once or deny")
            try:
                decode_approval_token(approval_id)
            except ValueError as error:
                raise ValidationError("approval must use a canonical approval token") from error
            key = "approval:" + hashlib.sha256(approval_id.encode("utf-8")).hexdigest()
        else:
            approval_id = ""
            decision = ""
            key = "stop"

        with self.service._try_authorized_connection(instance_id, bearer) as connection:
            if connection is None:
                raise ConflictError("Control not sent: connection is busy")
            instance_lock = self.service._operation_lock("instance:" + instance_id)
            if not instance_lock.acquire(blocking=False):
                raise ConflictError("Control not sent: instance is busy")
            try:
                run = self.service.store.owned_run(connection, task_id)
                instance = self.service.store.get_instance(instance_id)
                if instance.get("kind") != "hermes" or instance.get("retired_at"):
                    raise ValidationError("run controls require an active Hermes identity")
                actions = copy.deepcopy(run.get("control_actions") or {})
                existing = actions.get(key)
                if existing is not None:
                    if existing.get("decision", "") != decision:
                        raise ConflictError("An accepted approval decision cannot be changed")
                    if existing.get("state") in {"queued", "sending", "unknown", "confirmed", "superseded"}:
                        return 202, self.project(run, self.service._public_run(run))
                    if existing.get("state") not in {"not_sent", "rejected"}:
                        raise ConflictError("Control action is not available for retry")
                if kind == "approval" and any(
                        key_name == "stop" and isinstance(action, dict)
                        and action.get("state") in _STOP_INTENT
                        for key_name, action in (run.get("control_actions") or {}).items()):
                    raise ConflictError("Approval cannot be sent after a stop request is active")
                if kind == "stop":
                    if run.get("status") in {"completed", "failed", "cancelled", "interrupted"}:
                        return 200, self.project(run, self.service._public_run(run))
                    if not run.get("upstream_run_id"):
                        raise ConflictError("Task has no upstream run; stop was not sent")
                else:
                    current = run.get("approval")
                    if run.get("status") != "waiting_for_approval" or not isinstance(current, dict) \
                            or current.get("approval_id") != approval_id:
                        raise ConflictError("Approval is no longer current")
                    if not run.get("upstream_run_id"):
                        raise ConflictError("Task has no upstream run")

                client = self.service._hermes(instance_id, allow_unchecked=True, request_timeout=10.0)
                with self._condition:
                    if self._closed or getattr(self.service, "_closed", False):
                        raise ConflictError("Run control queue is closed")
                    if not self._slots.acquire(blocking=False):
                        raise ConflictError("Run control queue is full")
                    old_attempt = existing.get("attempt_id") if existing is not None else None
                    now = max(1, int(self.service.clock()))
                    item = {
                        "kind": kind,
                        "approval_id": approval_id,
                        "decision": decision,
                        "state": "queued",
                        "attempt_id": uuid.uuid4().hex,
                        "requested_at": now,
                        "updated_at": now,
                    }
                    try:
                        saved = self.service.store.update_control_action(
                            task_id, key, item, expected_attempt_id=old_attempt)
                    except Exception:
                        self._slots.release()
                        raise
                    job = {
                        "task_id": task_id,
                        "instance_id": instance_id,
                        "connection": copy.deepcopy(connection),
                        "bearer": bearer,
                        "expected_instance": self._identity_from(instance),
                        "upstream_run_id": run["upstream_run_id"],
                        "kind": kind,
                        "approval_id": approval_id,
                        "decision": decision,
                        "control_key": key,
                        "attempt_id": item["attempt_id"],
                        "client": client,
                    }
                    thread = None
                    registered = False
                    try:
                        thread = threading.Thread(target=self._worker, args=(job,), daemon=True,
                                                  name="padnote-run-control-" + item["attempt_id"][:8])
                        self._threads.add(thread)
                        registered = True
                        thread.start()
                    except Exception:
                        try:
                            self._record_start_failure(task_id, key, item)
                        finally:
                            if registered:
                                self._threads.discard(thread)
                                self._condition.notify_all()
                            self._slots.release()
                        raise
                return 202, self.project(saved, self.service._public_run(saved))
            finally:
                instance_lock.release()

    def _record_start_failure(self, task_id: str, key: str, item: dict[str, Any]) -> None:
        failed = dict(item, state="not_sent", updated_at=max(1, int(self.service.clock())))
        self.service.store.update_control_action(
            task_id, key, failed, expected_attempt_id=item["attempt_id"])

    def _admit_worker(self, job: dict[str, Any], task_stack: ExitStack) -> str:
        """Acquire authorization locks briefly and task lock for the caller's network phase."""
        task_lock = self.service._operation_lock("task:" + job["task_id"])
        with self.service._try_authorized_connection(job["instance_id"], job["bearer"]) as connection:
            if connection is None:
                return "busy"
            instance_lock = self.service._operation_lock("instance:" + job["instance_id"])
            with ExitStack() as instance_stack:
                if not instance_lock.acquire(blocking=False):
                    return "busy"
                instance_stack.callback(instance_lock.release)
                if (connection.get("connection_id") != job["connection"].get("connection_id")
                        or connection.get("device_id") != job["connection"].get("device_id")
                        or getattr(self.service, "_closed", False) or self._closed
                        or self._instance_identity(job["instance_id"]) != job["expected_instance"]):
                    self._transition(job, "not_sent")
                    return "done"
                if not task_lock.acquire(blocking=False):
                    return "busy"
                task_stack.callback(task_lock.release)
                run = self.service.store.owned_run(connection, job["task_id"])
                record = self._current_action(job)
                if (not record or record.get("state") != "queued"
                        or record.get("kind") != job["kind"]
                        or record.get("approval_id") != job["approval_id"]
                        or record.get("decision") != job["decision"]
                        or run.get("upstream_run_id") != job["upstream_run_id"]):
                    self._transition(job, "superseded")
                    return "done"
                if job["kind"] == "approval":
                    stop = run.get("control_actions", {}).get("stop")
                    if isinstance(stop, dict) and stop.get("state") in _STOP_INTENT:
                        self._transition(job, "superseded")
                        return "done"
                    approval = run.get("approval")
                    if (run.get("status") != "waiting_for_approval"
                            or not isinstance(approval, dict)
                            or approval.get("approval_id") != job["approval_id"]):
                        self._transition(job, "superseded")
                        return "done"
                elif run.get("status") in {"completed", "failed", "cancelled", "interrupted"}:
                    self._transition(job, "superseded")
                    return "done"
                sending = dict(record, state="sending", updated_at=max(1, int(self.service.clock())))
                self.service.store.update_control_action(
                    job["task_id"], job["control_key"], sending,
                    expected_attempt_id=job["attempt_id"])
                return "ready"

    def _worker(self, job: dict[str, Any]) -> None:
        sent = False
        try:
            import time
            deadline = time.monotonic() + 15.0
            while time.monotonic() < deadline:
                try:
                    with ExitStack() as task_stack:
                        outcome = self._admit_worker(job, task_stack)
                        if outcome == "done":
                            return
                        if outcome == "ready":
                            try:
                                sent = True
                                if job["kind"] == "stop":
                                    job["client"].stop_run(job["upstream_run_id"])
                                else:
                                    job["client"].approve_run(
                                        job["upstream_run_id"], job["approval_id"], job["decision"])
                                self._transition(job, "confirmed")
                                if job["kind"] == "stop":
                                    self.service.store.update_run(job["task_id"], status="stopping")
                                else:
                                    self.service.store.update_run(
                                        job["task_id"], status="running", approval=None)
                            except HermesError as error:
                                self._transition(job, "unknown" if error.uncertain else "rejected")
                            return
                except Exception:
                    try:
                        self._transition(job, "unknown" if sent else "not_sent")
                    except Exception:
                        pass
                    return
                if outcome == "busy":
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        break
                    with self._condition:
                        self._condition.wait(timeout=min(0.05, remaining))
            self._transition(job, "not_sent")
        finally:
            with self._condition:
                self._threads.discard(threading.current_thread())
                self._condition.notify_all()
            self._slots.release()

    def _instance_identity(self, instance_id: str) -> dict[str, Any]:
        instance = self.service.store.get_instance(instance_id)
        return self._identity_from(instance)

    @staticmethod
    def _identity_from(instance: dict[str, Any]) -> dict[str, Any]:
        return {name: instance.get(name) for name in
                ("instance_id", "kind", "base_url", "config_ref", "config_fingerprint",
                 "credential_fingerprint", "retired_at")}

    def _current_action(self, job: dict[str, Any]) -> dict[str, Any] | None:
        run = self.service.store.snapshot().get("runs", {}).get(job["task_id"], {})
        action = run.get("control_actions", {}).get(job["control_key"])
        if isinstance(action, dict) and action.get("attempt_id") == job["attempt_id"]:
            return action
        return None

    def _transition(self, job: dict[str, Any], state: str) -> None:
        action = self._current_action(job)
        if not action:
            return
        updated = dict(action, state=state, updated_at=max(1, int(self.service.clock())))
        self.service.store.update_control_action(
            job["task_id"], job["control_key"], updated,
            expected_attempt_id=job["attempt_id"])
