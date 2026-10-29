from __future__ import annotations

import copy
import hashlib
import http.client
import json
import os
import tempfile
import threading
import time
import urllib.parse
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from padnote_assistant.bridge import BridgeService
from padnote_assistant.admin_ui import _health, _health_guidance
from padnote_assistant.state import StateStore
from padnote_assistant.web import ServerGroup
from padnote_assistant.wsl_hermes import WslHermesManager


DISTRO_ID = "{806d943b-0882-43b5-a010-1f092cfa8b22}"
DISTRO_NAME = "Ubuntu-24.04"
PROFILES = ("default", "work", "research")


class FakeHermesHTTP:
    def __init__(self) -> None:
        self.requests: list[tuple[str, str]] = []
        self.lock = threading.Lock()


class FakeHermesHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_GET(self) -> None:
        state: FakeHermesHTTP = self.server.state  # type: ignore[attr-defined]
        with state.lock:
            state.requests.append((self.path, self.headers.get("Authorization", "")))
        if not self.path.endswith("/v1/capabilities"):
            body = b'{"error":"not_found"}'
            self.send_response(404)
        else:
            prefix = self.path.removesuffix("/v1/capabilities")
            body = json.dumps({
                "object": "hermes.api_server.capabilities",
                "platform": "hermes-agent",
                "features": {
                    "run_submission": True,
                    "run_status": True,
                    "run_stop": True,
                    "run_approval_response": True,
                },
                "endpoints": {
                    "runs": {"method": "POST", "path": prefix + "/v1/runs"},
                    "run_status": {"method": "GET", "path": prefix + "/v1/runs/{run_id}"},
                    "run_stop": {"method": "POST", "path": prefix + "/v1/runs/{run_id}/stop"},
                    "run_approval": {"method": "POST", "path": prefix + "/v1/runs/{run_id}/approval"},
                },
            }, separators=(",", ":")).encode("utf-8")
            self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)


class SyntheticWslHermesManager(WslHermesManager):
    """Keep manager orchestration/preview/operation code real; fake only WSL guest I/O."""

    def __init__(self, api_port: int):
        self.api_port = api_port
        self.runner_calls: list[list[str]] = []
        self.guest_requests: list[dict] = []
        self.running = False
        self.settings = {
            "default": {"enabled": True, "key": "default-key", "host": "127.0.0.1", "port": api_port},
            "work": {"enabled": False, "key": "work-key", "host": "127.0.0.1", "port": api_port},
            "research": {"enabled": False, "key": "research-key", "host": "127.0.0.1", "port": api_port},
        }
        self.revisions = {profile: 1 for profile in PROFILES}
        super().__init__(runner=self._runner, registry_reader=lambda: {DISTRO_NAME: DISTRO_ID},
                         platform="nt", wsl_executable="wsl.exe")

    def _runner(self, args, **_kwargs):
        command = list(args)
        self.runner_calls.append(command)
        if command[-2:] == ["--list", "--quiet"]:
            output = f"{DISTRO_NAME}\r\n".encode("utf-16-le")
        elif command[-3:] == ["--list", "--running", "--quiet"]:
            output = (f"{DISTRO_NAME}\r\n" if self.running else "").encode("utf-16-le")
        elif "--distribution" in command and command[-2:] == ["/usr/bin/uname", "-r"]:
            self.running = True
            output = b"5.15.146.1-microsoft-standard-WSL2\n"
        else:
            raise AssertionError(f"unexpected fake WSL command: {command!r}")
        return SimpleNamespace(returncode=0, stdout=output, stderr=b"")

    def _snapshot(self, profile: str) -> dict:
        values = copy.deepcopy(self.settings[profile])
        revision = self.revisions[profile]
        digest = hashlib.sha256(f"{profile}:{revision}".encode()).hexdigest()
        return {
            "ok": True,
            "api_state": "configured",
            "mode": "default" if profile == "default" else "shared",
            "env_sha256": digest,
            "config_sha256": hashlib.sha256((digest + ":config").encode()).hexdigest(),
            "env_mode": "0600",
            "config_mode": "0600",
            "effective": values,
            "gateway": {"pid": 4200, "served_profiles": ["work", "research"]},
            "home": "/home/padnote/.hermes" if profile == "default" else f"/home/padnote/.hermes/profiles/{profile}",
            "uid": 1000,
            "profiles": list(PROFILES),
        }

    def _guest_json(self, _row, request: dict, *, timeout: float = 0):
        del timeout
        self.guest_requests.append(copy.deepcopy(request))
        operation = request.get("op")
        if operation == "profiles":
            return {"ok": True, "profiles": list(PROFILES)}
        if operation == "inspect":
            return self._snapshot(request["profile"])
        if operation == "write_env":
            profile = request["profile"]
            self.settings[profile].update(request.get("updates", {}))
            self.revisions[profile] += 1
            return {"ok": True, "env_sha256": self._snapshot(profile)["env_sha256"]}
        if operation == "start_gateway":
            return {"ok": True, "started": False, "attached": True}
        raise AssertionError(f"unexpected synthetic guest operation: {request!r}")

    def rotate_key(self, profile: str, value: str) -> None:
        self.settings[profile]["key"] = value
        self.revisions[profile] += 1


class WslConnectionHTTPTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-wsl-http-")
        self.root = Path(self.temp.name)
        self.fake_hermes = FakeHermesHTTP()
        self.hermes_server = ThreadingHTTPServer(("127.0.0.1", 0), FakeHermesHandler)
        self.hermes_server.state = self.fake_hermes  # type: ignore[attr-defined]
        self.hermes_thread = threading.Thread(target=self.hermes_server.serve_forever, daemon=True)
        self.hermes_thread.start()
        self.manager = SyntheticWslHermesManager(self.hermes_server.server_address[1])
        self.service = BridgeService(self.root / "state")
        self.service.wsl_hermes.close()
        self.service.wsl_hermes = self.manager
        self.group = ServerGroup(self.service, admin_port=0, api_port=0)
        self.group.start()
        self.port = self.group.admin.server_address[1]
        self.host = f"127.0.0.1:{self.port}"
        self.origin = f"http://{self.host}"
        self.csrf = self.group.admin.csrf_token

    def tearDown(self) -> None:
        self.group.close()
        self.service.close()
        self.hermes_server.shutdown()
        self.hermes_server.server_close()
        self.hermes_thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, method: str, path: str, *, body: dict | None = None,
                host: str | None = None, origin: str | None = None,
                csrf: str | None = None):
        headers = {"Host": host or self.host}
        if origin is not None:
            headers["Origin"] = origin
        if csrf is not None:
            headers["X-PadNote-CSRF"] = csrf
        payload = None
        if body is not None:
            payload = urllib.parse.urlencode(body).encode("utf-8")
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        connection.request(method, path, body=payload, headers=headers)
        response = connection.getresponse()
        result = response.status, response.read()
        connection.close()
        return result

    def post(self, path: str, **fields):
        return self.request("POST", path, body={"csrf": self.csrf, **fields},
                            origin=self.origin, csrf=self.csrf)

    def poll(self, operation_id: str) -> dict:
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            status, body = self.request("GET", f"/admin/wsl/operations/{operation_id}",
                                        origin=self.origin, csrf=self.csrf)
            self.assertEqual(status, 200, body.decode("utf-8", "replace"))
            value = json.loads(body)
            if value["status"] in {"succeeded", "failed", "unknown", "cancelled"}:
                return value
            time.sleep(0.01)
        self.fail(f"WSL operation {operation_id} did not finish")

    def connect_profile(self, profile: str) -> tuple[str, dict]:
        status, body = self.post("/admin/wsl/inspect", distro_id=DISTRO_ID, profile_id=profile)
        self.assertEqual(status, 200, body.decode("utf-8", "replace"))
        preview = json.loads(body)
        self.assertRegex(preview["preview_token"], r"^[A-Za-z0-9_-]{24,64}$")
        self.assertNotIn("default-key", body.decode())
        self.assertNotIn("work-key", body.decode())
        self.assertNotIn("research-key", body.decode())
        status, body = self.post("/admin/wsl/connect", preview_token=preview["preview_token"])
        self.assertEqual(status, 202, body.decode("utf-8", "replace"))
        operation_id = json.loads(body)["operation_id"]
        result = self.poll(operation_id)
        self.assertEqual(result["status"], "succeeded", result)
        self.assertIsInstance(result.get("result"), dict)
        self.assertIsInstance(result["result"].get("instance_id"), str)
        return result["result"]["instance_id"], result

    def paired_connection(self, instance_id: str, device_id: str) -> dict:
        _code, payload = self.service.store.create_pair_code(instance_id, "https://padnote.invalid")
        requested = self.service.store.request_pair(payload["code"], device_id, "Synthetic tablet")
        self.service.store.decide_pair(requested["request_id"], True)
        status, response = self.service.store.claim_pair(requested["request_id"], requested["poll_token"])
        self.assertEqual(status, 200)
        token = response["connections"][0]["token"]
        return self.service.store.authenticate(instance_id, token)

    def test_host_origin_and_csrf_gate_all_wsl_reads_and_posts_without_side_effects(self) -> None:
        get_cases = (
            ("/admin/wsl/distros", self.host, self.origin, "wrong"),
            ("/admin/wsl/distros", "attacker.example", "http://attacker.example", self.csrf),
            ("/admin/wsl/distros", self.host, "https://attacker.example", self.csrf),
            ("/admin/wsl/operations/AAAAAAAAAAAAAAAA", self.host, self.origin, "wrong"),
        )
        for path, host, origin, csrf in get_cases:
            with self.subTest(method="GET", path=path, host=host, origin=origin):
                status, _body = self.request("GET", path, host=host, origin=origin, csrf=csrf)
                self.assertEqual(status, 403)

        post_cases = (
            ("/admin/wsl/profiles", {"distro_id": DISTRO_ID}, "attacker.example", self.origin, self.csrf),
            ("/admin/wsl/profiles", {"distro_id": DISTRO_ID}, self.host, "https://attacker.example", self.csrf),
            ("/admin/wsl/profiles", {"distro_id": DISTRO_ID}, self.host, self.origin, "wrong"),
            ("/admin/wsl/inspect", {"distro_id": DISTRO_ID, "profile_id": "work"}, self.host, self.origin, "wrong"),
            ("/admin/wsl/connect", {"preview_token": "A" * 32}, self.host, "https://attacker.example", self.csrf),
        )
        for path, fields, host, origin, csrf in post_cases:
            with self.subTest(method="POST", path=path, host=host, origin=origin):
                status, _body = self.request("POST", path, body={"csrf": csrf, **fields},
                                             host=host, origin=origin, csrf=csrf)
                self.assertEqual(status, 403)

        self.assertEqual(self.manager.runner_calls, [])
        self.assertEqual(self.manager.guest_requests, [])
        self.assertEqual(self.manager._operations, {})
        self.assertEqual(self.manager._previews, {})
        with self.fake_hermes.lock:
            self.assertEqual(self.fake_hermes.requests, [])

    def test_real_http_route_drives_shared_profiles_and_uses_each_profile_key(self) -> None:
        started = time.monotonic()
        status, body = self.request("GET", "/admin/wsl/distros", origin=self.origin, csrf=self.csrf)
        self.assertEqual(status, 200, body.decode())
        distros = json.loads(body)["distros"]
        self.assertEqual(distros, [{"id": DISTRO_ID.lower(), "name": DISTRO_NAME,
                                    "state": "stopped", "version": "unknown"}])
        self.assertFalse(any("--distribution" in call for call in self.manager.runner_calls),
                         "passive distro listing must not execute a guest command")

        status, body = self.post("/admin/wsl/profiles", distro_id=DISTRO_ID.lower())
        self.assertEqual(status, 202, body.decode())
        self.assertLess(time.monotonic() - started, 1.0, "HTTP should return before WSL work finishes")
        profiles_operation = json.loads(body)["operation_id"]
        profiles_result = self.poll(profiles_operation)
        self.assertEqual(profiles_result["status"], "succeeded", profiles_result)
        self.assertEqual([item["id"] for item in profiles_result["profiles"]], list(PROFILES))
        self.assertTrue(any(call[-2:] == ["/usr/bin/uname", "-r"] for call in self.manager.runner_calls),
                        "only the explicit profiles action may start the stopped synthetic distro")

        instance_ids = {}
        for profile in PROFILES:
            instance_id, result = self.connect_profile(profile)
            instance_ids[profile] = instance_id
            self.assertEqual(result["result"]["health"], "ready")
            registered = self.service.store.get_instance(instance_id)
            self.assertTrue(registered["executable"])
            self.assertEqual(registered["kind"], "hermes")
            self.assertIn("wsl://", registered["config_ref"])
            if profile != "default":
                self.assertIn(f"prefix=%2Fp%2F{profile}", registered["config_ref"])
                self.assertFalse(self.manager.settings[profile]["enabled"],
                                 "a shared named profile must not enable its own listener")

        with self.fake_hermes.lock:
            requests = list(self.fake_hermes.requests)
        expected_auth = {
            "/v1/capabilities": "Bearer default-key",
            "/p/work/v1/capabilities": "Bearer work-key",
            "/p/research/v1/capabilities": "Bearer research-key",
        }
        for path, authorization in expected_auth.items():
            self.assertIn((path, authorization), requests)
        self.assertNotIn(("/p/work/v1/capabilities", "Bearer default-key"), requests)
        self.assertNotIn(("/p/research/v1/capabilities", "Bearer work-key"), requests)
        self.assertFalse(any(item.get("op") == "write_env" for item in self.manager.guest_requests),
                         "already configured shared listener/profile keys should connect without enabling a named listener")

        old_id = instance_ids["work"]
        old_connection = self.paired_connection(old_id, "synthetic-device-old")
        old_run, created = self.service.store.create_or_get_run(
            old_connection, "same-client-task", {"input": "synthetic old-profile history"})
        self.assertTrue(created)

        self.manager.rotate_key("work", "work-key-rotated")
        replacement_id, replacement_result = self.connect_profile("work")
        self.assertNotEqual(old_id, replacement_id)
        self.assertEqual(replacement_result["result"]["instance_id"], replacement_id)
        retired_old = self.service.store.get_instance(old_id)
        self.assertIsInstance(retired_old.get("retired_at"), int)
        self.assertFalse(retired_old["executable"])
        replacement = self.service.store.get_instance(replacement_id)
        self.assertIsNone(replacement.get("retired_at"))
        self.assertEqual(self.service.store.snapshot()["runs"][old_run["task_id"]]["instance_id"], old_id)

        new_connection = self.paired_connection(replacement_id, "synthetic-device-new")
        new_run, new_created = self.service.store.create_or_get_run(
            new_connection, "same-client-task", {"input": "synthetic old-profile history"})
        self.assertTrue(new_created)
        self.assertNotEqual(old_run["task_id"], new_run["task_id"])
        self.assertEqual(new_run["instance_id"], replacement_id)
        self.assertEqual(self.service.store.snapshot()["runs"][old_run["task_id"]]["instance_id"], old_id,
                         "profile identity changes must preserve history on the old instance")
        with self.fake_hermes.lock:
            self.assertIn(("/p/work/v1/capabilities", "Bearer work-key-rotated"), self.fake_hermes.requests)

    def test_startup_treats_wsl_config_ref_as_opaque_and_marks_reconnect_required(self) -> None:
        with tempfile.TemporaryDirectory(prefix="padnote-wsl-opaque-") as temporary:
            state_dir = Path(temporary) / "state"
            opaque_home = Path(temporary) / "guest-home-that-must-not-be-read"
            opaque_ref = (f"wsl://{DISTRO_ID.strip('{}')}/1000/work?home="
                          + urllib.parse.quote(str(opaque_home), safe="")
                          + "&mode=shared&prefix=%2Fp%2Fwork")
            seed = StateStore(state_dir)
            prior = seed.add_instance("hermes", "Synthetic WSL profile",
                                      "http://127.0.0.1:8642", config_ref=opaque_ref,
                                      config_fingerprint="synthetic-fingerprint",
                                      credential_fingerprint="synthetic-credential-fingerprint")
            seed.update_instance_check(prior["instance_id"], health="ready", detail="synthetic",
                                       features={"run_submission": True}, executable=True)
            seed.close()

            manager = SyntheticWslHermesManager(self.hermes_server.server_address[1])
            with patch("padnote_assistant.bridge.WslHermesManager", return_value=manager):
                restarted = BridgeService(state_dir)
            try:
                after = restarted.store.get_instance(prior["instance_id"])
                self.assertEqual(after["health"], "wsl_reconnect_required")
                self.assertFalse(after["executable"])
                self.assertFalse(restarted.vault.has(prior["instance_id"]))
                label, tone = _health(after["health"])
                self.assertEqual((label, tone), ("需重新连接 WSL", "warn"))
                guidance = _health_guidance(after["health"])
                self.assertIn("重新选择原发行版和 Hermes 配置", guidance)
                self.assertIn("不会自动启动 WSL", guidance)
                self.assertFalse(opaque_home.exists())
                self.assertEqual(manager.runner_calls, [])
                self.assertEqual(manager.guest_requests, [])
            finally:
                restarted.close()


if __name__ == "__main__":
    unittest.main()
