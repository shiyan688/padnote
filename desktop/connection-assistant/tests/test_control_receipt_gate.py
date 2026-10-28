from __future__ import annotations

import http.client
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from padnote_assistant.bridge import BridgeService
from padnote_assistant.security import canonical_json
from padnote_assistant.web import API_PREFIX, ServerGroup
from test_core import ready_instance


class ControlReceiptGateHTTPTests(unittest.TestCase):
    def test_stop_and_approval_require_receipt_header_after_authentication(self):
        calls = []

        class FakeHermes:
            def __init__(self, *args, **kwargs):
                pass

            def stop_run(self, run_id):
                calls.append(("stop", run_id))
                return {"status": "stopping"}

            def approve_run(self, run_id, approval_id, decision):
                calls.append(("approval", run_id, approval_id, decision))
                return {"status": "running"}

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", FakeHermes):
            service = BridgeService(Path(directory))
            group = ServerGroup(service, admin_port=0, api_port=0)
            group.start()
            try:
                instance, token, connection = ready_instance(service)
                task, _ = service.store.create_or_get_run(
                    connection, "stop-gate-task", {"input": "synthetic stop"})
                task = service.store.update_run(task["task_id"], status="running",
                                                upstream_run_id="run_stop_gate")
                approval_task, _ = service.store.create_or_get_run(
                    connection, "approval-gate-task", {"input": "synthetic approval"})
                approval_task = service.store.update_run(
                    approval_task["task_id"], status="waiting_for_approval",
                    upstream_run_id="run_approval_gate",
                    approval={"approval_id": "pc1:1:prompt-gate"})
                base = API_PREFIX + "/agents/" + instance["instance_id"] + "/runs/"
                stop_path = base + task["task_id"] + "/stop"
                approval_path = base + approval_task["task_id"] + "/approval"

                status, unauthorized = self._request(group, "POST", stop_path, body={})
                self.assertIn(status, (401, 403))
                self.assertNotIn("version", unauthorized.get("error", {}).get("message", "").lower())
                self.assertEqual({}, service.store.snapshot()["runs"][task["task_id"]].get("control_actions", {}))

                for path, payload in ((stop_path, {}), (approval_path, {
                        "approval_id": "pc1:1:prompt-gate", "decision": "once"})):
                    for header in (None, "wrong"):
                        status, error = self._request(group, "POST", path, token=token,
                                                      body=payload, receipt=header)
                        self.assertEqual(409, status)
                        self.assertIn("update PadNote", error.get("error", {}).get("message", ""))
                    self.assertEqual([], calls)

                status, capability = self._request(
                    group, "GET", API_PREFIX + "/agents/" + instance["instance_id"] + "/capabilities",
                    token=token)
                self.assertEqual(200, status)
                self.assertEqual(1, capability.get("run_control_receipts_version"))

                status, stop_receipt = self._request(
                    group, "POST", stop_path, token=token, body={}, receipt="1")
                self.assertEqual(202, status)
                self.assertIn("task_id", stop_receipt)
                status, approval_receipt = self._request(
                    group, "POST", approval_path, token=token,
                    body={"approval_id": "pc1:1:prompt-gate", "decision": "once"}, receipt="1")
                self.assertEqual(202, status)
                self.assertIn("task_id", approval_receipt)
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline and len(calls) < 2:
                    time.sleep(0.005)
                self.assertEqual(2, len(calls))
            finally:
                group.close()
                service.close()

    @staticmethod
    def _request(group, method, path, *, token=None, body=None, receipt=None):
        port = group.api.server_address[1]
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=4)
        headers = {"Host": f"127.0.0.1:{port}"}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        if body is not None:
            headers["Content-Type"] = "application/json"
        if receipt is not None:
            headers["X-PadNote-Control-Receipt"] = receipt
        connection.request(method, path, body=None if body is None else canonical_json(body), headers=headers)
        response = connection.getresponse()
        data = response.read()
        connection.close()
        return response.status, json.loads(data) if data else {}


if __name__ == "__main__":
    unittest.main()
