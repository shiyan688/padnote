from __future__ import annotations

import hashlib
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from . import discovery
from .bundles import (
    collect_artifacts,
    open_artifact,
    prepare_task_directory,
    public_artifacts,
)
from .hermes import CredentialVault, HermesClient, HermesError, normalize_run
from .profiles import read_hermes_profile
from .security import ValidationError, canonical_json, validate_identifier, validate_public_url, validate_text
from .state import AuthorizationError, ConflictError, PairingError, StateStore


TERMINAL_STATUSES = {"completed", "failed", "cancelled", "interrupted"}
RESUBMIT_WINDOW_SECONDS = 24 * 60 * 60


class BridgeService:
    def __init__(self, state_dir: Path, *, allow_test_http: bool = False, clock=time.time):
        self.store = StateStore(state_dir, clock=clock)
        self._closed = False
        try:
            self.vault = CredentialVault()
            self.allow_test_http = allow_test_http
            self.clock = clock
            self.discovery_results = discovery.discover()
            self.last_pair_payload: dict[str, Any] | None = None
            self.last_pair_expires_at: int = 0
            self._operation_locks: dict[str, threading.Lock] = {}
            self._operation_locks_guard = threading.Lock()
            self._reload_configured_profiles()
        except BaseException:
            self.store.close()
            raise

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self.vault = CredentialVault()
        self.store.close()

    def __enter__(self) -> BridgeService:
        return self

    def __exit__(self, _type, _value, _traceback) -> None:
        self.close()

    def refresh_discovery(self, *, home: Path | None = None, path_value: str | None = None) -> list[dict]:
        self.discovery_results = discovery.discover(home, path_value)
        return list(self.discovery_results)

    def add_instance(self, kind: str, name: str, base_url: str, api_key: str | None = None) -> dict:
        fingerprint = self._key_fingerprint(api_key) if api_key else None
        instance = self.store.add_instance(
            kind, name, base_url, credential_fingerprint=fingerprint)
        if api_key:
            self.vault.set(instance["instance_id"], api_key)
        return instance

    def import_discovered_profile(self, path: str) -> dict:
        allowed = {
            str(Path(item["path"]).expanduser().resolve())
            for item in self.discovery_results
            if item.get("kind") == "hermes" and Path(item.get("path", "")).name == ".env"
        }
        selected = str(Path(path).expanduser().resolve())
        if selected not in allowed:
            raise ValidationError("Selected profile is not a discovered Hermes .env")
        profile = read_hermes_profile(Path(selected))
        profile_lock = "profile:" + hashlib.sha256(profile.path.encode("utf-8")).hexdigest()
        with self._operation_lock(profile_lock):
            matching = [item for item in self.store.list_instances()
                        if item.get("config_ref") == profile.path and not item.get("retired_at")]
            instance = next((item for item in matching
                             if item.get("config_fingerprint") == profile.fingerprint
                             and item.get("base_url") == profile.base_url), None)
            if instance is None:
                for old in matching:
                    with self._operation_lock("instance:" + old["instance_id"]):
                        self.store.retire_instance(
                            old["instance_id"],
                            "Hermes API address or key changed. Pair again with the new instance.")
                        self.vault.clear(old["instance_id"])
                instance = self.store.add_instance(
                    "hermes", f"Hermes · {Path(profile.path).parent.name or 'default'}",
                    profile.base_url, config_ref=profile.path,
                    config_fingerprint=profile.fingerprint,
                    credential_fingerprint=self._key_fingerprint(profile.api_key))
            elif not instance.get("credential_fingerprint"):
                instance = self.store.set_credential_fingerprint(
                    instance["instance_id"], self._key_fingerprint(profile.api_key))
            self.vault.set(instance["instance_id"], profile.api_key)
            return instance

    def _reload_configured_profiles(self) -> None:
        for instance in self.store.list_instances():
            if instance.get("retired_at"):
                self.vault.clear(instance["instance_id"])
                continue
            config_ref = instance.get("config_ref")
            if not config_ref:
                continue
            try:
                profile = read_hermes_profile(Path(config_ref))
            except ValidationError as error:
                self.vault.clear(instance["instance_id"])
                self.store.update_instance_check(
                    instance["instance_id"], health="profile_unavailable", detail=str(error),
                    features={}, executable=False)
                continue
            if profile.fingerprint != instance.get("config_fingerprint") \
                    or profile.base_url != instance.get("base_url"):
                self.store.retire_instance(
                    instance["instance_id"],
                    "Hermes profile changed; choose Use this profile again and pair with the new instance.")
                self.vault.clear(instance["instance_id"])
                continue
            if not instance.get("credential_fingerprint"):
                self.store.set_credential_fingerprint(
                    instance["instance_id"], self._key_fingerprint(profile.api_key))
            self.vault.set(instance["instance_id"], profile.api_key)

    def configure_key(self, instance_id: str, api_key: str) -> dict[str, Any]:
        fingerprint = self._key_fingerprint(api_key)
        with self._operation_lock("instance:" + instance_id):
            instance = self.store.get_instance(instance_id)
            if instance.get("retired_at"):
                raise ValidationError("This Agent identity is retired; add or import it as a new instance")
            existing_fingerprint = instance.get("credential_fingerprint")
            if existing_fingerprint == fingerprint:
                self.vault.set(instance_id, api_key)
                return instance
            if existing_fingerprint is None and not self.store.has_instance_history(instance_id):
                instance = self.store.set_credential_fingerprint(instance_id, fingerprint)
                self.vault.set(instance_id, api_key)
                return instance
            if instance.get("config_ref"):
                raise ValidationError("The selected profile key changed; use this profile again and re-pair")
            self.store.retire_instance(
                instance_id, "Hermes key changed. Existing device grants cannot use the new identity; pair again.")
            self.vault.clear(instance_id)
            replacement = self.store.add_instance(
                instance["kind"], instance["name"], instance["base_url"],
                credential_fingerprint=fingerprint)
            self.vault.set(replacement["instance_id"], api_key)
            return replacement

    def check_instance(self, instance_id: str) -> dict:
        with self._operation_lock("instance:" + instance_id):
            instance = self.store.get_instance(instance_id)
            if instance.get("retired_at"):
                raise ValidationError("This Agent identity is retired; pair with its replacement")
            if instance["kind"] != "hermes":
                return self.store.update_instance_check(
                    instance_id,
                    health="unsupported",
                    detail="OpenClaw 已发现，但本版尚未接通任务执行。",
                    features={},
                    executable=False,
                )
            client = self._hermes(instance_id, allow_unchecked=True)
            result = client.check_capabilities()
            return self.store.update_instance_check(
                instance_id,
                health=result.health,
                detail=result.detail,
                features=result.features,
                executable=result.executable,
            )

    def create_pair_payload(self, instance_id: str, public_url: str) -> dict[str, Any]:
        url = validate_public_url(public_url, allow_loopback_http=self.allow_test_http)
        _, payload = self.store.create_pair_code(instance_id, url)
        self.last_pair_payload = payload
        self.last_pair_expires_at = int(self.clock()) + self.store.PAIR_TTL_SECONDS
        return dict(payload)

    def request_pair(self, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        return 202, self.store.request_pair(
            validate_text(payload.get("code"), "code", 200),
            payload.get("device_id"),
            payload.get("device_name"),
        )

    def claim_pair(self, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        request_id = validate_identifier(payload.get("request_id"), "request_id")
        poll_token = validate_text(payload.get("poll_token"), "poll_token", 200)
        return self.store.claim_pair(request_id, poll_token)

    def approve_pair(self, request_id: str, approved: bool) -> None:
        self.store.decide_pair(request_id, approved)

    def revoke_connection(self, connection_id: str) -> None:
        # Persist the revocation before waiting behind an in-flight operation.
        # Requests already queued on this connection will then fail their
        # second authentication check instead of each reaching Hermes first.
        self.store.revoke_connection(connection_id)
        with self._operation_lock("connection:" + connection_id):
            pass

    def capabilities(self, instance_id: str, bearer: str) -> dict[str, Any]:
        with self._authorized_connection(instance_id, bearer):
            with self._operation_lock("instance:" + instance_id):
                instance = self.store.get_instance(instance_id)
                available = bool(instance.get("executable")) and not instance.get("retired_at") \
                    and self.vault.has(instance_id)
                features = dict(instance.get("features") or {})
                if not available:
                    for key in ("run_submission", "run_status", "run_stop", "run_approval_response"):
                        features[key] = False
                features["task_bundle"] = available
                features["artifacts"] = True
                return {
                    "object": "padnote.agent.capabilities",
                    "protocol_version": 1,
                    "bridge_id": self.store.bridge_id,
                    "instance_id": instance_id,
                    "kind": instance["kind"],
                    "name": instance["name"],
                    "features": features,
                }

    def submit_run(self, instance_id: str, bearer: str, idempotency_key: str,
                   payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        clean = self._validate_run_payload(payload, idempotency_key)
        with self._authorized_connection(instance_id, bearer) as connection:
            with self._operation_lock("instance:" + instance_id):
                self._hermes(instance_id)
                lock_key = f"submit:{connection['connection_id']}:{instance_id}:{clean['client_task_id']}"
                with self._operation_lock(lock_key):
                    return self._submit_run_locked(instance_id, connection, clean)

    def _submit_run_locked(self, instance_id: str, connection: dict[str, Any],
                           clean: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        run, created = self.store.create_or_get_run(connection, clean["client_task_id"], clean)
        with self._operation_lock("task:" + run["task_id"]):
            current = self.store.owned_run(connection, run["task_id"])
            return self._continue_submit_locked(instance_id, connection, clean, current, created)

    def _continue_submit_locked(self, instance_id: str, connection: dict[str, Any],
                                clean: dict[str, Any], run: dict[str, Any],
                                created: bool) -> tuple[int, dict[str, Any]]:
        if not created:
            if run["status"] != "submitting" or run.get("upstream_run_id"):
                return 202, self._public_run(run)
            if int(self.clock()) - int(run.get("first_submit_at", 0)) > RESUBMIT_WINDOW_SECONDS:
                return 202, self._public_run(run)

        try:
            task_dir = prepare_task_directory(
                self.store.tasks_dir,
                run["task_id"],
                clean,
                clean.get("bundle_base64"),
                clean.get("bundle_sha256"),
            )
        except ValidationError as error:
            failed = self.store.update_run(run["task_id"], status="failed", error=str(error))
            return 202, self._public_run(failed)

        upstream_input = self._upstream_input(clean, task_dir)
        upstream_payload = {"input": upstream_input}
        upstream_key = hashlib.sha256(
            f"{self.store.bridge_id}\n{connection['connection_id']}\n{instance_id}\n{clean['client_task_id']}".encode("utf-8")
        ).hexdigest()
        if created or not run.get("upstream_payload"):
            run = self.store.update_run(
                run["task_id"],
                upstream_payload=upstream_payload,
                upstream_idempotency_key=upstream_key,
            )
        else:
            upstream_payload = run["upstream_payload"]
            upstream_key = run["upstream_idempotency_key"]

        try:
            response = self._hermes(instance_id).create_run(
                upstream_payload["input"], upstream_key,
            )
            upstream_run_id = response.get("id") or response.get("run_id")
            if not isinstance(upstream_run_id, str) or not upstream_run_id.strip():
                raise HermesError("upstream_invalid", "Hermes did not return a run ID", uncertain=True)
            try:
                normalized = normalize_run(response)
            except HermesError as error:
                error.uncertain = True
                raise
            status = normalized["status"]
            if status == "submitting":
                status = "running"
            artifacts = run.get("artifacts") or []
            if status == "completed":
                artifacts = public_artifacts(collect_artifacts(
                    self.store.tasks_dir / run["task_id"], run["task_id"]))
            run = self.store.update_run(
                run["task_id"],
                upstream_run_id=upstream_run_id.strip(),
                status=status,
                output=normalized["output"],
                error=normalized["error"],
                approval=normalized["approval"],
                artifacts=artifacts,
            )
        except HermesError as error:
            if error.uncertain:
                run = self.store.update_run(run["task_id"], error="Submission outcome is unknown; retry with the same task ID")
            else:
                run = self.store.update_run(run["task_id"], status="failed", error=str(error))
        return 202, self._public_run(run)

    def get_run(self, instance_id: str, bearer: str, task_id: str) -> dict[str, Any]:
        task_id = validate_identifier(task_id, "task_id")
        with self._authorized_connection(instance_id, bearer) as connection:
            with self._operation_lock("instance:" + instance_id):
                with self._operation_lock("task:" + task_id):
                    return self._get_run_locked(instance_id, connection, task_id)

    def _get_run_locked(self, instance_id: str, connection: dict[str, Any], task_id: str) -> dict[str, Any]:
        run = self.store.owned_run(connection, task_id)
        if run["status"] not in TERMINAL_STATUSES and run.get("upstream_run_id"):
            response = self._hermes(instance_id).get_run(run["upstream_run_id"])
            normalized = normalize_run(response)
            fields: dict[str, Any] = normalized
            if normalized["status"] == "completed":
                fields["artifacts"] = public_artifacts(collect_artifacts(
                    self.store.tasks_dir / task_id, task_id))
            run = self.store.update_run(task_id, **fields)
        return self._public_run(run)

    def stop_run(self, instance_id: str, bearer: str, task_id: str) -> tuple[int, dict[str, Any]]:
        task_id = validate_identifier(task_id, "task_id")
        with self._authorized_connection(instance_id, bearer) as connection:
            with self._operation_lock("instance:" + instance_id):
                with self._operation_lock("task:" + task_id):
                    return self._stop_run_locked(instance_id, connection, task_id)

    def _stop_run_locked(self, instance_id: str, connection: dict[str, Any],
                         task_id: str) -> tuple[int, dict[str, Any]]:
        run = self.store.owned_run(connection, task_id)
        if run["status"] in TERMINAL_STATUSES:
            return 200, self._public_run(run)
        if not run.get("upstream_run_id"):
            # A stop request must never be the first operation that submits an
            # uncertain task. The user can retry the original submit with its
            # persisted idempotency identity, then stop once Hermes returns an ID.
            run = self.store.update_run(
                task_id,
                error="Cannot confirm whether Hermes accepted this task. Check Hermes on the computer before retrying stop.",
            )
            return 202, self._public_run(run)
        try:
            self._hermes(instance_id).stop_run(run["upstream_run_id"])
            run = self.store.update_run(task_id, status="stopping", stopped_at=int(self.clock()))
        except HermesError as error:
            if error.uncertain:
                run = self.store.update_run(task_id, status="stopping")
            else:
                raise
        return 202, self._public_run(run)

    def approve_run(self, instance_id: str, bearer: str, task_id: str,
                    payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        task_id = validate_identifier(task_id, "task_id")
        with self._authorized_connection(instance_id, bearer) as connection:
            with self._operation_lock("instance:" + instance_id):
                with self._operation_lock("task:" + task_id):
                    return self._approve_run_locked(instance_id, connection, task_id, payload)

    def _approve_run_locked(self, instance_id: str, connection: dict[str, Any], task_id: str,
                            payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        run = self.store.owned_run(connection, task_id)
        approval_id = validate_text(payload.get("approval_id"), "approval_id", 256)
        decision = validate_text(payload.get("decision"), "decision", 20)
        if decision not in {"once", "deny"}:
            raise ValidationError("decision must be once or deny")
        current = run.get("approval")
        if run.get("status") != "waiting_for_approval" or not isinstance(current, dict) \
                or current.get("approval_id") != approval_id:
            raise ConflictError("Approval is no longer current")
        if not run.get("upstream_run_id"):
            raise ConflictError("Task has no upstream run")
        self._hermes(instance_id).approve_run(run["upstream_run_id"], approval_id, decision)
        run = self.store.update_run(task_id, status="running", approval=None)
        return 202, self._public_run(run)

    def get_artifact(self, instance_id: str, bearer: str, task_id: str,
                     artifact_id: str):
        with self._authorized_connection(instance_id, bearer) as connection:
            self.store.owned_run(connection, validate_identifier(task_id, "task_id"))
            artifact_id = validate_identifier(artifact_id, "artifact_id")
            return open_artifact(self.store.tasks_dir / task_id, task_id, artifact_id)

    @contextmanager
    def _authorized_connection(self, instance_id: str, bearer: str):
        initial = self.store.authenticate(instance_id, bearer)
        with self._operation_lock("connection:" + initial["connection_id"]):
            current = self.store.authenticate(instance_id, bearer)
            if current["connection_id"] != initial["connection_id"]:
                raise AuthorizationError("Device credential identity changed")
            yield current

    def _operation_lock(self, key: str) -> threading.Lock:
        with self._operation_locks_guard:
            return self._operation_locks.setdefault(key, threading.Lock())

    def _hermes(self, instance_id: str, *, allow_unchecked: bool = False) -> HermesClient:
        instance = self.store.get_instance(instance_id)
        if instance["kind"] != "hermes":
            raise ValidationError("OpenClaw task execution is not implemented")
        if instance.get("retired_at"):
            raise ValidationError("This Agent identity is retired; pair with its replacement")
        if not allow_unchecked and not instance.get("executable"):
            raise ValidationError("Hermes task execution is not currently available")
        if not self.vault.has(instance_id):
            raise ValidationError("Hermes key is not loaded on this computer")
        return HermesClient(instance["base_url"], self.vault.get(instance_id))

    @staticmethod
    def _key_fingerprint(api_key: str) -> str:
        value = api_key.strip()
        if not value or len(value) > 4096 or "\r" in value or "\n" in value:
            raise ValidationError("invalid Hermes key")
        return hashlib.sha256(b"padnote-hermes-key-v1\x00" + value.encode("utf-8")).hexdigest()

    @staticmethod
    def _validate_run_payload(payload: dict[str, Any], idempotency_key: str) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValidationError("request body must be a JSON object")
        client_task_id = validate_identifier(payload.get("client_task_id"), "client_task_id")
        if validate_identifier(idempotency_key, "Idempotency-Key") != client_task_id:
            raise ValidationError("Idempotency-Key must equal client_task_id")
        title = validate_text(payload.get("title"), "title", 256)
        input_value = payload.get("input")
        if not isinstance(input_value, str) or len(input_value.encode("utf-8")) > 128 * 1024:
            raise ValidationError("input must be a string no larger than 128 KiB")
        source = payload.get("source")
        if not isinstance(source, dict):
            raise ValidationError("source must be an object")
        note_id = validate_identifier(source.get("note_id"), "note_id")
        revision = source.get("note_revision")
        if not isinstance(revision, (str, int)) or len(str(revision)) > 120:
            raise ValidationError("invalid note_revision")
        clean = {
            "client_task_id": client_task_id,
            "title": title,
            "input": input_value,
            "source": {"note_id": note_id, "note_revision": revision},
        }
        if "bundle_base64" in payload or "bundle_sha256" in payload:
            clean["bundle_base64"] = payload.get("bundle_base64")
            clean["bundle_sha256"] = validate_text(payload.get("bundle_sha256"), "bundle_sha256", 64)
        return clean

    @staticmethod
    def _upstream_input(payload: dict[str, Any], task_dir: Path) -> str:
        return (
            f"{payload['title']}\n\n{payload['input']}\n\n"
            f"PadNote task workspace: {task_dir}\n"
            "Read only the user-confirmed input in this workspace. Write deliverable files under output/. "
            "This path instruction is a workflow boundary, not an operating-system sandbox."
        )

    @staticmethod
    def _public_run(run: dict[str, Any]) -> dict[str, Any]:
        value = {
            "task_id": run["task_id"],
            "instance_id": run["instance_id"],
            "status": run["status"],
            "artifacts": run.get("artifacts") or [],
        }
        for key in ("output", "error", "approval"):
            if run.get(key) is not None:
                value[key] = run[key]
        return value
