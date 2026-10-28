#!/usr/bin/env python3
"""Verify a staged PadNote Windows LAN startup in isolated temporary state."""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import platform
import secrets
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parent
STARTUP_TIMEOUT = 60
SHUTDOWN_TIMEOUT = 15


def _port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def _admin() -> bool:
    return bool(ctypes.windll.shell32.IsUserAnAdmin())


def _event(name: str):
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateEventW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_wchar_p]
    kernel.CreateEventW.restype = ctypes.c_void_p
    handle = kernel.CreateEventW(None, True, False, name)
    if not handle:
        raise OSError(ctypes.get_last_error(), "CreateEventW failed")
    return kernel, handle


def _close_event(kernel, handle) -> None:
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    kernel.CloseHandle.restype = ctypes.c_int
    if not kernel.CloseHandle(handle):
        raise OSError(ctypes.get_last_error(), "CloseHandle failed")


def _identity(state: Path) -> tuple[str, str]:
    sys.path.insert(0, str(ROOT))
    from padnote_assistant.lan import load_or_create_identity
    identity = load_or_create_identity(state)
    return identity.sha256, hashlib.sha256(identity.key_path.read_bytes()).hexdigest()


def _run_once(state: Path) -> tuple[str, str]:
    admin, api, lan_port = _port(), _port(), _port()
    while len({admin, api, lan_port}) != 3:
        admin, api, lan_port = _port(), _port(), _port()
    name = "Local\\PadNoteConnectionAssistantShutdown-" + secrets.token_hex(16)
    kernel, handle = _event(name)
    process = None
    logs = []
    primary = None
    operation = "create assistant process"
    try:
        env = os.environ.copy()
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        process = subprocess.Popen(
            [str(Path(sys.executable)), str(ROOT / "start.py"), "--lan", "--no-browser",
             "--state-dir", str(state), "--admin-port", str(admin), "--api-port", str(api),
             "--lan-port", str(lan_port), "--shutdown-event", name],
            cwd=str(ROOT), env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        operation = "wait for local admin HTTP 200 and LAN TLS listener"
        deadline = time.monotonic() + STARTUP_TIMEOUT
        http_ok = tls_ok = False
        while time.monotonic() < deadline:
            if process.poll() is not None:
                break
            try:
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                with opener.open(f"http://127.0.0.1:{admin}/", timeout=1) as response:
                    http_ok = response.status == 200
                if http_ok:
                    operation = "read newly created TLS identity and verify loopback TLS fingerprint"
                    from padnote_assistant.lan import certificate_sha256
                    fingerprint = certificate_sha256(state / "lan-cert.pem")
                    key_digest = hashlib.sha256((state / "lan-key.pem").read_bytes()).hexdigest()
                    context = ssl.create_default_context()
                    context.check_hostname = False
                    context.verify_mode = ssl.CERT_NONE
                    with socket.create_connection(("127.0.0.1", lan_port), timeout=1) as raw:
                        with context.wrap_socket(raw, server_hostname="localhost") as tls:
                            peer = tls.getpeercert(binary_form=True)
                            tls_ok = hashlib.sha256(peer).hexdigest() == fingerprint
                    if tls_ok:
                        break
            except (OSError, ssl.SSLError):
                time.sleep(.25)
        if not http_ok:
            raise RuntimeError("local admin HTTP endpoint did not return 200")
        if not tls_ok:
            raise RuntimeError("loopback TLS certificate fingerprint did not match")
        if process.poll() is not None:
            raise RuntimeError(f"assistant exited during readiness check (code {process.returncode})")
    except Exception as exc:
        primary = (operation, exc)
    finally:
        cleanup_errors = []
        if process is not None:
            try:
                if process.poll() is None:
                    operation = "signal normal shutdown event"
                    kernel.SetEvent.argtypes = [ctypes.c_void_p]
                    kernel.SetEvent.restype = ctypes.c_int
                    if not kernel.SetEvent(handle):
                        cleanup_errors.append("SetEvent failed")
                    operation = "wait for normal assistant shutdown"
                    process.wait(timeout=SHUTDOWN_TIMEOUT)
            except subprocess.TimeoutExpired:
                cleanup_errors.append("normal shutdown timed out after 15 seconds")
                try:
                    process.kill()
                    process.wait(timeout=5)
                except Exception as exc:
                    cleanup_errors.append(_describe(exc, "kill/wait for owned assistant process"))
            except Exception as exc:
                cleanup_errors.append(_describe(exc, operation))
                try:
                    if process.poll() is None:
                        process.kill()
                        process.wait(timeout=5)
                except Exception as kill_exc:
                    cleanup_errors.append(_describe(kill_exc, "kill/wait for owned assistant process"))
        if process is not None:
            try:
                output = process.communicate(timeout=2)[0]
                from launcher_support import sanitize_diagnostics
                logs.append(sanitize_diagnostics(output, 3000))
            except Exception as exc:
                cleanup_errors.append(_describe(exc, "collect bounded process output"))
        try:
            _close_event(kernel, handle)
        except Exception as exc:
            cleanup_errors.append(_describe(exc, "close shutdown event"))
        if primary is not None:
            detail = _describe(primary[1], primary[0])
            if cleanup_errors:
                detail += "; cleanup: " + "; ".join(cleanup_errors)
            if logs and logs[0]:
                detail += "\n" + logs[0]
            raise RuntimeError(detail)
        if cleanup_errors:
            raise RuntimeError("normal shutdown/cleanup failed: " + "; ".join(cleanup_errors))
        if process is None or process.returncode != 0:
            raise RuntimeError(f"assistant did not exit normally (code {process.returncode if process else 'unknown'})")
    return fingerprint, key_digest


def _describe(exc: Exception, operation: str) -> str:
    """Include causal exception types and subprocess return codes, never argv payloads."""
    parts = [operation]
    seen = set()
    current = exc
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        item = type(current).__name__
        if isinstance(current, subprocess.CalledProcessError):
            item += f" (return code {current.returncode})"
            command = current.cmd if isinstance(current.cmd, (list, tuple)) else [current.cmd]
            executable = Path(str(command[0])).name if command else "process"
            flags = [str(part) for part in command[1:] if str(part).startswith("/")
                     and len(str(part)) < 32 and "\\" not in str(part)]
            item += f" from {executable}" + (" " + " ".join(flags) if flags else "")
        elif str(current):
            from launcher_support import sanitize_diagnostics
            item += ": " + sanitize_diagnostics(str(current), 700)
        parts.append(item)
        current = current.__cause__ or current.__context__
    return "; caused by ".join(parts)


def _remove_temp(temporary, report: dict, checks: list) -> None:
    try:
        temporary.cleanup()
        report["cleanup"] = "temporary state removed"
    except Exception as exc:
        report["cleanup"] = "FAIL: " + _describe(exc, "remove temporary state")[:1200]
        checks.append({"name": "temporary state cleanup", "result": "FAIL",
                       "detail": report["cleanup"]})


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True, help="write a JSON acceptance report")
    args = parser.parse_args(argv)
    report = {"tool": "PadNote Windows startup verifier", "build": "2026-10-28-r2",
              "checks": [], "cleanup": "not started"}
    checks = report["checks"]

    def check(name, fn):
        try:
            detail = fn()
            checks.append({"name": name, "result": "PASS", "detail": detail or "completed"})
            return detail
        except Exception as exc:
            checks.append({"name": name, "result": "FAIL",
                           "detail": _describe(exc, name)[:4000]})
            return None

    if os.name != "nt":
        report.update({"result": "FAIL", "python": sys.version.split()[0], "platform": platform.platform(),
                       "cleanup": "not applicable"})
        checks.append({"name": "Windows platform", "result": "FAIL", "detail": "Windows is required"})
    else:
        try:
            elevated = _admin()
        except Exception as exc:
            elevated = True
            checks.append({"name": "ordinary user", "result": "FAIL", "detail": f"could not verify elevation: {type(exc).__name__}"})
        if elevated:
            report.update({"result": "FAIL", "python": sys.version.split()[0], "platform": platform.platform(),
                           "cleanup": "not started"})
            checks.append({"name": "ordinary user", "result": "FAIL", "detail": "Run from a non-elevated account"})
        else:
            report.update({"python": sys.version.split()[0], "platform": platform.platform(), "user_context": "non-elevated"})
            check("ordinary user", lambda: "non-elevated Windows account confirmed")
            temporary = None
            try:
                temporary = tempfile.TemporaryDirectory(prefix="padnote-startup-verify-")
                root = Path(temporary.name)
                acl_state = root / "identity-check"
                acl_state.mkdir()
                check("fresh identity and Windows ACL",
                      lambda: (_identity(acl_state), "identity created; Windows ACL verification passed")[1])
                state = root / "assistant-state"
                state.mkdir()
                # Keep this directory empty so the actual assistant first-start creates its identity.
                try:
                    first_fp, first_key = _run_once(state)
                    checks.append({"name": "fresh LAN startup, HTTP/TLS, normal shutdown", "result": "PASS",
                                   "detail": "empty state; HTTP 200; TLS fingerprint matched; exit code 0"})
                except Exception as exc:
                    checks.append({"name": "fresh LAN startup, HTTP/TLS, normal shutdown", "result": "FAIL",
                                   "detail": _describe(exc, "fresh assistant startup")[:4000]})
                else:
                    def second_start():
                        fp, key = _run_once(state)
                        if fp != first_fp or key != first_key:
                            raise RuntimeError("certificate fingerprint or private-key digest changed on restart")
                        return "fingerprint and private-key digest stable; exit code 0"
                    check("repeat startup identity stability", second_start)
            except Exception as exc:
                checks.append({"name": "isolated temporary state", "result": "FAIL",
                               "detail": _describe(exc, "create or use isolated temporary state")[:4000]})
            finally:
                if temporary is not None:
                    _remove_temp(temporary, report, checks)
    report["result"] = "PASS" if checks and all(item["result"] == "PASS" for item in checks) else "FAIL"
    try:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except Exception as exc:
        print(f"FAIL: could not write report ({type(exc).__name__})", file=sys.stderr)
        return 1
    print(f"{report['result']}: report written to {args.report}")
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
