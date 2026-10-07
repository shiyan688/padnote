from __future__ import annotations

import base64
import hashlib
import http.client
import json
import tempfile
import threading
import time
import unittest
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

from padnote_assistant.bridge import BridgeService
from padnote_assistant.bundles import input_snapshot_digest, prepare_task_directory
from padnote_assistant.security import canonical_json
from padnote_assistant.video_preview import binding_from_submission
from padnote_assistant.video_worker import VideoWorkerError
from padnote_assistant.web import API_PREFIX, ServerGroup


REPO = Path(__file__).resolve().parents[3]
BUNDLE = REPO / "desktop/connection-assistant/tests/fixtures/android-video-task.zip"


class StubVideoWorker:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, str]] = []
        self.entered = None
        self.release = None
        self.mode = "success"
        self.reconcile_mode = "unconfirmed"
        self.reconcile_result = None
        self.storyboard_entered = None
        self.storyboard_release = None
        self.storyboard_mode = "success"
        self.cancel_mode = "verified_cancelled"
        self.cancel_calls = []
        self.cancel_entered = None
        self.cancel_release = None

    def initialize_exact(self, task_id: str, worker_task_id: str, request_sha256: str,
                         **operation_binding: Any) -> dict[str, Any]:
        self.calls.append((task_id, worker_task_id, request_sha256))
        if self.entered is not None:
            self.entered.set()
        if self.release is not None:
            self.release.wait(3)
        if self.mode == "unknown":
            raise VideoWorkerError("worker_timeout", "private child output", unknown=True)
        return {
            "protocol_version": 1, "task_id": worker_task_id,
            "status": "initialized", "phase": "idle", "event_cursor": 1,
        }

    def reconcile_exact(self, task_id, worker_task_id, request_sha256, operation):
        self.calls.append(("reconcile", operation["operation_id"], operation["attempt_id"]))
        return {
            "schema_version": 1,
            "outcome": self.reconcile_mode,
            "reason": ("receipt_match" if self.reconcile_mode == "verified_completed"
                       else "cancel_receipt_match" if self.reconcile_mode == "verified_cancelled"
                       else "receipt_missing"),
            "operation_id": operation["operation_id"],
            "attempt_id": operation["attempt_id"],
            "task_id": worker_task_id,
            "action": operation["action"],
            "payload_digest": operation["payload_digest"],
            "request_sha256": request_sha256,
            "source_snapshot_digest": operation["source_snapshot_digest"],
            "result": self.reconcile_result if self.reconcile_mode == "verified_completed" else None,
        }

    def build_storyboard_exact(self, task_id, worker_task_id, request_sha256, revision,
                               event_cursor, **operation_binding):
        if self.storyboard_entered is not None:
            self.storyboard_entered.set()
        if self.storyboard_release is not None:
            self.storyboard_release.wait(3)
        if self.storyboard_mode == "unknown":
            raise VideoWorkerError("worker_timeout", "private child output", unknown=True)
        return {"protocol_version": 1, "task_id": worker_task_id,
                "status": "awaiting_storyboard_review", "phase": "awaiting_approval",
                "event_cursor": event_cursor + 2, "revision": revision,
                "review_sha256": "a" * 64, "lesson_ir_sha256": "b" * 64}

    def cancel_running_storyboard(self, task_id, worker_task_id, request_sha256, operation):
        self.cancel_calls.append((task_id, operation["operation_id"], operation["attempt_id"]))
        if self.cancel_entered is not None:
            self.cancel_entered.set()
        if self.cancel_release is not None:
            self.cancel_release.wait(3)
        return {"object": "padnote.video.cancel", "protocol_version": 1,
                "operation_id": operation["operation_id"],
                "attempt_id": operation["attempt_id"], "status": self.cancel_mode,
                "reason": "cancel_receipt_match" if self.cancel_mode == "verified_cancelled"
                else "cancel_pending"}


class VideoOperationHTTPTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-video-operations-http-")
        self.state_dir = Path(self.temp.name).resolve()
        self.service = BridgeService(self.state_dir)
        self.instance = self.service.add_instance(
            "hermes", "Fixture Hermes", "http://127.0.0.1:8642", "fixture-key")
        self.service.store.update_instance_check(
            self.instance["instance_id"], health="ready", detail="ready",
            features={"run_submission": True, "run_status": True, "task_bundle": True}, executable=True)
        self.token, self.connection = self._pair("video-device-a")
        self.other_token, self.other_connection = self._pair("video-device-b")
        self.payload, self.task_id, self.binding = self._make_bound_run(self.connection, "primary")
        self.worker = StubVideoWorker()
        self.service.video_worker = self.worker
        self.service._start_video_scheduler()
        self.group = ServerGroup(self.service, admin_port=0, api_port=0)
        self.group.start()

    def tearDown(self) -> None:
        worker = getattr(self, "worker", None)
        if worker is not None and worker.release is not None:
            worker.release.set()
        group = getattr(self, "group", None)
        if group is not None:
            group.close()
        service = getattr(self, "service", None)
        if service is not None:
            service.close()
        self.temp.cleanup()

    def _pair(self, device_id: str) -> tuple[str, dict[str, Any]]:
        code, _ = self.service.store.create_pair_code(
            self.instance["instance_id"], "https://computer.example.ts.net")
        request = self.service.store.request_pair(code, device_id, "Fixture Tablet")
        self.service.store.decide_pair(request["request_id"], True)
        _, claim = self.service.store.claim_pair(request["request_id"], request["poll_token"])
        token = claim["connections"][0]["token"]
        return token, self.service.store.authenticate(self.instance["instance_id"], token)

    def _make_bound_run(self, connection: dict[str, Any], label: str) -> tuple[dict[str, Any], str, dict[str, str]]:
        raw_bundle = BUNDLE.read_bytes()
        with zipfile.ZipFile(BUNDLE) as archive:
            request = json.loads(archive.read("request.json"))
        payload = {
            "client_task_id": f"http-video-operation-{connection['device_id']}-{label}",
            "title": "Fixture video operation", "input": "Synthetic request, no Hermes call",
            "source": {"note_id": request["source"]["note_id"],
                       "note_revision": request["source"]["note_revision"]},
            "bundle_base64": base64.b64encode(raw_bundle).decode("ascii"),
            "bundle_sha256": hashlib.sha256(raw_bundle).hexdigest(),
        }
        run, _created = self.service.store.create_or_get_run(
            connection, payload["client_task_id"], payload)
        task_root = prepare_task_directory(
            self.service.store.tasks_dir, run["task_id"], payload,
            payload["bundle_base64"], payload["bundle_sha256"])
        binding = binding_from_submission(payload)
        assert binding is not None
        self.service.store.update_run(
            run["task_id"], status="completed", session_id="synthetic-session",
            source_snapshot_digest=input_snapshot_digest(task_root / "input"),
            video_binding=binding)
        return payload, run["task_id"], binding

    def _request(self, method: str, path: str, *, token: str | None = None,
                 body: dict[str, Any] | None = None, key: str | None = None) -> tuple[int, dict[str, Any]]:
        port = self.group.api.server_address[1]
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=4)
        headers = {"Host": f"127.0.0.1:{port}"}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        if body is not None:
            headers["Content-Type"] = "application/json"
        if key is not None:
            headers["Idempotency-Key"] = key
        encoded = None if body is None else canonical_json(body)
        connection.request(method, path, body=encoded, headers=headers)
        response = connection.getresponse()
        value = json.loads(response.read().decode("utf-8"))
        status = response.status
        connection.close()
        return status, value

    def _path(self, task_id: str | None = None, operation_id: str | None = None,
              suffix: str = "") -> str:
        path = (f"{API_PREFIX}/agents/{self.instance['instance_id']}/runs/"
                f"{task_id or self.task_id}/video/operations")
        if operation_id:
            path += f"/{operation_id}"
        return path + suffix

    def _wait_status(self, operation_id: str, expected: str, timeout: float = 3.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            status, value = self._request(
                "GET", self._path(operation_id=operation_id), token=self.token)
            if status == 200 and value.get("status") == expected:
                return value
            time.sleep(0.01)
        raise AssertionError("video operation did not reach its expected state")

    def test_same_idempotency_key_executes_once_and_result_survives_reopen(self) -> None:
        key = "initialize-once-001"
        body = {"action": "initialize", "parameters": {}}
        status, first = self._request("POST", self._path(), token=self.token, body=body, key=key)
        self.assertEqual(status, 202)
        status, second = self._request("POST", self._path(), token=self.token, body=body, key=key)
        self.assertEqual(status, 202)
        self.assertEqual(first["operation_id"], second["operation_id"])
        self.assertEqual(first["task_id"], self.task_id)
        completed = self._wait_status(first["operation_id"], "succeeded")
        self.assertEqual(completed["result"]["task_id"], self.task_id)
        self.assertEqual(len(self.worker.calls), 1)
        self.assertEqual(completed["object"], "padnote.video.operation")
        self.assertNotIn("attempt_id", completed)
        self.assertNotIn("source_snapshot_digest", completed)

        self.group.close()
        self.group = None
        self.service.close()
        self.service = BridgeService(self.state_dir)
        self.group = ServerGroup(self.service, admin_port=0, api_port=0)
        self.group.start()
        status, reopened = self._request(
            "GET", self._path(operation_id=first["operation_id"]), token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(reopened["status"], "succeeded")
        self.assertEqual(reopened["result"]["task_id"], self.task_id)

    def test_unknown_outcome_is_not_replayed_after_scheduler_restart(self) -> None:
        self.worker.mode = "unknown"
        status, queued = self._request(
            "POST", self._path(), token=self.token,
            body={"action": "initialize", "parameters": {}}, key="unknown-once-001")
        self.assertEqual(status, 202)
        result = self._wait_status(queued["operation_id"], "unknown")
        self.assertEqual(result["error"], "worker_timeout")
        self.assertEqual(len(self.worker.calls), 1)

        self.group.close()
        self.group = None
        self.service.close()
        self.service = BridgeService(self.state_dir)
        restarted_worker = StubVideoWorker()
        self.service.video_worker = restarted_worker
        self.service._start_video_scheduler()
        self.group = ServerGroup(self.service, admin_port=0, api_port=0)
        self.group.start()
        time.sleep(0.05)
        status, reopened = self._request(
            "GET", self._path(operation_id=queued["operation_id"]), token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(reopened["status"], "unknown")
        self.assertEqual(restarted_worker.calls, [])

    def test_explicit_reconcile_only_resolves_verified_receipt_and_never_replays(self):
        self.worker.mode = "unknown"
        status, queued = self._request(
            "POST", self._path(), token=self.token,
            body={"action": "initialize", "parameters": {}}, key="reconcile-once-001")
        self.assertEqual(status, 202)
        unknown = self._wait_status(queued["operation_id"], "unknown")
        self.assertEqual(1, len(self.worker.calls))
        reconcile_path = self._path(operation_id=queued["operation_id"], suffix="/reconcile")
        for body in ({"status": "succeeded"}, {"result": {}}, [], None):
            status, response = self._request("POST", reconcile_path, token=self.token, body=body)
            self.assertEqual(status, 400)
            self.assertIn("error", response)
        status, forbidden = self._request("POST", reconcile_path,
                                          token=self.other_token, body={})
        self.assertEqual(status, 401)
        self.assertEqual(1, len(self.worker.calls))

        status, still_unknown = self._request("POST", reconcile_path, token=self.token, body={})
        self.assertEqual(status, 200)
        self.assertEqual("unknown", still_unknown["status"])
        self.assertEqual(1, len([call for call in self.worker.calls if call[0] != "reconcile"]))
        self.assertEqual(1, len([call for call in self.worker.calls if call[0] == "reconcile"]))
        blocked_status, _blocked = self._request(
            "POST", self._path(), token=self.token,
            body={"action": "initialize", "parameters": {}},
            key="new-action-stays-blocked")
        self.assertEqual(409, blocked_status)

        self.worker.reconcile_mode = "verified_completed"
        self.worker.reconcile_result = {
            "protocol_version": 1, "task_id": self.binding["worker_task_id"],
            "status": "initialized", "phase": "idle", "event_cursor": 1,
        }
        status, completed = self._request("POST", reconcile_path, token=self.token, body={})
        self.assertEqual(status, 200)
        self.assertEqual("succeeded", completed["status"])
        self.assertEqual(self.task_id, completed["result"]["task_id"])
        self.assertEqual(1, len([call for call in self.worker.calls if call[0] != "reconcile"]))
        self.assertEqual(2, len([call for call in self.worker.calls if call[0] == "reconcile"]))
        again_status, again = self._request("POST", reconcile_path, token=self.token, body={})
        self.assertEqual(again_status, 200)
        self.assertEqual(completed, again)
        self.assertEqual(1, len([call for call in self.worker.calls if call[0] != "reconcile"]))
        self.assertEqual(2, len([call for call in self.worker.calls if call[0] == "reconcile"]))

    def test_strict_payload_cross_device_revocation_and_running_cancel_conflict(self) -> None:
        invalid_bodies = (
            {"action": "storyboard", "parameters": {}},
            {"action": "initialize", "parameters": {"revision": 1}},
            {"action": "initialize", "parameters": {}, "command": "secret"},
        )
        for index, body in enumerate(invalid_bodies):
            status, _ = self._request("POST", self._path(), token=self.token,
                                      body=body, key=f"invalid-parameter-{index}")
            self.assertEqual(status, 400)
        status, _ = self._request(
            "POST", self._path(), token=self.token,
            body={"action": "initialize", "parameters": {}})
        self.assertEqual(status, 400)
        self.assertEqual(self.worker.calls, [])

        _queued_payload, queued_task_id, _queued_binding = self._make_bound_run(
            self.connection, "cancel-queued")

        self.worker.entered = threading.Event()
        self.worker.release = threading.Event()
        status, queued = self._request(
            "POST", self._path(), token=self.token,
            body={"action": "initialize", "parameters": {}}, key="cancel-running-001")
        self.assertEqual(status, 202)
        self.assertTrue(self.worker.entered.wait(3))
        started = time.monotonic()
        status, duplicate = self._request(
            "POST", self._path(), token=self.token,
            body={"action": "initialize", "parameters": {}}, key="cancel-running-001")
        self.assertEqual(status, 202)
        self.assertEqual(duplicate["operation_id"], queued["operation_id"])
        status, running = self._request(
            "GET", self._path(operation_id=queued["operation_id"]), token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(running["status"], "running")
        status, invalid_cancel = self._request(
            "POST", self._path(operation_id=queued["operation_id"], suffix="/cancel"),
            token=self.token, body={"cancel": True})
        self.assertEqual(status, 400)
        self.assertIn("error", invalid_cancel)
        status, conflict = self._request(
            "POST", self._path(operation_id=queued["operation_id"], suffix="/cancel"),
            token=self.token, body={})
        self.assertEqual(status, 409)
        self.assertIn("error", conflict)

        started = time.monotonic()
        status, queued_operation = self._request(
            "POST", self._path(task_id=queued_task_id), token=self.token,
            body={"action": "initialize", "parameters": {}}, key="cancel-queued-001")
        self.assertEqual(status, 202)
        self.assertLess(time.monotonic() - started, 1.5)
        for _ in range(2):
            status, cancelled = self._request(
                "POST", self._path(task_id=queued_task_id,
                                   operation_id=queued_operation["operation_id"], suffix="/cancel"),
                token=self.token, body={})
            self.assertEqual(status, 200)
            self.assertEqual(cancelled["status"], "cancelled")
        self.assertLess(time.monotonic() - started, 1.5)
        self.worker.release.set()
        completed = self._wait_status(queued["operation_id"], "succeeded")
        status, still_cancelled = self._request(
            "GET", self._path(task_id=queued_task_id,
                              operation_id=queued_operation["operation_id"]), token=self.token)
        self.assertEqual(status, 200)
        self.assertEqual(still_cancelled["status"], "cancelled")
        self.assertEqual(len(self.worker.calls), 1)

        status, forbidden = self._request(
            "GET", self._path(operation_id=queued["operation_id"]), token=self.other_token)
        self.assertEqual(status, 401)
        self.assertIn("error", forbidden)
        self.service.store.revoke_connection(self.connection["connection_id"])
        status, revoked = self._request(
            "GET", self._path(operation_id=queued["operation_id"]), token=self.token)
        self.assertEqual(status, 401)
        self.assertIn("error", revoked)
        self.assertEqual(completed["status"], "succeeded")

    def test_no_worker_does_not_persist_or_accept_video_operation(self) -> None:
        self.group.close()
        self.service.close()
        self.service = BridgeService(self.state_dir)
        self.group = ServerGroup(self.service, admin_port=0, api_port=0)
        self.group.start()
        status, response = self._request(
            "POST", self._path(), token=self.token,
            body={"action": "initialize", "parameters": {}}, key="no-worker-001")
        self.assertIn(status, {409, 503})
        self.assertIn("error", response)
        self.assertEqual(self.service.store.snapshot()["video_operations"], {})

    def test_running_storyboard_cancel_is_prompt_persisted_and_terminal(self) -> None:
        self.worker.storyboard_entered = threading.Event()
        self.worker.storyboard_release = threading.Event()
        status, queued = self._request(
            "POST", self._path(), token=self.token,
            body={"action": "storyboard", "parameters": {"revision": 1, "event_cursor": 1}},
            key="running-storyboard-cancel-01")
        self.assertEqual(202, status)
        self.assertTrue(self.worker.storyboard_entered.wait(2))
        cancel_path = self._path(operation_id=queued["operation_id"], suffix="/cancel-running")
        status, absent = self._request("GET", cancel_path, token=self.token)
        self.assertEqual(409, status)
        self.assertIn("error", absent)
        status, forbidden = self._request("POST", cancel_path,
                                          token=self.other_token, body={})
        self.assertEqual(401, status)
        self.assertEqual([], self.worker.cancel_calls)
        status, invalid = self._request("POST", cancel_path, token=self.token,
                                        body={"pid": 1})
        self.assertEqual(400, status)
        self.assertEqual([], self.worker.cancel_calls)

        started = time.monotonic()
        status, stopped = self._request("POST", cancel_path, token=self.token, body={})
        self.assertEqual(200, status)
        self.assertLess(time.monotonic() - started, 1.0)
        self.assertEqual("padnote.video.cancel_request", stopped["object"])
        self.assertEqual("verified_cancelled", stopped["status"])
        self.assertEqual(1, len(self.worker.cancel_calls))
        status, op = self._request("GET", self._path(operation_id=queued["operation_id"]), token=self.token)
        self.assertEqual(200, status)
        self.assertEqual("cancelled", op["status"])
        status, again = self._request("POST", cancel_path, token=self.token, body={})
        self.assertEqual(200, status)
        self.assertEqual(stopped, again)
        self.assertEqual(1, len(self.worker.cancel_calls))
        self.worker.storyboard_release.set()
        time.sleep(0.05)
        status, still_cancelled = self._request(
            "GET", self._path(operation_id=queued["operation_id"]), token=self.token)
        self.assertEqual(200, status)
        self.assertEqual("cancelled", still_cancelled["status"])

    def test_cancel_request_survives_unknown_restart_and_receipt_reconciles_without_replay(self) -> None:
        self.worker.storyboard_entered = threading.Event()
        self.worker.storyboard_release = threading.Event()
        self.worker.cancel_mode = "unconfirmed"
        self.worker.storyboard_mode = "unknown"
        status, queued = self._request(
            "POST", self._path(), token=self.token,
            body={"action": "storyboard", "parameters": {"revision": 1, "event_cursor": 1}},
            key="running-storyboard-unknown-cancel")
        self.assertEqual(202, status)
        self.assertTrue(self.worker.storyboard_entered.wait(2))
        cancel_path = self._path(operation_id=queued["operation_id"], suffix="/cancel-running")
        status, pending = self._request("POST", cancel_path, token=self.token, body={})
        self.assertEqual(200, status)
        self.assertEqual("unconfirmed", pending["status"])
        self.worker.storyboard_release.set()
        unknown = self._wait_status(queued["operation_id"], "unknown")
        self.assertEqual("worker_timeout", unknown["error"])
        status, pending = self._request("GET", cancel_path, token=self.token)
        self.assertEqual(200, status)
        self.assertEqual("unconfirmed", pending["status"])

        self.worker.reconcile_mode = "verified_cancelled"
        status, cancelled = self._request(
            "POST", self._path(operation_id=queued["operation_id"], suffix="/reconcile"),
            token=self.token, body={})
        self.assertEqual(200, status)
        self.assertEqual("cancelled", cancelled["status"])
        self.assertEqual(1, len(self.worker.cancel_calls))
        self.assertEqual("cancelled", self._wait_status(queued["operation_id"], "cancelled")["status"])

    def test_source_change_during_control_call_keeps_operation_unconfirmed(self) -> None:
        self.worker.storyboard_entered = threading.Event()
        self.worker.storyboard_release = threading.Event()
        self.worker.cancel_entered = threading.Event()
        self.worker.cancel_release = threading.Event()
        status, queued = self._request(
            "POST", self._path(), token=self.token,
            body={"action": "storyboard", "parameters": {"revision": 1, "event_cursor": 1}},
            key="running-storyboard-source-race")
        self.assertEqual(202, status)
        self.assertTrue(self.worker.storyboard_entered.wait(2))
        cancel_path = self._path(operation_id=queued["operation_id"], suffix="/cancel-running")
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(self._request, "POST", cancel_path, token=self.token, body={})
            self.assertTrue(self.worker.cancel_entered.wait(2))
            self.service.store.update_run(self.task_id, source_snapshot_digest="c" * 64)
            self.worker.cancel_release.set()
            status, response = future.result(timeout=2)
        self.assertEqual(200, status)
        self.assertEqual("unconfirmed", response["status"])
        operation = self.service.store.snapshot()["video_operations"][queued["operation_id"]]
        self.assertEqual("running", operation["status"])
        self.assertEqual("unconfirmed", operation["cancel_request"]["control_status"])
        self.worker.storyboard_release.set()


if __name__ == "__main__":
    unittest.main()
