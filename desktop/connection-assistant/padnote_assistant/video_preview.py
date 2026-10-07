from __future__ import annotations

import base64
import binascii
import copy
import hashlib
import hmac
import io
import json
import os
import re
import stat
import zipfile
from pathlib import Path
from typing import Any

from .security import ValidationError, canonical_json
from .state import ConflictError
from .video_worker import VideoWorkerError, _validate_review_projection
from .windows_files import WindowsSafeOpenError, open_regular_file as _open_windows_regular


MAX_BUNDLE_BYTES = 8 * 1024 * 1024
MAX_REQUEST_BYTES = 1024 * 1024
MAX_STATE_BYTES = 256 * 1024
MAX_PREVIEW_BYTES = 8 * 1024 * 1024
MAX_SNAPSHOT_BYTES = 64 * 1024
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
_WORKER_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
_SHA256 = re.compile(r"^[a-f0-9]{64}$")
_SCENE_ID = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
_TASK_ROOT_KEYS = {"schema_version", "task_id", "request_sha256", "status", "phase", "updated_at",
                   "event_cursor", "revision", "lesson_ir_sha256", "review_sha256", "approval",
                   "attempt", "cancellation", "error"}


def binding_from_submission(payload: dict[str, Any]) -> dict[str, str] | None:
    """Extracts a video worker binding only from the original submitted ZIP bytes."""
    if not isinstance(payload, dict):
        raise ValidationError("Video submission is invalid")
    encoded = payload.get("bundle_base64")
    if encoded is None:
        return None
    if not isinstance(encoded, str) or len(encoded) > ((MAX_BUNDLE_BYTES + 2) // 3) * 4 \
            or not isinstance(payload.get("bundle_sha256"), str):
        raise ValidationError("Video task bundle is invalid")
    try:
        raw = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ValidationError("Video task bundle is invalid") from error
    if len(raw) > MAX_BUNDLE_BYTES:
        raise ValidationError("Video task bundle exceeds its size limit")
    expected_bundle_hash = payload["bundle_sha256"]
    if not isinstance(expected_bundle_hash, str) or not re.fullmatch(r"^[a-fA-F0-9]{64}$", expected_bundle_hash) \
            or not hmac.compare_digest(hashlib.sha256(raw).hexdigest(), expected_bundle_hash.lower()):
        raise ValidationError("Video task bundle digest is invalid")
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            entries = archive.infolist()
            if len(entries) > 8:
                raise ValidationError("Video task bundle contains too many entries")
            request_entries = [item for item in entries if item.filename == "request.json"]
            if len(request_entries) != 1:
                raise ValidationError("Video task bundle request is missing or duplicated")
            item = request_entries[0]
            mode = (item.external_attr >> 16) & 0xFFFF
            file_type = stat.S_IFMT(mode)
            if item.is_dir() or file_type not in {0, stat.S_IFREG} or item.file_size > MAX_REQUEST_BYTES:
                raise ValidationError("Video task bundle request is not a bounded regular file")
            with archive.open(item, "r") as source:
                request_bytes = source.read(MAX_REQUEST_BYTES + 1)
                if len(request_bytes) > MAX_REQUEST_BYTES or len(request_bytes) != item.file_size:
                    raise ValidationError("Video task bundle request exceeds its size limit")
    except (OSError, zipfile.BadZipFile, RuntimeError, EOFError, NotImplementedError) as error:
        raise ValidationError("Video task bundle is invalid") from error
    request = _parse_object(request_bytes, "Video task bundle request is invalid")
    if request.get("task_type") != "video.explain.v1":
        return None
    worker_task_id = request.get("task_id")
    if not isinstance(worker_task_id, str) or not _WORKER_ID.fullmatch(worker_task_id):
        raise ValidationError("Video task bundle identity is invalid")
    return {"worker_task_id": worker_task_id,
            "request_sha256": hashlib.sha256(request_bytes).hexdigest()}


def verify_request_binding(task_root: Path, binding: dict[str, Any] | None) -> None:
    if not _valid_binding(binding):
        raise ValidationError("Video task source binding is unavailable")
    raw = _read_regular(task_root, "request.json", MAX_REQUEST_BYTES)
    _assert_request_bytes(raw, binding)


def capture_review_snapshot(task_root: Path, binding: dict[str, Any] | None,
                            projection: dict[str, Any]) -> dict[str, Any]:
    if not _valid_binding(binding):
        raise ValidationError("Video task source binding is unavailable")
    _validate_projection(projection)
    verify_request_binding(task_root, binding)
    state_raw = _read_regular(task_root, "work/task-state.json", MAX_STATE_BYTES)
    state = _parse_object(state_raw, "Video task state is invalid")
    _assert_projection_state(state, binding, projection)
    verify_request_binding(task_root, binding)
    state_after = _read_regular(task_root, "work/task-state.json", MAX_STATE_BYTES)
    if not hmac.compare_digest(hashlib.sha256(state_raw).digest(), hashlib.sha256(state_after).digest()):
        raise ConflictError("Video task state changed while the review was being saved")
    _assert_projection_state(_parse_object(state_after, "Video task state is invalid"), binding, projection)
    compact = {
        "task_id": projection["task_id"],
        "status": projection["status"],
        "event_cursor": projection["event_cursor"],
        "revision": projection["revision"],
        "review_sha256": projection["review_sha256"],
        "lesson_ir_sha256": projection["lesson_ir_sha256"],
        "scenes": [{"id": scene["id"], "preview": copy.deepcopy(scene["preview"])}
                   for scene in projection["scenes"]],
    }
    if len(canonical_json(compact)) > MAX_SNAPSHOT_BYTES:
        raise ValidationError("Video review metadata exceeds its size limit")
    return {"projection": compact,
            "state_sha256": hashlib.sha256(state_raw).hexdigest()}


def preview_id(projection: dict[str, Any], scene: dict[str, Any]) -> str:
    _validate_projection_binding_fields(projection)
    scene_id = scene.get("id") if isinstance(scene, dict) else None
    if not isinstance(scene_id, str) or not _SCENE_ID.fullmatch(scene_id):
        raise ValidationError("Video preview identity is invalid")
    material = {
        "revision": projection["revision"],
        "review_sha256": projection["review_sha256"],
        "lesson_ir_sha256": projection["lesson_ir_sha256"],
        "scene_id": scene_id,
    }
    return hashlib.sha256(canonical_json(material)).hexdigest()


def public_review(server_task_id: str, projection: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(server_task_id, str) or not _WORKER_ID.fullmatch(server_task_id):
        raise ValidationError("Video task identity is invalid")
    _validate_projection(projection)
    result = copy.deepcopy(projection)
    result["object"] = "padnote.video.review"
    result["worker_task_id"] = result["task_id"]
    result["task_id"] = server_task_id
    for scene in result["scenes"]:
        scene["preview"].pop("path", None)
        scene["preview"]["id"] = preview_id(projection, scene)
    return result


def open_preview(task_root: Path, binding: dict[str, Any] | None,
                 snapshot: dict[str, Any], requested_preview_id: str) -> tuple[io.BytesIO, dict[str, Any]]:
    if not _valid_binding(binding):
        raise ValidationError("Video task source binding is unavailable")
    if not isinstance(snapshot, dict) or set(snapshot) != {"projection", "state_sha256"} \
            or not isinstance(snapshot.get("state_sha256"), str) \
            or not _SHA256.fullmatch(snapshot["state_sha256"]):
        raise ValidationError("Video review snapshot is invalid")
    projection = snapshot.get("projection")
    _validate_preview_snapshot_projection(projection)
    verify_request_binding(task_root, binding)
    before = _read_regular(task_root, "work/task-state.json", MAX_STATE_BYTES)
    if not hmac.compare_digest(hashlib.sha256(before).hexdigest(), snapshot["state_sha256"]):
        raise ConflictError("Video task review has changed; refresh the review")
    _assert_projection_state(_parse_object(before, "Video task state is invalid"), binding, projection)

    if not isinstance(requested_preview_id, str) or not _SHA256.fullmatch(requested_preview_id):
        raise ValidationError("Video preview is unavailable")
    selected: dict[str, Any] | None = None
    for scene in projection["scenes"]:
        expected_id = preview_id(projection, scene)
        if hmac.compare_digest(expected_id, requested_preview_id):
            selected = scene
    if selected is None:
        raise ConflictError("Video preview is unavailable")
    scene_id = selected["id"]
    preview = selected["preview"]
    expected_path = f"storyboard-{scene_id}.png"
    if preview.get("path") != expected_path or preview.get("media_type") != "image/png" \
            or type(preview.get("size_bytes")) is not int or not 1 <= preview["size_bytes"] <= MAX_PREVIEW_BYTES \
            or not isinstance(preview.get("sha256"), str) or not _SHA256.fullmatch(preview["sha256"]):
        raise ValidationError("Video preview metadata is invalid")
    data = _read_regular(task_root, f"output/{expected_path}", MAX_PREVIEW_BYTES,
                         declared_size=preview["size_bytes"])
    width, height = _png_dimensions(data)
    if hashlib.sha256(data).hexdigest() != preview["sha256"] \
            or width != preview.get("width") or height != preview.get("height"):
        raise ConflictError("Video preview bytes no longer match the saved review")

    verify_request_binding(task_root, binding)
    after = _read_regular(task_root, "work/task-state.json", MAX_STATE_BYTES)
    if not hmac.compare_digest(hashlib.sha256(after).hexdigest(), snapshot["state_sha256"]):
        raise ConflictError("Video task review changed while loading the preview")
    _assert_projection_state(_parse_object(after, "Video task state is invalid"), binding, projection)
    item = {"name": expected_path, "media_type": "image/png", "size_bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest()}
    return io.BytesIO(data), item


def _valid_binding(binding: Any) -> bool:
    return isinstance(binding, dict) and set(binding) == {"worker_task_id", "request_sha256"} \
        and isinstance(binding.get("worker_task_id"), str) and _WORKER_ID.fullmatch(binding["worker_task_id"]) \
        and isinstance(binding.get("request_sha256"), str) and _SHA256.fullmatch(binding["request_sha256"])


def _assert_request_bytes(raw: bytes, binding: dict[str, Any]) -> None:
    if not hmac.compare_digest(hashlib.sha256(raw).hexdigest(), binding["request_sha256"]):
        raise ConflictError("Video task source has changed")
    request = _parse_object(raw, "Video task request is invalid")
    if request.get("task_type") != "video.explain.v1" or request.get("task_id") != binding["worker_task_id"]:
        raise ConflictError("Video task source identity has changed")


def _assert_projection_state(state: dict[str, Any], binding: dict[str, Any],
                             projection: dict[str, Any]) -> None:
    _validate_state_shape(state)
    if state.get("task_id") != binding["worker_task_id"] \
            or state.get("request_sha256") != binding["request_sha256"] \
            or state.get("task_id") != projection.get("task_id") \
            or state.get("status") != projection.get("status") \
            or state.get("event_cursor") != projection.get("event_cursor") \
            or state.get("revision") != projection.get("revision") \
            or state.get("review_sha256") != projection.get("review_sha256") \
            or state.get("lesson_ir_sha256") != projection.get("lesson_ir_sha256"):
        raise ConflictError("Video review no longer matches the persisted task state")


def _validate_projection(projection: Any) -> None:
    if not isinstance(projection, dict):
        raise ValidationError("Video review projection is invalid")
    try:
        _validate_review_projection(projection, projection.get("task_id"))
    except VideoWorkerError as error:
        raise ValidationError("Video review projection is invalid") from error


def _validate_projection_binding_fields(projection: Any) -> None:
    if not isinstance(projection, dict) \
            or not isinstance(projection.get("task_id"), str) \
            or not _WORKER_ID.fullmatch(projection["task_id"]) \
            or type(projection.get("revision")) is not int or not 1 <= projection["revision"] <= 2**53 - 1 \
            or not isinstance(projection.get("review_sha256"), str) or not _SHA256.fullmatch(projection["review_sha256"]) \
            or not isinstance(projection.get("lesson_ir_sha256"), str) or not _SHA256.fullmatch(projection["lesson_ir_sha256"]):
        raise ValidationError("Video review binding is invalid")


def _validate_preview_snapshot_projection(projection: Any) -> None:
    _validate_projection_binding_fields(projection)
    if set(projection) != {"task_id", "status", "event_cursor", "revision", "review_sha256",
                           "lesson_ir_sha256", "scenes"} \
            or not isinstance(projection.get("status"), str) \
            or projection["status"] in {"initialized", "running", "cancelling"} \
            or type(projection.get("event_cursor")) is not int or projection["event_cursor"] < 1 \
            or not isinstance(projection.get("scenes"), list) or len(projection["scenes"]) > 60:
        raise ValidationError("Video review snapshot is invalid")
    seen: set[str] = set()
    for scene in projection["scenes"]:
        if not isinstance(scene, dict) or set(scene) != {"id", "preview"}:
            raise ValidationError("Video review snapshot is invalid")
        scene_id = scene.get("id")
        preview = scene.get("preview")
        if not isinstance(scene_id, str) or not _SCENE_ID.fullmatch(scene_id) or scene_id in seen \
                or not isinstance(preview, dict) or set(preview) != {
                    "path", "media_type", "size_bytes", "sha256", "width", "height"} \
                or preview.get("path") != f"storyboard-{scene_id}.png" \
                or preview.get("media_type") != "image/png" \
                or type(preview.get("size_bytes")) is not int or not 1 <= preview["size_bytes"] <= MAX_PREVIEW_BYTES \
                or not isinstance(preview.get("sha256"), str) or not _SHA256.fullmatch(preview["sha256"]) \
                or type(preview.get("width")) is not int or type(preview.get("height")) is not int \
                or not 1 <= preview["width"] <= 4096 or not 1 <= preview["height"] <= 4096 \
                or preview["width"] * preview["height"] > 8388608:
            raise ValidationError("Video review snapshot is invalid")
        seen.add(scene_id)


def _validate_state_shape(state: dict[str, Any]) -> None:
    if set(state) - _TASK_ROOT_KEYS or state.get("schema_version") != "1.0" \
            or not isinstance(state.get("task_id"), str) or not _WORKER_ID.fullmatch(state["task_id"]) \
            or not isinstance(state.get("request_sha256"), str) or not _SHA256.fullmatch(state["request_sha256"]) \
            or not isinstance(state.get("status"), str) \
            or type(state.get("event_cursor")) is not int or state["event_cursor"] < 1 \
            or type(state.get("revision")) is not int or not 1 <= state["revision"] <= 2**53 - 1 \
            or not isinstance(state.get("review_sha256"), str) or not _SHA256.fullmatch(state["review_sha256"]) \
            or not isinstance(state.get("lesson_ir_sha256"), str) or not _SHA256.fullmatch(state["lesson_ir_sha256"]):
        raise ValidationError("Video task state is invalid")


def _read_regular(task_root: Path, relative_path: str, maximum: int,
                  declared_size: int | None = None) -> bytes:
    if not isinstance(task_root, Path) or not task_root.is_absolute():
        raise ValidationError("Video task directory is unavailable")
    parts = relative_path.split("/")
    if not parts or any(part in {"", ".", ".."} or "\\" in part or "\x00" in part for part in parts):
        raise ValidationError("Video file path is invalid")
    try:
        if os.name == "nt":
            descriptor = _open_windows_regular(task_root, tuple(parts), 8)
        else:
            root_info = os.lstat(task_root)
            if not stat.S_ISDIR(root_info.st_mode) or stat.S_ISLNK(root_info.st_mode):
                raise ValidationError("Video task directory is unavailable")
            current = task_root
            for part in parts[:-1]:
                current = current / part
                info = os.lstat(current)
                if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode):
                    raise ValidationError("Video file path is unavailable")
            path = current / parts[-1]
            if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_NONBLOCK"):
                raise ValidationError("Platform cannot safely read video files")
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except ValidationError:
        raise
    except WindowsSafeOpenError as error:
        raise ValidationError("Video file is unavailable or unsafe") from error
    except OSError as error:
        raise ValidationError("Video file is unavailable") from error
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode) or before.st_size < 0 or before.st_size > maximum \
                or (declared_size is not None and before.st_size != declared_size):
            raise ValidationError("Video file is not a bounded regular file")
        chunks = bytearray()
        while len(chunks) <= maximum:
            try:
                chunk = os.read(descriptor, min(65536, maximum + 1 - len(chunks)))
            except OSError as error:
                raise ValidationError("Video file could not be safely read") from error
            if not chunk:
                break
            chunks.extend(chunk)
        after = os.fstat(descriptor)
        if len(chunks) > maximum or len(chunks) != after.st_size \
                or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != \
                (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
            raise ConflictError("Video file changed while it was being read")
        return bytes(chunks)
    finally:
        os.close(descriptor)


def _parse_object(raw: bytes, message: str) -> dict[str, Any]:
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_pairs,
                           parse_constant=_reject_constant)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        raise ValidationError(message) from error
    if not isinstance(value, dict):
        raise ValidationError(message)
    return value


def _png_dimensions(data: bytes) -> tuple[int, int]:
    if len(data) < 33 or data[:8] != PNG_SIGNATURE or int.from_bytes(data[8:12], "big") != 13 \
            or data[12:16] != b"IHDR":
        raise ValidationError("Video preview PNG header is invalid")
    width = int.from_bytes(data[16:20], "big")
    height = int.from_bytes(data[20:24], "big")
    if not 1 <= width <= 4096 or not 1 <= height <= 4096 or width * height > 8388608:
        raise ValidationError("Video preview PNG dimensions are invalid")
    return width, height


def _unique_pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in items:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ValueError("invalid JSON constant")
