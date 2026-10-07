from __future__ import annotations

import copy
import hashlib
import json
import re
import uuid
from typing import Any

from .security import canonical_json, validate_identifier


MAX_VIDEO_OPERATIONS = 1024
MAX_VIDEO_OPERATIONS_PER_TASK = 64
MAX_VIDEO_RESULT_BYTES = 16 * 1024
_HASH = re.compile(r"^[a-f0-9]{64}$")
_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
_ACTIONS = {"initialize", "storyboard", "approve", "revise", "produce"}
_STATUSES = {"queued", "running", "succeeded", "failed", "unknown", "cancelled"}
_UNRESOLVED = {"queued", "running", "unknown"}
ERROR_CODES = {
    "worker_failed", "worker_unknown", "worker_interrupted", "worker_result_invalid",
    "worker_timeout", "authorization_revoked", "instance_retired", "run_unavailable",
    "source_changed", "binding_changed", "cancelled",
    "tts_input_invalid", "tts_provider_request_failed", "tts_provider_http_error",
    "tts_provider_response_invalid", "tts_provider_response_too_large", "tts_audio_url_missing",
    "tts_audio_url_rejected", "tts_audio_download_failed", "tts_audio_download_http_error",
    "tts_audio_download_too_large", "tts_audio_format_invalid", "tts_audio_output_write_failed",
    "tts_adapter_failure", "tts_adapter_result_invalid", "tts_adapter_start_failed",
}
_TASK_STATUSES = {
    "initialized", "awaiting_storyboard_review", "approved", "ready_to_render",
    "running", "interrupted", "failed", "cancelling", "cancelled", "completed",
}
_PHASES = {
    "idle", "storyboard", "awaiting_approval", "approval_pending", "approval_consumed",
    "tts_starting", "audio_ready", "render_starting", "completed", "cancelling", "cancelled",
}
_RECORD_FIELDS = {
    "operation_id", "owner_connection_id", "instance_id", "task_id", "client_operation_id",
    "action", "parameters", "payload_digest", "source_snapshot_digest", "video_binding",
    "queue_sequence", "status", "attempt_id", "created_at", "updated_at", "result", "error",
}
_RECORD_FIELDS_V2 = _RECORD_FIELDS | {"cancel_request"}

# The exact staging receipt the Skill's revise-operation entry returns. It is not
# an inspection and not a task summary, so it needs its own accepted shape.
_REVISE_RESULT_FIELDS = {
    "schema_version", "operation_id", "attempt_id", "task_id", "action",
    "payload_digest", "source_snapshot_digest", "request_sha256", "input_event_cursor",
    "revision", "review_sha256", "lesson_ir_sha256", "feedback_sha256", "candidate_path",
}
# Kept in step with readValidatedReviseCandidate in the Skill's revise-candidate.ts.
_REVISE_CANDIDATE_NAME = "candidate-lesson-ir.json"


class VideoOperationInvalid(ValueError):
    pass


class VideoOperationConflict(ValueError):
    pass


def detached(value: Any) -> Any:
    return copy.deepcopy(value)


def normalize_parameters(action: Any, parameters: Any, *, allow_cursor_overflow: bool = False) -> dict[str, Any]:
    if not isinstance(action, str) or action not in _ACTIONS or not isinstance(parameters, dict):
        raise VideoOperationInvalid("Video operation action or parameters are invalid")
    if action == "initialize":
        if parameters != {}:
            raise VideoOperationInvalid("Initialize parameters must be empty")
        return {}
    if action == "storyboard":
        if set(parameters) != {"revision", "event_cursor"}:
            raise VideoOperationInvalid("Storyboard parameters are invalid")
        event_cursor = _positive_safe_integer(parameters["event_cursor"], "event_cursor")
        if not allow_cursor_overflow and event_cursor > 2**53 - 3:
            raise VideoOperationInvalid("Storyboard event cursor cannot be advanced safely")
        return {
            "revision": _positive_safe_integer(parameters["revision"], "revision"),
            "event_cursor": event_cursor,
        }
    if action == "produce":
        expected = {"revision", "event_cursor", "review_sha256", "lesson_ir_sha256", "allow_cloud_tts"}
        if set(parameters) != expected or parameters.get("allow_cloud_tts") is not True:
            raise VideoOperationInvalid("Video production requires explicit cloud TTS consent")
        return {
            "revision": _positive_safe_integer(parameters["revision"], "revision"),
            "event_cursor": _positive_safe_integer(parameters["event_cursor"], "event_cursor"),
            "review_sha256": _digest(parameters["review_sha256"], "review_sha256"),
            "lesson_ir_sha256": _digest(parameters["lesson_ir_sha256"], "lesson_ir_sha256"),
            "allow_cloud_tts": True,
        }
    if action == "approve":
        if set(parameters) != {"revision", "review_sha256", "lesson_ir_sha256", "event_cursor"}:
            raise VideoOperationInvalid("Approval parameters are invalid")
        event_cursor = _positive_safe_integer(parameters["event_cursor"], "event_cursor")
        if not allow_cursor_overflow and event_cursor == 2**53 - 1:
            raise VideoOperationInvalid("Approval event cursor cannot be advanced safely")
        return {
            "revision": _positive_safe_integer(parameters["revision"], "revision"),
            "review_sha256": _digest(parameters["review_sha256"], "review_sha256"),
            "lesson_ir_sha256": _digest(parameters["lesson_ir_sha256"], "lesson_ir_sha256"),
            "event_cursor": event_cursor,
        }
    # A revision names the same observed review as an approval, plus the bounded
    # feedback the generator will consume. The projection must stay identical to
    # the Skill's operationParameters for action "revise", because both sides
    # independently hash it into the payload digest.
    if set(parameters) != {"revision", "review_sha256", "lesson_ir_sha256",
                           "feedback_sha256", "event_cursor"}:
        raise VideoOperationInvalid("Revision parameters are invalid")
    event_cursor = _positive_safe_integer(parameters["event_cursor"], "event_cursor")
    if not allow_cursor_overflow and event_cursor == 2**53 - 1:
        # Recording revision_started has to advance the cursor by one.
        raise VideoOperationInvalid("Revision event cursor cannot be advanced safely")
    return {
        "revision": _positive_safe_integer(parameters["revision"], "revision"),
        "review_sha256": _digest(parameters["review_sha256"], "review_sha256"),
        "lesson_ir_sha256": _digest(parameters["lesson_ir_sha256"], "lesson_ir_sha256"),
        "feedback_sha256": _digest(parameters["feedback_sha256"], "feedback_sha256"),
        "event_cursor": event_cursor,
    }


def payload_digest(action: str, parameters: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_json({"action": action, "parameters": parameters})).hexdigest()


def validate_video_operations(data: dict[str, Any], *, version: int = 2) -> None:
    """Validate persisted immutable references and every video operation record."""
    records = data.get("video_operations")
    if not isinstance(records, dict) or len(records) > MAX_VIDEO_OPERATIONS:
        raise ValueError("video operation ledger is malformed")
    per_task: dict[str, int] = {}
    active_by_task: dict[str, int] = {}
    idempotency_keys: set[tuple[str, str, str]] = set()
    queue_sequences: set[int] = set()
    for operation_id, record in records.items():
        _validate_record(operation_id, record, data, version=version)
        sequence = record["queue_sequence"]
        if sequence in queue_sequences:
            raise ValueError("video operation queue sequence is duplicated")
        queue_sequences.add(sequence)
        task_id = record["task_id"]
        per_task[task_id] = per_task.get(task_id, 0) + 1
        if per_task[task_id] > MAX_VIDEO_OPERATIONS_PER_TASK:
            raise ValueError("video operation task limit is exceeded")
        key = (record["owner_connection_id"], task_id, record["client_operation_id"])
        if key in idempotency_keys:
            raise ValueError("video operation idempotency key is duplicated")
        idempotency_keys.add(key)
        if record["status"] in _UNRESOLVED:
            active_by_task[task_id] = active_by_task.get(task_id, 0) + 1
            if active_by_task[task_id] > 1:
                raise ValueError("multiple unresolved video operations exist for one task")


def current_binding(data: dict[str, Any], connection_id: str,
                    instance_id: str, task_id: str) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    connections = data.get("connections", {})
    instances = data.get("instances", {})
    runs = data.get("runs", {})
    connection = connections.get(connection_id)
    instance = instances.get(instance_id)
    run = runs.get(task_id)
    if not isinstance(connection, dict) or connection.get("instance_id") != instance_id:
        raise VideoOperationConflict("Device connection is unavailable")
    if connection.get("revoked_at") is not None:
        raise VideoOperationConflict("Device connection has been revoked")
    if not isinstance(instance, dict) or instance.get("retired_at") is not None:
        raise VideoOperationConflict("Agent instance is unavailable")
    if not isinstance(run, dict) or run.get("owner_connection_id") != connection_id \
            or run.get("instance_id") != instance_id:
        raise VideoOperationConflict("Video task is not owned by this device")
    source_sha = run.get("source_snapshot_digest")
    binding = run.get("video_binding")
    if not isinstance(source_sha, str) or not _HASH.fullmatch(source_sha) \
            or not valid_video_binding(binding):
        raise VideoOperationConflict("Video task has no immutable source binding")
    return connection, instance, run


def valid_video_binding(value: Any) -> bool:
    return isinstance(value, dict) and set(value) == {"worker_task_id", "request_sha256"} \
        and isinstance(value.get("worker_task_id"), str) \
        and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", value["worker_task_id"]) is not None \
        and isinstance(value.get("request_sha256"), str) \
        and _HASH.fullmatch(value["request_sha256"]) is not None


def record_binding_error(data: dict[str, Any], record: dict[str, Any]) -> str | None:
    connection = data.get("connections", {}).get(record["owner_connection_id"])
    instance = data.get("instances", {}).get(record["instance_id"])
    run = data.get("runs", {}).get(record["task_id"])
    if not isinstance(connection, dict) or connection.get("revoked_at") is not None \
            or connection.get("instance_id") != record["instance_id"]:
        return "authorization_revoked"
    if not isinstance(instance, dict) or instance.get("retired_at") is not None:
        return "instance_retired"
    if not isinstance(run, dict) or run.get("owner_connection_id") != record["owner_connection_id"] \
            or run.get("instance_id") != record["instance_id"]:
        return "run_unavailable"
    if run.get("source_snapshot_digest") != record["source_snapshot_digest"]:
        return "source_changed"
    if run.get("video_binding") != record["video_binding"]:
        return "binding_changed"
    return None


def make_record(connection: dict[str, Any], task_id: str, client_operation_id: str,
                action: str, parameters: dict[str, Any], run: dict[str, Any],
                queue_sequence: int, now: int) -> dict[str, Any]:
    operation_id = str(uuid.uuid4())
    return {
        "operation_id": operation_id,
        "owner_connection_id": connection["connection_id"],
        "instance_id": connection["instance_id"],
        "task_id": task_id,
        "client_operation_id": client_operation_id,
        "action": action,
        "parameters": detached(parameters),
        "payload_digest": payload_digest(action, parameters),
        "source_snapshot_digest": run["source_snapshot_digest"],
        "video_binding": detached(run["video_binding"]),
        "queue_sequence": queue_sequence,
        "status": "queued",
        "attempt_id": None,
        "created_at": now,
        "updated_at": now,
        "result": None,
        "error": None,
        "cancel_request": None,
    }


def validate_result(value: Any, expected_worker_id: str, action: str,
                    parameters: dict[str, Any], *,
                    expected_record: dict[str, Any] | None = None) -> dict[str, Any]:
    if not isinstance(value, dict) or len(value) > 16:
        raise VideoOperationInvalid("Video worker result is invalid")
    try:
        encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                             allow_nan=False).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError) as error:
        raise VideoOperationInvalid("Video worker result is invalid") from error
    if len(encoded) > MAX_VIDEO_RESULT_BYTES:
        raise VideoOperationInvalid("Video worker result exceeds its size limit")
    if action == "revise":
        return _validate_revise_result(value, expected_worker_id, parameters, expected_record)
    if "protocol_version" in value:
        try:
            from .video_worker import _validate_inspection
            _validate_inspection(value, expected_worker_id)
        except Exception as error:
            raise VideoOperationInvalid("Video worker inspection is invalid") from error
    else:
        if set(value) != {"task_id", "status", "phase", "event_cursor"} \
                or value.get("task_id") != expected_worker_id \
                or not isinstance(value.get("status"), str) or value["status"] not in _TASK_STATUSES \
                or not isinstance(value.get("phase"), str) or value["phase"] not in _PHASES \
                or type(value.get("event_cursor")) is not int \
                or not 1 <= value["event_cursor"] <= 2**53 - 1:
            raise VideoOperationInvalid("Video worker summary is invalid")
    if action in {"initialize", "approve", "produce"} or (action == "storyboard" and "event_cursor" in parameters):
        if "protocol_version" not in value:
            raise VideoOperationInvalid("Video action requires a full inspection")
    if action == "storyboard" and (value["status"] != "awaiting_storyboard_review"
                                    or value["phase"] != "awaiting_approval"):
        raise VideoOperationInvalid("Storyboard result has the wrong task state")
    if action == "storyboard" and "protocol_version" in value \
            and (value.get("revision") != parameters["revision"]
                 or ("event_cursor" in parameters
                     and value.get("event_cursor") != parameters["event_cursor"] + 2)):
        raise VideoOperationInvalid("Storyboard result does not match the requested revision")
    if action == "approve" and (value["status"] != "approved" or value["phase"] != "approval_pending"):
        raise VideoOperationInvalid("Approval result has the wrong task state")
    if action == "approve":
        if "protocol_version" not in value \
                or value.get("revision") != parameters["revision"] \
                or value.get("review_sha256") != parameters["review_sha256"] \
                or value.get("lesson_ir_sha256") != parameters["lesson_ir_sha256"] \
                or value.get("event_cursor") != parameters["event_cursor"] + 1:
            raise VideoOperationInvalid("Approval result does not match the approved review")
    if action == "produce":
        if value.get("protocol_version") != 1 or value.get("status") != "completed" \
                or value.get("phase") != "completed" \
                or value.get("revision") != parameters["revision"] \
                or value.get("review_sha256") != parameters["review_sha256"] \
                or value.get("lesson_ir_sha256") != parameters["lesson_ir_sha256"] \
                or type(value.get("event_cursor")) is not int \
                or value["event_cursor"] <= parameters["event_cursor"]:
            raise VideoOperationInvalid("Video production result does not match the approved review")
    return detached(value)


def _validate_revise_result(value: dict[str, Any], expected_worker_id: str,
                            parameters: dict[str, Any],
                            expected_record: dict[str, Any] | None) -> dict[str, Any]:
    """Prove a revision staging receipt belongs to one reserved operation.

    Every other action's result is a task-level observation, so checking it
    against the task is enough. A revision result is narrower: it is the claim
    that one specific operation staged one specific candidate from one specific
    review. That claim is only meaningful against the reservation, so this
    refuses to verify anything without it, and then requires the receipt to
    match the reservation field for field. Nothing here is taken on trust from
    the worker: the payload digest is recomputed from the action and the
    reserved parameters, and the candidate path is derived from the reservation
    rather than accepted as given.
    """
    if not isinstance(expected_record, dict):
        raise VideoOperationInvalid("Revision result cannot be verified without its reservation")
    # `type(...) is int` because `True == 1` and `2.0 == 2`; the adapter checks
    # the same, but reconcile and ledger reload only pass through here.
    if set(value) != _REVISE_RESULT_FIELDS or type(value.get("schema_version")) is not int \
            or value.get("schema_version") != 1 or value.get("action") != "revise" \
            or type(value.get("revision")) is not int \
            or type(value.get("input_event_cursor")) is not int:
        raise VideoOperationInvalid("Video worker revision result is invalid")
    binding = expected_record.get("video_binding")
    if not isinstance(binding, dict) or not valid_video_binding(binding):
        raise VideoOperationInvalid("Video worker revision reservation is invalid")
    operation_id = expected_record.get("operation_id")
    attempt_id = expected_record.get("attempt_id")
    if not isinstance(operation_id, str) or not _UUID.fullmatch(operation_id) \
            or not isinstance(attempt_id, str) or not _UUID.fullmatch(attempt_id):
        raise VideoOperationInvalid("Video worker revision reservation is invalid")
    if value["operation_id"] != operation_id or value["attempt_id"] != attempt_id \
            or value["task_id"] != expected_worker_id \
            or value["task_id"] != binding["worker_task_id"]:
        raise VideoOperationInvalid("Video worker revision result belongs to another operation")
    if value["payload_digest"] != expected_record.get("payload_digest") \
            or value["payload_digest"] != payload_digest("revise", parameters):
        raise VideoOperationInvalid("Video worker revision result has the wrong payload digest")
    if value["source_snapshot_digest"] != expected_record.get("source_snapshot_digest") \
            or value["request_sha256"] != binding["request_sha256"]:
        raise VideoOperationInvalid("Video worker revision result changed the source binding")
    for field in ("revision", "review_sha256", "lesson_ir_sha256", "feedback_sha256"):
        if value[field] != parameters.get(field):
            raise VideoOperationInvalid("Video worker revision result does not match the request")
    if value["input_event_cursor"] != parameters.get("event_cursor"):
        raise VideoOperationInvalid("Video worker revision result changed the observed cursor")
    if value["candidate_path"] != f"work/revise/{operation_id}/{_REVISE_CANDIDATE_NAME}":
        raise VideoOperationInvalid("Video worker revision candidate path is invalid")
    return detached(value)


def _validate_record(operation_id: Any, record: Any, data: dict[str, Any], *, version: int) -> None:
    expected_fields = _RECORD_FIELDS if version == 1 else _RECORD_FIELDS_V2
    if not isinstance(operation_id, str) or not _UUID.fullmatch(operation_id) \
            or not isinstance(record, dict) or set(record) != expected_fields \
            or record.get("operation_id") != operation_id:
        raise ValueError("video operation record shape is invalid")
    for key in ("owner_connection_id", "instance_id", "task_id"):
        value = record.get(key)
        if not isinstance(value, str) or not _UUID.fullmatch(value):
            raise ValueError("video operation reference is invalid")
    try:
        clean_client_id = validate_identifier(record.get("client_operation_id"), "client_operation_id")
        if clean_client_id != record["client_operation_id"]:
            raise ValueError("video operation idempotency reference is not canonical")
    except Exception as error:
        raise ValueError("video operation idempotency reference is invalid") from error
    if not isinstance(record["action"], str) or record["action"] not in _ACTIONS \
            or not isinstance(record["status"], str) or record["status"] not in _STATUSES:
        raise ValueError("video operation enum is invalid")
    # Preserve pre-exact storyboard records and their original payload digest.
    # They remain readable but cannot be dispatched without an event cursor.
    legacy_storyboard = record["action"] == "storyboard" \
        and isinstance(record["parameters"], dict) and set(record["parameters"]) == {"revision"}
    if legacy_storyboard:
        parameters = {"revision": _positive_safe_integer(record["parameters"].get("revision"), "revision")}
    else:
        parameters = normalize_parameters(record["action"], record["parameters"],
                                          allow_cursor_overflow=True)
    if parameters != record["parameters"] or record["payload_digest"] != payload_digest(record["action"], parameters):
        raise ValueError("video operation parameter digest is invalid")
    if not isinstance(record["source_snapshot_digest"], str) or not _HASH.fullmatch(record["source_snapshot_digest"]) \
            or not valid_video_binding(record["video_binding"]):
        raise ValueError("video operation source binding is invalid")
    if type(record["created_at"]) is not int or type(record["updated_at"]) is not int \
            or record["created_at"] < 0 or record["updated_at"] < record["created_at"]:
        raise ValueError("video operation timestamps are invalid")
    if type(record["queue_sequence"]) is not int \
            or not 1 <= record["queue_sequence"] <= 2**53 - 1:
        raise ValueError("video operation queue sequence is invalid")
    attempt = record["attempt_id"]
    if attempt is not None and (not isinstance(attempt, str) or not _UUID.fullmatch(attempt)):
        raise ValueError("video operation attempt is invalid")
    if record["status"] == "queued" and attempt is not None:
        raise ValueError("queued video operation has an attempt")
    if record["status"] in {"running", "succeeded", "failed", "unknown"} and attempt is None:
        raise ValueError("claimed video operation has no attempt")
    error = record["error"]
    if error is not None and (not isinstance(error, str) or error not in ERROR_CODES):
        raise ValueError("video operation error code is invalid")
    result = record["result"]
    if result is not None:
        expected_worker = record["video_binding"]["worker_task_id"]
        if validate_result(result, expected_worker, record["action"], record["parameters"],
                           expected_record=record) != result:
            raise ValueError("video operation result is invalid")
    if record["status"] == "succeeded" and (result is None or error is not None):
        raise ValueError("successful video operation result is missing")
    if record["status"] in {"failed", "unknown", "cancelled"} and error is None:
        raise ValueError("terminal video operation error is missing")
    if record["status"] in {"queued", "running", "failed", "unknown", "cancelled"} and result is not None:
        raise ValueError("non-successful video operation has a result")
    if record["status"] in {"queued", "running"} and error is not None:
        raise ValueError("active video operation has an error")
    if version == 2:
        cancel_request = record["cancel_request"]
        if cancel_request is not None:
            cancel_instance = data.get("instances", {}).get(record["instance_id"])
            cancel_allowed = record["action"] == "storyboard" or (
                record["action"] == "produce" and isinstance(cancel_instance, dict)
                and cancel_instance.get("kind") == "builtin_video")
            if not isinstance(cancel_request, dict) or set(cancel_request) != {
                    "attempt_id", "requested_at", "control_id", "control_status"} \
                    or not isinstance(cancel_request.get("attempt_id"), str) \
                    or not _UUID.fullmatch(cancel_request["attempt_id"]) \
                    or cancel_request["attempt_id"] != attempt \
                    or type(cancel_request.get("requested_at")) is not int \
                    or cancel_request["requested_at"] < record["created_at"] \
                    or not isinstance(cancel_request.get("control_id"), str) \
                    or not _UUID.fullmatch(cancel_request["control_id"]) \
                    or cancel_request.get("control_status") not in {"requested", "unconfirmed"} \
                    or not cancel_allowed \
                    or record["status"] not in {"running", "unknown", "cancelled", "succeeded", "failed"}:
                raise ValueError("video operation cancellation request is invalid")
    if record["status"] == "cancelled" and attempt is not None \
            and not (version == 2 and record.get("cancel_request") is not None):
        raise ValueError("cancelled queued video operation has an attempt")
    connection = data.get("connections", {}).get(record["owner_connection_id"])
    instance = data.get("instances", {}).get(record["instance_id"])
    run = data.get("runs", {}).get(record["task_id"])
    if not isinstance(connection, dict) or connection.get("instance_id") != record["instance_id"] \
            or not isinstance(instance, dict) \
            or not isinstance(run, dict) or run.get("owner_connection_id") != record["owner_connection_id"] \
            or run.get("instance_id") != record["instance_id"]:
        raise ValueError("video operation persisted reference is invalid")


def _positive_safe_integer(value: Any, name: str) -> int:
    if type(value) is not int or not 1 <= value <= 2**53 - 1:
        raise VideoOperationInvalid(f"Video operation {name} is invalid")
    return value


def _digest(value: Any, name: str) -> str:
    if not isinstance(value, str) or not _HASH.fullmatch(value):
        raise VideoOperationInvalid(f"Video operation {name} is invalid")
    return value
