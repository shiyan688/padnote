from __future__ import annotations

import copy
import threading
import time
import weakref
from typing import Any, Callable, Protocol

from .video_worker import VideoWorkerError


_TERMINAL_FAILURE_CODES = frozenset({
    "worker_failed", "worker_unknown", "worker_interrupted", "worker_result_invalid",
    "worker_timeout", "authorization_revoked", "instance_retired", "run_unavailable",
    "source_changed", "binding_changed", "cancelled",
    "tts_input_invalid", "tts_provider_request_failed", "tts_provider_http_error",
    "tts_provider_response_invalid", "tts_provider_response_too_large", "tts_audio_url_missing",
    "tts_audio_url_rejected", "tts_audio_download_failed", "tts_audio_download_http_error",
    "tts_audio_download_too_large", "tts_audio_format_invalid", "tts_audio_output_write_failed",
    "tts_adapter_failure", "tts_adapter_result_invalid", "tts_adapter_start_failed",
})
_STORE_OWNERS_LOCK = threading.Lock()
_STORE_OWNERS: weakref.WeakKeyDictionary[object, weakref.ReferenceType] = weakref.WeakKeyDictionary()


class VideoOperationStore(Protocol):
    """Persistent queue contract implemented by the connection StateStore."""

    def recover_video_operations(self) -> int: ...

    def claim_next_video_operation(self) -> dict[str, Any] | None: ...

    def finish_video_operation(
        self, operation_id: str, attempt_id: str, status: str, *,
        result: dict[str, Any] | None = None, error: str | None = None,
    ) -> dict[str, Any]: ...


class VideoOperationScheduler:
    """Runs persistent video operations serially without creating per-job threads."""

    def __init__(self, store: VideoOperationStore,
                 execute_callback: Callable[[dict[str, Any]], dict[str, Any]],
                 finish_callback: Callable[[dict[str, Any], dict[str, Any]], None] | None = None) -> None:
        self._store = store
        self._execute = execute_callback
        self._finish = finish_callback
        self._condition = threading.Condition()
        self._thread: threading.Thread | None = None
        self._started = False
        self._starting = False
        self._closed = False
        self._stopping = False
        self._halted = False
        self._busy = False
        self._wake_pending = True
        self._safe_failure_code: str | None = None
        self._owns_store = False

    @property
    def safe_failure_code(self) -> str | None:
        """A fixed, path-free scheduler diagnostic; never returns exception text."""
        with self._condition:
            return self._safe_failure_code

    @property
    def halted(self) -> bool:
        with self._condition:
            return self._halted

    def start(self) -> bool:
        """Recover durable in-flight operations before making queued work runnable."""
        with self._condition:
            if self._closed or self._stopping:
                return False
            if self._started:
                while self._starting and not self._closed:
                    self._condition.wait()
                return self._thread is not None and self._thread.is_alive()
            # Reserve start while recovery is synchronous, so concurrent callers
            # cannot run recovery twice or race thread creation.
            self._started = True
            self._starting = True
        if not self._acquire_store_ownership():
            with self._condition:
                self._starting = False
                self._halted = True
                self._safe_failure_code = "scheduler_already_active"
                self._condition.notify_all()
            return False
        try:
            self._store.recover_video_operations()
        except BaseException:
            with self._condition:
                self._starting = False
                self._halted = True
                self._safe_failure_code = "recovery_failed"
                self._condition.notify_all()
            return False
        with self._condition:
            self._starting = False
            if self._closed or self._stopping or self._halted:
                self._condition.notify_all()
                return False
            thread = threading.Thread(
                target=self._run, name="padnote-video-scheduler", daemon=False,
            )
            try:
                thread.start()
            except BaseException:
                self._halted = True
                self._safe_failure_code = "thread_start_failed"
                self._thread = None
                self._condition.notify_all()
                return False
            self._thread = thread
            self._condition.notify_all()
            return True

    def wake(self) -> None:
        with self._condition:
            self._wake_pending = True
            self._condition.notify_all()

    def close(self, timeout: float | None = None) -> bool:
        """Stop future claims and optionally wait for the current callback to finish.

        The scheduler never closes the store. The current claimed operation covers
        its claim, callback and finish commit; close never abandons that sequence.
        False means store access is still in progress and ownership must be retained.
        """
        deadline = None if timeout is None else time.monotonic() + max(0.0, timeout)
        with self._condition:
            self._closed = True
            self._stopping = True
            self._condition.notify_all()
            while self._starting:
                remaining = None if deadline is None else deadline - time.monotonic()
                if remaining is not None and remaining <= 0:
                    return False
                self._condition.wait(remaining)
            thread = self._thread
        if thread is None:
            self._release_store_ownership()
            return True
        if thread is threading.current_thread():
            return False
        remaining = None if deadline is None else max(0.0, deadline - time.monotonic())
        thread.join(remaining)
        completed = not thread.is_alive()
        if completed:
            self._release_store_ownership()
        return completed

    def _run(self) -> None:
        while True:
            with self._condition:
                while not self._stopping and not self._halted and not self._wake_pending:
                    self._condition.wait()
                if self._stopping or self._halted:
                    return
                self._wake_pending = False
                self._busy = True

            try:
                operation = self._store.claim_next_video_operation()
            except BaseException:
                self._halt("claim_failed")
                return

            if operation is None:
                with self._condition:
                    self._busy = False
                    self._condition.notify_all()
                    # A wake during claim should immediately retry; otherwise wait
                    # for an enqueue notification without polling the ledger.
                    if not self._stopping and not self._halted and not self._wake_pending:
                        self._condition.wait()
                    if self._stopping or self._halted:
                        return
                continue

            operation_id = operation.get("operation_id")
            attempt_id = operation.get("attempt_id")
            if not isinstance(operation_id, str) or not isinstance(attempt_id, str):
                self._halt("claimed_operation_invalid")
                return

            try:
                result = self._execute(copy.deepcopy(operation))
                if not isinstance(result, dict):
                    raise VideoWorkerError(
                        "worker_result_invalid", "Video worker result is invalid", unknown=True)
                status = "succeeded"
                safe_result = result
                safe_error = None
            except VideoWorkerError as error:
                status, safe_result, safe_error = self._classify_worker_error(error)
            except BaseException:
                status, safe_result, safe_error = "unknown", None, "worker_unknown"

            try:
                finished = self._store.finish_video_operation(
                    operation_id, attempt_id, status, result=safe_result, error=safe_error)
                if self._finish is not None:
                    self._finish(copy.deepcopy(operation), copy.deepcopy(finished))
            except BaseException:
                self._halt("finish_failed")
                return
            with self._condition:
                self._busy = False
                self._condition.notify_all()
                if self._stopping or self._halted:
                    return
                # One operation at a time; continue draining already queued work.
                self._wake_pending = True

    @staticmethod
    def _classify_worker_error(error: VideoWorkerError) -> tuple[str, None, str]:
        if error.unknown:
            code = error.code if isinstance(error.code, str) \
                and error.code in _TERMINAL_FAILURE_CODES else "worker_unknown"
            if code not in {"worker_result_invalid", "worker_timeout", "worker_interrupted"} \
                    and not code.startswith("tts_"):
                code = "worker_unknown"
            return "unknown", None, code
        code = error.code if isinstance(error.code, str) \
            and error.code in _TERMINAL_FAILURE_CODES else "worker_failed"
        if code in {"worker_unknown", "worker_interrupted", "worker_timeout"}:
            code = "worker_failed"
        return "failed", None, code

    def _halt(self, code: str) -> None:
        with self._condition:
            self._halted = True
            self._safe_failure_code = code
            self._busy = False
            self._condition.notify_all()

    def _acquire_store_ownership(self) -> bool:
        with _STORE_OWNERS_LOCK:
            owner_ref = _STORE_OWNERS.get(self._store)
            owner = owner_ref() if owner_ref is not None else None
            if owner is not None and owner is not self:
                return False
            _STORE_OWNERS[self._store] = weakref.ref(self)
            self._owns_store = True
            return True

    def _release_store_ownership(self) -> None:
        if not self._owns_store:
            return
        with _STORE_OWNERS_LOCK:
            owner_ref = _STORE_OWNERS.get(self._store)
            if owner_ref is not None and owner_ref() is self:
                del _STORE_OWNERS[self._store]
            self._owns_store = False
