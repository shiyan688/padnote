from __future__ import annotations

import base64
import hashlib
import io
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from padnote_assistant.bridge import BridgeService
from padnote_assistant.bundles import MAX_ARTIFACT_BYTES, collect_artifacts, prepare_task_directory
from padnote_assistant.hermes import HermesClient
from padnote_assistant.profiles import read_hermes_profile
from padnote_assistant.security import ValidationError
from padnote_assistant.state import AuthorizationError, StateError, StateOwnershipError, StateStore


ROOT = Path(__file__).resolve().parents[1]
ANDROID_BUNDLE = ROOT / "tests/fixtures/android-video-task.zip"


def ready_instance(service: BridgeService, *, key: str = "fixture-key") -> tuple[dict, str, dict]:
    instance = service.add_instance("hermes", "Fixture Hermes", "http://127.0.0.1:8642", key)
    service.store.update_instance_check(
        instance["instance_id"], health="ready", detail="ready",
        features={"run_submission": True, "run_status": True, "run_stop": True}, executable=True)
    code, _ = service.store.create_pair_code(instance["instance_id"], "https://computer.example.ts.net")
    request = service.store.request_pair(code, "device-a", "Tablet")
    service.store.decide_pair(request["request_id"], True)
    _, claim = service.store.claim_pair(request["request_id"], request["poll_token"])
    token = claim["connections"][0]["token"]
    return instance, token, service.store.authenticate(instance["instance_id"], token)


def run_payload() -> dict:
    return {
        "client_task_id": "client-task-1",
        "title": "Fixture",
        "input": "Only answer fixture",
        "source": {"note_id": "note-1", "note_revision": 1},
    }


class StateTests(unittest.TestCase):
    def _pair(self, store: StateStore, instance_id: str, device_id: str = "same-device"):
        code, _ = store.create_pair_code(instance_id, "https://computer.example.ts.net")
        request = store.request_pair(code, device_id, "Test tablet")
        store.decide_pair(request["request_id"], True)
        status, claim = store.claim_pair(request["request_id"], request["poll_token"])
        self.assertEqual(200, status)
        token = claim["connections"][0]["token"]
        return token, store.authenticate(instance_id, token)

    def _instance(self, store: StateStore):
        instance = store.add_instance("hermes", "Fixture Hermes", "http://127.0.0.1:8642")
        store.update_instance_check(
            instance["instance_id"], health="ready", detail="ready",
            features={"run_submission": True}, executable=True)
        return instance

    def test_failed_persist_does_not_publish_claim_in_memory_or_after_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            with StateStore(Path(directory)) as store:
                instance = self._instance(store)
                code, _ = store.create_pair_code(instance["instance_id"], "https://computer.example.ts.net")
                request = store.request_pair(code, "device-a", "Tablet")
                store.decide_pair(request["request_id"], True)
                original_save = store._save
                store._save = lambda data=None: (_ for _ in ()).throw(OSError("disk full"))
                with self.assertRaises(OSError):
                    store.claim_pair(request["request_id"], request["poll_token"])
                self.assertEqual([], store.list_connections())
                store._save = original_save
            with StateStore(Path(directory)) as reopened:
                self.assertEqual([], reopened.list_connections())

    def test_same_device_id_new_connection_does_not_inherit_old_tasks(self):
        with tempfile.TemporaryDirectory() as directory:
            with StateStore(Path(directory)) as store:
                instance = self._instance(store)
                _, first = self._pair(store, instance["instance_id"])
                _, second = self._pair(store, instance["instance_id"])
                run, _ = store.create_or_get_run(first, "task-1", {"input": "one"})
                self.assertEqual(run["task_id"], store.owned_run(first, run["task_id"])["task_id"])
                with self.assertRaises(AuthorizationError):
                    store.owned_run(second, run["task_id"])

    def test_canonical_directory_has_one_owner_and_lock_file_is_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state_dir = root / "state"
            state_dir.mkdir()
            alias = root / "state-alias"
            alias.symlink_to(state_dir, target_is_directory=True)
            first = StateStore(state_dir)
            bridge_id = first.bridge_id
            before = first.path.read_bytes()
            try:
                with self.assertRaises(StateOwnershipError):
                    StateStore(alias)
                self.assertEqual(before, first.path.read_bytes())
            finally:
                first.close()
            self.assertTrue((state_dir / ".owner.lock").exists())
            with StateStore(alias) as reopened:
                self.assertEqual(bridge_id, reopened.bridge_id)

    def test_closed_store_cannot_overwrite_new_owner_revocation(self):
        with tempfile.TemporaryDirectory() as directory:
            state_dir = Path(directory)
            old = StateStore(state_dir)
            instance = self._instance(old)
            token, connection = self._pair(old, instance["instance_id"])
            old.close()

            with StateStore(state_dir) as current:
                current.revoke_connection(connection["connection_id"])
                revoked = current.path.read_bytes()
                with self.assertRaisesRegex(StateError, "closed"):
                    old.add_instance("hermes", "stale writer", "http://127.0.0.1:8643")
                with self.assertRaisesRegex(StateError, "closed"):
                    old.authenticate(instance["instance_id"], token)
                self.assertEqual(revoked, current.path.read_bytes())


class ProfileTests(unittest.TestCase):
    def _write(self, path: Path, *, port: int = 8642, key: str = "secret-one", extra: str = ""):
        path.write_text(
            f"API_SERVER_ENABLED=true\nAPI_SERVER_HOST=127.0.0.1\nAPI_SERVER_PORT={port}\n"
            f"API_SERVER_KEY={key}\n{extra}", encoding="utf-8")

    def test_profile_identity_ignores_unrelated_values_but_tracks_endpoint_and_key(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            self._write(path, extra="MODEL=fixture\n")
            first = read_hermes_profile(path)
            self._write(path, extra="MODEL=another-fixture\n")
            self.assertEqual(first.fingerprint, read_hermes_profile(path).fingerprint)
            self._write(path, port=8643)
            self.assertNotEqual(first.fingerprint, read_hermes_profile(path).fingerprint)
            self._write(path, key="secret-two")
            self.assertNotEqual(first.fingerprint, read_hermes_profile(path).fingerprint)

    def test_changed_profile_creates_new_instance_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            profile_path = root / ".env"
            self._write(profile_path)
            service = BridgeService(root / "state")
            self.addCleanup(service.close)
            service.discovery_results = [{"kind": "hermes", "path": str(profile_path)}]
            first = service.import_discovered_profile(str(profile_path))
            service.store.update_instance_check(
                first["instance_id"], health="ready", detail="ready",
                features={"run_submission": True, "run_status": True, "run_stop": True}, executable=True)
            code, _ = service.store.create_pair_code(
                first["instance_id"], "https://computer.example.ts.net")
            request = service.store.request_pair(code, "profile-device", "Profile Tablet")
            service.store.decide_pair(request["request_id"], True)
            _, claim = service.store.claim_pair(request["request_id"], request["poll_token"])
            old_token = claim["connections"][0]["token"]
            self._write(profile_path, port=8643)
            second = service.import_discovered_profile(str(profile_path))
            self.assertNotEqual(first["instance_id"], second["instance_id"])
            self.assertEqual("identity_changed", service.store.get_instance(first["instance_id"])["health"])
            self.assertIsNotNone(service.store.get_instance(first["instance_id"])["retired_at"])
            self.assertFalse(service.vault.has(first["instance_id"]))
            with mock.patch.object(HermesClient, "create_run") as create:
                with self.assertRaisesRegex(ValidationError, "retired"):
                    service.submit_run(first["instance_id"], old_token, "client-task-1", run_payload())
                create.assert_not_called()


class AuthorizationLifecycleTests(unittest.TestCase):
    def test_manual_key_change_rotates_identity_and_old_token_never_calls_upstream(self):
        with tempfile.TemporaryDirectory() as directory:
            service = BridgeService(Path(directory))
            self.addCleanup(service.close)
            instance, token, _ = ready_instance(service, key="old-secret")
            self.assertEqual(
                instance["instance_id"], service.configure_key(instance["instance_id"], "old-secret")["instance_id"])
            replacement = service.configure_key(instance["instance_id"], "new-secret")
            self.assertNotEqual(instance["instance_id"], replacement["instance_id"])
            self.assertFalse(service.vault.has(instance["instance_id"]))
            features = service.capabilities(instance["instance_id"], token)["features"]
            self.assertFalse(features["run_submission"])
            self.assertFalse(features["task_bundle"])
            with mock.patch.object(HermesClient, "create_run") as create:
                with self.assertRaisesRegex(ValidationError, "retired"):
                    service.submit_run(instance["instance_id"], token, "client-task-1", run_payload())
                create.assert_not_called()
            state_text = service.store.path.read_text(encoding="utf-8")
            self.assertNotIn("old-secret", state_text)
            self.assertNotIn("new-secret", state_text)

    def test_revoke_while_request_waits_prevents_upstream_call(self):
        with tempfile.TemporaryDirectory() as directory:
            service = BridgeService(Path(directory))
            self.addCleanup(service.close)
            instance, token, connection = ready_instance(service)
            guard = service._operation_lock("connection:" + connection["connection_id"])
            original_lock = service._operation_lock
            queued = threading.Event()
            def observed_lock(key: str):
                if key == "connection:" + connection["connection_id"]:
                    queued.set()
                return original_lock(key)
            service._operation_lock = observed_lock
            guard.acquire()
            errors: list[Exception] = []
            try:
                worker = threading.Thread(target=lambda: self._submit_catching(
                    service, instance["instance_id"], token, errors))
                worker.start()
                self.assertTrue(queued.wait(timeout=2))
                service.store.revoke_connection(connection["connection_id"])
            finally:
                guard.release()
            worker.join(timeout=5)
            self.assertFalse(worker.is_alive())
            self.assertEqual(1, len(errors))
            self.assertIsInstance(errors[0], AuthorizationError)
            self.assertEqual({}, service.store.snapshot()["runs"])

    @staticmethod
    def _submit_catching(service: BridgeService, instance_id: str, token: str,
                         errors: list[Exception]) -> None:
        try:
            service.submit_run(instance_id, token, "client-task-1", run_payload())
        except Exception as error:
            errors.append(error)


class BundleTests(unittest.TestCase):
    def _envelope(self, raw: bytes) -> dict:
        return {
            "client_task_id": "client-task-1",
            "title": "生成讲解视频 · 牛顿第二定律",
            "input": "请生成讲解视频",
            "source": {"note_id": "fixture-note-1", "note_revision": 7},
            "bundle_base64": base64.b64encode(raw).decode("ascii"),
            "bundle_sha256": hashlib.sha256(raw).hexdigest(),
        }

    def test_real_android_bundle_keeps_video_request_and_writes_bridge_metadata(self):
        raw = ANDROID_BUNDLE.read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            tasks = Path(directory)
            task = prepare_task_directory(tasks, "bridge-task", self._envelope(raw),
                                          base64.b64encode(raw).decode("ascii"), hashlib.sha256(raw).hexdigest())
            request_text = (task / "request.json").read_text(encoding="utf-8")
            self.assertIn('"task_type": "video.explain.v1"', request_text)
            self.assertTrue((task / "work/padnote-submission.json").is_file())

    def test_tampered_content_is_rejected_by_manifest(self):
        original = ANDROID_BUNDLE.read_bytes()
        source = zipfile.ZipFile(io.BytesIO(original))
        output = io.BytesIO()
        with source, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as target:
            for info in source.infolist():
                data = source.read(info.filename)
                if info.filename == "input/content.md":
                    data += b"tampered"
                target.writestr(info, data)
        raw = output.getvalue()
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValidationError, "manifest"):
                prepare_task_directory(Path(directory), "bridge-task", self._envelope(raw),
                                       base64.b64encode(raw).decode("ascii"), hashlib.sha256(raw).hexdigest())

    def test_oversized_artifact_is_rejected_before_hashing(self):
        with tempfile.TemporaryDirectory() as directory:
            task = Path(directory) / "task"
            output = task / "output"
            output.mkdir(parents=True)
            with (output / "large.mp4").open("wb") as stream:
                stream.truncate(MAX_ARTIFACT_BYTES + 1)
            with mock.patch("padnote_assistant.bundles._hash_descriptor") as hasher:
                with self.assertRaisesRegex(ValidationError, "100 MiB"):
                    collect_artifacts(task, "task")
                hasher.assert_not_called()

    def test_output_enumeration_counts_unrecognized_files(self):
        with tempfile.TemporaryDirectory() as directory:
            task = Path(directory) / "task"
            output = task / "output"
            output.mkdir(parents=True)
            for index in range(513):
                (output / f"ignored-{index}.bin").touch()
            with self.assertRaisesRegex(ValidationError, "too many entries"):
                collect_artifacts(task, "task")


class HermesCapabilityTests(unittest.TestCase):
    def test_capabilities_require_identity_flags_and_exact_routes(self):
        client = HermesClient("http://127.0.0.1:8642", "fixture")
        valid = {
            "object": "hermes.api_server.capabilities", "platform": "hermes-agent",
            "features": {"run_submission": True, "run_status": True, "run_stop": True,
                         "run_approval_response": True},
            "endpoints": {
                "runs": {"method": "POST", "path": "/v1/runs"},
                "run_status": {"method": "GET", "path": "/v1/runs/{run_id}"},
                "run_stop": {"method": "POST", "path": "/v1/runs/{run_id}/stop"},
                "run_approval": {"method": "POST", "path": "/v1/runs/{run_id}/approval"},
            },
        }
        client._request = lambda *args, **kwargs: valid
        self.assertTrue(client.check_capabilities().executable)
        invalid = dict(valid)
        invalid["features"] = dict(valid["features"], run_status=1)
        client._request = lambda *args, **kwargs: invalid
        self.assertFalse(client.check_capabilities().executable)


if __name__ == "__main__":
    unittest.main()
