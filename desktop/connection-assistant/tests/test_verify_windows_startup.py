import importlib.util
from pathlib import Path
import sys
import unittest


SETUP = Path(__file__).resolve().parents[1] / "windows-setup"
sys.path.insert(0, str(SETUP))
spec = importlib.util.spec_from_file_location("verify_windows_startup", SETUP / "verify_windows_startup.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


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


if __name__ == "__main__":
    unittest.main()
