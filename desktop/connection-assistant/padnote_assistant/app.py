from __future__ import annotations

import argparse
import os
import sys
import webbrowser
from pathlib import Path

from .bridge import BridgeService
from .state import StateError, StateOwnershipError
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
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args(argv)
    if args.admin_port == args.api_port or not (1 <= args.admin_port <= 65535) or not (1 <= args.api_port <= 65535):
        parser.error("admin and API ports must be distinct valid ports")

    service: BridgeService | None = None
    servers: ServerGroup | None = None
    try:
        service = BridgeService(Path(args.state_dir))
        servers = ServerGroup(service, admin_port=args.admin_port, api_port=args.api_port)
        servers.start()
        print(f"PadNote management UI: {servers.admin_url}", flush=True)
        print(f"PadNote device API (serve this port only): {servers.api_url}", flush=True)
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
