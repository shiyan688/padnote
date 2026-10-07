"""Small OS-backed secret store for provider credentials.

Secrets never pass through argv, environment variables, the task tree or state.json.
The Linux adapter uses Secret Service, Windows uses the current user's Credential
Manager, and macOS uses the Keychain Security framework directly.
"""
from __future__ import annotations

import ctypes
import os
import platform
import shutil
import subprocess
import uuid
from typing import Protocol


class SecureStoreError(RuntimeError):
    pass


class _Backend(Protocol):
    def set(self, key: str, secret: str) -> None: ...
    def get(self, key: str) -> str | None: ...
    def delete(self, key: str) -> None: ...


class SecureCredentialStore:
    SERVICE = "PadNoteConnectionAssistant"

    def __init__(self, backend: _Backend | None = None):
        self._backend = backend or _platform_backend()
        self.persistent = not isinstance(self._backend, _MemoryBackend)

    def set(self, key: str, secret: str) -> None:
        if not isinstance(key, str) or not key or len(key) > 200:
            raise SecureStoreError("credential reference is invalid")
        if not isinstance(secret, str) or not secret.strip() or len(secret) > 4096 \
                or "\r" in secret or "\n" in secret:
            raise SecureStoreError("provider API key is invalid")
        try:
            self._backend.set(key, secret.strip())
        except SecureStoreError:
            raise
        except Exception as error:
            raise SecureStoreError("OS secure storage could not save the provider key") from error

    def get(self, key: str) -> str | None:
        try:
            return self._backend.get(key)
        except SecureStoreError:
            raise
        except Exception as error:
            raise SecureStoreError("OS secure storage could not load the provider key") from error

    def delete(self, key: str) -> None:
        try:
            self._backend.delete(key)
        except SecureStoreError:
            raise
        except Exception as error:
            raise SecureStoreError("OS secure storage could not revoke the provider key") from error


class BuiltinCredentialVault:
    """Credential store keyed by the immutable built-in engine instance id."""

    def __init__(self, store: SecureCredentialStore | None = None):
        self._store = store

    def _secure_store(self) -> SecureCredentialStore:
        if self._store is None:
            self._store = SecureCredentialStore()
        return self._store

    @staticmethod
    def _reference(instance_id: str) -> str:
        try:
            if str(uuid.UUID(instance_id)) != instance_id:
                raise ValueError
        except (AttributeError, TypeError, ValueError) as error:
            raise SecureStoreError("credential instance reference is invalid") from error
        return "builtin-video:" + instance_id

    def set(self, instance_id: str, key: str) -> None:
        self._secure_store().set(self._reference(instance_id), key)

    def get(self, instance_id: str) -> str | None:
        return self._secure_store().get(self._reference(instance_id))

    def has(self, instance_id: str) -> bool:
        return bool(self.get(instance_id))

    def clear(self, instance_id: str) -> None:
        self._secure_store().delete(self._reference(instance_id))

    @property
    def persistent(self) -> bool:
        return self._secure_store().persistent


def _platform_backend() -> _Backend:
    system = platform.system()
    if system == "Darwin":
        return _MacKeychain()
    if system == "Windows":
        return _WindowsCredentials()
    if system == "Linux":
        try:
            return _SecretService()
        except SecureStoreError:
            # Headless Linux has no standard secure store. Keep keys only in
            # memory for this launch and make that shorter retention visible.
            return _MemoryBackend()
    raise SecureStoreError("OS secure storage is not supported on this computer")


class _MacKeychain:
    ERR_NOT_FOUND = -25300

    def __init__(self) -> None:
        self._security = ctypes.CDLL(
            "/System/Library/Frameworks/Security.framework/Security")
        self._security.SecKeychainFindGenericPassword.argtypes = [
            ctypes.c_void_p, ctypes.c_uint32, ctypes.c_char_p,
            ctypes.c_uint32, ctypes.c_char_p, ctypes.POINTER(ctypes.c_uint32),
            ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_void_p)]
        self._security.SecKeychainFindGenericPassword.restype = ctypes.c_int32
        self._security.SecKeychainAddGenericPassword.argtypes = [
            ctypes.c_void_p, ctypes.c_uint32, ctypes.c_char_p,
            ctypes.c_uint32, ctypes.c_char_p, ctypes.c_uint32,
            ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]
        self._security.SecKeychainAddGenericPassword.restype = ctypes.c_int32
        self._security.SecKeychainItemModifyAttributesAndData.argtypes = [
            ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint32, ctypes.c_void_p]
        self._security.SecKeychainItemModifyAttributesAndData.restype = ctypes.c_int32
        self._security.SecKeychainItemFreeContent.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
        self._security.SecKeychainItemFreeContent.restype = ctypes.c_int32
        self._security.SecKeychainItemDelete.argtypes = [ctypes.c_void_p]
        self._security.SecKeychainItemDelete.restype = ctypes.c_int32
        self._core = ctypes.CDLL(
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
        self._core.CFRelease.argtypes = [ctypes.c_void_p]
        self._core.CFRelease.restype = None

    @staticmethod
    def _bytes(value: str) -> bytes:
        return value.encode("utf-8")

    def _find(self, key: str):
        service = self._bytes(SecureCredentialStore.SERVICE)
        account = self._bytes(key)
        size = ctypes.c_uint32()
        password = ctypes.c_void_p()
        item = ctypes.c_void_p()
        status = self._security.SecKeychainFindGenericPassword(
            None, len(service), service, len(account), account,
            ctypes.byref(size), ctypes.byref(password), ctypes.byref(item))
        return status, size, password, item

    def set(self, key: str, secret: str) -> None:
        service = self._bytes(SecureCredentialStore.SERVICE)
        account = self._bytes(key)
        password = secret.encode("utf-8")
        data = ctypes.create_string_buffer(password)
        status, _size, old_data, item = self._find(key)
        if status == 0:
            try:
                changed = self._security.SecKeychainItemModifyAttributesAndData(
                    item, None, len(password), ctypes.cast(data, ctypes.c_void_p))
                if changed != 0:
                    raise SecureStoreError("OS secure storage could not update the provider key")
            finally:
                self._security.SecKeychainItemFreeContent(None, old_data)
                if item:
                    self._core.CFRelease(item)
            return
        if status != self.ERR_NOT_FOUND:
            raise SecureStoreError("OS secure storage could not access the provider key")
        status = self._security.SecKeychainAddGenericPassword(
            None, len(service), service, len(account), account, len(password),
            ctypes.cast(data, ctypes.c_void_p), None)
        if status != 0:
            raise SecureStoreError("OS secure storage could not add the provider key")

    def get(self, key: str) -> str | None:
        status, size, password, item = self._find(key)
        if status == self.ERR_NOT_FOUND:
            return None
        if status != 0:
            raise SecureStoreError("OS secure storage could not read the provider key")
        try:
            return ctypes.string_at(password, size.value).decode("utf-8")
        finally:
            self._security.SecKeychainItemFreeContent(None, password)
            if item:
                self._core.CFRelease(item)

    def delete(self, key: str) -> None:
        status, _size, password, item = self._find(key)
        if status == self.ERR_NOT_FOUND:
            return
        if status != 0:
            raise SecureStoreError("OS secure storage could not revoke the provider key")
        self._security.SecKeychainItemFreeContent(None, password)
        try:
            deleted = self._security.SecKeychainItemDelete(item)
            if deleted != 0:
                raise SecureStoreError("OS secure storage could not revoke the provider key")
        finally:
            if item:
                self._core.CFRelease(item)


class _WindowsCredentials:
    CRED_TYPE_GENERIC = 1
    CRED_PERSIST_LOCAL_MACHINE = 2

    class _Credential(ctypes.Structure):
        _fields_ = [
            ("Flags", ctypes.c_uint32), ("Type", ctypes.c_uint32),
            ("TargetName", ctypes.c_wchar_p), ("Comment", ctypes.c_wchar_p),
            ("LastWritten", ctypes.c_uint64), ("CredentialBlobSize", ctypes.c_uint32),
            ("CredentialBlob", ctypes.c_void_p), ("Persist", ctypes.c_uint32),
            ("AttributeCount", ctypes.c_uint32), ("Attributes", ctypes.c_void_p),
            ("TargetAlias", ctypes.c_wchar_p), ("UserName", ctypes.c_wchar_p),
        ]

    @staticmethod
    def _target(key: str) -> str:
        return f"PadNoteConnectionAssistant/{key}"

    def __init__(self) -> None:
        self._api = ctypes.WinDLL("Advapi32.dll", use_last_error=True)
        self._api.CredWriteW.argtypes = [ctypes.POINTER(self._Credential), ctypes.c_uint32]
        self._api.CredReadW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32,
                                        ctypes.c_uint32, ctypes.POINTER(ctypes.c_void_p)]
        self._api.CredDeleteW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_uint32]
        self._api.CredFree.argtypes = [ctypes.c_void_p]
        self._api.CredFree.restype = None

    def set(self, key: str, secret: str) -> None:
        raw = secret.encode("utf-8")
        if len(raw) > 2560:
            raise SecureStoreError("provider API key exceeds the OS credential size limit")
        blob = ctypes.create_string_buffer(raw)
        record = self._Credential()
        record.Type = self.CRED_TYPE_GENERIC
        record.TargetName = self._target(key)
        record.CredentialBlobSize = len(raw)
        record.CredentialBlob = ctypes.cast(blob, ctypes.c_void_p)
        record.Persist = self.CRED_PERSIST_LOCAL_MACHINE
        if not self._api.CredWriteW(ctypes.byref(record), 0):
            raise SecureStoreError("OS secure storage could not save the provider key")

    def get(self, key: str) -> str | None:
        pointer = ctypes.c_void_p()
        if not self._api.CredReadW(self._target(key), self.CRED_TYPE_GENERIC, 0,
                                   ctypes.byref(pointer)):
            error = ctypes.get_last_error()
            if error == 1168:
                return None
            raise SecureStoreError("OS secure storage could not load the provider key")
        try:
            credential = ctypes.cast(pointer, ctypes.POINTER(self._Credential)).contents
            return ctypes.string_at(credential.CredentialBlob,
                                    credential.CredentialBlobSize).decode("utf-8")
        finally:
            self._api.CredFree(pointer)

    def delete(self, key: str) -> None:
        if not self._api.CredDeleteW(self._target(key), self.CRED_TYPE_GENERIC, 0):
            if ctypes.get_last_error() != 1168:
                raise SecureStoreError("OS secure storage could not revoke the provider key")


class _SecretService:
    def __init__(self) -> None:
        self._command = shutil.which("secret-tool")
        if not self._command:
            raise SecureStoreError("Linux Secret Service is unavailable; install a desktop keyring")

    @staticmethod
    def _attributes(key: str) -> list[str]:
        return ["service", SecureCredentialStore.SERVICE, "account", key]

    def set(self, key: str, secret: str) -> None:
        subprocess.run([self._command, "store", "--label=PadNote video provider",
                        *self._attributes(key)], input=secret.encode("utf-8"),
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       check=True, timeout=10, env={"PATH": os.defpath})

    def get(self, key: str) -> str | None:
        result = subprocess.run([self._command, "lookup", *self._attributes(key)],
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, check=False, timeout=10,
                                env={"PATH": os.defpath})
        if result.returncode == 1:
            return None
        if result.returncode != 0 or len(result.stdout) > 8192:
            raise SecureStoreError("OS secure storage could not load the provider key")
        try:
            return result.stdout.decode("utf-8").rstrip("\n")
        except UnicodeDecodeError as error:
            raise SecureStoreError("OS secure storage returned an invalid provider key") from error

    def delete(self, key: str) -> None:
        result = subprocess.run([self._command, "clear", *self._attributes(key)],
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, check=False, timeout=10,
                                env={"PATH": os.defpath})
        if result.returncode not in {0, 1}:
            raise SecureStoreError("OS secure storage could not revoke the provider key")


class _MemoryBackend:
    def __init__(self) -> None:
        self._secrets: dict[str, str] = {}

    def set(self, key: str, secret: str) -> None:
        self._secrets[key] = secret

    def get(self, key: str) -> str | None:
        return self._secrets.get(key)

    def delete(self, key: str) -> None:
        self._secrets.pop(key, None)
