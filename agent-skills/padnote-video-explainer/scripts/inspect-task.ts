import {constants} from 'node:fs';
import {lstat, open} from 'node:fs/promises';
import {resolve} from 'node:path';
import {sha256Bytes, validateSchema, type JsonObject} from './lib.js';
import {readWindowsRegularFile} from './windows-reader.js';

const MAX_STATE_BYTES = 256 * 1024;
const MAX_REQUEST_BYTES = 1024 * 1024;
const taskStatuses = new Set([
  'initialized', 'awaiting_storyboard_review', 'approved', 'ready_to_render',
  'running', 'interrupted', 'failed', 'cancelling', 'cancelled', 'completed',
]);
const phases = new Set([
  'idle', 'storyboard', 'awaiting_approval', 'approval_pending', 'approval_consumed',
  'tts_starting', 'audio_ready', 'render_starting', 'completed', 'cancelling', 'cancelled',
]);
const hashPattern = /^[a-f0-9]{64}$/;
const idPattern = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

export interface TaskInspection extends JsonObject {
  protocol_version: 1;
  task_id: string;
  status: string;
  phase: string;
  event_cursor: number;
  revision?: number;
  lesson_ir_sha256?: string;
  review_sha256?: string;
  approval?: {
    approval_id: string;
    revision: number;
    lesson_ir_sha256: string;
    review_sha256: string;
    granted_at: string;
    consumed_at?: string;
  };
}

/** Reports bounded persisted metadata only. This never initializes or recovers a task. */
export async function inspectTask(taskRoot: string): Promise<TaskInspection> {
  const root = await lstat(taskRoot);
  if (!root.isDirectory() || root.isSymbolicLink()) throw new Error('task root is not a real directory');
  const work = await lstat(resolve(taskRoot, 'work'));
  if (!work.isDirectory() || work.isSymbolicLink()) throw new Error('work is not a real directory');

  const [stateBytes, requestBytes] = await Promise.all([
    readBoundedRegularFile(resolve(taskRoot, 'work/task-state.json'), MAX_STATE_BYTES),
    readBoundedRegularFile(resolve(taskRoot, 'request.json'), MAX_REQUEST_BYTES),
  ]);
  const state = parseObject(stateBytes, 'task state');
  const request = parseObject(requestBytes, 'request');
  await validateSchema('request', request);
  validateTaskState(state);
  if (request.task_id !== state.task_id) throw new Error('request task id does not match task state');
  if (sha256Bytes(requestBytes) !== state.request_sha256) {
    throw new Error('request digest does not match task state');
  }

  const result: TaskInspection = {
    protocol_version: 1,
    task_id: state.task_id,
    status: state.status,
    phase: state.phase,
    event_cursor: state.event_cursor,
  };
  for (const key of ['revision', 'lesson_ir_sha256', 'review_sha256'] as const) {
    if (state[key] !== undefined) result[key] = state[key];
  }
  if (state.approval) {
    const approval = state.approval as JsonObject;
    result.approval = {
      approval_id: approval.approval_id,
      revision: approval.revision,
      lesson_ir_sha256: approval.lesson_ir_sha256,
      review_sha256: approval.review_sha256,
      granted_at: approval.granted_at,
      ...(approval.consumed_at ? {consumed_at: approval.consumed_at} : {}),
    };
  }
  return result;
}

export async function readBoundedRegularFile(path: string, maxBytes: number): Promise<Buffer> {
  if (process.platform === 'win32') {
    return (await readWindowsRegularFile(path, maxBytes)).bytes;
  }
  // O_NONBLOCK prevents an attacker replacing the file with a FIFO from hanging inspect.
  if (typeof constants.O_NONBLOCK !== 'number' || typeof constants.O_NOFOLLOW !== 'number') {
    throw new Error('platform cannot safely inspect task files');
  }
  const flags = constants.O_RDONLY | constants.O_NONBLOCK | constants.O_NOFOLLOW;
  const file = await open(path, flags);
  try {
    const before = await file.stat({bigint: true});
    if (!before.isFile()) throw new Error('inspect input is not a regular file');
    if (before.size > BigInt(maxBytes)) throw new Error('inspect input exceeds its size limit');
    const chunks: Buffer[] = [];
    let total = 0;
    while (total <= maxBytes) {
      const chunk = Buffer.alloc(Math.min(64 * 1024, maxBytes + 1 - total));
      const {bytesRead} = await file.read(chunk, 0, chunk.length, null);
      if (bytesRead === 0) break;
      chunks.push(chunk.subarray(0, bytesRead));
      total += bytesRead;
    }
    if (total > maxBytes) throw new Error('inspect input exceeds its size limit');
    const after = await file.stat({bigint: true});
    if (total !== Number(after.size) || before.dev !== after.dev || before.ino !== after.ino
        || before.size !== after.size || before.mtimeNs !== after.mtimeNs) {
      throw new Error('inspect input changed while being read');
    }
    return Buffer.concat(chunks, total);
  } finally {
    await file.close();
  }
}

function parseObject(bytes: Buffer, label: string): JsonObject {
  let value: unknown;
  try {
    value = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(bytes));
  } catch {
    throw new Error(`${label} is not valid UTF-8 JSON`);
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${label} must be a JSON object`);
  }
  return value as JsonObject;
}

function validateTaskState(state: JsonObject): void {
  const allowed = new Set([
    'schema_version', 'task_id', 'request_sha256', 'status', 'phase', 'updated_at',
    'event_cursor', 'revision', 'lesson_ir_sha256', 'review_sha256', 'approval',
    'attempt', 'cancellation', 'error',
  ]);
  if (Object.keys(state).some(key => !allowed.has(key))) throw new Error('task state has unknown fields');
  if (state.schema_version !== '1.0' || typeof state.task_id !== 'string'
      || !idPattern.test(state.task_id) || typeof state.request_sha256 !== 'string'
      || !hashPattern.test(state.request_sha256) || typeof state.status !== 'string'
      || !taskStatuses.has(state.status) || typeof state.phase !== 'string'
      || !phases.has(state.phase) || !validDate(state.updated_at)
      || !Number.isSafeInteger(state.event_cursor) || state.event_cursor < 1) {
    throw new Error('task state has invalid required fields');
  }
  const hasRevision = state.revision !== undefined;
  const hasIrHash = state.lesson_ir_sha256 !== undefined;
  const hasReviewHash = state.review_sha256 !== undefined;
  if (hasRevision !== hasIrHash || hasRevision !== hasReviewHash
      || (hasRevision && (!Number.isSafeInteger(state.revision) || state.revision < 1
        || !hashPattern.test(state.lesson_ir_sha256) || !hashPattern.test(state.review_sha256)))) {
    throw new Error('task state review binding is invalid');
  }

  if (state.approval !== undefined) validateApproval(state.approval, state);
  if (state.attempt !== undefined) validateAttempt(state.attempt);
  if (state.cancellation !== undefined) validateCancellation(state.cancellation);
  if (state.error !== undefined) validateError(state.error);

  if (state.status === 'initialized' && (state.phase !== 'idle' || state.approval || state.attempt)) {
    throw new Error('initialized task state is inconsistent');
  }
  if (state.status === 'awaiting_storyboard_review'
      && (!hasRevision || state.phase !== 'awaiting_approval' || state.approval || state.attempt)) {
    throw new Error('storyboard review state is inconsistent');
  }
  if (state.status === 'approved'
      && (!state.approval || state.approval.consumed_at || state.phase !== 'approval_pending' || state.attempt)) {
    throw new Error('approved task state is inconsistent');
  }
  if (state.status === 'running' && !state.attempt) throw new Error('running task state has no attempt');
  if (state.status === 'running'
      && !new Set(['storyboard', 'approval_consumed', 'tts_starting', 'audio_ready', 'render_starting'])
        .has(state.phase)) throw new Error('running task phase is inconsistent');
  if (state.status === 'cancelling'
      && (state.phase !== 'cancelling' || !state.attempt || !state.cancellation
        || state.cancellation.completed_at)) {
    throw new Error('cancelling task state is inconsistent');
  }
  if (state.status === 'interrupted' && (!state.error || state.attempt)) {
    throw new Error('interrupted task state is inconsistent');
  }
  if (state.status === 'failed' && (!state.error || state.attempt)) {
    throw new Error('failed task state is inconsistent');
  }
  if (state.status === 'cancelled' && (state.phase !== 'cancelled' || state.attempt
      || !state.cancellation?.completed_at)) throw new Error('cancelled task state is inconsistent');
  if (state.status === 'completed' && (state.phase !== 'completed' || state.attempt
      || !state.approval?.consumed_at)) throw new Error('completed task state is inconsistent');
  if (state.status === 'ready_to_render'
      && (state.phase !== 'audio_ready' || !state.approval?.consumed_at || state.attempt)) {
    throw new Error('ready-to-render task state is inconsistent');
  }
}

function validateApproval(value: unknown, state: JsonObject): asserts value is JsonObject {
  if (!isObject(value)) throw new Error('task approval is invalid');
  const allowed = new Set([
    'approval_id', 'revision', 'lesson_ir_sha256', 'review_sha256',
    'lesson_ir_snapshot_path', 'review_snapshot_path', 'granted_at', 'consumed_at',
  ]);
  if (Object.keys(value).some(key => !allowed.has(key))
      || typeof value.approval_id !== 'string' || !idPattern.test(value.approval_id)
      || !Number.isSafeInteger(value.revision) || value.revision < 1
      || !hashPattern.test(value.lesson_ir_sha256) || !hashPattern.test(value.review_sha256)
      || !validDate(value.granted_at)
      || (value.consumed_at !== undefined && !validDate(value.consumed_at))
      || value.lesson_ir_snapshot_path !== `work/approved-input/${value.approval_id}/lesson.ir.json`
      || value.review_snapshot_path !== `work/approved-input/${value.approval_id}/review.json`
      || value.revision !== state.revision || value.lesson_ir_sha256 !== state.lesson_ir_sha256
      || value.review_sha256 !== state.review_sha256) {
    throw new Error('task approval binding is invalid');
  }
}

function validateAttempt(value: unknown): void {
  if (!isObject(value)) throw new Error('task attempt is invalid');
  const allowed = new Set(['attempt_id', 'lease_token', 'pid', 'started_at', 'phase', 'external_effect_possible']);
  if (Object.keys(value).some(key => !allowed.has(key)) || typeof value.attempt_id !== 'string'
      || !idPattern.test(value.attempt_id) || (value.lease_token !== undefined
        && (typeof value.lease_token !== 'string' || !idPattern.test(value.lease_token)))
      || !Number.isSafeInteger(value.pid) || value.pid < 1 || !validDate(value.started_at)
      || typeof value.phase !== 'string' || !phases.has(value.phase)
      || typeof value.external_effect_possible !== 'boolean') throw new Error('task attempt is invalid');
}

function validateCancellation(value: unknown): void {
  if (!isObject(value) || Object.keys(value).some(key => !['requested_at', 'completed_at'].includes(key))
      || !validDate(value.requested_at)
      || (value.completed_at !== undefined && !validDate(value.completed_at))) {
    throw new Error('task cancellation is invalid');
  }
}

function validateError(value: unknown): void {
  if (!isObject(value) || Object.keys(value).some(key => !['message', 'phase', 'external_effect_possible'].includes(key))
      || typeof value.message !== 'string' || value.message.length > 500
      || typeof value.phase !== 'string' || !phases.has(value.phase)
      || typeof value.external_effect_possible !== 'boolean') throw new Error('task error metadata is invalid');
}

function isObject(value: unknown): value is JsonObject {
  return value != null && typeof value === 'object' && !Array.isArray(value);
}

function validDate(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const parsed = new Date(value);
  return Number.isFinite(parsed.getTime()) && parsed.toISOString() === value;
}
