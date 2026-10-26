from __future__ import annotations

import http.client
import json
import os
import tempfile
import unittest
import urllib.parse
from pathlib import Path

from padnote_assistant.bridge import BridgeService
from padnote_assistant.web import ServerGroup


class AdminUIHTTPTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="padnote-admin-ui-")
        self.state_dir = Path(self.temp.name)
        os.chmod(self.state_dir, 0o700)
        self.service = BridgeService(self.state_dir)
        self.group = ServerGroup(self.service, admin_port=0, api_port=0)
        self.group.start()
        self.port = self.group.admin.server_address[1]
        self.host = f"127.0.0.1:{self.port}"

    def tearDown(self) -> None:
        self.group.close()
        self.service.close()
        self.temp.cleanup()

    def request(self, method: str, path: str, *, headers: dict[str, str] | None = None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=4)
        request_headers = {"Host": self.host}
        request_headers.update(headers or {})
        connection.request(method, path, headers=request_headers)
        response = connection.getresponse()
        body = response.read()
        result = response.status, dict(response.getheaders()), body
        connection.close()
        return result

    def post_form(self, path: str, *, origin: str, csrf: str):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=4)
        body = urllib.parse.urlencode({"csrf": csrf}).encode("utf-8")
        connection.request("POST", path, body=body, headers={
            "Host": self.host,
            "Origin": origin,
            "Content-Type": "application/x-www-form-urlencoded",
        })
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def test_empty_admin_page_and_fixed_static_assets(self) -> None:
        status, headers, body = self.request("GET", "/")
        self.assertEqual(status, 200)
        self.assertIn("Agent 管理", body.decode())
        self.assertEqual(headers.get("Cache-Control"), "no-store")
        self.assertEqual(headers.get("Referrer-Policy"), "same-origin")
        for path, expected in (("/assets/admin.css", "text/css"),
                               ("/assets/admin.js", "text/javascript"),
                               ("/assets/brand.png", "image/png")):
            status, headers, body = self.request("GET", path)
            self.assertEqual(status, 200, path)
            self.assertIn(expected, headers.get("Content-Type", ""))
            self.assertTrue(body)
            self.assertEqual(headers.get("X-Content-Type-Options"), "nosniff")
        for path in ("/assets/admin.css?x=1", "/assets/../web.py", "/assets/%2e%2e/web.py"):
            status, _headers, _body = self.request("GET", path)
            self.assertIn(status, (403, 404), path)

    def test_bad_host_rejected(self) -> None:
        status, _headers, _body = self.request("GET", "/", headers={"Host": "attacker.example"})
        self.assertEqual(status, 403)

    def test_live_requires_csrf_and_same_origin(self) -> None:
        status, _headers, _body = self.request("GET", "/admin/live")
        self.assertEqual(status, 403)
        csrf = self.group.admin.csrf_token
        status, _headers, _body = self.request(
            "GET", "/admin/live", headers={"X-PadNote-CSRF": csrf, "Origin": "https://attacker.example"})
        self.assertEqual(status, 403)
        status, headers, body = self.request(
            "GET", "/admin/live", headers={"X-PadNote-CSRF": csrf, "Origin": f"http://{self.host}"})
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Cache-Control"), "no-store")
        value = json.loads(body)
        self.assertEqual(value["pending_count"], 0)
        self.assertEqual(value["connection_count"], 0)
        self.assertEqual(set(value), {"pending_html", "connections_html", "pending_count", "connection_count", "updated_at"})

    def test_form_post_requires_exact_origin_and_csrf(self) -> None:
        csrf = self.group.admin.csrf_token
        status, _headers, _body = self.post_form(
            "/admin/discovery/refresh", origin=f"http://{self.host}", csrf=csrf)
        self.assertEqual(status, 303)
        for origin in ("null", "https://attacker.example"):
            status, _headers, _body = self.post_form(
                "/admin/discovery/refresh", origin=origin, csrf=csrf)
            self.assertEqual(status, 403, origin)
        status, _headers, _body = self.post_form(
            "/admin/discovery/refresh", origin=f"http://{self.host}", csrf="wrong-token")
        self.assertEqual(status, 403)

    def test_display_names_are_escaped_and_live_data_has_no_credentials(self) -> None:
        marker = "<img src=x onerror=alert(1)>"
        instance = self.service.add_instance("hermes", marker, "http://127.0.0.1:8642", "secret-fixture-key")
        self.assertEqual(instance["name"], marker)
        status, _headers, page = self.request("GET", "/")
        self.assertEqual(status, 200)
        self.assertIn("&lt;img src=x onerror=alert(1)&gt;", page.decode())
        csrf = self.group.admin.csrf_token
        status, _headers, live = self.request("GET", "/admin/live", headers={"X-PadNote-CSRF": csrf})
        self.assertEqual(status, 200)
        self.assertNotIn(b"secret-fixture-key", live)
        self.assertNotIn(marker.encode(), live)


if __name__ == "__main__":
    unittest.main()
