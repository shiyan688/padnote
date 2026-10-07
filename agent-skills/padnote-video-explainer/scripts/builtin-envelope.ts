import type {TaskInspection} from './inspect-task.js';
import type {VideoOperationBinding} from './video-operation.js';

export interface BuiltinProduceSuccess {
  ok: true;
  inspection: TaskInspection;
  receipt: {
    operation_id: string;
    attempt_id: string;
    action: 'produce';
    payload_digest: string;
    source_snapshot_digest: string;
    request_sha256: string;
    input_event_cursor: number;
    result_event_cursor: number;
    revision: number;
    review_sha256: string;
    lesson_ir_sha256: string;
    allow_cloud_tts: true;
  };
  artifacts: Record<string, unknown>[];
}

/** Build the exact, bounded success projection consumed by the Python ledger. */
export function makeBuiltinProduceSuccess(
  inspection: TaskInspection,
  binding: VideoOperationBinding,
  artifacts: Record<string, unknown>[],
): BuiltinProduceSuccess {
  const approval = inspection.approval;
  if (binding.action !== 'produce' || binding.allow_cloud_tts !== true
      || inspection.protocol_version !== 1 || inspection.task_id.length === 0
      || inspection.status !== 'completed' || inspection.phase !== 'completed'
      || !Number.isSafeInteger(inspection.event_cursor)
      || inspection.event_cursor <= binding.input_event_cursor
      || inspection.revision !== binding.revision
      || inspection.review_sha256 !== binding.review_sha256
      || inspection.lesson_ir_sha256 !== binding.lesson_ir_sha256
      || approval?.revision !== binding.revision
      || approval?.review_sha256 !== binding.review_sha256
      || approval?.lesson_ir_sha256 !== binding.lesson_ir_sha256
      || !approval?.consumed_at
      || !Array.isArray(artifacts) || artifacts.length < 1 || artifacts.length > 16) {
    throw new Error('completed task inspection does not match the approved operation');
  }
  return {
    ok: true,
    inspection,
    receipt: {
      operation_id: binding.operation_id,
      attempt_id: binding.attempt_id,
      action: 'produce',
      payload_digest: binding.payload_digest,
      source_snapshot_digest: binding.source_snapshot_digest,
      request_sha256: binding.request_sha256,
      input_event_cursor: binding.input_event_cursor,
      result_event_cursor: inspection.event_cursor,
      revision: binding.revision!,
      review_sha256: binding.review_sha256!,
      lesson_ir_sha256: binding.lesson_ir_sha256!,
      allow_cloud_tts: true,
    },
    artifacts,
  };
}
