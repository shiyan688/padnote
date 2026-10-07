"""Small OS boundary for owning a one-shot child process tree.

Windows uses a kill-on-close Job Object. POSIX keeps its existing new-session
process-group behavior. The Windows implementation deliberately has no
taskkill/PID-only fallback: if the OS refuses job assignment, startup fails.
"""
from __future__ import annotations

import ctypes
import os
import subprocess
from ctypes import wintypes


class ProcessTreeError(OSError):
    pass


class WindowsJob:
    """Own child processes through a Windows Job Object until close."""

    _JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000
    _JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9

    def __init__(self, process: subprocess.Popen) -> None:
        if os.name != "nt":
            raise ProcessTreeError("Windows Job Objects are unavailable")
        self._kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self._kernel32.CreateJobObjectW.restype = wintypes.HANDLE
        self._kernel32.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        self._kernel32.SetInformationJobObject.restype = wintypes.BOOL
        self._kernel32.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int,
                                                           ctypes.c_void_p, wintypes.DWORD]
        self._kernel32.AssignProcessToJobObject.restype = wintypes.BOOL
        self._kernel32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        self._kernel32.TerminateJobObject.restype = wintypes.BOOL
        self._kernel32.TerminateJobObject.argtypes = [wintypes.HANDLE, wintypes.UINT]
        self._kernel32.CloseHandle.restype = wintypes.BOOL
        self._kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
        self._handle = self._kernel32.CreateJobObjectW(None, None)
        if not self._handle:
            raise self._error("CreateJobObjectW")
        try:
            self._configure_kill_on_close()
            if not self._kernel32.AssignProcessToJobObject(
                    wintypes.HANDLE(self._handle), wintypes.HANDLE(int(process._handle))):
                raise self._error("AssignProcessToJobObject")
            self._resume_primary_thread(process.pid)
        except BaseException:
            self.close()
            raise

    def _configure_kill_on_close(self) -> None:
        class IO_COUNTERS(ctypes.Structure):
            _fields_ = [(name, ctypes.c_ulonglong) for name in (
                "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]

        class BASIC_LIMIT(ctypes.Structure):
            _fields_ = [("PerProcessUserTimeLimit", ctypes.c_longlong),
                        ("PerJobUserTimeLimit", ctypes.c_longlong),
                        ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                        ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                        ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD),
                        ("SchedulingClass", wintypes.DWORD)]

        class EXTENDED_LIMIT(ctypes.Structure):
            _fields_ = [("BasicLimitInformation", BASIC_LIMIT), ("IoInfo", IO_COUNTERS),
                        ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                        ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]

        limits = EXTENDED_LIMIT()
        limits.BasicLimitInformation.LimitFlags = self._JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self._kernel32.SetInformationJobObject(
                wintypes.HANDLE(self._handle), self._JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                ctypes.byref(limits), ctypes.sizeof(limits)):
            raise self._error("SetInformationJobObject")

    def terminate(self, exit_code: int = 1) -> None:
        if not self._handle or not self.active_processes():
            return
        if not self._kernel32.TerminateJobObject(wintypes.HANDLE(self._handle), exit_code):
            error = ctypes.get_last_error()
            raise self._error("TerminateJobObject", error)

    def active_processes(self) -> int:
        class ACCOUNTING(ctypes.Structure):
            _fields_ = [("TotalUserTime", ctypes.c_longlong), ("TotalKernelTime", ctypes.c_longlong),
                        ("ThisPeriodTotalUserTime", ctypes.c_longlong),
                        ("ThisPeriodTotalKernelTime", ctypes.c_longlong),
                        ("TotalPageFaultCount", wintypes.DWORD), ("TotalProcesses", wintypes.DWORD),
                        ("ActiveProcesses", wintypes.DWORD), ("TotalTerminatedProcesses", wintypes.DWORD)]

        self._kernel32.QueryInformationJobObject.restype = wintypes.BOOL
        self._kernel32.QueryInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int,
                                                              ctypes.c_void_p, wintypes.DWORD,
                                                              ctypes.c_void_p]
        info = ACCOUNTING()
        if not self._kernel32.QueryInformationJobObject(
                wintypes.HANDLE(self._handle), 1, ctypes.byref(info), ctypes.sizeof(info), None):
            raise self._error("QueryInformationJobObject")
        return int(info.ActiveProcesses)

    def close(self) -> None:
        handle, self._handle = getattr(self, "_handle", None), None
        if handle:
            if not self._kernel32.CloseHandle(wintypes.HANDLE(handle)):
                raise self._error("CloseHandle")

    def _resume_primary_thread(self, pid: int) -> None:
        """Resume the one suspended initial thread after job assignment."""
        class THREADENTRY32(ctypes.Structure):
            _fields_ = [("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD),
                        ("th32ThreadID", wintypes.DWORD), ("th32OwnerProcessID", wintypes.DWORD),
                        ("tpBasePri", wintypes.LONG), ("tpDeltaPri", wintypes.LONG),
                        ("dwFlags", wintypes.DWORD)]

        kernel = self._kernel32
        kernel.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
        kernel.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
        kernel.Thread32First.restype = wintypes.BOOL
        kernel.Thread32First.argtypes = [wintypes.HANDLE, ctypes.c_void_p]
        kernel.Thread32Next.restype = wintypes.BOOL
        kernel.Thread32Next.argtypes = [wintypes.HANDLE, ctypes.c_void_p]
        kernel.OpenThread.restype = wintypes.HANDLE
        kernel.OpenThread.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.ResumeThread.restype = wintypes.DWORD
        kernel.ResumeThread.argtypes = [wintypes.HANDLE]
        kernel.CloseHandle.restype = wintypes.BOOL
        snapshot = kernel.CreateToolhelp32Snapshot(0x00000004, 0)
        invalid = ctypes.c_void_p(-1).value
        if not snapshot or snapshot == invalid:
            raise self._error("CreateToolhelp32Snapshot")
        try:
            entry = THREADENTRY32()
            entry.dwSize = ctypes.sizeof(entry)
            found = kernel.Thread32First(snapshot, ctypes.byref(entry))
            while found:
                if entry.th32OwnerProcessID == pid:
                    thread = kernel.OpenThread(0x0002, False, entry.th32ThreadID)
                    if not thread:
                        raise self._error("OpenThread")
                    try:
                        if kernel.ResumeThread(thread) == 0xFFFFFFFF:
                            raise self._error("ResumeThread")
                    finally:
                        kernel.CloseHandle(thread)
                    return
                found = kernel.Thread32Next(snapshot, ctypes.byref(entry))
        finally:
            kernel.CloseHandle(snapshot)
        raise ProcessTreeError("suspended process has no initial thread")

    @staticmethod
    def _error(api: str, code: int | None = None) -> ProcessTreeError:
        value = ctypes.get_last_error() if code is None else code
        return ProcessTreeError(value, f"{api} failed with Windows error {value}")


def popen_tree_kwargs() -> dict[str, object]:
    if os.name == "posix":
        return {"start_new_session": True}
    if os.name == "nt":
        flags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x00000200)
        flags |= getattr(subprocess, "CREATE_SUSPENDED", 0x00000004)
        return {"creationflags": flags}
    raise ProcessTreeError("process tree control is unsupported on this platform")
