from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from padnote_assistant.wsl_hermes import (
    WslDistro,
    WslHermesError,
    WslHermesManager,
    _GUEST_BOOTSTRAP,
    _GUEST_HELPER,
)


class WslConfigReviewTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.home = self.root / "home"
        self.hermes_home = self.home / ".hermes"
        self.hermes_home.mkdir(parents=True)
        self.profile = self.hermes_home / "profiles" / "work"
        self.profile.mkdir(parents=True)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_guest(self, request: dict) -> dict:
        env = dict(os.environ)
        env["HOME"] = str(self.home)
        result = subprocess.run(
            [sys.executable, "-c", _GUEST_HELPER],
            input=json.dumps(request).encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=5,
            check=False,
            env=env,
        )
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", "replace"))
        return json.loads(result.stdout.decode("utf-8"))

    def test_write_preserves_unrelated_provider_bytes_crlf_and_final_newline(self):
        path = self.profile / ".env"
        original = (
            b"# provider configuration\r\n"
            b"PROVIDER_API_KEY=keep-exactly\r\n"
            b"API_SERVER_ENABLED=false\r\n"
            b"API_SERVER_KEY=old-key\r\n"
        )
        path.write_bytes(original)
        path.chmod(0o640)
        result = self.run_guest({
            "op": "write_env",
            "profile": "work",
            "expected_env_sha256": hashlib.sha256(original).hexdigest(),
            "expected_config_sha256": None,
            "expected_env_mode": 0o640,
            "expected_config_mode": None,
            "expected_mode": "unknown",
            "updates": {"API_SERVER_ENABLED": "true", "API_SERVER_KEY": "new-local-key"},
        })

        self.assertTrue(result["ok"], result)
        updated = path.read_bytes()
        self.assertIn(b"# provider configuration\r\n", updated)
        self.assertIn(b"PROVIDER_API_KEY=keep-exactly\r\n", updated)
        self.assertTrue(updated.endswith(b"\r\n"))
        self.assertNotIn(b"\n", updated.replace(b"\r\n", b""))
        self.assertIn(b'API_SERVER_ENABLED="true"\r\n', updated)
        self.assertIn(b'API_SERVER_KEY="new-local-key"\r\n', updated)

    def test_config_yaml_change_after_review_refuses_env_write(self):
        path = self.hermes_home / ".env"
        config = self.hermes_home / "config.yaml"
        original_env = b"PROVIDER_KEY=preserve\nAPI_SERVER_ENABLED=false\n"
        original_config = b"# config snapshot one\n"
        path.write_bytes(original_env)
        config.write_bytes(original_config)
        path.chmod(0o600)
        config.chmod(0o640)
        expected_env = hashlib.sha256(original_env).hexdigest()
        expected_config = hashlib.sha256(original_config).hexdigest()
        config.write_bytes(b"# config snapshot changed after preview\n")

        result = self.run_guest({
            "op": "write_env",
            "profile": "default",
            "expected_env_sha256": expected_env,
            "expected_config_sha256": expected_config,
            "expected_env_mode": 0o600,
            "expected_config_mode": 0o640,
            "expected_mode": "default",
            "updates": {"API_SERVER_ENABLED": "true"},
        })

        self.assertFalse(result["ok"])
        self.assertEqual(result["code"], "stale_config")
        self.assertEqual(path.read_bytes(), original_env)

    def test_no_update_reuse_target_rechecks_confirmed_fingerprint(self):
        guid = "{806d943b-0882-43b5-a010-1f092cfa8b22}"
        manager = WslHermesManager(
            registry_reader=lambda: {"Ubuntu-24.04": guid},
            platform="nt",
        )
        manager._known[guid.lower()] = WslDistro(guid.lower(), "Ubuntu-24.04", "running")
        manager._guest_json = lambda _row, _request: {
            "ok": True,
            "env_sha256": "changed-after-preview",
            "config_sha256": "config-sha",
            "env_mode": 0o600,
            "config_mode": None,
            "mode": "shared",
        }
        plan = {"targets": [{
            "profile": "work",
            "expected_env_sha256": "preview-env-sha",
            "expected_config_sha256": "config-sha",
            "expected_env_mode": 0o600,
            "expected_config_mode": None,
            "expected_mode": "shared",
            "updates": {},
        }]}

        with self.assertRaises(WslHermesError) as raised:
            manager.apply_api_updates(guid, plan)
        self.assertEqual(raised.exception.code, "stale_config")

    def test_named_profile_runtime_selection_accounts_for_default_yaml(self):
        (self.hermes_home / "config.yaml").write_text(
            "gateway:\n  multiplex_profiles: true\n", encoding="utf-8"
        )
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        hermes = bin_dir / "hermes"
        hermes.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        hermes.chmod(0o700)
        no_yaml_python = bin_dir / "python-without-yaml"
        no_yaml_python.write_text("#!/bin/sh\nexit 9\n", encoding="utf-8")
        no_yaml_python.chmod(0o700)
        env = dict(os.environ)
        env["HOME"] = str(self.home)
        env["PATH"] = str(bin_dir) + os.pathsep + env.get("PATH", "")
        shim = (
            "import sys; sys.executable = " + repr(str(no_yaml_python)) + "; "
            "exec(compile(" + repr(_GUEST_BOOTSTRAP) + ", '<guest-bootstrap>', 'exec'))"
        )
        result = subprocess.run(
            [sys.executable, "-c", shim],
            input=json.dumps({"profile": "work"}).encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=5,
            check=False,
            env=env,
        )
        self.assertEqual(result.returncode, 0)
        value = json.loads(result.stdout.decode("utf-8"))
        self.assertFalse(value["ok"], value)
        self.assertEqual(value["code"], "hermes_python_unavailable")

    def test_duplicate_api_entry_and_symlinked_env_are_rejected_without_touching_target(self):
        duplicate = self.profile / ".env"
        duplicate_bytes = b"API_SERVER_KEY=one\nAPI_SERVER_KEY=two\n"
        duplicate.write_bytes(duplicate_bytes)
        result = self.run_guest({"op": "inspect", "profile": "work"})
        self.assertFalse(result["ok"])
        self.assertEqual(duplicate.read_bytes(), duplicate_bytes)

        duplicate.unlink()
        external = self.root / "outside.env"
        external_bytes = b"PROVIDER_SECRET=must-remain\n"
        external.write_bytes(external_bytes)
        (self.profile / ".env").symlink_to(external)
        result = self.run_guest({"op": "inspect", "profile": "work"})
        self.assertFalse(result["ok"])
        self.assertEqual(external.read_bytes(), external_bytes)


if __name__ == "__main__":
    unittest.main()
