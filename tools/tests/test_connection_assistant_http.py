"""Cross-component checks through real loopback HTTP and the Android ZIP exporter fixture.

No real Agent, personal configuration, or external network is used.
Run: python3 tools/tests/test_connection_assistant_http.py
"""
from __future__ import annotations

import base64
import concurrent.futures
import hashlib
import html
import json
import re
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "desktop/connection-assistant"))
from padnote_assistant.bridge import BridgeService
from padnote_assistant.web import ServerGroup


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class FakeHermes(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send_json(self, code, value):
        data = json.dumps(value).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer fixture-hermes-key":
            return self.send_json(401, {})
        if self.path == "/v1/capabilities":
            return self.send_json(200, {
                "object": "hermes.api_server.capabilities", "platform": "hermes-agent",
                "features": {"run_submission": True, "run_status": True, "run_stop": True,
                             "run_approval_response": True},
                "endpoints": {
                    "runs": {"method": "POST", "path": "/v1/runs"},
                    "run_status": {"method": "GET", "path": "/v1/runs/{run_id}"},
                    "run_stop": {"method": "POST", "path": "/v1/runs/{run_id}/stop"},
                    "run_approval": {"method": "POST", "path": "/v1/runs/{run_id}/approval"},
                },
            })
        if self.path.startswith("/v1/runs/"):
            rid = self.path.rsplit("/", 1)[1]
            return self.send_json(200, self.server.runs[rid])
        self.send_json(404, {})

    def do_POST(self):
        if self.headers.get("Authorization") != "Bearer fixture-hermes-key":
            return self.send_json(401, {})
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/v1/runs":
            key = self.headers.get("Idempotency-Key")
            with self.server.lock:
                self.server.submit_calls += 1
                if key not in self.server.keys:
                    rid = "run-" + str(len(self.server.runs) + 1)
                    self.server.keys[key] = rid
                    self.server.runs[rid] = {"object": "hermes.run", "run_id": rid, "status": "running"}
                    self.server.inputs.append(body["input"])
                rid = self.server.keys[key]
                bad_reply = self.server.bad_reply_once
                self.server.bad_reply_once = False
            if bad_reply:
                self.send_response(202)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", "1")
                self.end_headers()
                self.wfile.write(b"{")
                return
            return self.send_json(202, self.server.runs[rid])
        rid, action = self.path.split("/")[-2:]
        if action == "stop":
            self.server.runs[rid]["status"] = "cancelled"
            return self.send_json(200, {"run_id": rid, "status": "stopping"})
        if action == "approval":
            self.server.approvals.append(body)
            self.server.runs[rid] = {"run_id": rid, "status": "running"}
            return self.send_json(200, {"run_id": rid, "choice": body.get("choice"), "resolved": 1})
        self.send_json(404, {})


class ConnectionAssistantHTTPTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-http-qa-")
        self.fake = ThreadingHTTPServer(("127.0.0.1", 0), FakeHermes)
        self.fake.keys, self.fake.runs, self.fake.inputs, self.fake.approvals = {}, {}, [], []
        self.fake.submit_calls, self.fake.bad_reply_once = 0, False
        self.fake.lock = threading.Lock()
        self.fake_thread = threading.Thread(target=self.fake.serve_forever, daemon=True)
        self.fake_thread.start()
        self.bridge = BridgeService(Path(self.temp.name), allow_test_http=True)
        self.servers = ServerGroup(self.bridge, admin_port=0, api_port=0)
        self.servers.start()
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def tearDown(self):
        self.servers.close()
        self.fake.shutdown()
        self.fake.server_close()
        self.fake_thread.join(2)
        self.temp.cleanup()

    def request(self, path, *, method="GET", data=None, token=None, headers=None, admin=False):
        url = (self.servers.admin_url.rstrip("/") if admin else self.servers.api_url) + path
        hdr = dict(headers or {})
        if token:
            hdr["Authorization"] = "Bearer " + token
        if isinstance(data, dict):
            data = json.dumps(data).encode()
            hdr.setdefault("Content-Type", "application/json")
        req = urllib.request.Request(url, method=method, data=data, headers=hdr)
        try:
            response = self.http.open(req, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            if "application/json" in response.headers.get("Content-Type", ""):
                raw = json.loads(raw)
            return response.status, raw, response.headers

    def admin(self, path, fields):
        status, page, _ = self.request("/", admin=True)
        self.assertEqual(status, 200)
        csrf = re.search(r'name="csrf" value="([^"]+)"', page.decode()).group(1)
        data = urllib.parse.urlencode({"csrf": html.unescape(csrf), **fields}).encode()
        return self.request(path, method="POST", data=data, admin=True, headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Origin": self.servers.admin_url.rstrip("/"),
        })

    def add_pair(self, name="Hermes A", device_id="same-displayed-device"):
        before = {i["instance_id"] for i in self.bridge.store.list_instances()}
        code, _, _ = self.admin("/admin/instances", {
            "kind": "hermes", "name": name,
            "base_url": "http://127.0.0.1:" + str(self.fake.server_port),
            "api_key": "fixture-hermes-key",
        })
        self.assertEqual(code, 303)
        instance = next(i for i in self.bridge.store.list_instances() if i["instance_id"] not in before)
        iid = instance["instance_id"]
        self.assertEqual(self.admin(f"/admin/instances/{iid}/check", {})[0], 303)
        self.assertTrue(self.bridge.store.get_instance(iid)["executable"])
        return iid, self.pair_existing(iid, device_id)

    def pair_existing(self, iid, device_id="same-displayed-device"):
        code, _, _ = self.admin("/admin/pair-codes", {"instance_id": iid, "public_url": self.servers.api_url})
        self.assertEqual(code, 303)
        pair = self.bridge.last_pair_payload
        code, request, _ = self.request("/padnote/v1/pair/request", method="POST", data={
            "code": pair["code"], "device_id": device_id, "device_name": "测试平板",
        })
        self.assertEqual(code, 202)
        claim = {k: request[k] for k in ("request_id", "poll_token")}
        self.assertEqual(self.request("/padnote/v1/pair/claim", method="POST", data=claim)[0], 202)
        self.assertEqual(self.admin(f"/admin/pair-requests/{request['request_id']}/approve", {})[0], 303)
        code, answer, _ = self.request("/padnote/v1/pair/claim", method="POST", data=claim)
        self.assertEqual(code, 200)
        self.assertNotIn("fixture-hermes-key", json.dumps(answer))
        self.assertNotEqual(self.request("/padnote/v1/pair/claim", method="POST", data=claim)[0], 200)
        return answer["connections"][0]["token"]

    def submit(self, iid, token, key="task-fixture-1", *, bundle=False):
        payload = {"client_task_id": key, "title": "牛顿第二定律", "input": "请整理选中的说明。",
                   "source": {"note_id": "fixture-note-1", "note_revision": 7}}
        if bundle:
            raw = (REPO / "tools/tests/fixtures/agent-video-task-android.zip").read_bytes()
            payload.update(bundle_base64=base64.b64encode(raw).decode(), bundle_sha256=hashlib.sha256(raw).hexdigest())
        code, answer, _ = self.request(f"/padnote/v1/agents/{iid}/runs", method="POST", token=token,
                                     data=payload, headers={"Idempotency-Key": key})
        self.assertEqual(code, 202, answer)
        self.assertEqual(answer["status"], "running", answer)
        return payload, answer["task_id"]

    def test_management_isolation_and_safe_rendering(self):
        self.assertEqual(self.request("/", headers={"Host": "evil.example"}, admin=True)[0], 403)
        self.assertEqual(self.request("/admin/instances", method="POST", data={})[0], 404)
        self.assertEqual(self.request("/", headers={"Origin": "https://evil.example"}, admin=True)[0], 200)
        self.assertEqual(self.request("/admin/instances", method="POST", data=b"csrf=bad", admin=True,
                                     headers={"Origin": "https://evil.example",
                                              "Content-Type": "application/x-www-form-urlencoded"})[0], 403)
        self.add_pair(name="<script>alert(1)</script>")
        page = self.request("/", admin=True)[1].decode()
        self.assertIn("&lt;script&gt;", page)
        self.assertNotIn("fixture-hermes-key", page)

    def test_real_android_bundle_artifact_and_restart(self):
        iid, token = self.add_pair()
        _, task_id = self.submit(iid, token, bundle=True)
        task_dir = self.bridge.store.tasks_dir / task_id
        original = json.loads((task_dir / "request.json").read_text())
        self.assertEqual(original["task_type"], "video.explain.v1")
        self.assertEqual(original["source"]["note_revision"], 7)
        artifact = "# 中文结果\n已经完成。\n".encode()
        (task_dir / "output/讲解.md").write_bytes(artifact)
        run_id = next(iter(self.fake.runs))
        self.fake.runs[run_id].update(status="completed", output="已生成结果")
        route = f"/padnote/v1/agents/{iid}/runs/{task_id}"
        code, result, _ = self.request(route, token=token)
        self.assertEqual(code, 200, result)
        self.assertEqual(result["status"], "completed")
        self.assertEqual(len(result["artifacts"]), 1)
        item = result["artifacts"][0]
        self.assertEqual(item["sha256"], hashlib.sha256(artifact).hexdigest())
        code, downloaded, _ = self.request(route + "/artifacts/" + item["id"], token=token)
        self.assertEqual(code, 200)
        self.assertEqual(downloaded, artifact)
        self.servers.close()
        with BridgeService(Path(self.temp.name), allow_test_http=True) as reopened:
            self.assertEqual(reopened.get_run(iid, token, task_id)["status"], "completed")
            self.assertEqual(reopened.get_run(iid, token, task_id)["output"], "已生成结果")

    def test_multiple_instances_same_device_id_and_revocation(self):
        aid, at = self.add_pair("Hermes A")
        bid, bt = self.add_pair("Hermes B")
        _, task_id = self.submit(aid, at)
        route = f"/padnote/v1/agents/{aid}/runs/{task_id}"
        self.assertIn(self.request(route, token=bt)[0], (401, 403, 404))
        self.assertIn(self.request(f"/padnote/v1/agents/{bid}/runs/{task_id}", token=bt)[0], (401, 403, 404))
        second_token = self.pair_existing(aid)
        self.assertIn(self.request(route, token=second_token)[0], (401, 403, 404))
        connection_id = self.bridge.store.authenticate(aid, at)["connection_id"]
        self.assertEqual(self.admin(f"/admin/connections/{connection_id}/revoke", {})[0], 303)
        self.assertIn(self.request(route, token=at)[0], (401, 403, 404))
        self.assertEqual(self.request(f"/padnote/v1/agents/{bid}/capabilities", token=bt)[0], 200)

    def test_retry_conflict_and_exact_approval_translation(self):
        iid, token = self.add_pair()
        payload, task_id = self.submit(iid, token)
        route = f"/padnote/v1/agents/{iid}/runs"
        repeat = self.request(route, method="POST", token=token, data=payload,
                              headers={"Idempotency-Key": payload["client_task_id"]})
        self.assertEqual(repeat[1]["task_id"], task_id)
        self.assertEqual(len(self.fake.runs), 1)
        changed = {**payload, "input": "不同的任务"}
        self.assertEqual(self.request(route, method="POST", token=token, data=changed,
                                      headers={"Idempotency-Key": payload["client_task_id"]})[0], 409)
        run_id = next(iter(self.fake.runs))
        self.fake.runs[run_id].update(status="waiting_for_approval", approval={
            "request_id": "approval-exact-1", "command": "read selected note", "reason": "需要读取本次输入",
        })
        result = self.request(route + "/" + task_id, token=token)[1]
        self.assertEqual(result["approval"]["approval_id"], "approval-exact-1")
        approval_route = route + "/" + task_id + "/approval"
        self.assertEqual(self.request(approval_route, method="POST", token=token,
                                      data={"approval_id": "old", "decision": "once"})[0], 409)
        self.assertEqual(self.request(approval_route, method="POST", token=token,
                                      data={"approval_id": "approval-exact-1", "decision": "once"})[0], 202)
        self.assertEqual(self.fake.approvals, [{"request_id": "approval-exact-1", "choice": "once"}])
        self.assertEqual(self.request(route + "/" + task_id + "/stop", method="POST", token=token, data={})[1]["status"], "stopping")
        self.assertEqual(self.request(route + "/" + task_id, token=token)[1]["status"], "cancelled")

    def test_simultaneous_submit_reuses_task_and_upstream_run(self):
        iid, token = self.add_pair()
        payload = {"client_task_id": "simultaneous-1", "title": "并发重试", "input": "同一次任务",
                   "source": {"note_id": "fixture-note-1", "note_revision": 7}}
        raw = (REPO / "tools/tests/fixtures/agent-video-task-android.zip").read_bytes()
        payload.update(bundle_base64=base64.b64encode(raw).decode(), bundle_sha256=hashlib.sha256(raw).hexdigest())
        def send(_):
            return self.request(f"/padnote/v1/agents/{iid}/runs", method="POST", token=token,
                                data=payload, headers={"Idempotency-Key": "simultaneous-1"})
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            responses = list(pool.map(send, range(4)))
        self.assertEqual({response[0] for response in responses}, {202})
        self.assertEqual({response[1]["status"] for response in responses}, {"running"})
        self.assertEqual(len({response[1]["task_id"] for response in responses}), 1)
        self.assertEqual(self.fake.submit_calls, 1)
        self.assertEqual(len(self.fake.runs), 1)

    def test_accepted_but_unreadable_reply_retries_same_identity(self):
        iid, token = self.add_pair()
        payload = {"client_task_id": "uncertain-1", "title": "响应中断", "input": "只执行一次",
                   "source": {"note_id": "fixture-note-1", "note_revision": 7}}
        route = f"/padnote/v1/agents/{iid}/runs"
        self.fake.bad_reply_once = True
        code, first, _ = self.request(route, method="POST", token=token, data=payload,
                                      headers={"Idempotency-Key": "uncertain-1"})
        self.assertEqual(code, 202)
        self.assertEqual(first["status"], "submitting", first)
        self.assertEqual(len(self.fake.runs), 1)
        code, second, _ = self.request(route, method="POST", token=token, data=payload,
                                       headers={"Idempotency-Key": "uncertain-1"})
        self.assertEqual(code, 202)
        self.assertEqual(second["status"], "running")
        self.assertEqual(first["task_id"], second["task_id"])
        self.assertEqual(len(self.fake.runs), 1)

    def test_uncertain_submission_beyond_retention_is_not_reexecuted(self):
        iid, token = self.add_pair()
        payload = {"client_task_id": "expired-retry-1", "title": "过期重试", "input": "只执行一次",
                   "source": {"note_id": "fixture-note-1", "note_revision": 7}}
        route = f"/padnote/v1/agents/{iid}/runs"
        self.fake.bad_reply_once = True
        first = self.request(route, method="POST", token=token, data=payload,
                             headers={"Idempotency-Key": "expired-retry-1"})[1]
        self.assertEqual(first["status"], "submitting", first)
        future = self.bridge.clock() + 86401
        self.bridge.clock = lambda: future
        second = self.request(route, method="POST", token=token, data=payload,
                              headers={"Idempotency-Key": "expired-retry-1"})[1]
        self.assertEqual(second["task_id"], first["task_id"])
        self.assertEqual(second["status"], "submitting")
        self.assertEqual(self.fake.submit_calls, 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
