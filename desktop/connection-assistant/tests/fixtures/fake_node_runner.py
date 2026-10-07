"""Test-only bridge from a fixture script to VideoWorker's real process path."""
from __future__ import annotations

import hashlib
import os
import sys
from pathlib import Path
from typing import Any, Callable


def install_fake_node_runner(worker: Any, configured_node: Path, fixture: Path, *,
                             platform_is_windows: Callable[[], bool] | None = None) -> None:
    """Run only the verified fake Node fixture via Python on Windows.

    The worker still owns Popen, stdin/stdout bounds, deadlines, and its native
    Windows Job Object. Real Node paths and all non-matching commands pass
    through unchanged.
    """
    configured = os.path.normcase(os.path.abspath(str(configured_node)))
    fixture_hash = hashlib.sha256(fixture.read_bytes()).digest()
    if hashlib.sha256(configured_node.read_bytes()).digest() != fixture_hash:
        raise AssertionError("configured fake Node fixture bytes changed")
    original = worker._run_process
    windows = platform_is_windows or (lambda: os.name == "nt")

    def run_process(argv, cwd, env, deadline, operation, *args, **kwargs):
        command = list(argv)
        if windows() and command and os.path.normcase(os.path.abspath(str(command[0]))) == configured:
            if hashlib.sha256(configured_node.read_bytes()).digest() != fixture_hash:
                raise AssertionError("configured fake Node path no longer contains the approved fixture")
            command = [sys.executable, str(configured_node), *command[1:]]
        return original(command, cwd, env, deadline, operation, *args, **kwargs)

    worker._run_process = run_process
