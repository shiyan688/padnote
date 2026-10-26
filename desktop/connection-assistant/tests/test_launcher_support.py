import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE_PATH = Path(__file__).parents[1] / "windows-setup" / "launcher_support.py"
spec = importlib.util.spec_from_file_location("launcher_support", MODULE_PATH)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class LauncherSupportTests(unittest.TestCase):
    def test_redacts_bearer_jwt_pairing_uri_query_and_json_secrets(self):
        source = ('Bearer abcdef123456 eyJabcdefgh.abcdefgh.abcdef '
                  'padnote://pair?code=onetime-secret '
                  'https://host/pair?display=device&token=query-secret#pair=fragment-secret '
                  '{"api_key": "json-secret-value", "safe": "visible"}')
        result = helper.sanitize_diagnostics(source)
        for secret in ("abcdef123456", "eyJabcdefgh", "onetime-secret", "query-secret",
                       "fragment-secret", "json-secret-value"):
            self.assertNotIn(secret, result)
        self.assertIn("visible", result)

    def test_redacts_private_paths_with_spaces_and_private_key_blocks(self):
        source = ('C:\\Users\\Alice Smith\\private config.json '
                  '/home/alice/Private Folder/settings.json '
                  '-----BEGIN RSA PRIVATE KEY-----secret\n')
        result = helper.sanitize_diagnostics(source)
        self.assertNotIn("Alice Smith", result)
        self.assertNotIn("/home/alice", result)
        self.assertNotIn("secret", result)

    def test_diagnostics_are_bounded(self):
        self.assertTrue(helper.sanitize_diagnostics("x" * 100, 12).endswith("[diagnostics truncated]"))

    def test_rotation_preserves_bounded_previous_log(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "launcher.log"
            path.write_text("old diagnostic", encoding="utf-8")
            self.assertIsNone(helper.rotate_log(path, max_bytes=5))
            self.assertEqual(path.with_name("launcher.log.previous").read_bytes(), b"ostic")
            path.write_text("new diagnostic", encoding="utf-8")
            helper.rotate_log(path, max_bytes=5)
            self.assertEqual(path.with_name("launcher.log.previous").read_bytes(), b"ostic")

    def test_log_directory_failure_is_returned_as_warning(self):
        with tempfile.TemporaryDirectory() as directory:
            blocker = Path(directory) / "file"
            blocker.write_text("x")
            self.assertIn("Could not rotate", helper.rotate_log(blocker / "launcher.log"))

    def test_startup_error_marker_is_structured_and_allowlisted(self):
        marker = 'PADNOTE_STARTUP_ERROR {"phase":"state","category":"state-in-use","exception":"StateOwnershipError","errno":null,"winerror":null}'
        self.assertEqual(helper.parse_startup_error(marker + "\nPADNOTE_STARTUP_ERROR {\"phase\":\"cleanup\",\"category\":\"runtime-error\"}")["category"], "state-in-use")
        self.assertIsNone(helper.parse_startup_error('PADNOTE_STARTUP_ERROR {"category":"unknown"}'))
        self.assertIsNone(helper.parse_startup_error(marker, ready=True))

    def test_lifecycle_prevents_restart_race_and_tracks_exit(self):
        state = helper.Lifecycle()
        first = object(); second = object()
        state.started(first)
        self.assertFalse(state.can_start)
        self.assertTrue(state.mark_ready(first))
        self.assertFalse(state.mark_ready(first))
        self.assertTrue(state.can_open)
        self.assertTrue(state.begin_stop(first))
        self.assertFalse(state.can_start)
        self.assertFalse(state.can_open)
        self.assertTrue(state.stop_result(first, exited=False))
        self.assertFalse(state.can_start)
        self.assertTrue(state.can_stop)
        self.assertTrue(state.begin_stop(first))
        self.assertTrue(state.stop_result(first, exited=True))
        self.assertTrue(state.can_start)
        state.started(second)
        self.assertFalse(state.exited(first))
        self.assertIs(state.process, second)



    def test_initial_open_loads_existing_logs_without_creating_or_rotating(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "launcher.log"
            path.write_text("current failure", encoding="utf-8")
            path.with_name("launcher.log.previous").write_text("older failure", encoding="utf-8")
            current, previous = helper.load_saved_logs(path)
            self.assertEqual(current, "current failure")
            self.assertEqual(previous, "older failure")
            self.assertTrue(path.exists())
            self.assertTrue(path.with_name("launcher.log.previous").exists())
            self.assertEqual(helper.parse_exit_code("PADNOTE_LAUNCHER_EXIT_CODE 7\n"), 7)

    def test_current_run_log_has_priority_over_previous_log(self):
        current = ("x" * 100) + "current actionable failure"
        previous = "old failure" * 100
        text = helper.prioritize_current_log(current, previous, limit=80)
        self.assertIn("current actionable failure", text)
        self.assertNotIn("old failure", text)
        self.assertLessEqual(len(text), 80)

    def test_session_log_reads_from_run_offset_and_bounds_the_read(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "launcher.log"
            path.write_bytes(b"old marker\nnew current output")
            text, end = helper.read_log_tail(path, start=len(b"old marker\n"), limit=100)
            self.assertEqual(text, "new current output")
            self.assertEqual(end, path.stat().st_size)
            text, _ = helper.read_log_tail(path, limit=5)
            self.assertEqual(text, "utput")

    def test_failure_categories_have_actionable_localized_copy(self):
        for category, expected in (("state-in-use", "另一个助手"), ("state-invalid", "状态"),
                                   ("port-in-use", "端口"), ("access-denied", "拒绝访问")):
            status, action = helper.explain_exit(-1, was_ready=False, startup_error={"category": category})
            self.assertIn(expected, status)
            self.assertTrue(action)

    def test_exit_failure_does_not_claim_missing_runtime_from_code_2(self):
        status, action = helper.explain_exit(2, was_ready=False)
        self.assertIn("2", status)
        self.assertIn("诊断", action)
        status, _ = helper.explain_exit(10048, was_ready=False,
                                       startup_error={"category": "port-in-use"})
        self.assertIn("端口", status)


if __name__ == "__main__":
    unittest.main()
