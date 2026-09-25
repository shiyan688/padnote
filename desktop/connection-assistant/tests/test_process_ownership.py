from __future__ import annotations

import html
import os
import re
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from padnote_assistant.state import AuthorizationError, StateStore


ROOT = Path(__file__).resolve().parents[1]


class ProcessOwnershipTests(unittest.TestCase):
    def _free_port(self) -> int:
        with socket.socket() as candidate:
            candidate.bind(("127.0.0.1", 0))
            return candidate.getsockname()[1]

    def _ports(self) -> tuple[int, int]:
        first = self._free_port()
        second = self._free_port()
        while second == first:
            second = self._free_port()
        return first, second

    def _command(self, state_dir: Path, ports: tuple[int, int]) -> list[str]:
        return [
            sys.executable,
            str(ROOT / "start.py"),
            "--state-dir", str(state_dir),
            "--admin-port", str(ports[0]),
            "--api-port", str(ports[1]),
            "--no-browser",
        ]

    def _environment(self, home: Path) -> dict[str, str]:
        environment = os.environ.copy()
        environment["HOME"] = str(home)
        environment["PYTHONUNBUFFERED"] = "1"
        return environment

    def _start(self, state_dir: Path, ports: tuple[int, int], home: Path) -> subprocess.Popen:
        process = subprocess.Popen(
            self._command(state_dir, ports),
            cwd=ROOT,
            env=self._environment(home),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self._wait_for_http(process, ports[0])
        return process

    def _wait_for_http(self, process: subprocess.Popen, port: int) -> None:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if process.poll() is not None:
                stdout, stderr = process.communicate(timeout=1)
                self.fail(f"assistant exited before startup ({process.returncode}): {stdout} {stderr}")
            try:
                with opener.open(f"http://127.0.0.1:{port}/", timeout=0.25) as response:
                    if response.status == 200:
                        return
            except (OSError, urllib.error.URLError):
                time.sleep(0.05)
        self._stop(process, hard=True)
        self.fail("assistant did not start its management endpoint")

    @staticmethod
    def _stop(process: subprocess.Popen | None, *, hard: bool) -> None:
        if process is None or process.poll() is not None:
            return
        if hard:
            process.kill()
        else:
            process.send_signal(signal.SIGINT)
        try:
            process.communicate(timeout=8)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate(timeout=5)

    def _request(self, port: int, path: str, *, method: str = "GET", data: bytes | None = None,
                 headers: dict[str, str] | None = None) -> tuple[int, bytes]:
        request = urllib.request.Request(
            f"http://127.0.0.1:{port}{path}", method=method, data=data, headers=headers or {})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            response = opener.open(request, timeout=3)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.read()

    def _revoke(self, admin_port: int, connection_id: str) -> None:
        status, page = self._request(admin_port, "/")
        self.assertEqual(200, status)
        match = re.search(rb'name="csrf" value="([^"]+)"', page)
        self.assertIsNotNone(match)
        csrf = html.unescape(match.group(1).decode("utf-8"))
        body = urllib.parse.urlencode({"csrf": csrf}).encode("ascii")
        origin = f"http://127.0.0.1:{admin_port}"
        status, _ = self._request(
            admin_port,
            f"/admin/connections/{connection_id}/revoke",
            method="POST",
            data=body,
            headers={"Content-Type": "application/x-www-form-urlencoded", "Origin": origin},
        )
        self.assertEqual(200, status)

    def test_cli_rejects_alias_owner_and_kill_release_preserves_revocation(self):
        with tempfile.TemporaryDirectory(prefix="padnote-owner-") as directory:
            root = Path(directory)
            state_dir = root / "state"
            state_dir.mkdir()
            alias = root / "state-alias"
            alias.symlink_to(state_dir, target_is_directory=True)
            home = root / "isolated-home"
            home.mkdir()

            with StateStore(state_dir) as seed:
                instance = seed.add_instance(
                    "hermes", "Fixture Hermes", "http://127.0.0.1:8642")
                instance_id = instance["instance_id"]
                seed.update_instance_check(
                    instance_id, health="ready", detail="ready",
                    features={"run_submission": True}, executable=True)
                code, _ = seed.create_pair_code(instance_id, "https://computer.example.ts.net")
                pair = seed.request_pair(code, "fixture-device", "Fixture tablet")
                seed.decide_pair(pair["request_id"], True)
                _, claim = seed.claim_pair(pair["request_id"], pair["poll_token"])
                token = claim["connections"][0]["token"]
                connection_id = seed.authenticate(instance_id, token)["connection_id"]

            first: subprocess.Popen | None = None
            recovered: subprocess.Popen | None = None
            try:
                first_ports = self._ports()
                first = self._start(state_dir, first_ports, home)
                before_status, _ = self._request(
                    first_ports[1], f"/padnote/v1/agents/{instance_id}/capabilities",
                    headers={"Authorization": "Bearer " + token})
                self.assertEqual(200, before_status)
                self._revoke(first_ports[0], connection_id)
                revoked_state = (state_dir / "state.json").read_bytes()

                blocked = subprocess.run(
                    self._command(alias, self._ports()),
                    cwd=ROOT,
                    env=self._environment(home),
                    stdin=subprocess.DEVNULL,
                    capture_output=True,
                    text=True,
                    timeout=8,
                )
                self.assertEqual(1, blocked.returncode, blocked.stdout + blocked.stderr)
                self.assertIn("already using this data directory", blocked.stderr)
                self.assertNotIn(token, blocked.stdout + blocked.stderr)
                self.assertEqual(revoked_state, (state_dir / "state.json").read_bytes())

                self._stop(first, hard=True)
                first = None
                recovered_ports = self._ports()
                recovered = self._start(alias, recovered_ports, home)
                status, _ = self._request(
                    recovered_ports[1], f"/padnote/v1/agents/{instance_id}/capabilities",
                    headers={"Authorization": "Bearer " + token})
                self.assertIn(status, (401, 403))

                self._stop(recovered, hard=False)
                recovered = None
                with StateStore(state_dir) as final_state:
                    with self.assertRaises(AuthorizationError):
                        final_state.authenticate(instance_id, token)
            finally:
                self._stop(first, hard=True)
                self._stop(recovered, hard=True)

    def test_cli_releases_state_and_partial_server_when_second_port_is_busy(self):
        with tempfile.TemporaryDirectory(prefix="padnote-owner-failed-start-") as directory:
            root = Path(directory)
            state_dir = root / "state"
            home = root / "isolated-home"
            home.mkdir()
            admin_port = self._free_port()
            with socket.socket() as occupied:
                occupied.bind(("127.0.0.1", 0))
                occupied.listen()
                api_port = occupied.getsockname()[1]
                failed = subprocess.run(
                    self._command(state_dir, (admin_port, api_port)),
                    cwd=ROOT,
                    env=self._environment(home),
                    stdin=subprocess.DEVNULL,
                    capture_output=True,
                    text=True,
                    timeout=8,
                )
            self.assertEqual(1, failed.returncode, failed.stdout + failed.stderr)
            self.assertIn("could not open its data directory or local ports", failed.stderr)
            with StateStore(state_dir):
                pass
            with socket.socket() as rebound:
                rebound.bind(("127.0.0.1", admin_port))


if __name__ == "__main__":
    unittest.main(verbosity=2)
