from __future__ import annotations

import hashlib
import threading
import time
from contextlib import contextmanager
import copy
import re
from pathlib import Path
from typing import Any

from . import discovery
from .bundles import (
    collect_artifacts,
    input_snapshot_digest,
    open_artifact,
    open_pinned_builtin_video_artifact,
    prepare_followup_directory,
    prepare_task_directory,
    public_artifacts,
)
from .hermes import CredentialVault, HermesClient, HermesError, normalize_run
from .secure_store import BuiltinCredentialVault, SecureStoreError
from .profiles import read_hermes_profile
from .security import (ValidationError, canonical_json, validate_builtin_video_provider,
                       validate_identifier, validate_lan_url, validate_public_url, validate_text)
from .state import AuthorizationError, ConflictError, PairingError, StateStore
from .video_worker import (VideoWorker, VideoWorkerConfig, VideoWorkerError,
                           not_configured_runtime_diagnostics, _validate_inspection)
from .video_preview import (binding_from_submission, capture_review_snapshot, open_preview,
                            public_review, verify_request_binding)
from .video_scheduler import VideoOperationScheduler
from .video_operations import normalize_parameters, payload_digest
from .video_worker import _MAX_REVISE_FEEDBACK_BYTES, _bounded_feedback_bytes


TERMINAL_STATUSES = {"completed", "failed", "cancelled", "interrupted"}


def _valid_cancel_result(result: Any, operation: dict[str, Any]) -> bool:
    return isinstance(result, dict) and set(result) == {
        "ok", "status", "operation_id", "attempt_id", "reason"} \
        and result.get("operation_id") == operation.get("operation_id") \
        and result.get("attempt_id") == operation.get("attempt_id") \
        and ((result.get("status") == "verified_cancelled"
              and result.get("reason") == "cancel_receipt_match" and result.get("ok") is True)
             or (result.get("status") == "unconfirmed"
                 and result.get("reason") == "cancel_pending" and result.get("ok") is True)
             or (result.get("status") == "unconfirmed" and result.get("ok") is False))
RESUBMIT_WINDOW_SECONDS = 24 * 60 * 60


class BridgeService:
    def __init__(self, state_dir: Path, *, allow_test_http: bool = False, clock=time.time,
                 video_node: Path | None = None, video_skill_root: Path | None = None):
        if (video_node is None) != (video_skill_root is None):
            raise VideoWorkerError(
                "invalid_configuration", "Video worker configuration is invalid")
        self.store = StateStore(state_dir, clock=clock)
        self._closed = False
        try:
            self.vault = CredentialVault()
            self.builtin_vault = BuiltinCredentialVault()
            self.video_worker: VideoWorker | None = None
            if video_node is not None and video_skill_root is not None:
                config = VideoWorkerConfig(
                    Path(video_node), Path(video_skill_root), self.store.tasks_dir)
                self.video_worker = VideoWorker(config)
            self.allow_test_http = allow_test_http
            self.clock = clock
            self.discovery_results = discovery.discover()
            self.last_pair_payload: dict[str, Any] | None = None
            # Set by the launcher when same-network connection is enabled.
            self.lan_identity = None
            self.lan_port: int | None = None
            self.last_pair_expires_at: int = 0
            self._operation_locks: dict[str, threading.Lock] = {}
            self._operation_locks_guard = threading.Lock()
            self._video_lifecycle_lock = threading.Lock()
            self._video_lifecycle_condition = threading.Condition(self._video_lifecycle_lock)
            self._active_video_reconciliations = 0
            self._active_video_cancellations = 0
            self._video_cancel_operations: set[str] = set()
            self._video_close_condition = threading.Condition()
            self._video_close_started = False
            self._video_close_finished = False
            self._accepting_video_operations = True
            self._video_scheduler: VideoOperationScheduler | None = None
            self._video_scheduler_healthy = False
            # The plaintext feedback of one queued revision at a time, keyed by
            # operation id. The ledger stores only the digest binding; the bytes
            # ride from the HTTP request to the staging call and are never
            # persisted here -- the Skill's staging directory is the sole
            # durable home for them.
            self._video_revise_feedback: dict[str, str] = {}
            self._reload_configured_profiles()
            if self.video_worker is not None:
                self._start_video_scheduler()
        except BaseException:
            scheduler = getattr(self, "_video_scheduler", None)
            if scheduler is not None:
                scheduler.close()
            self.store.close()
            raise

    def close(self) -> None:
        with self._video_close_condition:
            while self._video_close_started and not self._video_close_finished:
                self._video_close_condition.wait()
            if self._video_close_finished:
                return
            self._video_close_started = True
        with self._video_lifecycle_condition:
            self._accepting_video_operations = False
            self._closed = True
            while self._active_video_reconciliations or self._active_video_cancellations:
                self._video_lifecycle_condition.wait()
        try:
            scheduler = self._video_scheduler
            if scheduler is not None:
                if not scheduler.close():
                    raise RuntimeError("video scheduler has not stopped")
            # Every callback has finished; whatever is left is plaintext of
            # reservations that never reached their staging call.
            with self._video_lifecycle_lock:
                self._video_revise_feedback.clear()
            self.vault = CredentialVault()
            # The OS keychain owns persistent keys; replacing the in-memory
            # Hermes vault on close does not remove provider credentials.
            self.store.close()
        except BaseException:
            with self._video_close_condition:
                self._video_close_started = False
                self._video_close_condition.notify_all()
            raise
        else:
            with self._video_close_condition:
                self._video_close_finished = True
                self._video_close_condition.notify_all()

    def _start_video_scheduler(self) -> bool:
        if self.video_worker is None or self._closed:
            return False
        if self._video_scheduler is not None:
            return self._video_scheduler_healthy and not self._video_scheduler.halted
        scheduler = VideoOperationScheduler(
            self.store, self._execute_video_operation, self._video_operation_finished)
        self._video_scheduler = scheduler
        self._video_scheduler_healthy = scheduler.start()
        return self._video_scheduler_healthy

    def __enter__(self) -> BridgeService:
        return self

    def __exit__(self, _type, _value, _traceback) -> None:
        self.close()

    def refresh_discovery(self, *, home: Path | None = None, path_value: str | None = None) -> list[dict]:
        self.discovery_results = discovery.discover(home, path_value)
        return list(self.discovery_results)

    def add_instance(self, kind: str, name: str, base_url: str, api_key: str | None = None,
                     *, provider_model: str | None = None,
                     provider_region: str | None = None,
                     response_mode: str | None = None,
                     tts_model: str | None = None,
                     tts_voice: str | None = None) -> dict:
        if kind == "builtin_video":
            provider_region = provider_region or "international"
            base_url = validate_builtin_video_provider(base_url, provider_region)
            fingerprint = self._key_fingerprint(api_key) if api_key else None
            instance = self.store.add_instance(
                kind, name, base_url, credential_fingerprint=fingerprint,
                provider_model=provider_model, provider_region=provider_region,
                response_mode=response_mode, tts_model=tts_model, tts_voice=tts_voice)
            if api_key:
                try:
                    self.builtin_vault.set(instance["instance_id"], api_key)
                except SecureStoreError as error:
                    # Do not leave a configured-but-unusable public identity.
                    self.store.retire_instance(instance["instance_id"], "Secure storage is unavailable.")
                    raise ValidationError(str(error)) from error
            return instance
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
            if instance["kind"] == "builtin_video":
                if instance.get("credential_fingerprint") == fingerprint:
                    try:
                        self.builtin_vault.set(instance_id, api_key)
                    except SecureStoreError as error:
                        raise ValidationError(str(error)) from error
                    return instance
                if instance.get("credential_fingerprint") is not None \
                        or self.store.has_instance_history(instance_id):
                    self.store.retire_instance(
                        instance_id,
                        "Provider key changed. Existing device grants require pairing again.")
                    try:
                        self.builtin_vault.clear(instance_id)
                    except SecureStoreError:
                        pass
                    replacement = self.store.add_instance(
                        instance["kind"], instance["name"], instance["base_url"],
                        credential_fingerprint=fingerprint,
                        provider_model=instance.get("provider_model"),
                        provider_region=instance.get("provider_region"),
                        response_mode=instance.get("response_mode"),
                        tts_model=instance.get("tts_model"),
                        tts_voice=instance.get("tts_voice"))
                    try:
                        self.builtin_vault.set(replacement["instance_id"], api_key)
                    except SecureStoreError as error:
                        self.store.retire_instance(replacement["instance_id"], "Secure storage is unavailable.")
                        raise ValidationError(str(error)) from error
                    return replacement
                try:
                    self.builtin_vault.set(instance_id, api_key)
                except SecureStoreError as error:
                    raise ValidationError(str(error)) from error
                try:
                    instance = self.store.set_credential_fingerprint(instance_id, fingerprint)
                except BaseException:
                    self.builtin_vault.clear(instance_id)
                    raise
                return instance
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

    def revoke_builtin_credential(self, instance_id: str) -> None:
        with self._operation_lock("instance:" + instance_id):
            instance = self.store.get_instance(instance_id)
            if instance.get("kind") != "builtin_video":
                raise ValidationError("This credential belongs to a different provider")
            # Retire first, then invalidate every paired device before deleting
            # the OS secret. Any storage failure leaves the old identity unusable.
            self.store.retire_instance(instance_id, "Built-in provider credential was revoked.")
            for connection in self.store.list_connections():
                if connection.get("instance_id") == instance_id and not connection.get("revoked_at"):
                    self.revoke_connection(connection["connection_id"])
            try:
                self.builtin_vault.clear(instance_id)
            except SecureStoreError as error:
                raise ValidationError(str(error)) from error

    def check_instance(self, instance_id: str) -> dict:
        with self._operation_lock("instance:" + instance_id):
            instance = self.store.get_instance(instance_id)
            if instance.get("retired_at"):
                raise ValidationError("This Agent identity is retired; pair with its replacement")
            if instance["kind"] == "builtin_video":
                ready = self.video_worker is not None and self._video_scheduler_healthy \
                    and not self._closed
                configured = self.builtin_vault.has(instance_id)
                return self.store.update_instance_check(
                    instance_id,
                    health="ready" if ready and configured else "needs_configuration",
                    detail=(("内置视频引擎与操作系统安全存储已就绪。"
                             if self.builtin_vault.persistent else
                             "内置视频引擎已就绪；API key 仅在本次启动期间有效。")
                            if ready and configured else
                            "请配置安全保存的供应商 API key，并确认本机视频引擎可用。"),
                    features={"task_bundle": ready and configured,
                              "video_operations": ready and configured,
                              "video_production": ready and configured,
                              "video_revision": False},
                    executable=ready and configured,
                )
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
        return self._remember_pair(payload)

    def create_lan_pair_payload(self, instance_id: str, address: str) -> dict[str, Any]:
        """Pair a tablet on the same network, pinned to this computer's certificate."""
        if self.lan_identity is None or self.lan_port is None:
            raise ValidationError("same-network connection is not enabled")
        url = validate_lan_url(f"https://{address}:{self.lan_port}")
        _, payload = self.store.create_pair_code(instance_id, url, self.lan_identity.sha256)
        return self._remember_pair(payload)

    def _remember_pair(self, payload: dict[str, Any]) -> dict[str, Any]:
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
        # Built-in production can hold connection/instance locks for a paid render.
        # Capability polling is a read-only snapshot and must stay responsive.
        authenticated = self.store.authenticate(instance_id, bearer)
        instance = self.store.get_instance(instance_id)
        if instance["kind"] == "builtin_video":
            operations = self._builtin_video_operations_available(instance_id, instance)
            features = dict(instance.get("features") or {})
            features.update({
                "run_submission": False, "run_stop": False,
                # These authenticated read routes are local projections owned
                # by the paired device. They do not require a provider key,
                # Node worker, or a healthy operation scheduler.
                "run_approval_response": False, "run_status": True,
                "task_bundle": operations, "artifacts": True,
                "video_revision": False, "video_operations": operations,
                "video_production": operations, "video_task_submission": operations,
            })
            return {"object": "padnote.agent.capabilities", "protocol_version": 1,
                    "bridge_id": self.store.bridge_id, "instance_id": instance_id,
                    "kind": "builtin_video", "name": instance["name"], "features": features}
        with self._authorized_connection(instance_id, bearer):
            with self._operation_lock("instance:" + instance_id):
                instance = self.store.get_instance(instance_id)
                available = bool(instance.get("executable")) and not instance.get("retired_at") \
                    and (self.vault.has(instance_id) if instance["kind"] == "hermes"
                         else self.builtin_vault.has(instance_id))
                features = dict(instance.get("features") or {})
                if not available:
                    for key in ("run_submission", "run_status", "run_stop", "run_approval_response"):
                        features[key] = False
                features["task_bundle"] = available
                features["artifacts"] = True
                # Candidate install is not yet recoverable as a versioned commit.
                # Keep the feature closed for every provider, including direct API callers.
                features["video_revision"] = False
                scheduler = self._video_scheduler
                features["video_operations"] = bool(
                    self.video_worker is not None and self._video_scheduler_healthy
                    and scheduler is not None and not scheduler.halted
                    and self._accepting_video_operations and not self._closed)
                if instance["kind"] == "builtin_video":
                    for key in ("run_submission", "run_status", "run_stop", "run_approval_response"):
                        features[key] = False
                    features["video_operations"] = bool(available and features["video_operations"])
                    features["video_production"] = features["video_operations"]
                    features["video_task_submission"] = features["video_operations"]
                    features["task_bundle"] = features["video_operations"]
                    features["run_status"] = available
                return {
                    "object": "padnote.agent.capabilities",
                    "protocol_version": 1,
                    "bridge_id": self.store.bridge_id,
                    "instance_id": instance_id,
                    "kind": instance["kind"],
                    "name": instance["name"],
                    "features": features,
                }

    def _builtin_video_operations_available(self, instance_id: str,
                                            instance: dict[str, Any] | None = None) -> bool:
        if instance is None:
            instance = self.store.get_instance(instance_id)
        if instance.get("kind") != "builtin_video" or instance.get("retired_at") \
                or not instance.get("executable"):
            return False
        try:
            if not self.builtin_vault.has(instance_id):
                return False
        except SecureStoreError:
            return False
        scheduler = self._video_scheduler
        return bool(self.video_worker is not None and self._video_scheduler_healthy
                    and scheduler is not None and not scheduler.halted
                    and self._accepting_video_operations and not self._closed)

    def submit_video_operation(self, instance_id: str, bearer: str, task_id: str,
                               idempotency_key: str, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        task_id = validate_identifier(task_id, "task_id")
        client_operation_id = validate_identifier(idempotency_key, "Idempotency-Key")
        if not isinstance(payload, dict) or not isinstance(payload.get("action"), str) \
                or not isinstance(payload.get("parameters"), dict):
            raise ValidationError("Video operation payload is invalid")
        action = payload.get("action")
        # Only a revision carries plaintext feedback, out-of-band from the
        # parameter projection the payload digest commits to. Any other action
        # carrying feedback would let unbound bytes ride past the digest, so it
        # is refused the same way the Skill refuses a feedback digest on a
        # non-revise binding.
        feedback = None
        if action == "revise":
            if set(payload) != {"action", "parameters", "feedback"}:
                raise ValidationError("Video operation payload is invalid")
            feedback = payload["feedback"]
            # Refuse blank, zero-width-only, oversized or unencodable feedback
            # before anything is reserved: a reservation consumes one of the
            # task's bounded operation slots and a retry needs a new key.
            try:
                if len(_bounded_feedback_bytes(feedback)) > _MAX_REVISE_FEEDBACK_BYTES:
                    raise ValidationError("Revision feedback is invalid")
            except VideoWorkerError as error:
                raise ValidationError("Revision feedback is invalid") from error
        elif set(payload) != {"action", "parameters"}:
            raise ValidationError("Video operation payload is invalid")
        try:
            parameters = normalize_parameters(action, payload.get("parameters"))
        except ValueError as error:
            raise ValidationError(str(error)) from error
        if feedback is not None:
            digest = hashlib.sha256(feedback.encode("utf-8")).hexdigest()
            if digest != parameters["feedback_sha256"]:
                raise ValidationError("Revision feedback does not match its digest")
        scheduler = self._video_scheduler
        connection = self.store.authenticate(instance_id, bearer)
        run = self.store.owned_run(connection, task_id)
        existing = self.store.video_operation_by_client_key(
            connection, task_id, client_operation_id)
        if existing is not None:
            if existing.get("action") != action \
                    or existing.get("payload_digest") != payload_digest(action, parameters):
                raise ConflictError("Video operation key was already used with different content")
            if existing.get("source_snapshot_digest") != run.get("source_snapshot_digest") \
                    or existing.get("video_binding") != run.get("video_binding"):
                raise ConflictError("Video operation key is bound to a different source snapshot")
            return 202, self._public_video_operation(existing, task_id)
        if action == "revise":
            # Authenticate ownership, then refuse before reserving a ledger row.
            raise ConflictError("Video revision is not available")
        instance = self.store.get_instance(instance_id)
        if instance.get("kind") == "builtin_video" \
                and not self._builtin_video_operations_available(instance_id, instance):
            raise ConflictError("Video operation worker is unavailable")
        if action == "produce":
            if instance.get("kind") != "builtin_video":
                raise ConflictError("Video production is available only on the built-in video workflow")
            # China-region TTS is the only production path that has been
            # validated end-to-end. Reject before reserving an operation or
            # dispatching any paid provider request.
            if instance.get("provider_region") != "china":
                raise ConflictError("Video production is currently available only in the China region")
            if self.video_worker is None:
                raise ConflictError("Built-in video engine is unavailable")
            try:
                current = self.video_worker.inspect(
                    task_id, run["video_binding"]["worker_task_id"])
            except VideoWorkerError as error:
                raise ConflictError("Approved video state could not be verified") from error
            approval = current.get("approval")
            if current.get("status") != "approved" or current.get("phase") != "approval_pending" \
                    or current.get("revision") != parameters["revision"] \
                    or current.get("review_sha256") != parameters["review_sha256"] \
                    or current.get("lesson_ir_sha256") != parameters["lesson_ir_sha256"] \
                    or current.get("event_cursor") != parameters["event_cursor"] \
                    or not isinstance(approval, dict) or "consumed_at" in approval \
                    or approval.get("revision") != parameters["revision"] \
                    or approval.get("review_sha256") != parameters["review_sha256"] \
                    or approval.get("lesson_ir_sha256") != parameters["lesson_ir_sha256"]:
                raise ConflictError("Video production does not match the current unused approval")
        if self.video_worker is None or scheduler is None or not self._video_scheduler_healthy \
                or scheduler.halted:
            raise ConflictError("Video operation worker is unavailable")
        task_root, binding = self._video_review_source(run)
        try:
            verify_request_binding(task_root, binding)
        except (ValidationError, ConflictError) as error:
            raise ConflictError("Video task request binding is unavailable") from error
        # This brief admission lock makes close atomic with reserve while never
        # blocking close or unrelated operations on worker/task execution locks.
        with self._video_lifecycle_lock:
            if not self._accepting_video_operations or self._closed:
                raise ConflictError("Video operations are shutting down")
            if scheduler is not self._video_scheduler or scheduler.halted \
                    or not self._video_scheduler_healthy:
                raise ConflictError("Video operation worker is unavailable")
            record, _created = self.store.reserve_video_operation(
                connection, task_id, client_operation_id, action, parameters,
                expected_source_snapshot_digest=run["source_snapshot_digest"],
                expected_video_binding=binding)
            if record.get("source_snapshot_digest") != run["source_snapshot_digest"] \
                    or record.get("video_binding") != binding:
                raise ConflictError("Video operation key is bound to a different source snapshot")
            if action == "revise" and record.get("status") == "queued":
                # The digest binding was checked before the reserve; the bytes
                # ride to the staging call under the same admission lock. Only a
                # still-queued record will be claimed and pop them, so a retry of
                # a running or settled revision must not park plaintext here.
                self._video_revise_feedback[record["operation_id"]] = feedback
            scheduler.wake()
        return 202, self._public_video_operation(record, task_id)

    def get_video_operation(self, instance_id: str, bearer: str, task_id: str,
                            operation_id: str) -> dict[str, Any]:
        task_id = validate_identifier(task_id, "task_id")
        operation_id = validate_identifier(operation_id, "operation_id")
        connection = self.store.authenticate(instance_id, bearer)
        record = self.store.owned_video_operation(connection, task_id, operation_id)
        return self._public_video_operation(record, task_id)

    def cancel_video_operation(self, instance_id: str, bearer: str, task_id: str,
                               operation_id: str) -> dict[str, Any]:
        task_id = validate_identifier(task_id, "task_id")
        operation_id = validate_identifier(operation_id, "operation_id")
        connection = self.store.authenticate(instance_id, bearer)
        record = self.store.cancel_queued_video_operation(connection, task_id, operation_id)
        # A cancelled queued revision can never reach its staging call, so its
        # plaintext would otherwise linger until close.
        with self._video_lifecycle_lock:
            self._video_revise_feedback.pop(operation_id, None)
        return self._public_video_operation(record, task_id)

    def cancel_running_video_operation(self, instance_id: str, bearer: str, task_id: str,
                                       operation_id: str) -> dict[str, Any]:
        """Persist a stop request, then ask the Skill to verify and stop its exact lease."""
        task_id = validate_identifier(task_id, "task_id")
        operation_id = validate_identifier(operation_id, "operation_id")
        with self._video_lifecycle_condition:
            if not self._accepting_video_operations or self._closed:
                raise ConflictError("Video cancellation is shutting down")
            if operation_id in self._video_cancel_operations or self._active_video_cancellations >= 1:
                raise ConflictError("A video cancellation control call is already active")
            self._video_cancel_operations.add(operation_id)
            self._active_video_cancellations += 1
        try:
            cancel_attempt_snapshot = None
            if self.video_worker is None:
                raise ConflictError("Video cancellation worker is unavailable")
            connection = self.store.authenticate(instance_id, bearer)
            existing = self.store.owned_video_operation(connection, task_id, operation_id)
            if existing.get("status") in {"cancelled", "succeeded", "failed"} \
                    and existing.get("cancel_request") is not None:
                return self._public_video_cancel_request(existing, task_id)
            operation, _created = self.store.request_running_video_cancel(
                connection, task_id, operation_id)
            if operation.get("status") in {"cancelled", "succeeded", "failed"}:
                return self._public_video_cancel_request(operation, task_id)
            # Re-read authorization and immutable source identity without taking
            # the worker's long execution locks. Skill independently rechecks
            # request/input identity before signaling its isolated process group.
            snapshot = self.store.snapshot()
            current_connection = snapshot.get("connections", {}).get(connection["connection_id"])
            current_instance = snapshot.get("instances", {}).get(instance_id)
            current_run = snapshot.get("runs", {}).get(task_id)
            if not isinstance(current_connection, dict) or current_connection.get("revoked_at") is not None \
                    or current_connection.get("device_id") != connection.get("device_id"):
                raise AuthorizationError("Device connection is invalid or revoked")
            if not isinstance(current_instance, dict) or current_instance.get("retired_at") is not None:
                raise ConflictError("Video task instance is unavailable")
            if not isinstance(current_run, dict) \
                    or current_run.get("owner_connection_id") != connection["connection_id"] \
                    or current_run.get("instance_id") != instance_id \
                    or current_run.get("source_snapshot_digest") != operation.get("source_snapshot_digest") \
                    or current_run.get("video_binding") != operation.get("video_binding"):
                raise ConflictError("Video operation source binding changed")
            task_root, binding = self._video_review_source(current_run)
            verify_request_binding(task_root, binding)
            # Last credential check directly before the bounded fixed-argv call.
            connection = self.store.authenticate(instance_id, bearer)
            current = self.store.owned_video_operation(connection, task_id, operation_id)
            if current.get("attempt_id") != operation.get("attempt_id") \
                    or current.get("cancel_request") != operation.get("cancel_request") \
                    or current.get("status") not in {"running", "unknown"}:
                return self._public_video_cancel_request(current, task_id)
            # Bind the receipt CAS to exactly the record passed to the Skill.
            cancel_attempt_snapshot = current
            if current_instance.get("kind") == "builtin_video":
                parameters = current.get("parameters", {})
                cancel_operation = {
                    "action": current["action"],
                    "operation_id": current["operation_id"],
                    "attempt_id": current["attempt_id"],
                    "payload_digest": current["payload_digest"],
                    "source_snapshot_digest": current["source_snapshot_digest"],
                }
                if current["action"] == "produce":
                    cancel_operation.update({
                        "review_sha256": parameters["review_sha256"],
                        "lesson_ir_sha256": parameters["lesson_ir_sha256"],
                        "allow_cloud_tts": True,
                    })
                cancel_request = {
                    "schema_version": 1, "action": "cancel",
                    "task_root": str(task_root),
                    "task_id": binding["worker_task_id"],
                    "request_sha256": binding["request_sha256"],
                    "revision": parameters["revision"],
                    "event_cursor": parameters["event_cursor"],
                    "operation": cancel_operation,
                }
                receipt = self.video_worker.run_builtin(
                    cancel_request, operation_id=current["operation_id"],
                    attempt_id=current["attempt_id"], timeout_seconds=120)
                if not _valid_cancel_result(receipt, current):
                    receipt = {"status": "unconfirmed", "reason": "invalid_receipt"}
                if receipt.get("status") == "unconfirmed" and receipt.get("reason") == "cancel_pending":
                    # The CLI has durably recorded this exact cancellation before
                    # Python terminates the matching platform-owned process tree.
                    if self.video_worker.terminate_builtin(
                            current["operation_id"], current["attempt_id"]):
                        receipt = self.video_worker.run_builtin(
                            cancel_request, operation_id=current["operation_id"],
                            attempt_id=current["attempt_id"], timeout_seconds=120)
                    else:
                        receipt = {"status": "unconfirmed", "reason": "worker_identity_unavailable"}
            else:
                receipt = self.video_worker.cancel_running_storyboard(
                    task_id, current["video_binding"]["worker_task_id"],
                    current["video_binding"]["request_sha256"], current)
            if receipt.get("status") == "verified_cancelled":
                try:
                    connection = self.store.authenticate(instance_id, bearer)
                    completed = self.store.finish_cancelled_video_operation(
                        connection, cancel_attempt_snapshot)
                    return self._public_video_cancel_request(
                        completed, task_id, control_inflight=False)
                except (ConflictError, AuthorizationError):
                    # A concurrently committed success or revoked credential wins;
                    # re-read only while still authorized and never guess a cancel.
                    connection = self.store.authenticate(instance_id, bearer)
                    try:
                        current = self.store.mark_video_cancel_unconfirmed(
                            connection, cancel_attempt_snapshot)
                    except (ConflictError, AuthorizationError):
                        current = self.store.owned_video_operation(connection, task_id, operation_id)
                    return self._public_video_cancel_request(current, task_id, control_inflight=False)
            try:
                connection = self.store.authenticate(instance_id, bearer)
                latest = self.store.mark_video_cancel_unconfirmed(
                    connection, cancel_attempt_snapshot)
            except (ConflictError, AuthorizationError):
                connection = self.store.authenticate(instance_id, bearer)
                latest = self.store.owned_video_operation(connection, task_id, operation_id)
                return self._public_video_cancel_request(latest, task_id, control_inflight=False)
            return self._public_video_cancel_request(latest, task_id, control_inflight=False)
        except VideoWorkerError:
            # A timeout or malformed worker reply provides no proof. Keep the
            # durable request and allow an explicit later receipt check.
            if not isinstance(cancel_attempt_snapshot, dict):
                raise
            connection = self.store.authenticate(instance_id, bearer)
            latest = self.store.mark_video_cancel_unconfirmed(
                connection, cancel_attempt_snapshot)
            return self._public_video_cancel_request(latest, task_id, control_inflight=False)
        finally:
            with self._video_lifecycle_condition:
                self._video_cancel_operations.discard(operation_id)
                self._active_video_cancellations -= 1
                self._video_lifecycle_condition.notify_all()

    def get_video_cancel_request(self, instance_id: str, bearer: str, task_id: str,
                                 operation_id: str) -> dict[str, Any]:
        connection = self.store.authenticate(instance_id, bearer)
        operation = self.store.owned_video_operation(connection, task_id, operation_id)
        if operation.get("cancel_request") is None:
            raise ConflictError("No running video cancellation has been requested")
        return self._public_video_cancel_request(operation, task_id)

    def _public_video_cancel_request(self, record: dict[str, Any], task_id: str, *,
                                     control_inflight: bool | None = None) -> dict[str, Any]:
        request = record.get("cancel_request")
        if not isinstance(request, dict):
            raise ConflictError("No running video cancellation has been requested")
        status = {
            "running": "requested", "unknown": "unconfirmed", "cancelled": "verified_cancelled",
            "succeeded": "too_late", "failed": "too_late",
        }.get(record.get("status"))
        if record.get("status") == "running":
            if control_inflight is None:
                with self._video_lifecycle_condition:
                    control_inflight = record["operation_id"] in self._video_cancel_operations
            status = "requested" if control_inflight else "unconfirmed"
        if status is None:
            raise ConflictError("Video cancellation status is unavailable")
        return {
            "object": "padnote.video.cancel_request", "protocol_version": 1,
            "operation_id": record["operation_id"], "task_id": task_id,
            "status": status, "requested_at": request["requested_at"],
            "updated_at": record["updated_at"],
        }

    @staticmethod
    def _public_video_operation(record: dict[str, Any], task_id: str) -> dict[str, Any]:
        result = copy.deepcopy(record.get("result"))
        if isinstance(result, dict):
            result["task_id"] = task_id
            if record.get("action") == "produce" and record.get("status") == "succeeded":
                parameters = record.get("parameters")
                binding = record.get("video_binding")
                attempt_id = record.get("attempt_id")
                if not isinstance(parameters, dict) or not isinstance(binding, dict) \
                        or not isinstance(attempt_id, str) \
                        or type(result.get("event_cursor")) is not int:
                    raise VideoWorkerError(
                        "worker_result_invalid", "Verified video receipt is unavailable", unknown=True)
                result["receipt"] = {
                    "operation_id": record["operation_id"],
                    "attempt_id": attempt_id,
                    "action": "produce",
                    "payload_digest": record["payload_digest"],
                    "source_snapshot_digest": record["source_snapshot_digest"],
                    "request_sha256": binding["request_sha256"],
                    "input_event_cursor": parameters["event_cursor"],
                    "result_event_cursor": result["event_cursor"],
                    "revision": parameters["revision"],
                    "review_sha256": parameters["review_sha256"],
                    "lesson_ir_sha256": parameters["lesson_ir_sha256"],
                    "allow_cloud_tts": True,
                }
        return {
            "object": "padnote.video.operation",
            "protocol_version": 1,
            "operation_id": record["operation_id"],
            "task_id": task_id,
            "client_operation_id": record["client_operation_id"],
            "action": record["action"],
            "status": record["status"],
            "created_at": record["created_at"],
            "updated_at": record["updated_at"],
            "result": result,
            "error": record.get("error"),
        }

    def _execute_video_operation(self, operation: dict[str, Any]) -> dict[str, Any]:
        worker = self.video_worker
        if worker is None:
            raise VideoWorkerError("worker_failed", "Video worker is unavailable")
        connection_id = operation.get("owner_connection_id")
        instance_id = operation.get("instance_id")
        task_id = operation.get("task_id")
        with self._operation_lock("connection:" + str(connection_id)):
            with self._operation_lock("instance:" + str(instance_id)):
                with self._operation_lock("task:" + str(task_id)):
                    snapshot = self.store.snapshot()
                    connections = snapshot.get("connections", {})
                    instances = snapshot.get("instances", {})
                    runs = snapshot.get("runs", {})
                    connection = connections.get(connection_id)
                    instance = instances.get(instance_id)
                    run = runs.get(task_id)
                    if not isinstance(connection, dict) or connection.get("revoked_at") is not None \
                            or connection.get("instance_id") != instance_id:
                        raise VideoWorkerError("authorization_revoked", "Video operation authorization is unavailable")
                    if not isinstance(instance, dict) or instance.get("retired_at") is not None:
                        raise VideoWorkerError("instance_retired", "Video task instance is unavailable")
                    if not isinstance(run, dict) or run.get("owner_connection_id") != connection_id \
                            or run.get("instance_id") != instance_id:
                        raise VideoWorkerError("run_unavailable", "Video task is unavailable")
                    if run.get("source_snapshot_digest") != operation.get("source_snapshot_digest"):
                        raise VideoWorkerError("source_changed", "Video task source changed")
                    if run.get("video_binding") != operation.get("video_binding"):
                        raise VideoWorkerError("binding_changed", "Video task binding changed")
                    try:
                        task_root, binding = self._video_review_source(run)
                    except ConflictError as error:
                        raise VideoWorkerError("source_changed", "Video task source changed") from error
                    try:
                        verify_request_binding(task_root, binding)
                    except (ValidationError, ConflictError) as error:
                        raise VideoWorkerError("binding_changed", "Video task request binding changed") from error
                    # Recheck immediately before the external action. A revocation
                    # after this point may race an already-started worker call.
                    latest = self.store.snapshot().get("connections", {}).get(connection_id)
                    if not isinstance(latest, dict) or latest.get("revoked_at") is not None:
                        raise VideoWorkerError("authorization_revoked", "Video operation authorization is unavailable")
                    action = operation.get("action")
                    parameters = operation.get("parameters")
                    if instance.get("kind") == "builtin_video":
                        if action == "revise":
                            raise VideoWorkerError(
                                "worker_failed", "Video revision is not available", unknown=False)
                        if action == "approve":
                            return worker.approve_bound(
                                task_id, binding["worker_task_id"], binding["request_sha256"],
                                parameters["revision"], parameters["event_cursor"],
                                parameters["review_sha256"], parameters["lesson_ir_sha256"],
                                operation_id=operation["operation_id"],
                                attempt_id=operation["attempt_id"],
                                payload_digest=operation["payload_digest"],
                                source_snapshot_sha256=operation["source_snapshot_digest"])
                        if action == "initialize":
                            return worker.initialize_exact(
                                task_id, binding["worker_task_id"], binding["request_sha256"],
                                operation_id=operation["operation_id"],
                                attempt_id=operation["attempt_id"],
                                payload_digest=operation["payload_digest"],
                                source_snapshot_sha256=operation["source_snapshot_digest"])
                        if action not in {"storyboard", "produce"}:
                            raise VideoWorkerError("worker_failed", "Unsupported built-in video action")
                        if action == "produce" and instance.get("provider_region") != "china":
                            raise VideoWorkerError(
                                "configuration_invalid",
                                "Video production is currently available only in the China region",
                                unknown=False)
                        provider_key = self.builtin_vault.get(instance_id)
                        if not provider_key:
                            raise VideoWorkerError(
                                "worker_failed", "Built-in video provider key is unavailable")
                        request = {
                            "schema_version": 1,
                            "action": action,
                            "task_root": str(task_root),
                            "task_id": binding["worker_task_id"],
                            "request_sha256": binding["request_sha256"],
                            "revision": parameters["revision"],
                            "event_cursor": parameters["event_cursor"],
                            "operation": {
                                "action": action,
                                "operation_id": operation["operation_id"],
                                "attempt_id": operation["attempt_id"],
                                "payload_digest": operation["payload_digest"],
                                "source_snapshot_digest": operation["source_snapshot_digest"],
                                **({
                                    "review_sha256": parameters["review_sha256"],
                                    "lesson_ir_sha256": parameters["lesson_ir_sha256"],
                                    "allow_cloud_tts": True,
                                } if action == "produce" else {}),
                            },
                            "provider": {
                                "base_url": instance["base_url"],
                                "model": instance.get("provider_model", "qwen-plus"),
                                "region": instance.get("provider_region", "international"),
                                "response_mode": instance.get("response_mode", "json_object"),
                                "tts_model": instance.get("tts_model", "qwen3-tts-instruct-flash"),
                                "tts_voice": instance.get("tts_voice", "Maia"),
                            },
                            "credential": {"api_key": provider_key},
                        }
                        timeout = 120 if action == "storyboard" else 1800
                        try:
                            result = worker.run_builtin(
                                request, operation_id=operation["operation_id"],
                                attempt_id=operation["attempt_id"], timeout_seconds=timeout)
                        except VideoWorkerError:
                            raise
                        except BaseException as error:
                            raise VideoWorkerError(
                                "worker_unknown", "Built-in video operation could not be confirmed",
                                unknown=True) from error
                        if not isinstance(result, dict):
                            raise VideoWorkerError(
                                "worker_result_invalid", "Built-in video result is invalid", unknown=True)
                        if result.get("ok") is not True:
                            uncertain = result.get("external_effect_possible") is True
                            code = "worker_unknown" if uncertain else "worker_failed"
                            raise VideoWorkerError(
                                code, "Built-in video operation failed", unknown=uncertain)
                        receipt_inspection = self._validate_builtin_receipt(
                            result, operation, binding, parameters)
                        # Read the persisted state back through the existing bounded
                        # inspector; the CLI summary alone never advances client state.
                        persisted = worker.inspect(task_id, binding["worker_task_id"])
                        if receipt_inspection is not None and persisted != receipt_inspection:
                            raise VideoWorkerError(
                                "worker_result_invalid", "Built-in video inspection changed", unknown=True)
                        return persisted
                    if action == "initialize":
                        return worker.initialize_exact(
                            task_id, binding["worker_task_id"], binding["request_sha256"],
                            operation_id=operation["operation_id"],
                            attempt_id=operation["attempt_id"],
                            payload_digest=operation["payload_digest"],
                            source_snapshot_sha256=operation["source_snapshot_digest"])
                    if action == "storyboard":
                        # Historical queued storyboard records have no cursor.
                        # They remain readable but are never guessed/replayed.
                        if not isinstance(parameters, dict) or "event_cursor" not in parameters:
                            raise VideoWorkerError("worker_failed", "Legacy storyboard operation lacks a cursor")
                        return worker.build_storyboard_exact(
                            task_id, binding["worker_task_id"], binding["request_sha256"],
                            parameters["revision"], parameters["event_cursor"],
                            operation_id=operation["operation_id"],
                            attempt_id=operation["attempt_id"],
                            payload_digest=operation["payload_digest"],
                            source_snapshot_sha256=operation["source_snapshot_digest"])
                    if action == "approve":
                        return worker.approve_bound(
                            task_id, binding["worker_task_id"], binding["request_sha256"],
                            parameters["revision"], parameters["event_cursor"],
                            parameters["review_sha256"], parameters["lesson_ir_sha256"],
                            operation_id=operation["operation_id"],
                            attempt_id=operation["attempt_id"],
                            payload_digest=operation["payload_digest"],
                            source_snapshot_sha256=operation["source_snapshot_digest"])
                    if action == "revise":
                        if not isinstance(parameters, dict) or set(parameters) != {
                                "revision", "event_cursor", "review_sha256",
                                "lesson_ir_sha256", "feedback_sha256"}:
                            raise VideoWorkerError("worker_failed",
                                                   "Revision parameters are unavailable")
                        with self._video_lifecycle_lock:
                            feedback = self._video_revise_feedback.pop(
                                operation["operation_id"], None)
                        if not isinstance(feedback, str):
                            # The bytes never survived to this claim (a restart
                            # after reserve, or a store-level claim cancel).
                            # Staging takes no lease and mutates only its own
                            # staging directory, so a failure here is a definite
                            # outcome the caller can retry, never an unknown one.
                            raise VideoWorkerError("worker_failed",
                                                   "Revision feedback is unavailable")
                        return worker.stage_revision(
                            task_id, binding["worker_task_id"], binding["request_sha256"],
                            parameters["revision"], parameters["event_cursor"],
                            parameters["review_sha256"], parameters["lesson_ir_sha256"],
                            feedback, parameters["feedback_sha256"],
                            operation_id=operation["operation_id"],
                            attempt_id=operation["attempt_id"],
                            payload_digest=operation["payload_digest"],
                            source_snapshot_sha256=operation["source_snapshot_digest"])
                    raise VideoWorkerError("worker_failed", "Unsupported queued video action")

    def _video_operation_finished(self, operation: dict[str, Any], record: dict[str, Any],
                                  *, artifact_proof: dict[str, Any] | None = None) -> None:
        instance = self.store.get_instance(operation["instance_id"])
        if instance.get("kind") != "builtin_video":
            return
        task_id = operation["task_id"]
        action = operation["action"]
        status = record.get("status")
        state_by_action = {
            "initialize": "initialized",
            "storyboard": "awaiting_storyboard_review",
            "approve": "approved",
            "produce": "completed",
        }
        updates: dict[str, Any] = {}
        if status == "succeeded":
            updates["video_workflow_state"] = state_by_action.get(action, "running")
            updates["video_workflow_error"] = None
            if action == "produce":
                current_run = self.store.snapshot().get("runs", {}).get(task_id, {})
                # Once the validated descriptors are pinned to this run, later
                # status reads never trust the mutable output directory again.
                if current_run.get("status") == "completed" and current_run.get("artifacts"):
                    return
                try:
                    if artifact_proof is None:
                        worker = self.video_worker
                        if worker is None:
                            raise VideoWorkerError("worker_failed", "Video artifact proof is unavailable")
                        binding = operation.get("video_binding")
                        if not isinstance(binding, dict):
                            raise VideoWorkerError("worker_result_invalid", "Video artifact proof is invalid",
                                                   unknown=True)
                        artifact_proof = worker.reconcile_exact(
                            task_id, binding["worker_task_id"], binding["request_sha256"], operation)
                    if not isinstance(artifact_proof, dict) \
                            or artifact_proof.get("outcome") != "verified_completed" \
                            or artifact_proof.get("result") != record.get("result"):
                        raise VideoWorkerError("worker_result_invalid", "Video artifact proof is invalid",
                                               unknown=True)
                    artifacts = public_artifacts(collect_artifacts(
                        self.store.tasks_dir / task_id, task_id,
                        video_manifest=artifact_proof.get("artifacts"),
                        worker_task_id=operation.get("video_binding", {}).get("worker_task_id"),
                        result_manifest_sha256=artifact_proof.get("result_manifest_sha256")))
                except (ValidationError, VideoWorkerError, KeyError, TypeError):
                    updates["status"] = "failed"
                    updates["video_workflow_state"] = "failed"
                    updates["video_workflow_error"] = "The completed video artifacts could not be verified."
                    updates["error"] = updates["video_workflow_error"]
                    updates["artifacts"] = []
                else:
                    updates["status"] = "completed"
                    updates["output"] = "视频配音与渲染已完成。"
                    updates["artifacts"] = artifacts
                    updates["error"] = None
        elif status == "unknown":
            updates["video_workflow_state"] = "outcome_unknown"
            updates["video_workflow_error"] = "Operation outcome could not be confirmed; inspect before retrying."
        elif status == "cancelled":
            updates.update(status="cancelled", video_workflow_state="cancelled",
                           video_workflow_error=None)
        elif status == "failed":
            updates.update(status="failed", video_workflow_state="failed",
                           video_workflow_error="The video operation failed.",
                           error="The video operation failed.")
        if updates:
            self.store.update_run(task_id, **updates)

    @staticmethod
    def _validate_builtin_receipt(result: dict[str, Any], operation: dict[str, Any],
                                  binding: dict[str, Any], parameters: dict[str, Any]
                                  ) -> dict[str, Any] | None:
        expected = {
            "operation_id", "attempt_id", "action", "payload_digest",
            "source_snapshot_digest", "request_sha256", "input_event_cursor",
            "result_event_cursor", "revision", "review_sha256", "lesson_ir_sha256",
        }
        produce = operation.get("action") == "produce"
        allowed_result = ({"ok", "inspection", "receipt", "artifacts"} if produce else {
            "ok", "task_id", "status", "phase", "event_cursor", "revision",
            "review_sha256", "lesson_ir_sha256", "receipt",
        })
        if set(result) != allowed_result:
            raise VideoWorkerError("worker_result_invalid", "Built-in video receipt is invalid", unknown=True)
        receipt = result.get("receipt")
        if result.get("ok") is not True or not isinstance(receipt, dict) \
                or set(receipt) - (expected | {"allow_cloud_tts"}) \
                or set(receipt) != expected | ({"allow_cloud_tts"} if operation.get("action") == "produce" else set()):
            raise VideoWorkerError("worker_result_invalid", "Built-in video receipt is invalid", unknown=True)
        inspection = result.get("inspection") if produce else result
        expected_task_id = binding.get("worker_task_id")
        if produce:
            if not isinstance(inspection, dict):
                raise VideoWorkerError("worker_result_invalid", "Built-in video inspection is invalid", unknown=True)
            try:
                _validate_inspection(inspection, expected_task_id)
            except Exception as error:
                raise VideoWorkerError("worker_result_invalid", "Built-in video inspection is invalid",
                                       unknown=True) from error
        if receipt.get("operation_id") != operation.get("operation_id") \
                or receipt.get("attempt_id") != operation.get("attempt_id") \
                or receipt.get("action") != operation.get("action") \
                or receipt.get("payload_digest") != operation.get("payload_digest") \
                or receipt.get("source_snapshot_digest") != operation.get("source_snapshot_digest") \
                or receipt.get("request_sha256") != binding.get("request_sha256") \
                or (inspection.get("task_id") if isinstance(inspection, dict)
                    else result.get("task_id")) != expected_task_id:
            raise VideoWorkerError("worker_result_invalid", "Built-in video receipt binding is invalid", unknown=True)
        input_cursor = parameters.get("event_cursor")
        if input_cursor is not None and receipt.get("input_event_cursor") != input_cursor:
            raise VideoWorkerError("worker_result_invalid", "Built-in video receipt cursor is invalid", unknown=True)
        if operation.get("action") == "storyboard":
            if receipt.get("revision") != parameters.get("revision") \
                    or any(not isinstance(receipt.get(name), str)
                           or not re.fullmatch(r"[a-f0-9]{64}", receipt[name])
                           for name in ("review_sha256", "lesson_ir_sha256")) \
                    or result.get("status") != "awaiting_storyboard_review" \
                    or result.get("phase") != "awaiting_approval" \
                    or result.get("revision") != receipt.get("revision") \
                    or result.get("review_sha256") != receipt.get("review_sha256") \
                    or result.get("lesson_ir_sha256") != receipt.get("lesson_ir_sha256"):
                raise VideoWorkerError("worker_result_invalid", "Built-in video receipt review binding is invalid", unknown=True)
        elif operation.get("action") == "produce":
            for name in ("revision", "review_sha256", "lesson_ir_sha256"):
                if receipt.get(name) != parameters.get(name):
                    raise VideoWorkerError("worker_result_invalid", "Built-in video receipt review binding is invalid", unknown=True)
            if inspection.get("status") != "completed" or inspection.get("phase") != "completed":
                raise VideoWorkerError("worker_result_invalid", "Built-in video production is incomplete", unknown=True)
            if type(inspection.get("event_cursor")) is not int \
                    or inspection["event_cursor"] != receipt.get("result_event_cursor"):
                raise VideoWorkerError("worker_result_invalid", "Video production cursor is invalid", unknown=True)
            if inspection.get("revision") != receipt.get("revision") \
                    or inspection.get("review_sha256") != receipt.get("review_sha256") \
                    or inspection.get("lesson_ir_sha256") != receipt.get("lesson_ir_sha256"):
                raise VideoWorkerError("worker_result_invalid", "Video production inspection is invalid", unknown=True)
            approval = inspection.get("approval")
            if not isinstance(approval, dict) or "consumed_at" not in approval \
                    or approval.get("revision") != receipt.get("revision") \
                    or approval.get("review_sha256") != receipt.get("review_sha256") \
                    or approval.get("lesson_ir_sha256") != receipt.get("lesson_ir_sha256"):
                raise VideoWorkerError("worker_result_invalid", "Video production approval is invalid", unknown=True)
            artifacts = result.get("artifacts")
            if not isinstance(artifacts, list) or not artifacts or any(
                    not isinstance(item, dict)
                    or set(item) != {"id", "role", "path", "media_type", "size_bytes", "sha256"}
                    or not isinstance(item.get("path"), str)
                    or type(item.get("size_bytes")) is not int or item["size_bytes"] < 0
                    or not isinstance(item.get("sha256"), str)
                    or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"])
                    for item in artifacts):
                raise VideoWorkerError("worker_result_invalid", "Video production artifacts are invalid", unknown=True)
            if len(artifacts) > 16:
                raise VideoWorkerError("worker_result_invalid", "Video production artifacts are invalid", unknown=True)
            artifact_ids = [item.get("id") for item in artifacts]
            artifact_paths = [item.get("path") for item in artifacts]
            if any(not isinstance(item.get("id"), str) or not item["id"]
                   or not isinstance(item.get("role"), str) or not item["role"]
                   or not isinstance(item.get("media_type"), str) or not item["media_type"]
                   or item["size_bytes"] > 2**40
                   or not item["path"] or item["path"].startswith(("/", "\\"))
                   or ".." in Path(item["path"]).parts
                   or "\\" in item["path"]
                   for item in artifacts) \
                    or len(set(artifact_ids)) != len(artifact_ids) \
                    or len(set(artifact_paths)) != len(artifact_paths):
                raise VideoWorkerError("worker_result_invalid", "Video production artifacts are invalid", unknown=True)
        if operation.get("action") == "produce" and receipt.get("allow_cloud_tts") is not True:
            raise VideoWorkerError("worker_result_invalid", "Video production consent is invalid", unknown=True)
        cursor = receipt.get("result_event_cursor")
        if type(cursor) is not int or cursor < 1 or cursor > 2**53 - 1 \
                or (inspection.get("event_cursor") if isinstance(inspection, dict)
                    else result.get("event_cursor")) != cursor:
            raise VideoWorkerError("worker_result_invalid", "Built-in video receipt cursor is invalid", unknown=True)
        return inspection if produce else None

    def reconcile_video_operation(self, instance_id: str, bearer: str, task_id: str,
                                  operation_id: str) -> dict[str, Any]:
        """Explicitly verify a durable unknown operation without replaying it."""
        task_id = validate_identifier(task_id, "task_id")
        operation_id = validate_identifier(operation_id, "operation_id")
        with self._video_lifecycle_condition:
            if not self._accepting_video_operations or self._closed:
                raise ConflictError("Video operation reconciliation is shutting down")
            self._active_video_reconciliations += 1
        try:
            initial = self.store.authenticate(instance_id, bearer)
            worker = self.video_worker
            with self._operation_lock("connection:" + initial["connection_id"]):
                with self._operation_lock("instance:" + instance_id):
                    with self._operation_lock("task:" + task_id):
                        connection = self.store.authenticate(instance_id, bearer)
                        if connection.get("connection_id") != initial.get("connection_id"):
                            raise AuthorizationError("Device credential identity changed")
                        expected = self.store.owned_video_operation(connection, task_id, operation_id)
                        if expected.get("status") in {"succeeded", "cancelled"}:
                            if expected.get("action") == "produce" and expected.get("status") == "succeeded":
                                self._video_operation_finished(expected, expected)
                                expected = self.store.owned_video_operation(connection, task_id, operation_id)
                            return self._public_video_operation(expected, task_id)
                        if expected.get("status") != "unknown":
                            raise ConflictError("Only unknown video operations can be reconciled")
                        if worker is None:
                            raise ConflictError("Video operation worker is unavailable")

                        snapshot = self.store.snapshot()
                        current_run = snapshot.get("runs", {}).get(task_id)
                        current_connection = snapshot.get("connections", {}).get(connection["connection_id"])
                        current_instance = snapshot.get("instances", {}).get(instance_id)
                        if not isinstance(current_connection, dict) or current_connection.get("revoked_at") is not None:
                            raise AuthorizationError("Device connection is invalid or revoked")
                        if not isinstance(current_instance, dict) or current_instance.get("retired_at") is not None:
                            raise ConflictError("Video task instance is unavailable")
                        if not isinstance(current_run, dict) \
                                or current_run.get("owner_connection_id") != connection["connection_id"] \
                                or current_run.get("instance_id") != instance_id:
                            raise ConflictError("Video task is unavailable")
                        if current_run.get("source_snapshot_digest") != expected.get("source_snapshot_digest") \
                                or current_run.get("video_binding") != expected.get("video_binding"):
                            raise ConflictError("Video operation source binding changed")
                        task_root, binding = self._video_review_source(current_run)
                        try:
                            verify_request_binding(task_root, binding)
                        except (ValidationError, ConflictError) as error:
                            raise ConflictError("Video task request binding is unavailable") from error
                        # Recheck authorization immediately before the read-only Skill call.
                        self.store.authenticate(instance_id, bearer)
                        try:
                            receipt = worker.reconcile_exact(
                                task_id, binding["worker_task_id"], binding["request_sha256"], expected)
                        except VideoWorkerError:
                            # A failed or malformed read-only check is not evidence
                            # of completion; preserve and return the durable unknown.
                            current_connection = self.store.authenticate(instance_id, bearer)
                            unchanged = self.store.owned_video_operation(
                                current_connection, task_id, operation_id)
                            return self._public_video_operation(unchanged, task_id)
                        if receipt.get("outcome") == "verified_cancelled":
                            if expected.get("action") not in {"storyboard", "produce"} \
                                    or expected.get("cancel_request") is None:
                                raise ConflictError("No persisted video stop request matches this receipt")
                            cancelled = self.store.finish_cancelled_video_operation(
                                connection, expected)
                            if cancelled.get("action") == "produce":
                                self._video_operation_finished(cancelled, cancelled)
                            return self._public_video_operation(cancelled, task_id)
                        if receipt.get("outcome") == "verified_completed":
                            # The CLI's receipt is trusted only after its complete
                            # envelope and result projection pass the adapter validators.
                            latest_connection = self.store.authenticate(instance_id, bearer)
                            latest_run = self.store.owned_run(latest_connection, task_id)
                            latest_root, latest_binding = self._video_review_source(latest_run)
                            verify_request_binding(latest_root, latest_binding)
                            if latest_run.get("source_snapshot_digest") != expected["source_snapshot_digest"] \
                                    or latest_binding != expected["video_binding"]:
                                raise ConflictError("Video operation source binding changed")
                            reconciled = self.store.reconcile_unknown_video_operation(
                                latest_connection, expected, receipt["result"])
                            if reconciled.get("action") == "produce":
                                self._video_operation_finished(
                                    reconciled, reconciled, artifact_proof=receipt)
                            return self._public_video_operation(reconciled, task_id)
                        # No evidence is an ordinary unsuccessful check. The
                        # durable unknown operation and its idempotency key remain.
                        latest = self.store.owned_video_operation(
                            connection, task_id, operation_id)
                        return self._public_video_operation(latest, task_id)
        finally:
            with self._video_lifecycle_condition:
                self._active_video_reconciliations -= 1
                self._video_lifecycle_condition.notify_all()

    def get_video_diagnostics(self, instance_id: str, bearer: str) -> dict[str, Any]:
        # Authentication is intentionally complete before configuration lookup
        # or any child process can start. Keep the existing connection ->
        # instance lock order so revocation cannot race a queued probe.
        with self._authorized_connection(instance_id, bearer):
            with self._operation_lock("instance:" + instance_id):
                if self.video_worker is None:
                    return not_configured_runtime_diagnostics()
                return self.video_worker.runtime_diagnostics()

    def submit_run(self, instance_id: str, bearer: str, idempotency_key: str,
                   payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        clean = self._validate_run_payload(payload, idempotency_key)
        with self._authorized_connection(instance_id, bearer) as connection:
            with self._operation_lock("instance:" + instance_id):
                instance = self.store.get_instance(instance_id)
                if instance["kind"] == "hermes":
                    self._hermes(instance_id)
                elif instance["kind"] == "builtin_video":
                    if not self._builtin_video_operations_available(instance_id, instance):
                        raise ConflictError("Built-in video engine is unavailable")
                    if clean.get("parent_task_id") or not clean.get("bundle_base64") \
                            or binding_from_submission(clean) is None:
                        raise ValidationError("The built-in video target accepts only a video task bundle")
                else:
                    raise ValidationError("This provider does not support task submission")
                lock_key = f"submit:{connection['connection_id']}:{instance_id}:{clean['client_task_id']}"
                with self._operation_lock(lock_key):
                    existing = self.store.find_idempotent_run(
                        connection, clean["client_task_id"], clean)
                    if existing and (existing["status"] != "submitting" or existing.get("upstream_run_id")):
                        return 202, self._public_run(existing)
                    if clean.get("parent_task_id"):
                        parent = self.store.owned_run(connection, clean["parent_task_id"])
                        root_id = parent.get("conversation_id", parent["task_id"])
                        root_dir = self.store.tasks_dir / root_id / "input"
                        if not parent.get("source_snapshot_digest") or \
                                input_snapshot_digest(root_dir) != parent["source_snapshot_digest"]:
                            if existing:
                                existing = self.store.update_run(
                                    existing["task_id"],
                                    error="The source snapshot changed; the uncertain submission was not resent.")
                                return 202, self._public_run(existing)
                            raise ConflictError("Parent source snapshot is missing or has changed")
                    return self._submit_run_locked(instance_id, connection, clean)

    def _submit_run_locked(self, instance_id: str, connection: dict[str, Any],
                           clean: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        parent_task_id = clean.get("parent_task_id") or None
        run, created = self.store.create_or_get_run(
            connection, clean["client_task_id"], clean, parent_task_id=parent_task_id)
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
            if run.get("parent_task_id"):
                task_dir = prepare_followup_directory(
                    self.store.tasks_dir, run["task_id"], run.get("conversation_id", run["parent_task_id"]))
            else:
                task_dir = prepare_task_directory(
                    self.store.tasks_dir, run["task_id"], clean,
                    clean.get("bundle_base64"), clean.get("bundle_sha256"))
            if not run.get("source_snapshot_digest"):
                run = self.store.update_run(
                    run["task_id"],
                    source_snapshot_digest=input_snapshot_digest(task_dir / "input"))
            if not run.get("parent_task_id") and clean.get("bundle_base64") \
                    and not run.get("video_binding"):
                binding = binding_from_submission(clean)
                if binding is not None:
                    run = self.store.update_run(run["task_id"], video_binding=binding)
        except ValidationError as error:
            failed = self.store.update_run(run["task_id"], status="failed", error=str(error))
            return 202, self._public_run(failed)

        instance = self.store.get_instance(instance_id)
        if instance["kind"] == "builtin_video":
            if not run.get("video_binding"):
                failed = self.store.update_run(
                    run["task_id"], status="failed",
                    error="The submitted task does not contain a supported video request.")
                return 202, self._public_run(failed)
            # Local video work starts only through the durable initialize operation.
            # This preserves the task/run identity and never submits to Hermes.
            run = self.store.update_run(
                run["task_id"], status="running", error=None,
                output="视频任务已接收，等待本机工作流初始化。")
            return 202, self._public_run(run)

        upstream_key = hashlib.sha256(
            f"{self.store.bridge_id}\n{connection['connection_id']}\n{instance_id}\n{clean['client_task_id']}".encode("utf-8")
        ).hexdigest()
        if created or not run.get("upstream_payload"):
            upstream_input = self._upstream_input(
                clean, task_dir,
                self.store.tasks_dir / run.get("conversation_id", run["task_id"]) / "input")
            upstream_payload = {"input": upstream_input}
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
                session_id=run.get("input_session_id"),
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
        connection = self.store.authenticate(instance_id, bearer)
        instance = self.store.get_instance(instance_id)
        if instance["kind"] == "builtin_video":
            # No model polling or worker lock: the local operation ledger/run
            # projection remains readable while production is in progress.
            return self._public_run(self.store.owned_run(connection, task_id))
        with self._authorized_connection(instance_id, bearer) as connection:
            with self._operation_lock("instance:" + instance_id):
                with self._operation_lock("task:" + task_id):
                    return self._get_run_locked(instance_id, connection, task_id)

    def _get_run_locked(self, instance_id: str, connection: dict[str, Any], task_id: str) -> dict[str, Any]:
        run = self.store.owned_run(connection, task_id)
        if run.get("upstream_run_id") and (run["status"] not in TERMINAL_STATUSES or not run.get("session_id")):
            try:
                response = self._hermes(instance_id).get_run(run["upstream_run_id"])
                response_id = response.get("run_id") or response.get("id")
                if response_id != run["upstream_run_id"]:
                    raise HermesError("upstream_invalid", "Hermes returned a different run", status=502)
                normalized = normalize_run(response)
                fields: dict[str, Any] = {}
                if run["status"] not in TERMINAL_STATUSES:
                    fields.update({key: value for key, value in normalized.items() if key != "session_id"})
                    if normalized["status"] == "completed":
                        fields["artifacts"] = public_artifacts(collect_artifacts(
                            self.store.tasks_dir / task_id, task_id))
                if normalized.get("session_id"):
                    fields["session_id"] = normalized["session_id"]
                if fields:
                    run = self.store.update_run(task_id, **fields)
            except (HermesError, ValidationError):
                if run["status"] not in TERMINAL_STATUSES:
                    raise
        return self._public_run(run)

    def get_video_review(self, instance_id: str, bearer: str,
                         task_id: str) -> dict[str, Any]:
        task_id = validate_identifier(task_id, "task_id")
        with self._authorized_connection(instance_id, bearer) as connection:
            with self._operation_lock("instance:" + instance_id):
                with self._operation_lock("task:" + task_id):
                    run = self.store.owned_run(connection, task_id)
                    task_root, binding = self._video_review_source(run)
                    if self.video_worker is None:
                        raise ConflictError("Video review is not configured")
                    try:
                        verify_request_binding(task_root, binding)
                        projection = self.video_worker.review(
                            task_id, binding["worker_task_id"])
                        snapshot = capture_review_snapshot(task_root, binding, projection)
                        result = public_review(task_id, projection)
                    except ValidationError as error:
                        raise ConflictError("Video review is unavailable; refresh the task") from error
                    self.store.update_run(task_id, video_review_snapshot=snapshot)
                    return result

    def get_video_preview(self, instance_id: str, bearer: str, task_id: str,
                          requested_preview_id: str):
        task_id = validate_identifier(task_id, "task_id")
        with self._authorized_connection(instance_id, bearer) as connection:
            with self._operation_lock("instance:" + instance_id):
                with self._operation_lock("task:" + task_id):
                    run = self.store.owned_run(connection, task_id)
                    task_root, binding = self._video_review_source(run)
                    snapshot = run.get("video_review_snapshot")
                    if not isinstance(snapshot, dict):
                        raise ConflictError("Video review is not available; refresh the review")
                    try:
                        verify_request_binding(task_root, binding)
                        return open_preview(task_root, binding, snapshot, requested_preview_id)
                    except ValidationError as error:
                        raise ConflictError("Video preview is unavailable; refresh the review") from error

    def _video_review_source(self, run: dict[str, Any]) -> tuple[Path, dict[str, Any]]:
        binding = run.get("video_binding")
        if not isinstance(binding, dict):
            # Legacy tasks are never retroactively bound from mutable workspace files.
            raise ConflictError("Video task has no verified submission binding")
        if not run.get("source_snapshot_digest"):
            raise ConflictError("Video task has no verified source snapshot")
        task_root = self.store.tasks_dir / run["task_id"]
        try:
            actual = input_snapshot_digest(task_root / "input")
        except ValidationError as error:
            raise ConflictError("Video task source snapshot is unavailable") from error
        if actual != run["source_snapshot_digest"]:
            raise ConflictError("Video task source snapshot has changed")
        return task_root, binding

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
            run = self.store.owned_run(connection, validate_identifier(task_id, "task_id"))
            instance = self.store.get_instance(instance_id)
            artifact_id = validate_identifier(artifact_id, "artifact_id")
            recorded = next((value for value in (run.get("artifacts") or [])
                             if value.get("id") == artifact_id), None)
            if instance.get("kind") == "builtin_video":
                if recorded is None:
                    raise FileNotFoundError(artifact_id)
                stream, item = open_pinned_builtin_video_artifact(
                    self.store.tasks_dir / task_id, task_id, recorded)
            else:
                stream, item = open_artifact(self.store.tasks_dir / task_id, task_id, artifact_id)
            if not recorded or any(recorded.get(key) != item.get(key)
                                   for key in ("name", "media_type", "size_bytes", "sha256")):
                stream.close()
                raise ConflictError("Artifact no longer matches the saved task result")
            return stream, item

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
        allowed_fields = {"client_task_id", "title", "input", "source", "bundle_base64",
                          "bundle_sha256", "parent_task_id"}
        if set(payload) - allowed_fields:
            raise ValidationError("request contains unsupported fields")
        parent_task_id = None
        if "parent_task_id" in payload:
            parent_task_id = validate_identifier(payload.get("parent_task_id"), "parent_task_id")
            if "bundle_base64" in payload or "bundle_sha256" in payload:
                raise ValidationError("follow-up requests cannot include a task bundle")
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
        if parent_task_id:
            clean["parent_task_id"] = parent_task_id
        if "bundle_base64" in payload or "bundle_sha256" in payload:
            clean["bundle_base64"] = payload.get("bundle_base64")
            clean["bundle_sha256"] = validate_text(payload.get("bundle_sha256"), "bundle_sha256", 64)
        return clean

    @staticmethod
    def _upstream_input(payload: dict[str, Any], task_dir: Path,
                        source_snapshot: Path | None = None) -> str:
        if payload.get("parent_task_id"):
            if source_snapshot is None:
                raise ValidationError("follow-up source snapshot is unavailable")
            return (
                f"Follow-up to PadNote task {payload['parent_task_id']}.\n"
                f"Read the original confirmed snapshot at {source_snapshot} as read-only reference.\n\n"
                f"{payload['title']}\n\n{payload['input']}\n\n"
                f"PadNote follow-up workspace: {task_dir}\n"
                "Write only this turn's deliverables under output/. The reference snapshot belongs to the parent turn. "
                "This path instruction is a workflow boundary, not an operating-system sandbox."
            )
        return (
            f"{payload['title']}\n\n{payload['input']}\n\n"
            f"PadNote task workspace: {task_dir}\n"
            "Read only the user-confirmed input in this workspace. Write deliverable files under output/. "
            "This path instruction is a workflow boundary, not an operating-system sandbox."
        )

    def _public_run(self, run: dict[str, Any]) -> dict[str, Any]:
        source = run.get("source")
        source_valid = isinstance(source, dict) and isinstance(source.get("note_id"), str) \
            and isinstance(source.get("note_revision"), (str, int))
        available = run.get("status") == "completed" and bool(run.get("session_id")) \
            and bool(run.get("source_snapshot_digest")) and source_valid \
            and not run.get("next_task_id")
        reason = "" if available else (
            "This turn is still running or has not completed." if run.get("status") != "completed"
            else "Hermes did not confirm a reusable session for this turn." if not run.get("session_id")
            else "This turn has no verified source snapshot."
            if not run.get("source_snapshot_digest") or not source_valid
            else "A follow-up turn already exists for this turn.")
        value = {
            "task_id": run["task_id"],
            "instance_id": run["instance_id"],
            "status": run["status"],
            "artifacts": run.get("artifacts") or [],
            "conversation_id": run.get("conversation_id", run["task_id"]),
            "parent_task_id": run.get("parent_task_id", ""),
            "followup_available": available,
            "followup_reason": reason,
        }
        for key in ("output", "error", "approval", "video_workflow_state", "video_workflow_error"):
            if run.get(key) is not None:
                value[key] = run[key]
        return value
