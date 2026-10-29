"""Local-only Hermes status and connect client for the Windows launcher."""
from __future__ import annotations

import html.parser
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import threading

BASE_URL = "http://127.0.0.1:8766"
STATUS_PATH = "/admin/onboarding/status"
CONNECT_PATH = "/admin/hermes/connect"
VALID_STATUSES = {"ready", "needs_setup", "not_installed", "ambiguous", "starting", "error"}
CONNECT_PROBE_STATUSES = {"ready", "needs_setup", "starting"}
INSTALLER_URL = "https://raw.githubusercontent.com/NousResearch/hermes-agent/bf0203a794a07aac3ff764972ab9087baa4e4f4e/scripts/install.ps1"
INSTALLER_COMMIT = "bf0203a794a07aac3ff764972ab9087baa4e4f4e"
INSTALLER_GIT_BLOB = "7fb6316fb5a8421cb1dc5836692860b871a3984c"
INSTALLER_SHA256 = "8d491a8b9b19934bb8d027b11962c394551798e969d244b68d4ab95295b970e2"
INSTALLER_BYTES = 88786
INSTALLER_MAX_BYTES = INSTALLER_BYTES
INSTALLER_TIMEOUT = 30.0
POWERSHELL_NEW_CONSOLE = 0x00000010


def should_probe_hermes(status: str) -> bool:
    """Cached startup states still need the explicit backend connection probe."""
    return status in CONNECT_PROBE_STATUSES


def phase_label(phase: str) -> str:
    labels = {
        "discovery": "查找 Hermes 安装",
        "config": "检查本机 API 配置",
        "command": "解析启动命令",
        "platform": "检查系统支持",
        "setup": "启动交互配置",
        "check": "检查 Hermes API",
        "not_running": "等待本机服务启动",
        "check_timeout": "等待 API 响应超时",
        "api": "检查 API 状态",
        "start": "启动 Hermes Gateway",
    }
    return labels.get(phase, "检查 Hermes 状态" if not phase else "Hermes 阶段：" + phase[:40])


def message_label(status: str, phase: str, message: str) -> str:
    text = message.casefold()
    if "multiple" in text or "ambigu" in text:
        return "发现多个 Hermes；请在管理页选择目标实例。"
    if "not found" in text or "was not found" in text:
        return "未在常见位置找到 Hermes；打开连接设置可手动填写本机 API，或查看官方安装指南。"
    if "api configuration" in text or "api settings" in text or "api server is not configured" in text:
        return "保留现有配置，完成 API Server 设置和模型登录。"
    if "already running" in text or "responding" in text:
        return "Hermes API 已响应。"
    if "still initializing" in text or "initializing" in text:
        return "Hermes 正在启动；请稍后再次检查。"
    if "could not start" in text or "exited during startup" in text:
        return "Hermes 启动失败；请检查本机日志后重试。"
    if "setup opened" in text or "complete provider login" in text:
        return "Hermes 配置终端已打开；完成模型登录后回到此处再次检查。"
    if status == "ready":
        return "Hermes 已连接，可以继续平板配对。"
    if status == "needs_setup":
        return "请打开配置向导完成模型登录和 API 设置。"
    if status == "not_installed":
        return "请按官方 Windows 指南安装 Hermes。"
    if status == "ambiguous":
        return "请先确认要使用的 Hermes 实例。"
    if status == "starting":
        return "Hermes 仍在启动，请稍后再次检查。"
    return "请检查 Hermes 配置和本机日志，再复制诊断。"


def verify_download_bytes(payload: bytes, expected_sha256: str) -> bool:
    """Check exact bytes against a fixed format SHA-256 digest."""
    return (len(expected_sha256) == 64 and all(ch in "0123456789abcdefABCDEF" for ch in expected_sha256)
            and hashlib.sha256(payload).hexdigest().lower() == expected_sha256.lower())


def _installer_opener():
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def hermes_installer_cache_dir(environ: dict[str, str] | None = None) -> Path:
    env = os.environ if environ is None else environ
    local_app_data = env.get("LOCALAPPDATA", "").strip()
    if not local_app_data:
        raise RuntimeError("Windows LOCALAPPDATA is unavailable")
    return Path(local_app_data) / "PadNote" / "cache" / "hermes-installer"


def official_hermes_cli_paths(local_app_data: str) -> tuple[Path, Path]:
    """Match the official Windows bin and managed-venv CLI fallbacks."""
    root = Path(local_app_data) / "hermes"
    return root / "bin" / "hermes.exe", root / "hermes-agent" / "venv" / "Scripts" / "hermes.exe"


def has_official_hermes_cli(local_app_data: str) -> bool:
    return any(path.is_file() for path in official_hermes_cli_paths(local_app_data))


def has_existing_hermes_home(local_app_data: str) -> bool:
    """Do not install over pre-existing official Hermes data, even if its CLI is missing."""
    root = Path(local_app_data) / "hermes"
    try:
        return root.is_dir() and next(root.iterdir(), None) is not None
    except OSError:
        return root.exists()


def download_pinned_installer(cache_dir: Path, cancel_event: threading.Event | None = None) -> Path:
    """Download only the pinned official script, verify size/hash, then create a new cache file."""
    _check_cancel(cancel_event)
    request = urllib.request.Request(INSTALLER_URL, headers={"Accept": "text/plain", "User-Agent": "PadNote-Connection-Assistant"})
    payload = bytearray()
    try:
        with _installer_opener().open(request, timeout=INSTALLER_TIMEOUT) as response:
            if response.status != 200 or response.geturl() != INSTALLER_URL:
                raise RuntimeError("Hermes 官方安装脚本下载地址或 HTTP 状态不正确")
            content_length = response.headers.get("Content-Length")
            if content_length:
                try:
                    if int(content_length) != INSTALLER_BYTES:
                        raise RuntimeError("Hermes 官方安装脚本长度与固定版本不符")
                except ValueError:
                    raise RuntimeError("Hermes 官方安装脚本长度无效") from None
            while True:
                _check_cancel(cancel_event)
                chunk = response.read(16_384)
                if not chunk:
                    break
                payload.extend(chunk)
                if len(payload) > INSTALLER_MAX_BYTES:
                    raise RuntimeError("Hermes 官方安装脚本超过允许大小")
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"Hermes 官方安装脚本下载失败（HTTP {exc.code}）") from None
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise RuntimeError(f"Hermes 官方安装脚本下载失败：{type(exc).__name__}") from None
    _check_cancel(cancel_event)
    if len(payload) != INSTALLER_BYTES:
        raise RuntimeError("Hermes 官方安装脚本长度校验失败")
    if not verify_download_bytes(bytes(payload), INSTALLER_SHA256):
        raise RuntimeError("Hermes 官方安装脚本 SHA-256 校验失败")
    cache_dir.mkdir(parents=True, exist_ok=True)
    fd, filename = tempfile.mkstemp(prefix="hermes-install-" + INSTALLER_COMMIT[:8] + "-", suffix=".ps1", dir=cache_dir)
    path = Path(filename)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        _check_cancel(cancel_event)
        return path
    except Exception:
        try:
            path.unlink()
        except OSError:
            pass
        raise


def installer_arguments(script_path: Path, powershell_path: Path, hermes_home: Path) -> list[str]:
    """Build an argv vector for the visible, non-elevated PowerShell process."""
    return [str(powershell_path), "-NoProfile", "-NoExit", "-File", str(script_path),
            "-Commit", INSTALLER_COMMIT, "-Channel", "stable",
            "-HermesHome", str(hermes_home), "-InstallDir", str(hermes_home / "hermes-agent")]


def powershell_path(system_root: str) -> Path:
    return Path(system_root) / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"


def launch_verified_installer(script_path: Path, system_root: str, local_app_data: str, popen_factory=None):
    """Open the verified script in a new visible, non-elevated PowerShell console."""
    executable = powershell_path(system_root)
    if not executable.is_file():
        raise RuntimeError("Windows PowerShell 5.1 executable was not found")
    if popen_factory is None:
        import subprocess
        popen_factory = subprocess.Popen
    import subprocess
    return popen_factory(
        installer_arguments(script_path, executable, Path(local_app_data) / "hermes"), shell=False,
        cwd=str(script_path.parent),
        creationflags=getattr(subprocess, "CREATE_NEW_CONSOLE", POWERSHELL_NEW_CONSOLE),
    )


class _CsrfParser(html.parser.HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.token = ""

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "input" and values.get("type") == "hidden" and values.get("name") == "csrf":
            self.token = values.get("value") or ""


def _local_opener():
    # Prevent system proxies or redirects from forwarding local administration data.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def _read_csrf(opener, timeout: float) -> str:
    with opener.open(urllib.request.Request(BASE_URL + "/", headers={"Accept": "text/html"}), timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"management page returned HTTP {response.status}")
        document = response.read(1_000_000).decode("utf-8", "replace")
    parser = _CsrfParser()
    parser.feed(document)
    if not parser.token or len(parser.token) > 256:
        raise RuntimeError("management page did not provide a valid CSRF token")
    return parser.token


def _safe_status_text(value: object, limit: int) -> str:
    if not isinstance(value, str):
        return ""
    return "".join(ch for ch in value if ch >= " " and ch not in "\x7f\u2028\u2029")[:limit]


def normalize_status_payload(payload: object) -> dict[str, object]:
    status = payload.get("status") if isinstance(payload, dict) else None
    if status not in VALID_STATUSES:
        raise RuntimeError("status endpoint returned an invalid status")
    instance_id = payload.get("instance_id", "")
    result: dict[str, object] = {
        "status": status,
        "instance_id": _safe_status_text(instance_id, 128),
        "message": _safe_status_text(payload.get("message", ""), 500),
        "phase": _safe_status_text(payload.get("phase", ""), 120),
        "launched": payload.get("launched") if isinstance(payload.get("launched"), bool) else False,
        "base_url": _safe_status_text(payload.get("base_url", ""), 256),
    }
    return result


def read_status(timeout: float = 6.0, cancel_event: threading.Event | None = None) -> dict[str, object]:
    opener = _local_opener()
    _check_cancel(cancel_event)
    csrf = _read_csrf(opener, timeout)
    _check_cancel(cancel_event)
    request = urllib.request.Request(BASE_URL + STATUS_PATH, headers={"Accept": "application/json", "X-PadNote-CSRF": csrf})
    with opener.open(request, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"status endpoint returned HTTP {response.status}")
        payload = json.loads(response.read(16384).decode("utf-8"))
    _check_cancel(cancel_event)
    return normalize_status_payload(payload)


def _check_cancel(cancel_event: threading.Event | None) -> None:
    if cancel_event and cancel_event.is_set():
        raise RuntimeError("Hermes 操作已取消")


def connect(timeout: float = 30.0, cancel_event: threading.Event | None = None) -> dict[str, object]:
    """Fetch same-origin CSRF token, POST the connect action, then read safe status."""
    opener = _local_opener()
    _check_cancel(cancel_event)
    token = _read_csrf(opener, timeout)
    _check_cancel(cancel_event)
    body = urllib.parse.urlencode({"csrf": token}).encode("ascii")
    request = urllib.request.Request(BASE_URL + CONNECT_PATH, data=body, method="POST", headers={
        "Content-Type": "application/x-www-form-urlencoded", "Origin": BASE_URL,
        "Referer": BASE_URL + "/", "Accept": "text/html",
    })
    try:
        with opener.open(request, timeout=timeout) as response:
            if response.status not in (200, 303):
                raise RuntimeError(f"connect endpoint returned HTTP {response.status}")
    except urllib.error.HTTPError as exc:
        if exc.code != 303:
            raise RuntimeError(f"connect endpoint returned HTTP {exc.code}") from None
    _check_cancel(cancel_event)
    return read_status(min(timeout, 8.0), cancel_event)


def open_setup(timeout: float = 30.0, cancel_event: threading.Event | None = None) -> None:
    """Ask the local service to open Hermes's visible setup terminal."""
    opener = _local_opener()
    _check_cancel(cancel_event)
    token = _read_csrf(opener, timeout)
    _check_cancel(cancel_event)
    body = urllib.parse.urlencode({"csrf": token}).encode("ascii")
    request = urllib.request.Request(BASE_URL + "/admin/hermes/setup", data=body, method="POST", headers={
        "Content-Type": "application/x-www-form-urlencoded", "Origin": BASE_URL,
        "Referer": BASE_URL + "/", "Accept": "text/html",
    })
    try:
        with opener.open(request, timeout=timeout) as response:
            if response.status not in (200, 303):
                raise RuntimeError(f"setup endpoint returned HTTP {response.status}")
    except urllib.error.HTTPError as exc:
        if exc.code != 303:
            raise RuntimeError(f"setup endpoint returned HTTP {exc.code}") from None
