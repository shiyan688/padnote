from __future__ import annotations

import json
import os
import re
import stat
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Callable

from .security import (
    ValidationError,
    canonical_json,
    digest_token,
    payload_digest,
    random_token,
    token_matches,
    validate_identifier,
    validate_text,
    validate_upstream_url,
)


class StateError(RuntimeError):
    pass


class StateOwnershipError(StateError):
    """The canonical state directory is already owned by another live store."""


_owned_state_dirs: set[str] = set()
_owned_state_dirs_lock = threading.Lock()


class PairingError(StateError):
    def __init__(self, code: str, message: str, status: int):
        super().__init__(message)
        self.code = code
        self.status = status


class AuthorizationError(StateError):
    pass


class ConflictError(StateError):
    pass


class StateStore:
    VERSION = 1
    PAIR_TTL_SECONDS = 300
    MAX_PENDING = 32

    def __init__(self, state_dir: Path, *, clock: Callable[[], float] = time.time):
        requested_dir = Path(state_dir).expanduser()
        self._clock = clock
        self._lock = threading.RLock()
        self._owner_fd: int | None = None
        self._windows_owner_lock = None
        self._owner_key: str | None = None
        self._closed = False
        requested_dir.mkdir(parents=True, exist_ok=True)
        self.state_dir = requested_dir.resolve(strict=True)
        self.path = self.state_dir / "state.json"
        self.tasks_dir = self.state_dir / "tasks"
        try:
            os.chmod(self.state_dir, 0o700)
        except OSError:
            pass
        self._acquire_ownership()
        try:
            self.tasks_dir.mkdir(parents=True, exist_ok=True)
            self._data = self._load_or_create()
        except BaseException:
            self.close()
            raise

    def _acquire_ownership(self) -> None:
        owner_key = str(self.state_dir)
        with _owned_state_dirs_lock:
            if owner_key in _owned_state_dirs:
                raise StateOwnershipError(
                    "another connection assistant is already using this state directory")
            _owned_state_dirs.add(owner_key)
        try:
            lock_path = self.state_dir / ".owner.lock"
            if os.name == "nt":
                from .windows_state_lock import WindowsFileLock
                owner_lock = WindowsFileLock.acquire(lock_path)
                self._windows_owner_lock = owner_lock
            else:
                import fcntl
                descriptor = os.open(
                    lock_path,
                    os.O_RDWR | os.O_CREAT | getattr(os, "O_CLOEXEC", 0)
                    | getattr(os, "O_NOFOLLOW", 0),
                    0o600,
                )
                try:
                    info = os.fstat(descriptor)
                    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 \
                            or info.st_uid != os.getuid() or info.st_mode & 0o077:
                        raise StateOwnershipError("state ownership file is not a private regular file")
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BaseException as error:
                    os.close(descriptor)
                    if isinstance(error, StateOwnershipError):
                        raise
                    raise StateOwnershipError(
                        "another connection assistant is already using this state directory") from error
                self._owner_fd = descriptor
            self._owner_key = owner_key
        except (BlockingIOError, OSError) as error:
            with _owned_state_dirs_lock:
                _owned_state_dirs.discard(owner_key)
            raise StateOwnershipError(
                "another connection assistant is already using this state directory") from error
        except BaseException:
            with _owned_state_dirs_lock:
                _owned_state_dirs.discard(owner_key)
            raise

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            descriptor, self._owner_fd = self._owner_fd, None
            owner_key, self._owner_key = self._owner_key, None
        try:
            if self._windows_owner_lock is not None:
                lock, self._windows_owner_lock = self._windows_owner_lock, None
                lock.release()
            if descriptor is not None:
                try:
                    import fcntl
                    fcntl.flock(descriptor, fcntl.LOCK_UN)
                finally:
                    os.close(descriptor)
        finally:
            if owner_key is not None:
                with _owned_state_dirs_lock:
                    _owned_state_dirs.discard(owner_key)

    def __enter__(self) -> StateStore:
        return self

    def __exit__(self, _type, _value, _traceback) -> None:
        self.close()

    def _assert_open(self) -> None:
        if self._closed:
            raise StateError("connection assistant state is closed")

    def _empty(self) -> dict[str, Any]:
        return {
            "version": self.VERSION,
            "bridge_id": str(uuid.uuid4()),
            "instances": {},
            "pair_codes": {},
            "pair_requests": {},
            "connections": {},
            "runs": {},
            "idempotency": {},
            "video_operations": {},
            "video_operations_version": 2,
        }

    def _load_or_create(self) -> dict[str, Any]:
        if not self.path.exists():
            data = self._empty()
            self._save(data)
            return data
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise StateError("connection assistant state is unreadable") from error
        if not isinstance(data, dict):
            raise StateError("connection assistant state is malformed")
        if data.get("version") != self.VERSION or not isinstance(data.get("bridge_id"), str):
            raise StateError("unsupported connection assistant state version")
        for key in ("instances", "pair_codes", "pair_requests", "connections", "runs", "idempotency"):
            if not isinstance(data.get(key), dict):
                raise StateError("connection assistant state is malformed")
        marker_present = "video_operations_version" in data
        table_present = "video_operations" in data
        version = data.get("video_operations_version") if marker_present else 1
        if type(version) is not int or version not in {1, 2} or (marker_present and not table_present):
            raise StateError("connection assistant video operation ledger version is unsupported")
        if not table_present:
            # Only pre-ledger state may omit both the table and its marker.
            data["video_operations"] = {}
            version = 1
        try:
            from .video_operations import validate_video_operations
            validate_video_operations(data, version=version)
        except (TypeError, ValueError, KeyError, AttributeError) as error:
            raise StateError("connection assistant video operation ledger is malformed") from error
        if version == 1:
            # Validate the complete v1 ledger before producing a separate v2
            # candidate. The atomic replace means a failed save leaves v1 intact.
            migrated = self._copy_state(data)
            for record in migrated["video_operations"].values():
                record["cancel_request"] = None
            migrated["video_operations_version"] = 2
            try:
                validate_video_operations(migrated, version=2)
            except (TypeError, ValueError, KeyError, AttributeError) as error:
                raise StateError("connection assistant video operation migration failed") from error
            self._save(migrated)
            return migrated
        return data

    def _save(self, data: dict[str, Any] | None = None) -> None:
        with self._lock:
            self._assert_open()
            value = data if data is not None else self._data
            encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2).encode("utf-8") + b"\n"
            temporary = self.path.with_suffix(f".tmp-{uuid.uuid4().hex}")
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                with os.fdopen(descriptor, "wb") as output:
                    output.write(encoded)
                    output.flush()
                    os.fsync(output.fileno())
                os.replace(temporary, self.path)
                try:
                    directory = os.open(self.state_dir, os.O_RDONLY)
                    try:
                        os.fsync(directory)
                    finally:
                        os.close(directory)
                except OSError:
                    pass
            finally:
                try:
                    temporary.unlink()
                except FileNotFoundError:
                    pass

    @staticmethod
    def _copy_state(data: dict[str, Any]) -> dict[str, Any]:
        return json.loads(json.dumps(data))

    def _commit(self, mutation):
        """Persist a copy before publishing it in memory.

        A disk failure must never leave a token, approval, or idempotency mapping
        active only in process memory.
        """
        self._assert_open()
        candidate = self._copy_state(self._data)
        result = mutation(candidate)
        self._save(candidate)
        self._data = candidate
        return result

    @property
    def bridge_id(self) -> str:
        with self._lock:
            self._assert_open()
            return self._data["bridge_id"]

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            self._assert_open()
            # JSON roundtrip prevents callers mutating internal state.
            return self._copy_state(self._data)

    def add_instance(self, kind: str, name: str, base_url: str, *, config_ref: str | None = None,
                     config_fingerprint: str | None = None,
                     credential_fingerprint: str | None = None,
                     provider_model: str | None = None,
                     provider_region: str | None = None,
                     response_mode: str | None = None,
                     tts_model: str | None = None,
                     tts_voice: str | None = None) -> dict[str, Any]:
        kind = validate_text(kind, "kind", 20).lower()
        if kind not in {"hermes", "openclaw", "builtin_video"}:
            raise ValidationError("unsupported Agent kind")
        name = validate_text(name, "name", 120)
        base_url = validate_upstream_url(base_url)
        instance_id = str(uuid.uuid4())
        now = int(self._clock())
        instance = {
            "instance_id": instance_id,
            "kind": kind,
            "name": name,
            "base_url": base_url,
            "created_at": now,
            "updated_at": now,
            "health": "not_checked" if kind in {"hermes", "builtin_video"} else "unsupported",
            "detail": (
                "Enter the Hermes key for this process, then run the capability check."
                if kind == "hermes" else
                "Configure the built-in video provider and local engine."
                if kind == "builtin_video" else
                "OpenClaw discovery is available, but task execution is not connected."
            ),
            "features": {},
            "executable": False,
        }
        if config_ref:
            instance["config_ref"] = config_ref
            instance["config_fingerprint"] = config_fingerprint or ""
        if credential_fingerprint:
            instance["credential_fingerprint"] = credential_fingerprint
        if kind == "builtin_video":
            instance["provider_model"] = validate_text(
                provider_model or "qwen-plus", "provider_model", 120)
            instance["provider_region"] = validate_text(
                provider_region or "international", "provider_region", 40)
            selected_mode = response_mode or "json_object"
            if selected_mode not in {"json_object", "json_schema"}:
                raise ValidationError("unsupported provider response mode")
            instance["response_mode"] = selected_mode
            instance["tts_model"] = validate_text(
                tts_model or "qwen3-tts-instruct-flash", "tts_model", 120)
            instance["tts_voice"] = validate_text(tts_voice or "Maia", "tts_voice", 120)
        with self._lock:
            self._assert_open()
            self._commit(lambda data: data["instances"].__setitem__(instance_id, instance))
        return dict(instance)

    def update_instance_check(self, instance_id: str, *, health: str, detail: str,
                              features: dict[str, Any], executable: bool) -> dict[str, Any]:
        with self._lock:
            self._assert_open()
            instance = self._data["instances"].get(instance_id)
            if not instance:
                raise KeyError(instance_id)
            update = {
                "health": health,
                "detail": validate_text(detail, "detail", 500, allow_empty=True),
                "features": dict(features),
                "executable": bool(executable) and instance["kind"] in {"hermes", "builtin_video"},
                "updated_at": int(self._clock()),
            }
            def mutation(data):
                data["instances"][instance_id].update(update)
                return dict(data["instances"][instance_id])
            return self._commit(mutation)

    def get_instance(self, instance_id: str) -> dict[str, Any]:
        with self._lock:
            self._assert_open()
            instance = self._data["instances"].get(instance_id)
            if not instance:
                raise KeyError(instance_id)
            return dict(instance)

    def attach_instance_config(self, instance_id: str, path: str, fingerprint: str) -> dict[str, Any]:
        with self._lock:
            self._assert_open()
            if instance_id not in self._data["instances"]:
                raise KeyError(instance_id)
            def mutation(data):
                target = data["instances"][instance_id]
                target["config_ref"] = path
                target["config_fingerprint"] = fingerprint
                target["updated_at"] = int(self._clock())
                return dict(target)
            return self._commit(mutation)

    def set_credential_fingerprint(self, instance_id: str, fingerprint: str) -> dict[str, Any]:
        with self._lock:
            self._assert_open()
            if instance_id not in self._data["instances"]:
                raise KeyError(instance_id)
            def mutation(data):
                target = data["instances"][instance_id]
                target["credential_fingerprint"] = validate_text(
                    fingerprint, "credential_fingerprint", 128)
                target["updated_at"] = int(self._clock())
                return dict(target)
            return self._commit(mutation)

    def retire_instance(self, instance_id: str, detail: str) -> dict[str, Any]:
        with self._lock:
            self._assert_open()
            if instance_id not in self._data["instances"]:
                raise KeyError(instance_id)
            def mutation(data):
                target = data["instances"][instance_id]
                target.update({
                    "health": "identity_changed",
                    "detail": validate_text(detail, "detail", 500),
                    "features": {},
                    "executable": False,
                    "retired_at": int(self._clock()),
                    "updated_at": int(self._clock()),
                })
                return dict(target)
            return self._commit(mutation)

    def has_instance_history(self, instance_id: str) -> bool:
        with self._lock:
            self._assert_open()
            return any(item.get("instance_id") == instance_id
                       for item in self._data["connections"].values()) \
                or any(item.get("instance_id") == instance_id
                       for item in self._data["runs"].values())

    def list_instances(self) -> list[dict[str, Any]]:
        with self._lock:
            self._assert_open()
            return [dict(item) for item in self._data["instances"].values()]

    def create_pair_code(self, instance_id: str, public_url: str,
                         cert_sha256: str | None = None) -> tuple[str, dict[str, Any]]:
        with self._lock:
            self._assert_open()
            instance = self._data["instances"].get(instance_id)
            if not instance:
                raise KeyError(instance_id)
            if instance["kind"] not in {"hermes", "builtin_video"} or not instance.get("executable"):
                raise StateError("Only a checked, executable provider instance can be paired")
            code = random_token(32)
            expires_at = int(self._clock()) + self.PAIR_TTL_SECONDS
            item = {
                "instance_id": instance_id,
                "public_url": public_url,
                "expires_at": expires_at,
            }
            self._commit(lambda data: data["pair_codes"].__setitem__(digest_token(code), item))
            payload = {
                "type": "padnote-pair",
                "version": 1,
                "url": public_url,
                "bridge_id": self.bridge_id,
                "code": code,
            }
            if cert_sha256 is not None:
                # Same-network pairing: the tablet trusts exactly this certificate.
                payload["cert_sha256"] = cert_sha256
            return code, payload

    def _expire_pairing(self, data: dict[str, Any]) -> None:
        now = int(self._clock())
        data["pair_codes"] = {
            key: item for key, item in data["pair_codes"].items()
            if int(item.get("expires_at", 0)) > now
        }
        for request in data["pair_requests"].values():
            if request.get("status") in {"pending", "approved"} and int(request.get("expires_at", 0)) <= now:
                request["status"] = "expired"
                request.pop("poll_digest", None)

    def request_pair(self, code: str, device_id: str, device_name: str) -> dict[str, Any]:
        device_id = validate_identifier(device_id, "device_id")
        device_name = validate_text(device_name, "device_name", 120)
        with self._lock:
            self._assert_open()
            now = int(self._clock())
            pending = sum(1 for item in self._data["pair_requests"].values()
                          if item.get("status") == "pending" and int(item.get("expires_at", 0)) > now)
            if pending >= self.MAX_PENDING:
                raise PairingError("pair_unavailable", "Pairing is temporarily unavailable", 429)
            pair = self._data["pair_codes"].get(digest_token(code))
            if pair and int(pair.get("expires_at", 0)) <= now:
                pair = None
            if not pair:
                raise PairingError("pair_invalid", "Pairing code is invalid or expired", 403)
            request_id = str(uuid.uuid4())
            poll_token = random_token(32)
            request = {
                "request_id": request_id,
                "instance_id": pair["instance_id"],
                "device_id": device_id,
                "device_name": device_name,
                "poll_digest": digest_token(poll_token),
                "status": "pending",
                "created_at": int(self._clock()),
                "expires_at": int(pair["expires_at"]),
            }
            def mutation(data):
                self._expire_pairing(data)
                consumed = data["pair_codes"].pop(digest_token(code), None)
                if not consumed:
                    raise PairingError("pair_invalid", "Pairing code is invalid or expired", 403)
                data["pair_requests"][request_id] = request
            self._commit(mutation)
            return {
                "request_id": request_id,
                "poll_token": poll_token,
                "expires_at": request["expires_at"],
                "status": "pending",
            }

    def decide_pair(self, request_id: str, approved: bool) -> None:
        with self._lock:
            self._assert_open()
            request = self._data["pair_requests"].get(request_id)
            if not request or request.get("status") != "pending" or int(request.get("expires_at", 0)) <= int(self._clock()):
                raise StateError("Pair request is no longer pending")
            def mutation(data):
                target = data["pair_requests"][request_id]
                target["status"] = "approved" if approved else "denied"
                target["decided_at"] = int(self._clock())
            self._commit(mutation)

    def claim_pair(self, request_id: str, poll_token: str) -> tuple[int, dict[str, Any]]:
        with self._lock:
            self._assert_open()
            request = self._data["pair_requests"].get(request_id)
            if not request or not token_matches(poll_token, request.get("poll_digest", "")):
                raise PairingError("pair_invalid", "Pair request is invalid or expired", 403)
            status = request.get("status")
            if int(request.get("expires_at", 0)) <= int(self._clock()):
                status = "expired"
            if status == "pending":
                return 202, {"request_id": request_id, "status": "pending", "expires_at": request["expires_at"]}
            if status == "denied":
                self._commit(lambda data: data["pair_requests"][request_id].pop("poll_digest", None))
                raise PairingError("pair_denied", "Pair request was denied", 403)
            if status in {"expired", "claimed"}:
                raise PairingError("pair_gone", "Pair request expired or was already claimed", 410)
            if status != "approved":
                raise PairingError("pair_invalid", "Pair request cannot be claimed", 403)
            instance = self._data["instances"].get(request["instance_id"])
            if not instance or instance.get("kind") not in {"hermes", "builtin_video"} \
                    or not instance.get("executable"):
                raise PairingError("instance_unavailable", "Selected Agent is no longer available", 409)
            token = random_token(32)
            connection_id = str(uuid.uuid4())
            connection = {
                "connection_id": connection_id,
                "device_id": request["device_id"],
                "device_name": request["device_name"],
                "instance_id": instance["instance_id"],
                "token_digest": digest_token(token),
                "created_at": int(self._clock()),
                "revoked_at": None,
            }
            def mutation(data):
                data["connections"][connection_id] = connection
                target = data["pair_requests"][request_id]
                target["status"] = "claimed"
                target["claimed_at"] = int(self._clock())
                target.pop("poll_digest", None)
            self._commit(mutation)
            return 200, {
                "bridge_id": self.bridge_id,
                "device_id": request["device_id"],
                "connections": [{
                    "instance_id": instance["instance_id"],
                    "kind": instance["kind"],
                    "name": instance["name"],
                    "token": token,
                }],
            }

    def authenticate(self, instance_id: str, token: str) -> dict[str, Any]:
        if not token:
            raise AuthorizationError("Missing device credential")
        with self._lock:
            self._assert_open()
            for connection in self._data["connections"].values():
                if connection.get("instance_id") != instance_id or connection.get("revoked_at") is not None:
                    continue
                if token_matches(token, connection.get("token_digest", "")):
                    return dict(connection)
        raise AuthorizationError("Device credential is invalid or revoked")

    def revoke_connection(self, connection_id: str) -> None:
        with self._lock:
            self._assert_open()
            connection = self._data["connections"].get(connection_id)
            if not connection:
                raise KeyError(connection_id)
            self._commit(lambda data: data["connections"][connection_id].__setitem__("revoked_at", int(self._clock())))

    def create_or_get_run(self, connection: dict[str, Any], client_task_id: str,
                          payload: dict[str, Any], *, parent_task_id: str | None = None) -> tuple[dict[str, Any], bool]:
        client_task_id = validate_identifier(client_task_id, "client_task_id")
        digest = payload_digest(payload)
        namespace = f"{connection['connection_id']}:{connection['instance_id']}:{client_task_id}"
        with self._lock:
            self._assert_open()
            existing_id = self._data["idempotency"].get(namespace)
            if existing_id:
                existing = self._data["runs"][existing_id]
                if existing.get("payload_digest") != digest:
                    raise ConflictError("Idempotency key was already used with different content")
                return dict(existing), False
            parent = None
            if parent_task_id is not None:
                parent = self._data["runs"].get(parent_task_id)
                if not parent or parent.get("owner_connection_id") != connection.get("connection_id") \
                        or parent.get("instance_id") != connection.get("instance_id"):
                    raise AuthorizationError("Parent task does not belong to this device connection")
                if parent.get("status") != "completed" or not parent.get("session_id"):
                    raise ConflictError("Parent task is not available for follow-up")
                if parent.get("next_task_id"):
                    raise ConflictError("Parent task already has a follow-up")
                if not isinstance(parent.get("source"), dict) or not parent.get("source_snapshot_digest"):
                    raise ConflictError("Parent task has no verified source snapshot for follow-up")
                if parent.get("source") != payload.get("source"):
                    raise ConflictError("Follow-up source must match the parent task")
            task_id = str(uuid.uuid4())
            root_task_id = parent.get("conversation_id", parent["task_id"]) if parent else task_id
            run = {
                "task_id": task_id,
                "instance_id": connection["instance_id"],
                "owner_connection_id": connection["connection_id"],
                "device_id": connection["device_id"],
                "client_task_id": client_task_id,
                "parent_task_id": parent_task_id or "",
                "conversation_id": root_task_id,
                "source": payload.get("source"),
                "session_id": None,
                "input_session_id": parent.get("session_id") if parent else None,
                "source_snapshot_digest": parent.get("source_snapshot_digest") if parent else None,
                "video_binding": None,
                "video_review_snapshot": None,
                "payload_digest": digest,
                "status": "submitting",
                "created_at": int(self._clock()),
                "first_submit_at": int(self._clock()),
                "updated_at": int(self._clock()),
                "upstream_run_id": None,
                "upstream_payload": None,
                "upstream_idempotency_key": None,
                "output": None,
                "error": None,
                "approval": None,
                "artifacts": [],
            }
            def mutation(data):
                data["runs"][task_id] = run
                data["idempotency"][namespace] = task_id
                if parent is not None:
                    data["runs"][parent_task_id]["next_task_id"] = task_id
            self._commit(mutation)
            return dict(run), True

    def find_idempotent_run(self, connection: dict[str, Any], client_task_id: str,
                            payload: dict[str, Any]) -> dict[str, Any] | None:
        client_task_id = validate_identifier(client_task_id, "client_task_id")
        digest = payload_digest(payload)
        namespace = f"{connection['connection_id']}:{connection['instance_id']}:{client_task_id}"
        with self._lock:
            self._assert_open()
            existing_id = self._data["idempotency"].get(namespace)
            if not existing_id:
                return None
            existing = self._data["runs"][existing_id]
            if existing.get("payload_digest") != digest:
                raise ConflictError("Idempotency key was already used with different content")
            return dict(existing)

    def update_run(self, task_id: str, **fields: Any) -> dict[str, Any]:
        with self._lock:
            self._assert_open()
            run = self._data["runs"].get(task_id)
            if not run:
                raise KeyError(task_id)
            allowed = {"status", "upstream_run_id", "upstream_payload", "upstream_idempotency_key",
                       "output", "error", "approval", "artifacts", "stopped_at", "session_id",
                       "source_snapshot_digest", "video_binding", "video_review_snapshot",
                       "video_workflow_state", "video_workflow_error"}
            for key, value in fields.items():
                if key not in allowed:
                    raise ValueError(f"unsupported run field: {key}")
                if key == "video_workflow_state" and value is not None and value not in {
                        "initialized", "awaiting_storyboard_review", "approved", "completed",
                        "outcome_unknown", "failed", "cancelled"}:
                    raise ValidationError("invalid video workflow state")
                if key == "video_workflow_error" and value is not None:
                    fields[key] = validate_text(value, "video_workflow_error", 500)
                if key == "video_binding" and value is not None:
                    if not isinstance(value, dict) or set(value) != {"worker_task_id", "request_sha256"} \
                            or not isinstance(value.get("worker_task_id"), str) \
                            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", value["worker_task_id"]) \
                            or not isinstance(value.get("request_sha256"), str) \
                            or not re.fullmatch(r"[a-f0-9]{64}", value["request_sha256"]):
                        raise ValidationError("invalid video task binding")
                if key == "video_binding" and run.get("video_binding") is not None \
                        and value != run["video_binding"]:
                    raise ConflictError("Video task binding is immutable")
                if key == "video_review_snapshot" and value is not None:
                    if not isinstance(value, dict) or set(value) != {"projection", "state_sha256"} \
                            or not isinstance(value.get("projection"), dict) \
                            or not isinstance(value.get("state_sha256"), str) \
                            or not re.fullmatch(r"[a-f0-9]{64}", value["state_sha256"]) \
                            or len(canonical_json(value)) > 64 * 1024:
                        raise ValidationError("invalid video review snapshot")
            def mutation(data):
                target = data["runs"][task_id]
                for key, value in fields.items():
                    target[key] = value
                target["updated_at"] = int(self._clock())
                return dict(target)
            return self._commit(mutation)

    def owned_run(self, connection: dict[str, Any], task_id: str) -> dict[str, Any]:
        with self._lock:
            self._assert_open()
            run = self._data["runs"].get(task_id)
            if not run or run.get("owner_connection_id") != connection.get("connection_id") \
                    or run.get("instance_id") != connection.get("instance_id"):
                raise AuthorizationError("Task does not belong to this device connection")
            return dict(run)

    def reserve_video_operation(self, connection: dict[str, Any], task_id: str,
                                client_operation_id: str, action: str,
                                parameters: dict[str, Any], *,
                                expected_source_snapshot_digest: str | None = None,
                                expected_video_binding: dict[str, Any] | None = None
                                ) -> tuple[dict[str, Any], bool]:
        from .video_operations import (
            VideoOperationConflict, VideoOperationInvalid, current_binding,
            detached, make_record, normalize_parameters, payload_digest,
            MAX_VIDEO_OPERATIONS, MAX_VIDEO_OPERATIONS_PER_TASK,
        )

        task_id = validate_identifier(task_id, "task_id")
        client_operation_id = validate_identifier(client_operation_id, "client_operation_id")
        if not isinstance(connection, dict):
            raise AuthorizationError("Device connection is invalid")
        try:
            clean_parameters = normalize_parameters(action, detached(parameters))
        except VideoOperationInvalid as error:
            raise ValidationError(str(error)) from error
        with self._lock:
            self._assert_open()
            try:
                live_connection, _instance, run = current_binding(
                    self._data, connection.get("connection_id"), connection.get("instance_id"), task_id)
            except (VideoOperationConflict, TypeError) as error:
                raise ConflictError(str(error)) from error
            if live_connection.get("device_id") != connection.get("device_id"):
                raise AuthorizationError("Device connection identity changed")
            if expected_source_snapshot_digest is not None \
                    and run.get("source_snapshot_digest") != expected_source_snapshot_digest:
                raise ConflictError("Video task source changed before reservation")
            if expected_video_binding is not None and run.get("video_binding") != expected_video_binding:
                raise ConflictError("Video task binding changed before reservation")
            digest = payload_digest(action, clean_parameters)
            records = self._data["video_operations"]
            for existing in records.values():
                if existing["owner_connection_id"] == live_connection["connection_id"] \
                        and existing["task_id"] == task_id \
                        and existing["client_operation_id"] == client_operation_id:
                    if existing["payload_digest"] != digest:
                        raise ConflictError("Video operation key was already used with different content")
                    return detached(existing), False
            if action in {"approve", "revise"}:
                # Both act on the review that is currently awaiting approval, so
                # a stale revision, cursor, review or IR digest must be refused
                # before the work is even queued. A revision reads the same
                # observed digests it is asked to replace.
                snapshot = run.get("video_review_snapshot")
                projection = snapshot.get("projection") if isinstance(snapshot, dict) else None
                binding = run["video_binding"]
                if not isinstance(projection, dict) \
                        or projection.get("task_id") != binding["worker_task_id"] \
                        or projection.get("status") != "awaiting_storyboard_review" \
                        or type(projection.get("revision")) is not int \
                        or projection.get("revision") != clean_parameters["revision"] \
                        or type(projection.get("event_cursor")) is not int \
                        or projection.get("review_sha256") != clean_parameters["review_sha256"] \
                        or projection.get("lesson_ir_sha256") != clean_parameters["lesson_ir_sha256"] \
                        or projection.get("event_cursor") != clean_parameters["event_cursor"]:
                    raise ConflictError(
                        "Approval parameters do not match the current storyboard review"
                        if action == "approve"
                        else "Revision parameters do not match the current storyboard review")
            elif action == "produce":
                approvals = [item for item in records.values()
                             if item.get("owner_connection_id") == live_connection["connection_id"]
                             and item.get("task_id") == task_id
                             and item.get("action") == "approve"
                             and item.get("status") == "succeeded"]
                approval = next((item for item in approvals
                                 if item.get("parameters", {}).get("revision") == clean_parameters["revision"]
                                 and item.get("parameters", {}).get("review_sha256") == clean_parameters["review_sha256"]
                                 and item.get("parameters", {}).get("lesson_ir_sha256") == clean_parameters["lesson_ir_sha256"]
                                 and isinstance(item.get("result"), dict)
                                 and item["result"].get("event_cursor") == clean_parameters["event_cursor"]), None)
                if approval is None:
                    raise ConflictError("Video production requires the exact approved storyboard receipt")
                if any(item.get("task_id") == task_id and item.get("action") == "produce"
                       and item.get("status") in {"succeeded", "queued", "running", "unknown"}
                       for item in records.values()):
                    raise ConflictError("This storyboard already has a video production operation")
            if len(records) >= MAX_VIDEO_OPERATIONS:
                raise ConflictError("Video operation ledger is full")
            task_records = [item for item in records.values() if item["task_id"] == task_id]
            if len(task_records) >= MAX_VIDEO_OPERATIONS_PER_TASK:
                raise ConflictError("Video task operation limit is full")
            if any(item["task_id"] == task_id
                   and item["status"] in {"queued", "running", "unknown"}
                   for item in records.values()):
                raise ConflictError("A video operation for this task is unresolved")
            queue_sequence = max((item["queue_sequence"] for item in records.values()), default=0) + 1
            if queue_sequence > 2**53 - 1:
                raise ConflictError("Video operation queue sequence is exhausted")
            now = max(0, int(self._clock()))
            record = make_record(live_connection, task_id, client_operation_id,
                                 action, clean_parameters, run, queue_sequence, now)
            operation_id = record["operation_id"]
            self._commit(lambda data: data["video_operations"].__setitem__(operation_id, record))
            return detached(self._data["video_operations"][operation_id]), True

    def owned_video_operation(self, connection: dict[str, Any], task_id: str,
                              operation_id: str) -> dict[str, Any]:
        from .video_operations import detached

        task_id = validate_identifier(task_id, "task_id")
        with self._lock:
            self._assert_open()
            live = self._live_video_connection(connection)
            operation = self._data["video_operations"].get(operation_id)
            if not isinstance(operation, dict) or operation.get("owner_connection_id") != live["connection_id"] \
                    or operation.get("instance_id") != live["instance_id"] \
                    or operation.get("task_id") != task_id:
                raise AuthorizationError("Video operation does not belong to this device task")
            return detached(operation)

    def video_operation_by_client_key(self, connection: dict[str, Any], task_id: str,
                                      client_operation_id: str) -> dict[str, Any] | None:
        """Return a caller-owned idempotency record without requiring fresh admission."""
        from .video_operations import detached

        task_id = validate_identifier(task_id, "task_id")
        client_operation_id = validate_identifier(client_operation_id, "client_operation_id")
        with self._lock:
            self._assert_open()
            live = self._live_video_connection(connection)
            matches = [record for record in self._data["video_operations"].values()
                       if record.get("owner_connection_id") == live["connection_id"]
                       and record.get("instance_id") == live["instance_id"]
                       and record.get("task_id") == task_id
                       and record.get("client_operation_id") == client_operation_id]
            if len(matches) > 1:
                raise ConflictError("Video operation idempotency record is ambiguous")
            return detached(matches[0]) if matches else None

    def claim_next_video_operation(self) -> dict[str, Any] | None:
        from .video_operations import detached, record_binding_error

        with self._lock:
            self._assert_open()
            if not any(item["status"] == "queued"
                       for item in self._data["video_operations"].values()):
                return None
            attempt_id = str(uuid.uuid4())
            clock_now = max(0, int(self._clock()))
            selected: dict[str, Any] | None = None
            def mutation(data):
                nonlocal selected
                records = data["video_operations"]
                # Re-evaluate selection from the fresh _commit copy.
                for record in sorted(records.values(), key=lambda item: item["queue_sequence"]):
                    if record["status"] != "queued":
                        continue
                    invalid = record_binding_error(data, record)
                    if invalid:
                        record.update(status="cancelled", error=invalid,
                                      updated_at=max(clock_now, record["updated_at"]))
                        continue
                    record.update(status="running", attempt_id=attempt_id,
                                  error=None, updated_at=max(clock_now, record["updated_at"]))
                    selected = record
                    break
                return selected

            # Claim or invalidate queued work durably before returning it to a scheduler.
            self._commit(mutation)
            if selected is None:
                return None
            return detached(self._data["video_operations"][selected["operation_id"]])

    def finish_video_operation(self, operation_id: str, attempt_id: str, status: str,
                               result: dict[str, Any] | None = None,
                               error: str | None = None) -> dict[str, Any]:
        from .video_operations import (
            ERROR_CODES, VideoOperationInvalid, detached, validate_result,
        )

        if not isinstance(operation_id, str) or not re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", operation_id):
            raise ValidationError("invalid video operation id")
        if not isinstance(attempt_id, str) or not re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", attempt_id):
            raise ValidationError("invalid video operation attempt")
        if not isinstance(status, str) or status not in {"succeeded", "failed", "unknown"}:
            raise ValidationError("invalid video operation result status")
        if error is not None and (not isinstance(error, str) or error not in ERROR_CODES):
            raise ValidationError("invalid video operation error code")
        with self._lock:
            self._assert_open()
            current = self._data["video_operations"].get(operation_id)
            if not current:
                raise KeyError(operation_id)
            clean_result = None
            if status in {"failed", "unknown"} and result is not None:
                raise ValidationError("failed or unknown video operation cannot store a result")
            if result is not None:
                try:
                    clean_result = validate_result(
                        detached(result), current["video_binding"]["worker_task_id"],
                        current["action"], current["parameters"], expected_record=current)
                except VideoOperationInvalid as invalid:
                    raise ValidationError(str(invalid)) from invalid
            if status == "succeeded" and (clean_result is None or error is not None):
                raise ValidationError("successful video operation requires a verified result")
            if status in {"failed", "unknown"} and error is None:
                raise ValidationError("failed or unknown video operation requires a safe error code")
            proposed = {"status": status, "result": clean_result, "error": error}
            if current["status"] in {"succeeded", "failed", "unknown", "cancelled"}:
                if current["status"] == "cancelled" and current.get("cancel_request") is not None \
                        and current.get("attempt_id") == attempt_id:
                    # A verified cancel receipt won the ledger CAS. The scheduler's
                    # callback may return later after its process group was stopped;
                    # that late result cannot overwrite or halt the durable winner.
                    return detached(current)
                existing = {key: current[key] for key in proposed}
                if current.get("attempt_id") == attempt_id and existing == proposed:
                    return detached(current)
                raise ConflictError("Video operation is already terminal")
            if current["status"] != "running" or current.get("attempt_id") != attempt_id:
                raise ConflictError("Video operation attempt is no longer current")
            def mutation(data):
                record = data["video_operations"][operation_id]
                if record["status"] != "running" or record.get("attempt_id") != attempt_id:
                    raise ConflictError("Video operation attempt is no longer current")
                record.update(status=status, result=detached(clean_result), error=error,
                              updated_at=max(0, int(self._clock()), record["updated_at"]))
            self._commit(mutation)
            return detached(self._data["video_operations"][operation_id])

    def reconcile_unknown_video_operation(self, connection: dict[str, Any],
                                          expected_record: dict[str, Any],
                                          result: dict[str, Any]) -> dict[str, Any]:
        """CAS an explicitly verified unknown operation to succeeded.

        This deliberately does not broaden finish_video_operation: callers must
        present the complete unknown record they inspected, and the connection,
        run, immutable source binding, and attempt are rechecked under the store
        lock immediately before the durable transition.
        """
        from .video_operations import (
            ERROR_CODES, VideoOperationConflict, VideoOperationInvalid,
            _RECORD_FIELDS_V2, current_binding, detached, validate_result,
        )

        if not isinstance(expected_record, dict):
            raise ValidationError("invalid expected video operation")
        try:
            expected = detached(expected_record)
        except Exception as error:
            raise ValidationError("invalid expected video operation") from error
        operation_id = expected.get("operation_id")
        attempt_id = expected.get("attempt_id")
        if not isinstance(operation_id, str) or not re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", operation_id) \
                or not isinstance(attempt_id, str) or not re.fullmatch(
                    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", attempt_id):
            raise ValidationError("invalid expected video operation")
        if set(expected) != _RECORD_FIELDS_V2 or expected.get("status") != "unknown" \
                or expected.get("result") is not None or expected.get("error") not in ERROR_CODES:
            raise ValidationError("expected video operation is not unresolved unknown work")
        try:
            clean_result = validate_result(
                detached(result), expected["video_binding"]["worker_task_id"],
                expected["action"], expected["parameters"], expected_record=expected)
        except (KeyError, TypeError, VideoOperationInvalid) as error:
            raise ValidationError("reconciled video operation result is invalid") from error

        identity_fields = (
            "operation_id", "owner_connection_id", "instance_id", "task_id",
            "client_operation_id", "action", "parameters", "payload_digest",
            "source_snapshot_digest", "video_binding", "queue_sequence",
            "attempt_id", "created_at",
        )
        with self._lock:
            self._assert_open()
            live = self._live_video_connection(connection)
            if live.get("connection_id") != expected.get("owner_connection_id") \
                    or live.get("instance_id") != expected.get("instance_id"):
                raise AuthorizationError("Video operation does not belong to this device connection")
            try:
                _connection, _instance, run = current_binding(
                    self._data, live["connection_id"], live["instance_id"], expected["task_id"])
            except (VideoOperationConflict, TypeError) as error:
                raise ConflictError(str(error)) from error
            if run.get("source_snapshot_digest") != expected.get("source_snapshot_digest") \
                    or run.get("video_binding") != expected.get("video_binding"):
                raise ConflictError("Video operation source binding changed")

            current = self._data["video_operations"].get(operation_id)
            if not isinstance(current, dict):
                raise KeyError(operation_id)
            if any(current.get(key) != expected.get(key) for key in identity_fields):
                raise ConflictError("Video operation identity or attempt changed")
            if current.get("status") == "succeeded":
                if current.get("result") == clean_result and current.get("error") is None:
                    return detached(current)
                raise ConflictError("Video operation was reconciled with a different result")
            if current != expected or current.get("status") != "unknown":
                raise ConflictError("Video operation is no longer the expected unknown attempt")

            def mutation(data):
                # Repeat all CAS conditions against the copy _commit will persist.
                live_conn = self._live_video_connection(connection)
                try:
                    _conn, _inst, current_run = current_binding(
                        data, live_conn["connection_id"], live_conn["instance_id"], expected["task_id"])
                except (VideoOperationConflict, TypeError) as error:
                    raise ConflictError(str(error)) from error
                if current_run.get("source_snapshot_digest") != expected["source_snapshot_digest"] \
                        or current_run.get("video_binding") != expected["video_binding"]:
                    raise ConflictError("Video operation source binding changed")
                record = data["video_operations"].get(operation_id)
                if record != expected or record.get("status") != "unknown":
                    raise ConflictError("Video operation is no longer the expected unknown attempt")
                record.update(status="succeeded", result=detached(clean_result), error=None,
                              updated_at=max(0, int(self._clock()), record["updated_at"]))

            self._commit(mutation)
            return detached(self._data["video_operations"][operation_id])

    def request_running_video_cancel(self, connection: dict[str, Any], task_id: str,
                                     operation_id: str) -> tuple[dict[str, Any], bool]:
        """Persist one cancellation intent for a bound video attempt."""
        from .video_operations import detached, current_binding

        task_id = validate_identifier(task_id, "task_id")
        if not isinstance(operation_id, str) or not re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", operation_id):
            raise ValidationError("invalid video operation id")
        with self._lock:
            self._assert_open()
            live = self._live_video_connection(connection)
            record = self._data["video_operations"].get(operation_id)
            if not isinstance(record, dict) or record.get("owner_connection_id") != live["connection_id"] \
                    or record.get("instance_id") != live["instance_id"] or record.get("task_id") != task_id:
                raise AuthorizationError("Video operation does not belong to this device task")
            try:
                _connection, instance, run = current_binding(
                    self._data, live["connection_id"], live["instance_id"], task_id)
            except (ValueError, TypeError) as error:
                raise ConflictError("Video task source binding is unavailable") from error
            allowed_action = record.get("action") == "storyboard" or (
                record.get("action") == "produce" and instance.get("kind") == "builtin_video")
            if not allowed_action or record.get("status") not in {"running", "unknown"}:
                raise ConflictError("This video operation cannot be stopped")
            if record.get("status") == "unknown" and record.get("cancel_request") is None:
                raise ConflictError("This storyboard has no persisted stop request to verify")
            if record.get("status") == "running" and record.get("attempt_id") is None:
                raise ConflictError("This storyboard has no active attempt")
            if run.get("source_snapshot_digest") != record.get("source_snapshot_digest") \
                    or run.get("video_binding") != record.get("video_binding"):
                raise ConflictError("Video task source binding changed")
            existing = record.get("cancel_request")
            if existing is not None:
                if existing.get("attempt_id") != record.get("attempt_id"):
                    raise ConflictError("Video stop request belongs to a different attempt")
                return detached(record), False
            now = max(0, int(self._clock()), record["updated_at"])
            request = {"attempt_id": record["attempt_id"], "requested_at": now,
                       "control_id": str(uuid.uuid4()), "control_status": "requested"}
            def mutation(data):
                current = data["video_operations"].get(operation_id)
                if current is None or current.get("status") != "running" \
                        or current.get("attempt_id") != request["attempt_id"] \
                        or current.get("cancel_request") is not None:
                    raise ConflictError("Video operation changed before the stop request was saved")
                current["cancel_request"] = request
                current["updated_at"] = now
            self._commit(mutation)
            return detached(self._data["video_operations"][operation_id]), True

    def mark_video_cancel_unconfirmed(self, connection: dict[str, Any],
                                      expected_record: dict[str, Any]) -> dict[str, Any]:
        """Persist that a bounded cancel attempt did not prove the process stopped."""
        from .video_operations import _RECORD_FIELDS_V2, _validate_record, detached

        operation_id = expected_record.get("operation_id") if isinstance(expected_record, dict) else None
        if not isinstance(operation_id, str) or not re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", operation_id) \
                or set(expected_record) != _RECORD_FIELDS_V2:
            raise ValidationError("invalid expected video cancellation record")
        with self._lock:
            self._assert_open()
            try:
                _validate_record(operation_id, expected_record, self._data, version=2)
            except (TypeError, ValueError, KeyError, AttributeError) as error:
                raise ValidationError("invalid expected video cancellation record") from error
            live = self._live_video_connection(connection)
            current = self._data["video_operations"].get(operation_id)
            if not isinstance(current, dict) or current.get("owner_connection_id") != live["connection_id"] \
                    or expected_record.get("owner_connection_id") != live["connection_id"]:
                raise AuthorizationError("Video operation does not belong to this device connection")
            if current.get("status") in {"cancelled", "succeeded", "failed"}:
                return detached(current)
            immutable_fields = (
                "operation_id", "owner_connection_id", "instance_id", "task_id",
                "client_operation_id", "action", "parameters", "payload_digest",
                "source_snapshot_digest", "video_binding", "queue_sequence", "attempt_id",
                "created_at", "cancel_request",
            )
            current_instance = self._data.get("instances", {}).get(expected_record.get("instance_id"))
            allowed_action = expected_record.get("action") == "storyboard" or (
                expected_record.get("action") == "produce"
                and isinstance(current_instance, dict) and current_instance.get("kind") == "builtin_video")
            if not allowed_action \
                    or expected_record.get("status") not in {"running", "unknown"} \
                    or not isinstance(expected_record.get("cancel_request"), dict) \
                    or expected_record["cancel_request"].get("attempt_id") != expected_record.get("attempt_id") \
                    or any(current.get(key) != expected_record.get(key) for key in immutable_fields) \
                    or current.get("status") not in {"running", "unknown"}:
                raise ConflictError("Video cancellation attempt changed before recording its result")
            # This stores only the conservative fact that no stop receipt was
            # proven. Source changes are a reason for uncertainty, not a reason
            # to drop the durable request history.
            now = max(0, int(self._clock()), current["updated_at"])
            def mutation(data):
                record = data["video_operations"].get(operation_id)
                if record is None or record.get("status") != current["status"] \
                        or any(record.get(key) != expected_record.get(key) for key in immutable_fields):
                    raise ConflictError("Video cancellation attempt changed before recording its result")
                record["cancel_request"]["control_status"] = "unconfirmed"
                record["updated_at"] = now
            self._commit(mutation)
            return detached(self._data["video_operations"][operation_id])

    def finish_cancelled_video_operation(self, connection: dict[str, Any],
                                         expected_record: dict[str, Any]) -> dict[str, Any]:
        """CAS a verified Skill cancellation receipt into the ledger."""
        from .video_operations import _RECORD_FIELDS_V2, _validate_record, detached, current_binding

        operation_id = expected_record.get("operation_id") if isinstance(expected_record, dict) else None
        attempt_id = expected_record.get("attempt_id") if isinstance(expected_record, dict) else None
        if not isinstance(operation_id, str) or not re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", operation_id) \
                or not isinstance(attempt_id, str) or not re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", attempt_id):
            raise ValidationError("invalid video cancellation binding")
        if set(expected_record) != _RECORD_FIELDS_V2:
            raise ValidationError("incomplete expected video cancellation record")
        with self._lock:
            self._assert_open()
            try:
                _validate_record(operation_id, expected_record, self._data, version=2)
            except (TypeError, ValueError, KeyError, AttributeError) as error:
                raise ValidationError("invalid expected video cancellation record") from error
            live = self._live_video_connection(connection)
            current = self._data["video_operations"].get(operation_id)
            if not isinstance(current, dict) or current.get("owner_connection_id") != live["connection_id"] \
                    or current.get("instance_id") != live["instance_id"] \
                    or expected_record.get("owner_connection_id") != live["connection_id"] \
                    or expected_record.get("instance_id") != live["instance_id"]:
                raise AuthorizationError("Video operation does not belong to this device connection")
            immutable_fields = (
                "operation_id", "owner_connection_id", "instance_id", "task_id",
                "client_operation_id", "action", "parameters", "payload_digest",
                "source_snapshot_digest", "video_binding", "queue_sequence", "attempt_id",
                "created_at", "cancel_request",
            )
            current_instance = self._data.get("instances", {}).get(expected_record.get("instance_id"))
            allowed_action = expected_record.get("action") == "storyboard" or (
                expected_record.get("action") == "produce"
                and isinstance(current_instance, dict) and current_instance.get("kind") == "builtin_video")
            if any(current.get(key) != expected_record.get(key) for key in immutable_fields) \
                    or not allowed_action \
                    or expected_record.get("status") not in {"running", "unknown", "cancelled"} \
                    or expected_record.get("cancel_request") is None \
                    or expected_record["cancel_request"].get("attempt_id") != attempt_id:
                raise ConflictError("Expected video cancellation identity changed")
            if current.get("status") == "cancelled" and current.get("attempt_id") == attempt_id:
                # A repeated verified receipt is idempotent only for the same
                # complete cancellation identity and still-current source binding.
                pass
            elif current.get("status") not in {"running", "unknown"} \
                    or expected_record.get("status") == "cancelled":
                raise ConflictError("Video operation is no longer eligible for verified cancellation")
            try:
                _live, _instance, run = current_binding(
                    self._data, live["connection_id"], live["instance_id"], current["task_id"])
            except (ValueError, TypeError) as error:
                raise ConflictError("Video task source binding is unavailable") from error
            if run.get("source_snapshot_digest") != current.get("source_snapshot_digest") \
                    or run.get("video_binding") != current.get("video_binding"):
                raise ConflictError("Video task source binding changed")
            observed_status = current.get("status")
            if observed_status == "cancelled":
                return detached(current)
            def mutation(data):
                record = data["video_operations"].get(operation_id)
                if record is None or record.get("status") != observed_status \
                        or any(record.get(key) != expected_record.get(key) for key in immutable_fields):
                    raise ConflictError("Video operation changed before cancellation was committed")
                record.update(status="cancelled", error="cancelled", result=None,
                              updated_at=max(0, int(self._clock()), record["updated_at"]))
            self._commit(mutation)
            return detached(self._data["video_operations"][operation_id])

    def cancel_queued_video_operation(self, connection: dict[str, Any], task_id: str,
                                      operation_id: str) -> dict[str, Any]:
        from .video_operations import detached

        task_id = validate_identifier(task_id, "task_id")
        with self._lock:
            self._assert_open()
            live = self._live_video_connection(connection)
            record = self._data["video_operations"].get(operation_id)
            if not isinstance(record, dict) or record.get("owner_connection_id") != live["connection_id"] \
                    or record.get("instance_id") != live["instance_id"] or record.get("task_id") != task_id:
                raise AuthorizationError("Video operation does not belong to this device task")
            if record["status"] == "cancelled" and record["error"] == "cancelled":
                return detached(record)
            if record["status"] != "queued":
                raise ConflictError("Only queued video operations can be cancelled")
            def mutation(data):
                current = data["video_operations"][operation_id]
                if current["status"] != "queued":
                    raise ConflictError("Only queued video operations can be cancelled")
                current.update(status="cancelled", error="cancelled",
                               updated_at=max(0, int(self._clock()), current["updated_at"]))
            self._commit(mutation)
            return detached(self._data["video_operations"][operation_id])

    def recover_video_operations(self) -> int:
        with self._lock:
            self._assert_open()
            stale_ids = [operation_id for operation_id, record in self._data["video_operations"].items()
                         if record["status"] == "running"]
            if not stale_ids:
                return 0
            def mutation(data):
                for operation_id in stale_ids:
                    record = data["video_operations"][operation_id]
                    if record["status"] == "running":
                        record.update(status="unknown", error="worker_interrupted",
                                      updated_at=max(0, int(self._clock()), record["updated_at"]))
            self._commit(mutation)
            return len(stale_ids)

    def _live_video_connection(self, connection: dict[str, Any]) -> dict[str, Any]:
        connection_id = connection.get("connection_id") if isinstance(connection, dict) else None
        live = self._data["connections"].get(connection_id)
        if not isinstance(live, dict) or live.get("revoked_at") is not None:
            raise AuthorizationError("Device connection is invalid or revoked")
        instance = self._data["instances"].get(live.get("instance_id"))
        if not isinstance(instance, dict) or instance.get("retired_at") is not None:
            raise AuthorizationError("Agent instance is unavailable")
        if connection.get("instance_id") != live.get("instance_id") \
                or connection.get("device_id") != live.get("device_id"):
            raise AuthorizationError("Device connection identity changed")
        return live

    def list_pending_pairs(self) -> list[dict[str, Any]]:
        with self._lock:
            self._assert_open()
            now = int(self._clock())
            return [dict(item) for item in self._data["pair_requests"].values()
                    if item.get("status") == "pending" and int(item.get("expires_at", 0)) > now]

    def list_connections(self) -> list[dict[str, Any]]:
        with self._lock:
            self._assert_open()
            values = []
            for item in self._data["connections"].values():
                clean = {key: value for key, value in item.items() if key != "token_digest"}
                values.append(clean)
            return values
