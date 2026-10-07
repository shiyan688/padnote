from __future__ import annotations

import ctypes
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from padnote_assistant.windows_state_lock import WindowsFileLock, WindowsLockError, _validate_lock_entry


class _LockApi:
    def __init__(self, result):
        self.result = result
        self.call = None
        self.LockFileEx = self

    def __call__(self, *args):
        self.call = args
        return self.result


class WindowsStateLockTests(unittest.TestCase):
    def test_locks_exact_first_byte_exclusively_and_nonblocking(self):
        api = _LockApi(1)
        overlap = WindowsFileLock._lock_handle(api, 123)
        handle, flags, reserved, low, high, pointer = api.call
        self.assertEqual(123, handle.value)
        self.assertEqual(3, flags)  # exclusive + fail immediately
        self.assertEqual((0, 1, 0), (reserved, low, high))
        self.assertEqual(ctypes.addressof(overlap), ctypes.addressof(pointer._obj))

    def test_reports_busy_owner_without_falling_back_to_pid_checks(self):
        api = _LockApi(0)
        with patch("padnote_assistant.windows_state_lock.ctypes.get_last_error", return_value=33,
                   create=True):
            with self.assertRaises(BlockingIOError):
                WindowsFileLock._lock_handle(api, 123)

    @unittest.skipUnless(os.name == "nt", "native LockFileEx integration test")
    def test_lock_lifetime_blocks_other_process_and_releases(self):
        with tempfile.TemporaryDirectory(prefix="PadNote lock 中文 space ") as directory:
            lock_path = Path(directory) / "owner.lock"
            child_code = (
                "import sys\n"
                "from padnote_assistant.windows_state_lock import WindowsFileLock\n"
                "try:\n"
                " lock=WindowsFileLock.acquire(sys.argv[1])\n"
                "except BlockingIOError:\n"
                " raise SystemExit(23)\n"
                "else:\n"
                " lock.release()\n"
            )
            child_env = {key: os.environ[key] for key in
                         ("SystemRoot", "PATH", "TEMP", "TMP", "PYTHONPATH")
                         if key in os.environ}

            def try_from_child() -> subprocess.CompletedProcess[str]:
                return subprocess.run(
                    [sys.executable, "-c", child_code, str(lock_path)],
                    cwd=str(Path(__file__).resolve().parents[1]), env=child_env,
                    stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE, text=True, timeout=15, check=False)

            owner = WindowsFileLock.acquire(lock_path)
            try:
                self.assertEqual(23, try_from_child().returncode)
            finally:
                owner.release()
            self.assertEqual(0, try_from_child().returncode)

    def test_owner_entry_rejects_symlinks_and_hardlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "target"
            target.write_text("", encoding="utf-8")
            symlink = root / "owner-link"
            symlink.symlink_to(target)
            with self.assertRaises(WindowsLockError):
                _validate_lock_entry(symlink)
            hardlink = root / "owner-hardlink"
            os.link(target, hardlink)
            with self.assertRaises(WindowsLockError):
                _validate_lock_entry(hardlink)


if __name__ == "__main__":
    unittest.main()
