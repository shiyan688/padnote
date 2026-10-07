"""OS-level process liveness helpers for process-ownership tests."""
from __future__ import annotations

import ctypes
import os
from pathlib import Path

ERROR_ACCESS_DENIED = 5
ERROR_INVALID_PARAMETER = 87
WAIT_OBJECT_0 = 0
WAIT_TIMEOUT = 0x00000102
WAIT_FAILED = 0xFFFFFFFF


def _windows_process_is_alive(pid: int, kernel32, get_last_error) -> bool:
    handle = kernel32.OpenProcess(0x00100000, 0, pid)  # SYNCHRONIZE
    if not handle:
        error = int(get_last_error())
        if error == ERROR_INVALID_PARAMETER:
            return False
        if error == ERROR_ACCESS_DENIED:
            return True
        raise OSError(error, "OpenProcess failed")
    try:
        result = int(kernel32.WaitForSingleObject(handle, 0))
        if result == WAIT_OBJECT_0:
            return False
        if result == WAIT_TIMEOUT:
            return True
        if result == WAIT_FAILED:
            raise OSError(int(get_last_error()), "WaitForSingleObject failed")
        raise RuntimeError("WaitForSingleObject returned an unexpected status")
    finally:
        if not kernel32.CloseHandle(handle):
            raise OSError(int(get_last_error()), "CloseHandle failed")


def process_is_alive(pid: int) -> bool:
    if os.name == "nt":
        kernel32 = ctypes.WinDLL("kernel32.dll", use_last_error=True)
        kernel32.OpenProcess.argtypes = [ctypes.c_uint32, ctypes.c_int, ctypes.c_uint32]
        kernel32.OpenProcess.restype = ctypes.c_void_p
        kernel32.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
        kernel32.WaitForSingleObject.restype = ctypes.c_uint32
        kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
        kernel32.CloseHandle.restype = ctypes.c_int
        return _windows_process_is_alive(pid, kernel32, ctypes.get_last_error)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    proc_stat = Path(f"/proc/{pid}/stat")
    if proc_stat.exists():
        try:
            if proc_stat.read_text().split()[2] == "Z":
                return False
        except (OSError, IndexError):
            pass
    return True
