from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from padnote_assistant.wsl_hermes import (
    WslHermesError,
    WslHermesManager,
    _GUEST_HELPER,
    _GUEST_BOOTSTRAP,
    parse_distro_names,
)


class WslHermesParsingTests(unittest.TestCase):
    def test_wsl_cli_utf16le_nul_output_is_decoded_without_loss(self):
        raw = "Ubuntu-24.04\r\nDocker Desktop\r\n".encode("utf-16-le")
        self.assertEqual(parse_distro_names(raw), ["Ubuntu-24.04", "Docker Desktop"])

    def test_profiles_are_enumerated_by_safe_identity_files_not_cli_table(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary) / "home"
            profiles = home / ".hermes" / "profiles"
            (profiles / "work").mkdir(parents=True)
            (profiles / "work" / "auth.json").write_text("{}", encoding="utf-8")
            (profiles / "empty").mkdir()
            result = self._run_guest(home, {"op": "profiles"}, Path(temporary))
            self.assertEqual(result["profiles"], ["default", "work"])

    def test_bootstrap_selects_hermes_virtualenv_with_yaml_when_system_python_lacks_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            home = root / "home"
            hermes_home = home / ".hermes"
            (hermes_home / "profiles" / "work").mkdir(parents=True)
            (hermes_home / "config.yaml").write_text("gateway: {}\n", encoding="utf-8")
            venv = home / "hermes-agent" / ".venv" / "bin"
            venv.mkdir(parents=True)
            python = venv / "python"
            python.write_text("#!/bin/sh\n[ \"$1\" = \"-c\" ] && [ \"$2\" = \"import yaml\" ]\n", encoding="utf-8")
            python.chmod(0o700)
            hermes_bin = home / ".local" / "bin"
            hermes_bin.mkdir(parents=True)
            hermes = hermes_bin / "hermes"
            hermes.write_text(f"#!{python}\n", encoding="utf-8")
            hermes.chmod(0o700)
            env = dict(os.environ, HOME=str(home), PATH="/usr/bin:/bin")
            result = subprocess.run([sys.executable, "-c", _GUEST_BOOTSTRAP],
                                    input=json.dumps({"profile": "work"}).encode(),
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    env=env, timeout=5, check=False)
            value = json.loads(result.stdout.decode("utf-8"))
            self.assertTrue(value["ok"], value)
            self.assertEqual(value["python"], str(python))

    def test_passive_list_never_issues_distribution_guest_command(self):
        calls = []
        def runner(args, **kwargs):
            calls.append(list(args))
            if args[-2:] == ["--list", "--quiet"]:
                output = "Ubuntu-24.04\r\nDocker Desktop\r\n".encode("utf-16-le")
            elif args[-3:] == ["--list", "--running", "--quiet"]:
                output = "Docker Desktop\r\n".encode("utf-16-le")
            else:
                raise AssertionError("passive discovery issued a guest command")
            return SimpleNamespace(returncode=0, stdout=output, stderr=b"")
        manager = WslHermesManager(runner=runner,
                                   registry_reader=lambda: {
                                       "Ubuntu-24.04": "{806d943b-0882-43b5-a010-1f092cfa8b22}",
                                       "Docker Desktop": "{106d943b-0882-43b5-a010-1f092cfa8b22}",
                                   }, platform="nt")
        rows = manager.list_distros()
        self.assertEqual([row["state"] for row in rows], ["stopped", "running"])
        self.assertEqual(len(calls), 2)
        self.assertTrue(all("--distribution" not in call for call in calls))

    def _run_guest(self, home: Path, request: dict, extra_path: Path) -> dict:
        env = dict(os.environ)
        env["HOME"] = str(home)
        env["PATH"] = str(extra_path) + os.pathsep + env.get("PATH", "")
        result = subprocess.run([sys.executable, "-c", _GUEST_HELPER],
                                input=json.dumps(request).encode("utf-8"),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=5, check=False, env=env)
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", "replace"))
        return json.loads(result.stdout.decode("utf-8"))

    def test_guest_inspect_only_returns_selected_api_fields_and_fingerprints(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            home = root / "home"
            hermes_home = home / ".hermes"
            selected = hermes_home / "profiles" / "work"
            selected.mkdir(parents=True)
            (selected / ".env").write_text(
                "PROVIDER_SECRET=do-not-read\nAPI_SERVER_ENABLED=true\n"
                "API_SERVER_HOST=127.0.0.1\nAPI_SERVER_PORT=8643\nAPI_SERVER_KEY=secret-key\n",
                encoding="utf-8")
            bin_dir = root / "bin"
            bin_dir.mkdir()
            hermes = bin_dir / "hermes"
            hermes.write_text("#!/bin/sh\nprintf 'default\\n* work\\n'\n", encoding="utf-8")
            hermes.chmod(0o700)
            result = self._run_guest(home, {"op": "inspect", "profile": "work"}, bin_dir)
            self.assertTrue(result["ok"])
            self.assertEqual(result["api_state"], "configured")
            self.assertEqual(result["effective"]["key"], "secret-key")
            self.assertEqual(result["effective"]["port"], 8643)
            self.assertNotIn("PROVIDER_SECRET", json.dumps(result))
            self.assertEqual(result["profiles"], ["default", "work"])

    def test_guest_write_is_cas_atomic_and_preserves_unrelated_lines(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            home = root / "home"
            profile = home / ".hermes" / "profiles" / "work"
            profile.mkdir(parents=True)
            env_path = profile / ".env"
            original = b"# user note\nPROVIDER_KEY=keep-me\nAPI_SERVER_ENABLED=false\n"
            env_path.write_bytes(original)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            expected = hashlib.sha256(original).hexdigest()
            result = self._run_guest(home, {
                "op": "write_env", "profile": "work", "expected_env_sha256": expected,
                "expected_config_sha256": None, "expected_env_mode": 0o644,
                "expected_config_mode": None, "expected_mode": "unknown",
                "updates": {"API_SERVER_ENABLED": "true", "API_SERVER_KEY": "generated-secret"},
            }, bin_dir)
            self.assertTrue(result["ok"])
            updated = env_path.read_bytes()
            self.assertIn(b"# user note\n", updated)
            self.assertIn(b"PROVIDER_KEY=keep-me\n", updated)
            self.assertIn(b'API_SERVER_ENABLED="true"', updated)
            self.assertIn(b'API_SERVER_KEY="generated-secret"', updated)
            self.assertEqual(env_path.stat().st_mode & 0o777, 0o600)
            backup = list(profile.glob(".env.padnote-backup-*"))
            self.assertEqual(len(backup), 1)
            self.assertEqual(backup[0].read_bytes(), original)
            stale = self._run_guest(home, {
                "op": "write_env", "profile": "work", "expected_env_sha256": expected,
                "expected_config_sha256": None, "expected_env_mode": 0o644,
                "expected_config_mode": None, "expected_mode": "unknown",
                "updates": {"API_SERVER_PORT": "8644"},
            }, bin_dir)
            self.assertEqual(stale["code"], "stale_config")
            self.assertEqual(env_path.read_bytes(), updated)

    def test_duplicate_target_setting_refuses_without_rewriting(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            home = root / "home"
            profile = home / ".hermes"
            profile.mkdir(parents=True)
            path = profile / ".env"
            path.write_text("API_SERVER_KEY=one\nAPI_SERVER_KEY=two\n", encoding="utf-8")
            bin_dir = root / "bin"
            bin_dir.mkdir()
            before = path.read_bytes()
            result = self._run_guest(home, {"op": "inspect", "profile": "default"}, bin_dir)
            self.assertFalse(result["ok"])
            self.assertEqual(path.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
