from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest import mock

from padnote_assistant.bridge import BridgeService
from padnote_assistant.bundles import collect_artifacts
from padnote_assistant.security import ValidationError
from padnote_assistant.state import ConflictError, StateStore


def payload(**extra):
    value = {
        "client_task_id": "delivery-task-1",
        "title": "Draw a graph",
        "input": "Return an image",
        "source": {"note_id": "note-1", "note_revision": 1},
    }
    value.update(extra)
    return value


def artifact(media_type: str, size_bytes: int = 4, name: str = "result.bin"):
    return {"id": "a" * 32, "name": name, "media_type": media_type,
            "size_bytes": size_bytes, "sha256": "b" * 64}


def ready_connection(service: BridgeService):
    instance = service.add_instance(
        "hermes", "Fixture Hermes", "http://127.0.0.1:8642", "fixture-key")
    service.store.update_instance_check(
        instance["instance_id"], health="ready", detail="ready",
        features={"run_submission": True}, executable=True)
    code, _ = service.store.create_pair_code(
        instance["instance_id"], "https://example.invalid")
    request = service.store.request_pair(code, "delivery-device", "Test tablet")
    service.store.decide_pair(request["request_id"], True)
    _status, claim = service.store.claim_pair(request["request_id"], request["poll_token"])
    token = claim["connections"][0]["token"]
    return instance, token


class DeliveryContractTests(unittest.TestCase):
    def test_expectations_are_optional_canonical_and_strict(self):
        base = BridgeService._validate_run_payload(payload(), "delivery-task-1")
        self.assertNotIn("expected_outputs", base)
        self.assertNotIn("expected_outputs", BridgeService._validate_run_payload(
            payload(expected_outputs=[]), "delivery-task-1"))
        clean = BridgeService._validate_run_payload(
            payload(expected_outputs=["pdf", "image"]), "delivery-task-1")
        self.assertEqual(["image", "pdf"], clean["expected_outputs"])
        for value in (None, "image", ["image", "image"], ["svg"], [1], ["file"] * 6):
            with self.subTest(value=value), self.assertRaises(ValidationError):
                BridgeService._validate_run_payload(
                    payload(expected_outputs=value), "delivery-task-1")

    def test_followup_can_request_delivery_but_cannot_bundle_or_set_capability(self):
        clean = BridgeService._validate_run_payload(
            payload(parent_task_id="parent-1", expected_outputs=["image"]), "delivery-task-1")
        self.assertEqual(["image"], clean["expected_outputs"])
        for extra in (
            {"parent_task_id": "parent-1", "bundle_base64": "eA==", "bundle_sha256": "a" * 64},
            {"parent_task_id": "parent-1", "required_capability": "note_context_bundle"},
        ):
            with self.subTest(extra=extra), self.assertRaises(ValidationError):
                BridgeService._validate_run_payload(payload(**extra), "delivery-task-1")

    def test_idempotency_includes_expectations_and_run_persists_them(self):
        with tempfile.TemporaryDirectory() as directory, StateStore(Path(directory)) as store:
            instance = store.add_instance("hermes", "Fixture", "http://127.0.0.1:8642")
            store.update_instance_check(
                instance["instance_id"], health="ready", detail="ready",
                features={"run_submission": True}, executable=True)
            code, _ = store.create_pair_code(instance["instance_id"], "https://example.invalid")
            request = store.request_pair(code, "device-1", "Tablet")
            store.decide_pair(request["request_id"], True)
            _status, claim = store.claim_pair(request["request_id"], request["poll_token"])
            connection = store.authenticate(instance["instance_id"], claim["connections"][0]["token"])
            first_payload = {"source": {"note_id": "note-1", "note_revision": 1},
                             "expected_outputs": ["image"]}
            first, created = store.create_or_get_run(connection, "delivery-task-1", first_payload)
            self.assertTrue(created)
            self.assertEqual(["image"], first["expected_outputs"])
            with self.assertRaises(ConflictError):
                store.create_or_get_run(connection, "delivery-task-1", {
                    "source": {"note_id": "note-1", "note_revision": 1},
                    "expected_outputs": ["pdf"],
                })

    def test_delivery_uses_saved_artifact_snapshot_and_keeps_execution_status_separate(self):
        def project(status, expected, artifacts):
            return BridgeService._delivery_projection({
                "status": status, "expected_outputs": expected, "artifacts": artifacts,
            })

        self.assertEqual({"status": "not_requested", "expected": [], "missing": [], "artifact_count": 0},
                         project("completed", [], []))
        self.assertEqual("pending", project("running", ["image"], [])["status"])
        self.assertEqual("pending", project("failed", ["image"], [])["status"])
        empty_or_malformed = project("completed", ["file", "image"], [
            artifact("image/png", 0),
            artifact("image/jpeg", True),
            {"media_type": "image/jpeg", "size_bytes": 1},
        ])
        self.assertEqual("missing", empty_or_malformed["status"])
        self.assertEqual(["file", "image"], empty_or_malformed["missing"])
        self.assertEqual("missing", project("completed", ["image", "pdf"], [
            artifact("image/png"),
        ])["status"])
        self.assertEqual(["pdf"], project("completed", ["image", "pdf"], [
            artifact("image/jpeg"),
        ])["missing"])
        self.assertEqual("delivered", project("completed", ["file", "image", "pdf", "video", "audio"], [
            artifact("image/png", 1, "image.png"),
            artifact("application/pdf", 1, "result.pdf"),
            artifact("video/mp4", 1, "video.mp4"),
            artifact("audio/mpeg", 1, "audio.mp3"),
        ])["status"])

    def test_public_run_includes_projection_and_capability_is_limited_to_submitting_hermes(self):
        with tempfile.TemporaryDirectory() as directory, BridgeService(Path(directory)) as service:
            run = {"task_id": "task-1", "instance_id": "instance-1", "status": "completed",
                   "source": {"note_id": "note-1", "note_revision": 1}, "artifacts": [],
                   "expected_outputs": ["pdf"], "conversation_id": "task-1"}
            self.assertEqual("missing", service._public_run(run)["delivery"]["status"])
            instance = service.add_instance("hermes", "Fixture", "http://127.0.0.1:8642", "test-key")
            service.store.update_instance_check(
                instance["instance_id"], health="ready", detail="ready",
                features={"run_submission": True}, executable=True)
            code, _ = service.store.create_pair_code(instance["instance_id"], "https://example.invalid")
            request = service.store.request_pair(code, "device-1", "Tablet")
            service.store.decide_pair(request["request_id"], True)
            _status, claim = service.store.claim_pair(request["request_id"], request["poll_token"])
            features = service.capabilities(
                instance["instance_id"], claim["connections"][0]["token"])["features"]
            self.assertTrue(features["delivery_contract_v1"])
            service.store.update_instance_check(
                instance["instance_id"], health="ready", detail="read only",
                features={"run_submission": False}, executable=True)
            features = service.capabilities(
                instance["instance_id"], claim["connections"][0]["token"])["features"]
            self.assertFalse(features["delivery_contract_v1"])

    def test_completed_submission_persists_valid_post_session_for_follow_up(self):
        class FakeHermes:
            def __init__(self):
                self.create_calls = 0
                self.get_calls = 0

            def create_run(self, upstream_input, _key, *, session_id=None):
                self.create_calls += 1
                marker = "PadNote task workspace: "
                workspace = upstream_input.split(marker, 1)[1].splitlines()[0]
                output = Path(workspace) / "output"
                output.mkdir(parents=True, exist_ok=True)
                (output / "result.png").write_bytes(b"\x89PNG\r\n\x1a\nfixture")
                return {
                    "id": "fixture-completed-run",
                    "status": "completed",
                    "output": "synthetic result",
                    "session_id": "fixture-post-session",
                }

            def get_run(self, run_id):
                self.get_calls += 1
                return {"id": run_id, "status": "completed",
                        "session_id": "fixture-post-session"}

        with tempfile.TemporaryDirectory() as directory, BridgeService(Path(directory)) as service:
            instance, token = ready_connection(service)
            client = FakeHermes()
            with mock.patch.object(service, "_hermes", return_value=client):
                _status, response = service.submit_run(
                    instance["instance_id"], token, "completed-session-task",
                    payload(client_task_id="completed-session-task", expected_outputs=["image"]))

            self.assertEqual("completed", response["status"])
            self.assertTrue(response["followup_available"])
            self.assertEqual("delivered", response["delivery"]["status"])
            run = service.store.owned_run(
                service.store.authenticate(instance["instance_id"], token), response["task_id"])
            self.assertEqual("fixture-post-session", run["session_id"])
            self.assertEqual(1, client.create_calls)
            self.assertEqual(0, client.get_calls)

    def test_completed_without_post_session_can_gain_session_on_get_without_snapshot_changes(self):
        class FakeHermes:
            def __init__(self):
                self.create_calls = 0
                self.get_calls = 0

            def create_run(self, upstream_input, _key, *, session_id=None):
                self.create_calls += 1
                marker = "PadNote task workspace: "
                workspace = upstream_input.split(marker, 1)[1].splitlines()[0]
                output = Path(workspace) / "output"
                output.mkdir(parents=True, exist_ok=True)
                (output / "result.png").write_bytes(b"\x89PNG\r\n\x1a\nfixture")
                return {"id": "fixture-completed-run", "status": "completed",
                        "output": "synthetic result"}

            def get_run(self, run_id):
                self.get_calls += 1
                return {"id": run_id, "status": "completed",
                        "session_id": "fixture-late-session"}

        with tempfile.TemporaryDirectory() as directory, BridgeService(Path(directory)) as service:
            instance, token = ready_connection(service)
            client = FakeHermes()
            with mock.patch.object(service, "_hermes", return_value=client):
                _status, response = service.submit_run(
                    instance["instance_id"], token, "late-session-task",
                    payload(client_task_id="late-session-task", expected_outputs=["image"]))
                self.assertEqual("completed", response["status"])
                self.assertFalse(response["followup_available"])

                connection = service.store.authenticate(instance["instance_id"], token)
                before = service.store.owned_run(connection, response["task_id"])
                source_snapshot = before["source_snapshot_digest"]
                artifact_snapshot = before["artifacts"]
                refreshed = service.get_run(instance["instance_id"], token, response["task_id"])
                after = service.store.owned_run(connection, response["task_id"])

            self.assertEqual("completed", refreshed["status"])
            self.assertTrue(refreshed["followup_available"])
            self.assertEqual("fixture-late-session", after["session_id"])
            self.assertEqual(source_snapshot, after["source_snapshot_digest"])
            self.assertEqual(artifact_snapshot, after["artifacts"])
            self.assertEqual(1, client.create_calls)
            self.assertEqual(1, client.get_calls)

    def test_native_and_wsl_instructions_use_absolute_current_turn_output_and_verify_files(self):
        with tempfile.TemporaryDirectory() as directory:
            task = Path(directory) / "task"
            task.mkdir()
            native = BridgeService._upstream_input(
                payload(expected_outputs=["image"]), task)
            self.assertIn(str((task / "output").resolve()), native)
            self.assertIn("exists, is readable", native)
            self.assertIn("PNG or JPEG", native)
            ordinary = BridgeService._upstream_input(payload(), task)
            self.assertNotIn("The user requested:", ordinary)
            projected = {"task_root": "/mnt/padnote/tasks/task",
                         "input_root": "/mnt/padnote/tasks/task/input",
                         "output_root": "/mnt/padnote/tasks/task/output",
                         "parent_input_root": None}
            wsl = BridgeService._wsl_upstream_input(
                payload(expected_outputs=["pdf"]), task, projected)
            self.assertIn("/mnt/padnote/tasks/task/output", wsl)
            self.assertIn("PDF document", wsl)
            self.assertIn("exists, is readable", wsl)

    def test_audio_collector_adds_only_explicit_safe_extensions(self):
        with tempfile.TemporaryDirectory() as directory:
            task = Path(directory) / "task"
            output = task / "output"
            output.mkdir(parents=True)
            for name in ("sample.wav", "sample.mp3", "sample.m4a", "sample.ogg", "sample.flac"):
                (output / name).write_bytes(b"synthetic")
            (output / "unknown.bin").write_bytes(b"not exposed")
            artifacts = collect_artifacts(task, "task")
            self.assertEqual({"audio/wav", "audio/mpeg", "audio/mp4", "audio/ogg", "audio/flac"},
                             {item["media_type"] for item in artifacts})
            self.assertEqual(5, len(artifacts))


if __name__ == "__main__":
    unittest.main()
