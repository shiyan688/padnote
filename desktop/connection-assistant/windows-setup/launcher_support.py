"""Cross-platform launcher diagnostics, redaction, and lifecycle rules."""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

MAX_DIAGNOSTIC_CHARS = 48_000
MAX_LOG_BYTES = 1_000_000

def _redact_url_query(match: re.Match[str]) -> str:
    url = match.group(1)
    base, query = url.split("?", 1)
    params = []
    for item in query.split("&"):
        key, sep, value = item.partition("=")
        if re.search(r"(?i)token|code|key|secret|pair|credential", key):
            value = "[REDACTED]"
        params.append(key + (sep + value if sep else ""))
    return base + "?" + "&".join(params)


_SECRET_KEY = r"(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|secret|(?:pair(?:ing)?[_-]?)?(?:code|key)|credential)"
_PATTERNS = [
    (re.compile(r"(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]+"), r"\1[REDACTED]"),
    # key=value and JSON-style quoted key/value pairs, including escaped strings.
    (re.compile(rf'(?i)(["\']?{_SECRET_KEY}["\']?\s*[=:]\s*)(["\']?)([^\s,;"\'}}]+)'), r"\1\2[REDACTED]"),
    (re.compile(r"(?i)\b(?:sk-[A-Za-z0-9_-]{12,}|xox[baprs]-[A-Za-z0-9-]{10,})\b"), "[REDACTED TOKEN]"),
    (re.compile(r"(?i)\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}\b"), "[REDACTED JWT]"),
    (re.compile(r"(?i)padnote://pair[^\s\"']*"), "padnote://pair/[REDACTED]"),
    (re.compile(r"(?i)(https?://[^\s?#]+\?[^\s#]*)(?:#[^\s]*)?"), _redact_url_query),
    (re.compile(r"(?i)(https?://)[^\s/@]+:[^\s/@]+@"), r"\1[REDACTED]@"),
    (re.compile(r"(?i)(?<!\w)(?:[A-Z]:\\Users\\[^\r\n\"']+|/Users/[^\r\n\"']+|/home/[^\r\n\"']+)"), "[PRIVATE PATH]"),
    (re.compile(r"(?i)\b(?:[A-F0-9]{2}:){5}[A-F0-9]{2}\b"), "[DEVICE ADDRESS]"),
]




def sanitize_diagnostics(text: str, limit: int = MAX_DIAGNOSTIC_CHARS) -> str:
    """Remove common credentials, pairing payloads, private paths, and key blocks."""
    value = str(text or "")
    # Replace private key material even when the dump is truncated or malformed.
    value = re.sub(r"-----BEGIN [^-]*PRIVATE KEY-----.*?(?:-----END [^-]*PRIVATE KEY-----|$)",
                   "[PRIVATE KEY REDACTED]", value, flags=re.S | re.I)
    for pattern, replacement in _PATTERNS:
        value = pattern.sub(replacement, value)
    if len(value) > limit:
        value = value[:limit] + "\n[diagnostics truncated]"
    return value


def rotate_log(path: Path, *, max_bytes: int = MAX_LOG_BYTES) -> str | None:
    """Keep the current bounded log as one previous generation."""
    previous = path.with_name(path.name + ".previous")
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            with path.open("rb") as source:
                source.seek(max(0, path.stat().st_size - max_bytes))
                previous.write_bytes(source.read(max_bytes))
            path.unlink()
        return None
    except OSError as exc:
        return f"Could not rotate launcher log: {type(exc).__name__}: {exc}"


def read_log_tail(path: Path, *, limit: int = MAX_LOG_BYTES, start: int | None = None) -> tuple[str, int]:
    """Read a bounded file tail or a bounded byte range since start; return next offset."""
    with path.open("rb") as stream:
        size = path.stat().st_size
        begin = max(0, size - limit) if start is None else min(size, max(0, start))
        if size - begin > limit:
            begin = size - limit
        stream.seek(begin)
        data = stream.read(limit)
    return data.decode("utf-8", "replace"), begin + len(data)


def load_saved_logs(path: Path) -> tuple[str, str]:
    """Read existing current and previous logs without creating or rotating files."""
    values = []
    for candidate in (path, path.with_name(path.name + ".previous")):
        try:
            values.append(read_log_tail(candidate)[0] if candidate.is_file() else "")
        except OSError as exc:
            values.append(f"Log unavailable: {type(exc).__name__}: {exc}")
    return values[0], values[1]


def prioritize_current_log(current: str, previous: str, *, limit: int = MAX_DIAGNOSTIC_CHARS) -> str:
    """Fit log sections to a limit while reserving space for the current run first."""
    current_header, previous_header = "--- 本次运行日志 ---\n", "\n--- 上次运行日志 ---\n"
    safe_current = sanitize_diagnostics(current, MAX_LOG_BYTES)
    safe_previous = sanitize_diagnostics(previous, MAX_LOG_BYTES)
    current_room = max(0, limit - len(current_header) - len(previous_header))
    current_text = safe_current[-current_room:] if current_room else ""
    prior_room = max(0, limit - len(current_header) - len(current_text) - len(previous_header))
    prior_text = safe_previous[-prior_room:] if prior_room else ""
    sections = current_header + current_text
    if prior_text:
        sections += previous_header + prior_text
    return sections


def read_logs(path: Path, memory_text: str = "", *, limit: int = MAX_DIAGNOSTIC_CHARS) -> str:
    """Read current and previous log tails, preserving useful fallback diagnostics."""
    pieces = []
    for label, candidate in (("Current log", path), ("Previous log", path.with_name(path.name + ".previous"))):
        try:
            if candidate.is_file():
                text, _ = read_log_tail(candidate)
                pieces.append(f"--- {label} ---\n" + text)
        except OSError as exc:
            pieces.append(f"--- {label} unavailable: {type(exc).__name__}: {exc} ---")
    if memory_text:
        pieces.append("--- Launcher diagnostics ---\n" + memory_text)
    return sanitize_diagnostics("\n".join(pieces), limit)


def explain_exit(code: int, *, was_ready: bool, startup_error: dict[str, Any] | None = None) -> tuple[str, str]:
    """Return accurate generic or structured failure copy and a next step."""
    if startup_error:
        category = startup_error.get("category")
        details = {
            "state-in-use": ("PadNote 状态正在被另一个助手使用。", "请关闭另一个 PadNote 助手后重试。"),
            "state-invalid": ("无法读取已保存的 PadNote 状态。", "请查看诊断并联系支持人员，再决定是否更改状态目录。"),
            "port-in-use": ("PadNote 所需的本机端口已被占用。", "请关闭占用该端口的程序后重试。"),
            "access-denied": ("Windows 拒绝访问助手所需的资源。", "请检查文件夹或网络权限后重试。"),
            "runtime-error": ("助手报告了启动错误。", "请查看下方诊断后重试。"),
        }
        return details.get(category, details["runtime-error"])
    if code == 0 and was_ready:
        return "连接助手已停止。", "需要重新连接时，请点击启动助手。"
    stage = "启动期间" if not was_ready else "就绪后"
    return f"连接助手在{stage}退出（退出代码 {code}）。", "请查看下方诊断后重试。"


class Lifecycle:
    """Small ownership model used by the UI and cross-platform race tests."""
    def __init__(self):
        self.process = None
        self.stopping = False
        self.closing = False
        self.ready = False

    @property
    def can_start(self) -> bool:
        return self.process is None and not self.stopping and not self.closing

    @property
    def can_open(self) -> bool:
        return self.process is not None and not self.stopping and not self.closing and self.ready

    @property
    def can_stop(self) -> bool:
        return self.process is not None and not self.stopping

    def started(self, process: object) -> None:
        if not self.can_start:
            raise RuntimeError("cannot start while another owned process is active")
        self.process = process
        self.ready = False

    def mark_ready(self, process: object) -> bool:
        if self.process is not process or self.stopping or self.ready:
            return False
        self.ready = True
        return True

    def begin_stop(self, process: object) -> bool:
        if self.process is not process or self.stopping:
            return False
        self.stopping = True
        return True

    def stop_result(self, process: object, *, exited: bool) -> bool:
        if self.process is not process or not self.stopping:
            return False
        self.stopping = False
        if exited:
            self.process = None
            self.ready = False
        return True

    def exited(self, process: object) -> bool:
        if self.process is not process or self.stopping:
            return False
        self.process = None
        self.ready = False
        return True


def parse_exit_code(text: str) -> int | None:
    matches = re.findall(r"(?m)^PADNOTE_LAUNCHER_EXIT_CODE (-?\d+)\s*$", text)
    return int(matches[-1]) if matches else None


def parse_startup_error(text: str, *, ready: bool = False) -> dict[str, Any] | None:
    if ready:
        return None
    for line in text.splitlines():
        marker = "PADNOTE_STARTUP_ERROR "
        if not line.startswith(marker):
            continue
        try:
            payload = json.loads(line[len(marker):])
        except (json.JSONDecodeError, TypeError):
            return None
        if isinstance(payload, dict) and payload.get("category") in {
            "state-in-use", "state-invalid", "port-in-use", "access-denied", "runtime-error"
        }:
            return payload
    return None
