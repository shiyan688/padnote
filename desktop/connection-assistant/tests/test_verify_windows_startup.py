import importlib.util
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch


SETUP = Path(__file__).resolve().parents[1] / "windows-setup"
sys.path.insert(0, str(SETUP))
spec = importlib.util.spec_from_file_location("verify_windows_startup", SETUP / "verify_windows_startup.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
import build_setup  # noqa: E402


class CleanupReportTests(unittest.TestCase):
    def test_cleanup_failure_is_reported_without_replacing_primary_failure(self):
        class FailingTemporaryDirectory:
            def cleanup(self):
                raise OSError("cleanup denied")

        report = {"cleanup": "not started"}
        checks = [{"name": "startup", "result": "FAIL", "detail": "ACL failed"}]
        verifier._remove_temp(FailingTemporaryDirectory(), report, checks)

        self.assertTrue(report["cleanup"].startswith("FAIL:"))
        self.assertEqual(checks[0]["detail"], "ACL failed")
        self.assertEqual(checks[-1]["name"], "temporary state cleanup")
        self.assertEqual(checks[-1]["result"], "FAIL")

    def test_uninstall_macro_removes_only_known_cpython313_cache_variants(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory) / "stage"
            (stage / "pkg").mkdir(parents=True)
            (stage / "launcher.py").write_text("# fixture", encoding="utf-8")
            (stage / "pkg" / "module.py").write_text("# fixture", encoding="utf-8")
            with patch.object(build_setup, "STAGE", stage):
                build_setup.write_uninstall_macro()
            macro = (stage / "installer-payload.nsh").read_text(encoding="utf-8")
            installer = (SETUP / "installer.nsi").read_text(encoding="utf-8")
            for relative in (
                r"__pycache__\launcher.cpython-313.pyc",
                r"__pycache__\launcher.cpython-313.opt-1.pyc",
                r"__pycache__\launcher.cpython-313.opt-2.pyc",
                r"pkg\__pycache__\module.cpython-313.pyc",
                r"pkg\__pycache__\module.cpython-313.opt-1.pyc",
                r"pkg\__pycache__\module.cpython-313.opt-2.pyc",
            ):
                self.assertIn(relative, macro)
            self.assertIn(r'RMDir "$INSTDIR\pkg\__pycache__"', macro)
            self.assertNotIn("*.pyc", macro)
            self.assertNotIn("unknown.cpython-313", macro)
            self.assertLess(macro.index("module.cpython-313.pyc"),
                            macro.index(r'RMDir "$INSTDIR\pkg\__pycache__"'))
            cleanup_start = macro.index("!macro CLEANUP_KNOWN_BYTECODE")
            cleanup_end = macro.index("!macroend", cleanup_start)
            uninstall_start = macro.index("!macro UNINSTALL_PAYLOAD")
            uninstall_end = macro.index("!macroend", uninstall_start)
            self.assertIn("!insertmacro CLEANUP_KNOWN_BYTECODE", macro[uninstall_start:uninstall_end])
            self.assertIn('!insertmacro CLEANUP_KNOWN_BYTECODE', installer)
            self.assertLess(installer.index('!insertmacro CLEANUP_KNOWN_BYTECODE'),
                            installer.index('File /r "${STAGE}\\*"'))
            self.assertLess(cleanup_start, uninstall_start)

    def test_gui_and_child_python_entries_disable_bytecode_writes(self):
        launcher = (SETUP / "launcher.py").read_text(encoding="utf-8")
        installer = (SETUP / "installer.nsi").read_text(encoding="utf-8")
        source_start = launcher.index("sys.dont_write_bytecode = True")
        self.assertLess(source_start, launcher.index("from launcher_support import"))
        self.assertLess(source_start, launcher.index("from padnote_assistant.hermes_onboarding_launcher"))
        self.assertIn('str(executable), "-B", str(root / "start.py")', launcher)
        self.assertIn('pythonw.exe" -B "$INSTDIR\\launcher.py"', installer)
        self.assertIn("'-B \"$INSTDIR\\launcher.py\"'", installer)
        self.assertIn("status_label as hermes_status_label", launcher)
        self.assertIn("return hermes_status_label(status)", launcher)
        verifier_source = (SETUP / "verify_windows_startup.py").read_text(encoding="utf-8")
        self.assertIn('str(Path(sys.executable)), "-B", str(ROOT / "start.py")', verifier_source)


if __name__ == "__main__":
    unittest.main()
