from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace

from padnote_assistant.bundles import input_snapshot_digest
from padnote_assistant.wsl_hermes import (
    WslHermesError,
    WslHermesManager,
    WslTaskContext,
    WslTaskFile,
    _GUEST_TASK_CONTEXT,
)


DISTRO_A = "{806d943b-0882-43b5-a010-1f092cfa8b22}"
DISTRO_B = "{906d943b-0882-43b5-a010-1f092cfa8b22}"
TEST_UID = getattr(os, "getuid", lambda: 1000)()


class WslTaskContextTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.tasks = self.root / "tasks"
        self.task_id = "task-qa-1"
        self.conversation_id = "conversation-qa-1"
        self.task_root = self.tasks / self.task_id
        self.input_root = self.task_root / "input"
        self.output_root = self.task_root / "output"
        self.input_root.mkdir(parents=True)
        self.output_root.mkdir()
        self.parent_root = self.tasks / self.conversation_id / "input"
        self.parent_root.mkdir(parents=True)
        self.profile_home = self.root / "home" / ".hermes"
        (self.profile_home / "profiles" / "work").mkdir(parents=True)

    def tearDown(self):
        self.temporary.cleanup()

    def files(self, root: Path) -> tuple[WslTaskFile, ...]:
        return tuple(
            WslTaskFile(p.relative_to(root).as_posix(), p.stat().st_size,
                        hashlib.sha256(p.read_bytes()).hexdigest())
            for p in sorted(root.rglob("*")) if p.is_file()
        )

    def context(self, *, distro_id: str = DISTRO_A, parent: bool = False) -> WslTaskContext:
        inp = self.files(self.input_root)
        parent_files = self.files(self.parent_root) if parent else ()
        return WslTaskContext(
            instance_id="inst-qa-1", instance_binding_sha256="a" * 64,
            distro_id=distro_id, distro_uid=TEST_UID, profile_id="work",
            profile_home=("/home/qa/.hermes/profiles/work" if os.name == "nt"
                          else str((self.profile_home / "profiles" / "work").resolve())), task_id=self.task_id,
            tasks_root=str(self.tasks.resolve()), task_root=str(self.task_root.resolve()), input_files=inp,
            input_digest=input_snapshot_digest(self.input_root),
            conversation_id=self.conversation_id if parent else None,
            parent_input_root=str(self.parent_root.resolve()) if parent else None,
            parent_files=parent_files,
            parent_digest=input_snapshot_digest(self.parent_root) if parent else None,
        )

    @unittest.skipIf(os.name == "nt", "embedded guest helper test uses POSIX paths and a local executable stub")
    def test_guest_projects_unicode_quoted_paths_checks_parent_and_cleans_probe(self):
        special = "資料 $ user's paper.pdf"
        (self.input_root / special).write_bytes(b"synthetic PDF bytes\n")
        (self.input_root / "notes with spaces.md").write_text("synthetic note", encoding="utf-8")
        (self.parent_root / "parent.md").write_text("verified parent", encoding="utf-8")
        context = self.context(parent=True)

        windows_tasks = r"C:\PadNote QA\tasks"
        windows_task = windows_tasks + "\\" + self.task_id
        windows_parent = windows_tasks + "\\" + self.conversation_id + r"\input"
        mapping = {
            windows_tasks: str(self.tasks.resolve()),
            windows_task: str(self.task_root.resolve()),
            windows_parent: str(self.parent_root.resolve()),
        }
        fake_bin = self.root / "fake-bin"
        fake_bin.mkdir()
        wslpath = fake_bin / "wslpath"
        wslpath.write_text(
            "#!/usr/bin/env python3\nimport json,os,sys\n"
            "m=json.loads(os.environ['WSL_TASK_TEST_PATH_MAP'])\n"
            "sys.stdout.write(m[sys.argv[2]]+'\\n')\n", encoding="utf-8")
        wslpath.chmod(0o700)
        script = _GUEST_TASK_CONTEXT.replace('"/usr/bin/wslpath"', json.dumps(str(wslpath)))
        request = {
            "op": "task_context", "instance_id": context.instance_id,
            "instance_binding_sha256": context.instance_binding_sha256,
            "distro_id": DISTRO_A.lower(), "uid": TEST_UID, "profile": "work",
            "profile_home": context.profile_home, "task_id": self.task_id,
            "tasks_root": windows_tasks, "task_root": windows_task,
            "input_files": [item.__dict__ for item in context.input_files],
            "input_digest": context.input_digest, "conversation_id": self.conversation_id,
            "parent_input_root": windows_parent,
            "parent_files": [item.__dict__ for item in context.parent_files],
            "parent_digest": context.parent_digest,
        }
        env = dict(os.environ)
        env["HOME"] = str((self.root / "home").resolve())
        env["WSL_TASK_TEST_PATH_MAP"] = json.dumps(mapping, ensure_ascii=False)

        def run_guest(value):
            return subprocess.run([sys.executable, "-c", script], input=json.dumps(value, ensure_ascii=False).encode(),
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=5, check=False)

        run = run_guest(request)
        self.assertEqual(run.returncode, 0, run.stderr.decode("utf-8", "replace"))
        value = json.loads(run.stdout.decode("utf-8"))
        self.assertTrue(value["ok"], value)
        self.assertEqual(value["task_root"], str(self.task_root.resolve()))
        self.assertEqual(value["input_root"], str(self.input_root.resolve()))
        self.assertEqual(value["output_root"], str(self.output_root.resolve()))
        self.assertEqual(value["parent_input_root"], str(self.parent_root.resolve()))
        self.assertEqual(value["input_digest"], context.input_digest)
        self.assertFalse(value["parent_readonly_enforced"])
        self.assertFalse(any(self.output_root.glob(".padnote-path-probe-*")))

        wrong_uid = dict(request, uid=TEST_UID + 1)
        rejected = json.loads(run_guest(wrong_uid).stdout.decode("utf-8"))
        self.assertEqual(rejected.get("code"), "wsl_task_identity_mismatch")
        escaped = dict(request, input_files=[dict(request["input_files"][0], relative_path="../escape")])
        rejected = json.loads(run_guest(escaped).stdout.decode("utf-8"))
        self.assertEqual(rejected.get("code"), "wsl_task_context_invalid")
        external = self.root / "external.txt"
        external.write_text("outside task root", encoding="utf-8")
        symlink = self.input_root / "linked.txt"
        symlink.symlink_to(external)
        try:
            rejected = json.loads(run_guest(request).stdout.decode("utf-8"))
            self.assertEqual(rejected.get("code"), "wsl_task_context_invalid")
        finally:
            symlink.unlink(missing_ok=True)
        self.assertFalse(any(self.output_root.glob(".padnote-path-probe-*")))

    def test_guest_rejects_changed_input_or_wrong_uid_before_probe(self):
        (self.input_root / "content.md").write_text("original", encoding="utf-8")
        context = self.context()
        (self.input_root / "content.md").write_text("changed", encoding="utf-8")
        self.assertNotEqual(input_snapshot_digest(self.input_root), context.input_digest)
        # Manager performs the host digest check before any guest invocation.
        manager, calls = self.manager(running=True)
        with self.assertRaises(WslHermesError) as caught:
            manager.project_task_context(context)
        self.assertEqual(caught.exception.code, "wsl_task_input_changed")
        self.assertEqual(sum("--distribution" in call for call in calls), 0)


    def test_manager_uses_one_guest_invocation_and_returns_bound_mapping(self):
        (self.input_root / "input name '$.txt").write_text("synthetic", encoding="utf-8")
        context = self.context()
        manager, calls = self.manager(running=True)
        result = manager.project_task_context(context)
        guest_calls = [call for call in calls if "--distribution" in call]
        self.assertEqual(len(guest_calls), 1)
        self.assertEqual(guest_calls[0][-3], "/usr/bin/python3")
        self.assertEqual(result["task_id"], context.task_id)
        self.assertEqual(result["instance_binding_sha256"], context.instance_binding_sha256)
        self.assertEqual(result["input_digest"], context.input_digest)
        self.assertFalse(result["parent_readonly_enforced"])

    def test_manager_refuses_stopped_distro_and_guid_reuse_before_guest(self):
        context = self.context()
        manager, calls = self.manager(running=False)
        with self.assertRaises(WslHermesError) as caught:
            manager.project_task_context(context)
        self.assertEqual(caught.exception.code, "wsl_task_distro_not_running")
        self.assertEqual(sum("--distribution" in call for call in calls), 0)

        manager, calls = self.manager(running=True, change_guid_after_list=True)
        with self.assertRaises(WslHermesError) as caught:
            manager.project_task_context(context)
        self.assertEqual(caught.exception.code, "stale_distro")
        self.assertEqual(sum("--distribution" in call for call in calls), 0)

    def test_task_projection_rechecks_latest_cached_running_state_before_guest(self):
        context = self.context()
        manager, calls = self.manager(running=True)
        selected = manager._selected
        selections = 0
        def stale_after_revalidation(distro_id):
            nonlocal selections
            row = selected(distro_id)
            selections += 1
            if selections == 2:
                # Simulate a concurrent passive refresh after projection selected
                # the running row but before its final task-specific admission.
                with manager._lock:
                    manager._known[distro_id.lower()] = row.__class__(row.id, row.name, "stopped", row.version)
            return row
        manager._selected = stale_after_revalidation
        with self.assertRaises(WslHermesError) as caught:
            manager.project_task_context(context)
        self.assertEqual(caught.exception.code, "wsl_task_distro_not_running")
        self.assertEqual(sum("--distribution" in call for call in calls), 0)

    def test_bounded_run_times_out_while_child_does_not_read_large_stdin(self):
        pid_file = self.root / "nonreader.pid"
        child = "import os,time;open(%r,'w').write(str(os.getpid()));time.sleep(30)" % str(pid_file)
        started = time.monotonic()
        with self.assertRaises(subprocess.TimeoutExpired):
            WslHermesManager._bounded_run(
                [sys.executable, "-c", child], input=b"x" * (1024 * 1024), timeout=0.35)
        elapsed = time.monotonic() - started
        self.assertLess(elapsed, 2.0, f"bounded stdin write took {elapsed:.2f}s")
        self.assertTrue(pid_file.exists(), "child did not start before the bounded wait")
        pid = int(pid_file.read_text(encoding="ascii"))
        if os.name == "nt":
            import ctypes
            from ctypes import wintypes
            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel32.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
            kernel32.OpenProcess.restype = wintypes.HANDLE
            kernel32.GetExitCodeProcess.argtypes = (wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD))
            kernel32.GetExitCodeProcess.restype = wintypes.BOOL
            kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
            kernel32.CloseHandle.restype = wintypes.BOOL
            handle = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
            if handle:
                try:
                    exit_code = wintypes.DWORD()
                    self.assertTrue(kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code)))
                    self.assertNotEqual(exit_code.value, 259, "blocked-stdin child is still active")
                finally:
                    kernel32.CloseHandle(handle)
        else:
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)

    def manager(self, *, running: bool, change_guid_after_list: bool = False):
        calls = []
        registry_calls = 0
        def registry():
            nonlocal registry_calls
            registry_calls += 1
            if change_guid_after_list and registry_calls > 1:
                return {"Ubuntu-24.04": DISTRO_B}
            return {"Ubuntu-24.04": DISTRO_A}
        def runner(args, **kwargs):
            calls.append(list(args))
            if args[-2:] == ["--list", "--quiet"]:
                return SimpleNamespace(returncode=0, stdout="Ubuntu-24.04\r\n".encode("utf-16-le"), stderr=b"")
            if args[-3:] == ["--list", "--running", "--quiet"]:
                names = "Ubuntu-24.04\r\n" if running else ""
                return SimpleNamespace(returncode=0, stdout=names.encode("utf-16-le"), stderr=b"")
            if "--distribution" in args:
                request = json.loads(kwargs["input"].decode("utf-8"))
                value = {
                    "ok": True, "tasks_root": "/mnt/c/PadNote/tasks",
                    "task_root": "/mnt/c/PadNote/tasks/task-qa-1",
                    "input_root": "/mnt/c/PadNote/tasks/task-qa-1/input",
                    "output_root": "/mnt/c/PadNote/tasks/task-qa-1/output",
                    "parent_input_root": None, "parent_digest": None,
                    "parent_readonly_enforced": False, "instance_id": request["instance_id"],
                    "instance_binding_sha256": request["instance_binding_sha256"],
                    "distro_id": DISTRO_A.lower(), "uid": request["uid"],
                    "profile": request["profile"], "profile_home": request["profile_home"],
                    "task_id": request["task_id"], "conversation_id": None,
                    "input_digest": request["input_digest"],
                }
                return SimpleNamespace(returncode=0, stdout=json.dumps(value).encode(), stderr=b"")
            raise AssertionError("unexpected WSL command")
        return WslHermesManager(runner=runner, registry_reader=registry, platform="nt", wsl_executable="wsl.exe"), calls


if __name__ == "__main__":
    unittest.main()
