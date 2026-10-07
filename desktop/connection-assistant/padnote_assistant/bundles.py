from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import os
import stat
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

from .security import ValidationError, canonical_json
from .windows_files import WindowsSafeOpenError, open_regular_file as _open_windows_regular


ALLOWED_BUNDLE_PATHS = {
    "request.json",
    "input/manifest.json",
    "input/content.md",
    "work/.keep",
    "output/.keep",
}
MAX_BUNDLE_BYTES = 8 * 1024 * 1024
MAX_EXPANDED_BYTES = 32 * 1024 * 1024
MAX_ARTIFACT_BYTES = 100 * 1024 * 1024
MAX_TASK_ARTIFACT_BYTES = 256 * 1024 * 1024
MAX_BUNDLE_ENTRIES = 8
MAX_ARTIFACT_COUNT = 128
MAX_ARTIFACT_DEPTH = 5
MAX_OUTPUT_ENTRIES = 512

MEDIA_TYPES = {
    ".md": "text/markdown",
    ".txt": "text/plain",
    ".pdf": "application/pdf",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".mp4": "video/mp4",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".srt": "application/x-subrip",
}

BUILTIN_VIDEO_ARTIFACTS = {
    "video": ("video-main", "explanation.mp4", "video/mp4"),
    "captions": ("captions-main", "captions.srt", "application/x-subrip"),
    "thumbnail": ("thumbnail-main", "thumbnail.png", "image/png"),
    "render_manifest": ("render-manifest", "render.manifest.json", "application/json"),
    "qa_report": ("qa-report", "qa-report.json", "application/json"),
}
BUILTIN_VIDEO_PUBLIC_FILES = {
    path: media_type for _artifact_id, path, media_type in BUILTIN_VIDEO_ARTIFACTS.values()
}


def _safe_zip_name(name: str) -> str:
    if "\\" in name or "\x00" in name or "//" in name or name.startswith("/") or name.endswith("/"):
        raise ValidationError("task bundle contains an invalid path")
    raw_parts = name.split("/")
    if any(part in {"", ".", ".."} for part in raw_parts):
        raise ValidationError("task bundle contains an unsafe path")
    pure = PurePosixPath(name)
    if pure.is_absolute():
        raise ValidationError("task bundle contains an unsafe path")
    normalized = pure.as_posix()
    if normalized not in ALLOWED_BUNDLE_PATHS:
        raise ValidationError("task bundle contains an unsupported entry")
    return normalized


def prepare_task_directory(tasks_root: Path, task_id: str, request_payload: dict[str, Any],
                           bundle_base64: str | None, bundle_sha256: str | None) -> Path:
    task_dir = tasks_root / task_id
    if task_dir.exists():
        return task_dir
    temporary = tasks_root / (task_id + ".preparing")
    if temporary.exists():
        raise ValidationError("task directory is already being prepared")
    temporary.mkdir(mode=0o700, parents=False)
    try:
        (temporary / "input").mkdir(mode=0o700)
        (temporary / "work").mkdir(mode=0o700)
        (temporary / "output").mkdir(mode=0o700)
        safe_request = {key: value for key, value in request_payload.items()
                        if key not in {"bundle_base64", "bundle_sha256"}}
        if bundle_base64 is not None:
            if not isinstance(bundle_base64, str) or not isinstance(bundle_sha256, str):
                raise ValidationError("task bundle requires base64 data and SHA-256")
            try:
                raw = base64.b64decode(bundle_base64, validate=True)
            except (binascii.Error, ValueError) as error:
                raise ValidationError("task bundle is not valid base64") from error
            if len(raw) > MAX_BUNDLE_BYTES:
                raise ValidationError("task bundle exceeds 8 MiB")
            actual_hash = hashlib.sha256(raw).hexdigest()
            if not hmac.compare_digest(actual_hash, bundle_sha256.lower()):
                raise ValidationError("task bundle SHA-256 does not match")
            archive_path = temporary / ".bundle.zip"
            archive_path.write_bytes(raw)
            _extract_bundle(archive_path, temporary)
            archive_path.unlink()
            _validate_bundle_documents(temporary, safe_request)
        (temporary / "work" / "padnote-submission.json").write_bytes(canonical_json(safe_request) + b"\n")
        os.replace(temporary, task_dir)
        return task_dir
    except Exception:
        _remove_tree(temporary)
        raise


def prepare_followup_directory(tasks_root: Path, task_id: str, parent_task_id: str) -> Path:
    """Create a fresh output area and a pointer to the immutable parent snapshot."""
    task_dir = tasks_root / task_id
    if task_dir.exists():
        return task_dir
    temporary = tasks_root / (task_id + ".preparing")
    if temporary.exists():
        raise ValidationError("task directory is already being prepared")
    temporary.mkdir(mode=0o700, parents=False)
    try:
        (temporary / "input").mkdir(mode=0o700)
        (temporary / "work").mkdir(mode=0o700)
        (temporary / "output").mkdir(mode=0o700)
        reference = {"parent_task_id": parent_task_id,
                     "source_snapshot": str((tasks_root / parent_task_id / "input").resolve())}
        (temporary / "work" / "padnote-followup-reference.json").write_bytes(
            canonical_json(reference) + b"\n")
        os.replace(temporary, task_dir)
        return task_dir
    except Exception:
        _remove_tree(temporary)
        raise


def input_snapshot_digest(input_dir: Path) -> str:
    digest = hashlib.sha256()
    files: list[PurePosixPath] = []
    stack: list[tuple[Path, PurePosixPath]] = [(input_dir, PurePosixPath())]
    try:
        if not stat.S_ISDIR(os.lstat(input_dir).st_mode):
            raise ValidationError("source snapshot root is not a regular directory")
    except OSError as error:
        raise ValidationError("source snapshot is unavailable") from error
    entries_seen = 0
    while stack:
        directory, prefix = stack.pop()
        try:
            entries = os.scandir(directory)
        except OSError as error:
            raise ValidationError("source snapshot cannot be safely enumerated") from error
        with entries:
            for entry in entries:
                relative = prefix / entry.name
                entries_seen += 1
                if entries_seen > 32:
                    raise ValidationError("source snapshot contains too many entries")
                if len(relative.parts) > MAX_ARTIFACT_DEPTH or entry.is_symlink():
                    raise ValidationError("source snapshot contains an unsafe path")
                if entry.is_dir(follow_symlinks=False):
                    stack.append((Path(entry.path), relative))
                elif entry.is_file(follow_symlinks=False):
                    files.append(relative)
                    if len(files) > MAX_BUNDLE_ENTRIES:
                        raise ValidationError("source snapshot contains too many files")
                else:
                    raise ValidationError("source snapshot contains a non-regular entry")
    total = 0
    for relative in sorted(files, key=lambda item: item.as_posix()):
        name = relative.as_posix().encode("utf-8")
        descriptor = _open_relative_regular(input_dir, relative)
        try:
            size = os.fstat(descriptor).st_size
            total += size
            if size > MAX_EXPANDED_BYTES or total > MAX_EXPANDED_BYTES:
                raise ValidationError("source snapshot is too large")
            file_hash = _hash_descriptor(descriptor, size)
            digest.update(len(name).to_bytes(4, "big"))
            digest.update(name)
            digest.update(size.to_bytes(8, "big"))
            digest.update(bytes.fromhex(file_hash))
        finally:
            os.close(descriptor)
    return digest.hexdigest()


def _extract_bundle(archive_path: Path, destination: Path) -> None:
    seen: set[str] = set()
    total = 0
    try:
        archive = zipfile.ZipFile(archive_path)
    except zipfile.BadZipFile as error:
        raise ValidationError("task bundle is not a valid ZIP") from error
    with archive:
        if len(archive.infolist()) > MAX_BUNDLE_ENTRIES:
            raise ValidationError("task bundle contains too many entries")
        for info in archive.infolist():
            name = _safe_zip_name(info.filename)
            if name in seen:
                raise ValidationError("task bundle contains a duplicate path")
            seen.add(name)
            mode = (info.external_attr >> 16) & 0xFFFF
            if stat.S_ISLNK(mode) or info.is_dir():
                raise ValidationError("task bundle entries must be regular files")
            if name in {"work/.keep", "output/.keep"} and info.file_size != 0:
                raise ValidationError("task bundle .keep entries must be empty")
            total += info.file_size
            if total > MAX_EXPANDED_BYTES:
                raise ValidationError("expanded task bundle is too large")
            target = destination / name
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            with archive.open(info) as source, target.open("wb") as output:
                remaining = info.file_size
                while remaining:
                    chunk = source.read(min(65536, remaining))
                    if not chunk:
                        raise ValidationError("task bundle entry ended unexpectedly")
                    output.write(chunk)
                    remaining -= len(chunk)
        if seen != ALLOWED_BUNDLE_PATHS:
            raise ValidationError("task bundle is missing a required entry")


def _validate_bundle_documents(destination: Path, envelope: dict[str, Any]) -> None:
    try:
        request = json.loads((destination / "request.json").read_text(encoding="utf-8"))
        manifest = json.loads((destination / "input/manifest.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValidationError("task bundle metadata JSON is invalid") from error
    if not isinstance(request, dict) or request.get("schema_version") != "1.0":
        raise ValidationError("task bundle request schema is unsupported")
    task_type = request.get("task_type")
    if not isinstance(task_type, str) or not task_type or len(task_type) > 100:
        raise ValidationError("task bundle task_type is invalid")
    source = request.get("source")
    outer_source = envelope["source"]
    if not isinstance(source, dict) or source.get("note_id") != outer_source["note_id"] \
            or source.get("note_revision") != outer_source["note_revision"]:
        raise ValidationError("task bundle source does not match the submitted note revision")
    if not isinstance(manifest, dict) or manifest.get("schema_version") != "1.0" \
            or not isinstance(manifest.get("files"), list) or len(manifest["files"]) != 1:
        raise ValidationError("task bundle manifest is invalid")
    entry = manifest["files"][0]
    content = destination / "input/content.md"
    if not isinstance(entry, dict) or entry.get("path") != "input/content.md" \
            or entry.get("media_type") != "text/markdown":
        raise ValidationError("task bundle manifest entry is invalid")
    raw = content.read_bytes()
    if entry.get("size_bytes") != len(raw) or entry.get("sha256") != hashlib.sha256(raw).hexdigest():
        raise ValidationError("task bundle content does not match its manifest")


def _remove_tree(root: Path) -> None:
    if not root.exists():
        return
    for path in sorted(root.rglob("*"), reverse=True):
        try:
            path.unlink() if path.is_file() or path.is_symlink() else path.rmdir()
        except FileNotFoundError:
            pass
    try:
        root.rmdir()
    except FileNotFoundError:
        pass


def collect_artifacts(task_dir: Path, task_id: str, *,
                      video_manifest: list[dict[str, Any]] | None = None,
                      worker_task_id: str | None = None,
                      result_manifest_sha256: str | None = None) -> list[dict[str, Any]]:
    output = task_dir / "output"
    if not output.exists() or output.is_symlink():
        return []
    expected = _validate_builtin_video_manifest(video_manifest) if video_manifest is not None else None
    if expected is not None and (not isinstance(worker_task_id, str) or not worker_task_id):
        raise ValidationError("video worker task identity is missing")
    allowed_video_paths = {item["path"] for item in expected.values()} if expected else set()
    if expected and (not isinstance(result_manifest_sha256, str) or len(result_manifest_sha256) != 64
                     or any(char not in "0123456789abcdef" for char in result_manifest_sha256)):
        raise ValidationError("video result manifest digest is invalid")
    artifacts: list[dict[str, Any]] = []
    total = 0
    entry_count = 0
    for path, relative_path, is_file in _walk_output(output):
        entry_count += 1
        if entry_count > MAX_OUTPUT_ENTRIES:
            raise ValidationError("task output contains too many entries")
        if not is_file or path.name == ".keep":
            continue
        relative = relative_path.as_posix()
        if expected is not None and relative not in allowed_video_paths:
            continue
        media_type = ("application/json" if expected is not None and relative in allowed_video_paths
                      and path.suffix.lower() == ".json" else MEDIA_TYPES.get(path.suffix.lower()))
        if not media_type:
            continue
        descriptor = _open_relative_regular(output, relative_path)
        try:
            size = os.fstat(descriptor).st_size
            if size > MAX_ARTIFACT_BYTES:
                raise ValidationError("task artifact exceeds 100 MiB")
            digest = _hash_descriptor(descriptor, size)
        finally:
            os.close(descriptor)
        total += size
        if total > MAX_TASK_ARTIFACT_BYTES:
            raise ValidationError("task artifacts exceed 256 MiB")
        artifact_id = hashlib.sha256(f"{task_id}\n{relative}".encode("utf-8")).hexdigest()[:32]
        artifacts.append({
            "id": artifact_id,
            "name": path.name,
            "media_type": media_type,
            "size_bytes": size,
            "sha256": digest,
            "_relative_path": relative,
        })
        if len(artifacts) > MAX_ARTIFACT_COUNT:
            raise ValidationError("task output contains too many artifacts")
    if expected is not None:
        by_path = {item["_relative_path"]: item for item in artifacts}
        if set(by_path) != allowed_video_paths:
            raise ValidationError("video output does not match its validated result manifest")
        for path, descriptor in expected.items():
            actual = by_path[path]
            if actual["media_type"] != descriptor["media_type"] \
                    or actual["size_bytes"] != descriptor["size_bytes"] \
                    or actual["sha256"] != descriptor["sha256"]:
                raise ValidationError("video artifact does not match its validated result manifest")
        _validate_result_manifest_file(output, worker_task_id, expected, result_manifest_sha256)
    return sorted(artifacts, key=lambda item: item["_relative_path"])


def _validate_builtin_video_manifest(value: Any) -> dict[str, dict[str, Any]]:
    if not isinstance(value, list) or len(value) != len(BUILTIN_VIDEO_ARTIFACTS):
        raise ValidationError("video result manifest is invalid")
    expected: dict[str, dict[str, Any]] = {}
    roles: set[str] = set()
    paths: set[str] = set()
    for item in value:
        if not isinstance(item, dict) or set(item) != {
                "id", "role", "path", "media_type", "size_bytes", "sha256"}:
            raise ValidationError("video result manifest is invalid")
        role = item.get("role")
        spec = BUILTIN_VIDEO_ARTIFACTS.get(role) if isinstance(role, str) else None
        if spec is None or item.get("id") != spec[0] or item.get("path") != spec[1] \
                or item.get("media_type") != spec[2] \
                or type(item.get("size_bytes")) is not int or not 1 <= item["size_bytes"] <= MAX_ARTIFACT_BYTES \
                or not isinstance(item.get("sha256"), str) \
                or len(item["sha256"]) != 64 or any(char not in "0123456789abcdef" for char in item["sha256"]) \
                or role in roles or spec[1] in paths:
            raise ValidationError("video result manifest is invalid")
        roles.add(role)
        paths.add(spec[1])
        expected[spec[1]] = item
    if roles != set(BUILTIN_VIDEO_ARTIFACTS):
        raise ValidationError("video result manifest is incomplete")
    return expected


def _validate_result_manifest_file(output: Path, worker_task_id: str,
                                   expected: dict[str, dict[str, Any]],
                                   expected_sha256: str) -> None:
    descriptor = _open_relative_regular(output, PurePosixPath("result.json"))
    try:
        size = os.fstat(descriptor).st_size
        if size < 1 or size > 1024 * 1024:
            raise ValidationError("video result metadata exceeds its size limit")
        content = bytearray()
        while len(content) < size:
            chunk = os.read(descriptor, min(64 * 1024, size - len(content)))
            if not chunk:
                raise ValidationError("video result metadata changed while reading")
            content.extend(chunk)
    finally:
        os.close(descriptor)
    if not hmac.compare_digest(hashlib.sha256(content).hexdigest(), expected_sha256):
        raise ValidationError("video result manifest changed after validation")
    try:
        result = json.loads(bytes(content).decode("utf-8", "strict"),
                            object_pairs_hook=_unique_json_object)
    except (UnicodeError, json.JSONDecodeError, ValueError) as error:
        raise ValidationError("video result metadata is invalid") from error
    manifest = [expected[path] for path in ("explanation.mp4", "captions.srt", "thumbnail.png",
                                             "render.manifest.json", "qa-report.json")]
    if not isinstance(result, dict) or result.get("schema_version") != "1.0" \
            or result.get("task_id") != worker_task_id or result.get("status") != "completed" \
            or result.get("artifacts") != manifest:
        raise ValidationError("video result metadata does not match its validated artifact manifest")


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON property")
        result[key] = value
    return result


def artifact_path(task_dir: Path, task_id: str, artifact_id: str) -> tuple[Path, dict[str, Any]]:
    for item in collect_artifacts(task_dir, task_id):
        if item["id"] == artifact_id:
            path = task_dir / "output" / item["_relative_path"]
            return path, item
    raise FileNotFoundError(artifact_id)


def open_artifact(task_dir: Path, task_id: str, artifact_id: str):
    path, item = artifact_path(task_dir, task_id, artifact_id)
    output = task_dir / "output"
    descriptor = _open_relative_regular(output, path.relative_to(output))
    stream = os.fdopen(descriptor, "rb", closefd=True)
    actual = os.fstat(stream.fileno())
    if actual.st_size > MAX_ARTIFACT_BYTES or actual.st_size != item["size_bytes"] \
            or _hash_descriptor(stream.fileno(), actual.st_size) != item["sha256"]:
        stream.close()
        raise ValidationError("task artifact changed during download")
    stream.seek(0)
    return stream, item


def open_pinned_builtin_video_artifact(task_dir: Path, task_id: str,
                                       recorded: dict[str, Any]):
    """Open a built-in video artifact using its immutable run pin, never a rescan."""
    if not isinstance(recorded, dict) or set(recorded) != {
            "id", "name", "media_type", "size_bytes", "sha256"}:
        raise ValidationError("saved video artifact reference is invalid")
    name = recorded.get("name")
    media_type = BUILTIN_VIDEO_PUBLIC_FILES.get(name) if isinstance(name, str) else None
    expected_id = hashlib.sha256(f"{task_id}\n{name}".encode("utf-8")).hexdigest()[:32] \
        if media_type is not None else None
    if media_type is None or recorded.get("media_type") != media_type \
            or recorded.get("id") != expected_id \
            or type(recorded.get("size_bytes")) is not int \
            or not 1 <= recorded["size_bytes"] <= MAX_ARTIFACT_BYTES \
            or not isinstance(recorded.get("sha256"), str) \
            or len(recorded["sha256"]) != 64 \
            or any(char not in "0123456789abcdef" for char in recorded["sha256"]):
        raise ValidationError("saved video artifact reference is invalid")
    relative_path = PurePosixPath(name)
    descriptor = _open_relative_regular(task_dir / "output", relative_path)
    stream = os.fdopen(descriptor, "rb", closefd=True)
    actual = os.fstat(stream.fileno())
    if actual.st_size != recorded["size_bytes"] \
            or _hash_descriptor(stream.fileno(), actual.st_size) != recorded["sha256"]:
        stream.close()
        raise ValidationError("video artifact no longer matches its saved result")
    stream.seek(0)
    item = dict(recorded)
    item["_relative_path"] = name
    return stream, item


def public_artifacts(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [{key: value for key, value in item.items() if not key.startswith("_")} for item in items]


def _walk_output(root: Path):
    stack: list[tuple[Path, PurePosixPath]] = [(root, PurePosixPath())]
    while stack:
        directory, relative_directory = stack.pop()
        try:
            entries = os.scandir(directory)
        except OSError as error:
            raise ValidationError("task output cannot be safely enumerated") from error
        with entries:
            for entry in entries:
                relative = relative_directory / entry.name
                if len(relative.parts) > MAX_ARTIFACT_DEPTH:
                    raise ValidationError("task output nesting is too deep")
                try:
                    if entry.is_symlink():
                        raise ValidationError("task output contains a symbolic link")
                    if entry.is_dir(follow_symlinks=False):
                        stack.append((Path(entry.path), relative))
                        yield Path(entry.path), relative, False
                    elif entry.is_file(follow_symlinks=False):
                        yield Path(entry.path), relative, True
                    else:
                        raise ValidationError("task output contains a non-regular entry")
                except OSError as error:
                    raise ValidationError("task output changed during enumeration") from error


def _open_relative_regular(root: Path, relative: PurePosixPath | Path) -> int:
    parts = tuple(relative.parts)
    if not parts or len(parts) > MAX_ARTIFACT_DEPTH or any(part in {"", ".", ".."} for part in parts):
        raise ValidationError("task artifact path is invalid")
    if os.name == "nt":
        try:
            return _open_windows_regular(root, parts, MAX_ARTIFACT_DEPTH)
        except WindowsSafeOpenError as error:
            raise ValidationError("task artifact path is unavailable or unsafe") from error
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    directory_flag = getattr(os, "O_DIRECTORY", 0)
    directory_fd = -1
    try:
        directory_fd = os.open(root, os.O_RDONLY | directory_flag | nofollow)
        for part in parts[:-1]:
            next_fd = os.open(part, os.O_RDONLY | directory_flag | nofollow, dir_fd=directory_fd)
            os.close(directory_fd)
            directory_fd = next_fd
        descriptor = os.open(parts[-1], os.O_RDONLY | nofollow | getattr(os, "O_NONBLOCK", 0),
                             dir_fd=directory_fd)
    except (OSError, ValueError) as error:
        raise ValidationError("task artifact path changed or escapes its directory") from error
    finally:
        if directory_fd >= 0:
            os.close(directory_fd)
    if not stat.S_ISREG(os.fstat(descriptor).st_mode):
        os.close(descriptor)
        raise ValidationError("task artifact is not a regular file")
    return descriptor


def _hash_descriptor(descriptor: int, expected_size: int) -> str:
    os.lseek(descriptor, 0, os.SEEK_SET)
    digest = hashlib.sha256()
    remaining = expected_size
    while remaining:
        chunk = os.read(descriptor, min(1024 * 1024, remaining))
        if not chunk:
            raise ValidationError("task artifact changed while being read")
        digest.update(chunk)
        remaining -= len(chunk)
    if os.read(descriptor, 1) or os.fstat(descriptor).st_size != expected_size:
        raise ValidationError("task artifact changed while being read")
    os.lseek(descriptor, 0, os.SEEK_SET)
    return digest.hexdigest()
