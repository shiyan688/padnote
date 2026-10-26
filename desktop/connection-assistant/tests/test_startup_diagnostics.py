from __future__ import annotations

import errno
import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock

from padnote_assistant import app
from padnote_assistant.state import StateError, StateOwnershipError


class StartupDiagnosticTests(unittest.TestCase):
    def _run(self, error: BaseException, *, phase: str, lan: bool = False) -> str:
        output = io.StringIO()
        service = mock.Mock()
        service.store.state_dir = Path("/private/sentinel-state-path")
        with tempfile.TemporaryDirectory() as state_dir, redirect_stderr(output):
            with mock.patch.object(app, "BridgeService", return_value=service), \
                    mock.patch.object(app, "ServerGroup") as server_group, \
                    mock.patch.object(app.lan, "load_or_create_identity") as identity:
                if phase == "state":
                    app.BridgeService.side_effect = error
                elif phase == "lan-identity":
                    identity.side_effect = error
                else:
                    server_group.return_value.start.side_effect = error
                    # A failing cleanup must not replace or expose the startup failure.
                    server_group.return_value.close.side_effect = RuntimeError("cleanup sentinel")
                args = ["--state-dir", state_dir, "--no-browser"]
                if lan:
                    args.append("--lan")
                self.assertEqual(1, app.main(args))
        return output.getvalue()

    def _record(self, output: str) -> dict:
        line = next(line for line in output.splitlines()
                    if line.startswith("PADNOTE_STARTUP_ERROR "))
        return json.loads(line.removeprefix("PADNOTE_STARTUP_ERROR "))

    def test_port_in_use_and_access_denied_are_classified_without_error_text(self):
        for error, category in (
            (OSError(errno.EADDRINUSE, "secret port/path"), "port-in-use"),
            (OSError(errno.EACCES, "secret path"), "access-denied"),
        ):
            with self.subTest(category=category):
                output = self._run(error, phase="local-services")
                self.assertEqual({"phase": "local-services", "category": category,
                                  "exception": type(error).__name__, "errno": error.errno,
                                  "winerror": None}, self._record(output))
                self.assertNotIn("secret", output)
                self.assertNotIn("sentinel", output)
                records = [self._record(line) for line in output.splitlines()
                           if line.startswith("PADNOTE_STARTUP_ERROR ")]
                self.assertEqual(2, len(records))
                self.assertEqual({"phase": "local-services", "category": "runtime-error",
                                  "exception": "RuntimeError", "errno": None,
                                  "winerror": None}, records[1])

    def test_state_lock_is_reported_without_state_path_or_exception_message(self):
        error = StateOwnershipError("secret state path")
        output = self._run(error, phase="state")
        self.assertEqual("state-in-use", self._record(output)["category"])
        self.assertEqual("state", self._record(output)["phase"])
        self.assertNotIn("secret", output)
        self.assertNotIn("sentinel", output)

    def test_state_access_denied_is_not_misclassified_as_invalid_state(self):
        error = OSError(errno.EACCES, "secret state path")
        output = self._run(error, phase="state")
        record = self._record(output)
        self.assertEqual("access-denied", record["category"])
        self.assertEqual("state", record["phase"])
        self.assertNotIn("secret", output)

    def test_state_error_uses_safe_invalid_state_category(self):
        error = StateError("secret state contents")
        output = self._run(error, phase="state")
        record = self._record(output)
        self.assertEqual("state-invalid", record["category"])
        self.assertNotIn("secret", output)

    def test_tls_runtime_failure_has_phase_marker_without_exception_message(self):
        error = ValueError("secret key path")
        output = self._run(error, phase="lan-identity", lan=True)
        self.assertEqual("runtime-error", self._record(output)["category"])
        self.assertEqual("lan-identity", self._record(output)["phase"])
        self.assertNotIn("secret", output)
        self.assertNotIn("sentinel", output)


if __name__ == "__main__":
    unittest.main()
