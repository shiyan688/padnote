#!/usr/bin/env python3
"""Read-only APK identity check; it is not a release authorization."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import threading
import time
from typing import Any

MAX_APK_BYTES = 2 * 1024 * 1024 * 1024
MAX_TOOL_BYTES = 512 * 1024 * 1024
MAX_CAPTURE_BYTES = 1024 * 1024
TOOL_TIMEOUT_SECONDS = 60
REAP_TIMEOUT_SECONDS = 5
CHUNK_BYTES = 64 * 1024
REPARSE_POINT = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)


class Reject(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _identity(st: os.stat_result) -> tuple[int, ...]:
    return (
        int(st.st_dev), int(st.st_ino), int(st.st_mode), int(st.st_nlink),
        int(st.st_size), int(st.st_mtime_ns), int(st.st_ctime_ns),
        int(getattr(st, "st_file_attributes", 0)),
    )


def _plain_stat(path: Path, *, max_bytes: int, allow_hardlinks: bool = False) -> os.stat_result:
    try:
        st = path.lstat()
    except OSError as exc:
        raise Reject("input_unavailable") from exc
    if stat.S_ISLNK(st.st_mode) or not stat.S_ISREG(st.st_mode):
        raise Reject("input_not_plain_regular_file")
    if getattr(st, "st_file_attributes", 0) & REPARSE_POINT:
        raise Reject("input_reparse_point")
    if not allow_hardlinks and st.st_nlink != 1:
        raise Reject("input_link_count")
    if st.st_size < 0 or st.st_size > max_bytes:
        raise Reject("input_size_limit")
    return st


def stable_hash(path: Path, *, max_bytes: int, allow_hardlinks: bool = False) -> tuple[int, str]:
    before = _plain_stat(path, max_bytes=max_bytes, allow_hardlinks=allow_hardlinks)
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as exc:
        raise Reject("input_open_failed") from exc
    try:
        opened = os.fstat(fd)
        if _identity(before) != _identity(opened):
            raise Reject("input_changed_before_read")
        digest = hashlib.sha256()
        total = 0
        while True:
            try:
                block = os.read(fd, CHUNK_BYTES)
            except OSError as exc:
                raise Reject("input_read_failed") from exc
            if not block:
                break
            total += len(block)
            if total > max_bytes:
                raise Reject("input_size_limit")
            digest.update(block)
        after_fd = os.fstat(fd)
    finally:
        os.close(fd)
    after_path = _plain_stat(path, max_bytes=max_bytes, allow_hardlinks=allow_hardlinks)
    if _identity(before) != _identity(after_fd) or _identity(before) != _identity(after_path) or total != before.st_size:
        raise Reject("input_changed_during_read")
    return total, digest.hexdigest()


def tool_info(raw_path: str, *, max_bytes: int) -> tuple[Path, dict[str, Any]]:
    supplied = Path(raw_path)
    try:
        resolved = supplied.resolve(strict=True)
    except OSError as exc:
        raise Reject("tool_unavailable") from exc
    if not resolved.is_file() or not os.access(resolved, os.X_OK):
        raise Reject("tool_not_executable")
    size, digest = stable_hash(resolved, max_bytes=max_bytes, allow_hardlinks=True)
    return resolved, {"name": resolved.name, "bytes": size, "sha256": digest}


def _reader(pipe: Any, sink: bytearray, state: dict[str, Any]) -> None:
    try:
        while True:
            chunk = pipe.read(CHUNK_BYTES)
            if not chunk:
                break
            room = MAX_CAPTURE_BYTES - len(sink)
            if room > 0:
                sink.extend(chunk[:room])
            if len(chunk) > max(room, 0):
                state["truncated"] = True
    except BaseException:
        state["reader_error"] = True
    finally:
        try:
            pipe.close()
        except OSError:
            state["reader_error"] = True


def run_tool(argv: list[str], env: dict[str, str]) -> bytes:
    started = time.monotonic()
    try:
        proc = subprocess.Popen(
            argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            shell=False, close_fds=True, env=env,
        )
    except OSError as exc:
        raise Reject("tool_start_failed") from exc
    assert proc.stdout is not None and proc.stderr is not None
    out, err = bytearray(), bytearray()
    out_state: dict[str, Any] = {}
    err_state: dict[str, Any] = {}
    threads = [
        threading.Thread(target=_reader, args=(proc.stdout, out, out_state), daemon=True),
        threading.Thread(target=_reader, args=(proc.stderr, err, err_state), daemon=True),
    ]
    for thread in threads:
        thread.start()
    timed_out = False
    try:
        proc.wait(timeout=TOOL_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        timed_out = True
        proc.kill()
        try:
            proc.wait(timeout=REAP_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired as exc:
            raise Reject("tool_reap_unknown") from exc
    for thread in threads:
        thread.join(REAP_TIMEOUT_SECONDS)
    if any(thread.is_alive() for thread in threads):
        raise Reject("tool_reader_unknown")
    if timed_out:
        raise Reject("tool_timeout")
    if proc.returncode != 0:
        raise Reject("tool_nonzero_exit")
    if any(state.get("truncated") for state in (out_state, err_state)):
        raise Reject("tool_output_limit")
    if any(state.get("reader_error") for state in (out_state, err_state)):
        raise Reject("tool_read_error")
    # The elapsed value is deliberately not returned: no command output, paths, or environment are echoed on failure.
    _ = time.monotonic() - started
    return bytes(out + b"\n" + err)


def parse_badging(raw: bytes) -> dict[str, Any]:
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise Reject("badging_not_utf8") from exc
    package_lines = [line for line in text.splitlines() if line.startswith("package:")]
    app_lines = [line for line in text.splitlines() if line.startswith("application:")]
    debug_lines = [line for line in text.splitlines() if line.strip() == "application-debuggable"]
    if len(package_lines) != 1 or len(app_lines) != 1 or len(debug_lines) > 1:
        raise Reject("badging_ambiguous")
    line = package_lines[0]
    fields: dict[str, str] = {}
    for key in ("name", "versionCode", "versionName"):
        matches = re.findall(r"(?:^|\s)" + re.escape(key) + r"='([^']*)'", line)
        if len(matches) != 1:
            raise Reject("badging_field_ambiguous")
        fields[key] = matches[0]
    if set(fields) != {"name", "versionCode", "versionName"} or not fields["name"] or not fields["versionName"]:
        raise Reject("badging_fields_missing")
    if not re.fullmatch(r"[0-9]+", fields["versionCode"]):
        raise Reject("badging_version_code_invalid")
    return {
        "package_name": fields["name"],
        "version_code": int(fields["versionCode"]),
        "version_name": fields["versionName"],
        "debuggable": bool(debug_lines),
    }


def parse_signer(raw: bytes) -> str:
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise Reject("signer_output_not_utf8") from exc
    digests = re.findall(r"^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", text, re.MULTILINE)
    signer_count = re.findall(r"^Number of signers: ([0-9]+)\s*$", text, re.MULTILINE)
    if len(digests) != 1 or digests[0][0] != "1" or (signer_count and (len(signer_count) != 1 or signer_count[0] != "1")):
        raise Reject("signer_count_ambiguous")
    return digests[0][1].lower()


def verify(args: argparse.Namespace) -> dict[str, Any]:
    if os.name != "posix":
        raise Reject("platform_unsupported")
    if args.channel not in ("preview", "release"):
        raise Reject("channel_invalid")
    expected_cert = args.expected_certificate_sha256.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected_cert):
        raise Reject("expected_certificate_invalid")
    if args.expected_version_code < 0 or not args.expected_package_name or not args.expected_version_name:
        raise Reject("expected_identity_invalid")

    apk = Path(args.apk)
    apk_size, apk_sha = stable_hash(apk, max_bytes=MAX_APK_BYTES)
    aapt_path, aapt_info = tool_info(args.aapt2, max_bytes=MAX_TOOL_BYTES)
    signer_path, signer_info = tool_info(args.apksigner, max_bytes=MAX_TOOL_BYTES)
    java_home = Path(args.java_home)
    java_path, java_info = tool_info(str(java_home / "bin" / "java"), max_bytes=MAX_TOOL_BYTES)
    if java_path.parent.parent != java_home.resolve(strict=True):
        raise Reject("java_home_mismatch")
    env = {
        "JAVA_HOME": str(java_home.resolve(strict=True)),
        "PATH": str(java_path.parent) + os.pathsep + "/usr/bin:/bin",
        "LC_ALL": "C",
    }
    java_version_output = run_tool([str(java_path), "-version"], env)
    java_version_line = next((line.strip() for line in java_version_output.decode("utf-8", errors="strict").splitlines() if "version" in line.lower()), None)
    if not java_version_line:
        raise Reject("java_version_unknown")
    java_version = " ".join(java_version_line.split())[:160]
    badging = run_tool([str(aapt_path), "dump", "badging", str(apk)], env)
    actual = parse_badging(badging)
    cert_output = run_tool([str(signer_path), "verify", "--verbose", "--print-certs", str(apk)], env)
    actual_cert = parse_signer(cert_output)
    if actual["package_name"] != args.expected_package_name:
        raise Reject("package_mismatch")
    if actual["version_code"] != args.expected_version_code:
        raise Reject("version_code_mismatch")
    if actual["version_name"] != args.expected_version_name:
        raise Reject("version_name_mismatch")
    if actual_cert != expected_cert:
        raise Reject("certificate_mismatch")
    if args.channel == "release" and actual["debuggable"]:
        raise Reject("release_debuggable")
    final_size, final_sha = stable_hash(apk, max_bytes=MAX_APK_BYTES)
    if apk_size != final_size or apk_sha != final_sha:
        raise Reject("apk_changed_after_inspection")
    if tool_info(args.aapt2, max_bytes=MAX_TOOL_BYTES)[1] != aapt_info:
        raise Reject("aapt2_changed_after_inspection")
    if tool_info(args.apksigner, max_bytes=MAX_TOOL_BYTES)[1] != signer_info:
        raise Reject("apksigner_changed_after_inspection")
    if tool_info(str(java_home / "bin" / "java"), max_bytes=MAX_TOOL_BYTES)[1] != java_info:
        raise Reject("java_changed_after_inspection")
    return {
        "schema": "padnote.android-apk-identity.v1",
        "status": "verified",
        "release_allowed": False,
        "scope": "apk_identity_only",
        "apk": {"name": apk.name, "bytes": apk_size, "sha256": apk_sha},
        "identity": actual,
        "signer_certificate_sha256": actual_cert,
        "expected_signer_certificate_sha256": expected_cert,
        "channel": args.channel,
        "debug_install_notice": "debuggable preview APK" if args.channel == "preview" and actual["debuggable"] else None,
        "tools": {"aapt2": aapt_info, "apksigner": signer_info, "java": {**java_info, "version": java_version}},
        "limitations": [
            "does_not_prove_source_to_apk_build_provenance",
            "does_not_authorize_release_or_publication",
            "does_not_prove_signer_continuity_or_physical_device_upgrade",
        ],
    }


class SafeParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise Reject("arguments_invalid")


def _receipt_identity(st: os.stat_result) -> tuple[int, ...]:
    return (
        int(st.st_dev), int(st.st_ino), int(st.st_mode), int(st.st_nlink),
        int(getattr(st, "st_file_attributes", 0)),
    )


def _remove_owned_receipt(target: Path, expected_identity: tuple[int, ...]) -> None:
    try:
        current = target.lstat()
        if (
            stat.S_ISREG(current.st_mode)
            and not stat.S_ISLNK(current.st_mode)
            and not (getattr(current, "st_file_attributes", 0) & REPARSE_POINT)
            and current.st_nlink == 1
            and _receipt_identity(current) == expected_identity
        ):
            target.unlink()
    except OSError:
        # A changed/missing/unreadable target is preserved; never chase or remove it.
        pass


def write_receipt_create_new(target: Path, encoded: str) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(target, flags, 0o600)
    except OSError as exc:
        raise Reject("receipt_create_failed") from exc
    expected_identity: tuple[int, ...] | None = None
    stream: Any = None
    fd_owned = True
    try:
        expected_identity = _receipt_identity(os.fstat(fd))
        stream = os.fdopen(fd, "w", encoding="utf-8")
        fd_owned = False
        payload = encoded + "\n"
        if stream.write(payload) != len(payload):
            raise OSError("short receipt write")
        stream.flush()
        os.fsync(stream.fileno())
        size, digest = stable_hash(target, max_bytes=64 * 1024)
        expected = payload.encode("utf-8")
        if size != len(expected) or digest != hashlib.sha256(expected).hexdigest():
            raise Reject("receipt_verify_failed")
        stream.close()
        stream = None
    except Exception as exc:
        if expected_identity is not None:
            _remove_owned_receipt(target, expected_identity)
        if stream is not None:
            try:
                stream.close()
            except Exception:
                pass
        elif fd_owned:
            try:
                os.close(fd)
            except OSError:
                pass
        if isinstance(exc, Reject):
            raise
        raise Reject("receipt_create_failed") from exc


def main() -> int:
    parser = SafeParser(description=__doc__)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--java-home", required=True, help="explicit JDK home; Java version and executable are checked without echoing the path")
    parser.add_argument("--expected-certificate-sha256", required=True)
    parser.add_argument("--expected-package-name", required=True)
    parser.add_argument("--expected-version-code", required=True, type=int)
    parser.add_argument("--expected-version-name", required=True)
    parser.add_argument("--channel", required=True, choices=("preview", "release"))
    parser.add_argument("--output-receipt", default=None, help="optional create-new JSON receipt path")
    try:
        args = parser.parse_args()
        result = verify(args)
    except Reject as exc:
        print(json.dumps({"schema": "padnote.android-apk-identity.v1", "status": "rejected", "release_allowed": False, "error": exc.code}, sort_keys=True))
        return 2
    except Exception:
        print(json.dumps({"schema": "padnote.android-apk-identity.v1", "status": "rejected", "release_allowed": False, "error": "internal_error"}, sort_keys=True))
        return 2
    encoded = json.dumps(result, sort_keys=True, separators=(",", ":"))
    if args.output_receipt:
        try:
            write_receipt_create_new(Path(args.output_receipt), encoded)
        except Reject as exc:
            print(json.dumps({"schema": "padnote.android-apk-identity.v1", "status": "rejected", "release_allowed": False, "error": exc.code}, sort_keys=True))
            return 2
    else:
        print(encoded)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
