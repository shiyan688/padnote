from __future__ import annotations

import hashlib
import hmac
import ipaddress
import json
import secrets
from typing import Any
from urllib.parse import urlsplit, urlunsplit


class ValidationError(ValueError):
    """A safe, user-displayable validation error."""


def random_token(byte_count: int = 32) -> str:
    if byte_count < 32:
        raise ValueError("security tokens must contain at least 32 random bytes")
    return secrets.token_urlsafe(byte_count)


def digest_token(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def token_matches(value: str, expected_digest: str) -> bool:
    return hmac.compare_digest(digest_token(value), expected_digest)


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def payload_digest(value: Any) -> str:
    return hashlib.sha256(canonical_json(value)).hexdigest()


def validate_identifier(value: Any, field: str, maximum: int = 120) -> str:
    if not isinstance(value, str):
        raise ValidationError(f"{field} must be a string")
    clean = value.strip()
    if not clean or len(clean) > maximum:
        raise ValidationError(f"invalid {field}")
    allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.:-"
    if any(character not in allowed for character in clean):
        raise ValidationError(f"invalid {field}")
    return clean


def validate_text(value: Any, field: str, maximum: int, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str):
        raise ValidationError(f"{field} must be a string")
    clean = value.strip()
    if (not clean and not allow_empty) or len(clean) > maximum:
        raise ValidationError(f"invalid {field}")
    return clean


def _is_loopback(hostname: str) -> bool:
    if hostname.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(hostname).is_loopback
    except ValueError:
        return False


def validate_upstream_url(value: str) -> str:
    """Allow HTTPS, plus HTTP only for an explicit loopback Hermes endpoint."""
    try:
        parsed = urlsplit(value.strip())
    except ValueError as error:
        raise ValidationError("invalid Agent URL") from error
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValidationError("Agent URL must use HTTP or HTTPS")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValidationError("Agent URL cannot contain credentials, query, or fragment")
    if parsed.scheme == "http" and not _is_loopback(parsed.hostname):
        raise ValidationError("plain HTTP is limited to loopback Agent services")
    path = parsed.path.rstrip("/")
    return urlunsplit((parsed.scheme, parsed.netloc, path, "", ""))


def validate_builtin_video_provider(value: str, region: str) -> str:
    """Restrict key-bearing built-in video requests to the official Qwen API."""
    try:
        parsed = urlsplit(value.strip())
    except ValueError as error:
        raise ValidationError("invalid built-in video provider") from error
    hosts = {
        "china": ("dashscope.aliyuncs.com", "/compatible-mode/v1"),
        "international": ("dashscope-intl.aliyuncs.com", "/compatible-mode/v1"),
    }
    expected = hosts.get(region)
    if parsed.scheme != "https" or expected is None or parsed.hostname != expected[0] \
            or parsed.port not in (None, 443) or parsed.path.rstrip("/") != expected[1] \
            or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValidationError("Built-in video provider must use an official Qwen regional endpoint")
    return f"https://{expected[0]}{expected[1]}"


def validate_public_url(value: str, *, allow_loopback_http: bool = False) -> str:
    """Validate the URL copied to a tablet. Production URLs must use HTTPS."""
    try:
        parsed = urlsplit(value.strip())
    except ValueError as error:
        raise ValidationError("invalid public URL") from error
    if not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValidationError("public URL cannot contain credentials, query, or fragment")
    allowed_http = allow_loopback_http and parsed.scheme == "http" and _is_loopback(parsed.hostname)
    if parsed.scheme != "https" and not allowed_http:
        raise ValidationError("tablet URL must use HTTPS")
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path.rstrip("/"), "", ""))


def validate_lan_url(value: str) -> str:
    """An https URL on a private IPv4 address, for certificate-pinned pairing only."""
    try:
        parsed = urlsplit(value.strip())
        address = ipaddress.ip_address(parsed.hostname or "")
        port = parsed.port
    except ValueError as error:
        raise ValidationError("invalid local network address") from error
    if parsed.scheme != "https" or not isinstance(address, ipaddress.IPv4Address) \
            or not address.is_private or address.is_loopback or address.is_link_local \
            or address in ipaddress.ip_network("100.64.0.0/10") or port is None \
            or parsed.path not in ("", "/") or parsed.username or parsed.query or parsed.fragment:
        raise ValidationError("local network address must be a private IPv4 address")
    return f"https://{address}:{port}"


def redact_exception(error: BaseException) -> str:
    """Return a generic message; upstream bodies and credentials never reach API logs."""
    if isinstance(error, ValidationError):
        return str(error)
    if isinstance(error, TimeoutError):
        return "Agent request timed out"
    if isinstance(error, ConnectionError):
        return "Agent is not reachable"
    return "Agent request failed"
