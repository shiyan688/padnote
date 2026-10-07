import {randomUUID} from 'node:crypto';
import {assertOperationInput, type VideoOperationBinding} from './video-operation.js';
import {assertReviseStagingIntact, type ReviseStagingDigests} from './revise-candidate.js';
import {inspectTask, type TaskInspection} from './inspect-task.js';
import {
  acquireExecutionLease,
  releaseExecutionLease,
  transitionTaskStateValidated,
  type TaskState,
} from './task-state.js';
import type {JsonObject} from './lib.js';

/**
 * Starting a revision (VIDEO_REVISION_PLAN step 3).
 *
 * Takes the execution lease, re-proves the binding while holding it, records
 * `revision_started` atomically, and makes the previous approval unusable. It
 * deliberately stops there: validating the candidate and installing the new
 * revision under the same attempt is step 4.
 *
 * No new task status or phase is introduced. A revision re-generates the
 * storyboard, which is exactly what `running` + `storyboard` already means, and
 * `buildExactStoryboard` already clears `approval` on entry (task-worker.ts:698)
 * -- the same mechanism the plan asks for here. Reusing it keeps the closed
 * status/phase whitelists untouched; introducing a phase instead would have to
 * be mirrored in three places, two of which are other people's files.
 */

export interface RevisionStartResult extends JsonObject {
  schema_version: 1;
  task_id: string;
  operation_id: string;
  attempt_id: string;
  action: 'revise';
  status: 'running';
  phase: 'storyboard';
  revision: number;
  review_sha256: string;
  lesson_ir_sha256: string;
  feedback_sha256: string;
  input_event_cursor: number;
  start_event_cursor: number;
}

/**
 * Why a revision may not begin, as one closed set of reasons.
 *
 * The plan asks to refuse a revision once the approval has been consumed or the
 * task has entered TTS/render, and every reason below is distinguishable so a
 * caller (and a reviewer) can tell those cases apart instead of receiving one
 * undifferentiated rejection.
 */
export type RevisionStartRefusal =
  | 'task_cancelled'
  | 'task_completed'
  | 'storyboard_approved'
  | 'approval_consumed'
  | 'render_in_progress'
  | 'generation_in_flight'
  | 'review_not_awaiting_approval'
  | 'task_not_awaiting_review';

/**
 * Decides whether this persisted state may start a revision. Total by design:
 * every status/phase pair maps to a reason or to allowance, so a state added
 * later cannot slip through as "allowed" by falling off the end.
 */
export function revisionStartRefusal(inspection: TaskInspection): RevisionStartRefusal | undefined {
  const {status, phase} = inspection;
  if (status === 'cancelling' || status === 'cancelled') return 'task_cancelled';
  if (status === 'completed') return 'task_completed';
  // `approved` carries an approval that has been granted but not yet consumed.
  if (status === 'approved') return 'storyboard_approved';
  if (status === 'ready_to_render') return 'render_in_progress';
  if (status === 'running') {
    if (phase === 'approval_consumed' || phase === 'tts_starting') return 'approval_consumed';
    if (phase === 'audio_ready' || phase === 'render_starting') return 'render_in_progress';
    return 'generation_in_flight';
  }
  if (status === 'awaiting_storyboard_review') {
    if (phase !== 'awaiting_approval') return 'review_not_awaiting_approval';
    if (inspection.approval) return 'storyboard_approved';
    if (!Number.isSafeInteger(inspection.revision) || (inspection.revision ?? 0) < 1
        || typeof inspection.review_sha256 !== 'string'
        || typeof inspection.lesson_ir_sha256 !== 'string') {
      return 'task_not_awaiting_review';
    }
    return undefined;
  }
  // initialized / interrupted / failed: there is no storyboard under review yet,
  // and a revision must not be a way to resume a task that is not awaiting one.
  return 'task_not_awaiting_review';
}

const TASK_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const SHA256 = /^[a-f0-9]{64}$/;

/**
 * Whether a `running` state is a revision this module parked, rather than a
 * worker that died.
 *
 * `startRevisionOperation` releases the lease in `finally` and leaves the task
 * `running`/`storyboard` for step 4 to install into. Recovery
 * (`recoverInterruptedTask`) infers "the worker died" from `running` with no
 * live lease, and that inference is sound for every other `running` state --
 * their workers hold the lease for the whole span -- but not for this one: the
 * process that authorised the revision is *supposed* to exit, so "nobody holds
 * the lease" is the normal parked state. Without this predicate a single
 * ordinary `status` read rewrites the park as `interrupted`, and the install is
 * then refused with `task_not_running`.
 *
 * The park is identified from the audit log rather than from the state, because
 * the state alone is indistinguishable from a crashed storyboard worker. The
 * conditions are deliberately conjunctive and check identity, not just shape: a
 * `revision_started` record left over from an earlier cursor must not excuse a
 * later state.
 *
 * Note what this does *not* decide: whether a genuine crash during the parked
 * span should stay `running`. That needs the generation itself to be observable
 * (its own lease and event), which is step 5's design; see the checkpoint.
 *
 * Two of the conditions are defence in depth rather than reachable guards: the
 * cancellation test, and the status/phase test. The CLI's cancellation request
 * always appends its own event (`cancelTask` -> `cancellation_requested`), so a
 * recorded cancellation can never sit under a `revision_started` tail, and the
 * event-name test rejects it first. They are kept because this decides whether a
 * task is left `running`, and the cost of being wrong is a task nobody
 * completes; the table test pins them.
 */
export function revisionParkIsIntact(state: TaskState, lastEvent: JsonObject | undefined): boolean {
  if (state.status !== 'running' || state.phase !== 'storyboard') return false;
  // A cancelled park is a cancellation to carry out, not a park to preserve.
  if (state.cancellation) return false;
  if (!lastEvent || lastEvent.event !== 'revision_started') return false;
  if (lastEvent.sequence !== state.event_cursor) return false;
  if (lastEvent.task_id !== state.task_id) return false;
  if (lastEvent.revision !== state.revision) return false;
  const started = lastEvent.video_operation_started as JsonObject | undefined;
  return started?.action === 'revise';
}

/**
 * The shared argument check for the two revision entry points (start and
 * install). Both take the same strictly ordered eleven options, so they must
 * agree byte for byte on what a well-formed call looks like.
 */
export function validateRevisionArguments(
  taskId: string, requestSha256: string, revision: number, eventCursor: number,
  reviewSha256: string, lessonIrSha256: string,
): void {
  if (!TASK_ID.test(taskId) || !SHA256.test(requestSha256)
      || !SHA256.test(reviewSha256) || !SHA256.test(lessonIrSha256)
      || !Number.isSafeInteger(revision) || revision < 1
      || !Number.isSafeInteger(eventCursor) || eventCursor < 1
      || !Number.isSafeInteger(eventCursor + 1)) {
    throw new Error('invalid revision start arguments');
  }
}

/**
 * The attempt for this execution.
 *
 * Deliberately mirrored from task-worker.ts's private `newAttempt` rather than
 * imported: that module will import this one for its CLI entry, so sharing it
 * would create a cycle. Both write the same shape, and `validateAttempt` in
 * inspect-task.ts is what actually enforces it. Exported so a test that has to
 * reproduce the started state writes the same attempt the start writes.
 */
export function revisionAttempt(leaseToken: string): JsonObject {
  return {
    attempt_id: randomUUID(),
    lease_token: leaseToken,
    pid: process.pid,
    started_at: new Date().toISOString(),
    phase: 'storyboard',
    external_effect_possible: false,
  };
}

/**
 * The canonical parameter projection both revision entry points commit to.
 *
 * Exported so the install path cannot drift from the start path: the caller's
 * `payload_digest` is computed over exactly these keys, and `assertOperationInput`
 * compares it against them, so a divergence would surface as a confusing
 * "binding does not match exact inputs" instead of a compile error.
 */
export function revisionParameters(
  revision: number, eventCursor: number, reviewSha256: string,
  lessonIrSha256: string, feedbackSha256: string,
): JsonObject {
  return {
    revision, event_cursor: eventCursor,
    review_sha256: reviewSha256, lesson_ir_sha256: lessonIrSha256,
    feedback_sha256: feedbackSha256,
  };
}

/**
 * The audit record a commit-to-revision writes, named by the operation it
 * belongs to. Exported so the install path (and the test that reproduces the
 * started state) reads exactly the record this writes.
 */
export function revisionStartedDetails(
  binding: VideoOperationBinding, eventCursor: number, revision: number,
  reviewSha256: string, lessonIrSha256: string, feedbackSha256: string,
): JsonObject {
  return {
    revision,
    video_operation_started: {
      schema_version: 1,
      operation_id: binding.operation_id,
      attempt_id: binding.attempt_id,
      action: 'revise',
      payload_digest: binding.payload_digest,
      source_snapshot_digest: binding.source_snapshot_digest,
      request_sha256: binding.request_sha256,
      input_event_cursor: eventCursor,
      revision,
      review_sha256: reviewSha256,
      lesson_ir_sha256: lessonIrSha256,
      feedback_sha256: feedbackSha256,
    },
  };
}

/** The values the caller claims this start is bound to. */
interface RevisionStartExpectation {
  taskId: string;
  requestSha256: string;
  eventCursor: number;
  revision: number;
  reviewSha256: string;
  lessonIrSha256: string;
}

/** Everything a start must refuse to proceed on, checked against one reading. */
function assertRevisionStartable(
  inspection: TaskInspection, binding: VideoOperationBinding, expected: RevisionStartExpectation,
): void {
  const refusal = revisionStartRefusal(inspection);
  if (refusal) throw new Error(`video revision cannot start: ${refusal}`);
  if (inspection.task_id !== expected.taskId || inspection.event_cursor !== expected.eventCursor
      || inspection.revision !== expected.revision
      || inspection.review_sha256 !== expected.reviewSha256
      || inspection.lesson_ir_sha256 !== expected.lessonIrSha256) {
    throw new Error('video revision binding is stale');
  }
  // assertOperationInput has already proven the binding's request digest
  // against the bytes on disk, so the caller's own claim has to equal it. This
  // is checked here rather than only under the lease so a borrowed or stale
  // identifier is refused before any state or lock is touched.
  if (binding.request_sha256 !== expected.requestSha256) {
    throw new Error('video operation binding does not match exact inputs');
  }
}

/**
 * Commits one revision to run: lease taken, binding re-proved under it,
 * `revision_started` recorded, previous approval dropped.
 *
 * A queued or failed preflight changes nothing, because every check that can
 * fail happens before the state transition -- and the transition repeats them
 * while holding both the state lock and the lease.
 */
export async function startRevisionOperation(
  taskRoot: string,
  expectedTaskId: string,
  expectedRequestSha256: string,
  revision: number,
  expectedEventCursor: number,
  expectedReviewSha256: string,
  expectedLessonIrSha256: string,
  binding: VideoOperationBinding,
): Promise<RevisionStartResult> {
  validateRevisionArguments(expectedTaskId, expectedRequestSha256, revision,
    expectedEventCursor, expectedReviewSha256, expectedLessonIrSha256);
  if (binding.action !== 'revise') throw new Error('invalid revision start arguments');
  const feedbackSha256 = binding.feedback_sha256!;
  const parameters = revisionParameters(revision, expectedEventCursor, expectedReviewSha256,
    expectedLessonIrSha256, feedbackSha256);
  const expected: RevisionStartExpectation = {
    taskId: expectedTaskId, requestSha256: expectedRequestSha256,
    eventCursor: expectedEventCursor, revision,
    reviewSha256: expectedReviewSha256, lessonIrSha256: expectedLessonIrSha256,
  };

  // Preflight, before any state is touched: the binding still describes this
  // task's exact request, source and payload.
  await assertOperationInput(taskRoot, binding, 'revise', parameters);
  const inspection = await inspectTask(taskRoot);
  assertRevisionStartable(inspection, binding, expected);

  // The revision that is about to run must be the one that was staged.
  const staged: ReviseStagingDigests = {
    input_lesson_ir_sha256: expectedLessonIrSha256,
    input_review_sha256: expectedReviewSha256,
    feedback_sha256: feedbackSha256,
  };
  await assertReviseStagingIntact(taskRoot, binding.operation_id, staged);

  const lease = await acquireExecutionLease(taskRoot);
  try {
    let attemptId = '';
    const next = await transitionTaskStateValidated(taskRoot, 'revision_started', async current => {
      // Re-check under the lease: the preflight reading may already be stale.
      await assertOperationInput(taskRoot, binding, 'revise', parameters);
      const currentInspection = await inspectTask(taskRoot);
      assertRevisionStartable(currentInspection, binding, expected);
      if (current.task_id !== expected.taskId
          || current.request_sha256 !== expected.requestSha256
          || current.event_cursor !== expected.eventCursor
          || current.revision !== expected.revision
          || current.review_sha256 !== expected.reviewSha256
          || current.lesson_ir_sha256 !== expected.lessonIrSha256) {
        throw new Error('video revision binding is stale');
      }
      const attempt = revisionAttempt(lease.token);
      attemptId = attempt.attempt_id as string;
      return {
        ...current,
        status: 'running',
        phase: 'storyboard',
        // The plan's "make the previous approval unusable". It is already absent
        // in `awaiting_storyboard_review`; clearing it again is defence in depth
        // and keeps this path identical to the storyboard one.
        approval: undefined,
        attempt: attempt as TaskState['attempt'],
        error: undefined,
        cancellation: undefined,
      };
    }, revisionStartedDetails(binding, expectedEventCursor, revision,
      expectedReviewSha256, expectedLessonIrSha256, feedbackSha256));
    return {
      schema_version: 1,
      task_id: expectedTaskId,
      operation_id: binding.operation_id,
      attempt_id: attemptId,
      action: 'revise',
      status: 'running',
      phase: 'storyboard',
      revision,
      review_sha256: expectedReviewSha256,
      lesson_ir_sha256: expectedLessonIrSha256,
      feedback_sha256: feedbackSha256,
      input_event_cursor: expectedEventCursor,
      start_event_cursor: next.event_cursor,
    };
  } finally {
    await releaseExecutionLease(taskRoot, lease);
  }
}
