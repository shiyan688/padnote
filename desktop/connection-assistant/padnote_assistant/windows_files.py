from __future__ import annotations

"""Race-resistant Windows file opening for untrusted task artifacts.

The implementation deliberately opens every path component as a handle, rejects
reparse points, and holds directory handles without FILE_SHARE_DELETE until the
leaf is open. It never falls back to reading through a normal path open.
"""

import ctypes
import ntpath
import os
import re
from ctypes import wintypes
from pathlib import Path


class WindowsSafeOpenError(OSError):
    """Path-free error with a stable code suitable for tests and safe mapping."""

    def __init__(self, code: str, *, winerror: int | None = None):
        super().__init__(code)
        self.code = code
        self.winerror = winerror


_GENERIC_READ = 0x80000000
_FILE_READ_ATTRIBUTES = 0x80
_FILE_SHARE_READ = 0x1
_FILE_SHARE_WRITE = 0x2
_OPEN_EXISTING = 3
_FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000
_FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
_FILE_FLAG_SEQUENTIAL_SCAN = 0x08000000
_FILE_ATTRIBUTE_DIRECTORY = 0x10
_FILE_ATTRIBUTE_REPARSE_POINT = 0x400
_FILE_TYPE_DISK = 1
_VOLUME_NAME_DOS = 0
_FILE_NAME_NORMALIZED = 0
_INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value


class _BY_HANDLE_FILE_INFORMATION(ctypes.Structure):
    _fields_ = [
        ("dwFileAttributes", wintypes.DWORD),
        ("ftCreationTime", wintypes.FILETIME),
        ("ftLastAccessTime", wintypes.FILETIME),
        ("ftLastWriteTime", wintypes.FILETIME),
        ("dwVolumeSerialNumber", wintypes.DWORD),
        ("nFileSizeHigh", wintypes.DWORD),
        ("nFileSizeLow", wintypes.DWORD),
        ("nNumberOfLinks", wintypes.DWORD),
        ("nFileIndexHigh", wintypes.DWORD),
        ("nFileIndexLow", wintypes.DWORD),
    ]


class _WinApi:
    def __init__(self) -> None:
        if os.name != "nt":
            raise WindowsSafeOpenError("unsupported_platform")
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self._kernel32 = kernel32
        self._create = kernel32.CreateFileW
        self._create.argtypes = (wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                 wintypes.LPVOID, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE)
        self._create.restype = wintypes.HANDLE
        self._info = kernel32.GetFileInformationByHandle
        self._info.argtypes = (wintypes.HANDLE, ctypes.POINTER(_BY_HANDLE_FILE_INFORMATION))
        self._info.restype = wintypes.BOOL
        self._file_type = kernel32.GetFileType
        self._file_type.argtypes = (wintypes.HANDLE,)
        self._file_type.restype = wintypes.DWORD
        self._final_path = kernel32.GetFinalPathNameByHandleW
        self._final_path.argtypes = (wintypes.HANDLE, wintypes.LPWSTR, wintypes.DWORD, wintypes.DWORD)
        self._final_path.restype = wintypes.DWORD
        self._close = kernel32.CloseHandle
        self._close.argtypes = (wintypes.HANDLE,)
        self._close.restype = wintypes.BOOL

    def open(self, path: str, access: int, share: int, flags: int) -> int:
        create_path = path
        if re.match(r"^[A-Za-z]:\\", path) and not path.startswith("\\\\?\\"):
            create_path = "\\\\?\\" + path
        handle = self._create(create_path, access, share, None, _OPEN_EXISTING, flags, None)
        raw = ctypes.cast(handle, ctypes.c_void_p).value
        if raw == _INVALID_HANDLE_VALUE or raw is None:
            raise WindowsSafeOpenError("open_failed", winerror=ctypes.get_last_error())
        return raw

    def info(self, handle: int) -> _BY_HANDLE_FILE_INFORMATION:
        result = _BY_HANDLE_FILE_INFORMATION()
        if not self._info(wintypes.HANDLE(handle), ctypes.byref(result)):
            raise WindowsSafeOpenError("metadata_failed")
        return result

    def file_type(self, handle: int) -> int:
        result = self._file_type(wintypes.HANDLE(handle))
        if result == 0:
            raise WindowsSafeOpenError("type_failed")
        return result

    def final_path(self, handle: int) -> str:
        size = 512
        while size <= 32768:
            buffer = ctypes.create_unicode_buffer(size)
            count = self._final_path(wintypes.HANDLE(handle), buffer, size,
                                     _VOLUME_NAME_DOS | _FILE_NAME_NORMALIZED)
            if count == 0:
                raise WindowsSafeOpenError("canonical_path_failed")
            if count < size:
                return buffer.value
            size = count + 1
        raise WindowsSafeOpenError("canonical_path_too_long")

    def close(self, handle: int) -> None:
        if not self._close(wintypes.HANDLE(handle)):
            raise WindowsSafeOpenError("close_failed")

    def to_fd(self, handle: int) -> int:
        import msvcrt

        try:
            return msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
        except OSError as error:
            raise WindowsSafeOpenError("ownership_transfer_failed") from error


def _validate_component(component: str) -> None:
    if not isinstance(component, str) or not component or component in {".", ".."} \
            or ":" in component or "/" in component or "\\" in component \
            or any(ord(char) < 32 or char in '*?"<>|' for char in component) \
            or component.endswith((".", " ")):
        raise WindowsSafeOpenError("invalid_component")
    device_stem = component.split(".", 1)[0].upper()
    if device_stem in {"CON", "PRN", "AUX", "NUL", "CLOCK$"} \
            or re.fullmatch(r"(?:COM|LPT)[1-9¹²³]", device_stem):
        raise WindowsSafeOpenError("device_component")


def _canonical(path: str) -> str:
    normalized = ntpath.normpath(path)
    if normalized.startswith("\\\\?\\"):
        normalized = normalized[4:]
    return ntpath.normcase(normalized)


def _validate_handle(api: object, handle: int, expected: str, *, directory: bool,
                     volume: int | None = None) -> int:
    if api.file_type(handle) != _FILE_TYPE_DISK:
        raise WindowsSafeOpenError("not_disk_file")
    info = api.info(handle)
    attrs = info.dwFileAttributes
    if attrs & _FILE_ATTRIBUTE_REPARSE_POINT:
        raise WindowsSafeOpenError("reparse_rejected")
    is_directory = bool(attrs & _FILE_ATTRIBUTE_DIRECTORY)
    if is_directory != directory:
        raise WindowsSafeOpenError("wrong_file_kind")
    if volume is not None and info.dwVolumeSerialNumber != volume:
        raise WindowsSafeOpenError("volume_changed")
    if _canonical(api.final_path(handle)) != _canonical(expected):
        raise WindowsSafeOpenError("canonical_path_mismatch")
    return info.dwVolumeSerialNumber


def open_regular_file(root: Path | str, components: tuple[str, ...], max_depth: int,
                      *, _api: object | None = None) -> int:
    """Open a regular, single-link local-disk file beneath an absolute root.

    `_api` is an internal test seam; production always uses the Win32 API.
    The returned CRT fd owns the leaf HANDLE.
    """
    try:
        root_text = os.fspath(root)
        if not isinstance(root_text, str) or not ntpath.isabs(root_text):
            raise WindowsSafeOpenError("invalid_root")
        drive, tail = ntpath.splitdrive(root_text)
        if not re.fullmatch(r"[A-Za-z]:", drive) or tail.startswith("\\\\"):
            raise WindowsSafeOpenError("unsupported_root")
        root_parts = [part for part in tail.replace("/", "\\").split("\\") if part]
        for part in root_parts:
            _validate_component(part)
        if not components or len(components) > max_depth:
            raise WindowsSafeOpenError("invalid_depth")
        for component in components:
            _validate_component(component)

        api = _api if _api is not None else _WinApi()
        expected = drive + "\\"
        held: list[int] = []
        leaf: int | None = None
        descriptor: int | None = None
        volume: int | None = None
        try:
            # Anchor the traversal at the local volume root. OPEN_REPARSE_POINT
            # is applied separately to each component, not just the leaf.
            root_handle = api.open(expected, _FILE_READ_ATTRIBUTES,
                                   _FILE_SHARE_READ,
                                   _FILE_FLAG_OPEN_REPARSE_POINT | _FILE_FLAG_BACKUP_SEMANTICS)
            held.append(root_handle)
            volume = _validate_handle(api, root_handle, expected, directory=True)
            directories = root_parts + list(components[:-1])
            for component in directories:
                expected = ntpath.join(expected, component)
                handle = api.open(expected, _FILE_READ_ATTRIBUTES,
                                  _FILE_SHARE_READ,
                                  _FILE_FLAG_OPEN_REPARSE_POINT | _FILE_FLAG_BACKUP_SEMANTICS)
                held.append(handle)
                _validate_handle(api, handle, expected, directory=True, volume=volume)
            expected = ntpath.join(expected, components[-1])
            leaf = api.open(expected, _GENERIC_READ, _FILE_SHARE_READ,
                            _FILE_FLAG_OPEN_REPARSE_POINT | _FILE_FLAG_SEQUENTIAL_SCAN)
            info = api.info(leaf)
            if api.file_type(leaf) != _FILE_TYPE_DISK:
                raise WindowsSafeOpenError("not_disk_file")
            attrs = info.dwFileAttributes
            if attrs & _FILE_ATTRIBUTE_REPARSE_POINT:
                raise WindowsSafeOpenError("reparse_rejected")
            if attrs & _FILE_ATTRIBUTE_DIRECTORY:
                raise WindowsSafeOpenError("wrong_file_kind")
            if info.dwVolumeSerialNumber != volume:
                raise WindowsSafeOpenError("volume_changed")
            if info.nNumberOfLinks != 1:
                raise WindowsSafeOpenError("hardlink_rejected")
            if _canonical(api.final_path(leaf)) != _canonical(expected):
                raise WindowsSafeOpenError("canonical_path_mismatch")
            descriptor = api.to_fd(leaf)
            leaf = None  # ownership was transferred to the CRT descriptor
        finally:
            close_error: BaseException | None = None
            if leaf is not None:
                try:
                    api.close(leaf)
                except BaseException as error:  # continue closing every acquired handle
                    close_error = close_error or error
            for handle in reversed(held):
                try:
                    api.close(handle)
                except BaseException as error:
                    close_error = close_error or error
            if close_error is not None:
                if descriptor is not None:
                    os.close(descriptor)
                    descriptor = None
                raise close_error
        assert descriptor is not None
        return descriptor
    except WindowsSafeOpenError:
        raise
    except (OSError, ValueError, TypeError) as error:
        raise WindowsSafeOpenError("safe_open_failed") from error
