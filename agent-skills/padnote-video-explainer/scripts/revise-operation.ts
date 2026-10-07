import {lstat} from 'node:fs/promises';
import {resolve} from 'node:path';
import {sha256Bytes, type JsonObject} from './lib.js';
import {inspectTask} from './inspect-task.js';
import {MAX_REVISE_FEEDBACK_BYTES, stageReviseCandidate} from './revise-candidate.js';
import {executionLeaseMutationLockPath, executionLockPath} from './task-state.js';
import {assertOperationInput, type VideoOperationBinding} from './video-operation.js';

/**
 * The dedicated revision operation (VIDEO_REVISION_PLAN step 2).
 *
 * It pins everything a later install must rely on -- the observed revision,
 * event cursor, review digest, Lesson IR digest and feedback digest -- proves
 * those against the current task, and stages the candidate materials into an
 * operation-exclusive directory. It deliberately does not write task state,
 * append an audit event, or touch the formal output/ tree: taking the lease,
 * recording revision_started and invalidating the previous approval are step 3,
 * and installing the new revision is step 4.
 */

export interface ReviseOperationResult extends JsonObject {
  schema_version: 1;
  operation_id: string;
  attempt_id: string;
  task_id: string;
  action: 'revise';
  payload_digest: string;
  source_snapshot_digest: string;
  request_sha256: string;
  input_event_cursor: number;
  revision: number;
  review_sha256: string;
  lesson_ir_sha256: string;
  feedback_sha256: string;
  candidate_path: string;
}

const TASK_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const SHA256 = /^[a-f0-9]{64}$/;

async function pathExists(path: string): Promise<boolean> {
  try {
    await lstat(path);
    return true;
  } catch (error: any) {
    if (error?.code === 'ENOENT') return false;
    throw error;
  }
}

function assertReviseArguments(taskId: string, requestSha256: string, revision: number,
                               eventCursor: number, reviewSha256: string,
                               lessonIrSha256: string): void {
  if (!TASK_ID.test(taskId) || !SHA256.test(requestSha256)
      || !SHA256.test(reviewSha256) || !SHA256.test(lessonIrSha256)
      || !Number.isSafeInteger(revision) || revision < 1
      || !Number.isSafeInteger(eventCursor) || eventCursor < 1) {
    throw new Error('invalid revision arguments');
  }
}

/** Reads the revision feedback from stdin, failing closed on oversize or invalid UTF-8. */
export async function readBoundedFeedbackStdin(
  maxBytes: number = MAX_REVISE_FEEDBACK_BYTES,
): Promise<string> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of process.stdin) {
    const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk as string);
    total += bytes.length;
    if (total > maxBytes) throw new Error('revise feedback exceeds its size limit');
    chunks.push(bytes);
  }
  try {
    return new TextDecoder('utf-8', {fatal: true}).decode(Buffer.concat(chunks, total));
  } catch {
    throw new Error('revise feedback is not valid UTF-8');
  }
}

/**
 * Stages one revision candidate for exactly the task revision the caller saw.
 * Read-only with respect to task state and output/; the staging ledger is the
 * only durable artifact it creates.
 */
export async function stageReviseOperation(
  taskRoot: string,
  expectedTaskId: string,
  expectedRequestSha256: string,
  revision: number,
  expectedEventCursor: number,
  expectedReviewSha256: string,
  expectedLessonIrSha256: string,
  binding: VideoOperationBinding,
  feedback: string,
): Promise<ReviseOperationResult> {
  assertReviseArguments(expectedTaskId, expectedRequestSha256, revision, expectedEventCursor,
    expectedReviewSha256, expectedLessonIrSha256);
  if (typeof feedback !== 'string') throw new Error('revise feedback is invalid');
  const feedbackSha256 = sha256Bytes(Buffer.from(feedback, 'utf8'));
  if (binding.feedback_sha256 !== feedbackSha256) {
    throw new Error('revise feedback does not match the operation binding');
  }
  // Every other observed value is covered by the payload digest, but the request
  // digest is not, so an unverified one could otherwise be echoed into the
  // result as if it had been proven.
  if (binding.request_sha256 !== expectedRequestSha256) {
    throw new Error('video revision binding does not match exact inputs');
  }
  await assertOperationInput(taskRoot, binding, 'revise', {
    revision, event_cursor: expectedEventCursor,
    review_sha256: expectedReviewSha256, lesson_ir_sha256: expectedLessonIrSha256,
    feedback_sha256: feedbackSha256,
  });

  // A revision may not be staged while another commit is in flight; the digests
  // it pins would otherwise be sampled from a task mid-transition.
  if (await pathExists(resolve(taskRoot, 'work/.task-state.lock'))
      || await pathExists(executionLockPath(taskRoot))
      || await pathExists(executionLeaseMutationLockPath(taskRoot))) {
    throw new Error('revise cannot start while an execution lease is active');
  }

  const inspection = await inspectTask(taskRoot);
  if (inspection.task_id !== expectedTaskId
      || inspection.status !== 'awaiting_storyboard_review'
      || inspection.phase !== 'awaiting_approval'
      || inspection.event_cursor !== expectedEventCursor
      || inspection.revision !== revision
      || inspection.review_sha256 !== expectedReviewSha256
      || inspection.lesson_ir_sha256 !== expectedLessonIrSha256
      || inspection.approval) {
    throw new Error('video revision binding is stale');
  }

  const staging = await stageReviseCandidate(taskRoot, binding.operation_id, feedback);
  if (staging.input_lesson_ir_sha256 !== expectedLessonIrSha256
      || staging.input_review_sha256 !== expectedReviewSha256
      || staging.feedback_sha256 !== feedbackSha256) {
    throw new Error('video revision staging does not match the operation binding');
  }
  return {
    schema_version: 1,
    operation_id: binding.operation_id,
    attempt_id: binding.attempt_id,
    task_id: expectedTaskId,
    action: 'revise',
    payload_digest: binding.payload_digest,
    source_snapshot_digest: binding.source_snapshot_digest,
    request_sha256: expectedRequestSha256,
    input_event_cursor: expectedEventCursor,
    revision,
    review_sha256: expectedReviewSha256,
    lesson_ir_sha256: expectedLessonIrSha256,
    feedback_sha256: feedbackSha256,
    candidate_path: staging.candidate_path,
  };
}
