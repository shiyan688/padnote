import {mkdir, rename, rm} from 'node:fs/promises';
import {resolve} from 'node:path';
import {buildStoryboard} from './build-storyboard.js';
import {readBoundedRegularFile, inspectTask, type TaskInspection} from './inspect-task.js';
import {atomicWriteFile, safeRelativePath, sha256Bytes, validateSchema, type JsonObject} from './lib.js';
import {validateReview} from './validate-review.js';
import {assertOperationInput, readAuditTail, type VideoOperationBinding} from './video-operation.js';
import {validateBoundedInput} from './validate-bounded-input.js';
import {
  MAX_REVISE_IR_BYTES,
  MAX_REVISE_REVIEW_BYTES,
  MAX_REVISE_REQUEST_BYTES,
  readValidatedReviseCandidate,
  type ReviseStagingDigests,
} from './revise-candidate.js';
import {revisionAttempt, revisionParameters, validateRevisionArguments} from './revision-start.js';
import {
  acquireExecutionLease,
  releaseExecutionLease,
  transitionTaskStateValidated,
  type TaskState,
} from './task-state.js';

/**
 * Installing a revision (VIDEO_REVISION_PLAN step 4).
 *
 * Step 3 committed the task to a revision and recorded `revision_started`; the
 * generator has by now written `candidate-lesson-ir.json` next to the staged
 * inputs. This step validates that candidate, installs it as the formal Lesson
 * IR, rebuilds the storyboard so the review describes the *new* IR, and moves the
 * task to `revision + 1` awaiting a fresh approval.
 *
 * Ordering is forced by two existing invariants:
 *
 *  - `readValidatedReviseCandidate` is pinned to the *previous* Lesson IR (it
 *    refuses a staging area built on anything else), so the candidate is read
 *    and validated before anything is installed.
 *  - `inspectReview` requires `review.json` to be strictly newer than the Lesson
 *    IR and every artifact, so the IR is installed first and `buildStoryboard`
 *    runs afterwards -- which is also the order `buildStoryboard` itself writes
 *    in (html, then previews, then review).
 *
 * The previous approval is already gone: step 3 dropped it when it moved to
 * `running`. The old IR and review survive as the staged copies under
 * `work/revise/<operation_id>/`, and any granted approval keeps its snapshot
 * under `work/approved-input/<approval_id>/`; nothing here deletes either.
 *
 * A revision that fails leaves `failed`, never a restored approval, so the plan's
 * "failure, unknown result or interrupted publish must not revive the previous
 * approval" holds by construction rather than by a recovery branch.
 */

export interface RevisionInstallResult extends JsonObject {
  schema_version: 1;
  task_id: string;
  operation_id: string;
  action: 'revise';
  status: 'awaiting_storyboard_review';
  phase: 'awaiting_approval';
  previous_revision: number;
  revision: number;
  candidate_sha256: string;
  lesson_ir_sha256: string;
  review_sha256: string;
  input_event_cursor: number;
  install_event_cursor: number;
}

/**
 * Why a staged candidate may not be installed, as one closed set of reasons.
 *
 * A revision is installable only from the state step 3 leaves behind: `running`
 * with phase `storyboard`, at exactly the cursor `revision_started` produced, by
 * an audit event that names *this* operation and *this* attempt. Every other
 * status maps to a reason, so a state added later cannot slip through as
 * installable by falling off the end.
 */
export type RevisionInstallRefusal =
  | 'task_identity_mismatch'
  | 'task_cancelled'
  | 'task_completed'
  | 'task_not_running'
  | 'phase_not_storyboard'
  | 'cursor_not_at_revision_start'
  | 'revision_not_started';

/** Everything the install must compare the persisted world against. */
export interface RevisionInstallExpectation {
  task_id: string;
  /** The cursor `revision_started` must have produced: one past the input cursor. */
  install_cursor: number;
  /** The revision the commit recorded; the install raises it by exactly one. */
  revision: number;
  operation_id: string;
  attempt_id: string;
}

/**
 * Decides whether this persisted state may install a revision. Total by design.
 *
 * `startedEvent` is the last record of the audit tail. The cursor check alone
 * only proves that exactly one event happened since the caller's reading; it
 * does not prove *which* event. Step 2 (staging) is read-only and takes no
 * lease, so two operations can be staged from the same reading, and an
 * operation that was staged but never started would otherwise be able to
 * install its candidate over an unrelated in-flight storyboard -- attributing
 * the install to an execution that never happened, and echoing an `attempt_id`
 * that nothing had authenticated. Requiring the event to be this operation's
 * `revision_started` closes that, and is what makes the plan's "install under
 * the same attempt" true rather than assumed.
 */
export function revisionInstallRefusal(
  inspection: TaskInspection,
  expected: RevisionInstallExpectation,
  startedEvent: JsonObject | undefined,
): RevisionInstallRefusal | undefined {
  if (inspection.task_id !== expected.task_id) return 'task_identity_mismatch';
  if (inspection.status === 'cancelling' || inspection.status === 'cancelled') return 'task_cancelled';
  if (inspection.status === 'completed') return 'task_completed';
  if (inspection.status !== 'running') return 'task_not_running';
  if (inspection.phase !== 'storyboard') return 'phase_not_storyboard';
  if (inspection.event_cursor !== expected.install_cursor) return 'cursor_not_at_revision_start';
  const started = startedEvent?.video_operation_started as JsonObject | undefined;
  if (!startedEvent || startedEvent.sequence !== expected.install_cursor
      || startedEvent.event !== 'revision_started'
      || startedEvent.revision !== expected.revision
      || started?.action !== 'revise'
      || started.operation_id !== expected.operation_id
      || started.attempt_id !== expected.attempt_id) {
    return 'revision_not_started';
  }
  return undefined;
}

/** The latest audit record, or undefined when the log cannot be read. */
async function latestAuditEvent(taskRoot: string): Promise<JsonObject | undefined> {
  return (await readAuditTail(taskRoot)).at(-1);
}

function parseBoundedJson(bytes: Buffer): JsonObject {
  let value: unknown;
  try {
    value = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(bytes));
  } catch {
    throw new Error('bounded JSON is invalid');
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('bounded JSON must be an object');
  }
  return value as JsonObject;
}

async function readExactRequest(
  taskRoot: string, expectedTaskId: string, expectedRequestSha256: string,
): Promise<JsonObject> {
  const path = resolve(taskRoot, 'request.json');
  const bytes = await readBoundedRegularFile(path, MAX_REVISE_REQUEST_BYTES);
  if (sha256Bytes(bytes) !== expectedRequestSha256) throw new Error('request digest mismatch');
  const request = parseBoundedJson(bytes);
  if (request.task_id !== expectedTaskId) throw new Error('request task identity mismatch');
  await validateBoundedInput(taskRoot, request);
  return request;
}

/**
 * Installs the validated candidate and rebuilds the review for `revision + 1`.
 *
 * Runs under the execution lease, because the sequence "replace the formal
 * Lesson IR, regenerate the storyboard, publish the new revision" must not
 * interleave with another worker -- and because the old IR is destroyed by this
 * step, so a second concurrent install would be operating on an already-changed
 * world.
 */
export async function installRevisionOperation(
  taskRoot: string,
  expectedTaskId: string,
  expectedRequestSha256: string,
  revision: number,
  expectedEventCursor: number,
  expectedReviewSha256: string,
  expectedLessonIrSha256: string,
  binding: VideoOperationBinding,
): Promise<RevisionInstallResult> {
  try {
    validateRevisionArguments(expectedTaskId, expectedRequestSha256, revision,
      expectedEventCursor, expectedReviewSha256, expectedLessonIrSha256);
  } catch {
    // The option set is shared with the start entry point (and must be, or the
    // two would disagree about the same binding), but the refusal names the
    // entry point the operator actually invoked.
    throw new Error('invalid revision install arguments');
  }
  if (binding.action !== 'revise') throw new Error('invalid revision install arguments');
  const feedbackSha256 = binding.feedback_sha256!;
  const parameters: JsonObject = revisionParameters(revision, expectedEventCursor,
    expectedReviewSha256, expectedLessonIrSha256, feedbackSha256);
  const installCursor = expectedEventCursor + 1;
  const nextRevision = revision + 1;
  if (!Number.isSafeInteger(installCursor) || !Number.isSafeInteger(installCursor + 1)) {
    throw new Error('invalid revision install arguments');
  }
  const expected: RevisionInstallExpectation = {
    task_id: expectedTaskId, install_cursor: installCursor, revision,
    operation_id: binding.operation_id, attempt_id: binding.attempt_id,
  };

  // Preflight, before any state or output file is touched.
  await assertOperationInput(taskRoot, binding, 'revise', parameters);
  const preflight = await inspectTask(taskRoot);
  const refusal = revisionInstallRefusal(preflight, expected, await latestAuditEvent(taskRoot));
  if (refusal) throw new Error(`video revision cannot be installed: ${refusal}`);

  const request = await readExactRequest(taskRoot, expectedTaskId, expectedRequestSha256);
  const staged: ReviseStagingDigests = {
    input_lesson_ir_sha256: expectedLessonIrSha256,
    input_review_sha256: expectedReviewSha256,
    feedback_sha256: feedbackSha256,
  };
  // Read while the *previous* Lesson IR is still installed: the candidate is
  // only meaningful as a revision of that exact revision.
  const candidate = await readValidatedReviseCandidate(
    taskRoot, binding.operation_id, staged, request);
  const candidateBytes = await readBoundedRegularFile(
    resolve(taskRoot, 'work/revise', binding.operation_id, 'candidate-lesson-ir.json'),
    MAX_REVISE_IR_BYTES);
  if (sha256Bytes(candidateBytes) !== candidate.candidate_sha256) {
    throw new Error('revise candidate changed before installation');
  }

  const lease = await acquireExecutionLease(taskRoot);
  let installing = false;
  let installingAttemptId = '';
  try {
    // Take the task over from the park: record `revision_installing` and rebind
    // the attempt to *this* process's lease. Until this commit the attempt still
    // names the start process, which has exited, so neither recovery nor cancel
    // could tell a live install from a dead one. After it, the park predicate no
    // longer matches (the tail is not `revision_started`), a crash here is
    // detected as an ordinary interrupted worker, and cancel signals this process.
    const installingState = await transitionTaskStateValidated(taskRoot, 'revision_installing', async current => {
      await assertOperationInput(taskRoot, binding, 'revise', parameters);
      const currentInspection = await inspectTask(taskRoot);
      const currentRefusal = revisionInstallRefusal(currentInspection, expected,
        await latestAuditEvent(taskRoot));
      if (currentRefusal) throw new Error(`video revision cannot be installed: ${currentRefusal}`);
      if (current.attempt?.phase !== 'storyboard') {
        throw new Error('video revision cannot be installed: attempt_missing');
      }
      return {
        ...current,
        attempt: revisionAttempt(lease.token) as TaskState['attempt'],
      };
    }, {
      revision,
      video_operation_installing: {
        schema_version: 1,
        operation_id: binding.operation_id,
        attempt_id: binding.attempt_id,
        action: 'revise',
        candidate_sha256: candidate.candidate_sha256,
      },
    });
    installing = true;
    installingAttemptId = installingState.attempt!.attempt_id;

    // Build the whole next revision inside the operation's own directory. The
    // layout check runs inside `buildStoryboard`, so a candidate that fails it
    // never touches output/: the published revision stays intact and consistent.
    const build = resolve(taskRoot, 'work/revise', binding.operation_id, 'build');
    await rm(build, {recursive: true, force: true});
    await mkdir(build, {recursive: true});
    await atomicWriteFile(resolve(build, 'lesson.ir.json'), candidateBytes);
    const built = await buildStoryboard(taskRoot, nextRevision,
      {request, ir: candidate.candidate}, build);

    const reviewBytes = await readBoundedRegularFile(
      resolve(build, 'review.json'), MAX_REVISE_REVIEW_BYTES);
    const review = parseBoundedJson(reviewBytes);
    await validateSchema('review', review);
    if (review.task_id !== expectedTaskId || review.lesson_ir_revision !== nextRevision
        || (review.lesson_ir as JsonObject | undefined)?.sha256 !== candidate.candidate_sha256) {
      throw new Error('revision review does not describe the installed candidate');
    }
    const reviewSha256 = sha256Bytes(reviewBytes);

    // Publish by rename, review last. A rename keeps the build's mtimes, so the
    // "review.json is newer than every artifact" invariant carries over. A crash
    // inside this short sequence is recorded as interrupted by recovery (the
    // attempt is bound to this lease), and `validateReview` below refuses a
    // half-published pair, so it can never be marked ready.
    const published = ['lesson.ir.json',
      ...(built.artifacts as JsonObject[]).map(artifact => artifact.path as string), 'review.json'];
    for (const name of published) {
      await rename(resolve(build, safeRelativePath(name)), resolve(taskRoot, 'output', safeRelativePath(name)));
    }
    await rm(build, {recursive: true, force: true});
    await validateReview(taskRoot);
    const installedIr = await readBoundedRegularFile(
      resolve(taskRoot, 'output/lesson.ir.json'), MAX_REVISE_IR_BYTES);
    if (sha256Bytes(installedIr) !== candidate.candidate_sha256) {
      throw new Error('installed Lesson IR does not match the validated candidate');
    }

    const next = await transitionTaskStateValidated(taskRoot, 'revision_ready', async current => {
      await assertOperationInput(taskRoot, binding, 'revise', parameters);
      if (current.task_id !== expectedTaskId || current.status !== 'running'
          || current.phase !== 'storyboard' || current.event_cursor !== installCursor + 1
          || current.revision !== revision || current.review_sha256 !== expectedReviewSha256
          || current.lesson_ir_sha256 !== expectedLessonIrSha256
          || current.attempt?.attempt_id !== installingAttemptId) {
        throw new Error('video revision install binding is stale');
      }
      // Re-read the published outputs under the lock: nothing may have moved
      // between `buildStoryboard` and this commit.
      const irNow = await readBoundedRegularFile(
        resolve(taskRoot, 'output/lesson.ir.json'), MAX_REVISE_IR_BYTES);
      const reviewNow = await readBoundedRegularFile(
        resolve(taskRoot, 'output/review.json'), MAX_REVISE_REVIEW_BYTES);
      if (sha256Bytes(irNow) !== candidate.candidate_sha256 || !reviewNow.equals(reviewBytes)) {
        throw new Error('revision outputs changed before the ready state');
      }
      return {
        ...current,
        status: 'awaiting_storyboard_review',
        phase: 'awaiting_approval',
        revision: nextRevision,
        lesson_ir_sha256: candidate.candidate_sha256,
        review_sha256: reviewSha256,
        // Step 3 already dropped the previous approval; clearing it again keeps
        // this path identical to the storyboard one and makes the invariant
        // local to the transition that establishes the new revision.
        approval: undefined,
        attempt: undefined,
        error: undefined,
        cancellation: undefined,
      };
    }, {
      revision: nextRevision,
      video_operation_installed: {
        schema_version: 1,
        operation_id: binding.operation_id,
        attempt_id: binding.attempt_id,
        action: 'revise',
        previous_revision: revision,
        revision: nextRevision,
        candidate_sha256: candidate.candidate_sha256,
        lesson_ir_sha256: candidate.candidate_sha256,
        review_sha256: reviewSha256,
        candidate_bytes: candidate.candidate_bytes,
        input_event_cursor: expectedEventCursor,
      },
    });

    return {
      schema_version: 1,
      task_id: expectedTaskId,
      operation_id: binding.operation_id,
      action: 'revise',
      status: 'awaiting_storyboard_review',
      phase: 'awaiting_approval',
      previous_revision: revision,
      revision: nextRevision,
      candidate_sha256: candidate.candidate_sha256,
      lesson_ir_sha256: candidate.candidate_sha256,
      review_sha256: reviewSha256,
      input_event_cursor: expectedEventCursor,
      install_event_cursor: next.event_cursor,
    };
  } catch (error) {
    if (installing) {
      await transitionTaskStateValidated(taskRoot, 'revision_failed', async current => {
        // Fail only the attempt that was actually installing: a state whose
        // attempt already moved on belongs to someone else's execution.
        if (current.status !== 'running' || current.phase !== 'storyboard'
            || current.event_cursor !== installCursor + 1
            || current.attempt?.attempt_id !== installingAttemptId) return undefined;
        return {
          ...current,
          status: 'failed',
          phase: 'storyboard',
          attempt: undefined,
          // No approval is restored: the previous one was dropped when the
          // revision started, and a failed install must not resurrect it.
          approval: undefined,
          error: {
            message: 'Video revision install failed',
            phase: 'storyboard',
            external_effect_possible: false,
          },
        } satisfies TaskState;
      }).catch(recordError => {
        // Never let the bookkeeping failure mask why the install failed.
        throw new Error(`${(error as Error)?.message ?? error}; recording revision_failed also failed: `
          + `${(recordError as Error)?.message ?? recordError}`);
      });
    }
    throw error;
  } finally {
    await releaseExecutionLease(taskRoot, lease);
  }
}
