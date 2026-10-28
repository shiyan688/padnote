from __future__ import annotations

import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any

from .profiles import read_hermes_profile
from .security import ValidationError


STARTUP_TIMEOUT = 12.0
POLL_INTERVAL = 0.4
INITIAL_CHECK_TIMEOUT = 8.0
POLL_CHECK_TIMEOUT = 2.0
_started_processes: dict[str, subprocess.Popen] = {}
_process_lock = threading.Lock()
_status_cache: dict[tuple, tuple[float, dict]] = {}


def _windows_candidates() -> tuple[list[Path], list[Path]]:
    """Return only the official Windows install's config and executable paths."""
    if sys.platform != "win32":
        return [], []
    local = os.environ.get("LOCALAPPDATA", "").strip()
    if not local:
        return [], []
    root = Path(local) / "hermes"
    configs = [root / ".env"]
    commands = [
        root / "bin" / "hermes.exe",
        root / "hermes-agent" / "venv" / "Scripts" / "hermes.exe",
    ]
    return configs, commands


def _hermes_candidates(service) -> tuple[list[Path], list[Path]]:
    config_paths: set[Path] = set()
    command_paths: set[Path] = set()
    for item in service.refresh_discovery():
        if item.get("kind") != "hermes":
            continue
        path = Path(item.get("path", "")).expanduser()
        if path.name == ".env" and path.is_file():
            config_paths.add(path.resolve())
        elif item.get("executable") and path.is_file():
            command_paths.add(path.resolve())
        elif path.is_dir():
            # The existing discovery includes ~/.hermes and hermes-agent data dirs.
            for name in ("hermes", "hermes-agent", "hermes.exe"):
                found = path / name
                if found.is_file() and os.access(found, os.X_OK):
                    command_paths.add(found.resolve())

    official_configs, official_commands = _windows_candidates()
    config_paths.update(path.resolve() for path in official_configs if path.is_file())
    command_paths.update(path.resolve() for path in official_commands if path.is_file())
    # The official Windows .env is a valid discovery source for Bridge's guarded importer.
    discovered = list(getattr(service, "discovery_results", []))
    for path in config_paths:
        if not any(Path(item.get("path", "")).expanduser().resolve() == path
                   for item in discovered if item.get("kind") == "hermes"):
            discovered.append({"kind": "hermes", "source": "windows_official",
                               "path": str(path), "present": True, "executable": False,
                               "guidance": "Hermes official Windows installation"})
    service.discovery_results = discovered
    configs = sorted(config_paths)
    commands = sorted(command_paths)
    def install_root(path: Path) -> Path:
        parts = list(path.parts)
        lower = [part.lower() for part in parts]
        for marker in ("hermes", "hermes-agent"):
            if marker in lower:
                index = lower.index(marker)
                if path.is_file() and index == len(parts) - 1:
                    return path.parent
                if marker == "hermes-agent" and index > 0 and lower[index - 1] == "hermes":
                    index -= 1
                return Path(*parts[:index + 1])
        return path.parent if path.suffix.lower() in {".exe", ".cmd", ".bat"} or path.is_file() else path
    # On Windows, Hermes exposes several executable shims for one installation.
    # With one discovered profile they are aliases; prefer the official launcher.
    if len(configs) == 1 and commands:
        home = configs[0].parent
        same_home = [command for command in commands if install_root(command) == install_root(home)]
        if same_home:
            other_homes = [command for command in commands if install_root(command) != install_root(home)]
            commands = same_home + other_homes if other_homes else same_home
        def rank(command: Path) -> tuple[int, str]:
            parts = tuple(part.lower() for part in command.parts)
            if command == home / "bin" / "hermes.exe":
                priority = 0
            elif command == home / "hermes-agent" / "venv" / "Scripts" / "hermes.exe":
                priority = 1
            elif command == home / "hermes-agent" / "venv" / "Scripts" / "hermes-agent.exe":
                priority = 2
            elif command.parent == home or home in command.parents:
                priority = 3
            else:
                priority = 4
            # Known installed roots can have PATH shim aliases outside the profile tree.
            if priority == 4 and ("hermes-agent" in parts or "hermes" in parts):
                priority = 3
            return priority, str(command).lower()
        if len({install_root(command) for command in commands}) == 1:
            commands = [min(commands, key=rank)]
    elif not configs and len(commands) > 1:
        roots = {install_root(command) for command in commands}
        if len(roots) == 1:
            commands = [min(commands, key=lambda command: (
                0 if command.name.lower() == "hermes.exe" and command.parent.name.lower() == "bin" else
                1 if command.name.lower() == "hermes.exe" else 2, str(command).lower()))]
    return configs, commands


def _safe_message(value: Any, secrets: tuple[str, ...] = ()) -> str:
    message = str(value or "")
    for secret in secrets:
        if secret:
            message = message.replace(secret, "[REDACTED]")
    message = re.sub(r"(?i)(api[_ -]?server[_ -]?key\s*[=:]\s*)[^\s,;]+", r"\1[REDACTED]", message)
    message = re.sub(r"(?i)(authorization\s*:\s*bearer\s+)[^\s]+", r"\1[REDACTED]", message)
    return message[:1200]


def _result(status: str, message: str, *, phase: str, instance_id: str | None = None,
            base_url: str | None = None, launched: bool = False,
            candidates: list[str] | None = None) -> dict:
    return {"status": status, "message": message, "phase": phase,
            "instance_id": instance_id, "base_url": base_url,
            "launched": launched, "candidates": candidates or []}


def _profile_map(paths: list[Path]) -> tuple[list[tuple[Path, Any]], list[str]]:
    profiles = []
    errors = []
    for path in paths:
        try:
            profiles.append((path, read_hermes_profile(path)))
        except ValidationError as error:
            errors.append(str(error))
    return profiles, errors


def _check_imported(service, path: Path) -> tuple[dict | None, str | None, str | None]:
    try:
        instance = service.import_discovered_profile(str(path))
        checked = service.check_instance(instance["instance_id"])
        if checked.get("health") == "ready":
            return instance, None, "ready"
        return instance, str(checked.get("detail") or checked.get("health") or "Hermes API check failed"), str(checked.get("health") or "api_error")
    except Exception as error:
        return None, str(error), "check_error"


def _bounded_check(service, path: Path, timeout: float):
    """Bound a synchronous Bridge check so slow DNS/API calls cannot extend the UI request."""
    finished = threading.Event()
    result: list[tuple] = []
    def run():
        try:
            result.append(_check_imported(service, path))
        finally:
            finished.set()
    threading.Thread(target=run, daemon=True).start()
    if not finished.wait(timeout):
        return None, f"Hermes API check exceeded the {timeout:g} second wait limit.", "check_timeout"
    return result[0]


def get_hermes_status(service=None) -> dict:
    """Return cached, non-invasive discovery/configuration status (never probes the API)."""
    global _status_cache
    now = time.monotonic()
    if service is None:
        from . import discovery
        class _DiscoveryOnly:
            discovery_results = []
            def refresh_discovery(self):
                self.discovery_results = discovery.discover()
                return self.discovery_results
        service = _DiscoveryOnly()
    try:
        configs, commands = _hermes_candidates(service)
    except Exception as error:
        return _result("error", _safe_message(error), phase="discovery")
    profile_stats = []
    for path in configs:
        try:
            metadata = path.stat()
            profile_stats.append((str(path), metadata.st_mtime_ns, metadata.st_size))
        except OSError:
            profile_stats.append((str(path), None, None))
    cache_key = (id(service), tuple(profile_stats), tuple(str(path) for path in commands))
    cached = _status_cache.get(cache_key)
    if cached and now - cached[0] < 3.0:
        return dict(cached[1])
    if not configs:
        if len(commands) > 1:
            result = _result("ambiguous", "Multiple Hermes installations were found. Select one installation before configuring its API.", phase="discovery", candidates=[str(p) for p in commands])
        elif commands:
            result = _result("needs_setup", "Hermes is installed, but its API configuration was not found. Configure API_SERVER_ENABLED=true, a loopback API_SERVER_HOST, API_SERVER_PORT, and API_SERVER_KEY in the existing Hermes .env; then retry.", phase="config", candidates=[str(p) for p in commands])
        else:
            result = _result("not_installed", "Hermes was not found in the standard install locations or PATH.", phase="discovery")
        _status_cache[cache_key] = (now, result)
        return result
    profiles, errors = _profile_map(configs)
    if not profiles:
        if len(configs) > 1:
            result = _result("ambiguous", "Multiple Hermes configurations were found. Remove the ambiguity or select one configuration before connecting.", phase="config", candidates=[str(p) for p in configs])
        else:
            result = _result("needs_setup", errors[0] if errors else "Hermes API settings are incomplete.", phase="config", candidates=[str(configs[0])])
        _status_cache[cache_key] = (now, result)
        return result
    if len(profiles) != 1:
        result = _result("ambiguous", "Multiple Hermes API configurations are available. Select one before connecting.", phase="config", candidates=[str(p) for p, _ in profiles])
        _status_cache[cache_key] = (now, result)
        return result
    path, profile = profiles[0]
    instance = None
    if hasattr(service, "store"):
        try:
            instance = next((item for item in service.store.list_instances()
                             if item.get("kind") == "hermes" and item.get("config_ref") == str(path)
                             and not item.get("retired_at")), None)
        except Exception:
            pass
    result = _result("ready" if instance and instance.get("health") == "ready" else "starting",
                     "Hermes API configuration is available." if instance else "Hermes API is configured. Select Check and start to verify it.",
                     phase="config", instance_id=instance.get("instance_id") if instance else None,
                     base_url=profile.base_url)
    _status_cache[cache_key] = (now, result)
    if len(_status_cache) > 32:
        oldest = min(_status_cache, key=lambda key: _status_cache[key][0])
        _status_cache.pop(oldest, None)
    return result


def open_hermes_setup(service) -> dict:
    """Open Hermes' interactive first-run setup in a visible Windows console."""
    try:
        _configs, commands = _hermes_candidates(service)
    except Exception as error:
        return _result("error", _safe_message(error), phase="discovery")
    if not commands:
        return _result("not_installed", "Hermes was not found. Install it using its official installer, then retry.", phase="discovery")
    if len(commands) != 1:
        return _result("ambiguous", "Multiple Hermes executables were found. No setup command was opened.", phase="command", candidates=[str(path) for path in commands])
    if sys.platform != "win32":
        return _result("error", "Interactive setup launch is available on Windows. On macOS or Linux, open a terminal and run `hermes setup`.", phase="platform", candidates=[str(commands[0])])
    try:
        process = subprocess.Popen(
            [str(commands[0]), "setup"],
            shell=False,
            creationflags=getattr(subprocess, "CREATE_NEW_CONSOLE"),
        )
    except (OSError, AttributeError) as error:
        return _result("error", f"Could not open Hermes setup: {_safe_message(error)}", phase="setup", candidates=[str(commands[0])])
    return _result("starting", "Hermes setup opened in a new console. Complete provider login there, then return and check the connection.", phase="setup", launched=True, candidates=[str(commands[0])])


def _write_redacted_log(pipe, log_path: Path, secrets: tuple[str, ...]) -> None:
    try:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with open(log_path, "w", encoding="utf-8") as output:
            try:
                os.chmod(log_path, 0o600)
            except OSError:
                pass
            for raw in iter(pipe.readline, b""):
                # Never persist raw Hermes output: provider credentials other than the
                # API Server key may also appear in diagnostics. Keep only fixed summaries.
                lowered = raw.decode("utf-8", errors="replace").lower()
                if "gateway" in lowered and any(word in lowered for word in ("started", "ready", "listening")):
                    summary = "Hermes Gateway status: ready.\n"
                elif "api" in lowered and any(word in lowered for word in ("started", "ready", "listening")):
                    summary = "Hermes API status: listening.\n"
                elif any(word in lowered for word in ("error", "exception", "failed")):
                    summary = "Hermes Gateway reported an error; details were omitted for privacy.\n"
                else:
                    continue
                output.write(summary)
                output.flush()
    except OSError:
        pass
    finally:
        try:
            pipe.close()
        except OSError:
            pass


def _launch(service, executable: Path, profile) -> tuple[subprocess.Popen | None, str | None]:
    log_path = Path(service.store.state_dir) / "logs" / "hermes-gateway.log"
    kwargs: dict[str, Any] = {"stdin": subprocess.DEVNULL, "stdout": subprocess.DEVNULL,
                              "stderr": subprocess.STDOUT, "shell": False, "cwd": str(executable.parent),
                              "env": {**os.environ, "HERMES_HOME": str(Path(profile.path).parent)}}
    if os.name == "nt":
        kwargs["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    try:
        proc = subprocess.Popen([str(executable), "gateway"], **kwargs)
    except OSError as error:
        return None, _safe_message(error, (profile.api_key,))
    key = profile.path
    with _process_lock:
        _started_processes[key] = proc
    try:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text("Hermes Gateway launch requested; process output is not captured. Check Hermes native diagnostics if needed.\n", encoding="utf-8")
        try:
            os.chmod(log_path, 0o600)
        except OSError:
            pass
    except OSError:
        pass
    return proc, None


def connect_hermes(service) -> dict:
    """Check an existing Hermes API, or launch one unique installed command in background."""
    try:
        configs, commands = _hermes_candidates(service)
    except Exception as error:
        return _result("error", _safe_message(error), phase="discovery")
    if not configs:
        if len(commands) > 1:
            return _result("ambiguous", "Multiple Hermes installations were found. No Gateway was started.", phase="discovery", candidates=[str(p) for p in commands])
        if commands:
            return _result("needs_setup", "Hermes is installed, but API Server is not configured. Preserve the existing .env and add API_SERVER_ENABLED=true, API_SERVER_HOST=127.0.0.1, API_SERVER_PORT=8642, and a strong API_SERVER_KEY, then retry.", phase="config", candidates=[str(p) for p in commands])
        return _result("not_installed", "Hermes was not found. Install it using its official installer, then retry.", phase="discovery")
    if len(configs) > 1:
        return _result("ambiguous", "Multiple Hermes configurations were found. No instance was selected or started.", phase="config", candidates=[str(p) for p in configs])
    path = configs[0]
    try:
        profile = read_hermes_profile(path)
    except ValidationError as error:
        return _result("needs_setup", _safe_message(error), phase="config", candidates=[str(path)])

    # Import and check first. A ready API belongs to the existing service; never start a duplicate.
    instance, error, phase = _bounded_check(service, path, INITIAL_CHECK_TIMEOUT)
    if instance and not error:
        return _result("ready", "Hermes API is already running and responding.", phase="check", instance_id=instance["instance_id"], base_url=instance.get("base_url"))
    if phase != "not_running":
        message = error or "Hermes API did not report that the service is stopped. No duplicate Gateway was started."
        return _result("error", _safe_message(message, (profile.api_key,)), phase=phase or "check",
                       instance_id=instance.get("instance_id") if instance else None,
                       base_url=profile.base_url)

    if len(commands) != 1:
        return _result("ambiguous" if commands else "error",
                       "Multiple Hermes executables were found; no process was started." if commands else "Hermes API is not responding and no Hermes executable was found to start.",
                       phase="command", instance_id=instance.get("instance_id") if instance else None,
                       base_url=profile.base_url, candidates=[str(p) for p in commands])
    executable = commands[0]
    key = profile.path
    with _process_lock:
        existing = _started_processes.get(key)
        if existing is not None and existing.poll() is not None:
            _started_processes.pop(key, None)
            existing = None
    if existing is not None:
        proc = existing
        launched = False
    else:
        proc, launch_error = _launch(service, executable, profile)
        if launch_error:
            return _result("error", f"Could not start Hermes Gateway: {launch_error}", phase="start", instance_id=instance.get("instance_id") if instance else None, base_url=profile.base_url)
        launched = True
    deadline = time.monotonic() + STARTUP_TIMEOUT
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            return _result("error", f"Hermes Gateway exited during startup (exit code {proc.returncode}). See the redacted local log for details.", phase="start", instance_id=instance.get("instance_id") if instance else None, base_url=profile.base_url, launched=True)
        time.sleep(min(POLL_INTERVAL, max(0.0, deadline - time.monotonic())))
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        instance, error, phase = _bounded_check(service, path, min(POLL_CHECK_TIMEOUT, remaining))
        if instance and not error:
            return _result("ready", "Hermes Gateway started and its API is responding.", phase="check", instance_id=instance["instance_id"], base_url=instance.get("base_url"), launched=launched)
        if phase == "check_timeout":
            break
        if phase != "not_running":
            return _result("error", _safe_message(error or "Hermes API responded with an unexpected service state.", (profile.api_key,)), phase=phase or "api", instance_id=instance.get("instance_id") if instance else None, base_url=profile.base_url, launched=launched)
    return _result("starting", "Hermes Gateway was started and is still initializing. Retry the check shortly.", phase=phase or "api", instance_id=instance.get("instance_id") if instance else None, base_url=profile.base_url, launched=launched)
