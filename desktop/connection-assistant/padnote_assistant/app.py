from __future__ import annotations

import argparse
import ctypes
import errno
import json
import os
import re
import sys
import webbrowser
from pathlib import Path

from . import lan
from .bridge import BridgeService
from .state import StateError, StateOwnershipError
from .video_worker import VideoWorkerError
from .web import ServerGroup


def _startup_category(phase: str, error: BaseException) -> str:
    if isinstance(error, StateOwnershipError):
        return "state-in-use"
    if isinstance(error, OSError):
        winerror = getattr(error, "winerror", None)
        if error.errno == errno.EADDRINUSE or winerror == 10048:
            return "port-in-use"
        if error.errno in (errno.EACCES, errno.EPERM) or winerror in (5, 32):
            return "access-denied"
    if phase == "state" and isinstance(error, StateError):
        return "state-invalid"
    return "runtime-error"


def _report_startup_error(phase: str, error: BaseException) -> None:
    category = _startup_category(phase, error)
    record = {
        "phase": phase,
        "category": category,
        "exception": type(error).__name__,
        "errno": getattr(error, "errno", None),
        "winerror": getattr(error, "winerror", None),
    }
    print("PADNOTE_STARTUP_ERROR " + json.dumps(record, separators=(",", ":"),
                                                   ensure_ascii=True), file=sys.stderr)
    messages = {
        "state-in-use": "PadNote Connection Assistant is already using this data directory.",
        "state-invalid": "PadNote Connection Assistant could not open its state.",
        "port-in-use": "PadNote Connection Assistant could not start because a local port is already in use.",
        "access-denied": "PadNote Connection Assistant was denied access to a required local resource.",
        "runtime-error": "PadNote Connection Assistant could not start because of an internal runtime error.",
    }
    if isinstance(error, VideoWorkerError):
        print("PadNote Connection Assistant could not configure the optional video worker.",
              file=sys.stderr)
    else:
        print(messages[category], file=sys.stderr)


def main(argv: list[str] | None = None) -> int:
    if sys.version_info < (3, 10):
        print("PadNote Connection Assistant requires Python 3.10 or newer.", file=sys.stderr)
        return 2
    default_state = os.environ.get(
        "PADNOTE_CONNECTION_STATE_DIR",
        str(Path.home() / ".local/share/padnote-connection-assistant"),
    )
    parser = argparse.ArgumentParser(description="PadNote desktop connection assistant")
    parser.add_argument("--state-dir", default=default_state)
    parser.add_argument("--admin-port", type=int, default=8766)
    parser.add_argument("--api-port", type=int, default=8765)
    parser.add_argument("--video-node", help="absolute path to the administrator-installed Node.js executable")
    parser.add_argument("--video-skill-root", help="absolute path to the installed PadNote video Skill")
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--shutdown-event", help=argparse.SUPPRESS)
    parser.add_argument("--lan", action="store_true",
                        help="accept tablets on the same Wi-Fi over TLS pinned by the pairing QR")
    parser.add_argument("--lan-port", type=int, default=8767)
    args = parser.parse_args(argv)
    if (args.video_node is None) != (args.video_skill_root is None):
        parser.error("--video-node and --video-skill-root must be provided together")
    if args.lan and (args.lan_port in (args.admin_port, args.api_port) or not 1 <= args.lan_port <= 65535):
        parser.error("the local network port must be a distinct valid port")
    if args.admin_port == args.api_port or not (1 <= args.admin_port <= 65535) or not (1 <= args.api_port <= 65535):
        parser.error("admin and API ports must be distinct valid ports")

    service: BridgeService | None = None
    servers: ServerGroup | None = None
    shutdown_handle = None
    phase = "shutdown-event"
    startup_failed = False
    try:
        if args.shutdown_event is not None:
            if os.name != "nt" or not re.fullmatch(
                    r"Local\\PadNoteConnectionAssistantShutdown-[0-9a-f]{32}",
                    args.shutdown_event):
                parser.error("invalid shutdown event")
            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel32.OpenEventW.argtypes = [ctypes.c_uint, ctypes.c_int, ctypes.c_wchar_p]
            kernel32.OpenEventW.restype = ctypes.c_void_p
            kernel32.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_uint]
            kernel32.WaitForSingleObject.restype = ctypes.c_uint
            kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
            shutdown_handle = kernel32.OpenEventW(0x00100000, False, args.shutdown_event)
            if not shutdown_handle:
                raise OSError("could not open the launcher shutdown event")
        else:
            kernel32 = None
        phase = "state"
        service = BridgeService(
            Path(args.state_dir),
            video_node=Path(args.video_node).expanduser() if args.video_node is not None else None,
            video_skill_root=Path(args.video_skill_root).expanduser()
            if args.video_skill_root is not None else None,
        )
        lan_context = None
        if args.lan:
            phase = "lan-identity"
            service.lan_identity = lan.load_or_create_identity(service.store.state_dir)
            service.lan_port = args.lan_port
            lan_context = service.lan_identity.server_context()
        phase = "local-services"
        servers = ServerGroup(service, admin_port=args.admin_port, api_port=args.api_port,
                              lan_port=args.lan_port if args.lan else None,
                              lan_ssl_context=lan_context)
        servers.start()
        print(f"PadNote management UI: {servers.admin_url}", flush=True)
        print(f"PadNote device API (serve this port only): {servers.api_url}", flush=True)
        if args.lan:
            print(f"Same-network tablets: TLS on port {args.lan_port}, certificate "
                  f"{service.lan_identity.sha256[:16]}...", flush=True)
        print("Press Ctrl+C to stop. Secrets and pairing codes are not written to the log.", flush=True)
        if not args.no_browser:
            webbrowser.open(servers.admin_url)
        while True:
            if shutdown_handle and kernel32.WaitForSingleObject(shutdown_handle, 0) == 0:
                break
            for thread in servers._threads:
                thread.join(timeout=0.25)
    except KeyboardInterrupt:
        pass
    except Exception as error:
        startup_failed = True
        _report_startup_error(phase, error)
        return 1
    finally:
        try:
            if servers is not None:
                servers.close()
            elif service is not None:
                service.close()
        except Exception as cleanup_error:
            if not startup_failed:
                raise
            _report_startup_error("local-services", cleanup_error)
        try:
            if shutdown_handle:
                kernel32.CloseHandle(shutdown_handle)
        except Exception as cleanup_error:
            if not startup_failed:
                raise
            _report_startup_error("shutdown-event", cleanup_error)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
