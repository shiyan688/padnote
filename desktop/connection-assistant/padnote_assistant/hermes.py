from __future__ import annotations

import json
import http.client
import ipaddress
import math
import queue
import socket
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

from .security import ValidationError, validate_upstream_url


class _RequestDeadline:
    """One monotonic deadline that can interrupt the currently active socket."""

    def __init__(self, timeout: float) -> None:
        self.ends_at = time.monotonic() + timeout
        self._lock = threading.Lock()
        self._socket: socket.socket | None = None
        self._expired = False
        self._timer = threading.Timer(timeout, self._expire)
        self._timer.daemon = True

    def start(self) -> None:
        self._timer.start()

    def remaining(self) -> float:
        remaining = self.ends_at - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Hermes request deadline expired")
        return remaining

    def check(self) -> None:
        self.remaining()

    def register_socket(self, active: socket.socket) -> None:
        try:
            active.settimeout(self.remaining())
        except BaseException:
            active.close()
            raise
        with self._lock:
            if self._expired or time.monotonic() >= self.ends_at:
                expired = True
            else:
                self._socket = active
                expired = False
        if expired:
            self._close_socket(active)
            raise TimeoutError("Hermes request deadline expired")

    def unregister_socket(self, active: socket.socket) -> None:
        with self._lock:
            if self._socket is active:
                self._socket = None

    def finish(self) -> None:
        self._timer.cancel()
        with self._lock:
            self._socket = None

    def _expire(self) -> None:
        with self._lock:
            self._expired = True
            active = self._socket
            self._socket = None
        if active is not None:
            self._close_socket(active)

    @staticmethod
    def _close_socket(active: socket.socket) -> None:
        try:
            active.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            active.close()
        except OSError:
            pass


class _BoundedResolver:
    """Resolve at most two hostnames concurrently without carrying credentials."""

    def __init__(self, capacity: int = 2) -> None:
        self._slots = threading.BoundedSemaphore(capacity)

    def resolve(self, host: str, port: int, deadline: _RequestDeadline) -> list[tuple]:
        try:
            ipaddress.ip_address(host)
            return socket.getaddrinfo(
                host, port, type=socket.SOCK_STREAM, flags=socket.AI_NUMERICHOST)
        except ValueError:
            pass
        if not self._slots.acquire(timeout=deadline.remaining()):
            raise TimeoutError("Hermes DNS resolver is busy")
        result: queue.Queue[tuple[bool, Any]] = queue.Queue(maxsize=1)

        def run() -> None:
            try:
                value = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
                result.put_nowait((True, value))
            except BaseException as error:
                result.put_nowait((False, error))
            finally:
                self._slots.release()

        worker = threading.Thread(target=run, name="padnote-hermes-dns", daemon=True)
        try:
            worker.start()
        except BaseException:
            self._slots.release()
            raise
        try:
            succeeded, value = result.get(timeout=deadline.remaining())
        except queue.Empty as error:
            raise TimeoutError("Hermes DNS resolution timed out") from error
        if not succeeded:
            raise value
        return value


_RESOLVER = _BoundedResolver()


class _ResolvedConnectionMixin:
    def _configure_deadline(self, deadline: _RequestDeadline,
                            addresses: list[tuple]) -> None:
        self._padnote_deadline = deadline
        self._padnote_addresses = addresses
        self._create_connection = self._open_resolved_connection

    def _open_resolved_connection(self, _address, _timeout=None,
                                  source_address=None) -> socket.socket:
        last_error: OSError | None = None
        for family, socktype, proto, _canonical, address in self._padnote_addresses:
            active = socket.socket(family, socktype, proto)
            try:
                self._padnote_deadline.register_socket(active)
                if source_address:
                    active.bind(source_address)
                active.connect(address)
                return active
            except OSError as error:
                last_error = error
                self._padnote_deadline.unregister_socket(active)
                active.close()
        if last_error is not None:
            raise last_error
        raise OSError("Hermes hostname has no usable address")


class _DeadlineHTTPConnection(_ResolvedConnectionMixin, http.client.HTTPConnection):
    def __init__(self, host: str, *, deadline: _RequestDeadline,
                 addresses: list[tuple], **kwargs: Any) -> None:
        kwargs["timeout"] = deadline.remaining()
        super().__init__(host, **kwargs)
        self._configure_deadline(deadline, addresses)

    def connect(self) -> None:
        super().connect()
        if self.sock is None:
            raise OSError("Hermes connection did not create a socket")
        self._padnote_deadline.register_socket(self.sock)


class _DeadlineHTTPSConnection(_ResolvedConnectionMixin, http.client.HTTPSConnection):
    def __init__(self, host: str, *, deadline: _RequestDeadline,
                 addresses: list[tuple], **kwargs: Any) -> None:
        kwargs["timeout"] = deadline.remaining()
        super().__init__(host, **kwargs)
        self._configure_deadline(deadline, addresses)

    def connect(self) -> None:
        # Establish TCP through the pre-resolved address list. Wrapping without
        # an automatic handshake lets the deadline own the SSLSocket before any
        # TLS reads; SNI and default certificate verification still use host.
        http.client.HTTPConnection.connect(self)
        if self.sock is None:
            raise OSError("Hermes connection did not create a socket")
        server_hostname = self._tunnel_host or self.host
        wrapped = self._context.wrap_socket(
            self.sock, server_hostname=server_hostname, do_handshake_on_connect=False)
        self.sock = wrapped
        self._padnote_deadline.register_socket(wrapped)
        wrapped.do_handshake()
        wrapped.settimeout(self._padnote_deadline.remaining())


class _DeadlineHTTPHandler(urllib.request.HTTPHandler):
    def __init__(self, deadline: _RequestDeadline, addresses: list[tuple]) -> None:
        super().__init__()
        self._deadline = deadline
        self._addresses = addresses

    def http_open(self, request):
        def factory(host, **kwargs):
            return _DeadlineHTTPConnection(
                host, deadline=self._deadline, addresses=self._addresses, **kwargs)
        return self.do_open(factory, request)


class _DeadlineHTTPSHandler(urllib.request.HTTPSHandler):
    def __init__(self, deadline: _RequestDeadline, addresses: list[tuple]) -> None:
        super().__init__(context=ssl.create_default_context())
        self._deadline = deadline
        self._addresses = addresses

    def https_open(self, request):
        def factory(host, **kwargs):
            return _DeadlineHTTPSConnection(
                host, deadline=self._deadline, addresses=self._addresses, **kwargs)
        return self.do_open(factory, request, context=self._context)


class HermesError(RuntimeError):
    def __init__(self, code: str, message: str, *, status: int = 502, uncertain: bool = False):
        super().__init__(message)
        self.code = code
        self.status = status
        self.uncertain = uncertain


class _RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        try:
            file_pointer.close()
        except (OSError, ValueError):
            pass
        raise HermesError(
            "upstream_redirect", "Hermes returned an unexpected redirect", status=502,
            uncertain=request.get_method() == "POST")


class CredentialVault:
    """Process-memory Hermes keys. Keys are never serialized or returned by the UI."""

    def __init__(self) -> None:
        self._keys: dict[str, str] = {}

    def set(self, instance_id: str, key: str) -> None:
        value = key.strip()
        if not value or len(value) > 4096 or "\r" in value or "\n" in value:
            raise ValidationError("invalid Hermes key")
        self._keys[instance_id] = value

    def clear(self, instance_id: str) -> None:
        self._keys.pop(instance_id, None)

    def has(self, instance_id: str) -> bool:
        return instance_id in self._keys

    def get(self, instance_id: str) -> str | None:
        return self._keys.get(instance_id)


@dataclass(frozen=True)
class CapabilityResult:
    health: str
    detail: str
    features: dict[str, bool]
    executable: bool


class HermesClient:
    MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    READ_CHUNK_BYTES = 64 * 1024
    MAX_JSON_NUMBER_CHARS = 128
    MAX_JSON_NESTING = 128

    def __init__(self, base_url: str, api_key: str | None, *, timeout: float = 10.0):
        self.base_url = validate_upstream_url(base_url)
        self.api_key = api_key
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)):
            raise ValueError("Hermes timeout must be finite and positive")
        try:
            valid_timeout = math.isfinite(timeout) and timeout > 0
        except (OverflowError, TypeError, ValueError):
            valid_timeout = False
        if not valid_timeout:
            raise ValueError("Hermes timeout must be finite and positive")
        self.timeout = float(timeout)
        parsed = urllib.parse.urlsplit(self.base_url)
        try:
            port = parsed.port or (443 if parsed.scheme == "https" else 80)
        except ValueError as error:
            raise ValidationError("invalid Agent URL port") from error
        self._scheme = parsed.scheme
        self._host = parsed.hostname or ""
        self._port = port

    def _request(self, method: str, path: str, body: Any = None,
                 headers: dict[str, str] | None = None) -> Any:
        data = None
        request_headers = {"Accept": "application/json", "User-Agent": "PadNote-Connection-Assistant/0.1"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        if self.api_key:
            request_headers["Authorization"] = f"Bearer {self.api_key}"
        if headers:
            request_headers.update(headers)
        request = urllib.request.Request(self.base_url + path, data=data, headers=request_headers, method=method)
        deadline = _RequestDeadline(self.timeout)
        deadline.start()
        try:
            addresses = _RESOLVER.resolve(self._host, self._port, deadline)
            if self._scheme == "http":
                try:
                    resolved_ips = [ipaddress.ip_address(item[4][0].split("%", 1)[0])
                                    for item in addresses]
                except ValueError as error:
                    raise HermesError(
                        "upstream_unreachable", "Hermes resolved to an invalid address",
                        status=503) from error
                if not resolved_ips or any(not value.is_loopback for value in resolved_ips):
                    raise HermesError(
                        "upstream_unreachable", "Hermes loopback hostname resolved outside this computer",
                        status=503)
            handlers: list[Any] = [urllib.request.ProxyHandler({}), _RejectRedirects()]
            handlers.append(_DeadlineHTTPSHandler(deadline, addresses)
                            if self._scheme == "https"
                            else _DeadlineHTTPHandler(deadline, addresses))
            opener = urllib.request.build_opener(*handlers)
            with opener.open(request, timeout=deadline.remaining()) as response:
                active = self._response_socket(response)
                if active is not None:
                    deadline.register_socket(active)
                declared = self._content_length(response.headers, method)
                raw = self._read_bounded(response, declared, deadline, method)
            deadline.check()
            try:
                self._validate_json_nesting(raw)
                parsed = json.loads(
                    raw.decode("utf-8"),
                    parse_int=self._parse_json_int,
                    parse_float=self._parse_json_float,
                    parse_constant=lambda _value: (_ for _ in ()).throw(ValueError()),
                )
            except (UnicodeDecodeError, json.JSONDecodeError, ValueError,
                    OverflowError, RecursionError) as error:
                raise HermesError(
                    "upstream_invalid", "Hermes returned invalid JSON",
                    uncertain=method == "POST") from error
            deadline.check()
        except HermesError:
            raise
        except urllib.error.HTTPError as error:
            try:
                if error.code in {401, 403}:
                    raise HermesError("upstream_auth", "Hermes rejected the configured key", status=502) from error
                if error.code == 404:
                    raise HermesError("upstream_disabled", "Hermes API Server route is not enabled", status=502) from error
                if error.code == 409:
                    raise HermesError("upstream_conflict", "Hermes rejected a conflicting request", status=409) from error
                if 400 <= error.code < 500:
                    raise HermesError("upstream_request", "Hermes rejected the request", status=502) from error
                raise HermesError(
                    "upstream_error", "Hermes returned a server error", status=502,
                    uncertain=method == "POST") from error
            finally:
                error.close()
        except http.client.IncompleteRead as error:
            raise HermesError(
                "upstream_invalid", "Hermes returned a truncated response",
                uncertain=method == "POST") from error
        except (urllib.error.URLError, socket.timeout, TimeoutError, http.client.HTTPException,
                ConnectionResetError, ConnectionAbortedError, BrokenPipeError,
                OSError, ValueError) as error:
            reason = getattr(error, "reason", error)
            if isinstance(reason, (ConnectionRefusedError, socket.gaierror)):
                message = "Hermes API Server is not running or is not reachable"
            else:
                message = "Hermes request timed out or the connection failed"
            raise HermesError("upstream_unreachable", message, status=503, uncertain=method == "POST") from error
        finally:
            deadline.finish()
        if not isinstance(parsed, dict):
            raise HermesError("upstream_invalid", "Hermes returned an unexpected response", uncertain=method == "POST")
        return parsed

    @classmethod
    def _content_length(cls, headers: Any, method: str) -> int | None:
        values = headers.get_all("Content-Length") or []
        if len(values) > 1:
            raise HermesError(
                "upstream_invalid", "Hermes returned duplicate response lengths",
                uncertain=method == "POST")
        if not values:
            return None
        value = values[0].strip()
        if len(value) > 20 or not value.isascii() or not value.isdecimal():
            raise HermesError(
                "upstream_invalid", "Hermes returned an invalid response length",
                uncertain=method == "POST")
        declared = int(value)
        if declared > cls.MAX_RESPONSE_BYTES:
            raise HermesError(
                "upstream_too_large", "Hermes response is too large",
                uncertain=method == "POST")
        if headers.get("Transfer-Encoding"):
            raise HermesError(
                "upstream_invalid", "Hermes returned conflicting response framing",
                uncertain=method == "POST")
        return declared

    @classmethod
    def _read_bounded(cls, response: Any, declared: int | None,
                      deadline: _RequestDeadline, method: str) -> bytes:
        chunks: list[bytes] = []
        total = 0
        while declared is None or total < declared:
            deadline.check()
            active = cls._response_socket(response)
            if active is not None:
                deadline.register_socket(active)
            remaining_budget = cls.MAX_RESPONSE_BYTES + 1 - total
            if remaining_budget <= 0:
                raise HermesError(
                    "upstream_too_large", "Hermes response is too large",
                    uncertain=method == "POST")
            wanted = min(cls.READ_CHUNK_BYTES, remaining_budget)
            if declared is not None:
                wanted = min(wanted, declared - total)
            chunk = response.read(wanted)
            deadline.check()
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
        if total > cls.MAX_RESPONSE_BYTES:
            raise HermesError(
                "upstream_too_large", "Hermes response is too large",
                uncertain=method == "POST")
        if declared is not None and total != declared:
            raise HermesError(
                "upstream_invalid", "Hermes returned a truncated response",
                uncertain=method == "POST")
        return b"".join(chunks)

    @staticmethod
    def _response_socket(response: Any) -> socket.socket | None:
        stream = getattr(response, "fp", None)
        raw = getattr(stream, "raw", None)
        active = getattr(raw, "_sock", None)
        return active if isinstance(active, socket.socket) else None

    @classmethod
    def _parse_json_int(cls, value: str) -> int:
        if len(value) > cls.MAX_JSON_NUMBER_CHARS:
            raise ValueError("JSON integer is too long")
        return int(value)

    @classmethod
    def _parse_json_float(cls, value: str) -> float:
        if len(value) > cls.MAX_JSON_NUMBER_CHARS:
            raise ValueError("JSON number is too long")
        parsed = float(value)
        if not math.isfinite(parsed):
            raise ValueError("JSON number is not finite")
        return parsed

    @classmethod
    def _validate_json_nesting(cls, raw: bytes) -> None:
        depth = 0
        in_string = False
        escaped = False
        for value in raw:
            if in_string:
                if escaped:
                    escaped = False
                elif value == 0x5C:  # backslash
                    escaped = True
                elif value == 0x22:  # quote
                    in_string = False
                continue
            if value == 0x22:
                in_string = True
            elif value in (0x5B, 0x7B):  # [ {
                depth += 1
                if depth > cls.MAX_JSON_NESTING:
                    raise ValueError("JSON response nesting is too deep")
            elif value in (0x5D, 0x7D):  # ] }
                depth -= 1

    def check_capabilities(self) -> CapabilityResult:
        try:
            value = self._request("GET", "/v1/capabilities")
        except HermesError as error:
            if error.code == "upstream_auth":
                return CapabilityResult("authentication_failed", str(error), {}, False)
            if error.code == "upstream_disabled":
                return CapabilityResult("api_not_enabled", str(error), {}, False)
            if error.code == "upstream_unreachable":
                return CapabilityResult("not_running", str(error), {}, False)
            return CapabilityResult("error", str(error), {}, False)

        if value.get("object") != "hermes.api_server.capabilities" or value.get("platform") != "hermes-agent":
            return CapabilityResult("wrong_service", "The endpoint is not a supported Hermes API Server", {}, False)
        raw_features = value.get("features") if isinstance(value.get("features"), dict) else {}
        endpoints = value.get("endpoints") if isinstance(value.get("endpoints"), dict) else {}
        def endpoint(name: str, method: str, path: str) -> bool:
            item = endpoints.get(name)
            return isinstance(item, dict) and item.get("method") == method and item.get("path") == path
        # Official Hermes exposes both strict booleans and exact endpoint metadata.
        run_submission = raw_features.get("run_submission") is True and endpoint("runs", "POST", "/v1/runs")
        run_status = raw_features.get("run_status") is True and endpoint("run_status", "GET", "/v1/runs/{run_id}")
        run_stop = raw_features.get("run_stop") is True and endpoint("run_stop", "POST", "/v1/runs/{run_id}/stop")
        run_approval = raw_features.get("run_approval_response") is True and endpoint(
            "run_approval", "POST", "/v1/runs/{run_id}/approval")
        features = {
            "run_submission": run_submission,
            "run_status": run_status,
            "run_stop": run_stop,
            "run_approval_response": run_approval,
            "task_bundle": True,
            "artifacts": True,
        }
        executable = run_submission and run_status and run_stop
        if executable:
            return CapabilityResult("ready", "Hermes task API is available", features, True)
        missing = [name for name in ("run_submission", "run_status", "run_stop") if not features[name]]
        return CapabilityResult(
            "capability_missing",
            "Hermes is reachable but required run capabilities are missing: " + ", ".join(missing),
            features,
            False,
        )

    def create_run(self, input_text: str, idempotency_key: str,
                   *, instructions: str | None = None, session_id: str | None = None) -> dict[str, Any]:
        body: dict[str, Any] = {"input": input_text}
        if instructions:
            body["instructions"] = instructions
        if session_id:
            body["session_id"] = session_id
        return self._request("POST", "/v1/runs", body, {"Idempotency-Key": idempotency_key})

    def get_run(self, upstream_run_id: str) -> dict[str, Any]:
        return self._request("GET", "/v1/runs/" + urllib.parse.quote(upstream_run_id, safe=""))

    def stop_run(self, upstream_run_id: str) -> dict[str, Any]:
        return self._request("POST", "/v1/runs/" + urllib.parse.quote(upstream_run_id, safe="") + "/stop", {})

    def approve_run(self, upstream_run_id: str, approval_id: str, decision: str) -> dict[str, Any]:
        if decision not in {"once", "deny"}:
            raise ValidationError("approval decision must be once or deny")
        return self._request(
            "POST",
            "/v1/runs/" + urllib.parse.quote(upstream_run_id, safe="") + "/approval",
            {"request_id": approval_id, "choice": decision},
        )


RUN_STATUSES = {
    "queued": "running",
    "submitting": "submitting",
    "running": "running",
    "waiting_for_approval": "waiting_for_approval",
    "stopping": "stopping",
    "completed": "completed",
    "failed": "failed",
    "cancelled": "cancelled",
    "canceled": "cancelled",
    "interrupted": "interrupted",
}


def normalize_run(value: dict[str, Any]) -> dict[str, Any]:
    raw_status = str(value.get("status", "")).lower()
    if raw_status not in RUN_STATUSES:
        raise HermesError("upstream_status_unknown", "Hermes returned an unknown run status")
    status = RUN_STATUSES[raw_status]
    approval = None
    raw_approval = value.get("approval")
    if status == "waiting_for_approval" and isinstance(raw_approval, dict):
        request_id = raw_approval.get("request_id")
        if isinstance(request_id, str) and request_id == request_id.strip() \
                and 1 <= len(request_id) <= 256 and "\r" not in request_id and "\n" not in request_id:
            title = raw_approval.get("title") or raw_approval.get("tool_name") or "Hermes 需要批准"
            details = []
            for key in ("description", "reason", "command", "tool_name", "question"):
                detail = raw_approval.get(key)
                if detail not in (None, ""):
                    rendered = detail if isinstance(detail, str) else json.dumps(detail, ensure_ascii=False, sort_keys=True)
                    details.append(f"{key}: {rendered}")
            description = "\n".join(details) or "请在继续前在电脑上检查这项操作。"
            approval = {
                "approval_id": request_id,
                "title": str(title)[:256],
                "description": str(description)[:2000],
            }
    output = value.get("output") if status == "completed" else None
    error = value.get("error") if status in {"failed", "cancelled", "interrupted"} else None
    if error is not None and not isinstance(error, (str, dict)):
        error = "Hermes task failed"
    return {"status": status, "output": output, "error": error, "approval": approval}
