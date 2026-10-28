from __future__ import annotations

import json
import math
import re
import secrets
import socket
import ssl
import sys
import threading
import time
import urllib.parse
from collections import deque
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from . import lan
from .bridge import BridgeService
from .bundles import MAX_ARTIFACT_BYTES
from .hermes import HermesError
from .security import ValidationError, canonical_json
from .state import AuthorizationError, ConflictError, PairingError, StateError
from .video_worker import VideoWorkerError


API_PREFIX = "/padnote/v1"
MAX_JSON_BODY = 12 * 1024 * 1024
MAX_ADMIN_BODY = 64 * 1024
DEFAULT_INPUT_DEADLINE_SECONDS = 20.0
DEFAULT_RESPONSE_DEADLINE_SECONDS = 20.0
DEFAULT_SOCKET_TIMEOUT_SECONDS = 15.0
# A maximum-sized 12 MiB API request therefore needs about 615 KiB/s from
# accept through the complete body. This is a deliberate bounded-input limit.
DEFAULT_FILE_RESPONSE_MAX_SECONDS = 10 * 60.0
# Artifact responses use a fixed overhead plus this conservative throughput,
# while the socket timeout still limits any single stalled write.
DEFAULT_FILE_MIN_BYTES_PER_SECOND = 192 * 1024.0


class _BodyTooLarge(ValidationError):
    pass


class _ConnectionDeadlines:
    def __init__(self, connection, input_seconds: float,
                 response_seconds: float, socket_timeout: float) -> None:
        self.connection = connection
        self.input_seconds = input_seconds
        self.response_seconds = response_seconds
        self.socket_timeout = socket_timeout
        self._lock = threading.Lock()
        self._input_ends_at = time.monotonic() + input_seconds
        self._input_active = True
        self._input_expired = False
        self._response_active = False
        self._input_timer = threading.Timer(input_seconds, self._expire)
        self._input_timer.daemon = True
        self._response_timer: threading.Timer | None = None
        connection.settimeout(min(socket_timeout, input_seconds))

    def start(self) -> None:
        self._input_timer.start()

    def finish_input(self) -> None:
        with self._lock:
            expired = self._input_expired or time.monotonic() >= self._input_ends_at
            if expired:
                self._input_expired = True
            self._input_active = False
            self._input_timer.cancel()
        if expired:
            self._close_connection()
            raise TimeoutError("request input deadline expired")

    def start_response(self, response_seconds: float | None = None) -> None:
        response_seconds = self.response_seconds if response_seconds is None else response_seconds
        with self._lock:
            self._input_active = False
            self._input_timer.cancel()
            if self._response_timer is not None:
                return
            self.connection.settimeout(min(self.socket_timeout, response_seconds))
            self._response_timer = threading.Timer(response_seconds, self._expire)
            self._response_timer.daemon = True
            self._response_active = True
            self._response_timer.start()

    def close(self) -> None:
        with self._lock:
            self._input_active = False
            self._response_active = False
            self._input_timer.cancel()
            if self._response_timer is not None:
                self._response_timer.cancel()

    def _expire(self) -> None:
        current = threading.current_thread()
        with self._lock:
            if current is self._input_timer:
                should_close = self._input_active
                if should_close:
                    self._input_expired = True
                self._input_active = False
            else:
                should_close = self._response_active
                self._response_active = False
        if should_close:
            self._close_connection()

    def _close_connection(self) -> None:
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.connection.close()
        except OSError:
            pass


class _Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 32

    def __init__(
        self,
        *args,
        max_workers: int = 32,
        input_deadline_seconds: float = DEFAULT_INPUT_DEADLINE_SECONDS,
        response_deadline_seconds: float = DEFAULT_RESPONSE_DEADLINE_SECONDS,
        socket_timeout_seconds: float = DEFAULT_SOCKET_TIMEOUT_SECONDS,
        file_response_max_seconds: float = DEFAULT_FILE_RESPONSE_MAX_SECONDS,
        file_min_bytes_per_second: float = DEFAULT_FILE_MIN_BYTES_PER_SECOND,
        ssl_context: ssl.SSLContext | None = None,
        **kwargs,
    ):
        self._ssl_context = ssl_context
        if (isinstance(max_workers, bool) or not isinstance(max_workers, int)
                or max_workers <= 0):
            raise ValueError("max_workers must be a positive integer")
        for name, value in {
            "input_deadline_seconds": input_deadline_seconds,
            "response_deadline_seconds": response_deadline_seconds,
            "socket_timeout_seconds": socket_timeout_seconds,
            "file_response_max_seconds": file_response_max_seconds,
            "file_min_bytes_per_second": file_min_bytes_per_second,
        }.items():
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ValueError(f"{name} must be positive")
            try:
                valid = math.isfinite(value) and value > 0
            except (OverflowError, TypeError, ValueError):
                valid = False
            if not valid:
                raise ValueError(f"{name} must be finite and positive")
        self._workers = threading.BoundedSemaphore(max_workers)
        self._input_deadline_seconds = float(input_deadline_seconds)
        self._response_deadline_seconds = float(response_deadline_seconds)
        self._socket_timeout_seconds = float(socket_timeout_seconds)
        self._file_response_max_seconds = float(file_response_max_seconds)
        self._file_min_bytes_per_second = float(file_min_bytes_per_second)
        self._deadline_lock = threading.Lock()
        self._deadlines: dict[Any, _ConnectionDeadlines] = {}
        super().__init__(*args, **kwargs)

    def get_request(self):
        request, address = super().get_request()
        if self._ssl_context is not None:
            # Defer the handshake to the handler thread so one slow or hostile
            # client cannot stall the accept loop; the connection deadlines
            # below bound the handshake like any other read.
            try:
                request = self._ssl_context.wrap_socket(
                    request, server_side=True, do_handshake_on_connect=False)
            except (OSError, ValueError):
                request.close()
                raise
        deadlines: _ConnectionDeadlines | None = None
        try:
            deadlines = _ConnectionDeadlines(
                request, self._input_deadline_seconds,
                self._response_deadline_seconds, self._socket_timeout_seconds)
            deadlines.start()
            with self._deadline_lock:
                self._deadlines[request] = deadlines
            return request, address
        except (OSError, RuntimeError, ValueError, OverflowError):
            if deadlines is not None:
                deadlines.close()
            self.shutdown_request(request)
            raise

    def finish_input(self, request) -> None:
        with self._deadline_lock:
            deadlines = self._deadlines.get(request)
        if deadlines is not None:
            deadlines.finish_input()

    def start_response(self, request, response_seconds: float | None = None) -> None:
        with self._deadline_lock:
            deadlines = self._deadlines.get(request)
        if deadlines is not None:
            deadlines.start_response(response_seconds)

    def file_response_deadline(self, size: int) -> float:
        if (isinstance(size, bool) or not isinstance(size, int)
                or not 0 <= size <= MAX_ARTIFACT_BYTES):
            raise ValidationError("artifact size is invalid")
        estimated = self._response_deadline_seconds + size / self._file_min_bytes_per_second
        return min(self._file_response_max_seconds, estimated)

    def _finish_connection(self, request) -> None:
        with self._deadline_lock:
            deadlines = self._deadlines.pop(request, None)
        if deadlines is not None:
            deadlines.close()

    def process_request(self, request, client_address):
        if not self._workers.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
            finally:
                self.shutdown_request(request)
                self._finish_connection(request)
            return
        try:
            super().process_request(request, client_address)
        except RuntimeError:
            self._workers.release()
            self._finish_connection(request)
            self.shutdown_request(request)
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._finish_connection(request)
            self._workers.release()

    def handle_error(self, request, client_address) -> None:
        # Socket deadline expiry and peer disconnects are expected and never
        # log request bodies, credentials, or tracebacks.
        error = sys.exc_info()[1]
        if isinstance(error, (OSError, TimeoutError)):
            return
        super().handle_error(request, client_address)


class _RateLimiter:
    def __init__(self, limit: int, window_seconds: float):
        self.limit = limit
        self.window_seconds = window_seconds
        self._events: deque[float] = deque()
        self._lock = threading.Lock()

    def allow(self) -> bool:
        now = time.monotonic()
        with self._lock:
            while self._events and self._events[0] <= now - self.window_seconds:
                self._events.popleft()
            if len(self._events) >= self.limit:
                return False
            self._events.append(now)
            return True


def _error(code: str, message: str) -> dict[str, Any]:
    return {"error": {"code": code, "message": message}}


def _qr_svg(text: str) -> str:
    try:
        from vendor.qrcodegen import QrCode
    except ImportError:
        return ""
    qr = QrCode.encode_text(text, QrCode.Ecc.MEDIUM)
    size = qr.get_size()
    border = 4
    commands: list[str] = []
    for y in range(size):
        for x in range(size):
            if qr.get_module(x, y):
                commands.append(f"M{x + border},{y + border}h1v1h-1z")
    dimension = size + border * 2
    path = "".join(commands)
    return (
        f'<svg class="qr" viewBox="0 0 {dimension} {dimension}" role="img" '
        f'aria-label="配对二维码" shape-rendering="crispEdges">'
        f'<rect width="100%" height="100%" fill="#fff"/><path d="{path}" fill="#111827"/></svg>'
    )


class _DeadlineRequestHandler(BaseHTTPRequestHandler):
    def handle(self) -> None:
        try:
            if isinstance(self.connection, ssl.SSLSocket):
                # Bounded by the connection's input deadline; a failed or
                # plaintext handshake just drops this one connection.
                self.connection.do_handshake()
            super().handle()
        except (OSError, TimeoutError):
            return
        except ValueError:
            if self.connection.fileno() < 0 or self.wfile.closed:
                return
            raise

    def _finish_input(self) -> None:
        self.server.finish_input(self.connection)  # type: ignore[attr-defined]

    def _prepare_response(self, response_seconds: float | None = None) -> None:
        self.close_connection = True
        self.server.start_response(self.connection, response_seconds)  # type: ignore[attr-defined]

    def _read_request_body(self, maximum: int) -> bytes:
        transfer_values = self.headers.get_all("Transfer-Encoding") or []
        length_values = self.headers.get_all("Content-Length") or []
        if transfer_values:
            raise ValidationError("Transfer-Encoding request bodies are not accepted")
        if len(length_values) != 1:
            raise ValidationError("exactly one Content-Length header is required")
        raw_length = length_values[0].strip()
        if len(raw_length) > 20 or not raw_length.isascii() or not raw_length.isdecimal():
            raise ValidationError("invalid Content-Length")
        length = int(raw_length)
        if length > maximum:
            raise _BodyTooLarge("request body is too large")
        try:
            body = self.rfile.read(length)
        except (OSError, TimeoutError, ValueError) as error:
            raise ValidationError("request body was not received in time") from error
        if len(body) != length:
            raise ValidationError("request body was incomplete")
        self._finish_input()
        return body


class AdminHandler(_DeadlineRequestHandler):
    server_version = "PadNoteAssistantAdmin/0.1"

    @property
    def service(self) -> BridgeService:
        return self.server.service  # type: ignore[attr-defined]

    def log_message(self, format: str, *args: Any) -> None:
        # Do not log query strings, bodies, CSRF values, keys, or pair codes.
        return

    def _host_ok(self) -> bool:
        return self.headers.get("Host", "") in self.server.allowed_hosts  # type: ignore[attr-defined]

    def _security_headers(self) -> None:
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "same-origin")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")

    def _deny(self, status: int = 403) -> None:
        body = b"Forbidden\n"
        self._prepare_response()
        self.send_response(status)
        self._security_headers()
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        self._finish_input()
        if not self._host_ok():
            self._deny()
            return
        parsed = urllib.parse.urlsplit(self.path)
        origin = self.headers.get("Origin")
        if origin is not None and origin != f"http://{self.headers.get('Host', '')}":
            self._deny()
            return
        if parsed.path in {"/assets/admin.css", "/assets/admin.js", "/assets/brand.png"}:
            if self.path != parsed.path:
                self._deny(404)
                return
            asset_path = Path(__file__).with_name("static") / parsed.path.rsplit("/", 1)[1]
            try:
                body = asset_path.read_bytes()
            except OSError:
                self._deny(404)
                return
            mime = {".css": "text/css; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".png": "image/png"}[asset_path.suffix]
            self._prepare_response()
            self.send_response(200)
            self._security_headers()
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if parsed.path == "/admin/live":
            expected = self.server.csrf_token  # type: ignore[attr-defined]
            supplied = self.headers.get("X-PadNote-CSRF", "")
            if not secrets.compare_digest(supplied, expected):
                self._deny()
                return
            from . import admin_ui
            body = json.dumps(admin_ui.render_live_state(self.service, expected), ensure_ascii=False,
                              separators=(",", ":")).encode("utf-8")
            self._prepare_response()
            self.send_response(200)
            self._security_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if parsed.path == "/admin/onboarding/status":
            expected = self.server.csrf_token  # type: ignore[attr-defined]
            supplied = self.headers.get("X-PadNote-CSRF", "")
            if not secrets.compare_digest(supplied, expected):
                self._deny()
                return
            from . import admin_ui
            body = json.dumps(admin_ui.hermes_onboarding_status(self.service),
                              ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self._prepare_response()
            self.send_response(200)
            self._security_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if parsed.path != "/":
            self._deny(404)
            return
        message = urllib.parse.parse_qs(parsed.query).get("message", [""])[0][:300]
        body = self._render(message).encode("utf-8")
        self._prepare_response()
        self.send_response(200)
        self._security_headers()
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:
        if not self._host_ok():
            self._deny()
            return
        host = self.headers.get("Host", "")
        if self.headers.get("Origin", "") != f"http://{host}":
            self._deny()
            return
        if not self.headers.get("Content-Type", "").lower().startswith("application/x-www-form-urlencoded"):
            self._deny(415)
            return
        try:
            raw_body = self._read_request_body(MAX_ADMIN_BODY)
            form = urllib.parse.parse_qs(
                raw_body.decode("utf-8", "strict"), keep_blank_values=True)
        except _BodyTooLarge:
            self._deny(413)
            return
        except (UnicodeDecodeError, ValidationError):
            self._deny(400)
            return
        if not secrets.compare_digest(form.get("csrf", [""])[0], self.server.csrf_token):  # type: ignore[attr-defined]
            self._deny()
            return
        path = urllib.parse.urlsplit(self.path).path
        try:
            message = self._dispatch_post(path, {key: values[0] for key, values in form.items()})
        except Exception as error:
            message = _admin_error_message(error)
        location = "/?" + urllib.parse.urlencode({"message": message})
        self._prepare_response()
        self.send_response(303)
        self._security_headers()
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _dispatch_post(self, path: str, form: dict[str, str]) -> str:
        if path == "/admin/hermes/setup":
            try:
                from . import hermes_onboarding
                result = hermes_onboarding.open_hermes_setup(self.service)
            except Exception:
                return "Hermes 配置向导未能打开。请查看官方安装与配置指南。"
            status = result.get("status") if isinstance(result, dict) else "error"
            if status in {"opened", "starting", "ready"}:
                detail = result.get("message") if isinstance(result, dict) else None
                if isinstance(detail, str) and detail.strip():
                    return detail[:300]
                return "已打开 Hermes 配置向导。请完成配置后回到此页运行连接检查。"
            return "Hermes 配置向导未能打开。请查看官方安装与配置指南。"
        if path == "/admin/hermes/connect":
            try:
                from . import hermes_onboarding
                result = hermes_onboarding.connect_hermes(self.service)
            except Exception:
                return "Hermes 连接未完成。请检查安装与配置后重试。"
            status = result.get("status") if isinstance(result, dict) else "error"
            messages = {
                "ready": "Hermes 已连接，可以继续生成配对二维码。",
                "needs_setup": "Hermes 本机 API Server 需要配置。请分别完成 API 设置和模型供应商登录后重试。",
                "not_installed": "未找到 Hermes。请先查看官方安装指南。",
                "ambiguous": "发现多个 Hermes 配置，请先选择要使用的实例。",
                "starting": "Hermes 正在启动，请稍候再检查。",
                "error": "Hermes 连接未完成。请检查安装与配置后重试。",
            }
            if status != "ready" and isinstance(result, dict):
                detail = result.get("message")
                if isinstance(detail, str) and detail.strip():
                    return detail[:300]
            return messages.get(status, messages["error"])
        if path == "/admin/discovery/refresh":
            self.service.refresh_discovery()
            return "已刷新本机候选项；只检查已知路径和命令元信息。"
        if path == "/admin/discovery/import":
            instance = self.service.import_discovered_profile(form.get("profile_path", ""))
            return f"已使用 {instance['name']} 的本机 API 配置；key 不会回显，请继续检测。"
        if path == "/admin/instances":
            kind = form.get("kind", "")
            region = form.get("provider_region", "international")
            endpoint = {
                "china": "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "international": "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            }.get(region) if kind == "builtin_video" else form.get("base_url", "")
            instance = self.service.add_instance(
                kind, form.get("name", ""), endpoint or "", form.get("api_key") or None,
                provider_model=form.get("provider_model") or None,
                provider_region=region if kind == "builtin_video" else None,
                response_mode=form.get("response_mode") or None,
                tts_model=form.get("tts_model") or None,
                tts_voice=form.get("tts_voice") or None)
            return f"已添加 {instance['name']}，请运行连接检测。"
        match = re.fullmatch(r"/admin/instances/([A-Za-z0-9_.:-]+)/key", path)
        if match:
            instance = self.service.configure_key(match.group(1), form.get("api_key", ""))
            if instance["instance_id"] != match.group(1):
                return "凭据身份已变化，已建立新实例；请检测后重新配对。旧设备授权不会继承。"
            if instance["kind"] == "builtin_video":
                return "Provider key 已保存到本机安全存储；管理页不会回显。"
            return "Hermes 凭据已加载到本次助手进程，请检测连接。重启后可重新填写或从已发现的本机配置加载。"
        match = re.fullmatch(r"/admin/instances/([A-Za-z0-9_.:-]+)/credential/revoke", path)
        if match:
            self.service.revoke_builtin_credential(match.group(1))
            return "内置视频凭据已撤销，关联设备也已失效。"
        match = re.fullmatch(r"/admin/instances/([A-Za-z0-9_.:-]+)/check", path)
        if match:
            instance = self.service.check_instance(match.group(1))
            return f"检测结果：{instance['health']} — {instance['detail']}"
        if path == "/admin/lan-pair-codes":
            self.service.create_lan_pair_payload(form.get("instance_id", ""), form.get("address", ""))
            return "已生成 5 分钟有效的同网配对内容（含本机证书指纹）。"
        if path == "/admin/pair-codes":
            self.service.create_pair_payload(form.get("instance_id", ""), form.get("public_url", ""))
            return "已生成 5 分钟有效的单次配对内容。"
        match = re.fullmatch(r"/admin/pair-requests/([A-Za-z0-9_.:-]+)/(approve|deny)", path)
        if match:
            self.service.approve_pair(match.group(1), match.group(2) == "approve")
            return "配对申请已处理。"
        match = re.fullmatch(r"/admin/connections/([A-Za-z0-9_.:-]+)/revoke", path)
        if match:
            self.service.revoke_connection(match.group(1))
            return "设备连接已撤销，原 token 立即失效。"
        raise ValidationError("unknown management action")

    def _render(self, message: str) -> str:
        from . import admin_ui
        return admin_ui.render_admin_page(
            self.service, self.server.csrf_token, self.server.server_address[1],
            self.server.api_port, message, _qr_svg)  # type: ignore[attr-defined]


class ApiHandler(_DeadlineRequestHandler):
    server_version = "PadNoteAssistantApi/0.1"

    @property
    def service(self) -> BridgeService:
        return self.server.service  # type: ignore[attr-defined]

    def log_message(self, format: str, *args: Any) -> None:
        return

    def _headers(self, content_type: str = "application/json; charset=utf-8") -> None:
        self.send_header("Content-Type", content_type)
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Connection", "close")

    def _json(self, status: int, value: Any) -> None:
        body = canonical_json(value)
        self._prepare_response()
        self.send_response(status)
        self._headers()
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> dict[str, Any]:
        if self.headers.get("Origin"):
            raise AuthorizationError("Browser cross-origin requests are not accepted")
        if not self.headers.get("Content-Type", "").lower().startswith("application/json"):
            raise ValidationError("Content-Type must be application/json")
        try:
            raw_body = self._read_request_body(MAX_JSON_BODY)
            value = json.loads(raw_body.decode("utf-8"))
        except _BodyTooLarge as error:
            raise ValidationError("request exceeds 12 MiB") from error
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValidationError("request body must be UTF-8 JSON") from error
        if not isinstance(value, dict):
            raise ValidationError("request body must be a JSON object")
        return value

    def _bearer(self) -> str:
        value = self.headers.get("Authorization", "")
        if not value.startswith("Bearer ") or not value[7:].strip():
            raise AuthorizationError("Missing device credential")
        return value[7:].strip()

    def do_OPTIONS(self) -> None:
        self._finish_input()
        self._json(403, _error("browser_forbidden", "Browser cross-origin requests are not accepted"))

    def do_GET(self) -> None:
        self._dispatch("GET")

    def do_POST(self) -> None:
        self._dispatch("POST")

    def _dispatch(self, method: str) -> None:
        try:
            if method == "GET":
                self._finish_input()
            if self.headers.get("Origin"):
                raise AuthorizationError("Browser cross-origin requests are not accepted")
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.query or parsed.fragment:
                raise ValidationError("query and fragment are not accepted")
            path = parsed.path
            match = re.fullmatch(re.escape(API_PREFIX) + r"/agents/([A-Za-z0-9_.:-]+)/video/diagnostics", path)
            if method == "GET" and match:
                self._json(200, self.service.get_video_diagnostics(match.group(1), self._bearer())); return
            if method == "POST" and path == API_PREFIX + "/pair/request":
                if not self.server.pair_limiter.allow():  # type: ignore[attr-defined]
                    self._json(429, _error("rate_limited", "Pairing is temporarily rate limited")); return
                status, value = self.service.request_pair(self._body())
                self._json(status, value); return
            if method == "POST" and path == API_PREFIX + "/pair/claim":
                status, value = self.service.claim_pair(self._body())
                self._json(status, value); return
            match = re.fullmatch(re.escape(API_PREFIX) + r"/agents/([A-Za-z0-9_.:-]+)/capabilities", path)
            if method == "GET" and match:
                probe_agent = self.headers.get("X-PadNote-Agent-Probe") == "1"
                if probe_agent:
                    value = self.service.capabilities(match.group(1), self._bearer(), probe_agent=True)
                else:
                    value = self.service.capabilities(match.group(1), self._bearer())
                self._json(200, value); return
            match = re.fullmatch(re.escape(API_PREFIX) + r"/agents/([A-Za-z0-9_.:-]+)/runs", path)
            if method == "POST" and match:
                status, value = self.service.submit_run(match.group(1), self._bearer(),
                                                        self.headers.get("Idempotency-Key", ""), self._body())
                self._json(status, value); return
            match = re.fullmatch(
                re.escape(API_PREFIX)
                + r"/agents/([A-Za-z0-9_.:-]+)/runs/([A-Za-z0-9_.:-]+)/video/operations", path)
            if method == "POST" and match:
                status, value = self.service.submit_video_operation(
                    match.group(1), self._bearer(), match.group(2),
                    self.headers.get("Idempotency-Key", ""), self._body())
                self._json(status, value); return
            match = re.fullmatch(
                re.escape(API_PREFIX)
                + r"/agents/([A-Za-z0-9_.:-]+)/runs/([A-Za-z0-9_.:-]+)/video/operations/"
                + r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})", path)
            cancel_running_match = re.fullmatch(
                re.escape(API_PREFIX)
                + r"/agents/([A-Za-z0-9_.:-]+)/runs/([A-Za-z0-9_.:-]+)/video/operations/"
                + r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/cancel-running", path)
            if method == "POST" and cancel_running_match:
                if self._body() != {}:
                    raise ValidationError("video running cancellation body must be an empty object")
                self._json(200, self.service.cancel_running_video_operation(
                    cancel_running_match.group(1), self._bearer(), cancel_running_match.group(2),
                    cancel_running_match.group(3))); return
            if method == "GET" and cancel_running_match:
                self._json(200, self.service.get_video_cancel_request(
                    cancel_running_match.group(1), self._bearer(), cancel_running_match.group(2),
                    cancel_running_match.group(3))); return
            if method == "POST" and path.endswith("/reconcile"):
                reconcile_match = re.fullmatch(
                    re.escape(API_PREFIX)
                    + r"/agents/([A-Za-z0-9_.:-]+)/runs/([A-Za-z0-9_.:-]+)/video/operations/"
                    + r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/reconcile", path)
                if reconcile_match:
                    if self._body() != {}:
                        raise ValidationError("video reconciliation body must be an empty object")
                    self._json(200, self.service.reconcile_video_operation(
                        reconcile_match.group(1), self._bearer(),
                        reconcile_match.group(2), reconcile_match.group(3))); return
            if method == "GET" and match:
                self._json(200, self.service.get_video_operation(
                    match.group(1), self._bearer(), match.group(2), match.group(3))); return
            if method == "POST" and path.endswith("/cancel"):
                match = re.fullmatch(
                    re.escape(API_PREFIX)
                    + r"/agents/([A-Za-z0-9_.:-]+)/runs/([A-Za-z0-9_.:-]+)/video/operations/"
                    + r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/cancel", path)
                if match:
                    if self._body():
                        raise ValidationError("video cancellation body must be empty JSON")
                    self._json(200, self.service.cancel_video_operation(
                        match.group(1), self._bearer(), match.group(2), match.group(3))); return
            match = re.fullmatch(re.escape(API_PREFIX) + r"/agents/([A-Za-z0-9_.:-]+)/runs/([A-Za-z0-9_.:-]+)", path)
            if method == "GET" and match:
                self._json(200, self.service.get_run(match.group(1), self._bearer(), match.group(2))); return
            match = re.fullmatch(re.escape(API_PREFIX) + r"/agents/([A-Za-z0-9_.:-]+)/runs/([A-Za-z0-9_.:-]+)/video/review", path)
            if method == "GET" and match:
                value = self.service.get_video_review(match.group(1), self._bearer(), match.group(2))
                self._json(200, value); return
            match = re.fullmatch(re.escape(API_PREFIX) + r"/agents/([A-Za-z0-9_.:-]+)/runs/([A-Za-z0-9_.:-]+)/video/previews/([a-f0-9]{64})", path)
            if method == "GET" and match:
                stream, item = self.service.get_video_preview(
                    match.group(1), self._bearer(), match.group(2), match.group(3))
                if item.get("media_type") != "image/png":
                    stream.close()
                    raise ValidationError("Video preview is unavailable")
                self._file(stream, item); return
            match = re.fullmatch(re.escape(API_PREFIX) + r"/agents/([A-Za-z0-9_.:-]+)/runs/([A-Za-z0-9_.:-]+)/stop", path)
            if method == "POST" and match:
                if self._body():
                    raise ValidationError("stop request body must be empty JSON")
                status, value = self.service.stop_run(match.group(1), self._bearer(), match.group(2))
                self._json(status, value); return
            match = re.fullmatch(re.escape(API_PREFIX) + r"/agents/([A-Za-z0-9_.:-]+)/runs/([A-Za-z0-9_.:-]+)/approval", path)
            if method == "POST" and match:
                status, value = self.service.approve_run(match.group(1), self._bearer(), match.group(2), self._body())
                self._json(status, value); return
            match = re.fullmatch(re.escape(API_PREFIX) + r"/agents/([A-Za-z0-9_.:-]+)/runs/([A-Za-z0-9_.:-]+)/artifacts/([A-Za-z0-9_.:-]+)", path)
            if method == "GET" and match:
                stream, item = self.service.get_artifact(match.group(1), self._bearer(), match.group(2), match.group(3))
                self._file(stream, item); return
            self._json(404, _error("not_found", "Route not found"))
        except PairingError as error:
            self._json(error.status, _error(error.code, str(error)))
        except AuthorizationError as error:
            self._json(401, _error("unauthorized", str(error)))
        except ConflictError as error:
            self._json(409, _error("conflict", str(error)))
        except HermesError as error:
            self._json(error.status, _error(error.code, str(error)))
        except VideoWorkerError as error:
            if error.code in {"diagnostic_timeout", "diagnostic_invalid", "diagnostic_unavailable"}:
                messages = {
                    "diagnostic_timeout": "Video diagnostics timed out",
                    "diagnostic_invalid": "Video diagnostics could not be verified",
                    "diagnostic_unavailable": "Video diagnostics are temporarily unavailable",
                }
                self._json(503, _error(error.code, messages[error.code]))
            elif error.code in {"worker_unavailable", "node_unavailable", "invalid_configuration",
                                "unsupported_platform"}:
                self._json(503, _error("video_worker_unavailable", "Video review is temporarily unavailable"))
            else:
                self._json(409, _error("video_review_conflict", "Video review is not available for this task"))
        except ValidationError as error:
            self._json(400, _error("invalid_request", str(error)))
        except KeyError:
            self._json(404, _error("not_found", "Resource not found"))
        except StateError as error:
            self._json(409, _error("state_conflict", str(error)))
        except FileNotFoundError:
            self._json(404, _error("not_found", "Artifact not found"))
        except Exception:
            self._json(500, _error("internal_error", "Connection assistant failed safely"))

    def _file(self, stream, item: dict[str, Any]) -> None:
        with stream as source:
            size = item["size_bytes"]
            response_seconds = self.server.file_response_deadline(size)  # type: ignore[attr-defined]
            self._prepare_response(response_seconds)
            self.send_response(200)
            self._headers(item["media_type"])
            self.send_header("Content-Length", str(size))
            suffix = Path(item["name"]).suffix.lower()
            safe_suffix = suffix if re.fullmatch(r"\.[a-z0-9]{1,10}", suffix) else ""
            encoded_name = urllib.parse.quote(item["name"], safe="")
            self.send_header(
                "Content-Disposition",
                f"attachment; filename=\"padnote-artifact{safe_suffix}\"; filename*=UTF-8''{encoded_name}",
            )
            self.send_header("X-PadNote-SHA256", item["sha256"])
            self.end_headers()
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)


def _admin_error_message(error: Exception) -> str:
    if isinstance(error, (ValidationError, StateError, HermesError, KeyError)):
        return f"操作未完成：{str(error)}"
    return "操作未完成，请检查本机配置。"


class ServerGroup:
    def __init__(
        self,
        service: BridgeService,
        *,
        admin_port: int = 8766,
        api_port: int = 8765,
        max_workers: int = 32,
        input_deadline_seconds: float = DEFAULT_INPUT_DEADLINE_SECONDS,
        response_deadline_seconds: float = DEFAULT_RESPONSE_DEADLINE_SECONDS,
        socket_timeout_seconds: float = DEFAULT_SOCKET_TIMEOUT_SECONDS,
        file_response_max_seconds: float = DEFAULT_FILE_RESPONSE_MAX_SECONDS,
        file_min_bytes_per_second: float = DEFAULT_FILE_MIN_BYTES_PER_SECOND,
        lan_port: int | None = None,
        lan_ssl_context: ssl.SSLContext | None = None,
        lan_host: str = "0.0.0.0",
    ):
        self.service = service
        self.lan: _Server | None = None
        self._threads: list[threading.Thread] = []
        self._started_servers: list[_Server] = []
        self._closed = False
        self.admin: _Server | None = None
        self.api: _Server | None = None
        server_options = {
            "max_workers": max_workers,
            "input_deadline_seconds": input_deadline_seconds,
            "response_deadline_seconds": response_deadline_seconds,
            "socket_timeout_seconds": socket_timeout_seconds,
            "file_response_max_seconds": file_response_max_seconds,
            "file_min_bytes_per_second": file_min_bytes_per_second,
        }
        try:
            self.admin = _Server(("127.0.0.1", admin_port), AdminHandler, **server_options)
            self.api = _Server(("127.0.0.1", api_port), ApiHandler, **server_options)
            if lan_port is not None:
                # Same-network tablets: the device API only, always over TLS. The
                # management UI never leaves loopback.
                if lan_ssl_context is None:
                    raise ValueError("the local network listener requires TLS")
                self.lan = _Server((lan_host, lan_port), ApiHandler,
                                   ssl_context=lan_ssl_context, **server_options)
        except BaseException:
            for server in (self.admin, self.api, self.lan):
                if server is not None:
                    server.server_close()
            raise
        self.admin.service = service  # type: ignore[attr-defined]
        self.api.service = service  # type: ignore[attr-defined]
        actual_admin_port = self.admin.server_address[1]
        self.admin.allowed_hosts = {f"127.0.0.1:{actual_admin_port}", f"localhost:{actual_admin_port}"}  # type: ignore[attr-defined]
        self.admin.csrf_token = secrets.token_urlsafe(32)  # type: ignore[attr-defined]
        self.admin.api_port = self.api.server_address[1]  # type: ignore[attr-defined]
        self.api.pair_limiter = _RateLimiter(20, 60)  # type: ignore[attr-defined]
        if self.lan is not None:
            self.lan.service = service  # type: ignore[attr-defined]
            self.lan.pair_limiter = self.api.pair_limiter  # type: ignore[attr-defined]

    @property
    def admin_url(self) -> str:
        return f"http://127.0.0.1:{self.admin.server_address[1]}/"

    @property
    def api_url(self) -> str:
        return f"http://127.0.0.1:{self.api.server_address[1]}"

    def start(self) -> None:
        if self._closed:
            raise RuntimeError("server group is closed")
        if self._threads:
            return
        servers = [(self.admin, "padnote-admin"), (self.api, "padnote-api")]
        if self.lan is not None:
            servers.append((self.lan, "padnote-lan"))
        for server, name in servers:
            thread = threading.Thread(target=server.serve_forever, name=name, daemon=True)
            thread.start()
            self._threads.append(thread)
            self._started_servers.append(server)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            for server in self._started_servers:
                server.shutdown()
            for server in (self.admin, self.api, self.lan):
                if server is not None:
                    server.server_close()
            for thread in self._threads:
                thread.join(timeout=5)
        finally:
            self.service.close()

    def serve_forever(self) -> None:
        self.start()
        try:
            for thread in self._threads:
                thread.join()
        except KeyboardInterrupt:
            pass
        finally:
            self.close()
