from __future__ import annotations

import os
import io
import subprocess
import sys
import tempfile
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
            self.assertEqual(popen.call_args.kwargs["env"]["HERMES_HOME"], str(Path(profile.path).parent))
            thread.return_value.start.assert_called_once()

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
