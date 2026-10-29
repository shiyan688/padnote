from __future__ import annotations

import base64
import hashlib
import io
import json
import tempfile
import threading
import time
import unittest
import zipfile
from pathlib import Path

from padnote_assistant.bridge import BridgeService
from padnote_assistant.bundles import input_snapshot_digest, prepare_task_directory
from padnote_assistant.hermes import HermesError
from padnote_assistant.security import canonical_json
from padnote_assistant.state import AuthorizationError
from padnote_assistant.wsl_hermes import WslHermesError


DISTRO_GUID = "806d943b-0882-43b5-a010-1f092cfa8b22"


class FakeHermes:
    def __init__(self, *, response_status: str = "started", error: Exception | None = None):
        self.calls: list[tuple[str, str, dict]] = []
        self.lock = threading.Lock()
        self.response_status = response_status
        self.error = error

    def create_run(self, input_text: str, idempotency_key: str, **kwargs):
        with self.lock:
            self.calls.append((input_text, idempotency_key, kwargs))
            if self.error:
                raise self.error
            return {"run_id": f"upstream-{len(self.calls)}", "status": self.response_status}


class FakeTaskProjector:
    def __init__(self, *, fail: Exception | None = None, block: bool = False,
                 output_bytes: bytes | None = None, projection_override: dict | None = None):
        self.fail = fail
        self.output_bytes = output_bytes
        self.projection_override = projection_override or {}
        self.entered = threading.Event()
        self.release = threading.Event()
        if not block:
            self.release.set()
        self.contexts = []

    def project_task_context(self, context):
        self.contexts.append(context)
        self.entered.set()
        if not self.release.wait(timeout=3):
            raise AssertionError("test did not release WSL task projection")
        if self.fail:
            raise self.fail
        if self.output_bytes is not None:
            output = Path(context.task_root) / "output" / "synthetic-result.txt"
            output.write_bytes(self.output_bytes)
        task = f"/mnt/c/PadNoteQA/tasks/{context.task_id}"
        parent = (f"/mnt/c/PadNoteQA/tasks/{context.conversation_id}/input"
                  if context.conversation_id else None)
        result = {
            "task_id": context.task_id,
            "instance_id": context.instance_id,
            "instance_binding_sha256": context.instance_binding_sha256,
            "distro_id": context.distro_id.lower(),
            "uid": context.distro_uid,
            "profile": context.profile_id,
            "profile_home": context.profile_home,
            "input_digest": context.input_digest,
            "conversation_id": context.conversation_id,
            "parent_digest": context.parent_digest,
            "parent_readonly_enforced": False,
            "tasks_root": "/mnt/c/PadNoteQA/tasks",
            "task_root": task,
            "input_root": task + "/input",
            "output_root": task + "/output",
            "parent_input_root": parent,
        }
        result.update(self.projection_override)
        return result


def _note_bundle() -> tuple[str, str]:
    content = b"Synthetic WSL bridge note.\n"
    paper = b"%PDF-1.7\nsynthetic test fixture\n%%EOF\n"
    rows = []
    for path, media_type, raw in (("input/content.md", "text/markdown", content),
                                  ("input/paper.pdf", "application/pdf", paper)):
        rows.append({"path": path, "media_type": media_type,
                     "size_bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
    digest = hashlib.sha256("".join(
        f"{row['path']}\0{row['size_bytes']}\0{row['sha256']}\n" for row in rows
    ).encode("utf-8")).hexdigest()
    request = {
        "schema_version": "1.0", "task_type": "note.work.v1",
        "source": {"note_id": "synthetic-note", "note_revision": 1,
                   "title": "Synthetic note", "entrypoint": "input/content.md",
                   "bundle_sha256": digest},
        "brief": {"instruction": "Summarize the synthetic paper", "preset_id": "summary"},
    }
    manifest = {"schema_version": "1.0", "files": rows}
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("request.json", json.dumps(request))
        archive.writestr("input/manifest.json", json.dumps(manifest))
        archive.writestr("input/content.md", content)
        archive.writestr("input/paper.pdf", paper)
        archive.writestr("work/.keep", b"")
        archive.writestr("output/.keep", b"")
    raw = output.getvalue()
    return base64.b64encode(raw).decode("ascii"), hashlib.sha256(raw).hexdigest()


def _wsl_service(directory: str, *, async_submission: bool = False):
    service = BridgeService(Path(directory), async_submission=async_submission)
    key = "synthetic-wsl-key"
    key_fingerprint = service._key_fingerprint(key)
    config_ref = (f"wsl://{DISTRO_GUID}/1000/default?home=%2Fhome%2Fqa%2F.hermes"
                  "&mode=default&prefix=")
    base_url = "http://127.0.0.1:8642"
    binding = hashlib.sha256(canonical_json({
        "config_ref": config_ref,
        "base_url": base_url,
        "api_prefix": "",
        "key_fingerprint": key_fingerprint,
    })).hexdigest()
    instance = service.store.add_instance(
        "hermes", "Synthetic WSL Hermes", base_url,
        config_ref=config_ref, config_fingerprint=binding,
        credential_fingerprint=key_fingerprint)
    service.vault.set(instance["instance_id"], key)
    service.store.update_instance_check(
        instance["instance_id"], health="ready", detail="synthetic",
        features={"run_submission": True, "run_status": True, "run_stop": True,
                  "task_bundle": True, "note_context_bundle": True}, executable=True)
    code, _ = service.store.create_pair_code(instance["instance_id"], "https://computer.example.ts.net")
    pairing = service.store.request_pair(code, "synthetic-device", "Synthetic tablet")
    service.store.decide_pair(pairing["request_id"], True)
    _, claim = service.store.claim_pair(pairing["request_id"], pairing["poll_token"])
    return service, instance, claim["connections"][0]["token"]


def _wait_for(service: BridgeService, task_id: str, predicate, timeout: float = 3.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        run = service.store.snapshot()["runs"].get(task_id)
        if run and predicate(run):
            return run
        time.sleep(0.01)
    raise AssertionError("submission worker did not reach the expected state")


def _wait_worker_finished(service: BridgeService, task_id: str, timeout: float = 3.0):
    target_name = "padnote-submit-" + task_id
    deadline = time.monotonic() + timeout
    with service._submission_condition:
        while any(worker.name == target_name for worker in service._submission_workers):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise AssertionError("submission worker did not finish")
            service._submission_condition.wait(timeout=remaining)


class WslTaskContextBridgeTests(unittest.TestCase):
    def test_note_bundle_uses_guest_paths_and_does_not_persist_absolute_prompt(self):
        with tempfile.TemporaryDirectory() as directory:
            service, instance, token = _wsl_service(directory)
            hermes = FakeHermes()
            projector = FakeTaskProjector(block=True)
            service.wsl_hermes.project_task_context = projector.project_task_context
            service._hermes = lambda *_args, **_kwargs: hermes
            bundle, bundle_sha = _note_bundle()
            payload = {
                "client_task_id": "note-task-bridge-1", "title": "Read synthetic note",
                "input": "Summarize this paper", "source": {"note_id": "synthetic-note", "note_revision": 1},
                "required_capability": "note_context_bundle",
                "bundle_base64": bundle, "bundle_sha256": bundle_sha,
            }
            try:
                status, receipt = service.submit_run(instance["instance_id"], token,
                                                     payload["client_task_id"], payload)
                self.assertEqual(202, status)
                self.assertTrue(projector.entered.wait(timeout=2))
                run = service.store.snapshot()["runs"][receipt["task_id"]]
                self.assertIsNone(run.get("upstream_payload"))
                self.assertEqual([], hermes.calls)
                context = projector.contexts[0]
                self.assertEqual(
                    {"content.md", "manifest.json", "paper.pdf"},
                    {item.relative_path for item in context.input_files})

                projector.release.set()
                saved = _wait_for(service, receipt["task_id"], lambda item: bool(item.get("upstream_run_id")))
                self.assertEqual("upstream-1", saved["upstream_run_id"])
                self.assertEqual(1, len(hermes.calls))
                text, key, options = hermes.calls[0]
                self.assertIn("/mnt/c/PadNoteQA/tasks/", text)
                self.assertIn("/input/content.md", text)
                self.assertIn("/input/paper.pdf", text)
                self.assertIn("/output/", text)
                self.assertNotIn("C:\\", text)
                self.assertEqual("note-task-bridge-1", payload["client_task_id"])
                self.assertEqual(saved["upstream_idempotency_key"], key)
                self.assertIsNone(options.get("session_id"))
                self.assertIsNone(saved.get("upstream_payload"))
            finally:
                projector.release.set()
                service.close()

    def test_preflight_failure_is_known_not_sent_and_same_id_is_not_replayed(self):
        with tempfile.TemporaryDirectory() as directory:
            service, instance, token = _wsl_service(directory)
            hermes = FakeHermes()
            projector = FakeTaskProjector(fail=WslHermesError(
                "wsl_task_path_unavailable", "synthetic inaccessible task path"))
            service.wsl_hermes.project_task_context = projector.project_task_context
            service._hermes = lambda *_args, **_kwargs: hermes
            payload = {"client_task_id": "task-preflight-fail", "title": "Synthetic",
                       "input": "No model call", "source": {"note_id": "n", "note_revision": 1}}
            try:
                _status, receipt = service.submit_run(
                    instance["instance_id"], token, payload["client_task_id"], payload)
                run = _wait_for(service, receipt["task_id"], lambda item: item.get("status") == "failed")
                self.assertFalse(run.get("submission_uncertain"))
                self.assertIn("Hermes未收到请求", run.get("error", ""))
                self.assertEqual([], hermes.calls)
                self.assertEqual(1, len(projector.contexts))

                retry_status, retry = service.submit_run(
                    instance["instance_id"], token, payload["client_task_id"], payload)
                self.assertEqual(202, retry_status)
                self.assertEqual(receipt["task_id"], retry["task_id"])
                self.assertEqual(1, len(projector.contexts))
                self.assertEqual([], hermes.calls)
            finally:
                service.close()

    def test_cancellation_during_projection_blocks_post(self):
        with tempfile.TemporaryDirectory() as directory:
            service, instance, token = _wsl_service(directory)
            hermes = FakeHermes()
            projector = FakeTaskProjector(block=True)
            service.wsl_hermes.project_task_context = projector.project_task_context
            service._hermes = lambda *_args, **_kwargs: hermes
            payload = {"client_task_id": "task-cancel-during-preflight", "title": "Synthetic",
                       "input": "No model call", "source": {"note_id": "n", "note_revision": 1}}
            try:
                _status, receipt = service.submit_run(
                    instance["instance_id"], token, payload["client_task_id"], payload)
                self.assertTrue(projector.entered.wait(timeout=2))
                service.store.update_run(receipt["task_id"], status="cancelled", submission_uncertain=False)
                projector.release.set()
                run = _wait_for(service, receipt["task_id"], lambda item: item.get("status") == "cancelled")
                _wait_worker_finished(service, receipt["task_id"])
                self.assertEqual("cancelled", run["status"])
                self.assertEqual([], hermes.calls)
            finally:
                projector.release.set()
                service.close()

    def test_instance_retirement_during_projection_blocks_post(self):
        with tempfile.TemporaryDirectory() as directory:
            service, instance, token = _wsl_service(directory)
            hermes = FakeHermes()
            projector = FakeTaskProjector(block=True)
            service.wsl_hermes.project_task_context = projector.project_task_context
            service._hermes = lambda *_args, **_kwargs: hermes
            payload = {"client_task_id": "task-retired-during-preflight", "title": "Synthetic",
                       "input": "No model call", "source": {"note_id": "n", "note_revision": 1}}
            try:
                _status, receipt = service.submit_run(
                    instance["instance_id"], token, payload["client_task_id"], payload)
                self.assertTrue(projector.entered.wait(timeout=2))
                service.store.retire_instance(instance["instance_id"], "synthetic profile replacement")
                projector.release.set()
                run = _wait_for(service, receipt["task_id"], lambda item: item.get("status") == "failed")
                _wait_worker_finished(service, receipt["task_id"])
                self.assertFalse(run.get("submission_uncertain"))
                self.assertEqual([], hermes.calls)
            finally:
                projector.release.set()
                service.close()

    def test_followup_projects_parent_input_and_child_output_without_changing_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            service, instance, token = _wsl_service(directory)
            hermes = FakeHermes()
            projector = FakeTaskProjector()
            service.wsl_hermes.project_task_context = projector.project_task_context
            service._hermes = lambda *_args, **_kwargs: hermes
            connection = service.store.authenticate(instance["instance_id"], token)
            source = {"note_id": "synthetic-parent", "note_revision": 9}
            parent_payload = {"client_task_id": "parent-client", "title": "Parent",
                              "input": "Parent fixture", "source": source}
            try:
                parent, created = service.store.create_or_get_run(
                    connection, "parent-client", parent_payload)
                self.assertTrue(created)
                parent_dir = prepare_task_directory(
                    service.store.tasks_dir, parent["task_id"], parent_payload, None, None)
                parent_digest = input_snapshot_digest(parent_dir / "input")
                service.store.update_run(parent["task_id"], status="completed",
                                         session_id="parent-session",
                                         source_snapshot_digest=parent_digest)

                payload = {"client_task_id": "followup-client", "title": "Follow up",
                           "input": "Continue the parent work", "source": source,
                           "parent_task_id": parent["task_id"]}
                _status, receipt = service.submit_run(
                    instance["instance_id"], token, payload["client_task_id"], payload)
                saved = _wait_for(service, receipt["task_id"], lambda item: bool(item.get("upstream_run_id")))
                self.assertEqual(parent["task_id"], saved["conversation_id"])
                self.assertEqual(parent["task_id"], projector.contexts[0].conversation_id)
                self.assertEqual(parent_digest, projector.contexts[0].parent_digest)
                self.assertEqual((), projector.contexts[0].input_files)
                prompt, _key, options = hermes.calls[0]
                self.assertIn(f"/tasks/{parent['task_id']}/input", prompt)
                self.assertIn(f"/tasks/{receipt['task_id']}/output", prompt)
                self.assertNotEqual(parent["task_id"], receipt["task_id"])
                self.assertEqual("parent-session", options.get("session_id"))
                self.assertIsNone(saved.get("upstream_payload"))
            finally:
                service.close()

    def test_revoke_or_rebind_during_projection_blocks_post(self):
        for change in ("revoke", "fingerprint"):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                service, instance, token = _wsl_service(directory)
                hermes = FakeHermes()
                projector = FakeTaskProjector(block=True)
                service.wsl_hermes.project_task_context = projector.project_task_context
                service._hermes = lambda *_args, **_kwargs: hermes
                payload = {"client_task_id": "task-identity-" + change, "title": "Synthetic",
                           "input": "No model call", "source": {"note_id": "n", "note_revision": 1}}
                try:
                    _status, receipt = service.submit_run(
                        instance["instance_id"], token, payload["client_task_id"], payload)
                    self.assertTrue(projector.entered.wait(timeout=2))
                    if change == "revoke":
                        connection = service.store.authenticate(instance["instance_id"], token)
                        service.store.revoke_connection(connection["connection_id"])
                    else:
                        service.store.attach_instance_config(
                            instance["instance_id"], instance["config_ref"], "a" * 64)
                    projector.release.set()
                    run = _wait_for(
                        service, receipt["task_id"], lambda item: item.get("status") == "failed")
                    _wait_worker_finished(service, receipt["task_id"])
                    self.assertFalse(run.get("submission_uncertain"))
                    self.assertEqual([], hermes.calls)
                finally:
                    projector.release.set()
                    service.close()

    def test_projection_identity_and_owned_paths_must_match_context(self):
        mismatches = {
            "distro_id": "{aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa}",
            "uid": True,
            "profile": "other-profile",
            "profile_home": "/home/other/.hermes",
            "task_root": "/mnt/c/PadNoteQA/tasks/unrelated",
            "input_root": "/mnt/c/PadNoteQA/tasks/task/input",
            "output_root": "/mnt/c/PadNoteQA/tasks/other/output",
        }
        for field, value in mismatches.items():
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                service, instance, token = _wsl_service(directory)
                hermes = FakeHermes()
                projector = FakeTaskProjector(projection_override={field: value})
                service.wsl_hermes.project_task_context = projector.project_task_context
                service._hermes = lambda *_args, **_kwargs: hermes
                payload = {"client_task_id": "task-projection-" + field, "title": "Synthetic",
                           "input": "Must not submit", "source": {"note_id": "n", "note_revision": 1}}
                try:
                    _status, receipt = service.submit_run(
                        instance["instance_id"], token, payload["client_task_id"], payload)
                    failed = _wait_for(
                        service, receipt["task_id"], lambda item: item.get("status") == "failed")
                    _wait_worker_finished(service, receipt["task_id"])
                    self.assertFalse(failed.get("submission_uncertain"))
                    self.assertEqual([], hermes.calls)
                finally:
                    service.close()

    def test_post_unknown_is_not_replayed_for_same_client_id(self):
        with tempfile.TemporaryDirectory() as directory:
            service, instance, token = _wsl_service(directory)
            hermes = FakeHermes(error=HermesError(
                "upstream_timeout", "synthetic timeout after POST", uncertain=True))
            projector = FakeTaskProjector()
            service.wsl_hermes.project_task_context = projector.project_task_context
            service._hermes = lambda *_args, **_kwargs: hermes
            payload = {"client_task_id": "task-post-unknown", "title": "Synthetic",
                       "input": "No automatic retry", "source": {"note_id": "n", "note_revision": 1}}
            try:
                _status, receipt = service.submit_run(
                    instance["instance_id"], token, payload["client_task_id"], payload)
                unknown = _wait_for(
                    service, receipt["task_id"],
                    lambda item: item.get("submission_uncertain") and bool(item.get("error")))
                _wait_worker_finished(service, receipt["task_id"])
                self.assertEqual("submitting", unknown["status"])
                self.assertEqual(1, len(hermes.calls))

                _status, same = service.submit_run(
                    instance["instance_id"], token, payload["client_task_id"], payload)
                self.assertEqual(receipt["task_id"], same["task_id"])
                self.assertEqual("submitting", same["status"])
                self.assertIn("无法确认是否已接收", same["error"])
                self.assertEqual(1, len(hermes.calls))
                self.assertEqual(1, len(projector.contexts))
            finally:
                service.close()

    def test_guest_output_is_collected_only_for_owning_task(self):
        with tempfile.TemporaryDirectory() as directory:
            service, instance, token = _wsl_service(directory)
            result_bytes = b"synthetic WSL deliverable\n"
            projector = FakeTaskProjector(output_bytes=result_bytes)
            hermes = FakeHermes(response_status="completed")
            service.wsl_hermes.project_task_context = projector.project_task_context
            service._hermes = lambda *_args, **_kwargs: hermes
            payload = {"client_task_id": "task-output-owner", "title": "Synthetic output",
                       "input": "Write the fixture", "source": {"note_id": "n", "note_revision": 1}}
            try:
                _status, receipt = service.submit_run(
                    instance["instance_id"], token, payload["client_task_id"], payload)
                completed = _wait_for(
                    service, receipt["task_id"], lambda item: item.get("status") == "completed")
                _wait_worker_finished(service, receipt["task_id"])
                artifact = next(item for item in completed["artifacts"]
                                if item["name"] == "synthetic-result.txt")
                stream, metadata = service.get_artifact(
                    instance["instance_id"], token, receipt["task_id"], artifact["id"])
                try:
                    self.assertEqual(result_bytes, stream.read())
                    self.assertEqual(artifact["sha256"], metadata["sha256"])
                finally:
                    stream.close()
                with self.assertRaises(AuthorizationError):
                    service.get_artifact(instance["instance_id"], token,
                                         "not-the-owning-task", artifact["id"])
            finally:
                service.close()


if __name__ == "__main__":
    unittest.main()
