from __future__ import annotations

import argparse
import os
import sys
import webbrowser
from pathlib import Path

from . import lan
from .bridge import BridgeService
from .state import StateError, StateOwnershipError
from .video_worker import VideoWorkerError
from .web import ServerGroup


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
    try:
        service = BridgeService(
            Path(args.state_dir),
            video_node=Path(args.video_node).expanduser() if args.video_node is not None else None,
            video_skill_root=Path(args.video_skill_root).expanduser()
            if args.video_skill_root is not None else None,
        )
        lan_context = None
        if args.lan:
            service.lan_identity = lan.load_or_create_identity(service.store.state_dir)
            service.lan_port = args.lan_port
            lan_context = service.lan_identity.server_context()
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
            for thread in servers._threads:
                thread.join(timeout=1)
    except KeyboardInterrupt:
        pass
    except StateOwnershipError:
        print("PadNote Connection Assistant is already using this data directory.", file=sys.stderr)
        return 1
    except StateError as error:
        print(f"PadNote Connection Assistant could not open its state: {error}", file=sys.stderr)
        return 1
    except VideoWorkerError:
        print("PadNote Connection Assistant could not configure the optional video worker.",
              file=sys.stderr)
        return 1
    except OSError:
        print("PadNote Connection Assistant could not open its data directory or local ports.", file=sys.stderr)
        return 1
    finally:
        if servers is not None:
            servers.close()
        elif service is not None:
            service.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
