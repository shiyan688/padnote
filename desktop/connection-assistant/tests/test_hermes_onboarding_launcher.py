import importlib.util
from pathlib import Path
import hashlib
import json
import tempfile
import threading
import unittest
from unittest import mock

MODULE = Path(__file__).parents[1] / "padnote_assistant" / "hermes_onboarding_launcher.py"
spec = importlib.util.spec_from_file_location("hermes_onboarding_launcher", MODULE)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class HermesOnboardingLauncherTests(unittest.TestCase):
    def test_sha256_verifier_accepts_exact_bytes_and_rejects_mismatch(self):
        digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        self.assertTrue(helper.verify_download_bytes(b"abc", digest))
        self.assertFalse(helper.verify_download_bytes(b"abd", digest))
        self.assertFalse(helper.verify_download_bytes(b"abc", "not-a-hash"))

    def test_csrf_parser_only_reads_hidden_csrf_input(self):
        parser = helper._CsrfParser()
        parser.feed('<input type="text" name="csrf" value="bad"><input type="hidden" name="csrf" value="good">')
        self.assertEqual(parser.token, "good")

    def test_status_allowlist_and_instance_id_bound(self):
        raw_message = "配置 Hermes" + "\r\nHTML"
        normalized = helper.normalize_status_payload({"status": "ready", "instance_id": "x" * 300,
            "message": raw_message, "phase": "checking", "launched": True, "base_url": "http://127.0.0.1:8642"})
        self.assertEqual(normalized["instance_id"], "x" * 128)
        self.assertEqual(normalized["message"], "配置 HermesHTML")
        self.assertEqual(normalized["phase"], "checking")
        self.assertIs(normalized["launched"], True)
        self.assertEqual(normalized["base_url"], "http://127.0.0.1:8642")
        with self.assertRaises(RuntimeError):
            helper.normalize_status_payload({"status": "unknown"})

    def test_cancel_event_stops_before_sending_connection_request(self):
        import threading
        event = threading.Event(); event.set()
        with self.assertRaisesRegex(RuntimeError, "已取消"):
            helper._check_cancel(event)

    def test_cached_starting_status_still_runs_connect_probe(self):
        for status in ("ready", "needs_setup", "starting"):
            self.assertTrue(helper.should_probe_hermes(status))
        for status in ("not_installed", "ambiguous", "error"):
            self.assertFalse(helper.should_probe_hermes(status))
        self.assertEqual(helper.connect.__defaults__[0], 30.0)

    def test_backend_phase_and_failure_message_have_chinese_ui_copy(self):
        self.assertEqual(helper.phase_label("config"), "检查本机 API 配置")
        advice = helper.message_label("needs_setup", "config", "API settings are incomplete")
        self.assertIn("API Server", advice)
        self.assertIn("保留现有配置", advice)
        self.assertIn("API 已响应", helper.message_label("ready", "check", "Hermes API is already running and responding."))

    def test_fixed_upstream_source_pin_matches_supplied_script(self):
        pin = json.loads((Path(__file__).parents[4] / "upstream-pin" / "PIN.json").read_text(encoding="utf-8"))
        pinned = Path(__file__).parents[4] / "upstream-pin" / "hermes-install.ps1"
        content = pinned.read_bytes()
        self.assertEqual(pin["commit"], helper.INSTALLER_COMMIT)
        self.assertEqual(pin["url"], helper.INSTALLER_URL)
        self.assertEqual(pin["sha256"], helper.INSTALLER_SHA256)
        self.assertEqual(pin["bytes"], helper.INSTALLER_BYTES)
        self.assertEqual(pin["git_blob"], helper.INSTALLER_GIT_BLOB)
        self.assertEqual(len(content), helper.INSTALLER_BYTES)
        self.assertEqual(hashlib.sha256(content).hexdigest(), helper.INSTALLER_SHA256)
        git_blob = hashlib.sha1(f"blob {len(content)}\0".encode("ascii") + content).hexdigest()
        self.assertEqual(git_blob, helper.INSTALLER_GIT_BLOB)

    def test_mock_download_writes_only_verified_pinned_bytes(self):
        pinned = Path(__file__).parents[4] / "upstream-pin" / "hermes-install.ps1"
        content = pinned.read_bytes()

        class FakeResponse:
            status = 200
            headers = {}
            def __init__(self): self.offset = 0
            def geturl(self): return helper.INSTALLER_URL
            def read(self, amount):
                chunk = content[self.offset:self.offset + amount]
                self.offset += len(chunk)
                return chunk
            def __enter__(self): return self
            def __exit__(self, *args): return False

        class FakeOpener:
            def open(self, request, timeout):
                self.request = request
                self.timeout = timeout
                return FakeResponse()

        opener = FakeOpener()
        with tempfile.TemporaryDirectory() as temp, mock.patch.object(helper, "_installer_opener", return_value=opener):
            result = helper.download_pinned_installer(Path(temp))
            self.assertEqual(result.read_bytes(), content)
            self.assertEqual(opener.request.full_url, helper.INSTALLER_URL)
            self.assertEqual(opener.timeout, helper.INSTALLER_TIMEOUT)
            self.assertIn(helper.INSTALLER_COMMIT[:8], result.name)

    def test_bad_hash_and_redirect_reject_download_without_cache_file(self):
        pinned = Path(__file__).parents[4] / "upstream-pin" / "hermes-install.ps1"
        content = pinned.read_bytes()

        class FakeResponse:
            status = 200
            headers = {}
            def __init__(self, url): self.url = url; self.offset = 0
            def geturl(self): return self.url
            def read(self, amount):
                chunk = content[self.offset:self.offset + amount]
                self.offset += len(chunk)
                return chunk
            def __enter__(self): return self
            def __exit__(self, *args): return False

        class FakeOpener:
            def __init__(self, url): self.url = url
            def open(self, request, timeout): return FakeResponse(self.url)

        with tempfile.TemporaryDirectory() as temp:
            with mock.patch.object(helper, "_installer_opener", return_value=FakeOpener("https://evil.invalid/install.ps1")):
                with self.assertRaisesRegex(RuntimeError, "地址"):
                    helper.download_pinned_installer(Path(temp))
            self.assertEqual(list(Path(temp).iterdir()), [])
            with mock.patch.object(helper, "_installer_opener", return_value=FakeOpener(helper.INSTALLER_URL)), \
                 mock.patch.object(helper, "INSTALLER_SHA256", "0" * 64):
                with self.assertRaisesRegex(RuntimeError, "SHA-256"):
                    helper.download_pinned_installer(Path(temp))
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_cancelled_download_does_not_open_network_or_create_cache(self):
        event = threading.Event(); event.set()
        with tempfile.TemporaryDirectory() as temp, mock.patch.object(helper, "_installer_opener") as opener:
            with self.assertRaisesRegex(RuntimeError, "已取消"):
                helper.download_pinned_installer(Path(temp), event)
            opener.assert_not_called()
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_cancellation_during_mock_download_creates_no_partial_script(self):
        pinned = Path(__file__).parents[4] / "upstream-pin" / "hermes-install.ps1"
        content = pinned.read_bytes()
        event = threading.Event()

        class FakeResponse:
            status = 200
            headers = {}
            def __init__(self): self.offset = 0
            def geturl(self): return helper.INSTALLER_URL
            def read(self, amount):
                chunk = content[self.offset:self.offset + amount]
                self.offset += len(chunk)
                event.set()
                return chunk
            def __enter__(self): return self
            def __exit__(self, *args): return False

        class FakeOpener:
            def open(self, request, timeout): return FakeResponse()

        with tempfile.TemporaryDirectory() as temp, mock.patch.object(helper, "_installer_opener", return_value=FakeOpener()):
            with self.assertRaisesRegex(RuntimeError, "已取消"):
                helper.download_pinned_installer(Path(temp), event)
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_installer_process_arguments_are_fixed_and_shell_free(self):
        args = helper.installer_arguments(Path(r"C:\Users\Test User\cache\setup.ps1"),
                                          Path(r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"),
                                          Path(r"C:\Users\Test User\AppData\Local\hermes"))
        self.assertEqual(args[1:4], ["-NoProfile", "-NoExit", "-File"])
        self.assertIn(helper.INSTALLER_COMMIT, args)
        self.assertEqual(args[args.index("-Channel"):args.index("-Channel") + 2], ["-Channel", "stable"])
        self.assertEqual(helper.powershell_path(r"C:\Windows").name, "powershell.exe")

    def test_official_windows_bin_and_venv_fallback_paths_prevent_repeat_install(self):
        with tempfile.TemporaryDirectory() as temp:
            bin_path, venv_path = helper.official_hermes_cli_paths(temp)
            self.assertEqual(bin_path, Path(temp) / "hermes" / "bin" / "hermes.exe")
            self.assertEqual(venv_path, Path(temp) / "hermes" / "hermes-agent" / "venv" / "Scripts" / "hermes.exe")
            self.assertFalse(helper.has_official_hermes_cli(temp))
            venv_path.parent.mkdir(parents=True)
            venv_path.write_bytes(b"mock exe")
            self.assertTrue(helper.has_official_hermes_cli(temp))

    def test_nonempty_hermes_home_without_cli_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            self.assertFalse(helper.has_existing_hermes_home(temp))
            hermes_home = Path(temp) / "hermes"
            hermes_home.mkdir()
            (hermes_home / ".env").write_text("preserve", encoding="utf-8")
            self.assertTrue(helper.has_existing_hermes_home(temp))

    def test_mock_visible_installer_spawn_uses_argv_without_shell_or_elevation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            powershell = helper.powershell_path(str(root))
            powershell.parent.mkdir(parents=True)
            powershell.write_bytes(b"mock powershell")
            script = root / "cache dir" / "verified.ps1"
            script.parent.mkdir()
            script.write_bytes(b"verified")
            observed = {}
            def fake_popen(args, **kwargs):
                observed["args"] = args
                observed.update(kwargs)
                return object()
            helper.launch_verified_installer(script, str(root), str(root / "AppData"), popen_factory=fake_popen)
            self.assertEqual(observed["args"], helper.installer_arguments(script, powershell, root / "AppData" / "hermes"))
            self.assertFalse(observed["shell"])
            self.assertEqual(observed["cwd"], str(script.parent))
            self.assertEqual(observed["creationflags"], helper.POWERSHELL_NEW_CONSOLE)
            self.assertNotIn("-ExecutionPolicy", observed["args"])
            self.assertEqual(observed["args"][-4:], ["-HermesHome", str(root / "AppData" / "hermes"),
                                                     "-InstallDir", str(root / "AppData" / "hermes" / "hermes-agent")])


if __name__ == "__main__":
    unittest.main()
