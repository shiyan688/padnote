from __future__ import annotations

import hashlib
import os
import re
import stat
from dataclasses import dataclass
from pathlib import Path

from .security import ValidationError


MAX_ENV_BYTES = 64 * 1024
ALLOWED_KEYS = {"API_SERVER_ENABLED", "API_SERVER_HOST", "API_SERVER_PORT", "API_SERVER_KEY"}


@dataclass(frozen=True)
class HermesProfile:
    path: str
    base_url: str
    api_key: str
    fingerprint: str


def read_hermes_profile(path: Path) -> HermesProfile:
    """Parse four API_SERVER_* values without sourcing or executing the file."""
    path = Path(path).expanduser()
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise ValidationError("Cannot safely open the selected Hermes .env file") from error
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > MAX_ENV_BYTES:
            raise ValidationError("Selected Hermes .env is not a small regular file")
        raw = b""
        while len(raw) <= MAX_ENV_BYTES:
            chunk = os.read(descriptor, min(8192, MAX_ENV_BYTES + 1 - len(raw)))
            if not chunk:
                break
            raw += chunk
        if len(raw) > MAX_ENV_BYTES:
            raise ValidationError("Selected Hermes .env is too large")
    finally:
        os.close(descriptor)
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValidationError("Selected Hermes .env must be UTF-8") from error
    values: dict[str, str] = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("export "):
            stripped = stripped[7:].lstrip()
        match = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)=(.*)", stripped)
        if not match or match.group(1) not in ALLOWED_KEYS:
            continue
        values[match.group(1)] = _literal_value(match.group(2))
    if values.get("API_SERVER_ENABLED", "").lower() not in {"1", "true", "yes", "on"}:
        raise ValidationError(
            "Hermes API Server is not enabled. Keep the existing .env and add API_SERVER_ENABLED=true, "
            "API_SERVER_HOST=127.0.0.1, API_SERVER_PORT=8642, and a strong API_SERVER_KEY. "
            "保存配置并重启 Gateway 后，刷新发现，再点一次‘使用这套配置’。"
        )
    key = values.get("API_SERVER_KEY", "").strip()
    if not key:
        raise ValidationError(
            "Selected Hermes profile has no API_SERVER_KEY. "
            "保存配置并重启 Gateway 后，刷新发现，再点一次‘使用这套配置’。")
    host = values.get("API_SERVER_HOST", "127.0.0.1").strip()
    if host in {"0.0.0.0", "::", "[::]", "localhost"}:
        host = "127.0.0.1"
    if host not in {"127.0.0.1", "::1", "[::1]"}:
        raise ValidationError("Hermes profile API_SERVER_HOST must be loopback for the local Bridge")
    raw_port = values.get("API_SERVER_PORT", "8642").strip()
    try:
        port = int(raw_port)
    except ValueError as error:
        raise ValidationError("Hermes API_SERVER_PORT is invalid") from error
    if port < 1 or port > 65535:
        raise ValidationError("Hermes API_SERVER_PORT is invalid")
    url_host = "[::1]" if host in {"::1", "[::1]"} else "127.0.0.1"
    identity = f"enabled=true\nhost={url_host}\nport={port}\nkey={key}\n".encode("utf-8")
    return HermesProfile(
        path=str(path.resolve()),
        base_url=f"http://{url_host}:{port}",
        api_key=key,
        fingerprint=hashlib.sha256(identity).hexdigest(),
    )


def _literal_value(value: str) -> str:
    value = value.strip()
    if not value:
        return ""
    if value[0] in {'"', "'"}:
        if len(value) < 2 or value[-1] != value[0]:
            raise ValidationError("Hermes .env contains an unterminated quoted value")
        return value[1:-1]
    # Do not interpret shell expansion, interpolation, escapes, or inline commands.
    if any(marker in value for marker in ("$(`", "$(", "`", "${", "\n", "\r")):
        raise ValidationError("Hermes .env API settings must use literal values")
    return value.split(" #", 1)[0].strip()
