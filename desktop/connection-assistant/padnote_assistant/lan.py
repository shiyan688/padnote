"""Same-network direct connection: a self-signed TLS identity pinned by the QR.

The tablet and this computer are usually on the same Wi-Fi, so no third-party
VPN is needed to reach the device API. Transport security comes from a
certificate generated here, whose SHA-256 fingerprint travels inside the
one-time pairing QR; the tablet accepts exactly that certificate and nothing
else. Access control stays where it already is -- the single-use pairing code,
the explicit approval on this computer, and per-device revocable tokens -- so
listening on the LAN does not widen who can act, only who can knock.
"""
from __future__ import annotations

import hashlib
import ipaddress
import os
import re
import shutil
import socket
import ssl
import stat
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

from .security import ValidationError

CERT_NAME = "lan-cert.pem"
KEY_NAME = "lan-key.pem"
_OPENSSL_TIMEOUT_SECONDS = 30


@dataclass(frozen=True)
class LanIdentity:
    cert_path: Path
    key_path: Path
    sha256: str  # lowercase hex of the DER certificate

    def server_context(self) -> ssl.SSLContext:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(self.cert_path), str(self.key_path))
        return context


def certificate_sha256(cert_path: Path) -> str:
    der = ssl.PEM_cert_to_DER_cert(cert_path.read_text(encoding="ascii"))
    return hashlib.sha256(der).hexdigest()


def load_or_create_identity(state_dir: Path, openssl: str | None = None) -> LanIdentity:
    """Reuse the stored identity, or create a self-signed RSA identity.

    Reusing it keeps already-paired tablets working across restarts; deleting
    the two files rotates it (every tablet then has to pair again, by design).
    """
    cert_path = state_dir / CERT_NAME
    key_path = state_dir / KEY_NAME
    cert_exists, key_exists = _lstat_exists(cert_path), _lstat_exists(key_path)
    if cert_exists and key_exists and _safe_identity_file(cert_path) and _safe_identity_file(key_path):
        if _is_windows():
            _restrict_windows_key_acl(key_path)
        return LanIdentity(cert_path, key_path, certificate_sha256(cert_path))
    if cert_exists or key_exists:
        # Never overwrite an unexpected symlink, reparse point, hardlink, or
        # non-regular object while rotating a pin-bearing certificate pair.
        raise ValidationError("The local network certificate files are not safe regular files")
    if _is_windows():
        return _create_windows_identity(state_dir, cert_path, key_path)
    binary = openssl or shutil.which("openssl")
    if not binary:
        raise ValidationError("openssl is required to create the local network certificate")
    with tempfile.TemporaryDirectory(dir=state_dir) as work:
        new_cert = Path(work) / CERT_NAME
        new_key = Path(work) / KEY_NAME
        # The name is cosmetic: the tablet pins the fingerprint, not a hostname.
        # An explicit config makes this an X.509 v3 server certificate on both
        # OpenSSL and macOS LibreSSL; a bare v1 certificate is refused by some
        # TLS 1.3 clients with a decode error.
        config = Path(work) / "req.cnf"
        config.write_text(
            "[req]\ndistinguished_name=dn\nx509_extensions=v3\nprompt=no\n"
            "[dn]\nCN=PadNote Connection Assistant\n"
            "[v3]\nbasicConstraints=critical,CA:FALSE\n"
            "keyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\n"
            "subjectKeyIdentifier=hash\n", encoding="ascii")
        subprocess.run(
            # RSA rather than ECDSA: older TLS 1.3 clients (macOS LibreSSL 3.3)
            # fail the ECDSA handshake with a decode error.
            [binary, "req", "-x509", "-newkey", "rsa:2048",
             "-nodes", "-sha256", "-days", "3650", "-config", str(config),
             "-keyout", str(new_key), "-out", str(new_cert)],
            check=True, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=_OPENSSL_TIMEOUT_SECONDS)
        os.chmod(new_key, 0o600)
        # Validate before publishing: a half-written pair must never be reused.
        ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER).load_cert_chain(str(new_cert), str(new_key))
        os.replace(new_key, key_path)
        os.replace(new_cert, cert_path)
    return LanIdentity(cert_path, key_path, certificate_sha256(cert_path))


def _is_windows() -> bool:
    return os.name == "nt"


def _safe_identity_file(path: Path) -> bool:
    try:
        info = path.lstat()
    except FileNotFoundError:
        return False
    attributes = int(getattr(info, "st_file_attributes", 0))
    return stat.S_ISREG(info.st_mode) and not stat.S_ISLNK(info.st_mode) \
        and info.st_nlink == 1 and not (_is_windows() and attributes & 0x400)


def _lstat_exists(path: Path) -> bool:
    try:
        path.lstat()
        return True
    except FileNotFoundError:
        return False


def _create_windows_identity(state_dir: Path, cert_path: Path, key_path: Path) -> LanIdentity:
    """Generate an X.509 v3 certificate without requiring OpenSSL on Windows.

    The signing key is not published until it has an explicit current-user-only
    ACL. The bundle pins cryptography and ships its abi3 wheel.
    """
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
    except ImportError as error:
        raise ValidationError("Native Windows certificate support is unavailable") from error

    from datetime import datetime, timedelta, timezone

    with tempfile.TemporaryDirectory(dir=state_dir) as work:
        work_dir = Path(work)
        _restrict_windows_key_acl(work_dir, directory=True)
        new_cert = work_dir / CERT_NAME
        new_key = work_dir / KEY_NAME
        private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        subject = x509.Name([x509.NameAttribute(
            NameOID.COMMON_NAME, "PadNote Connection Assistant")])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder()
                .subject_name(subject).issuer_name(subject)
                .public_key(private_key.public_key())
                .serial_number(x509.random_serial_number())
                .not_valid_before(now - timedelta(minutes=5))
                .not_valid_after(now + timedelta(days=3650))
                .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False,
                    key_encipherment=False, data_encipherment=False, key_agreement=False,
                    key_cert_sign=False, crl_sign=False, encipher_only=None, decipher_only=None),
                    critical=True)
                .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
                .add_extension(x509.SubjectKeyIdentifier.from_public_key(private_key.public_key()),
                               critical=False)
                .sign(private_key, hashes.SHA256()))
        new_cert.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        new_key.write_bytes(private_key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL,
            serialization.NoEncryption()))
        _restrict_windows_key_acl(new_key)
        ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER).load_cert_chain(str(new_cert), str(new_key))
        os.replace(new_key, key_path)
        os.replace(new_cert, cert_path)
    return LanIdentity(cert_path, key_path, certificate_sha256(cert_path))


def _restrict_windows_key_acl(key_path: Path, *, directory: bool = False) -> None:
    """Replace and verify a protected DACL for current user and LocalSystem."""
    system = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32"
    whoami = system / "whoami.exe"
    icacls = system / "icacls.exe"
    powershell = system / "WindowsPowerShell" / "v1.0" / "powershell.exe"
    try:
        result = subprocess.run([str(whoami), "/user", "/fo", "csv", "/nh"],
                                check=True, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                timeout=10, text=True, encoding="utf-8")
        match = re.search(r"\bS-1-5-(?:\d+-)*\d+\b", result.stdout)
        if match is None:
            raise ValueError("missing user SID")
        sid = match.group(0)
        # /reset removes stale explicit ACEs; protected inheritance then removes
        # every inherited ACE before the two intentional principals are added.
        subprocess.run([str(icacls), str(key_path), "/reset"], check=True,
                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=10)
        subprocess.run([str(icacls), str(key_path), "/inheritance:r"], check=True,
                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=10)
        subprocess.run([str(icacls), str(key_path), "/setowner", f"*{sid}"], check=True,
                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=10)
        inheritance_flags = "(OI)(CI)(F)" if directory else "(F)"
        subprocess.run([str(icacls), str(key_path), "/grant:r",
                        f"*{sid}:{inheritance_flags}",
                        f"*S-1-5-18:{inheritance_flags}"], check=True,
                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=10)
        verify_script = None
        verify_dir = key_path if directory else key_path.parent
        descriptor, script_name = tempfile.mkstemp(prefix=".padnote-acl-", suffix=".ps1",
                                                   dir=verify_dir)
        verify_script = Path(script_name)
        try:
            with os.fdopen(descriptor, "w", encoding="ascii", newline="\n") as script:
                script.write(
                    "param([Parameter(Mandatory=$true)][string]$Path, "
                    "[Parameter(Mandatory=$true)][string]$Sid)\n"
                    "$ErrorActionPreference='Stop'\n"
                    "$acl=Get-Acl -LiteralPath $Path\n"
                    "if (-not $acl.AreAccessRulesProtected) { throw 'DACL is not protected' }\n"
                    "if ($acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value -ne $Sid) { throw 'owner mismatch' }\n"
                    "$rules=@($acl.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier]))\n"
                    "if ($rules.Count -ne 2) { throw 'unexpected ACE count' }\n"
                    "$expected=@($Sid,'S-1-5-18')\n"
                    "foreach ($rule in $rules) {\n"
                    "  if ($rule.IsInherited -or $rule.AccessControlType -ne [System.Security.AccessControl.AccessControlType]::Allow -or\n"
                    "      $rule.FileSystemRights -ne [System.Security.AccessControl.FileSystemRights]::FullControl -or\n"
                    "      $rule.IdentityReference.Value -notin $expected) { throw 'unexpected explicit ACE' }\n"
                    "}\nWrite-Output 'PADNOTE_ACL_OK'\n")
            subprocess.run([str(icacls), str(verify_script), "/inheritance:r"], check=True,
                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=10)
            subprocess.run([str(icacls), str(verify_script), "/setowner", f"*{sid}"], check=True,
                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=10)
            subprocess.run([str(icacls), str(verify_script), "/grant:r",
                            f"*{sid}:(F)", "*S-1-5-18:(F)"], check=True,
                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=10)
            verified = subprocess.run(
                [str(powershell), "-NoLogo", "-NoProfile", "-NonInteractive", "-File",
                 str(verify_script), str(key_path), sid], check=True, stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10,
                text=True, encoding="utf-8")
            if verified.stdout.strip() != "PADNOTE_ACL_OK":
                raise ValueError("private key ACL verification failed")
        finally:
            try:
                verify_script.unlink()
            except OSError:
                pass
    except (OSError, subprocess.SubprocessError, ValueError) as error:
        raise ValidationError("Could not protect the local network private key") from error


def lan_addresses() -> list[str]:
    """Private IPv4 addresses a tablet on the same network could reach.

    Uses the routing table's choice of outgoing interface (no packet is sent by
    a UDP connect) plus the host name's addresses; only RFC 1918 private
    addresses are offered, so a public or VPN address is never suggested.
    """
    found: list[str] = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))  # TEST-NET-1, never routed anywhere
            found.append(probe.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(info[4][0])
    except OSError:
        pass
    result: list[str] = []
    for address in found:
        try:
            parsed = ipaddress.ip_address(address)
        except ValueError:
            continue
        if parsed.is_private and not parsed.is_loopback and not parsed.is_link_local \
                and not _is_cgnat(parsed) and address not in result:
            result.append(address)
    return result


def _is_cgnat(address: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    # 100.64.0.0/10 is Tailscale/carrier NAT space, not the user's Wi-Fi.
    return isinstance(address, ipaddress.IPv4Address) \
        and address in ipaddress.ip_network("100.64.0.0/10")
