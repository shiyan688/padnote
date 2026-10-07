from __future__ import annotations

import unittest
from pathlib import Path

from padnote_assistant import windows_files as wf


class _Info:
    def __init__(self, *, directory: bool, links: int = 1, reparse: bool = False, volume: int = 7):
        self.dwFileAttributes = (wf._FILE_ATTRIBUTE_DIRECTORY if directory else 0) \
            | (wf._FILE_ATTRIBUTE_REPARSE_POINT if reparse else 0)
        self.dwVolumeSerialNumber = volume
        self.nNumberOfLinks = links


class _Api:
    def __init__(self, *, fail_info: dict[int, _Info] | None = None,
                 fail_open_path: str | None = None, wrong_final_handle: int | None = None):
        self.opened: list[tuple[str, int, int, int, int]] = []
        self.closed: list[int] = []
        self.expected: dict[int, str] = {}
        self.fail_info = fail_info or {}
        self.fail_open_path = fail_open_path
        self.wrong_final_handle = wrong_final_handle
        self.next_handle = 10

    def open(self, path: str, access: int, share: int, flags: int) -> int:
        if path == self.fail_open_path:
            raise wf.WindowsSafeOpenError("open_failed")
        handle = self.next_handle
        self.next_handle += 1
        self.opened.append((path, access, share, flags, handle))
        self.expected[handle] = path
        return handle

    def info(self, handle: int) -> _Info:
        if handle in self.fail_info:
            return self.fail_info[handle]
        return _Info(directory=handle != 13)

    def file_type(self, handle: int) -> int:
        return wf._FILE_TYPE_DISK

    def final_path(self, handle: int) -> str:
        if handle == self.wrong_final_handle:
            return r"C:\outside\video.mp4"
        return self.expected[handle]

    def close(self, handle: int) -> None:
        self.closed.append(handle)

    def to_fd(self, handle: int) -> int:
        return 900


class WindowsSafeFilesTests(unittest.TestCase):
    def test_opens_components_no_delete_share_and_transfers_only_leaf(self):
        api = _Api()
        descriptor = wf.open_regular_file(Path("C:\\root"), ("dir", "video.mp4"), 5, _api=api)
        self.assertEqual(900, descriptor)
        self.assertEqual(["C:\\", r"C:\root", r"C:\root\dir", r"C:\root\dir\video.mp4"],
                         [item[0] for item in api.opened])
        for _path, _access, share, flags, _handle in api.opened[:-1]:
            self.assertEqual(wf._FILE_SHARE_READ, share)
            self.assertEqual(wf._FILE_FLAG_OPEN_REPARSE_POINT | wf._FILE_FLAG_BACKUP_SEMANTICS, flags)
        _path, access, share, flags, leaf = api.opened[-1]
        self.assertEqual(wf._GENERIC_READ, access)
        self.assertEqual(wf._FILE_SHARE_READ, share)
        self.assertEqual(wf._FILE_FLAG_OPEN_REPARSE_POINT | wf._FILE_FLAG_SEQUENTIAL_SCAN, flags)
        self.assertEqual([12, 11, 10], api.closed)
        self.assertNotIn(leaf, api.closed)  # CRT descriptor now owns the leaf handle

    def test_rejects_hardlink_and_closes_every_raw_handle(self):
        api = _Api(fail_info={13: _Info(directory=False, links=2)})
        with self.assertRaises(wf.WindowsSafeOpenError) as caught:
            wf.open_regular_file(r"C:\root", ("dir", "video.mp4"), 5, _api=api)
        self.assertEqual("hardlink_rejected", caught.exception.code)
        self.assertEqual([13, 12, 11, 10], api.closed)

    def test_rejects_reparse_parent_and_closes_parent_handles(self):
        api = _Api(fail_info={12: _Info(directory=True, reparse=True)})
        with self.assertRaises(wf.WindowsSafeOpenError) as caught:
            wf.open_regular_file(r"C:\root", ("dir", "video.mp4"), 5, _api=api)
        self.assertEqual("reparse_rejected", caught.exception.code)
        self.assertEqual([12, 11, 10], api.closed)
        self.assertEqual(3, len(api.opened))  # leaf was never attempted

    def test_rejects_volume_mismatch_and_canonical_path_alias(self):
        api = _Api(fail_info={13: _Info(directory=False, volume=8)})
        with self.assertRaises(wf.WindowsSafeOpenError) as caught:
            wf.open_regular_file(r"C:\root", ("dir", "video.mp4"), 5, _api=api)
        self.assertEqual("volume_changed", caught.exception.code)
        self.assertEqual([13, 12, 11, 10], api.closed)

        api = _Api(wrong_final_handle=11)
        with self.assertRaises(wf.WindowsSafeOpenError) as caught:
            wf.open_regular_file(r"C:\root", ("dir", "video.mp4"), 5, _api=api)
        self.assertEqual("canonical_path_mismatch", caught.exception.code)
        self.assertEqual([11, 10], api.closed)

    def test_open_failure_releases_all_held_ancestor_handles(self):
        api = _Api(fail_open_path=r"C:\root\dir")
        with self.assertRaises(wf.WindowsSafeOpenError) as caught:
            wf.open_regular_file(r"C:\root", ("dir", "video.mp4"), 5, _api=api)
        self.assertEqual("open_failed", caught.exception.code)
        self.assertEqual([11, 10], api.closed)

    def test_rejects_windows_aliases_before_opening(self):
        for part in ("file:stream", "..", "bad.", "bad ", "CON.txt", r"x\y", "C:foo"):
            with self.subTest(part=part):
                api = _Api()
                with self.assertRaises(wf.WindowsSafeOpenError):
                    wf.open_regular_file(r"C:\root", (part,), 5, _api=api)
                self.assertEqual([], api.opened)


if __name__ == "__main__":
    unittest.main()
