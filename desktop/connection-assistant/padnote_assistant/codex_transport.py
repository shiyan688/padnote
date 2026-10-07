"""Bounded stdio JSONL transport for a future Codex app-server adapter.

This module deliberately knows nothing about PadNote's HTTP bridge or task model.  It
only owns a child process and the bidirectional JSON-RPC-like wire protocol documented
for ``codex app-server``.  Callers must make every approval decision explicitly.
"""

from __future__ import annotations

import collections
import dataclasses
import json
import math
import os
import queue
import subprocess
import threading
import time
from collections.abc import Mapping, Sequence
from typing import Any


class CodexTransportError(RuntimeError):
    """Base class for transport failures."""


class CodexProtocolError(CodexTransportError):
    """The child emitted a malformed or out-of-bounds protocol frame."""


class CodexChildExited(CodexTransportError):
    """The app-server child exited while the transport was active."""

    def __init__(self, returncode: int | None) -> None:
        self.returncode = returncode
        super().__init__(f"Codex app-server exited (code {returncode})")


class CodexTransportClosed(CodexTransportError):
    """The transport has been shut down."""


class CodexRequestTimeout(CodexTransportError):
    """A local wait expired; the late response will be ignored."""


class CodexRequestCancelled(CodexTransportError):
    """A local request wait was explicitly cancelled."""


class CodexTooManyPending(CodexTransportError):
    """The configured in-flight request bound was reached."""


class CodexRemoteError(CodexTransportError):
    """An error response returned by app-server."""

    def __init__(self, code: Any, message: str, data: Any = None) -> None:
        self.code = code
        self.message = message
        self.data = data
        super().__init__(f"Codex app-server error {code}: {message}")


@dataclasses.dataclass(frozen=True)
class CodexNotification:
    method: str
    params: Any


@dataclasses.dataclass(frozen=True)
class CodexServerRequest:
    request_id: int | str
    method: str
    params: Any


class _Pending:
    def __init__(self) -> None:
        self.event = threading.Event()
        self.result: Any = None
        self.error: BaseException | None = None


@dataclasses.dataclass(frozen=True)
class _OutgoingFrame:
    data: bytes
    request_id: int | None


@dataclasses.dataclass(frozen=True)
class _QueuedInbound:
    value: Any
    byte_count: int
    release_on_get: bool


class CodexRequestHandle:
    """A correlated request that can be waited on or locally cancelled."""

    def __init__(self, transport: "CodexStdioTransport", request_id: int,
                 pending: _Pending) -> None:
        self._transport = transport
        self.request_id = request_id
        self._pending = pending

    def result(self, timeout: float | None = None) -> Any:
        timeout = self._transport._validate_timeout(timeout)
        if not self._pending.event.wait(timeout):
            self._transport._cancel_request(
                self.request_id, CodexRequestTimeout("Codex request timed out"))
            self._pending.event.wait()
        if self._pending.error is not None:
            raise self._pending.error
        return self._pending.result

    def cancel(self) -> bool:
        """Cancel the local wait; an already-started frame may still be processed."""
        return self._transport._cancel_request(
            self.request_id, CodexRequestCancelled("Codex request cancelled"))


class CodexStdioTransport:
    """Thread-safe, bounded transport for one app-server child process."""

    DEFAULT_MAX_FRAME_BYTES = 4 * 1024 * 1024
    DEFAULT_MAX_PENDING = 32
    DEFAULT_MAX_NOTIFICATIONS = 512
    DEFAULT_MAX_SERVER_REQUESTS = 32
    DEFAULT_MAX_OUTGOING_FRAMES = 64
    DEFAULT_MAX_OUTGOING_BYTES = 16 * 1024 * 1024
    DEFAULT_MAX_INCOMING_EVENT_BYTES = 16 * 1024 * 1024
    DEFAULT_STDERR_BYTES = 64 * 1024

    def __init__(
        self,
        command: Sequence[str],
        *,
        cwd: str | None = None,
        env: Mapping[str, str] | None = None,
        max_frame_bytes: int = DEFAULT_MAX_FRAME_BYTES,
        max_pending_requests: int = DEFAULT_MAX_PENDING,
        max_notifications: int = DEFAULT_MAX_NOTIFICATIONS,
        max_server_requests: int = DEFAULT_MAX_SERVER_REQUESTS,
        max_outgoing_frames: int = DEFAULT_MAX_OUTGOING_FRAMES,
        max_outgoing_bytes: int = DEFAULT_MAX_OUTGOING_BYTES,
        max_incoming_event_bytes: int = DEFAULT_MAX_INCOMING_EVENT_BYTES,
        max_stderr_bytes: int = DEFAULT_STDERR_BYTES,
    ) -> None:
        if isinstance(command, (str, bytes, bytearray)):
            raise ValueError("command must be an argv sequence, not text or bytes")
        argv = list(command)
        if not argv or any(not isinstance(value, str) or not value or "\0" in value
                           for value in argv):
            raise ValueError("command must contain non-empty, NUL-free strings")
        for name, value in {
            "max_frame_bytes": max_frame_bytes,
            "max_pending_requests": max_pending_requests,
            "max_notifications": max_notifications,
            "max_server_requests": max_server_requests,
            "max_outgoing_frames": max_outgoing_frames,
            "max_outgoing_bytes": max_outgoing_bytes,
            "max_incoming_event_bytes": max_incoming_event_bytes,
            "max_stderr_bytes": max_stderr_bytes,
        }.items():
            if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
                raise ValueError(f"{name} must be a positive integer")

        self.max_frame_bytes = max_frame_bytes
        self.max_pending_requests = max_pending_requests
        self.max_server_requests = max_server_requests
        self.max_outgoing_bytes = max_outgoing_bytes
        self.max_incoming_event_bytes = max_incoming_event_bytes
        self._stderr_limit = max_stderr_bytes
        self._lock = threading.RLock()
        self._initialize_lock = threading.Lock()
        self._pending: dict[int, _Pending] = {}
        self._ignored_ids: collections.deque[int] = collections.deque()
        self._ignored_id_set: set[int] = set()
        self._server_requests: dict[int | str, tuple[CodexServerRequest, int]] = {}
        self._notifications: queue.Queue[_QueuedInbound] = queue.Queue(max_notifications)
        self._server_request_queue: queue.Queue[_QueuedInbound] = queue.Queue(
            max_server_requests)
        self._outgoing: queue.Queue[_OutgoingFrame] = queue.Queue(max_outgoing_frames)
        self._outgoing_bytes = 0
        self._incoming_event_bytes = 0
        self._next_id = 1
        self._initialized = False
        self._initializing = False
        self._closing = False
        self._terminal_error: BaseException | None = None
        self._stderr = bytearray()

        # shell=False and an argv sequence ensure protocol or model text is never
        # interpreted as a local command line.
        self._process = subprocess.Popen(
            argv,
            cwd=cwd,
            env=None if env is None else dict(env),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            shell=False,
            bufsize=0,
        )
        self._reader = threading.Thread(target=self._read_loop,
                                        name="padnote-codex-stdout", daemon=True)
        self._writer = threading.Thread(target=self._write_loop,
                                        name="padnote-codex-stdin", daemon=True)
        self._stderr_reader = threading.Thread(target=self._stderr_loop,
                                               name="padnote-codex-stderr", daemon=True)
        self._reader.start()
        self._writer.start()
        self._stderr_reader.start()

    @property
    def pid(self) -> int:
        return self._process.pid

    @property
    def initialized(self) -> bool:
        with self._lock:
            return self._initialized

    @property
    def pending_count(self) -> int:
        with self._lock:
            return len(self._pending)

    @property
    def stderr_tail(self) -> bytes:
        with self._lock:
            return bytes(self._stderr)

    def initialize(self, client_info: Mapping[str, Any], *, timeout: float = 10.0,
                   capabilities: Mapping[str, Any] | None = None) -> Any:
        """Perform the one-time initialize request and initialized notification."""
        if not isinstance(client_info, Mapping):
            raise TypeError("client_info must be a mapping")
        timeout = self._validate_timeout(timeout, allow_none=False)
        with self._initialize_lock:
            started = time.monotonic()
            with self._lock:
                self._ensure_open_locked()
                if self._initialized or self._initializing:
                    raise CodexProtocolError("transport is already initialized")
                self._initializing = True
            try:
                params: dict[str, Any] = {"clientInfo": dict(client_info)}
                if capabilities is not None:
                    params["capabilities"] = dict(capabilities)
                handle = self._send_request("initialize", params, allow_uninitialized=True)
                result = handle.result(self._remaining_timeout(started, timeout))
                self._write_message({"method": "initialized", "params": {}})
                with self._lock:
                    self._initialized = True
                return result
            except BaseException as error:
                self._fail(error if isinstance(error, CodexTransportError)
                           else CodexProtocolError("Codex initialization failed"))
                raise
            finally:
                with self._lock:
                    self._initializing = False

    def send_request(self, method: str, params: Any = None) -> CodexRequestHandle:
        return self._send_request(method, params, allow_uninitialized=False)

    def request(self, method: str, params: Any = None,
                *, timeout: float | None = None) -> Any:
        timeout = self._validate_timeout(timeout)
        started = time.monotonic()
        handle = self.send_request(method, params)
        return handle.result(self._remaining_timeout(started, timeout))

    def notify(self, method: str, params: Any = None) -> None:
        self._validate_method(method)
        with self._lock:
            self._ensure_ready_locked()
        self._write_message({"method": method, "params": params})

    def next_notification(self, timeout: float | None = None) -> CodexNotification:
        return self._queue_get(self._notifications, timeout)

    def next_server_request(self, timeout: float | None = None) -> CodexServerRequest:
        return self._queue_get(self._server_request_queue, timeout)

    def reply_server_request(self, request_id: int | str, *, result: Any = None,
                             error: Mapping[str, Any] | None = None) -> None:
        request_id = self._validate_id(request_id)
        if error is not None and result is not None:
            raise ValueError("reply must contain result or error, not both")
        message: dict[str, Any] = {"id": request_id}
        if error is None:
            message["result"] = result
        else:
            message["error"] = dict(error)
        with self._lock:
            self._ensure_open_locked()
            entry = self._server_requests.get(request_id)
            if entry is None:
                raise CodexProtocolError("server request is not pending")
            # Enqueue while holding the re-entrant transport lock so a duplicate
            # reverse request cannot race the removal of the original one.
            self._write_message(message)
            del self._server_requests[request_id]
            self._incoming_event_bytes -= entry[1]

    def shutdown(self, timeout: float = 2.0) -> int | None:
        """Wake callers and stop the writer and child within a shared deadline."""
        timeout = self._validate_timeout(timeout, allow_none=False)
        with self._lock:
            if not self._closing:
                self._closing = True
                self._fail_locked(CodexTransportClosed("Codex transport shut down"))
        deadline = time.monotonic() + timeout
        # Do not close stdin here: a buffered or partially-written pipe can block.
        # A short natural-exit grace period is followed by terminate/kill, which
        # closes the child's read end and releases a writer blocked in os.write.
        graceful = min(max(0.0, deadline - time.monotonic()), timeout * 0.1)
        if not self._wait_process(graceful):
            try:
                self._process.terminate()
            except OSError:
                pass
            terminate_wait = min(max(0.0, deadline - time.monotonic()), timeout * 0.3)
            if not self._wait_process(terminate_wait):
                try:
                    self._process.kill()
                except OSError:
                    pass
                self._wait_process(max(0.0, deadline - time.monotonic()))
        self._reader.join(timeout=max(0.0, deadline - time.monotonic()))
        self._writer.join(timeout=max(0.0, deadline - time.monotonic()))
        self._stderr_reader.join(timeout=max(0.0, deadline - time.monotonic()))
        for stream in (self._process.stdin, self._process.stdout, self._process.stderr):
            if stream is not None:
                try:
                    stream.close()
                except (OSError, ValueError):
                    pass
        return self._process.poll()

    def __enter__(self) -> "CodexStdioTransport":
        return self

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        self.shutdown()

    def _send_request(self, method: str, params: Any,
                      *, allow_uninitialized: bool) -> CodexRequestHandle:
        self._validate_method(method)
        with self._lock:
            self._ensure_open_locked()
            if not allow_uninitialized:
                self._ensure_ready_locked()
            if len(self._pending) >= self.max_pending_requests:
                raise CodexTooManyPending("too many pending Codex requests")
            request_id = self._next_id
            self._next_id += 1
            pending = _Pending()
            self._pending[request_id] = pending
        try:
            self._write_message({"method": method, "id": request_id, "params": params},
                                request_id=request_id)
        except BaseException as error:
            with self._lock:
                self._pending.pop(request_id, None)
            pending.error = error
            pending.event.set()
            raise
        return CodexRequestHandle(self, request_id, pending)

    def _write_message(self, message: Mapping[str, Any],
                       *, request_id: int | None = None) -> None:
        try:
            frame = json.dumps(message, ensure_ascii=False, separators=(",", ":"),
                               allow_nan=False).encode("utf-8")
        except (TypeError, ValueError) as error:
            raise CodexProtocolError("message is not bounded JSON data") from error
        if len(frame) > self.max_frame_bytes:
            raise CodexProtocolError("outgoing JSONL frame exceeds limit")
        outgoing = _OutgoingFrame(frame + b"\n", request_id)
        with self._lock:
            self._ensure_open_locked()
            if self._outgoing_bytes + len(outgoing.data) > self.max_outgoing_bytes:
                raise CodexTooManyPending("Codex outgoing byte budget is exhausted")
            try:
                self._outgoing.put_nowait(outgoing)
            except queue.Full as error:
                raise CodexTooManyPending("Codex outgoing frame queue is full") from error
            self._outgoing_bytes += len(outgoing.data)

    def _write_loop(self) -> None:
        stdin = self._process.stdin
        if stdin is None:
            self._fail(CodexProtocolError("Codex stdin is unavailable"))
            return
        descriptor = stdin.fileno()
        try:
            while True:
                try:
                    outgoing = self._outgoing.get(timeout=0.1)
                except queue.Empty:
                    with self._lock:
                        if self._closing or self._terminal_error is not None:
                            return
                    continue
                with self._lock:
                    self._outgoing_bytes -= len(outgoing.data)
                    if self._closing or self._terminal_error is not None:
                        return
                    if outgoing.request_id is not None:
                        # Cancellation before the first byte is written removes
                        # the request from _pending, regardless of how many old
                        # cancellation IDs the late-response cache retained.
                        if outgoing.request_id not in self._pending:
                            continue
                view = memoryview(outgoing.data)
                while view:
                    written = os.write(descriptor, view)
                    if written <= 0:
                        raise BrokenPipeError("Codex stdin accepted no bytes")
                    view = view[written:]
                    with self._lock:
                        if self._closing or self._terminal_error is not None:
                            return
        except (BrokenPipeError, OSError, ValueError) as error:
            with self._lock:
                closing = self._closing
            if not closing:
                failure = CodexChildExited(self._process.poll())
                self._fail(failure)
            return

    def _read_loop(self) -> None:
        stdout = self._process.stdout
        if stdout is None:
            self._fail(CodexProtocolError("Codex stdout is unavailable"))
            return
        try:
            while True:
                line = stdout.readline(self.max_frame_bytes + 2)
                if not line:
                    break
                if not line.endswith(b"\n") or len(line) > self.max_frame_bytes + 1:
                    raise CodexProtocolError("incoming JSONL frame exceeds limit")
                payload = line[:-1]
                if payload.endswith(b"\r"):
                    payload = payload[:-1]
                if not payload or len(payload) > self.max_frame_bytes:
                    raise CodexProtocolError("incoming JSONL frame is empty or exceeds limit")
                try:
                    message = json.loads(payload.decode("utf-8", errors="strict"),
                                         parse_constant=self._reject_json_constant)
                except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
                    raise CodexProtocolError("incoming JSONL frame is invalid") from error
                if not isinstance(message, dict):
                    raise CodexProtocolError("incoming JSONL frame must be an object")
                self._ensure_finite_json(message)
                self._dispatch(message, len(payload))
        except BaseException as error:
            self._fail(error if isinstance(error, CodexTransportError)
                       else CodexProtocolError("Codex reader failed"))
            return
        returncode = self._process.poll()
        if returncode is None:
            try:
                returncode = self._process.wait(timeout=0.2)
            except subprocess.TimeoutExpired:
                returncode = None
        with self._lock:
            closing = self._closing
        self._fail(CodexTransportClosed("Codex transport shut down") if closing
                   else CodexChildExited(returncode))

    def _stderr_loop(self) -> None:
        stderr = self._process.stderr
        if stderr is None:
            return
        while True:
            try:
                chunk = stderr.read(4096)
            except OSError:
                return
            if not chunk:
                return
            with self._lock:
                self._stderr.extend(chunk)
                overflow = len(self._stderr) - self._stderr_limit
                if overflow > 0:
                    del self._stderr[:overflow]

    def _dispatch(self, message: dict[str, Any], frame_bytes: int) -> None:
        has_id = "id" in message
        has_method = "method" in message
        if has_id and has_method:
            request_id = self._validate_id(message["id"])
            method = self._validate_method(message["method"])
            request = CodexServerRequest(request_id, method, message.get("params"))
            queued = _QueuedInbound(request, frame_bytes, False)
            with self._lock:
                self._ensure_open_locked()
                if request_id in self._server_requests:
                    raise CodexProtocolError("duplicate server request id")
                if len(self._server_requests) >= self.max_server_requests:
                    raise CodexProtocolError("too many pending server requests")
                if (self._incoming_event_bytes + frame_bytes
                        > self.max_incoming_event_bytes):
                    raise CodexProtocolError("incoming event byte budget is exhausted")
                try:
                    self._server_request_queue.put_nowait(queued)
                except queue.Full as error:
                    raise CodexProtocolError("server request queue is full") from error
                self._server_requests[request_id] = (request, frame_bytes)
                self._incoming_event_bytes += frame_bytes
            return
        if has_method:
            method = self._validate_method(message["method"])
            notification = CodexNotification(method, message.get("params"))
            queued = _QueuedInbound(notification, frame_bytes, True)
            with self._lock:
                if (self._incoming_event_bytes + frame_bytes
                        > self.max_incoming_event_bytes):
                    raise CodexProtocolError("incoming event byte budget is exhausted")
                try:
                    self._notifications.put_nowait(queued)
                except queue.Full as error:
                    raise CodexProtocolError("notification queue is full") from error
                self._incoming_event_bytes += frame_bytes
            return
        if has_id:
            request_id = self._validate_id(message["id"])
            if not isinstance(request_id, int):
                raise CodexProtocolError("response id must match a numeric client request")
            with self._lock:
                pending = self._pending.pop(request_id, None)
                if pending is None and request_id in self._ignored_id_set:
                    self._ignored_id_set.remove(request_id)
                    try:
                        self._ignored_ids.remove(request_id)
                    except ValueError:
                        pass
                    return
            if pending is None:
                raise CodexProtocolError("response id does not match a pending request")
            has_result = "result" in message
            has_error = "error" in message
            if has_result == has_error:
                failure = CodexProtocolError(
                    "response must contain exactly one of result or error")
                pending.error = failure
                pending.event.set()
                raise failure
            elif has_error:
                error = message["error"]
                if not isinstance(error, dict):
                    failure = CodexProtocolError("response error must be an object")
                    pending.error = failure
                    pending.event.set()
                    raise failure
                else:
                    pending.error = CodexRemoteError(error.get("code"),
                                                     str(error.get("message", "")),
                                                     error.get("data"))
            else:
                pending.result = message["result"]
            pending.event.set()
            return
        raise CodexProtocolError("incoming frame is neither response, request, nor notification")

    def _cancel_request(self, request_id: int, error: BaseException) -> bool:
        with self._lock:
            pending = self._pending.pop(request_id, None)
            if pending is None:
                return False
            self._remember_ignored_locked(request_id)
            pending.error = error
            pending.event.set()
            return True

    def _remember_ignored_locked(self, request_id: int) -> None:
        self._ignored_ids.append(request_id)
        self._ignored_id_set.add(request_id)
        while len(self._ignored_ids) > self.max_pending_requests:
            expired = self._ignored_ids.popleft()
            self._ignored_id_set.discard(expired)

    def _fail(self, error: BaseException) -> None:
        with self._lock:
            self._fail_locked(error)
        if not self._closing and self._process.poll() is None:
            try:
                self._process.terminate()
            except OSError:
                pass

    def _fail_locked(self, error: BaseException) -> None:
        if self._terminal_error is not None:
            return
        self._terminal_error = error
        pending = list(self._pending.values())
        self._pending.clear()
        for value in pending:
            value.error = error
            value.event.set()

    def _ensure_open_locked(self) -> None:
        if self._terminal_error is not None:
            raise self._terminal_error
        if self._closing:
            raise CodexTransportClosed("Codex transport is closed")

    def _ensure_ready_locked(self) -> None:
        self._ensure_open_locked()
        if not self._initialized:
            raise CodexProtocolError("Codex transport is not initialized")

    @staticmethod
    def _validate_method(method: Any) -> str:
        if not isinstance(method, str) or not method or len(method) > 256 or "\0" in method:
            raise CodexProtocolError("invalid protocol method")
        return method

    @staticmethod
    def _validate_id(request_id: Any) -> int | str:
        if isinstance(request_id, bool) or not isinstance(request_id, (int, str)):
            raise CodexProtocolError("invalid protocol request id")
        if isinstance(request_id, str) and (not request_id or len(request_id) > 256):
            raise CodexProtocolError("invalid protocol request id")
        return request_id

    def _queue_get(self, values: queue.Queue[_QueuedInbound],
                   timeout: float | None) -> Any:
        timeout = self._validate_timeout(timeout)
        deadline = None if timeout is None else time.monotonic() + timeout
        while True:
            # A zero timeout is a poll. An already queued value therefore wins
            # over the expired deadline and over a later terminal condition.
            try:
                return self._unwrap_queued_inbound(values.get_nowait())
            except queue.Empty:
                pass
            with self._lock:
                terminal = self._terminal_error
            if terminal is not None and values.empty():
                raise terminal
            remaining = None if deadline is None else deadline - time.monotonic()
            if remaining is not None and remaining <= 0:
                raise CodexRequestTimeout("timed out waiting for Codex event")
            try:
                queued = values.get(timeout=min(0.1, remaining)
                                    if remaining is not None else 0.1)
                return self._unwrap_queued_inbound(queued)
            except queue.Empty:
                continue

    def _unwrap_queued_inbound(self, queued: _QueuedInbound) -> Any:
        if queued.release_on_get:
            with self._lock:
                self._incoming_event_bytes -= queued.byte_count
        return queued.value

    def _wait_process(self, timeout: float) -> bool:
        try:
            self._process.wait(timeout=timeout)
            return True
        except subprocess.TimeoutExpired:
            return False

    @staticmethod
    def _remaining_timeout(started: float, timeout: float | None) -> float | None:
        if timeout is None:
            return None
        return max(0.0, timeout - (time.monotonic() - started))

    @staticmethod
    def _validate_timeout(timeout: Any, *, allow_none: bool = True) -> float | None:
        if timeout is None:
            if allow_none:
                return None
            raise ValueError("timeout must be finite and non-negative")
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)):
            raise ValueError("timeout must be finite and non-negative")
        try:
            finite = math.isfinite(timeout)
        except (OverflowError, TypeError, ValueError):
            finite = False
        if not finite or timeout < 0:
            raise ValueError("timeout must be finite and non-negative")
        return float(timeout)

    @staticmethod
    def _reject_json_constant(value: str) -> Any:
        raise ValueError(f"non-finite JSON number is forbidden: {value}")

    @staticmethod
    def _ensure_finite_json(value: Any) -> None:
        pending = [value]
        while pending:
            current = pending.pop()
            if isinstance(current, float) and not math.isfinite(current):
                raise CodexProtocolError("incoming JSON contains a non-finite number")
            if isinstance(current, dict):
                pending.extend(current.values())
            elif isinstance(current, list):
                pending.extend(current)


__all__ = [
    "CodexChildExited",
    "CodexNotification",
    "CodexProtocolError",
    "CodexRemoteError",
    "CodexRequestCancelled",
    "CodexRequestHandle",
    "CodexRequestTimeout",
    "CodexServerRequest",
    "CodexStdioTransport",
    "CodexTooManyPending",
    "CodexTransportClosed",
    "CodexTransportError",
]
