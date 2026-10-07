from __future__ import annotations

"""Private bounded NDJSON reader for files beneath one trusted task root.

The Node worker uses this short-lived helper on Windows where Node lacks the
no-follow/nonblocking open flags used by the Skill's protected-file readers.
All filesystem access goes through ``windows_files.open_regular_file``; there
is deliberately no ordinary path-open fallback.
"""

import base64
import json
import os
import re
import stat
import sys
from pathlib import Path
from typing import BinaryIO, Callable


MAX_MESSAGE_BYTES = 16 * 1024
MAX_PATH_BYTES = 4096
MAX_PATH_DEPTH = 16
MAX_WHOLE_BYTES = 32 * 1024 * 1024
MAX_TAIL_BYTES = 64 * 1024
MAX_REQUESTS = 4096
MAX_TOTAL_RAW_BYTES = 512 * 1024 * 1024
_CHUNK_BYTES = 64 * 1024
_ID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


class _ReaderError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


_ERROR_CODES = frozenset({
    "invalid_request", "invalid_path", "size_limit", "file_unavailable", "not_found",
    "unsafe_file", "file_changed", "read_failed", "request_limit",
    "total_limit", "protocol_error",
})


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def _relative_components(value: object) -> tuple[str, ...]:
    if not isinstance(value, str) or not value:
        raise _ReaderError("invalid_path")
    try:
        if len(value.encode("utf-8", "strict")) > MAX_PATH_BYTES:
            raise _ReaderError("invalid_path")
    except UnicodeEncodeError as error:
        raise _ReaderError("invalid_path") from error
    if value.startswith("/") or "\\" in value or "\x00" in value or ":" in value:
        raise _ReaderError("invalid_path")
    components = tuple(value.split("/"))
    if len(components) > MAX_PATH_DEPTH:
        raise _ReaderError("invalid_path")
    for part in components:
        if not part or part in {".", ".."} or part.endswith((".", " ")) \
                or any(ord(char) < 32 or char in '*?"<>|' for char in part):
            raise _ReaderError("invalid_path")
        device_stem = part.split(".", 1)[0].upper()
        if device_stem in {"CON", "PRN", "AUX", "NUL", "CLOCK$"} \
                or re.fullmatch(r"(?:COM|LPT)[1-9¹²³]", device_stem):
            raise _ReaderError("invalid_path")
    return components


def _validate_request(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise _ReaderError("invalid_request")
    allowed = {"schema_version", "id", "mode", "relative_path", "max_bytes", "declared_size"}
    required = allowed - {"declared_size"}
    if set(value) - allowed or not required.issubset(value):
        raise _ReaderError("invalid_request")
    if type(value["schema_version"]) is not int or value["schema_version"] != 1 \
            or not isinstance(value["id"], str) or not _ID_RE.fullmatch(value["id"]):
        raise _ReaderError("invalid_request")
    mode = value["mode"]
    max_bytes = value["max_bytes"]
    if mode not in {"whole", "tail"} or type(max_bytes) is not int:
        raise _ReaderError("invalid_request")
    if mode == "whole":
        if not 0 <= max_bytes <= MAX_WHOLE_BYTES:
            raise _ReaderError("size_limit")
        if "declared_size" in value and (type(value["declared_size"]) is not int
                                          or not 0 <= value["declared_size"] <= MAX_WHOLE_BYTES):
            raise _ReaderError("invalid_request")
    else:
        if not 1 <= max_bytes <= MAX_TAIL_BYTES or "declared_size" in value:
            raise _ReaderError("size_limit" if type(max_bytes) is int else "invalid_request")
    components = _relative_components(value["relative_path"])
    normalized = dict(value)
    normalized["components"] = components
    return normalized


def _file_metadata(descriptor: int) -> tuple[os.stat_result, tuple[int, int, int, int, int, int]]:
    try:
        info = os.fstat(descriptor)
    except OSError as error:
        raise _ReaderError("read_failed") from error
    if not stat.S_ISREG(info.st_mode) or info.st_size < 0 or info.st_nlink != 1:
        raise _ReaderError("unsafe_file")
    fingerprint = (int(info.st_dev), int(info.st_ino), int(info.st_size),
                   int(info.st_mtime_ns), int(info.st_ctime_ns), int(info.st_nlink))
    return info, fingerprint


def _read_descriptor(descriptor: int, request: dict[str, object]) -> tuple[bytes, dict[str, str]]:
    mode = request["mode"]
    maximum = request["max_bytes"]
    assert isinstance(maximum, int)
    before, fingerprint = _file_metadata(descriptor)
    declared = request.get("declared_size")
    if declared is not None and before.st_size != declared:
        raise _ReaderError("file_changed")
    if mode == "whole":
        if before.st_size > maximum:
            raise _ReaderError("size_limit")
        offset = 0
        expected_length = before.st_size
    else:
        offset = max(0, before.st_size - maximum)
        expected_length = min(before.st_size, maximum)
    try:
        os.lseek(descriptor, offset, os.SEEK_SET)
    except OSError as error:
        raise _ReaderError("read_failed") from error

    data = bytearray()
    limit = maximum + 1 if mode == "whole" else expected_length
    while len(data) < limit:
        try:
            chunk = os.read(descriptor, min(_CHUNK_BYTES, limit - len(data)))
        except OSError as error:
            raise _ReaderError("read_failed") from error
        if not chunk:
            break
        data.extend(chunk)
    if mode == "whole" and len(data) > maximum:
        raise _ReaderError("size_limit")
    if len(data) != expected_length:
        raise _ReaderError("file_changed")
    after, after_fingerprint = _file_metadata(descriptor)
    if fingerprint != after_fingerprint:
        raise _ReaderError("file_changed")
    metadata = {
        "file_size": str(after.st_size),
        "offset": str(offset),
        "dev": str(after.st_dev),
        "ino": str(after.st_ino),
        "mtime_ns": str(after.st_mtime_ns),
        "ctime_ns": str(after.st_ctime_ns),
    }
    return bytes(data), metadata


def _open_and_read(root: Path, request: dict[str, object],
                   opener: Callable[[Path, tuple[str, ...], int], int]) -> tuple[bytes, dict[str, str]]:
    descriptor: int | None = None
    try:
        descriptor = opener(root, request["components"], MAX_PATH_DEPTH)  # type: ignore[arg-type]
        return _read_descriptor(descriptor, request)
    except _ReaderError:
        raise
    except OSError as error:
        # Keep only stable, path-free protocol errors. Do not leak raw paths,
        # Win32 messages, or exception text to the Node process.
        WindowsSafeOpenError = _safe_open_error_type()

        if isinstance(error, WindowsSafeOpenError):
            if error.code == "open_failed":
                if error.winerror in {2, 3}:  # ERROR_FILE_NOT_FOUND / ERROR_PATH_NOT_FOUND
                    raise _ReaderError("not_found") from error
                raise _ReaderError("file_unavailable") from error
            raise _ReaderError("unsafe_file") from error
        raise _ReaderError("file_unavailable") from error
    finally:
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError as error:
                # A close failure means we cannot claim the read was safely
                # completed. It is handled by the outer protocol boundary.
                raise _ReaderError("read_failed") from error


def _safe_open_api():
    if __package__:
        from .windows_files import WindowsSafeOpenError, open_regular_file
    else:
        # In isolated-script mode, main() has added only this packaged sibling
        # directory to sys.path before serving requests.
        from windows_files import WindowsSafeOpenError, open_regular_file
    return WindowsSafeOpenError, open_regular_file


def _safe_open_error_type():
    return _safe_open_api()[0]


def _response(request_id: str, *, mode: str | None = None, data: bytes | None = None,
              metadata: dict[str, str] | None = None, error: str | None = None) -> bytes:
    if error is not None:
        payload: dict[str, object] = {
            "schema_version": 1, "id": request_id, "ok": False,
            "code": error if error in _ERROR_CODES else "read_failed",
        }
    else:
        assert mode is not None and data is not None and metadata is not None
        payload = {
            "schema_version": 1, "id": request_id, "ok": True, "mode": mode,
            "data_base64": base64.b64encode(data).decode("ascii"), **metadata,
        }
    return json.dumps(payload, separators=(",", ":"), ensure_ascii=True).encode("utf-8") + b"\n"


def serve(root: Path | str, stdin: BinaryIO, stdout: BinaryIO,
          opener: Callable[[Path, tuple[str, ...], int], int] | None = None) -> int:
    """Serve bounded sequential requests until stdin EOF or a protocol limit."""
    fixed_root = Path(root)
    if not fixed_root.is_absolute():
        return 2
    if opener is None:
        opener = _safe_open_api()[1]
    request_count = 0
    total_raw_bytes = 0
    while True:
        try:
            line = stdin.readline(MAX_MESSAGE_BYTES + 1)
        except OSError:
            return 2
        if not line:
            return 0
        request_count += 1
        if request_count > MAX_REQUESTS:
            stdout.write(_response("", error="request_limit"))
            stdout.flush()
            return 2
        if len(line) > MAX_MESSAGE_BYTES or not line.endswith(b"\n"):
            stdout.write(_response("", error="protocol_error"))
            stdout.flush()
            return 2
        request_id = ""
        try:
            value = json.loads(line[:-1].decode("utf-8", "strict"), object_pairs_hook=_unique_object)
            if isinstance(value, dict) and isinstance(value.get("id"), str) \
                    and _ID_RE.fullmatch(value["id"]):
                request_id = value["id"]
            request = _validate_request(value)
            data, metadata = _open_and_read(fixed_root, request, opener)
            if total_raw_bytes + len(data) > MAX_TOTAL_RAW_BYTES:
                stdout.write(_response(request_id, error="total_limit"))
                stdout.flush()
                return 2
            total_raw_bytes += len(data)
            stdout.write(_response(request_id, mode=request["mode"], data=data, metadata=metadata))
        except _ReaderError as error:
            stdout.write(_response(request_id, error=error.code))
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError, TypeError):
            stdout.write(_response(request_id, error="invalid_request"))
        except BrokenPipeError:
            return 2
        except Exception:
            # No traceback, exception text, request content, or local path is
            # emitted on either protocol stream.
            stdout.write(_response(request_id, error="read_failed"))
        try:
            stdout.flush()
        except (OSError, BrokenPipeError):
            return 2


def main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--root", required=True)
    args = parser.parse_args(argv)
    try:
        return serve(args.root, sys.stdin.buffer, sys.stdout.buffer)
    except Exception:
        return 2


if __name__ == "__main__":
    # `-I` excludes the script directory, so resolve exactly this packaged
    # sibling module rather than consulting CWD, PYTHONPATH, or site packages.
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    raise SystemExit(main())
