from __future__ import annotations

import importlib.util
import json
import contextlib
import io
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "verify-android-apk.py"
spec = importlib.util.spec_from_file_location("verify_android_apk", SCRIPT)
verify_apk = importlib.util.module_from_spec(spec)
assert spec and spec.loader
sys.modules[spec.name] = verify_apk
spec.loader.exec_module(verify_apk)

CERT = "a" * 64
BADGING = b"""package: name='com.padnote.android.beta' versionCode='46' versionName='0.18.0-beta.7-debug' platformBuildVersionName='15'\nsdkVersion:'24'\napplication-label:'PadNote'\napplication: label='PadNote' icon=''\napplication-debuggable\n"""
SIGNER = ("Verified using v2 scheme (APK Signature Scheme v2): true\n"
          "Number of signers: 1\n"
          f"Signer #1 certificate SHA-256 digest: {CERT}\n").encode()


class VerifyApkTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.apk = self.root / "candidate.apk"
        self.apk.write_bytes(b"synthetic-not-an-apk")
        self.aapt = self._python_tool("aapt2", BADGING)
        self.signer = self._python_tool("apksigner", SIGNER)
        self.jdk = self.root / "jdk"
        (self.jdk / "bin").mkdir(parents=True)
        self.java = self._tool_at(self.jdk / "bin" / "java", f"#!{sys.executable}\nprint('openjdk version \"17.0.1\"', file=__import__('sys').stderr)\n")

    def tearDown(self):
        self.tmp.cleanup()

    def _tool_at(self, path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        path.chmod(path.stat().st_mode | stat.S_IXUSR)
        return path

    def _python_tool(self, name, output):
        code = f"#!{sys.executable}\nimport sys\nsys.stdout.buffer.write(bytes.fromhex('" + output.hex() + "'))\n"
        return self._tool(name, code)

    def _tool(self, name, content):
        path = self.root / name
        path.write_text(content)
        path.chmod(path.stat().st_mode | stat.S_IXUSR)
        return path

    def _args(self, **updates):
        values = dict(
            apk=str(self.apk), aapt2=str(self.aapt), apksigner=str(self.signer), java_home=str(self.jdk),
            expected_certificate_sha256=CERT, expected_package_name="com.padnote.android.beta",
            expected_version_code=46, expected_version_name="0.18.0-beta.7-debug", channel="preview",
            output_receipt=None,
        )
        values.update(updates)
        return type("Args", (), values)()

    def test_parse_badging_accepts_exact_debug_identity(self):
        self.assertEqual(verify_apk.parse_badging(BADGING), {
            "package_name": "com.padnote.android.beta", "version_code": 46,
            "version_name": "0.18.0-beta.7-debug", "debuggable": True,
        })

    def test_badging_rejects_missing_duplicate_or_invalid_fields(self):
        for raw in (b"", BADGING + BADGING, BADGING.replace(b"versionCode='46'", b"versionCode='x'")):
            with self.subTest(raw=raw[:20]), self.assertRaises(verify_apk.Reject):
                verify_apk.parse_badging(raw)
        duplicate_name = BADGING.replace(b"name='com.padnote.android.beta'", b"name='com.padnote.android.beta' name='com.attacker.shadow'")
        with self.assertRaisesRegex(verify_apk.Reject, "badging_field_ambiguous"):
            verify_apk.parse_badging(duplicate_name)

    def test_signer_parser_requires_one_unambiguous_signer(self):
        self.assertEqual(verify_apk.parse_signer(SIGNER), CERT)
        for raw in (
            SIGNER + SIGNER,
            SIGNER.replace(b"Number of signers: 1", b"Number of signers: 2"),
            SIGNER.replace(b"Signer #1", b"Signer #2"),
            b"Number of signers: 1\n",
        ):
            with self.subTest(raw=raw[:40]), self.assertRaises(verify_apk.Reject):
                verify_apk.parse_signer(raw)

    def test_actual_cli_contract_receipt_is_identity_only(self):
        env = dict(os.environ)
        proc = subprocess.run([
            sys.executable, str(SCRIPT), "--apk", str(self.apk), "--aapt2", str(self.aapt),
            "--apksigner", str(self.signer), "--java-home", str(self.jdk),
            "--expected-certificate-sha256", CERT, "--expected-package-name", "com.padnote.android.beta",
            "--expected-version-code", "46", "--expected-version-name", "0.18.0-beta.7-debug",
            "--channel", "preview",
        ], capture_output=True, text=True, env=env, timeout=10, check=False)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        receipt = json.loads(proc.stdout)
        self.assertEqual(receipt["status"], "verified")
        self.assertFalse(receipt["release_allowed"])
        self.assertEqual(receipt["scope"], "apk_identity_only")
        self.assertEqual(receipt["identity"]["package_name"], "com.padnote.android.beta")
        self.assertEqual(receipt["signer_certificate_sha256"], CERT)

    def test_receipt_is_create_new_and_never_claims_release_authority(self):
        receipt = self.root / "receipt.json"
        env = dict(os.environ)
        argv = [
            sys.executable, str(SCRIPT), "--apk", str(self.apk), "--aapt2", str(self.aapt),
            "--apksigner", str(self.signer), "--java-home", str(self.jdk),
            "--expected-certificate-sha256", CERT, "--expected-package-name", "com.padnote.android.beta",
            "--expected-version-code", "46", "--expected-version-name", "0.18.0-beta.7-debug",
            "--channel", "preview", "--output-receipt", str(receipt),
        ]
        first = subprocess.run(argv, capture_output=True, text=True, env=env, timeout=10, check=False)
        self.assertEqual(first.returncode, 0, first.stdout)
        self.assertEqual(json.loads(receipt.read_text())["release_allowed"], False)
        prior = receipt.read_bytes()
        second = subprocess.run(argv, capture_output=True, text=True, env=env, timeout=10, check=False)
        self.assertEqual(second.returncode, 2)
        rejected = json.loads(second.stdout)
        self.assertEqual(rejected["error"], "receipt_create_failed")
        self.assertEqual(receipt.read_bytes(), prior)

    def test_receipt_write_flush_and_fsync_failures_remove_only_our_file(self):
        real_fdopen = os.fdopen

        class FailingStream:
            def __init__(self, fd, mode, *, encoding=None, failure="write"):
                self.base = real_fdopen(fd, mode, encoding=encoding)
                self.failure = failure

            def __enter__(self):
                return self

            def __exit__(self, *_):
                self.base.close()

            def write(self, payload):
                if self.failure == "write":
                    raise OSError("synthetic write failure")
                return self.base.write(payload)

            def flush(self):
                if self.failure == "flush":
                    raise OSError("synthetic flush failure")
                return self.base.flush()

            def fileno(self):
                return self.base.fileno()

            def close(self):
                return self.base.close()

        for failure in ("write", "flush", "fsync"):
            target = self.root / f"{failure}.json"
            if failure in ("write", "flush"):
                with mock.patch.object(verify_apk.os, "fdopen", side_effect=lambda fd, mode, encoding=None, f=failure: FailingStream(fd, mode, encoding=encoding, failure=f)):
                    with self.subTest(failure=failure), self.assertRaisesRegex(verify_apk.Reject, "receipt_create_failed"):
                        verify_apk.write_receipt_create_new(target, '{"status":"verified"}')
            else:
                with mock.patch.object(verify_apk.os, "fsync", side_effect=OSError("synthetic fsync failure")):
                    with self.subTest(failure=failure), self.assertRaisesRegex(verify_apk.Reject, "receipt_create_failed"):
                        verify_apk.write_receipt_create_new(target, '{"status":"verified"}')
            self.assertFalse(target.exists(), f"{failure} failure left a success receipt")

    def test_receipt_failure_preserves_replaced_path(self):
        target = self.root / "replaced.json"
        def replace_then_fail(_fd):
            target.unlink()
            target.write_bytes(b"replacement-owned-elsewhere")
            raise OSError("synthetic fsync failure")
        with mock.patch.object(verify_apk.os, "fsync", side_effect=replace_then_fail):
            with self.assertRaisesRegex(verify_apk.Reject, "receipt_create_failed"):
                verify_apk.write_receipt_create_new(target, '{"status":"verified"}')
        self.assertEqual(target.read_bytes(), b"replacement-owned-elsewhere")

    def test_cli_fsync_failure_returns_rejected_json_without_receipt(self):
        target = self.root / "failed-receipt.json"
        old_argv = sys.argv
        sys.argv = [
            str(SCRIPT), "--apk", str(self.apk), "--aapt2", str(self.aapt), "--apksigner", str(self.signer),
            "--java-home", str(self.jdk), "--expected-certificate-sha256", CERT,
            "--expected-package-name", "com.padnote.android.beta", "--expected-version-code", "46",
            "--expected-version-name", "0.18.0-beta.7-debug", "--channel", "preview",
            "--output-receipt", str(target),
        ]
        stdout = io.StringIO()
        try:
            with mock.patch.object(verify_apk, "verify", return_value={"status": "verified", "release_allowed": False}), \
                 mock.patch.object(verify_apk, "write_receipt_create_new", side_effect=verify_apk.Reject("receipt_create_failed")), \
                 contextlib.redirect_stdout(stdout):
                status = verify_apk.main()
        finally:
            sys.argv = old_argv
        self.assertEqual(status, 2)
        self.assertEqual(json.loads(stdout.getvalue())["status"], "rejected")
        self.assertFalse(target.exists())

    def test_release_channel_rejects_debuggable_apk(self):
        args = self._args(channel="release")
        with mock.patch.object(verify_apk, "run_tool", side_effect=[b'openjdk version "17.0.1"\n', BADGING, SIGNER]):
            with self.assertRaisesRegex(verify_apk.Reject, "release_debuggable"):
                verify_apk.verify(args)

    def test_preview_receipt_explicitly_marks_debug_install(self):
        args = self._args()
        with mock.patch.object(verify_apk, "run_tool", side_effect=[b'openjdk version "17.0.1"\n', BADGING, SIGNER]):
            result = verify_apk.verify(args)
        self.assertEqual(result["debug_install_notice"], "debuggable preview APK")

    def test_identity_and_certificate_mismatches_reject(self):
        args = self._args(expected_package_name="wrong.package")
        with mock.patch.object(verify_apk, "run_tool", side_effect=[b'openjdk version "17.0.1"\n', BADGING, SIGNER]):
            with self.assertRaisesRegex(verify_apk.Reject, "package_mismatch"):
                verify_apk.verify(args)
        args = self._args(expected_certificate_sha256="b" * 64)
        with mock.patch.object(verify_apk, "run_tool", side_effect=[b'openjdk version "17.0.1"\n', BADGING, SIGNER]):
            with self.assertRaisesRegex(verify_apk.Reject, "certificate_mismatch"):
                verify_apk.verify(args)

    def test_apk_symlink_and_hardlink_reject(self):
        link = self.root / "link.apk"
        link.symlink_to(self.apk)
        with self.assertRaisesRegex(verify_apk.Reject, "input_not_plain_regular_file"):
            verify_apk.stable_hash(link, max_bytes=verify_apk.MAX_APK_BYTES)
        hard = self.root / "hard.apk"
        os.link(self.apk, hard)
        with self.assertRaisesRegex(verify_apk.Reject, "input_link_count"):
            verify_apk.stable_hash(hard, max_bytes=verify_apk.MAX_APK_BYTES)

    def test_bounded_capture_rejects_overflow_and_timeout(self):
        noisy = self._tool("noisy", "#!/bin/sh\nprintf '%1100000s' x\n")
        old_cap = verify_apk.MAX_CAPTURE_BYTES
        verify_apk.MAX_CAPTURE_BYTES = 1024
        try:
            with self.assertRaisesRegex(verify_apk.Reject, "tool_output_limit"):
                verify_apk.run_tool([str(noisy)], {"PATH": "/usr/bin:/bin", "LC_ALL": "C"})
        finally:
            verify_apk.MAX_CAPTURE_BYTES = old_cap
        slow = self._tool("slow", "#!/bin/sh\nsleep 2\n")
        old_timeout = verify_apk.TOOL_TIMEOUT_SECONDS
        verify_apk.TOOL_TIMEOUT_SECONDS = 0.05
        try:
            with self.assertRaisesRegex(verify_apk.Reject, "tool_timeout"):
                verify_apk.run_tool([str(slow)], {"PATH": "/usr/bin:/bin", "LC_ALL": "C"})
        finally:
            verify_apk.TOOL_TIMEOUT_SECONDS = old_timeout

    def test_tool_nonzero_is_rejected_without_echoing_output(self):
        failed = self._tool("failed", "#!/bin/sh\necho PRIVATE_PATH >&2\nexit 17\n")
        with self.assertRaisesRegex(verify_apk.Reject, "tool_nonzero_exit"):
            verify_apk.run_tool([str(failed)], {"PATH": "/usr/bin:/bin", "LC_ALL": "C"})


if __name__ == "__main__":
    unittest.main()
