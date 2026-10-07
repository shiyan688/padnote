from __future__ import annotations

import hashlib
import json
import os
import re
import selectors
import signal
import stat
import subprocess
import sys
import tempfile
import time
import queue
import threading
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping

from .video_process import ProcessTreeError, WindowsJob, popen_tree_kwargs


_TASK_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
_SHA256 = re.compile(r"^[a-f0-9]{64}$")
_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
_NODE_VERSION = re.compile(rb"^v(\d{1,3})\.(\d{1,3})\.(\d{1,3})\r?\n?$")
_STATUSES = {
    "initialized", "awaiting_storyboard_review", "approved", "ready_to_render",
    "running", "interrupted", "failed", "cancelling", "cancelled", "completed",
}
_PHASES = {
    "idle", "storyboard", "awaiting_approval", "approval_pending", "approval_consumed",
    "tts_starting", "audio_ready", "render_starting", "completed", "cancelling", "cancelled",
}
_RECONCILE_REASONS = {
    "receipt_match", "receipt_missing", "legacy_no_receipt", "receipt_mismatch",
    "audit_malformed", "source_changed", "task_binding_changed", "active_lease",
    "approval_snapshot_invalid", "cancel_receipt_match", "binding_mismatch",
    "not_running", "active_lease_missing", "worker_identity_unavailable", "cancel_pending",
    "process_group_still_alive", "cancel_commit_unconfirmed",
}
_CANCEL_REASONS = {
    "cancel_receipt_match", "binding_mismatch", "task_binding_changed", "source_changed",
    "not_running", "active_lease_missing", "worker_identity_unavailable", "cancel_pending",
    "process_group_still_alive", "cancel_commit_unconfirmed",
}
_MAX_PACKAGE_BYTES = 256 * 1024
_MAX_REVIEW_STDOUT_BYTES = 2 * 1024 * 1024
_MAX_REVIEW_TOTAL_OUTPUT_BYTES = _MAX_REVIEW_STDOUT_BYTES + 64 * 1024
_MAX_REVIEW_SCENES = 60
_MAX_REVIEW_PREVIEW_BYTES = 8 * 1024 * 1024
_MAX_REVIEW_TOTAL_PREVIEW_BYTES = 128 * 1024 * 1024
_MAX_DIAGNOSTIC_STDOUT_BYTES = 16 * 1024
_MAX_REQUEST_BYTES = 1024 * 1024
_MAX_BUILTIN_REQUEST_BYTES = 64 * 1024
_MAX_BUILTIN_RESPONSE_BYTES = 1024 * 1024
# Operations that cannot leave task state ambiguous. A failure or timeout here is
# a definite outcome the ledger may report and let the caller retry, never an
# unknown one. Staging a revision belongs in this set for a structural reason: it
# writes only inside work/revise/<operation_id>/ and takes no execution lease, so
# there is no interval in which the adapter cannot say what happened.
_KNOWN_FAILURE_OPERATIONS = frozenset({"inspect", "diagnose", "revise-operation"})
_OWNING_CLI_ACTIONS = frozenset({"initialize-operation", "storyboard-operation",
                                 "approve-operation", "revise-operation"})
_UNCONFIRMED_JOB = object()
_BUILTIN_TTS_ERROR_CODES = frozenset({
    "tts_input_invalid", "tts_provider_request_failed", "tts_provider_http_error",
    "tts_provider_response_invalid", "tts_provider_response_too_large", "tts_audio_url_missing",
    "tts_audio_url_rejected", "tts_audio_download_failed", "tts_audio_download_http_error",
    "tts_audio_download_too_large", "tts_audio_format_invalid", "tts_audio_output_write_failed",
    "tts_adapter_failure", "tts_adapter_result_invalid", "tts_adapter_start_failed",
})
_BUILTIN_KNOWN_TTS_FAILURES = frozenset({"tts_input_invalid", "tts_adapter_start_failed"})
_BUILTIN_FAILURE_CODES = frozenset({
    "operation_failed", "external_effect_possible", "output_too_large", "invalid_request",
    "provider_not_allowed", "task_not_approved", "approval_missing", "operation_conflict",
    "configuration_invalid",
}) | _BUILTIN_TTS_ERROR_CODES
_BUILTIN_FAILURE_MESSAGES = {
    True: "Provider outcome is uncertain; inspect before any retry",
    False: "Built-in video operation failed",
    "output_too_large": "Built-in video result exceeded its output limit",
}
# The revision feedback bound, kept in step with MAX_REVISE_FEEDBACK_BYTES in the
# Skill's revise-candidate.ts. The adapter refuses oversize or mismatched feedback
# before spawning and the Skill refuses it again; the two must not disagree about
# the bound, or one side would accept what the other rejects.
_MAX_REVISE_FEEDBACK_BYTES = 16 * 1024
# The exact staging receipt the Skill's revise-operation entry returns. Kept in
# step with ReviseOperationResult in scripts/revise-operation.ts and with
# _REVISE_RESULT_FIELDS in video_operations.py; a test asserts the three agree, so
# a change on one side cannot silently stop matching the others.
_REVISE_RESULT_FIELDS = {
    "schema_version", "operation_id", "attempt_id", "task_id", "action",
    "payload_digest", "source_snapshot_digest", "request_sha256", "input_event_cursor",
    "revision", "review_sha256", "lesson_ir_sha256", "feedback_sha256", "candidate_path",
}
_REVISE_CANDIDATE_NAME = "candidate-lesson-ir.json"
_REVIEW_STABLE_STATUSES = _STATUSES - {"initialized", "running", "cancelling"}
_SCENE_ID = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
_VISUAL_KINDS = {
    "title", "formula_steps", "concept_map", "process", "comparison",
    "annotated_source", "quantity_change",
}
_DIAGNOSTIC_CHECKS = (
    "worker_modules", "storyboard_browser", "render_browser", "ffmpeg", "ffprobe", "tts",
)
_DIAGNOSTIC_STATUSES = {"available", "missing", "unchecked", "not_configured"}
_DIAGNOSTIC_REASONS = {
    "modules_resolved", "module_missing", "installed_executable_found", "executable_missing",
    "configuration_unavailable", "adapter_not_configured", "adapter_path_invalid",
    "adapter_present_unverified", "worker_not_configured",
}


def _is_windows() -> bool:
    """Isolated platform predicate so tests can emulate Windows safely."""
    return os.name == "nt"


def _is_posix() -> bool:
    return os.name == "posix"


class VideoWorkerError(RuntimeError):
    """A path-free, credential-free worker error safe to expose to a caller."""

    def __init__(self, code: str, message: str, *, unknown: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.unknown = unknown


@dataclass(frozen=True)
class VideoWorkerConfig:
    """Immutable computer-admin paths. No request or tablet field can alter these."""

    node_path: Path
    skill_root: Path
    tasks_root: Path

    def __post_init__(self) -> None:
        try:
            for name in ("node_path", "skill_root", "tasks_root"):
                value = getattr(self, name)
                if not isinstance(value, Path):
                    raise ValueError("configured paths must be pathlib paths")
                _validate_canonical_path(value)
            _require_regular_executable(self.node_path)
            _require_directory(self.skill_root)
            _require_directory(self.tasks_root)
            _require_skill_install(self.skill_root)
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as error:
            raise VideoWorkerError("invalid_configuration", "Video worker configuration is invalid") from error
        except Exception as error:
            raise VideoWorkerError("invalid_configuration", "Video worker configuration is invalid") from error


@dataclass(frozen=True)
class VideoWorkerDiagnostic:
    configuration_valid: bool
    node_version: str | None
    reason: str | None


class VideoWorker:
    """Fixed, one-shot CLI adapter for safe video-review operations only."""

    ACTION_TIMEOUT_SECONDS = 120.0
    REVIEW_TIMEOUT_SECONDS = 15.0
    INITIALIZE_TIMEOUT_SECONDS = 15.0
    RECONCILE_TIMEOUT_SECONDS = 15.0
    DIAGNOSTIC_TIMEOUT_SECONDS = 15.0
    VERSION_TIMEOUT_SECONDS = 5.0
    CLEANUP_RESERVE_SECONDS = 1.25
    TERMINATE_GRACE_SECONDS = 0.35
    MAX_STDOUT_BYTES = 128 * 1024
    MAX_STDERR_BYTES = 64 * 1024
    MAX_TOTAL_OUTPUT_BYTES = 160 * 1024
    READ_CHUNK_BYTES = 16 * 1024

    def __init__(self, config: VideoWorkerConfig) -> None:
        if not isinstance(config, VideoWorkerConfig):
            raise VideoWorkerError("invalid_configuration", "Video worker configuration is invalid")
        if os.name not in {"posix", "nt"}:
            raise VideoWorkerError(
                "unsupported_platform", "Local video worker process-tree control is unsupported on this platform")
        self._config = config
        self._identities = _path_identities(config.node_path, config.skill_root, config.tasks_root)
        self._active_jobs: dict[tuple[str, str], WindowsJob | None] = {}
        self._active_jobs_lock = threading.Lock()

    def diagnose(self) -> VideoWorkerDiagnostic:
        """Check configured paths and Node version; this does not establish runtime readiness."""
        try:
            self._verify_configuration()
            deadline = time.monotonic() + self.VERSION_TIMEOUT_SECONDS
            stdout, _stderr, code = self._run_process(
                [str(self._config.node_path), "--version"], self._config.skill_root,
                self._environment(self._config.tasks_root), deadline, "diagnose")
            if code != 0:
                return VideoWorkerDiagnostic(False, None, "node_check_failed")
            match = _NODE_VERSION.fullmatch(stdout)
            if not match:
                return VideoWorkerDiagnostic(False, None, "node_version_invalid")
            version = tuple(int(part) for part in match.groups())
            if version < (22, 22, 0):
                return VideoWorkerDiagnostic(False, ".".join(map(str, version)), "node_version_too_old")
            return VideoWorkerDiagnostic(True, ".".join(map(str, version)), None)
        except VideoWorkerError as error:
            return VideoWorkerDiagnostic(False, None, error.code)

    def inspect(self, server_task_id: str, expected_worker_task_id: str) -> dict[str, Any]:
        return self._call("inspect", server_task_id, expected_worker_task_id)

    def initialize_exact(self, server_task_id: str, expected_worker_task_id: str,
                         expected_request_sha256: str, *, operation_id: str | None = None,
                         attempt_id: str | None = None, payload_digest: str | None = None,
                         source_snapshot_sha256: str | None = None) -> dict[str, Any]:
        """Explicitly initialize a task only when its immutable request matches."""
        _validate_id(server_task_id)
        _validate_id(expected_worker_task_id)
        _validate_sha256(expected_request_sha256)
        self._verify_configuration()
        task_root = self._task_root(server_task_id)
        try:
            request_bytes = _read_regular_file_bytes(task_root / "request.json", _MAX_REQUEST_BYTES)
            request = _parse_single_json(request_bytes)
            if request.get("task_id") != expected_worker_task_id:
                raise ValueError("request identity does not match")
            if hashlib.sha256(request_bytes).hexdigest() != expected_request_sha256:
                raise ValueError("request digest does not match")
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as error:
            raise VideoWorkerError("invalid_request", "Video task request could not be verified",
                                   unknown=False) from error

        metadata = _validate_operation_metadata(
            operation_id, attempt_id, payload_digest, source_snapshot_sha256)
        deadline = time.monotonic() + self.INITIALIZE_TIMEOUT_SECONDS
        self._require_node_version(deadline)
        try:
            if metadata is None:
                result = self._invoke_action(
                    "init-exact", task_root, deadline,
                    "--task-id", expected_worker_task_id,
                    "--request-sha256", expected_request_sha256,
                    operation="init-exact")
            else:
                result = self._invoke_action(
                    "initialize-operation", task_root, deadline,
                    "--task-id", expected_worker_task_id,
                    "--request-sha256", expected_request_sha256,
                    *metadata, operation="initialize-operation")
            _validate_inspection(result, expected_worker_task_id)
            return result
        except VideoWorkerError as error:
            raise VideoWorkerError(error.code, "Video initialization result could not be verified",
                                   unknown=True) from error
        except BaseException as error:
            raise VideoWorkerError("worker_result_invalid", "Video initialization result could not be verified",
                                   unknown=True) from error

    def build_storyboard_exact(self, server_task_id: str, expected_worker_task_id: str,
                               expected_request_sha256: str, revision: int,
                               event_cursor: int, *, operation_id: str | None = None,
                               attempt_id: str | None = None, payload_digest: str | None = None,
                               source_snapshot_sha256: str | None = None) -> dict[str, Any]:
        """Build a storyboard only from the exact persisted task and event version."""
        _validate_revision(revision)
        _validate_event_cursor(event_cursor, increment=2)
        task_root, deadline, current = self._exact_mutation_preflight(
            server_task_id, expected_worker_task_id, expected_request_sha256)
        metadata = _validate_operation_metadata(
            operation_id, attempt_id, payload_digest, source_snapshot_sha256)
        current_revision = current.get("revision")
        if current["event_cursor"] != event_cursor:
            raise VideoWorkerError("preflight_state_conflict", "Video task changed before storyboard generation")
        if current["status"] == "initialized":
            expected_revision = 1
            valid_phase = current["phase"] == "idle" and current_revision is None
        elif current["status"] == "awaiting_storyboard_review":
            valid_phase = current["phase"] == "awaiting_approval" and type(current_revision) is int
            expected_revision = current_revision + 1 if valid_phase else -1
            if valid_phase and expected_revision > 2**53 - 1:
                valid_phase = False
        else:
            valid_phase = False
            expected_revision = -1
        if not valid_phase or revision != expected_revision:
            raise VideoWorkerError("preflight_state_conflict", "Video task is not ready for this storyboard revision")
        try:
            if metadata is None:
                result = self._invoke_action(
                    "storyboard-exact", task_root, deadline,
                    "--task-id", expected_worker_task_id,
                    "--request-sha256", expected_request_sha256,
                    "--revision", str(revision),
                    "--event-cursor", str(event_cursor),
                    operation="storyboard-exact")
            else:
                result = self._invoke_action(
                    "storyboard-operation", task_root, deadline,
                    "--task-id", expected_worker_task_id,
                    "--request-sha256", expected_request_sha256,
                    "--revision", str(revision),
                    "--event-cursor", str(event_cursor),
                    *metadata, operation="storyboard-operation")
            _validate_inspection(result, expected_worker_task_id)
            if result["status"] != "awaiting_storyboard_review" \
                    or result["phase"] != "awaiting_approval" \
                    or result.get("revision") != revision \
                    or result["event_cursor"] != event_cursor + 2:
                raise VideoWorkerError("worker_result_invalid", "Video storyboard result could not be verified")
            return result
        except VideoWorkerError as error:
            raise VideoWorkerError(error.code, "Video storyboard result could not be verified",
                                   unknown=True) from error
        except BaseException as error:
            raise VideoWorkerError("worker_result_invalid", "Video storyboard result could not be verified",
                                   unknown=True) from error

    def approve_bound(self, server_task_id: str, expected_worker_task_id: str,
                      expected_request_sha256: str, revision: int, event_cursor: int,
                      review_sha256: str, lesson_ir_sha256: str, *,
                      operation_id: str | None = None, attempt_id: str | None = None,
                      payload_digest: str | None = None,
                      source_snapshot_sha256: str | None = None) -> dict[str, Any]:
        """Approve only the exact currently reviewed revision and event cursor."""
        _validate_revision(revision)
        _validate_event_cursor(event_cursor, increment=1)
        _validate_sha256(review_sha256)
        _validate_sha256(lesson_ir_sha256)
        task_root, deadline, current = self._exact_mutation_preflight(
            server_task_id, expected_worker_task_id, expected_request_sha256)
        metadata = _validate_operation_metadata(
            operation_id, attempt_id, payload_digest, source_snapshot_sha256)
        if current["status"] != "awaiting_storyboard_review" \
                or current["phase"] != "awaiting_approval" \
                or current["event_cursor"] != event_cursor \
                or current.get("revision") != revision \
                or current.get("review_sha256") != review_sha256 \
                or current.get("lesson_ir_sha256") != lesson_ir_sha256:
            raise VideoWorkerError("preflight_binding_mismatch", "Current storyboard no longer matches the review")
        try:
            if metadata is None:
                result = self._invoke_action(
                    "approve-bound", task_root, deadline,
                    "--task-id", expected_worker_task_id,
                    "--request-sha256", expected_request_sha256,
                    "--revision", str(revision),
                    "--event-cursor", str(event_cursor),
                    "--review-sha256", review_sha256,
                    "--lesson-ir-sha256", lesson_ir_sha256,
                    operation="approve-bound")
            else:
                result = self._invoke_action(
                    "approve-operation", task_root, deadline,
                    "--task-id", expected_worker_task_id,
                    "--request-sha256", expected_request_sha256,
                    "--revision", str(revision),
                    "--event-cursor", str(event_cursor),
                    "--review-sha256", review_sha256,
                    "--lesson-ir-sha256", lesson_ir_sha256,
                    *metadata, operation="approve-operation")
            _validate_inspection(result, expected_worker_task_id)
            approval = result.get("approval")
            if result["status"] != "approved" or result["phase"] != "approval_pending" \
                    or result.get("revision") != revision \
                    or result.get("review_sha256") != review_sha256 \
                    or result.get("lesson_ir_sha256") != lesson_ir_sha256 \
                    or result["event_cursor"] != event_cursor + 1 \
                    or not isinstance(approval, dict) \
                    or approval.get("revision") != revision \
                    or approval.get("review_sha256") != review_sha256 \
                    or approval.get("lesson_ir_sha256") != lesson_ir_sha256:
                raise VideoWorkerError("worker_result_invalid", "Video approval result could not be verified")
            return result
        except VideoWorkerError as error:
            raise VideoWorkerError(error.code, "Video approval result could not be verified",
                                   unknown=True) from error
        except BaseException as error:
            raise VideoWorkerError("worker_result_invalid", "Video approval result could not be verified",
                                   unknown=True) from error

    def reconcile_exact(self, server_task_id: str, expected_worker_task_id: str,
                        expected_request_sha256: str, operation: Mapping[str, Any]
                        ) -> dict[str, Any]:
        """Read a Skill receipt and return its bounded reconciliation envelope."""
        _validate_id(server_task_id)
        _validate_id(expected_worker_task_id)
        _validate_sha256(expected_request_sha256)
        self._verify_configuration()
        task_root = self._task_root(server_task_id)
        metadata = _validate_reconcile_operation(
            operation, expected_worker_task_id, expected_request_sha256)
        try:
            request_bytes = _read_regular_file_bytes(task_root / "request.json", _MAX_REQUEST_BYTES)
            request = _parse_single_json(request_bytes)
            if request.get("task_id") != expected_worker_task_id \
                    or hashlib.sha256(request_bytes).hexdigest() != expected_request_sha256:
                raise ValueError("request binding does not match")
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as error:
            raise VideoWorkerError("invalid_request", "Video task request could not be verified",
                                   unknown=False) from error
        deadline = time.monotonic() + self.RECONCILE_TIMEOUT_SECONDS
        self._require_node_version(deadline)
        # Keep this byte-for-byte aligned with the Skill's strict CLI grammar:
        # cursor and optional review binding precede the operation receipt fields.
        options = ["--task-id", expected_worker_task_id,
                   "--request-sha256", expected_request_sha256,
                   "--event-cursor", str(metadata["event_cursor"])]
        if metadata["revision"] is not None:
            options.extend(("--revision", str(metadata["revision"])))
        if metadata["review_sha256"] is not None:
            options.extend(("--review-sha256", metadata["review_sha256"],
                            "--lesson-ir-sha256", metadata["lesson_ir_sha256"]))
        if metadata["action"] == "produce":
            options.extend(("--allow-cloud-tts", "true"))
        options.extend(("--operation-id", metadata["operation_id"],
                        "--attempt-id", metadata["attempt_id"],
                        "--action", metadata["action"],
                        "--payload-digest", metadata["payload_digest"],
                        "--source-snapshot-sha256", metadata["source_snapshot_digest"]))
        try:
            result = self._invoke_action("reconcile-operation", task_root, deadline,
                                         *options, operation="reconcile-operation")
            _validate_reconcile_envelope(result, metadata, expected_worker_task_id,
                                         expected_request_sha256)
            return result
        except VideoWorkerError:
            raise
        except BaseException as error:
            raise VideoWorkerError("reconcile_invalid", "Video operation receipt could not be verified",
                                   unknown=False) from error

    def cancel_running_storyboard(self, server_task_id: str, expected_worker_task_id: str,
                                  expected_request_sha256: str, operation: Mapping[str, Any]
                                  ) -> dict[str, Any]:
        """Ask the Skill to stop only the process bound to this storyboard attempt."""
        _validate_id(server_task_id)
        _validate_id(expected_worker_task_id)
        _validate_sha256(expected_request_sha256)
        self._verify_configuration()
        task_root = self._task_root(server_task_id)
        metadata = _validate_reconcile_operation(
            operation, expected_worker_task_id, expected_request_sha256)
        if metadata["action"] != "storyboard" or metadata["revision"] is None:
            raise VideoWorkerError("invalid_operation_binding", "Video cancellation binding is invalid")
        cancel_request = operation.get("cancel_request")
        if not isinstance(cancel_request, Mapping) \
                or cancel_request.get("attempt_id") != metadata["attempt_id"]:
            raise VideoWorkerError("invalid_operation_binding", "Video cancellation binding is invalid")
        deadline = time.monotonic() + self.RECONCILE_TIMEOUT_SECONDS
        self._require_node_version(deadline)
        options = [
            "--task-id", expected_worker_task_id,
            "--request-sha256", expected_request_sha256,
            "--event-cursor", str(metadata["event_cursor"]),
            "--revision", str(metadata["revision"]),
            "--operation-id", metadata["operation_id"],
            "--attempt-id", metadata["attempt_id"],
            "--action", "storyboard",
            "--payload-digest", metadata["payload_digest"],
            "--source-snapshot-sha256", metadata["source_snapshot_digest"],
        ]
        try:
            result = self._invoke_action("cancel-operation", task_root, deadline,
                                         *options, operation="cancel-operation")
            _validate_cancel_envelope(result, metadata)
            if _is_windows() and result["status"] == "unconfirmed" \
                    and result["reason"] == "cancel_pending":
                # First CLI invocation durably wrote the exact cancellation
                # request. Only now ask the original call's Job Object to stop
                # its full tree, then let a fresh CLI verify and commit terminal
                # state. The cancellation CLI itself is not registered as owner.
                if not self.terminate_builtin(metadata["operation_id"], metadata["attempt_id"]):
                    return result
                result = self._invoke_action("cancel-operation", task_root, deadline,
                                             *options, operation="cancel-operation")
                _validate_cancel_envelope(result, metadata)
            return result
        except VideoWorkerError:
            raise
        except BaseException as error:
            raise VideoWorkerError("cancel_invalid", "Video cancellation could not be verified",
                                   unknown=True) from error

    def stage_revision(self, server_task_id: str, expected_worker_task_id: str,
                       expected_request_sha256: str, revision: int, event_cursor: int,
                       review_sha256: str, lesson_ir_sha256: str, feedback: str,
                       expected_feedback_sha256: str, *, operation_id: str, attempt_id: str,
                       payload_digest: str, source_snapshot_sha256: str) -> dict[str, Any]:
        """Stage one revision candidate for exactly the revision the caller observed.

        This is the only revision action that mutates nothing but its own
        operation-exclusive staging directory: it takes no execution lease, writes
        no task state and never touches output/, so a failure is always a definite
        outcome rather than an unknown one. Status and phase are deliberately not
        preflighted here even though the Skill requires
        awaiting_storyboard_review/awaiting_approval -- the Skill owns that check,
        and duplicating it would replace its exact refusal reason with a coarser
        local one. The feedback is re-hashed against the digest the caller's
        operation reserved, so the bytes the generator will consume are the bytes
        the payload digest already committed to.
        """
        _validate_revision(revision)
        _validate_event_cursor(event_cursor)
        _validate_sha256(review_sha256)
        _validate_sha256(lesson_ir_sha256)
        _validate_sha256(expected_feedback_sha256)
        feedback_bytes = _bounded_feedback_bytes(feedback)
        if hashlib.sha256(feedback_bytes).hexdigest() != expected_feedback_sha256:
            raise VideoWorkerError("preflight_binding_mismatch",
                                   "Video revision feedback does not match the operation binding")
        _validate_id(server_task_id)
        _validate_id(expected_worker_task_id)
        _validate_sha256(expected_request_sha256)
        self._verify_configuration()
        task_root = self._task_root(server_task_id)
        try:
            request_bytes = _read_regular_file_bytes(task_root / "request.json", _MAX_REQUEST_BYTES)
            request = _parse_single_json(request_bytes)
            if request.get("task_id") != expected_worker_task_id \
                    or hashlib.sha256(request_bytes).hexdigest() != expected_request_sha256:
                raise ValueError("request binding does not match")
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as error:
            raise VideoWorkerError("invalid_request", "Video task request could not be verified",
                                   unknown=False) from error
        metadata = _validate_operation_metadata(
            operation_id, attempt_id, payload_digest, source_snapshot_sha256)
        # Every other action can fall back to a legacy, binding-free call. A
        # revision cannot: the receipt names an operation the ledger reserved, so
        # an absent binding is a refusal rather than an older record.
        if metadata is None:
            raise VideoWorkerError("invalid_operation_binding", "Video operation binding is invalid")
        binding = {
            "operation_id": operation_id, "attempt_id": attempt_id,
            "payload_digest": payload_digest, "source_snapshot_digest": source_snapshot_sha256,
            "request_sha256": expected_request_sha256, "event_cursor": event_cursor,
            "revision": revision, "review_sha256": review_sha256,
            "lesson_ir_sha256": lesson_ir_sha256,
            "feedback_sha256": expected_feedback_sha256,
        }
        deadline = time.monotonic() + self.ACTION_TIMEOUT_SECONDS
        self._require_node_version(deadline)
        # Keep this byte-for-byte aligned with the Skill's strict 11-option
        # grammar; the feedback digest sits between the review binding and the
        # operation metadata, so it cannot simply be appended.
        options = ["--task-id", expected_worker_task_id,
                   "--request-sha256", expected_request_sha256,
                   "--revision", str(revision),
                   "--event-cursor", str(event_cursor),
                   "--review-sha256", review_sha256,
                   "--lesson-ir-sha256", lesson_ir_sha256,
                   "--feedback-sha256", expected_feedback_sha256]
        try:
            result = self._invoke_action(
                "revise-operation", task_root, deadline, *options, *metadata,
                operation="revise-operation", stdin_bytes=feedback_bytes)
            _validate_revise_staging(result, binding, expected_worker_task_id)
            return result
        except VideoWorkerError as error:
            # Staging writes only its own work/revise/<operation_id>/, so a failure
            # is definite -- unless the child could not be stopped or reaped, in
            # which case it may still be writing and the outcome is not known.
            raise VideoWorkerError(error.code, "Video revision staging could not be verified",
                                   unknown=error.code == "worker_cleanup_failed") from error
        except Exception as error:
            raise VideoWorkerError("worker_result_invalid", "Video revision staging could not be verified",
                                   unknown=False) from error

    def _exact_mutation_preflight(self, server_task_id: str, expected_worker_task_id: str,
                                  expected_request_sha256: str
                                  ) -> tuple[Path, float, dict[str, Any]]:
        _validate_id(server_task_id)
        _validate_id(expected_worker_task_id)
        _validate_sha256(expected_request_sha256)
        self._verify_configuration()
        task_root = self._task_root(server_task_id)
        try:
            request_bytes = _read_regular_file_bytes(task_root / "request.json", _MAX_REQUEST_BYTES)
            request = _parse_single_json(request_bytes)
            if request.get("task_id") != expected_worker_task_id \
                    or hashlib.sha256(request_bytes).hexdigest() != expected_request_sha256:
                raise ValueError("request binding does not match")
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as error:
            raise VideoWorkerError("invalid_request", "Video task request could not be verified",
                                   unknown=False) from error
        deadline = time.monotonic() + self.ACTION_TIMEOUT_SECONDS
        self._require_node_version(deadline)
        try:
            current = self._invoke_action("inspect", task_root, deadline, operation="inspect")
            _validate_inspection(current, expected_worker_task_id)
            return task_root, deadline, current
        except VideoWorkerError as error:
            raise VideoWorkerError(error.code, "Video task could not be verified before the action",
                                   unknown=False) from error
        except BaseException as error:
            raise VideoWorkerError("preflight_failed", "Video task could not be verified before the action",
                                   unknown=False) from error

    def review(self, server_task_id: str, expected_worker_task_id: str) -> dict[str, Any]:
        """Read a bounded, validated storyboard projection without mutation preflight."""
        try:
            return self._call("review", server_task_id, expected_worker_task_id)
        except VideoWorkerError as error:
            # Review is read-only, including cleanup failures; callers can safely
            # retry after observing an error. Never expose child diagnostic text.
            raise VideoWorkerError(
                error.code, "Video review could not be verified", unknown=False) from error

    def runtime_diagnostics(self) -> dict[str, Any]:
        """Inspect fixed local dependencies without launching a browser or renderer."""
        try:
            self._verify_configuration()
            with tempfile.TemporaryDirectory(prefix="padnote-video-diagnostics-") as private_tmp:
                private_tmp_path = Path(private_tmp).resolve(strict=True)
                tasks_root = self._config.tasks_root.resolve(strict=True)
                if private_tmp_path == tasks_root or tasks_root in private_tmp_path.parents:
                    raise VideoWorkerError(
                        "diagnostic_unavailable", "Video diagnostics could not be verified")
                env = self._environment(self._config.tasks_root)
                # Node/tsx writes a compile cache. Keep it in this private OS
                # temporary directory rather than the persistent task store.
                env["TMPDIR"] = str(private_tmp_path)
                deadline = time.monotonic() + self.DIAGNOSTIC_TIMEOUT_SECONDS
                self._require_node_version(deadline, env)
                script = self._config.skill_root / "scripts/diagnose-runtime.ts"
                argv = [str(self._config.node_path), "--import", "tsx", str(script)]
                stdout, _stderr, returncode = self._run_process(
                    argv, self._config.skill_root, env, deadline, "diagnostics")
                if returncode != 0 or len(stdout) > _MAX_DIAGNOSTIC_STDOUT_BYTES:
                    raise VideoWorkerError("diagnostic_unavailable", "Video diagnostics could not be verified")
                result = _parse_single_json(stdout)
                _validate_runtime_diagnostics(result)
                return result
        except VideoWorkerError as error:
            code = error.code
            if code in {"diagnostic_timeout", "diagnostic_invalid", "diagnostic_unavailable"}:
                safe_code = code
            elif code == "worker_timeout":
                safe_code = "diagnostic_timeout"
            elif code in {"worker_unavailable", "node_unavailable", "unsupported_platform",
                          "invalid_configuration"}:
                safe_code = "diagnostic_unavailable"
            else:
                safe_code = "diagnostic_invalid"
            raise VideoWorkerError(safe_code, "Video diagnostics could not be verified", unknown=False) from error
        except BaseException as error:
            raise VideoWorkerError("diagnostic_invalid", "Video diagnostics could not be verified",
                                   unknown=False) from error

    def storyboard(self, server_task_id: str, expected_worker_task_id: str,
                   revision: int) -> dict[str, Any]:
        _validate_revision(revision)
        return self._call("storyboard", server_task_id, expected_worker_task_id,
                          "--revision", str(revision))

    def approve_exact(self, server_task_id: str, expected_worker_task_id: str,
                      revision: int, review_sha256: str,
                      lesson_ir_sha256: str) -> dict[str, Any]:
        _validate_revision(revision)
        _validate_sha256(review_sha256)
        _validate_sha256(lesson_ir_sha256)
        return self._call("approve-exact", server_task_id, expected_worker_task_id,
                          "--revision", str(revision),
                          "--review-sha256", review_sha256,
                          "--lesson-ir-sha256", lesson_ir_sha256)

    def run_builtin(self, request: Mapping[str, Any], *, operation_id: str,
                    attempt_id: str, timeout_seconds: float = 1800) -> dict[str, Any]:
        """Run the fixed builtin engine with a bounded, secret-safe JSON pipe.

        The request is never copied to argv, environment variables, or task
        files. Any failure after process creation is treated as an unknown
        external outcome so callers cannot blindly replay TTS or rendering.
        """
        if not isinstance(request, Mapping):
            raise VideoWorkerError("invalid_request", "Video engine request is invalid")
        _validate_uuid(operation_id)
        _validate_uuid(attempt_id)
        if type(timeout_seconds) not in (int, float) or not 120 <= timeout_seconds <= 7200:
            raise VideoWorkerError("invalid_request", "Video engine timeout is invalid")
        try:
            payload = json.dumps(dict(request), ensure_ascii=False, allow_nan=False,
                                 separators=(",", ":")).encode("utf-8")
        except (TypeError, ValueError, UnicodeError) as error:
            raise VideoWorkerError("invalid_request", "Video engine request is invalid") from error
        if not payload or len(payload) > _MAX_BUILTIN_REQUEST_BYTES:
            raise VideoWorkerError("invalid_request", "Video engine request exceeds its size limit")
        self._verify_configuration()
        identity = (operation_id, attempt_id)
        register_job = request.get("action") != "cancel"
        reserved = register_job and self._reserve_operation_job(identity)
        try:
            deadline = time.monotonic() + timeout_seconds
            self._require_node_version(deadline)
            script = self._config.skill_root / "scripts/builtin-engine.ts"
            argv = [str(self._config.node_path), "--import", "tsx", str(script)]
            stdout, _stderr, returncode = self._run_process(
                argv, self._config.skill_root, self._environment(self._config.tasks_root),
                deadline, "builtin", stdin_bytes=payload,
                operation_identity=(operation_id, attempt_id) if register_job else None)
            if len(stdout) > _MAX_BUILTIN_RESPONSE_BYTES:
                raise VideoWorkerError("worker_result_invalid", "Video engine result could not be verified",
                                       unknown=True)
            try:
                result = _parse_single_json(stdout)
            except VideoWorkerError as error:
                raise VideoWorkerError("worker_result_invalid", "Video engine result could not be verified",
                                       unknown=True) from error
            if not isinstance(result, dict) or type(result.get("ok")) is not bool:
                raise ValueError("engine response must use the builtin result envelope")
            if returncode != 0:
                code, external_effect_possible = _validate_builtin_failure(result)
                if code in _BUILTIN_TTS_ERROR_CODES:
                    if not external_effect_possible and code not in _BUILTIN_KNOWN_TTS_FAILURES:
                        raise VideoWorkerError("worker_result_invalid",
                                               "Video engine result could not be verified", unknown=True)
                    raise VideoWorkerError(code, "Built-in video provider operation failed",
                                           unknown=external_effect_possible)
                if not external_effect_possible and code in {
                    "operation_failed", "invalid_request", "provider_not_allowed",
                    "task_not_approved", "approval_missing", "operation_conflict",
                    "configuration_invalid",
                }:
                    raise VideoWorkerError(code,
                                           "Video engine rejected the request before external work",
                                           unknown=False)
                raise VideoWorkerError("worker_result_invalid", "Video engine result could not be verified",
                                       unknown=True)
            if result.get("ok") is False:
                code, external_effect_possible = _validate_builtin_failure(result)
                if code in _BUILTIN_TTS_ERROR_CODES:
                    if not external_effect_possible and code not in _BUILTIN_KNOWN_TTS_FAILURES:
                        raise VideoWorkerError("worker_result_invalid",
                                               "Video engine result could not be verified", unknown=True)
                    raise VideoWorkerError(code, "Built-in video provider operation failed",
                                           unknown=external_effect_possible)
                if code in {"operation_failed", "external_effect_possible"}:
                    raise VideoWorkerError(code, "Video engine operation failed",
                                           unknown=external_effect_possible)
                raise VideoWorkerError("worker_result_invalid", "Video engine result could not be verified",
                                       unknown=True)
            return result
        except VideoWorkerError as error:
            raise VideoWorkerError(error.code, "Video engine result could not be verified",
                                   unknown=error.unknown) from error
        except Exception as error:
            raise VideoWorkerError("worker_result_invalid", "Video engine result could not be verified",
                                   unknown=True) from error
        finally:
            if reserved:
                self._release_operation_job(identity)

    def terminate_builtin(self, operation_id: str, attempt_id: str) -> bool:
        """Stop only the live Job Object registered for this exact attempt."""
        _validate_uuid(operation_id)
        _validate_uuid(attempt_id)
        identity = (operation_id, attempt_id)
        with self._active_jobs_lock:
            job = self._active_jobs.get(identity)
            if not isinstance(job, WindowsJob):
                return False
            if not _is_windows():
                return False
            try:
                job.terminate()
                return True
            except OSError as error:
                raise VideoWorkerError("worker_cleanup_failed", "Video worker process tree could not be stopped",
                                       unknown=True) from error
    def _call(self, action: str, server_task_id: str, expected_worker_task_id: str,
              *fixed_options: str) -> dict[str, Any]:
        if action not in {"inspect", "review", "storyboard", "approve-exact"}:
            raise VideoWorkerError("unsupported_action", "Video worker action is unsupported")
        _validate_id(server_task_id)
        _validate_id(expected_worker_task_id)
        self._verify_configuration()
        task_root = self._task_root(server_task_id)
        timeout = self.REVIEW_TIMEOUT_SECONDS if action == "review" else self.ACTION_TIMEOUT_SECONDS
        deadline = time.monotonic() + timeout
        self._require_node_version(deadline)
        if action not in {"inspect", "review"}:
            try:
                current = self._invoke_action("inspect", task_root, deadline, operation="inspect")
                if current.get("task_id") != expected_worker_task_id:
                    raise VideoWorkerError("preflight_identity_mismatch", "Video task identity changed before the action")
                _validate_inspection(current, expected_worker_task_id)
                self._validate_mutation_preflight(
                    action, current, fixed_options, expected_worker_task_id)
            except VideoWorkerError as error:
                # The mutating command has not been sent, even if inspect cleanup
                # itself was uncertain; callers must not treat this as an action result.
                raise VideoWorkerError(error.code, str(error), unknown=False) from error
            except BaseException as error:
                raise VideoWorkerError("preflight_failed", "Video task could not be verified before the action") from error
        try:
            result = self._invoke_action(action, task_root, deadline, *fixed_options,
                                         operation=action)
            if action == "inspect":
                _validate_inspection(result, expected_worker_task_id)
            elif action == "review":
                _validate_review_projection(result, expected_worker_task_id)
            else:
                expected_status = "awaiting_storyboard_review" if action == "storyboard" else "approved"
                expected_phase = "awaiting_approval" if action == "storyboard" else "approval_pending"
                _validate_summary(result, expected_worker_task_id, expected_status, expected_phase)
            return result
        except VideoWorkerError as error:
            if action != "inspect" and not error.unknown:
                raise VideoWorkerError(error.code, str(error), unknown=True) from error
            raise
        except BaseException as error:
            # Never surface child stderr, exception text, command paths, or request content.
            raise VideoWorkerError(
                "worker_result_invalid", "Video worker result could not be verified",
                unknown=action != "inspect") from error

    def _invoke_action(self, action: str, task_root: Path, deadline: float,
                       *fixed_options: str, operation: str,
                       stdin_bytes: bytes | None = None) -> dict[str, Any]:
        worker = self._config.skill_root / "scripts/task-worker.ts"
        argv = [str(self._config.node_path), "--import", "tsx", str(worker), action, str(task_root)]
        argv.extend(fixed_options)
        # Keep the worker inside this adapter's process group; do not let its CLI
        # create a detached nested process group for storyboard rendering.
        argv.append("--internal-worker")
        # Only mutations own the attempt's process tree. Reconciliation is
        # read-only and cancellation targets the already registered owner.
        owning = action in _OWNING_CLI_ACTIONS
        operation_identity = _operation_identity_from_options(fixed_options) if owning else None
        if owning and operation_identity is None:
            raise VideoWorkerError("invalid_operation_binding", "Video operation binding is invalid")
        reserved = self._reserve_operation_job(operation_identity) if operation_identity is not None else False
        try:
            process_options = {} if operation_identity is None else {"operation_identity": operation_identity}
            # Callers without a payload keep the original call shape, so wrappers
            # around _run_process need not accept a new keyword argument.
            if stdin_bytes is None:
                stdout, _stderr, returncode = self._run_process(
                    argv, self._config.skill_root, self._environment(task_root), deadline, operation,
                    **process_options)
            else:
                stdout, _stderr, returncode = self._run_process(
                    argv, self._config.skill_root, self._environment(task_root), deadline, operation,
                    stdin_bytes=stdin_bytes, **process_options)
        finally:
            if reserved:
                self._release_operation_job(operation_identity)
        if returncode != 0:
            raise VideoWorkerError("worker_failed", "Video worker failed",
                                   unknown=operation not in _KNOWN_FAILURE_OPERATIONS | {"reconcile-operation"})
        return _parse_single_json(stdout)

    def _reserve_operation_job(self, identity: tuple[str, str]) -> bool:
        """Reserve a Windows Job slot before starting an owning CLI action."""
        if not _is_windows():
            return False
        with self._active_jobs_lock:
            if identity in self._active_jobs:
                raise VideoWorkerError("worker_busy", "Video operation attempt is already active")
            self._active_jobs[identity] = None
        return True

    def _release_operation_job(self, identity: tuple[str, str]) -> None:
        """Remove only this call's still-unclaimed reservation."""
        with self._active_jobs_lock:
            if self._active_jobs.get(identity) is None:
                self._active_jobs.pop(identity, None)

    @staticmethod
    def _validate_mutation_preflight(action: str, current: dict[str, Any],
                                     fixed_options: tuple[str, ...],
                                     expected_worker_task_id: str) -> None:
        if current["task_id"] != expected_worker_task_id:
            raise VideoWorkerError("preflight_identity_mismatch", "Video task identity changed before the action")
        if action == "storyboard":
            # Failure/interruption recovery remains an explicit future action;
            # this adapter never runs ensure/recover against those states.
            if current["status"] not in {"initialized", "awaiting_storyboard_review"}:
                raise VideoWorkerError("preflight_state_conflict", "Video task is not ready for storyboard generation")
            return
        if action != "approve-exact" or len(fixed_options) != 6:
            raise VideoWorkerError("unsupported_action", "Video worker action is unsupported")
        revision = int(fixed_options[1])
        review_sha256 = fixed_options[3]
        lesson_ir_sha256 = fixed_options[5]
        if current["status"] != "awaiting_storyboard_review" \
                or current.get("revision") != revision \
                or current.get("review_sha256") != review_sha256 \
                or current.get("lesson_ir_sha256") != lesson_ir_sha256:
            raise VideoWorkerError("preflight_binding_mismatch", "Current storyboard no longer matches the approved review")

    def _require_node_version(self, deadline: float,
                              env: Mapping[str, str] | None = None) -> None:
        stdout, _stderr, code = self._run_process(
            [str(self._config.node_path), "--version"], self._config.skill_root,
            env if env is not None else self._environment(self._config.tasks_root),
            deadline, "diagnose")
        match = _NODE_VERSION.fullmatch(stdout)
        if code != 0 or not match or tuple(int(part) for part in match.groups()) < (22, 22, 0):
            raise VideoWorkerError("node_unavailable", "Configured Node.js 22.22 or newer is required")

    def _verify_configuration(self) -> None:
        try:
            _validate_canonical_path(self._config.node_path)
            _validate_canonical_path(self._config.skill_root)
            _validate_canonical_path(self._config.tasks_root)
            _require_regular_executable(self._config.node_path)
            _require_directory(self._config.skill_root)
            _require_directory(self._config.tasks_root)
            _require_skill_install(self._config.skill_root)
            if _path_identities(self._config.node_path, self._config.skill_root,
                                self._config.tasks_root) != self._identities:
                raise ValueError("configured filesystem object changed")
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as error:
            raise VideoWorkerError("invalid_configuration", "Video worker configuration is unavailable") from error

    def _task_root(self, server_task_id: str) -> Path:
        candidate = self._config.tasks_root / server_task_id
        try:
            _validate_canonical_path(candidate)
            _require_directory(candidate)
        except (OSError, ValueError, TypeError) as error:
            raise VideoWorkerError("invalid_task_root", "Video task is unavailable") from error
        if candidate.parent != self._config.tasks_root or candidate.name != server_task_id:
            raise VideoWorkerError("invalid_task_root", "Video task is unavailable")
        return candidate

    def _environment(self, temporary_root: Path) -> dict[str, str]:
        work = temporary_root / "work"
        try:
            _require_directory(work)
        except FileNotFoundError:
            work = self._config.tasks_root
        path_entries = [str(self._config.node_path.parent)]
        if _is_windows():
            system_root = os.environ.get("SystemRoot", r"C:\Windows")
            path_entries.extend((str(Path(system_root) / "System32"), str(Path(system_root))))
        else:
            path_entries.extend(("/usr/bin", "/bin"))
        result = {
            "PATH": os.pathsep.join(path_entries),
            "LANG": "C",
            "LC_ALL": "C",
            "DISABLE_TELEMETRY": "true",
            "TMPDIR": str(work),
        }
        if _is_windows():
            result["SystemRoot"] = system_root
            result["WINDIR"] = system_root
            result["TEMP"] = str(work)
            result["TMP"] = str(work)
            reader_python = Path(sys.executable).resolve(strict=True)
            reader_script = Path(__file__).with_name("windows_reader.py").resolve(strict=True)
            reader_root = self._config.tasks_root.resolve(strict=True)
            _validate_canonical_path(reader_python)
            _validate_canonical_path(reader_script)
            _validate_canonical_path(reader_root)
            _require_regular_executable(reader_python)
            _require_regular_file(reader_script)
            _require_directory(reader_root)
            result["PADNOTE_WINDOWS_READER_PYTHON"] = str(reader_python)
            result["PADNOTE_WINDOWS_READER_SCRIPT"] = str(reader_script)
            result["PADNOTE_WINDOWS_READER_ROOT"] = str(reader_root)
        return result

    def _run_process(self, argv: list[str], cwd: Path, env: Mapping[str, str],
                     deadline: float, operation: str,
                     stdin_bytes: bytes | None = None,
                     operation_identity: tuple[str, str] | None = None) -> tuple[bytes, bytes, int]:
        if time.monotonic() >= deadline - self.CLEANUP_RESERVE_SECONDS:
            raise VideoWorkerError("worker_timeout", "Video worker operation timed out",
                                   unknown=operation not in _KNOWN_FAILURE_OPERATIONS)
        process: subprocess.Popen | None = None
        job: WindowsJob | None = None
        try:
            process = subprocess.Popen(
                argv, cwd=str(cwd), env=dict(env),
                stdin=subprocess.PIPE if stdin_bytes is not None else subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=False,
                bufsize=0, **popen_tree_kwargs(),
            )
            if _is_windows():
                # popen_tree_kwargs creates the child suspended. Assignment and
                # kill-on-close configuration therefore precede its first code.
                job = WindowsJob(process)
                if operation_identity is not None:
                    with self._active_jobs_lock:
                        if operation_identity not in self._active_jobs:
                            raise ProcessTreeError("operation attempt reservation disappeared")
                        if self._active_jobs[operation_identity] is not None:
                            raise ProcessTreeError("operation attempt reservation is not available")
                        self._active_jobs[operation_identity] = job
        except (OSError, ValueError, ProcessTreeError) as error:
            cleanup_error: BaseException | None = None
            if process is not None:
                if job is not None:
                    try:
                        self._terminate_group(process, deadline, job)
                        job_cleanup_confirmed = True
                    except VideoWorkerError as stop_error:
                        cleanup_error = stop_error
                        job_cleanup_confirmed = False
                else:
                    try:
                        process.kill()
                        process.wait(timeout=max(0.0, min(
                            deadline - time.monotonic(), self.CLEANUP_RESERVE_SECONDS)))
                        job_cleanup_confirmed = True
                    except (OSError, subprocess.TimeoutExpired) as stop_error:
                        cleanup_error = stop_error
                        job_cleanup_confirmed = False
                # Popen owns three pipe handles even when native Job creation or
                # registration fails before the normal reader threads start.
                # Close them here so startup failures do not leak OS handles.
                for stream in (process.stdin, process.stdout, process.stderr):
                    if stream is not None:
                        try:
                            stream.close()
                        except OSError:
                            pass
                if job is not None:
                    try:
                        job.close()
                    except OSError as close_error:
                        cleanup_error = cleanup_error or close_error
                        job_cleanup_confirmed = False
                if cleanup_error is not None and operation_identity is not None and job is not None:
                    with self._active_jobs_lock:
                        if self._active_jobs.get(operation_identity) in (None, job):
                            self._active_jobs[operation_identity] = _UNCONFIRMED_JOB
            if cleanup_error is not None:
                raise VideoWorkerError(
                    "worker_cleanup_failed", "Video worker process tree could not be confirmed stopped",
                    unknown=True) from cleanup_error
            raise VideoWorkerError("worker_unavailable", "Video worker could not be started",
                                   unknown=operation not in _KNOWN_FAILURE_OPERATIONS) from error
        assert process is not None
        assert process.stdout is not None and process.stderr is not None
        stdout = bytearray()
        stderr = bytearray()
        if operation == "review":
            stdout_limit, total_limit = _MAX_REVIEW_STDOUT_BYTES, _MAX_REVIEW_TOTAL_OUTPUT_BYTES
        elif operation == "builtin":
            stdout_limit, total_limit = _MAX_BUILTIN_RESPONSE_BYTES, _MAX_BUILTIN_RESPONSE_BYTES + self.MAX_STDERR_BYTES
        else:
            stdout_limit, total_limit = self.MAX_STDOUT_BYTES, self.MAX_TOTAL_OUTPUT_BYTES
        selector = selectors.DefaultSelector() if _is_posix() else None
        readers: list[threading.Thread] = []
        output_queue: queue.Queue[tuple[bytearray, bytes | None]] | None = None
        windows_streams_open = 0
        stdin_thread: threading.Thread | None = None
        stdin_errors: list[BaseException] = []
        reader_stop = threading.Event()
        job_cleanup_confirmed = job is None
        termination_attempted = False
        try:
            if selector is not None:
                for stream, target in ((process.stdout, stdout), (process.stderr, stderr)):
                    os.set_blocking(stream.fileno(), False)
                    selector.register(stream, selectors.EVENT_READ, target)
            else:
                output_queue = queue.Queue(maxsize=8)
                windows_streams_open = 2
                for stream, target in ((process.stdout, stdout), (process.stderr, stderr)):
                    reader = threading.Thread(target=_read_pipe_chunks,
                                              args=(stream, target, output_queue, self.READ_CHUNK_BYTES,
                                                    reader_stop),
                                              daemon=True)
                    reader.start()
                    readers.append(reader)
            if stdin_bytes is not None:
                stdin_thread = threading.Thread(
                    target=_write_child_stdin_guarded,
                    args=(process, stdin_bytes, stdin_errors), daemon=True)
                stdin_thread.start()
            action_deadline = deadline - self.CLEANUP_RESERVE_SECONDS
            while (selector is not None and selector.get_map() or
                   selector is None and windows_streams_open > 0 or
                   process.poll() is None):
                remaining = action_deadline - time.monotonic()
                if remaining <= 0:
                    raise VideoWorkerError("worker_timeout", "Video worker operation timed out",
                                           unknown=operation not in _KNOWN_FAILURE_OPERATIONS)
                if selector is not None:
                    available = [(key.data, os.read(key.fileobj.fileno(), self.READ_CHUNK_BYTES), key.fileobj)
                                 for key, _ in selector.select(min(remaining, 0.1))]
                    for target, chunk, stream in available:
                        if not chunk:
                            selector.unregister(stream)
                            stream.close()
                            continue
                        self._append_output(target, chunk, stdout, stderr, stdout_limit, total_limit, operation)
                else:
                    assert output_queue is not None
                    try:
                        target, chunk = output_queue.get(timeout=min(remaining, 0.1))
                    except queue.Empty:
                        continue
                    if chunk is not None:
                        self._append_output(target, chunk, stdout, stderr, stdout_limit, total_limit, operation)
                    else:
                        windows_streams_open -= 1
            if stdin_thread is not None:
                stdin_thread.join(timeout=max(0.0, deadline - time.monotonic()))
                if stdin_thread.is_alive():
                    raise VideoWorkerError("worker_timeout", "Video worker operation timed out",
                                           unknown=operation not in _KNOWN_FAILURE_OPERATIONS)
                if stdin_errors:
                    raise stdin_errors[0]
            remaining = deadline - time.monotonic()
            returncode = process.wait(timeout=max(0.0, remaining))
            # A completed CLI has no legitimate detached work left. Clean any
            # same-group descendants before returning, including after success.
            termination_attempted = True
            self._terminate_group(process, deadline, job)
            job_cleanup_confirmed = True
            return bytes(stdout), bytes(stderr), returncode
        except BaseException as error:
            reader_stop.set()
            if not termination_attempted:
                termination_attempted = True
                try:
                    self._terminate_group(process, deadline, job)
                    job_cleanup_confirmed = True
                except VideoWorkerError:
                    job_cleanup_confirmed = False
                    raise
            if isinstance(error, VideoWorkerError):
                raise
            if not isinstance(error, Exception):
                raise
            if isinstance(error, subprocess.TimeoutExpired):
                raise VideoWorkerError("worker_timeout", "Video worker operation timed out",
                                       unknown=operation not in _KNOWN_FAILURE_OPERATIONS) from error
            raise VideoWorkerError("worker_failed", "Video worker operation failed",
                                   unknown=operation not in _KNOWN_FAILURE_OPERATIONS) from error
        finally:
            reader_stop.set()
            if selector is not None:
                selector.close()
            for stream in (process.stdout, process.stderr):
                try:
                    stream.close()
                except OSError:
                    pass
            try:
                cleanup_wait = max(0.0, min(
                    deadline - time.monotonic(), self.CLEANUP_RESERVE_SECONDS))
                process.wait(timeout=cleanup_wait)
            except subprocess.TimeoutExpired:
                # _terminate_group already attempted to stop and verify the tree.
                pass
            if job is not None:
                def close_job_and_update_owner() -> None:
                    try:
                        job.close()
                    except OSError as error:
                        if operation_identity is not None and self._active_jobs.get(operation_identity) is job:
                            self._active_jobs[operation_identity] = _UNCONFIRMED_JOB
                        raise VideoWorkerError(
                            "worker_cleanup_failed", "Video worker process tree could not be released",
                            unknown=True) from error
                    if operation_identity is not None:
                        # Serialize handle close and removal/update against
                        # terminate_builtin, which holds this lock while using it.
                        if self._active_jobs.get(operation_identity) is job:
                            # Keep the reservation until the owning public call
                            # exits; an unconfirmed tree remains blocked instead
                            # of making its operation identity reusable.
                            self._active_jobs[operation_identity] = (
                                None if job_cleanup_confirmed else _UNCONFIRMED_JOB)
                if operation_identity is not None:
                    with self._active_jobs_lock:
                        close_job_and_update_owner()
                else:
                    close_job_and_update_owner()
            for reader in readers:
                reader.join(timeout=0.2)

    @staticmethod
    def _append_output(target: bytearray, chunk: bytes, stdout: bytearray, stderr: bytearray,
                       stdout_limit: int, total_limit: int, operation: str) -> None:
        target.extend(chunk)
        if len(stdout) > stdout_limit or len(stderr) > VideoWorker.MAX_STDERR_BYTES \
                or len(stdout) + len(stderr) > total_limit:
            raise VideoWorkerError("worker_output_limit", "Video worker output exceeded its limit",
                                   unknown=operation not in _KNOWN_FAILURE_OPERATIONS)

    def _terminate_group(self, process: subprocess.Popen, deadline: float,
                         job: WindowsJob | None = None) -> None:
        if _is_windows():
            try:
                if job is None:
                    raise ProcessTreeError("worker process Job Object is unavailable")
                confirmation_deadline = min(
                    deadline, time.monotonic() + self.CLEANUP_RESERVE_SECONDS)
                job.terminate()
                while True:
                    active = job.active_processes()
                    if type(active) is not int or active < 0:
                        raise ProcessTreeError("worker Job Object returned invalid activity")
                    if active == 0:
                        break
                    remaining = confirmation_deadline - time.monotonic()
                    if remaining <= 0:
                        raise ProcessTreeError("worker Job Object remained active after termination")
                    time.sleep(min(0.02, remaining))
                process.wait(timeout=max(0.0, confirmation_deadline - time.monotonic()))
            except subprocess.TimeoutExpired as error:
                raise VideoWorkerError("worker_cleanup_failed", "Video worker process could not be reaped",
                                       unknown=True) from error
            except OSError as error:
                raise VideoWorkerError("worker_cleanup_failed", "Video worker process tree could not be stopped",
                                       unknown=True) from error
            return
        if not _is_posix():
            return
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            try:
                process.wait(timeout=max(0.0, deadline - time.monotonic()))
            except subprocess.TimeoutExpired as error:
                raise VideoWorkerError("worker_cleanup_failed", "Video worker process could not be reaped",
                                       unknown=True) from error
            return
        except PermissionError as error:
            raise VideoWorkerError("worker_cleanup_failed", "Video worker process group could not be stopped",
                                   unknown=True) from error
        grace_end = min(deadline, time.monotonic() + self.TERMINATE_GRACE_SECONDS)
        grace_remaining = max(0.0, grace_end - time.monotonic())
        if grace_remaining:
            time.sleep(grace_remaining)
        # This pgid belongs to the session started above. Signal it directly
        # rather than probing with signal 0, which is restricted on some hosts.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except PermissionError as error:
            raise VideoWorkerError("worker_cleanup_failed", "Video worker process group could not be stopped",
                                   unknown=True) from error
        try:
            process.wait(timeout=max(0.0, deadline - time.monotonic()))
        except subprocess.TimeoutExpired as error:
            raise VideoWorkerError("worker_cleanup_failed", "Video worker process could not be reaped",
                                   unknown=True) from error


def _write_child_stdin(process: subprocess.Popen, payload: bytes) -> None:
    """Write one bounded payload to the child's stdin, then always close it.

    The worker reads its whole stdin before it acts, so a pipe left open would
    hold it until the deadline. Closing happens in a finally block so a partial
    write cannot leave the child waiting either. A child that already exited
    raises BrokenPipeError here and is reported as a failed operation.
    """
    stream = process.stdin
    if stream is None:
        raise OSError("worker stdin is unavailable")
    try:
        remaining = memoryview(payload)
        while remaining:
            written = stream.write(remaining)
            if not written:
                raise OSError("worker stdin accepted no data")
            remaining = remaining[written:]
    finally:
        try:
            stream.close()
        except OSError:
            pass


def _read_pipe_chunks(stream: Any, target: bytearray,
                      output_queue: "queue.Queue[tuple[bytearray, bytes | None]]",
                      chunk_size: int, stop: threading.Event) -> None:
    try:
        while not stop.is_set():
            chunk = stream.read(chunk_size)
            if not chunk:
                break
            while not stop.is_set():
                try:
                    output_queue.put((target, chunk), timeout=0.1)
                    break
                except queue.Full:
                    continue
    except (OSError, ValueError):
        pass
    finally:
        while not stop.is_set():
            try:
                output_queue.put((target, None), timeout=0.1)
                break
            except queue.Full:
                continue


def _write_child_stdin_guarded(process: subprocess.Popen, payload: bytes,
                               errors: list[BaseException]) -> None:
    try:
        _write_child_stdin(process, payload)
    except BaseException as error:
        errors.append(error)


# Zero-width characters are Cf (format), not whitespace, so trim() leaves them in
# place. Kept in step with isBlankFeedback in the Skill's revise-candidate.ts.
_ZERO_WIDTH_FEEDBACK = ("\u200b", "\u200c", "\u200d")


def _bounded_feedback_bytes(feedback: Any) -> bytes:
    """Encode revision feedback under the Skill's own bound.

    The Skill enforces this again after the spawn; refusing here keeps the exact
    reason instead of degrading it to a generic worker failure, and the bound
    itself is shared with the Skill's MAX_REVISE_FEEDBACK_BYTES.
    """
    if not isinstance(feedback, str):
        raise VideoWorkerError("invalid_revise_feedback", "Video revision feedback is invalid")
    try:
        encoded = feedback.encode("utf-8")
    except UnicodeEncodeError as error:
        raise VideoWorkerError("invalid_revise_feedback", "Video revision feedback is invalid") from error
    stripped = feedback
    for character in _ZERO_WIDTH_FEEDBACK:
        stripped = stripped.replace(character, "")
    if not 1 <= len(encoded) <= _MAX_REVISE_FEEDBACK_BYTES or not stripped.strip():
        raise VideoWorkerError("invalid_revise_feedback",
                               "Video revision feedback is empty or exceeds its size limit")
    return encoded


def _validate_revise_staging(value: dict[str, Any], binding: Mapping[str, Any],
                            expected_task_id: str) -> None:
    """Prove a staging receipt is exactly the one this operation asked for.

    The revision entry answers with a receipt rather than a task summary, so it
    has its own exact field set. Every value the caller observed beforehand is
    re-proved here, and the candidate path is recomputed from the operation id
    rather than read from the receipt, so a receipt for a different staging
    directory cannot be presented as this operation's result.
    """
    if set(value) != _REVISE_RESULT_FIELDS \
            or type(value.get("schema_version")) is not int or value["schema_version"] != 1 \
            or value.get("action") != "revise" \
            or type(value.get("input_event_cursor")) is not int \
            or type(value.get("revision")) is not int:
        raise VideoWorkerError("worker_result_invalid", "Video revision staging result is invalid")
    expected = {
        "operation_id": binding["operation_id"], "attempt_id": binding["attempt_id"],
        "task_id": expected_task_id, "payload_digest": binding["payload_digest"],
        "source_snapshot_digest": binding["source_snapshot_digest"],
        "request_sha256": binding["request_sha256"],
        "input_event_cursor": binding["event_cursor"], "revision": binding["revision"],
        "review_sha256": binding["review_sha256"],
        "lesson_ir_sha256": binding["lesson_ir_sha256"],
        "feedback_sha256": binding["feedback_sha256"],
    }
    if any(value[field] != wanted for field, wanted in expected.items()):
        raise VideoWorkerError("worker_result_invalid", "Video revision staging result is invalid")
    if value["candidate_path"] \
            != f"work/revise/{binding['operation_id']}/{_REVISE_CANDIDATE_NAME}":
        raise VideoWorkerError("worker_result_invalid", "Video revision staging result is invalid")


def _validate_canonical_path(path: Path) -> None:
    if not path.is_absolute() or ".." in path.parts or os.path.normpath(str(path)) != str(path):
        raise ValueError("path must be canonical and absolute")
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current = current / part
        info = os.lstat(current)
        if stat.S_ISLNK(info.st_mode) or _is_reparse_point(info):
            raise ValueError("symlink or reparse components are forbidden")


def _is_reparse_point(info: os.stat_result) -> bool:
    return bool(getattr(info, "st_file_attributes", 0) & 0x400)


def _require_directory(path: Path) -> None:
    info = os.lstat(path)
    if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode) or _is_reparse_point(info):
        raise ValueError("expected a real directory")


def _require_regular_executable(path: Path) -> None:
    info = os.lstat(path)
    if not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode) \
            or _is_reparse_point(info) or not os.access(path, os.X_OK):
        raise ValueError("configured Node path must be an executable regular file")


def _require_skill_install(root: Path) -> None:
    package_path = root / "package.json"
    scripts_path = root / "scripts"
    _require_directory(scripts_path)
    worker_path = scripts_path / "task-worker.ts"
    _require_regular_file(package_path)
    _require_regular_file(worker_path)
    package = _read_regular_json(package_path, _MAX_PACKAGE_BYTES)
    engines = package.get("engines")
    scripts = package.get("scripts")
    if package.get("name") != "padnote-video-explainer" \
            or not isinstance(engines, dict) or engines.get("node") != ">=22.22.0" \
            or not isinstance(scripts, dict) or "task:inspect" not in scripts:
        raise ValueError("configured Skill package does not implement the expected worker protocol")


def _require_regular_file(path: Path) -> None:
    info = os.lstat(path)
    if not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode) or _is_reparse_point(info):
        raise ValueError("expected a regular non-symlink file")


def _read_regular_json(path: Path, maximum: int) -> dict[str, Any]:
    data = _read_regular_file_bytes(path, maximum)
    value = json.loads(data.decode("utf-8"), object_pairs_hook=_unique_pairs,
                       parse_constant=_reject_json_constant)
    if not isinstance(value, dict):
        raise ValueError("configuration file must contain an object")
    return value


def _read_regular_file_bytes(path: Path, maximum: int) -> bytes:
    before_path = os.lstat(path)
    if _is_reparse_point(before_path) or stat.S_ISLNK(before_path.st_mode):
        raise ValueError("configuration file cannot be a symlink or reparse point")
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_NOFOLLOW", 0))
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode) or before.st_size > maximum \
                or (before.st_dev, before.st_ino) != (before_path.st_dev, before_path.st_ino):
            raise ValueError("configuration file is invalid")
        data = bytearray()
        while len(data) <= maximum:
            chunk = os.read(descriptor, min(65536, maximum + 1 - len(data)))
            if not chunk:
                break
            data.extend(chunk)
        after = os.fstat(descriptor)
        if len(data) > maximum or len(data) != after.st_size \
                or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != \
                (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
            raise ValueError("configuration file changed or exceeded its size limit")
    finally:
        os.close(descriptor)
    return bytes(data)


def _path_identities(node: Path, skill: Path, tasks: Path) -> tuple[tuple[int, int, int, int], ...]:
    result = []
    for path in (node, skill, tasks):
        info = os.lstat(path)
        if stat.S_ISLNK(info.st_mode) or _is_reparse_point(info):
            raise ValueError("configured paths cannot be symlinks")
        result.append((info.st_dev, info.st_ino, 0, 0) if stat.S_ISDIR(info.st_mode)
                      else (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns))
    return tuple(result)


def _validate_id(value: Any) -> str:
    if not isinstance(value, str) or not _TASK_ID.fullmatch(value):
        raise VideoWorkerError("invalid_task_id", "Video task identity is invalid")
    return value


def _validate_uuid(value: Any) -> str:
    if not isinstance(value, str) or not _UUID.fullmatch(value):
        raise VideoWorkerError("invalid_request", "Video operation identity is invalid")
    return value


def _operation_identity_from_options(options: tuple[str, ...]) -> tuple[str, str] | None:
    values: dict[str, str] = {}
    for key in ("--operation-id", "--attempt-id"):
        try:
            index = options.index(key)
        except ValueError:
            continue
        if index + 1 < len(options):
            value = options[index + 1]
            try:
                values[key] = _validate_uuid(value)
            except VideoWorkerError:
                return None
    if set(values) == {"--operation-id", "--attempt-id"}:
        return values["--operation-id"], values["--attempt-id"]
    return None


def _validate_revision(value: Any) -> None:
    if type(value) is not int or value < 1 or value > 2**53 - 1:
        raise VideoWorkerError("invalid_revision", "Storyboard revision is invalid")


def _validate_event_cursor(value: Any, *, increment: int = 0) -> None:
    if type(value) is not int or value < 1 or value > 2**53 - 1 - increment:
        raise VideoWorkerError("invalid_event_cursor", "Video task event cursor is invalid")


def _validate_sha256(value: Any) -> None:
    if not isinstance(value, str) or not _SHA256.fullmatch(value):
        raise VideoWorkerError("invalid_digest", "Review digest is invalid")


def _validate_operation_metadata(operation_id: Any, attempt_id: Any,
                                operation_payload_digest: Any,
                                source_snapshot_sha256: Any) -> list[str] | None:
    supplied = (operation_id, attempt_id, operation_payload_digest, source_snapshot_sha256)
    if all(value is None for value in supplied):
        return None
    if any(not isinstance(value, str) or not _UUID.fullmatch(value)
           for value in supplied[:2]) \
            or any(not isinstance(value, str) or not _SHA256.fullmatch(value)
                   for value in supplied[2:]):
        raise VideoWorkerError("invalid_operation_binding", "Video operation binding is invalid")
    return ["--operation-id", operation_id,
            "--attempt-id", attempt_id,
            "--payload-digest", operation_payload_digest,
            "--source-snapshot-sha256", source_snapshot_sha256]


def _validate_reconcile_operation(operation: Mapping[str, Any], expected_worker_task_id: str,
                                  expected_request_sha256: str) -> dict[str, Any]:
    if not isinstance(operation, Mapping):
        raise VideoWorkerError("invalid_operation_binding", "Video operation binding is invalid")
    action = operation.get("action")
    parameters = operation.get("parameters")
    if action == "initialize" and parameters == {}:
        revision = review_sha = ir_sha = None
        event_cursor = 0
    elif action == "storyboard" and isinstance(parameters, Mapping) \
            and set(parameters) == {"revision", "event_cursor"}:
        revision = parameters.get("revision")
        event_cursor = parameters.get("event_cursor")
        review_sha = ir_sha = None
        _validate_revision(revision)
        _validate_event_cursor(event_cursor)
    elif action == "approve" and isinstance(parameters, Mapping) \
            and set(parameters) == {"revision", "event_cursor", "review_sha256", "lesson_ir_sha256"}:
        revision = parameters.get("revision")
        event_cursor = parameters.get("event_cursor")
        review_sha = parameters.get("review_sha256")
        ir_sha = parameters.get("lesson_ir_sha256")
        _validate_revision(revision)
        _validate_event_cursor(event_cursor)
        _validate_sha256(review_sha)
        _validate_sha256(ir_sha)
    elif action == "produce" and isinstance(parameters, Mapping) \
            and set(parameters) == {"revision", "event_cursor", "review_sha256",
                                    "lesson_ir_sha256", "allow_cloud_tts"} \
            and parameters.get("allow_cloud_tts") is True:
        revision = parameters.get("revision")
        event_cursor = parameters.get("event_cursor")
        review_sha = parameters.get("review_sha256")
        ir_sha = parameters.get("lesson_ir_sha256")
        _validate_revision(revision)
        _validate_event_cursor(event_cursor)
        _validate_sha256(review_sha)
        _validate_sha256(ir_sha)
    else:
        raise VideoWorkerError("invalid_operation_binding", "Video operation binding is invalid")
    operation_id = operation.get("operation_id")
    attempt_id = operation.get("attempt_id")
    if not isinstance(operation_id, str) or not _UUID.fullmatch(operation_id) \
            or not isinstance(attempt_id, str) or not _UUID.fullmatch(attempt_id):
        raise VideoWorkerError("invalid_operation_binding", "Video operation binding is invalid")
    for key in ("payload_digest", "source_snapshot_digest", "video_binding"):
        if key not in operation:
            raise VideoWorkerError("invalid_operation_binding", "Video operation binding is invalid")
    payload_sha = operation.get("payload_digest")
    source_sha = operation.get("source_snapshot_digest")
    binding = operation.get("video_binding")
    _validate_sha256(payload_sha)
    _validate_sha256(source_sha)
    if not isinstance(binding, Mapping):
        raise VideoWorkerError("invalid_operation_binding", "Video operation binding is invalid")
    request_sha = binding.get("request_sha256")
    _validate_sha256(request_sha)
    if binding.get("worker_task_id") != expected_worker_task_id \
            or request_sha != expected_request_sha256:
        raise VideoWorkerError("invalid_operation_binding", "Video operation binding is invalid")
    try:
        from .video_operations import payload_digest
        computed_digest = payload_digest(action, dict(parameters))
    except Exception as error:
        raise VideoWorkerError("invalid_operation_binding", "Video operation binding is invalid") from error
    if computed_digest != payload_sha:
        raise VideoWorkerError("invalid_operation_binding", "Video operation binding is invalid")
    return {
        "operation_id": operation_id, "attempt_id": attempt_id, "action": action,
        "payload_digest": payload_sha, "source_snapshot_digest": source_sha,
        "request_sha256": request_sha, "event_cursor": event_cursor,
        "revision": revision, "review_sha256": review_sha, "lesson_ir_sha256": ir_sha,
    }


def _validate_reconcile_envelope(value: dict[str, Any], metadata: Mapping[str, Any],
                                 expected_task_id: str, expected_request_sha256: str) -> None:
    required = {"schema_version", "outcome", "reason", "operation_id", "attempt_id",
                "task_id", "action", "payload_digest", "request_sha256",
                "source_snapshot_digest", "result"}
    if isinstance(value, dict) and metadata.get("action") == "produce" \
            and value.get("outcome") == "verified_completed":
        required.update({"artifacts", "result_manifest_sha256"})
    if set(value) != required or type(value.get("schema_version")) is not int \
            or value["schema_version"] != 1 \
            or value.get("outcome") not in {"verified_completed", "verified_cancelled", "unconfirmed"} \
            or not isinstance(value.get("reason"), str) \
            or value["reason"] not in _RECONCILE_REASONS:
        raise VideoWorkerError("reconcile_invalid", "Video operation receipt could not be verified")
    expected_values = {
        "operation_id": metadata["operation_id"], "attempt_id": metadata["attempt_id"],
        "task_id": expected_task_id, "action": metadata["action"],
        "payload_digest": metadata["payload_digest"],
        "request_sha256": expected_request_sha256,
        "source_snapshot_digest": metadata["source_snapshot_digest"],
    }
    if any(value.get(key) != expected for key, expected in expected_values.items()):
        raise VideoWorkerError("reconcile_invalid", "Video operation receipt could not be verified")
    if value["outcome"] == "verified_cancelled":
        if value.get("result") is not None or value.get("reason") != "cancel_receipt_match" \
                or metadata["action"] not in {"storyboard", "produce"} \
                or metadata["revision"] is None:
            raise VideoWorkerError("reconcile_invalid", "Video operation receipt could not be verified")
        return
    if value["outcome"] == "unconfirmed":
        if value.get("result") is not None:
            raise VideoWorkerError("reconcile_invalid", "Video operation receipt could not be verified")
        return
    if value.get("reason") != "receipt_match" or not isinstance(value.get("result"), dict):
        raise VideoWorkerError("reconcile_invalid", "Video operation receipt could not be verified")
    try:
        from .video_operations import validate_result
        validate_result(value["result"], expected_task_id, metadata["action"], {
            "revision": metadata["revision"],
            "event_cursor": metadata["event_cursor"],
            **({"review_sha256": metadata["review_sha256"],
                "lesson_ir_sha256": metadata["lesson_ir_sha256"]}
               if metadata["action"] in {"approve", "produce"} else {}),
            **({"allow_cloud_tts": True} if metadata["action"] == "produce" else {}),
        })
    except Exception as error:
        raise VideoWorkerError("reconcile_invalid", "Video operation receipt could not be verified") from error
    if metadata["action"] == "produce":
        try:
            from .bundles import _validate_builtin_video_manifest
            _validate_builtin_video_manifest(value.get("artifacts"))
        except Exception as error:
            raise VideoWorkerError("reconcile_invalid", "Video artifact manifest could not be verified",
                                   unknown=True) from error
        if not isinstance(value.get("result_manifest_sha256"), str) \
                or not _SHA256.fullmatch(value["result_manifest_sha256"]):
            raise VideoWorkerError("reconcile_invalid", "Video artifact manifest could not be verified",
                                   unknown=True)


def _validate_cancel_envelope(value: dict[str, Any], metadata: Mapping[str, Any]) -> None:
    required = {"object", "protocol_version", "operation_id", "attempt_id", "status", "reason"}
    if not isinstance(value, dict) or set(value) != required \
            or value.get("object") != "padnote.video.cancel" \
            or type(value.get("protocol_version")) is not int or value["protocol_version"] != 1 \
            or value.get("operation_id") != metadata["operation_id"] \
            or value.get("attempt_id") != metadata["attempt_id"] \
            or value.get("status") not in {"verified_cancelled", "unconfirmed"} \
            or value.get("reason") not in _CANCEL_REASONS:
        raise VideoWorkerError("cancel_invalid", "Video cancellation could not be verified", unknown=True)
    if value["status"] == "verified_cancelled" and value["reason"] != "cancel_receipt_match":
        raise VideoWorkerError("cancel_invalid", "Video cancellation could not be verified", unknown=True)
    if value["status"] == "unconfirmed" and value["reason"] == "cancel_receipt_match":
        raise VideoWorkerError("cancel_invalid", "Video cancellation could not be verified", unknown=True)


def _parse_single_json(data: bytes) -> dict[str, Any]:
    try:
        text = data.decode("utf-8", errors="strict")
        value = json.loads(text, object_pairs_hook=_unique_pairs,
                           parse_constant=_reject_json_constant)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid") from error
    if not isinstance(value, dict):
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    return value


def _validate_builtin_failure(value: dict[str, Any]) -> tuple[str, bool]:
    """Validate the CLI's fixed, non-disclosing failure envelope."""
    required = {"ok", "code", "external_effect_possible", "message"}
    if "http_status" in value:
        required.add("http_status")
    if set(value) != required or value.get("ok") is not False \
            or type(value.get("external_effect_possible")) is not bool:
        raise VideoWorkerError("worker_result_invalid", "Video engine result could not be verified",
                               unknown=True)
    code = value.get("code")
    uncertain = value["external_effect_possible"]
    if not isinstance(code, str) or code not in _BUILTIN_FAILURE_CODES:
        raise VideoWorkerError("worker_result_invalid", "Video engine result could not be verified",
                               unknown=True)
    expected_message = _BUILTIN_FAILURE_MESSAGES.get(code) if code == "output_too_large" \
        else _BUILTIN_FAILURE_MESSAGES[uncertain]
    if value.get("message") != expected_message:
        raise VideoWorkerError("worker_result_invalid", "Video engine result could not be verified",
                               unknown=True)
    if "http_status" in value and (type(value["http_status"]) is not int
                                    or not 100 <= value["http_status"] <= 599):
        raise VideoWorkerError("worker_result_invalid", "Video engine result could not be verified",
                               unknown=True)
    if code in {"tts_input_invalid", "tts_adapter_start_failed"} and uncertain \
            or code not in {"tts_input_invalid", "tts_adapter_start_failed"} \
            and code in _BUILTIN_TTS_ERROR_CODES and not uncertain:
        raise VideoWorkerError("worker_result_invalid", "Video engine result could not be verified",
                               unknown=True)
    if code == "output_too_large" and not uncertain:
        raise VideoWorkerError("worker_result_invalid", "Video engine result could not be verified",
                               unknown=True)
    if "http_status" in value and code not in {"tts_provider_http_error", "tts_audio_download_http_error"}:
        raise VideoWorkerError("worker_result_invalid", "Video engine result could not be verified",
                               unknown=True)
    return code, uncertain


def not_configured_runtime_diagnostics() -> dict[str, Any]:
    """Return the stable diagnostic shape without reading local configuration."""
    return {
        "schema_version": "1.0",
        "runtime_verified": False,
        "video_ready": False,
        "checks": {
            name: {"status": "not_configured", "reason": "worker_not_configured"}
            for name in _DIAGNOSTIC_CHECKS
        },
    }


def _validate_runtime_diagnostics(value: dict[str, Any]) -> None:
    if set(value) != {"schema_version", "runtime_verified", "video_ready", "checks"} \
            or value.get("schema_version") != "1.0" \
            or value.get("runtime_verified") is not False \
            or value.get("video_ready") is not False:
        raise VideoWorkerError("diagnostic_invalid", "Video diagnostics could not be verified")
    checks = value.get("checks")
    if not isinstance(checks, dict) or set(checks) != set(_DIAGNOSTIC_CHECKS):
        raise VideoWorkerError("diagnostic_invalid", "Video diagnostics could not be verified")
    unconfigured = [isinstance(check, dict) and check.get("reason") == "worker_not_configured"
                   for check in checks.values()]
    if any(unconfigured) and not all(unconfigured):
        raise VideoWorkerError("diagnostic_invalid", "Video diagnostics could not be verified")
    for name in _DIAGNOSTIC_CHECKS:
        check = checks[name]
        if not isinstance(check, dict) or set(check) != {"status", "reason"} \
                or not isinstance(check.get("status"), str) \
                or check["status"] not in _DIAGNOSTIC_STATUSES \
                or not isinstance(check.get("reason"), str) \
                or check["reason"] not in _DIAGNOSTIC_REASONS:
            raise VideoWorkerError("diagnostic_invalid", "Video diagnostics could not be verified")
        # The CLI probes only installed local evidence; status/reason pairs must
        # agree so malformed output cannot upgrade a missing component.
        status, reason = check["status"], check["reason"]
        if reason == "worker_not_configured" and status == "not_configured":
            continue
        if name == "worker_modules":
            valid_pair = (status, reason) in {
                ("available", "modules_resolved"), ("missing", "module_missing"),
            }
        elif name == "tts":
            valid_pair = (status, reason) == ("not_configured", "adapter_not_configured")
        elif name in {"storyboard_browser", "render_browser"}:
            valid_pair = (status, reason) in {
                ("available", "installed_executable_found"),
                ("missing", "executable_missing"),
                ("unchecked", "configuration_unavailable"),
            }
        else:
            valid_pair = (status, reason) in {
                ("available", "installed_executable_found"),
                ("missing", "executable_missing"),
            }
        if not valid_pair:
            raise VideoWorkerError("diagnostic_invalid", "Video diagnostics could not be verified")


def _validate_summary(value: dict[str, Any], expected_task_id: str,
                      expected_status: str, expected_phase: str) -> None:
    if set(value) != {"task_id", "status", "phase", "event_cursor"} \
            or value.get("task_id") != expected_task_id \
            or value.get("status") != expected_status \
            or value.get("phase") != expected_phase \
            or type(value.get("event_cursor")) is not int or value["event_cursor"] < 1 \
            or value["event_cursor"] > 2**53 - 1:
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid", unknown=True)


def _validate_inspection(value: dict[str, Any], expected_task_id: str) -> None:
    required = {"protocol_version", "task_id", "status", "phase", "event_cursor"}
    allowed = required | {"revision", "lesson_ir_sha256", "review_sha256", "approval"}
    if not required.issubset(value) or not set(value).issubset(allowed) \
            or type(value.get("protocol_version")) is not int or value["protocol_version"] != 1 \
            or value.get("task_id") != expected_task_id \
            or not isinstance(value.get("status"), str) or value.get("status") not in _STATUSES \
            or not isinstance(value.get("phase"), str) or value.get("phase") not in _PHASES \
            or type(value.get("event_cursor")) is not int or value["event_cursor"] < 1 \
            or value["event_cursor"] > 2**53 - 1:
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid", unknown=False)
    revision = value.get("revision")
    has_revision = "revision" in value
    has_ir = "lesson_ir_sha256" in value
    has_review = "review_sha256" in value
    if has_revision != has_ir or has_revision != has_review:
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    if has_revision and (type(revision) is not int or revision < 1 or revision > 2**53 - 1
                         or not _valid_digest(value.get("lesson_ir_sha256"))
                         or not _valid_digest(value.get("review_sha256"))):
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    has_approval = "approval" in value
    approval = value.get("approval")
    if has_approval:
        if not isinstance(approval, dict):
            raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
        approval_required = {
            "approval_id", "revision", "lesson_ir_sha256", "review_sha256", "granted_at",
        }
        approval_allowed = approval_required | {"consumed_at"}
        if not approval_required.issubset(approval) or not set(approval).issubset(approval_allowed) \
                or not isinstance(approval.get("approval_id"), str) \
                or not _TASK_ID.fullmatch(approval["approval_id"]) \
                or type(approval.get("revision")) is not int or approval["revision"] < 1 \
                or approval["revision"] > 2**53 - 1 \
                or not _valid_digest(approval.get("lesson_ir_sha256")) \
                or not _valid_digest(approval.get("review_sha256")) \
                or not _valid_iso_datetime(approval.get("granted_at")) \
                or ("consumed_at" in approval and not _valid_iso_datetime(approval["consumed_at"])):
            raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
        if not has_revision or approval["revision"] != revision \
                or approval["lesson_ir_sha256"] != value["lesson_ir_sha256"] \
                or approval["review_sha256"] != value["review_sha256"]:
            raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    if value["status"] == "approved" and (not has_approval or "consumed_at" in approval):
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    if value["status"] in {"ready_to_render", "completed"} \
            and (not has_approval or "consumed_at" not in approval):
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    if value["status"] == "initialized" and (value["phase"] != "idle" or has_approval or has_revision):
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    if value["status"] == "awaiting_storyboard_review" and (
            value["phase"] != "awaiting_approval" or has_approval or not has_revision):
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    if value["status"] == "approved" and value["phase"] != "approval_pending":
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    if value["status"] == "ready_to_render" and value["phase"] != "audio_ready":
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    if value["status"] == "completed" and value["phase"] != "completed":
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    if value["status"] == "cancelling" and value["phase"] != "cancelling":
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    if value["status"] == "cancelled" and value["phase"] != "cancelled":
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")
    if value["status"] == "running" and value["phase"] not in {
            "storyboard", "approval_consumed", "tts_starting", "audio_ready", "render_starting"}:
        raise VideoWorkerError("worker_result_invalid", "Video worker result is invalid")


def _validate_review_projection(value: dict[str, Any], expected_task_id: str) -> None:
    required = {
        "protocol_version", "task_id", "status", "event_cursor", "revision",
        "review_sha256", "lesson_ir_sha256", "episode", "scenes",
    }
    if set(value) != required \
            or type(value.get("protocol_version")) is not int or value["protocol_version"] != 1 \
            or value.get("task_id") != expected_task_id \
            or not isinstance(value.get("status"), str) \
            or value["status"] not in _REVIEW_STABLE_STATUSES \
            or type(value.get("event_cursor")) is not int or not 1 <= value["event_cursor"] <= 2**53 - 1 \
            or type(value.get("revision")) is not int or not 1 <= value["revision"] <= 2**53 - 1 \
            or not _valid_digest(value.get("review_sha256")) \
            or not _valid_digest(value.get("lesson_ir_sha256")):
        _invalid_review_projection()

    episode = value.get("episode")
    if not isinstance(episode, dict) or set(episode) != {
            "title", "audience", "learning_goal", "language"}:
        _invalid_review_projection()
    for key, minimum, maximum in (("title", 1, 300), ("audience", 1, 500),
                                  ("learning_goal", 1, 500), ("language", 2, 32)):
        if not _bounded_string(episode.get(key), minimum, maximum):
            _invalid_review_projection()

    scenes = value.get("scenes")
    if not isinstance(scenes, list) or not 1 <= len(scenes) <= _MAX_REVIEW_SCENES:
        _invalid_review_projection()
    seen_ids: set[str] = set()
    total_preview_bytes = 0
    for scene in scenes:
        if not isinstance(scene, dict) or set(scene) != {
                "id", "learning_objective", "narration", "screen_text", "visual_kind", "preview"}:
            _invalid_review_projection()
        scene_id = scene.get("id")
        if not isinstance(scene_id, str) or not _SCENE_ID.fullmatch(scene_id) \
                or scene_id in seen_ids:
            _invalid_review_projection()
        seen_ids.add(scene_id)
        if not _bounded_string(scene.get("learning_objective"), 1, 500) \
                or not _bounded_string(scene.get("narration"), 1, 3000) \
                or not isinstance(scene.get("visual_kind"), str) \
                or scene["visual_kind"] not in _VISUAL_KINDS:
            _invalid_review_projection()
        screen_text = scene.get("screen_text")
        if not isinstance(screen_text, list) or len(screen_text) > 8 \
                or any(not _bounded_string(item, 1, 300) for item in screen_text):
            _invalid_review_projection()

        preview = scene.get("preview")
        if not isinstance(preview, dict) or set(preview) != {
                "path", "media_type", "size_bytes", "sha256", "width", "height"}:
            _invalid_review_projection()
        if preview.get("path") != f"storyboard-{scene_id}.png" \
                or preview.get("media_type") != "image/png" \
                or type(preview.get("size_bytes")) is not int \
                or not 1 <= preview["size_bytes"] <= _MAX_REVIEW_PREVIEW_BYTES \
                or not _valid_digest(preview.get("sha256")):
            _invalid_review_projection()
        width = preview.get("width")
        height = preview.get("height")
        if type(width) is not int or type(height) is not int \
                or not 1 <= width <= 4096 or not 1 <= height <= 4096 \
                or width * height > 8_388_608:
            _invalid_review_projection()
        total_preview_bytes += preview["size_bytes"]
        if total_preview_bytes > _MAX_REVIEW_TOTAL_PREVIEW_BYTES:
            _invalid_review_projection()


def _bounded_string(value: Any, minimum: int, maximum: int) -> bool:
    return isinstance(value, str) and minimum <= len(value) <= maximum


def _invalid_review_projection() -> None:
    raise VideoWorkerError("worker_result_invalid", "Video review projection is invalid", unknown=False)


def _valid_digest(value: Any) -> bool:
    return isinstance(value, str) and _SHA256.fullmatch(value) is not None


def _valid_iso_datetime(value: Any) -> bool:
    if not isinstance(value, str) or not re.fullmatch(
            r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z", value):
        return False
    try:
        datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%fZ")
        return True
    except ValueError:
        return False


def _unique_pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in items:
        if key in value:
            raise ValueError("duplicate JSON key")
        value[key] = item
    return value


def _reject_json_constant(value: str) -> None:
    raise ValueError(f"invalid JSON constant: {value}")
