from __future__ import annotations

import ctypes
import os
import queue
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from padnote_assistant import video_process, video_worker


class _Api:
    def __init__(self, name, callback):
        self.name = name
        self.callback = callback

    def __call__(self, *args):
        return self.callback(*args)


class _Kernel:
    def __init__(self, events):
        self.events = events
        self.CreateJobObjectW = _Api("CreateJobObjectW", lambda *_: 11)
        self.SetInformationJobObject = _Api("SetInformationJobObject", self._set_info)
        self.AssignProcessToJobObject = _Api("AssignProcessToJobObject", self._assign)
        self.TerminateJobObject = _Api("TerminateJobObject", self._terminate)
        self.CloseHandle = _Api("CloseHandle", self._close)
        self.CreateToolhelp32Snapshot = _Api("CreateToolhelp32Snapshot", lambda *_: 22)
        self.Thread32First = _Api("Thread32First", self._thread_first)
        self.Thread32Next = _Api("Thread32Next", lambda *_: 0)
        self.OpenThread = _Api("OpenThread", lambda *_: (events.append(("open-thread",)), 33)[1])
        self.ResumeThread = _Api("ResumeThread", lambda *_: (events.append(("resume",)), 1)[1])

    def _set_info(self, *_):
        self.events.append(("configure",))
        return 1

    def _close(self, handle):
        self.events.append(("close", getattr(handle, "value", handle)))
        return 1

    def _assign(self, _job, process):
        self.events.append(("assign", process.value))
        return 1

    def _thread_first(self, _snapshot, entry_ptr):
        class THREADENTRY32(ctypes.Structure):
            _fields_ = [("dwSize", video_process.wintypes.DWORD),
                        ("cntUsage", video_process.wintypes.DWORD),
                        ("th32ThreadID", video_process.wintypes.DWORD),
                        ("th32OwnerProcessID", video_process.wintypes.DWORD),
                        ("tpBasePri", video_process.wintypes.LONG),
                        ("tpDeltaPri", video_process.wintypes.LONG),
                        ("dwFlags", video_process.wintypes.DWORD)]
        entry = ctypes.cast(entry_ptr, ctypes.POINTER(THREADENTRY32)).contents
        self.events.append(("thread-enumerated", entry.dwSize))
        entry.th32ThreadID = 55
        entry.th32OwnerProcessID = 123
        return 1

    def _terminate(self, _job, exit_code):
        self.events.append(("terminate", exit_code))
        return 1


class VideoProcessTests(unittest.TestCase):
    def test_windows_start_is_suspended_until_job_assignment(self):
        events = []
        kernel = _Kernel(events)
        process = type("Process", (), {"pid": 123, "_handle": 44})()
        with patch.object(video_process.os, "name", "nt"), \
             patch.object(video_process.ctypes, "WinDLL", return_value=kernel, create=True):
            self.assertIn("creationflags", video_process.popen_tree_kwargs())
            flags = video_process.popen_tree_kwargs()["creationflags"]
            self.assertEqual(0x204, flags)
            job = video_process.WindowsJob(process)
            self.assertEqual(["configure", "assign", "thread-enumerated", "open-thread", "resume"],
                             [item[0] for item in events[:5]])
            job.close()
            self.assertEqual("close", events[-1][0])

    @unittest.skipUnless(os.name == "nt", "native Windows Job Object integration test")
    def test_job_contains_and_terminates_spawned_grandchild(self):
        with tempfile.TemporaryDirectory(prefix="PadNote Job 中文 space ") as directory:
            marker = os.path.join(directory, "child-pids.json")
            grandchild_code = "import time; time.sleep(120)"
            parent_code = (
                "import json,subprocess,sys,time\n"
                "child=subprocess.Popen([sys.executable,'-c',sys.argv[2]])\n"
                "open(sys.argv[1],'w',encoding='utf-8').write(json.dumps([child.pid]))\n"
                "time.sleep(120)\n"
            )
            child_env = {key: os.environ[key] for key in
                         ("SystemRoot", "PATH", "TEMP", "TMP", "PYTHONPATH")
                         if key in os.environ}
            process = subprocess.Popen(
                [sys.executable, "-c", parent_code, marker, grandchild_code],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, env=child_env, **video_process.popen_tree_kwargs())
            job = None
            try:
                job = video_process.WindowsJob(process)
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline and not os.path.exists(marker):
                    time.sleep(0.02)
                self.assertTrue(os.path.isfile(marker), "parent did not launch its grandchild")
                self.assertGreaterEqual(job.active_processes(), 2)
                job.terminate(73)
                process.wait(timeout=15)
                deadline = time.monotonic() + 10
                while job.active_processes() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertEqual(0, job.active_processes(),
                                 "terminating the job must reap every descendant")
            finally:
                if job is not None:
                    job.close()
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=10)

    def test_windows_pipe_flood_is_backpressured_and_reader_stops_during_cleanup(self):
        class Flood:
            def read(self, _size):
                return b"x" * 16_384

        output = queue.Queue(maxsize=1)
        stop = threading.Event()
        thread = threading.Thread(target=video_worker._read_pipe_chunks,
                                  args=(Flood(), bytearray(), output, 16_384, stop), daemon=True)
        thread.start()
        deadline = time.monotonic() + 1
        while output.qsize() == 0 and time.monotonic() < deadline:
            time.sleep(0.005)
        self.assertEqual(1, output.qsize())
        stop.set()
        thread.join(timeout=1)
        self.assertFalse(thread.is_alive())
        self.assertLessEqual(output.qsize(), 1)


if __name__ == "__main__":
    unittest.main()
