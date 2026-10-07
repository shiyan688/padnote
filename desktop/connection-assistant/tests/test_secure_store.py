from __future__ import annotations

import unittest
from unittest.mock import patch

from padnote_assistant.secure_store import (
    BuiltinCredentialVault, SecureCredentialStore, SecureStoreError,
    _MemoryBackend, _WindowsCredentials,
)


class SecureStoreTests(unittest.TestCase):
    def test_builtin_vault_uses_uuid_scoped_backend_reference_and_revokes(self):
        class RecordingBackend(_MemoryBackend):
            calls = []

            def set(self, key, secret):
                self.calls.append(("set", key, secret))
                super().set(key, secret)

        backend = RecordingBackend()
        vault = BuiltinCredentialVault(SecureCredentialStore(backend))
        instance_id = "11111111-1111-4111-8111-111111111111"
        vault.set(instance_id, "local-test-key")
        self.assertTrue(vault.has(instance_id))
        self.assertEqual(("set", "builtin-video:" + instance_id, "local-test-key"),
                         backend.calls[0])
        vault.clear(instance_id)
        self.assertFalse(vault.has(instance_id))

    def test_vault_rejects_non_uuid_references(self):
        vault = BuiltinCredentialVault(SecureCredentialStore(_MemoryBackend()))
        with self.assertRaises(SecureStoreError):
            vault.set("not-an-instance-id", "unit-test-key")

    def test_linux_without_secret_service_is_explicitly_memory_only(self):
        with patch("padnote_assistant.secure_store.platform.system", return_value="Linux"), \
                patch("padnote_assistant.secure_store.shutil.which", return_value=None):
            store = SecureCredentialStore()
        self.assertFalse(store.persistent)
        store.set("test-ref", "unit-test-key")
        self.assertEqual("unit-test-key", store.get("test-ref"))

    def test_windows_credential_blob_limit_is_utf8_bytes(self):
        backend = _WindowsCredentials.__new__(_WindowsCredentials)
        backend._api = object()
        with self.assertRaises(SecureStoreError):
            backend.set("test-ref", "é" * 1281)

    def test_storage_does_not_swallow_process_control_exceptions(self):
        class InterruptingBackend:
            def set(self, _key, _secret):
                raise KeyboardInterrupt()
            def get(self, _key):
                raise KeyboardInterrupt()
            def delete(self, _key):
                raise KeyboardInterrupt()

        store = SecureCredentialStore(InterruptingBackend())
        for action in (lambda: store.set("ref", "key"),
                       lambda: store.get("ref"), lambda: store.delete("ref")):
            with self.assertRaises(KeyboardInterrupt):
                action()


if __name__ == "__main__":
    unittest.main()
