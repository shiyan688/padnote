from __future__ import annotations

import hashlib
import http.client
import json
import os
import socket
import ssl
import stat
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock
from pathlib import Path

from padnote_assistant import lan
from padnote_assistant.bridge import BridgeService
from padnote_assistant.security import ValidationError, validate_lan_url
from padnote_assistant.web import API_PREFIX, ServerGroup


def pinned_context(expected_sha256: str) -> ssl.SSLContext:
    """What the tablet does: no CA, no hostname, only the fingerprint from the QR."""
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    context.expected = expected_sha256  # type: ignore[attr-defined]
    return context


def pinned_request(port: int, expected_sha256: str, method: str, path: str,
                   headers: dict[str, str] | None = None) -> tuple[int, dict]:
    connection = http.client.HTTPSConnection("127.0.0.1", port, timeout=5,
                                             context=pinned_context(expected_sha256))
    try:
        connection.connect()
        der = connection.sock.getpeercert(binary_form=True)  # type: ignore[union-attr]
        if hashlib.sha256(der).hexdigest() != expected_sha256:
            raise ssl.SSLError("certificate does not match the pinned fingerprint")
        connection.request(method, path, headers=headers or {})
        response = connection.getresponse()
        return response.status, json.loads(response.read() or b"{}")
    finally:
        connection.close()


class LanIdentityTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.state = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_identity_is_created_once_and_reused(self) -> None:
        first = lan.load_or_create_identity(self.state)
        self.assertRegex(first.sha256, r"^[0-9a-f]{64}$")
        if os.name != "nt":
            self.assertEqual(0o600, first.key_path.stat().st_mode & 0o777)
        # On Windows the creation path has already applied and re-read the exact
        # current-user + SYSTEM protected DACL; POSIX mode bits are not a proxy.
        second = lan.load_or_create_identity(self.state)
        self.assertEqual(first.sha256, second.sha256,
                         "a restart must keep already-paired tablets working")

    def test_deleting_the_identity_rotates_it(self) -> None:
        first = lan.load_or_create_identity(self.state)
        first.cert_path.unlink()
        first.key_path.unlink()
        self.assertNotEqual(first.sha256, lan.load_or_create_identity(self.state).sha256)

    def test_native_windows_certificate_backend_emits_pinnable_x509_v3(self) -> None:
        from cryptography import x509
        from cryptography.x509.oid import ExtendedKeyUsageOID

        with mock.patch.object(lan, "_restrict_windows_key_acl") as protect:
            identity = lan._create_windows_identity(
                self.state, self.state / lan.CERT_NAME, self.state / lan.KEY_NAME)
        self.assertEqual(2, protect.call_count)
        self.assertTrue(protect.call_args_list[0].kwargs["directory"])
        self.assertEqual(lan.KEY_NAME, protect.call_args_list[1].args[0].name)
        cert = x509.load_pem_x509_certificate(identity.cert_path.read_bytes())
        self.assertEqual(3, cert.version.value + 1)
        self.assertTrue(cert.extensions.get_extension_for_class(x509.BasicConstraints).critical)
        self.assertEqual([ExtendedKeyUsageOID.SERVER_AUTH], list(
            cert.extensions.get_extension_for_class(x509.ExtendedKeyUsage).value))
        self.assertEqual(identity.sha256, lan.certificate_sha256(identity.cert_path))
        identity.server_context()

    def test_windows_acl_helper_grants_only_the_parsed_current_sid(self) -> None:
        completed = mock.Mock(stdout='"DOMAIN\\user","S-1-5-21-123-456-789-1001"\n')
        acl_verified = mock.Mock(stdout="PADNOTE_ACL_OK\n")
        script_paths: list[Path] = []

        def run(*args, **kwargs):
            command = args[0]
            if str(command[0]).lower().endswith("powershell.exe"):
                script_path = Path(command[5])
                script_paths.append(script_path)
                self.assertTrue(script_path.is_file())
                self.assertIn("$acl=Get-Acl -LiteralPath $Path",
                              script_path.read_text(encoding="ascii"))
                return acl_verified
            if str(command[0]).lower().endswith("whoami.exe"):
                return completed
            return mock.Mock()

        with mock.patch.object(lan.subprocess, "run", side_effect=run) as patched:
            lan._restrict_windows_key_acl(self.state / lan.KEY_NAME)
        self.assertEqual(9, patched.call_count)
        commands = [call.args[0] for call in patched.call_args_list]
        self.assertIn("/reset", commands[1])
        self.assertIn("/inheritance:r", commands[2])
        self.assertIn("/setowner", commands[3])
        self.assertIn("*S-1-5-21-123-456-789-1001:(F)", commands[4])
        self.assertIn("*S-1-5-18:(F)", commands[4])
        self.assertIn("/inheritance:r", commands[5])
        self.assertIn("/setowner", commands[6])
        self.assertIn("*S-1-5-21-123-456-789-1001:(F)", commands[7])
        self.assertIn("*S-1-5-18:(F)", commands[7])
        self.assertEqual("-File", commands[8][4])
        self.assertEqual(str(script_paths[0]), commands[8][5])
        self.assertFalse(script_paths[0].exists())

    def test_windows_temp_directory_acl_is_safe_for_new_child_files(self) -> None:
        completed = mock.Mock(stdout='"DOMAIN\\user","S-1-5-21-123-456-789-1001"\n')
        acl_verified = mock.Mock(stdout="PADNOTE_ACL_OK\n")
        with mock.patch.object(lan.subprocess, "run", side_effect=[completed] +
                                [mock.Mock() for _ in range(7)] + [acl_verified]) as run:
            lan._restrict_windows_key_acl(self.state, directory=True)
        grant_args = run.call_args_list[4].args[0]
        self.assertIn("*S-1-5-21-123-456-789-1001:(OI)(CI)(F)", grant_args)
        self.assertIn("*S-1-5-18:(OI)(CI)(F)", grant_args)

    def test_windows_reparse_certificate_file_is_rejected(self) -> None:
        info = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_nlink=1,
                               st_file_attributes=0x400)
        with mock.patch.object(lan, "_is_windows", return_value=True), \
                mock.patch.object(Path, "lstat", return_value=info):
            self.assertFalse(lan._safe_identity_file(self.state / lan.KEY_NAME))

    def test_only_private_ipv4_lan_urls_are_accepted(self) -> None:
        self.assertEqual("https://192.168.1.20:8767", validate_lan_url("https://192.168.1.20:8767"))
        self.assertEqual("https://10.0.0.5:8767", validate_lan_url("https://10.0.0.5:8767/"))
        for bad in ("http://192.168.1.20:8767", "https://8.8.8.8:8767", "https://127.0.0.1:8767",
                    "https://100.101.102.103:8767", "https://169.254.1.1:8767",
                    "https://192.168.1.20", "https://example.com:8767",
                    "https://192.168.1.20:8767/x", "https://u@192.168.1.20:8767"):
            with self.assertRaises(ValidationError, msg=bad):
                validate_lan_url(bad)

    def test_offered_addresses_are_private_and_not_vpn(self) -> None:
        import ipaddress
        for address in lan.lan_addresses():
            parsed = ipaddress.ip_address(address)
            self.assertTrue(parsed.is_private)
            self.assertNotIn(parsed, ipaddress.ip_network("100.64.0.0/10"))


class LanServerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.state = Path(self.temp.name).resolve()
        self.service = BridgeService(self.state)
        self.identity = lan.load_or_create_identity(self.state)
        self.service.lan_identity = self.identity
        self.instance = self.service.add_instance(
            "hermes", "Fixture Hermes", "http://127.0.0.1:8642", "fixture-key")
        self.service.store.update_instance_check(
            self.instance["instance_id"], health="ready", detail="ready",
            features={"run_submission": True}, executable=True)
        # Bound to loopback in tests; production binds 0.0.0.0 with the same TLS.
        self.group = ServerGroup(self.service, admin_port=0, api_port=0, lan_port=0,
                                 lan_ssl_context=self.identity.server_context(),
                                 lan_host="127.0.0.1")
        self.group.start()
        self.lan_port = self.group.lan.server_address[1]  # type: ignore[union-attr]
        self.service.lan_port = self.lan_port

    def tearDown(self) -> None:
        self.group.close()
        self.service.close()
        self.temp.cleanup()

    def _paired_token(self) -> str:
        payload = self.service.create_lan_pair_payload(self.instance["instance_id"], "192.168.1.20")
        self.assertEqual(self.identity.sha256, payload["cert_sha256"])
        self.assertEqual(f"https://192.168.1.20:{self.lan_port}", payload["url"])
        request = self.service.store.request_pair(payload["code"], "lan-tablet", "Fixture Tablet")
        self.service.store.decide_pair(request["request_id"], True)
        _, claim = self.service.store.claim_pair(request["request_id"], request["poll_token"])
        return claim["connections"][0]["token"]

    def test_a_pinned_tablet_reaches_the_device_api_over_tls(self) -> None:
        token = self._paired_token()
        status, body = pinned_request(
            self.lan_port, self.identity.sha256, "GET",
            f"{API_PREFIX}/agents/{self.instance['instance_id']}/capabilities",
            {"Authorization": f"Bearer {token}"})
        self.assertEqual(200, status, body)

    def test_an_unpaired_device_on_the_network_is_refused(self) -> None:
        status, _ = pinned_request(
            self.lan_port, self.identity.sha256, "GET",
            f"{API_PREFIX}/agents/{self.instance['instance_id']}/capabilities",
            {"Authorization": "Bearer not-a-real-token-" + "x" * 40})
        self.assertIn(status, (401, 403))

    def test_a_different_certificate_is_rejected_by_the_pin(self) -> None:
        with self.assertRaises(ssl.SSLError):
            pinned_request(self.lan_port, "0" * 64, "GET", f"{API_PREFIX}/health")

    def test_plaintext_http_gets_no_answer(self) -> None:
        with socket.create_connection(("127.0.0.1", self.lan_port), timeout=5) as raw:
            raw.sendall(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
            try:
                data = raw.recv(64)
            except (ConnectionResetError, socket.timeout):
                data = b""
        self.assertFalse(data.startswith(b"HTTP/"), "the LAN port must never speak plaintext HTTP")

    def test_the_management_ui_is_not_served_on_the_lan_port(self) -> None:
        status, _ = pinned_request(self.lan_port, self.identity.sha256, "GET", "/")
        self.assertNotEqual(200, status)

    def test_lan_pairing_requires_the_feature_to_be_enabled(self) -> None:
        self.service.lan_identity = None
        with self.assertRaises(ValidationError):
            self.service.create_lan_pair_payload(self.instance["instance_id"], "192.168.1.20")


if __name__ == "__main__":
    unittest.main()
