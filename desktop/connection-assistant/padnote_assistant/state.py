from __future__ import annotations

import json
import os
import fcntl
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
        descriptor: int | None = None
        try:
            descriptor = os.open(
                self.state_dir / ".owner.lock",
                os.O_RDWR | os.O_CREAT | getattr(os, "O_CLOEXEC", 0),
                0o600,
            )
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except (BlockingIOError, OSError) as error:
                raise StateOwnershipError(
                    "another connection assistant is already using this state directory") from error
            self._owner_fd = descriptor
            self._owner_key = owner_key
        except BaseException:
            if descriptor is not None:
                os.close(descriptor)
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
        if descriptor is not None:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
            finally:
                os.close(descriptor)
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
        if data.get("version") != self.VERSION or not isinstance(data.get("bridge_id"), str):
            raise StateError("unsupported connection assistant state version")
        for key in ("instances", "pair_codes", "pair_requests", "connections", "runs", "idempotency"):
            if not isinstance(data.get(key), dict):
                raise StateError("connection assistant state is malformed")
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
                     credential_fingerprint: str | None = None) -> dict[str, Any]:
        kind = validate_text(kind, "kind", 20).lower()
        if kind not in {"hermes", "openclaw"}:
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
            "health": "not_checked" if kind == "hermes" else "unsupported",
            "detail": (
                "Enter the Hermes key for this process, then run the capability check."
                if kind == "hermes"
                else "OpenClaw discovery is available, but task execution is not connected."
            ),
            "features": {},
            "executable": False,
        }
        if config_ref:
            instance["config_ref"] = config_ref
            instance["config_fingerprint"] = config_fingerprint or ""
        if credential_fingerprint:
            instance["credential_fingerprint"] = credential_fingerprint
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
                "executable": bool(executable) and instance["kind"] == "hermes",
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

    def create_pair_code(self, instance_id: str, public_url: str) -> tuple[str, dict[str, Any]]:
        with self._lock:
            self._assert_open()
            instance = self._data["instances"].get(instance_id)
            if not instance:
                raise KeyError(instance_id)
            if instance["kind"] != "hermes" or not instance.get("executable"):
                raise StateError("Only a checked, executable Hermes instance can be paired")
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
            if not instance or instance.get("kind") != "hermes" or not instance.get("executable"):
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
                          payload: dict[str, Any]) -> tuple[dict[str, Any], bool]:
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
            task_id = str(uuid.uuid4())
            run = {
                "task_id": task_id,
                "instance_id": connection["instance_id"],
                "owner_connection_id": connection["connection_id"],
                "device_id": connection["device_id"],
                "client_task_id": client_task_id,
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
            self._commit(mutation)
            return dict(run), True

    def update_run(self, task_id: str, **fields: Any) -> dict[str, Any]:
        with self._lock:
            self._assert_open()
            run = self._data["runs"].get(task_id)
            if not run:
                raise KeyError(task_id)
            allowed = {"status", "upstream_run_id", "upstream_payload", "upstream_idempotency_key",
                       "output", "error", "approval", "artifacts", "stopped_at"}
            for key, value in fields.items():
                if key not in allowed:
                    raise ValueError(f"unsupported run field: {key}")
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
