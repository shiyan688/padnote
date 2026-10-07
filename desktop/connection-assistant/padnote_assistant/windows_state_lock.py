"""Cross-process Windows byte-range locking for the assistant state owner file."""
from __future__ import annotations

import ctypes
import os
import stat
from ctypes import wintypes
from pathlib import Path


class WindowsLockError(OSError):
    pass


class WindowsFileLock:
    """An exclusive LockFileEx lock whose open handle owns its lifetime."""

    _LOCKFILE_EXCLUSIVE_LOCK = 0x00000002
    _LOCKFILE_FAIL_IMMEDIATELY = 0x00000001
    _ERROR_LOCK_VIOLATION = 33

    class _Overlapped(ctypes.Structure):
        _fields_ = [("Internal", ctypes.c_void_p), ("InternalHigh", ctypes.c_void_p),
                    ("Offset", wintypes.DWORD), ("OffsetHigh", wintypes.DWORD),
                    ("hEvent", wintypes.HANDLE)]

    def __init__(self, fd: int, api: object, handle: int, overlapped: ctypes.Structure) -> None:
        self._fd = fd
        self._api = api
        self._handle = handle
        self._overlapped = overlapped
        self._released = False

    @classmethod
    def acquire(cls, path: str | os.PathLike[str]) -> "WindowsFileLock":
        if os.name != "nt":
            raise WindowsLockError("Windows file locking is unavailable")
        lock_path = Path(path)
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        _validate_lock_entry(lock_path)
        flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_BINARY", 0)
        fd = os.open(lock_path, flags, 0o600)
        try:
            opened = os.fstat(fd)
            current = os.lstat(lock_path)
            if not stat.S_ISREG(opened.st_mode) or opened.st_nlink != 1 \
                    or _is_reparse_point(opened) or _is_reparse_point(current) \
                    or not stat.S_ISREG(current.st_mode) or current.st_nlink != 1 \
                    or (opened.st_dev, opened.st_ino) != (current.st_dev, current.st_ino):
                raise WindowsLockError("state ownership file is not a stable regular file")
            import msvcrt
            handle = msvcrt.get_osfhandle(fd)
            api = ctypes.WinDLL("kernel32", use_last_error=True)
            api.LockFileEx.restype = wintypes.BOOL
            api.LockFileEx.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.DWORD,
                                       wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p]
            api.UnlockFileEx.restype = wintypes.BOOL
            api.UnlockFileEx.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.DWORD,
                                         wintypes.DWORD, ctypes.c_void_p]
            overlapped = cls._lock_handle(api, handle)
            return cls(fd, api, handle, overlapped)
        except BaseException:
            os.close(fd)
            raise

    @classmethod
    def _lock_handle(cls, api: object, handle: int) -> ctypes.Structure:
        overlapped = cls._Overlapped()
        flags = cls._LOCKFILE_EXCLUSIVE_LOCK | cls._LOCKFILE_FAIL_IMMEDIATELY
        if not api.LockFileEx(wintypes.HANDLE(handle), flags, 0, 1, 0, ctypes.byref(overlapped)):
            error = ctypes.get_last_error()
            if error == cls._ERROR_LOCK_VIOLATION:
                raise BlockingIOError(error, "state directory is already owned")
            raise WindowsLockError(error, f"LockFileEx failed with Windows error {error}")
        return overlapped

    def release(self) -> None:
        if self._released:
            return
        self._released = True
        try:
            if not self._api.UnlockFileEx(
                    wintypes.HANDLE(self._handle), 0, 1, 0, ctypes.byref(self._overlapped)):
                error = ctypes.get_last_error()
                raise WindowsLockError(error, f"UnlockFileEx failed with Windows error {error}")
        finally:
            os.close(self._fd)


def _is_reparse_point(info: os.stat_result) -> bool:
    attributes = getattr(info, "st_file_attributes", 0)
    return bool(attributes & 0x0400)


def _validate_lock_entry(path: Path) -> None:
    try:
        info = os.lstat(path)
    except FileNotFoundError:
        return
    if _is_reparse_point(info) or not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise WindowsLockError("state ownership file must be a single-link regular file")
