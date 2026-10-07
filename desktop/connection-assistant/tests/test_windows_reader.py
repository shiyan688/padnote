from __future__ import annotations

import base64
import io
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import uuid

from padnote_assistant import windows_reader as reader


class WindowsReaderProtocolTests(unittest.TestCase):
    """Protocol/reader tests with a fake opener; native safety is tested separately on Windows."""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="padnote reader ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.opened: list[tuple[Path, tuple[str, ...], int]] = []
        self.descriptors: list[int] = []

    def _open_fixture(self, root: Path, components: tuple[str, ...], max_depth: int) -> int:
        self.opened.append((root, components, max_depth))
        descriptor = os.open(root.joinpath(*components), os.O_RDONLY)
        self.descriptors.append(descriptor)
        return descriptor

    def _request(self, path: str, mode: str = "whole", maximum: int = 1024,
                 **extra: object) -> dict[str, object]:
        return {"schema_version": 1, "id": str(uuid.uuid4()), "mode": mode,
                "relative_path": path, "max_bytes": maximum, **extra}

    def _serve(self, requests: list[dict[str, object]], *, trailing: bytes = b"",
               opener=None) -> tuple[int, list[dict[str, object]], bytes]:
        incoming = b"".join(json.dumps(request, separators=(",", ":")).encode() + b"\n"
                            for request in requests) + trailing
        output = io.BytesIO()
        status = reader.serve(self.root, io.BytesIO(incoming), output,
                              opener=self._open_fixture if opener is None else opener)
        raw_lines = output.getvalue().splitlines()
        return status, [json.loads(line) for line in raw_lines], output.getvalue()

    def _write(self, relative: str, data: bytes) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def _assert_closed(self) -> None:
        for descriptor in self.descriptors:
            with self.assertRaises(OSError):
                os.fstat(descriptor)

    def test_whole_reads_bounded_bytes_and_exact_metadata_as_strings(self) -> None:
        self._write("子目录/data.bin", b"0123456789")
        request = self._request("子目录/data.bin", maximum=10, declared_size=10)
        status, responses, _raw = self._serve([request])
        self.assertEqual(0, status)
        response = responses[0]
        self.assertEqual({
            "schema_version", "id", "ok", "mode", "data_base64", "file_size",
            "offset", "dev", "ino", "mtime_ns", "ctime_ns",
        }, set(response))
        self.assertEqual(request["id"], response["id"])
        self.assertIs(response["ok"], True)
        self.assertEqual("whole", response["mode"])
        self.assertEqual("MDEyMzQ1Njc4OQ==", response["data_base64"])
        self.assertEqual("10", response["file_size"])
        self.assertEqual("0", response["offset"])
        for key in ("file_size", "offset", "dev", "ino", "mtime_ns", "ctime_ns"):
            self.assertRegex(response[key], r"^(?:0|[1-9][0-9]*)$")
        self.assertEqual([(self.root, ("子目录", "data.bin"), reader.MAX_PATH_DEPTH)], self.opened)
        self._assert_closed()

    def test_empty_whole_with_zero_limit_returns_empty_and_closes_fd(self) -> None:
        self._write("empty-whole.bin", b"")
        request = self._request("empty-whole.bin", maximum=0, declared_size=0)
        status, responses, _raw = self._serve([request])
        self.assertEqual(0, status)
        response = responses[0]
        self.assertIs(response["ok"], True)
        self.assertEqual("", response["data_base64"])
        self.assertEqual("0", response["file_size"])
        self.assertEqual("0", response["offset"])
        self._assert_closed()

    def test_tail_returns_only_requested_eof_window_and_exact_offset(self) -> None:
        self._write("events.ndjson", b"0123456789abcdef")
        request = self._request("events.ndjson", mode="tail", maximum=6)
        status, responses, _raw = self._serve([request])
        self.assertEqual(0, status)
        self.assertEqual("abcdef", base64.b64decode(responses[0]["data_base64"]).decode())
        self.assertEqual("16", responses[0]["file_size"])
        self.assertEqual("10", responses[0]["offset"])
        self._assert_closed()

    def test_empty_tail_returns_empty_and_closes_fd(self) -> None:
        self._write("empty-tail.bin", b"")
        request = self._request("empty-tail.bin", mode="tail", maximum=10)
        status, responses, _raw = self._serve([request])
        self.assertEqual(0, status)
        self.assertIs(responses[0]["ok"], True)
        self.assertEqual("", responses[0]["data_base64"])
        self.assertEqual("0", responses[0]["file_size"])
        self.assertEqual("0", responses[0]["offset"])
        self._assert_closed()

    def test_short_reads_are_completed_without_exceeding_bound(self) -> None:
        self._write("short.bin", b"abcdefghijk")
        actual_read = os.read

        def short_read(descriptor: int, length: int) -> bytes:
            return actual_read(descriptor, min(length, 3))

        request = self._request("short.bin", maximum=11)
        output = io.BytesIO()
        with patch.object(reader.os, "read", side_effect=short_read):
            status = reader.serve(self.root, io.BytesIO(json.dumps(request).encode() + b"\n"),
                                  output, opener=self._open_fixture)
        self.assertEqual(0, status)
        self.assertTrue(json.loads(output.getvalue())["ok"])
        self._assert_closed()

    def test_rejects_size_and_declared_size_mismatch_and_closes_fd(self) -> None:
        self._write("bounded.bin", b"12345")
        too_small = self._request("bounded.bin", maximum=4)
        mismatch = self._request("bounded.bin", maximum=8, declared_size=4)
        status, responses, _raw = self._serve([too_small, mismatch])
        self.assertEqual(0, status)
        self.assertEqual(["size_limit", "file_changed"], [item["code"] for item in responses])
        self._assert_closed()

    def test_metadata_change_during_read_is_rejected_and_fd_is_closed(self) -> None:
        self._write("changing.bin", b"abcdef")
        actual_fstat = os.fstat
        calls = 0

        def changing_fstat(descriptor: int):
            nonlocal calls
            calls += 1
            info = actual_fstat(descriptor)
            if calls == 2:
                values = {name: getattr(info, name) for name in dir(info)
                          if name.startswith("st_") and not callable(getattr(info, name))}
                values["st_mtime_ns"] += 1
                return type("ChangedStat", (), values)()
            return info

        request = self._request("changing.bin", maximum=6)
        output = io.BytesIO()
        with patch.object(reader.os, "fstat", side_effect=changing_fstat):
            status = reader.serve(self.root, io.BytesIO(json.dumps(request).encode() + b"\n"),
                                  output, opener=self._open_fixture)
        self.assertEqual(0, status)
        self.assertEqual("file_changed", json.loads(output.getvalue())["code"])
        self._assert_closed()

    def test_invalid_paths_and_request_shapes_never_reach_opener(self) -> None:
        invalid_paths = ("../secret", "/absolute/file", r"dir\file", "C:/file", "name:stream",
                         "dir//file", "./file", "CON.txt", "trailing.", "trailing ")
        requests = [self._request(path) for path in invalid_paths]
        requests.extend([
            {**self._request("safe.txt"), "unexpected": True},
            {**self._request("safe.txt"), "max_bytes": True},
            {key: value for key, value in self._request("safe.txt").items() if key != "id"},
        ])
        status, responses, _raw = self._serve(requests)
        self.assertEqual(0, status)
        self.assertEqual(len(requests), len(responses))
        self.assertTrue(all(item["code"] in {"invalid_path", "invalid_request"} for item in responses))
        self.assertEqual([], self.opened)

    def test_duplicate_json_fields_and_oversized_paths_are_rejected(self) -> None:
        valid_id = str(uuid.uuid4())
        duplicate = (f'{{"schema_version":1,"id":"{valid_id}","id":"{valid_id}",'
                     '"mode":"whole","relative_path":"safe.txt","max_bytes":10}\n').encode()
        oversized_path = self._request("a" * (reader.MAX_PATH_BYTES + 1))
        output = io.BytesIO()
        status = reader.serve(self.root, io.BytesIO(duplicate +
            json.dumps(oversized_path).encode() + b"\n"), output, opener=self._open_fixture)
        self.assertEqual(0, status)
        responses = [json.loads(item) for item in output.getvalue().splitlines()]
        self.assertEqual(["invalid_request", "invalid_path"], [item["code"] for item in responses])
        self.assertEqual([], self.opened)

    def test_not_found_error_mapping_does_not_leak_path_or_winerror_text(self) -> None:
        from padnote_assistant.windows_files import WindowsSafeOpenError

        request = self._request("absent.json")

        def missing(_root: Path, _components: tuple[str, ...], _depth: int) -> int:
            raise WindowsSafeOpenError("open_failed", winerror=2)

        status, responses, raw = self._serve([request], opener=missing)
        self.assertEqual(0, status)
        self.assertEqual({"schema_version", "id", "ok", "code"}, set(responses[0]))
        self.assertEqual("not_found", responses[0]["code"])
        self.assertNotIn(b"absent.json", raw)
        self.assertNotIn(b"WinError", raw)

    @unittest.skipUnless(os.name == "nt", "requires native Windows file-handle validation")
    def test_isolated_script_serves_multiple_reads_through_real_windows_handles(self) -> None:
        self._write("中文 子目录/data.json", b'{"ok":true}')
        self._write("中文 子目录/events.ndjson", b"event-one\nevent-two\n")
        request = self._request("中文 子目录/data.json", maximum=64)
        tail_request = self._request("中文 子目录/events.ndjson", mode="tail", maximum=10)
        script = Path(reader.__file__).resolve()
        completed = subprocess.run(
            [sys.executable, "-I", "-B", "-u", str(script), "--root", str(self.root)],
            input=b"".join(json.dumps(item).encode("utf-8") + b"\n"
                            for item in (request, tail_request)), capture_output=True,
            timeout=10, check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stderr.decode("utf-8", "replace"))
        self.assertEqual(b"", completed.stderr)
        responses = [json.loads(line) for line in completed.stdout.splitlines()]
        self.assertEqual([request["id"], tail_request["id"]], [item["id"] for item in responses])
        self.assertTrue(all(item["ok"] for item in responses))
        self.assertEqual('{"ok":true}', base64.b64decode(responses[0]["data_base64"]).decode("utf-8"))
        self.assertEqual(b"event-two\n", base64.b64decode(responses[1]["data_base64"]))
        self.assertEqual("20", responses[1]["file_size"])
        self.assertEqual("10", responses[1]["offset"])

    def test_request_and_total_limits_are_terminal_and_bounded(self) -> None:
        self._write("small.bin", b"123")
        requests = [self._request("small.bin", maximum=3), self._request("small.bin", maximum=3)]
        output = io.BytesIO()
        with patch.object(reader, "MAX_TOTAL_RAW_BYTES", 5):
            status = reader.serve(self.root, io.BytesIO(b"".join(
                json.dumps(item).encode() + b"\n" for item in requests)), output,
                opener=self._open_fixture)
        self.assertEqual(2, status)
        self.assertEqual([True, False], [item["ok"] for item in map(json.loads, output.getvalue().splitlines())])
        self.assertEqual("total_limit", json.loads(output.getvalue().splitlines()[1])["code"])
        self._assert_closed()

        output = io.BytesIO()
        with patch.object(reader, "MAX_REQUESTS", 1):
            status = reader.serve(self.root, io.BytesIO(b"".join(
                json.dumps(item).encode() + b"\n" for item in requests)), output,
                opener=self._open_fixture)
        self.assertEqual(2, status)
        self.assertEqual("request_limit", json.loads(output.getvalue().splitlines()[1])["code"])

    def test_oversized_or_unterminated_message_fails_closed(self) -> None:
        output = io.BytesIO()
        status = reader.serve(self.root, io.BytesIO(b"x" * (reader.MAX_MESSAGE_BYTES + 1)), output,
                              opener=self._open_fixture)
        self.assertEqual(2, status)
        self.assertEqual("protocol_error", json.loads(output.getvalue())["code"])
        self.assertEqual([], self.opened)


if __name__ == "__main__":
    unittest.main()
