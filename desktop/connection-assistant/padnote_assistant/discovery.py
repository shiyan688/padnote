from __future__ import annotations

import os
import shutil
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class Candidate:
    kind: str
    source: str
    path: str
    present: bool
    executable: bool
    guidance: str

    def to_dict(self) -> dict:
        return asdict(self)


KNOWN_PATHS = {
    "hermes": (
        ".hermes",
        ".hermes/.env",
        ".config/hermes",
        ".config/hermes/.env",
        "hermes-agent",
    ),
    "openclaw": (
        ".openclaw",
        ".config/openclaw",
    ),
}


def _metadata_candidate(kind: str, path: Path) -> Candidate:
    # Deliberately inspect only path metadata. Never open candidate config files.
    present = path.exists()
    return Candidate(
        kind=kind,
        source="known_path",
        path=str(path),
        present=present,
        executable=present and path.is_file() and os.access(path, os.X_OK),
        guidance=(
            "Hermes detected; start its API server and add the loopback URL manually."
            if kind == "hermes"
            else "OpenClaw detected; task execution is not connected in this release."
        ),
    )


def discover(home: Path | None = None, path_value: str | None = None) -> list[dict]:
    home = (home or Path.home()).expanduser()
    results: list[Candidate] = []
    for kind, relative_paths in KNOWN_PATHS.items():
        for relative in relative_paths:
            candidate = _metadata_candidate(kind, home / relative)
            if candidate.present:
                results.append(candidate)

    custom_home = os.environ.get("HERMES_HOME", "").strip()
    if custom_home:
        candidate = _metadata_candidate("hermes", Path(custom_home).expanduser() / ".env")
        if candidate.present:
            results.append(candidate)

    search_path = path_value if path_value is not None else os.environ.get("PATH", "")
    for kind, commands in {"hermes": ("hermes", "hermes-agent"), "openclaw": ("openclaw",)}.items():
        for command in commands:
            resolved = shutil.which(command, path=search_path)
            if resolved:
                results.append(Candidate(
                    kind=kind,
                    source="path_command",
                    path=resolved,
                    present=True,
                    executable=True,
                    guidance=(
                        "Start the Hermes API server, then enter its loopback URL and key."
                        if kind == "hermes"
                        else "OpenClaw discovery is informational; its task adapter is not implemented."
                    ),
                ))

    unique: dict[tuple[str, str], Candidate] = {}
    for candidate in results:
        unique[(candidate.kind, candidate.path)] = candidate
    return [unique[key].to_dict() for key in sorted(unique)]


def discovery_summary(candidates: Iterable[dict]) -> dict:
    values = list(candidates)
    return {
        "hermes": sum(1 for item in values if item.get("kind") == "hermes"),
        "openclaw": sum(1 for item in values if item.get("kind") == "openclaw"),
        "message": "Discovery checks known paths and executable metadata only; no config or credential files are read.",
    }
