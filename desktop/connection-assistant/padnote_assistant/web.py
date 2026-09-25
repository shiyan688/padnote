from __future__ import annotations

import html
import json
import math
import re
import secrets
import socket
import sys
import threading
import time
import urllib.parse
from collections import deque
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from .bridge import BridgeService
from .bundles import MAX_ARTIFACT_BYTES
from .hermes import HermesError
from .security import ValidationError, canonical_json
from .state import AuthorizationError, ConflictError, PairingError, StateError


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
        **kwargs,
    ):
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
        self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
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
        if path == "/admin/discovery/refresh":
            self.service.refresh_discovery()
            return "已刷新本机候选项；只检查已知路径和命令元信息。"
        if path == "/admin/discovery/import":
            instance = self.service.import_discovered_profile(form.get("profile_path", ""))
            return f"已使用 {instance['name']} 的本机 API 配置；key 不会回显，请继续检测。"
        if path == "/admin/instances":
            instance = self.service.add_instance(form.get("kind", ""), form.get("name", ""),
                                                 form.get("base_url", ""), form.get("api_key") or None)
            return f"已添加 {instance['name']}，请运行连接检测。"
        match = re.fullmatch(r"/admin/instances/([A-Za-z0-9_.:-]+)/key", path)
        if match:
            instance = self.service.configure_key(match.group(1), form.get("api_key", ""))
            if instance["instance_id"] != match.group(1):
                return "Hermes key 身份已变化，已建立新实例；请检测后重新配对。旧设备授权不会继承。"
            return "Hermes key 已加载到当前进程；管理页不会回显。"
        match = re.fullmatch(r"/admin/instances/([A-Za-z0-9_.:-]+)/check", path)
        if match:
            instance = self.service.check_instance(match.group(1))
            return f"检测结果：{instance['health']} — {instance['detail']}"
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
        csrf = html.escape(self.server.csrf_token, quote=True)  # type: ignore[attr-defined]
        admin_port = self.server.server_address[1]
        api_port = self.server.api_port  # type: ignore[attr-defined]
        instances = sorted(self.service.store.list_instances(), key=lambda item: item["created_at"])
        instance_by_id = {item["instance_id"]: item for item in instances}
        candidates = self.service.discovery_results
        pending = self.service.store.list_pending_pairs()
        connections = self.service.store.list_connections()
        forms = []
        for instance in instances:
            iid = html.escape(instance["instance_id"], quote=True)
            forms.append(f"""
            <article><h3>{html.escape(instance['name'])} <small>{html.escape(instance['kind'])}</small></h3>
            <p><code>{html.escape(instance['base_url'])}</code></p>
            <p class="status">{html.escape(instance['health'])}：{html.escape(instance['detail'])}</p>
            <form method="post" action="/admin/instances/{iid}/key">
              <input type="hidden" name="csrf" value="{csrf}">
              <label>Hermes key（只保留在当前进程）<input type="password" name="api_key" autocomplete="off" required></label>
              <button>加载 key</button>
            </form>
            <form method="post" action="/admin/instances/{iid}/check">
              <input type="hidden" name="csrf" value="{csrf}"><button>检测运行与接口</button>
            </form></article>""")
        def candidate_row(item):
            action = ""
            if item.get("kind") == "hermes" and Path(item.get("path", "")).name == ".env":
                action = (
                    '<form class="inline" method="post" action="/admin/discovery/import">'
                    f'<input type="hidden" name="csrf" value="{csrf}">'
                    f'<input type="hidden" name="profile_path" value="{html.escape(item["path"], quote=True)}">'
                    '<button>使用这套配置</button></form>'
                )
            return f"<li><strong>{html.escape(item['kind'])}</strong> — <code>{html.escape(item['path'])}</code><br>{html.escape(item['guidance'])} {action}</li>"
        candidate_html = "".join(candidate_row(item) for item in candidates) \
            or "<li>未在已知位置发现候选安装。这不代表未安装，可以手动添加本机 URL。</li>"
        pending_html = "".join(
            f"<li>{html.escape(item['device_name'])} <code>{html.escape(item['device_id'])}</code> → "
            f"{html.escape(instance_by_id.get(item['instance_id'], {}).get('name', '未知实例'))} "
            f'<form class="inline" method="post" action="/admin/pair-requests/{html.escape(item["request_id"], quote=True)}/approve"><input type="hidden" name="csrf" value="{csrf}"><button>批准</button></form> '
            f'<form class="inline" method="post" action="/admin/pair-requests/{html.escape(item["request_id"], quote=True)}/deny"><input type="hidden" name="csrf" value="{csrf}"><button class="danger">拒绝</button></form></li>'
            for item in pending) or "<li>暂无待批准设备。</li>"
        connection_html = "".join(
            f"<li>{html.escape(item['device_name'])} → {html.escape(instance_by_id.get(item['instance_id'], {}).get('name', '未知实例'))} "
            + ('<span class="muted">已撤销</span>' if item.get("revoked_at") else
               f'<form class="inline" method="post" action="/admin/connections/{html.escape(item["connection_id"], quote=True)}/revoke"><input type="hidden" name="csrf" value="{csrf}"><button class="danger">撤销</button></form>')
            + "</li>" for item in connections) or "<li>尚无已授权设备。</li>"
        options = "".join(
            f'<option value="{html.escape(item["instance_id"], quote=True)}" {"disabled" if not item.get("executable") else ""}>{html.escape(item["name"])} — {html.escape(item["health"])}</option>'
            for item in instances)
        pair_block = ""
        payload = self.service.last_pair_payload
        if payload and self.service.last_pair_expires_at > int(self.service.clock()):
            payload_text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
            pair_block = f'<section><h2>配对内容</h2>{_qr_svg(payload_text)}<textarea readonly rows="5">{html.escape(payload_text)}</textarea><p>二维码与文本是同一份单次内容，5 分钟后过期。</p></section>'
        message_html = f'<p class="notice">{html.escape(message)}</p>' if message else ""
        return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PadNote 电脑连接助手</title>
        <style>body{{font:15px system-ui;margin:0;background:#f4f6f8;color:#18202a}}main{{max-width:900px;margin:auto;padding:28px}}section,article{{background:white;border:1px solid #dbe1e8;border-radius:12px;padding:18px;margin:14px 0}}h1,h2,h3{{margin-top:0}}label{{display:block;margin:9px 0}}input,select,textarea{{box-sizing:border-box;width:100%;padding:9px;border:1px solid #aeb8c4;border-radius:7px;margin-top:4px}}button{{padding:8px 12px;border:0;border-radius:7px;background:#155eef;color:white;cursor:pointer}}button.danger{{background:#b42318}}form.inline{{display:inline}}small,.muted{{color:#667085}}.notice{{padding:10px;background:#e7f0ff;border-radius:8px}}.status{{color:#475467}}.qr{{width:min(280px,100%);display:block;margin:10px 0}}code{{overflow-wrap:anywhere}}li{{margin:9px 0}}</style></head><body><main>
        <h1>PadNote 电脑连接助手</h1><p>管理页只在本机 {admin_port} 端口打开。只把 {api_port} 设备 API 端口交给 Tailscale Serve。</p>{message_html}
        <section><h2>1. 发现本机 Agent</h2><ul>{candidate_html}</ul><form method="post" action="/admin/discovery/refresh"><input type="hidden" name="csrf" value="{csrf}"><button>刷新发现</button></form></section>
        <section><h2>2. 添加实例</h2><p>不会安装、覆盖或启动 Agent。Hermes API 常用本机地址为 <code>http://127.0.0.1:8642</code>。</p>
        <form method="post" action="/admin/instances"><input type="hidden" name="csrf" value="{csrf}"><label>类型<select name="kind"><option value="hermes">Hermes</option><option value="openclaw">OpenClaw（仅发现/引导）</option></select></label><label>显示名称<input name="name" maxlength="120" required></label><label>本机 Agent URL<input name="base_url" value="http://127.0.0.1:8642" required></label><label>Hermes key（可稍后填写，不回显）<input type="password" name="api_key" autocomplete="off"></label><button>添加</button></form></section>
        <section><h2>3. 检测实例</h2>{''.join(forms) or '<p>请先添加实例。</p>'}</section>
        <section><h2>4. 生成平板配对</h2><p>先用 <code>tailscale serve status</code> 检查现有配置，再把本机 {api_port} 设备 API 端口发布为 HTTPS。助手不会自动修改、reset 或启用 Funnel。</p><pre>tailscale serve --bg --https=443 http://127.0.0.1:{api_port}</pre>
        <form method="post" action="/admin/pair-codes"><input type="hidden" name="csrf" value="{csrf}"><label>Agent 实例<select name="instance_id" required>{options}</select></label><label>Tailscale HTTPS 根地址<input name="public_url" placeholder="https://computer.example.ts.net" required></label><button>生成单次配对</button></form></section>
        {pair_block}<section><h2>待批准设备</h2><p>平板提交申请后，点击刷新查看；批准前请核对设备名称。</p><form method="get" action="/"><button>刷新待批准设备</button></form><ul>{pending_html}</ul></section><section><h2>已授权设备</h2><ul>{connection_html}</ul></section>
        </main></body></html>"""


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
                self._json(200, self.service.capabilities(match.group(1), self._bearer())); return
            match = re.fullmatch(re.escape(API_PREFIX) + r"/agents/([A-Za-z0-9_.:-]+)/runs", path)
            if method == "POST" and match:
                status, value = self.service.submit_run(match.group(1), self._bearer(),
                                                        self.headers.get("Idempotency-Key", ""), self._body())
                self._json(status, value); return
            match = re.fullmatch(re.escape(API_PREFIX) + r"/agents/([A-Za-z0-9_.:-]+)/runs/([A-Za-z0-9_.:-]+)", path)
            if method == "GET" and match:
                self._json(200, self.service.get_run(match.group(1), self._bearer(), match.group(2))); return
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
    ):
        self.service = service
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
        except BaseException:
            for server in (self.admin, self.api):
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
        for server, name in ((self.admin, "padnote-admin"), (self.api, "padnote-api")):
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
            for server in (self.admin, self.api):
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
