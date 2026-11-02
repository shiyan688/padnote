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
    FIXTURE_DIR = Path(__file__).parent / "fixtures" / "hermes-upstream"

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

    def test_timeout_statuses_are_accepted_and_have_actionable_chinese_labels(self):
        for status in ("check_timeout", "unresponsive"):
            normalized = helper.normalize_status_payload({"status": status, "phase": status})
            self.assertEqual(normalized["status"], status)
            self.assertTrue(helper.should_probe_hermes(status))
        timeout = helper.message_label("check_timeout", "check_timeout", "English timeout")
        self.assertIn("当前状态未确认", timeout)
        self.assertIn("本机日志", timeout)
        unresponsive = helper.message_label("unresponsive", "unresponsive", "English timeout")
        self.assertIn("没有及时返回 HTTP", unresponsive)
        self.assertIn("不会自动重启", unresponsive)

    def test_cached_health_states_are_allowed_and_explicit_connect_button_probes_them(self):
        statuses = ("not_checked", "check_expired", "not_executable", "needs_configuration",
                    "not_running", "api_not_enabled", "authentication_failed", "credential_missing",
                    "wrong_service", "capability_missing", "profile_unavailable",
                    "wsl_reconnect_required", "unsupported")
        for status in statuses:
            with self.subTest(status=status):
                state = helper.normalize_status_payload({"status": status, "phase": "health"})
                self.assertTrue(helper.should_probe_hermes(state["status"]))
                # This is the existing Windows Connect button's guarded branch:
                # it calls the backend only after should_probe_hermes returns true.
                connect = mock.Mock(return_value={"status": "ready"})
                if helper.should_probe_hermes(state["status"]):
                    result = connect(timeout=30.0, cancel_event=None)
                else:
                    result = state
                connect.assert_called_once_with(timeout=30.0, cancel_event=None)
                self.assertEqual(result["status"], "ready")

    def test_expired_status_message_never_claims_old_success_is_current(self):
        state = helper.normalize_status_payload({"status": "check_expired", "phase": "health",
            "message": "The Hermes API was already running and responding."})
        self.assertEqual(helper.phase_label(state["phase"]), "确认 API 与任务执行能力")
        label = helper.message_label(state["status"], state["phase"], state["message"])
        self.assertIn("当前状态尚未确认", label)
        self.assertIn("上次成功不代表现在", label)
        self.assertNotIn("API 已响应", label)

    def test_unknown_status_remains_rejected(self):
        for status in ("healthy", "invented", "check_expired_typo"):
            with self.subTest(status=status), self.assertRaises(RuntimeError):
                helper.normalize_status_payload({"status": status})

    def test_status_labels_cover_all_known_states_and_unknown_fallback(self):
        self.assertEqual(set(helper.VALID_STATUSES), {
            "ready", "not_checked", "check_expired", "not_executable", "needs_setup",
            "needs_configuration", "not_installed", "ambiguous", "starting", "checking",
            "not_running", "api_not_enabled", "authentication_failed", "credential_missing",
            "wrong_service", "capability_missing", "profile_unavailable",
            "wsl_reconnect_required", "unsupported", "check_timeout", "unresponsive", "error",
        })
        for status in helper.VALID_STATUSES:
            with self.subTest(status=status):
                self.assertTrue(helper.status_label(status))
        self.assertEqual(helper.status_label("check_expired"), "检测已过期")
        self.assertEqual(helper.status_label("credential_missing"), "缺少 API 凭据")
        self.assertEqual(helper.status_label("unknown"), "状态未确认")
        auth = helper.message_label("authentication_failed", "health", "The stored credential was rejected.")
        self.assertIn("凭据未通过验证", auth)

    def test_connect_consumes_structured_json_result_when_available(self):
        class Response:
            status = 200
            headers = {"Content-Type": "application/json; charset=utf-8"}
            def __enter__(self): return self
            def __exit__(self, *_args): return False
            def read(self, _limit):
                return b'{"status":"check_timeout","phase":"check_timeout","message":"localized","launched":true}'

        class Opener:
            request = None
            def open(self, request, timeout):
                self.request = request
                return Response()

        opener = Opener()
        with mock.patch.object(helper, "_local_opener", return_value=opener), \
                mock.patch.object(helper, "_read_csrf", return_value="csrf-token"):
            value = helper.connect(timeout=5)

        self.assertEqual(value["status"], "check_timeout")
        self.assertEqual(value["message"], "localized")
        self.assertTrue(value["launched"])
        self.assertEqual(opener.request.get_header("Accept"), "application/json")

    def test_connect_observes_cancellation_after_response_body_read(self):
        event = threading.Event()

        class Response:
            status = 200
            headers = {"Content-Type": "application/json"}
            def __enter__(self): return self
            def __exit__(self, *_args): return False
            def read(self, _limit):
                event.set()
                return b'{"status":"check_timeout","phase":"check_timeout","message":"bounded"}'

        class Opener:
            def open(self, _request, timeout):
                return Response()

        with mock.patch.object(helper, "_local_opener", return_value=Opener()), \
                mock.patch.object(helper, "_read_csrf", return_value="csrf-token"):
            with self.assertRaisesRegex(RuntimeError, "已取消"):
                helper.connect(timeout=5, cancel_event=event)

    def test_cancel_event_stops_before_sending_connection_request(self):
        import threading
        event = threading.Event(); event.set()
        with self.assertRaisesRegex(RuntimeError, "已取消"):
            helper._check_cancel(event)

    def test_cached_starting_status_still_runs_connect_probe(self):
        for status in ("ready", "needs_setup", "starting"):
            self.assertTrue(helper.should_probe_hermes(status))
        for status in ("not_installed", "ambiguous"):
            self.assertFalse(helper.should_probe_hermes(status))
        self.assertEqual(helper.connect.__defaults__[0], 30.0)

    def test_backend_phase_and_failure_message_have_chinese_ui_copy(self):
        self.assertEqual(helper.phase_label("config"), "检查本机 API 配置")
        advice = helper.message_label("needs_setup", "config", "API settings are incomplete")
        self.assertIn("API Server", advice)
        self.assertIn("保留现有配置", advice)
        self.assertIn("Hermes 已连接", helper.message_label("ready", "check", "Hermes API is already running and responding."))
        missing = helper.message_label("not_installed", "discovery", "Hermes was not found in standard install locations or PATH.")
        self.assertIn("未在常见位置找到 Hermes", missing)
        self.assertIn("打开连接设置可手动填写本机 API", missing)

    def test_fixed_upstream_source_pin_matches_supplied_script(self):
        pin = json.loads((self.FIXTURE_DIR / "PIN.json").read_text(encoding="utf-8"))
        pinned = self.FIXTURE_DIR / "hermes-install.ps1"
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
        pinned = self.FIXTURE_DIR / "hermes-install.ps1"
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
        pinned = self.FIXTURE_DIR / "hermes-install.ps1"
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
        pinned = self.FIXTURE_DIR / "hermes-install.ps1"
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
