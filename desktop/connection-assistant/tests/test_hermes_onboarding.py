from __future__ import annotations

import os
import signal
import io
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

from padnote_assistant import hermes_onboarding as onboarding


class FakeStore:
    state_dir = Path(tempfile.gettempdir()) / "padnote-onboarding-tests"

    def list_instances(self):
        return []


class FakeService:
    def __init__(self, configs=(), commands=(), *, check_results=()):
        self.discovery_results = ([{"kind": "hermes", "source": "known_path", "path": str(p),
                                   "present": True, "executable": False, "guidance": ""}
                                  for p in configs]
                                 + [{"kind": "hermes", "source": "path_command", "path": str(p),
                                     "present": True, "executable": True, "guidance": ""}
                                    for p in commands])
        self.store = FakeStore()
        self.check_results = iter(check_results)
        self.imported = []
        self.check_calls = 0

    def refresh_discovery(self):
        return list(self.discovery_results)

    def import_discovered_profile(self, path):
        self.imported.append(path)
        return {"instance_id": "instance-safe-id", "base_url": "http://127.0.0.1:8642"}

    def check_instance(self, _instance_id):
        self.check_calls += 1
        result = next(self.check_results)
        if isinstance(result, Exception):
            raise result
        return result


def write_env(path: Path, *, enabled="true", key="test-secret") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"API_SERVER_ENABLED={enabled}\nAPI_SERVER_HOST=127.0.0.1\nAPI_SERVER_PORT=8642\nAPI_SERVER_KEY={key}\n", encoding="utf-8")
    return path


class HermesOnboardingTests(unittest.TestCase):
    def setUp(self):
        local_app_data = tempfile.TemporaryDirectory()
        self.addCleanup(local_app_data.cleanup)
        local_app_data_patch = patch.dict(os.environ, {"LOCALAPPDATA": local_app_data.name})
        local_app_data_patch.start()
        self.addCleanup(local_app_data_patch.stop)
        onboarding._status_cache.clear()
        onboarding._started_processes.clear()

    def test_official_windows_install_is_found_without_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "hermes"
            config = write_env(root / ".env")
            executable = root / "bin" / "hermes.exe"
            executable.parent.mkdir(parents=True)
            executable.write_bytes(b"stub")
            service = FakeService()
            with patch.object(onboarding.sys, "platform", "win32"), patch.dict(os.environ, {"LOCALAPPDATA": directory}):
                configs, commands = onboarding._hermes_candidates(service)
            self.assertEqual(configs, [config.resolve()])
            self.assertEqual(commands, [executable.resolve()])
            self.assertFalse(any(item.get("source") == "path_command" for item in service.discovery_results))

    def test_running_api_is_checked_without_starting_a_process(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env")
            executable = Path(directory) / "hermes"
            executable.touch()
            service = FakeService([config], [executable], check_results=[{"health": "ready"}])
            with patch.object(onboarding.subprocess, "Popen") as popen:
                result = onboarding.connect_hermes(service)
            popen.assert_not_called()
            self.assertEqual(result["status"], "ready")
            self.assertEqual(result["instance_id"], "instance-safe-id")

    def test_missing_api_configuration_returns_setup_guidance_without_import(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env", enabled="false", key="do-not-leak")
            service = FakeService([config], [Path(directory) / "hermes"])
            result = onboarding.connect_hermes(service)
            self.assertEqual(result["status"], "needs_setup")
            self.assertIn("API_SERVER_ENABLED", result["message"])
            self.assertNotIn("do-not-leak", str(result))
            self.assertEqual(service.imported, [])

    def test_multiple_configurations_are_not_blindly_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            first = write_env(Path(directory) / "one" / ".env")
            second = write_env(Path(directory) / "two" / ".env", key="another-secret")
            service = FakeService([first, second], [Path(directory) / "hermes"])
            result = onboarding.connect_hermes(service)
            self.assertEqual(result["status"], "ambiguous")
            self.assertEqual(service.imported, [])

    def test_start_failure_reports_phase_and_redacts_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env", key="super-secret")
            executable = Path(directory) / "hermes"
            executable.touch()
            service = FakeService([config], [executable], check_results=[{"health": "not_running"}])
            with patch.object(onboarding.subprocess, "Popen", side_effect=OSError("denied: super-secret")):
                result = onboarding.connect_hermes(service)
            self.assertEqual(result["status"], "error")
            self.assertEqual(result["phase"], "start")
            self.assertNotIn("super-secret", str(result))

    def test_gateway_log_redacts_api_key(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "gateway.log"
            onboarding._write_redacted_log(io.BytesIO(b"gateway failed key=log-secret\n"), log, ("log-secret",))
            contents = log.read_text(encoding="utf-8")
            self.assertNotIn("log-secret", contents)
            self.assertNotIn("key=", contents)
            self.assertIn("details were omitted", contents)

    def test_gateway_process_is_background_and_uses_no_shell(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env")
            executable = Path(directory) / "hermes"
            executable.touch()
            class Proc:
                stdout = None
                returncode = None
                def poll(self): return None
            profile = onboarding.read_hermes_profile(config)
            with patch.object(onboarding.subprocess, "Popen", return_value=Proc()) as popen, \
                    patch.object(onboarding.threading, "Thread") as thread:
                process, error = onboarding._launch(FakeService(), executable, profile)
            self.assertIsNone(error)
            self.assertIsNotNone(process)
            self.assertEqual(popen.call_args.args[0], [str(executable), "gateway"])
            self.assertFalse(popen.call_args.kwargs["shell"])
            self.assertIs(popen.call_args.kwargs["stdout"], subprocess.DEVNULL)
            self.assertIs(popen.call_args.kwargs["stderr"], subprocess.STDOUT)
            self.assertEqual(popen.call_args.kwargs["env"]["HERMES_HOME"], str(Path(profile.path).parent))
            thread.assert_not_called()

    def test_noisy_child_completes_after_parent_exits_with_devnull_output(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "completed"
            code = (
                "import pathlib,sys,time; "
                "time.sleep(0.25); "
                "sys.stdout.buffer.write(b'x' * (1024 * 1024)); sys.stdout.flush(); "
                "sys.stderr.buffer.write(b'y' * (1024 * 1024)); sys.stderr.flush(); "
                "pathlib.Path(sys.argv[1]).write_text('done', encoding='utf-8')"
            )
            parent_code = (
                "import subprocess,sys; "
                "p=subprocess.Popen([sys.executable,'-c',sys.argv[1],sys.argv[2]], "
                "stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.STDOUT, "
                "close_fds=True); print(p.pid, flush=True)"
            )
            parent = subprocess.run([sys.executable, "-c", parent_code, code, str(marker)],
                                    check=True, timeout=5, capture_output=True, text=True)
            child_pid = int(parent.stdout.strip())
            try:
                deadline = __import__("time").monotonic() + 4
                while not marker.exists() and __import__("time").monotonic() < deadline:
                    __import__("time").sleep(0.05)
                self.assertTrue(marker.exists(), "noisy child did not finish after parent exit")
                self.assertEqual(marker.read_text(encoding="utf-8"), "done")
            finally:
                if not marker.exists():
                    try:
                        os.kill(child_pid, signal.SIGTERM)
                    except OSError:
                        pass

    def test_reachable_wrong_service_does_not_start_duplicate_gateway(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env")
            executable = Path(directory) / "hermes"
            executable.touch()
            service = FakeService([config], [executable], check_results=[{"health": "wrong_service", "detail": "Wrong API"}])
            with patch.object(onboarding.subprocess, "Popen") as popen:
                result = onboarding.connect_hermes(service)
            popen.assert_not_called()
            self.assertEqual(result["status"], "error")
            self.assertEqual(result["phase"], "wrong_service")

    def test_gateway_started_then_bounded_http_timeout_is_unknown_not_starting(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env")
            executable = Path(directory) / "hermes"
            executable.touch()

            class Proc:
                returncode = None
                def poll(self):
                    return None

            service = FakeService([config], [executable])
            with patch.object(onboarding, "_bounded_check", side_effect=[
                    ({"instance_id": "instance-safe-id"}, "API is not running", "not_running"),
                    (None, "bounded wait expired", "check_timeout")]), \
                    patch.object(onboarding.subprocess, "Popen", return_value=Proc()) as popen, \
                    patch.object(onboarding.time, "sleep"):
                result = onboarding.connect_hermes(service)

            popen.assert_called_once()
            self.assertEqual(result["status"], "check_timeout")
            self.assertEqual(result["phase"], "check_timeout")
            self.assertTrue(result["launched"])
            self.assertIn("state is unknown", result["message"])
            self.assertIn("No additional Gateway was started", result["message"])

    def test_lower_level_unresponsive_health_is_preserved_without_duplicate_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env", key="private-test-key")
            executable = Path(directory) / "hermes"
            executable.touch()
            service = FakeService([config], [executable])
            with patch.object(onboarding, "_bounded_check", return_value=(
                    {"instance_id": "instance-safe-id"}, "listener gave no HTTP response", "unresponsive")), \
                    patch.object(onboarding.subprocess, "Popen") as popen:
                result = onboarding.connect_hermes(service)

            popen.assert_not_called()
            self.assertEqual(result["status"], "unresponsive")
            self.assertEqual(result["phase"], "unresponsive")
            self.assertNotIn("private-test-key", str(result))

    def test_simultaneous_connects_share_one_gateway_for_the_same_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env")
            executable = Path(directory) / "hermes"
            executable.touch()
            service = FakeService([config], [executable])
            first_checks = threading.Barrier(2)
            local = threading.local()
            spawn_entered = threading.Event()
            release_spawn = threading.Event()
            competing_reservation = threading.Event()

            class ReservationLock:
                def __init__(self):
                    self.lock = threading.Lock()
                    self.spawn_thread = None
                def __enter__(self):
                    if (spawn_entered.is_set()
                            and threading.current_thread() is not self.spawn_thread):
                        competing_reservation.set()
                    self.lock.acquire()
                    return self
                def __exit__(self, _kind, _value, _traceback):
                    self.lock.release()

            reservation_lock = ReservationLock()

            def bounded_check(_service, _path, _timeout):
                if not getattr(local, "did_initial", False):
                    local.did_initial = True
                    first_checks.wait(timeout=2)
                    return {"instance_id": "instance-safe-id"}, "API is not running", "not_running"
                return {"instance_id": "instance-safe-id"}, None, "ready"

            class Proc:
                returncode = None
                def poll(self):
                    return None

            def blocked_popen(*_args, **_kwargs):
                reservation_lock.spawn_thread = threading.current_thread()
                spawn_entered.set()
                if not release_spawn.wait(timeout=2):
                    raise RuntimeError("test spawn release timed out")
                return Proc()

            results = []
            failures = []
            def run_connect():
                try:
                    results.append(onboarding.connect_hermes(service))
                except BaseException as error:
                    failures.append(error)

            with patch.object(onboarding, "_bounded_check", side_effect=bounded_check), \
                    patch.object(onboarding, "_process_lock", reservation_lock), \
                    patch.object(onboarding.subprocess, "Popen", side_effect=blocked_popen) as popen, \
                    patch.object(onboarding.time, "sleep"):
                workers = [threading.Thread(target=run_connect, name=f"connect-{index}")
                           for index in range(2)]
                for worker in workers:
                    worker.start()
                did_enter_spawn = spawn_entered.wait(timeout=2)
                competing_reached_reservation = (
                    competing_reservation.wait(timeout=2) if did_enter_spawn else False)
                release_spawn.set()
                for worker in workers:
                    worker.join(timeout=3)

            self.assertTrue(competing_reached_reservation,
                            "second connect did not reach the per-profile reservation while Popen was held")
            self.assertFalse(any(worker.is_alive() for worker in workers))
            self.assertEqual(failures, [])
            self.assertEqual(len(results), 2)
            self.assertEqual([result["status"] for result in results], ["ready", "ready"])
            self.assertEqual(sum(bool(result["launched"]) for result in results), 1)
            popen.assert_called_once()

    def test_gateway_registry_is_scoped_to_each_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            first_config = write_env(Path(directory) / "one" / ".env")
            second_config = write_env(Path(directory) / "two" / ".env", key="other-profile-key")
            first_exe = Path(directory) / "one" / "hermes"
            second_exe = Path(directory) / "two" / "hermes"
            first_exe.touch(); second_exe.touch()

            class Proc:
                def __init__(self, identity): self.identity = identity
                returncode = None
                def poll(self): return None

            procs = [Proc("first"), Proc("second")]
            with patch.object(onboarding.subprocess, "Popen", side_effect=procs) as popen:
                first, first_error, first_launched = onboarding._launch_or_reuse(
                    FakeService([first_config], [first_exe]), first_exe,
                    onboarding.read_hermes_profile(first_config))
                second, second_error, second_launched = onboarding._launch_or_reuse(
                    FakeService([second_config], [second_exe]), second_exe,
                    onboarding.read_hermes_profile(second_config))

            self.assertIsNone(first_error)
            self.assertIsNone(second_error)
            self.assertTrue(first_launched)
            self.assertTrue(second_launched)
            self.assertIsNot(first, second)
            self.assertEqual(popen.call_count, 2)

    def test_retry_reuses_gateway_process_started_by_this_module(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env")
            executable = Path(directory) / "hermes"
            executable.touch()
            class Proc:
                returncode = None
                def poll(self): return None
            proc = Proc()
            onboarding._started_processes[str(config.resolve())] = proc
            service = FakeService([config], [executable], check_results=[{"health": "not_running"}, {"health": "ready"}])
            with patch.object(onboarding.subprocess, "Popen") as popen:
                result = onboarding.connect_hermes(service)
            popen.assert_not_called()
            self.assertEqual(result["status"], "ready")
            self.assertFalse(result["launched"])

    def test_one_windows_install_aliases_choose_official_bin_command(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "hermes"
            config = write_env(root / ".env")
            official = root / "bin" / "hermes.exe"
            alias = root / "hermes-agent" / "venv" / "Scripts" / "hermes-agent.exe"
            official.parent.mkdir(parents=True)
            alias.parent.mkdir(parents=True)
            official.touch()
            alias.touch()
            service = FakeService([config], [alias])
            with patch.object(onboarding.sys, "platform", "win32"), patch.dict(os.environ, {"LOCALAPPDATA": directory}):
                _configs, commands = onboarding._hermes_candidates(service)
            self.assertEqual(commands, [official.resolve()])

    def test_distinct_install_homes_remain_ambiguous_with_one_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / "profile" / ".env")
            first = Path(directory) / "install-one" / "hermes.exe"
            second = Path(directory) / "install-two" / "hermes.exe"
            first.parent.mkdir()
            second.parent.mkdir()
            first.touch()
            second.touch()
            service = FakeService([config], [first, second])
            _configs, commands = onboarding._hermes_candidates(service)
            self.assertEqual(len(commands), 2)

    def test_status_cache_is_isolated_per_service(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env")
            class StatusService(FakeService):
                def __init__(self, health):
                    super().__init__([config])
                    self.health = health
                def store_instances(self):
                    return [{"kind": "hermes", "config_ref": str(config.resolve()),
                             "health": self.health, "instance_id": f"id-{self.health}"}]
            first = StatusService("ready")
            second = StatusService("not_running")
            first.store.list_instances = first.store_instances
            second.store.list_instances = second.store_instances
            self.assertEqual(onboarding.get_hermes_status(first)["status"], "ready")
            self.assertEqual(onboarding.get_hermes_status(second)["status"], "starting")

    def test_check_wait_timeout_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env")
            service = FakeService([config])
            def slow_check(_instance_id):
                import time
                time.sleep(0.1)
                return {"health": "not_running"}
            service.check_instance = slow_check
            started = __import__("time").monotonic()
            instance, error, phase = onboarding._bounded_check(service, config, 0.01)
            self.assertLess(__import__("time").monotonic() - started, 0.08)
            self.assertIsNone(instance)
            self.assertEqual(phase, "check_timeout")

    def test_cached_unresponsive_instance_is_not_rendered_as_starting(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env")
            service = FakeService([config])
            service.store.list_instances = lambda: [{
                "kind": "hermes", "config_ref": str(config.resolve()),
                "instance_id": "instance-safe-id", "health": "unresponsive",
            }]
            value = onboarding.get_hermes_status(service)
            self.assertEqual(value["status"], "unresponsive")
            self.assertEqual(value["phase"], "unresponsive")
            self.assertNotIn("API_SERVER_KEY", value["message"])

    def test_setup_opens_visible_windows_console_without_changing_env(self):
        with tempfile.TemporaryDirectory() as directory:
            config = write_env(Path(directory) / ".env", key="setup-secret")
            original = config.read_bytes()
            executable = Path(directory) / "hermes.exe"
            executable.touch()
            service = FakeService([config], [executable])
            with patch.object(onboarding.sys, "platform", "win32"), \
                    patch.object(onboarding.subprocess, "CREATE_NEW_CONSOLE", 16, create=True), \
                    patch.object(onboarding.subprocess, "Popen") as popen:
                result = onboarding.open_hermes_setup(service)
            popen.assert_called_once_with([str(executable.resolve()), "setup"], shell=False, creationflags=16)
            self.assertEqual(result["status"], "starting")
            self.assertTrue(result["launched"])
            self.assertNotIn("setup-secret", str(result))
            self.assertEqual(config.read_bytes(), original)

    def test_setup_rejects_missing_and_ambiguous_commands(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = onboarding.open_hermes_setup(FakeService())
            first = Path(directory) / "install-one" / "hermes"
            second = Path(directory) / "install-two" / "hermes-agent"
            first.parent.mkdir()
            second.parent.mkdir()
            first.touch()
            second.touch()
            ambiguous = onboarding.open_hermes_setup(FakeService(commands=[first, second]))
            self.assertEqual(missing["status"], "not_installed")
            self.assertEqual(ambiguous["status"], "ambiguous")

    def test_setup_on_posix_returns_instructions_without_spawning(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "hermes"
            executable.touch()
            with patch.object(onboarding.sys, "platform", "linux"), patch.object(onboarding.subprocess, "Popen") as popen:
                result = onboarding.open_hermes_setup(FakeService(commands=[executable]))
            popen.assert_not_called()
            self.assertEqual(result["status"], "error")
            self.assertEqual(result["phase"], "platform")
            self.assertIn("hermes setup", result["message"])


if __name__ == "__main__":
    unittest.main()
