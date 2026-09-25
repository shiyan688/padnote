import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {access, readFile, rename} from 'node:fs/promises';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {buildStoryboard} from './build-storyboard.js';
import {loadApprovedAudioInput} from './approved-audio.js';
import {prepareAudio} from './prepare-audio.js';
import {renderApprovedVideo} from './render-video.js';
import {
  acquireExecutionLease,
  executionLeaseIsLive,
  initialTaskState,
  loadTaskState,
  readExecutionLease,
  releaseExecutionLease,
  transitionTaskState,
  transitionTaskStateIf,
  verifiedExecutionProcessGroup,
  type TaskState,
} from './task-state.js';
import {atomicWriteFile, sha256Bytes, sha256File, type JsonObject} from './lib.js';
import {validateAudio, type AudioInputBinding} from './validate-audio.js';
import {validateRequest} from './validate-request.js';
import {validateResult} from './validate-result.js';
import {validateReview} from './validate-review.js';

export interface WorkerEffects {
  prepareAudio(taskRoot: string, input: ApprovedExecutionInput): Promise<JsonObject>;
  render(taskRoot: string, revision: number, allowFixtureAudio: boolean,
         input: ApprovedExecutionInput): Promise<JsonObject | undefined>;
  validateAudio(taskRoot: string, input: ApprovedExecutionInput): Promise<JsonObject>;
  validateResult(taskRoot: string): Promise<JsonObject>;
}

export interface ApprovedExecutionInput {
  lessonIrPath: string;
  reviewPath: string;
  audioBinding: AudioInputBinding;
  ir: JsonObject;
  request: JsonObject;
}

const productionEffects: WorkerEffects = {
  prepareAudio: (taskRoot, input) => prepareAudio(taskRoot, {
    irPath: input.lessonIrPath,
    inputBinding: input.audioBinding,
    irObject: input.ir,
    requestObject: input.request,
  }),
  render: (taskRoot, revision, allowFixtureAudio, input) => renderApprovedVideo(taskRoot, {
    approval: 'approve', revision, allowFixtureAudio,
    lessonIrPath: input.lessonIrPath,
    expectedLessonIrSha256: input.audioBinding.lesson_ir_sha256,
    audioBinding: input.audioBinding,
  }),
  validateAudio: (taskRoot, input) => validateAudio(taskRoot, undefined,
    input.audioBinding, input.lessonIrPath),
  validateResult,
};

export async function ensureTaskState(taskRoot: string): Promise<TaskState> {
  const request = await validateRequest(taskRoot);
  const digest = await sha256File(resolve(taskRoot, 'request.json'));
  const existing = await loadTaskState(taskRoot);
  if (existing) {
    if (existing.task_id !== request.task_id || existing.request_sha256 !== digest) {
      throw new Error('request.json changed after persistent task state was created');
    }
    return recoverInterruptedTask(taskRoot, existing);
  }
  return transitionTaskState(taskRoot, 'task_initialized', current => {
    if (current) return current;
    return initialTaskState(request.task_id, digest);
  });
}

export async function buildPersistedStoryboard(taskRoot: string, revision: number): Promise<TaskState> {
  if (!Number.isInteger(revision) || revision < 1) throw new Error('storyboard revision must be positive');
  const initial = await ensureTaskState(taskRoot);
  assertNotTerminal(initial);
  const lease = await acquireExecutionLease(taskRoot);
  try {
    await transitionTaskState(taskRoot, 'storyboard_started', current => ({
      ...requireState(current), status: 'running', phase: 'storyboard',
      attempt: newAttempt('storyboard', lease.token), error: undefined,
    }));
    await buildStoryboard(taskRoot, revision);
    const binding = await currentReviewBinding(taskRoot);
    return transitionTaskState(taskRoot, 'storyboard_ready', current => ({
      ...requireState(current),
      status: 'awaiting_storyboard_review',
      phase: 'awaiting_approval',
      revision: binding.revision,
      lesson_ir_sha256: binding.lessonIrSha256,
      review_sha256: binding.reviewSha256,
      approval: undefined,
      attempt: undefined,
      error: undefined,
    }), {revision});
  } catch (error) {
    await recordFailure(taskRoot, 'storyboard', error, false);
    throw error;
  } finally {
    await releaseExecutionLease(taskRoot, lease);
  }
}

export async function grantApproval(
  taskRoot: string,
  revision: number,
  allowUncertainRetry = false,
): Promise<TaskState> {
  const state = await ensureTaskState(taskRoot);
  assertCanGrantApproval(state, allowUncertainRetry);
  const binding = await currentReviewBinding(taskRoot);
  if (binding.revision !== revision) {
    throw new Error(`approval revision ${revision} does not match review revision ${binding.revision}`);
  }
  const approvalId = randomUUID();
  const snapshot = await persistApprovalSnapshot(taskRoot, approvalId, binding);
  return transitionTaskState(taskRoot, 'approval_granted', current => {
    const checked = requireState(current);
    assertCanGrantApproval(checked, allowUncertainRetry);
    return {
      ...checked,
      status: 'approved',
      phase: 'approval_pending',
      revision,
      lesson_ir_sha256: binding.lessonIrSha256,
      review_sha256: binding.reviewSha256,
      approval: {
        approval_id: approvalId,
        revision,
        lesson_ir_sha256: binding.lessonIrSha256,
        review_sha256: binding.reviewSha256,
        lesson_ir_snapshot_path: snapshot.lessonIrPath,
        review_snapshot_path: snapshot.reviewPath,
        granted_at: new Date().toISOString(),
      },
      attempt: undefined,
      error: undefined,
    };
  }, {revision, uncertain_retry: allowUncertainRetry});
}

/** Runs approved Stage 2. No failure or restart automatically creates another approval. */
export async function runApprovedTask(
  taskRoot: string,
  mode: 'audio' | 'render' | 'all',
  allowFixtureAudio = false,
  effects: WorkerEffects = productionEffects,
): Promise<TaskState> {
  await ensureTaskState(taskRoot);
  const lease = await acquireExecutionLease(taskRoot);
  let phase = 'approval_consumed';
  let externalEffectPossible = false;
  let cancellationSeen = false;
  let activeAttemptId: string | undefined;
  const onSignal = () => { cancellationSeen = true; };
  process.once('SIGINT', onSignal);
  process.once('SIGTERM', onSignal);
  try {
    let state = await loadTaskState(taskRoot);
    if (!state) throw new Error('task state disappeared');
    if (state.status === 'cancelled' || state.status === 'cancelling') {
      throw new CancelledError();
    }
    let approvedInput: ApprovedExecutionInput;
    if (state.status === 'approved') {
      const binding = await assertApprovalBinding(taskRoot, state);
      state = await transitionTaskState(taskRoot, 'approval_consumed', current => {
        const checked = requireState(current);
        const approval = checked.approval;
        if (checked.status !== 'approved' || !approval || approval.consumed_at) {
          throw new Error('approval has already been consumed');
        }
        return {
          ...checked,
          status: 'running',
          phase: 'approval_consumed',
          lesson_ir_sha256: binding.lessonIrSha256,
          review_sha256: binding.reviewSha256,
          approval: {...approval, consumed_at: new Date().toISOString()},
          attempt: newAttempt('approval_consumed', lease.token),
          error: undefined,
        };
      });
      activeAttemptId = state.attempt?.attempt_id;
      approvedInput = await approvedExecutionInput(taskRoot, state);
    } else if (state.status === 'ready_to_render' && (mode === 'render' || mode === 'all')) {
      await assertApprovalBinding(taskRoot, state);
      state = await transitionTaskState(taskRoot, 'render_resumed', current => ({
        ...requireState(current), status: 'running', phase: 'audio_ready',
        attempt: newAttempt('audio_ready', lease.token), error: undefined,
      }));
      activeAttemptId = state.attempt?.attempt_id;
      approvedInput = await approvedExecutionInput(taskRoot, state);
    } else {
      throw new Error(`task is ${state.status}; a fresh, exact-revision approval is required`);
    }

    if (cancellationSeen || await cancellationRequested(taskRoot)) throw new CancelledError();
    if (mode === 'audio' || mode === 'all') {
      let audioReady = await audioManifestIsValid(taskRoot, effects, approvedInput);
      if (!audioReady) {
        phase = 'tts_starting';
        externalEffectPossible = true;
        await transitionTaskState(taskRoot, 'tts_started', current => ({
          ...requireRunning(current), phase, attempt: {
            ...requireState(current).attempt!, phase, external_effect_possible: true,
          },
        }));
        await effects.prepareAudio(taskRoot, approvedInput);
        await effects.validateAudio(taskRoot, approvedInput);
        audioReady = true;
      }
      if (!audioReady) throw new Error('audio preparation did not produce a valid manifest');
      phase = 'audio_ready';
      externalEffectPossible = false;
      state = await transitionTaskState(taskRoot, 'audio_ready', current => ({
        ...requireRunning(current), phase, attempt: {
          ...requireState(current).attempt!, phase, external_effect_possible: false,
        },
      }));
      if (mode === 'audio') {
        return transitionTaskState(taskRoot, 'execution_paused_for_render', current => ({
          ...requireRunning(current), status: 'ready_to_render', phase: 'audio_ready',
          attempt: undefined,
        }));
      }
    } else if (!await audioManifestIsValid(taskRoot, effects, approvedInput)) {
      throw new Error('validated audio is required before render-only execution');
    }

    if (cancellationSeen || await cancellationRequested(taskRoot)) throw new CancelledError();
    phase = 'render_starting';
    await assertApprovalBinding(taskRoot, state);
    await transitionTaskState(taskRoot, 'render_started', current => ({
      ...requireRunning(current), phase, attempt: {
        ...requireState(current).attempt!, phase, external_effect_possible: false,
      },
    }));
    await effects.render(taskRoot, state.revision!, allowFixtureAudio, approvedInput);
    await effects.validateResult(taskRoot);
    if (cancellationSeen || await cancellationRequested(taskRoot)) throw new CancelledError();
    return transitionTaskState(taskRoot, 'task_completed', current => ({
      ...requireRunning(current), status: 'completed', phase: 'completed',
      attempt: undefined, error: undefined,
    }));
  } catch (error) {
    const cancelled = error instanceof CancelledError || cancellationSeen
      || await cancellationRequested(taskRoot);
    if (cancelled) {
      const result = await transitionTaskStateIf(taskRoot, 'task_cancelled', current =>
        current != null && (current.status === 'running' || current.status === 'cancelling')
          && (!activeAttemptId || current.attempt?.attempt_id === activeAttemptId), current => ({
        ...current, status: 'cancelled', phase: 'cancelled',
        cancellation: {
          requested_at: current.cancellation?.requested_at
            ?? new Date().toISOString(),
          completed_at: new Date().toISOString(),
        },
        attempt: undefined,
        error: undefined,
      }));
      if (result.state.status === 'cancelled') await quarantineLateResult(taskRoot);
      return result.state;
    }
    await recordFailure(taskRoot, phase, error, externalEffectPossible, activeAttemptId);
    throw error;
  } finally {
    process.removeListener('SIGINT', onSignal);
    process.removeListener('SIGTERM', onSignal);
    await releaseExecutionLease(taskRoot, lease);
  }
}

export async function cancelTask(
  taskRoot: string,
  hooks: {beforeStateCommit?(): Promise<void>} = {},
): Promise<TaskState> {
  const state = await ensureTaskState(taskRoot);
  if (state.status === 'completed') throw new Error('completed task cannot be cancelled');
  if (state.status === 'cancelled') return state;
  const lease = await readExecutionLease(taskRoot);
  const leaseBelongsToAttempt = lease != null
    && lease.token === state.attempt?.lease_token
    && lease.pid === state.attempt?.pid;
  const leaseLive = leaseBelongsToAttempt && lease ? await executionLeaseIsLive(lease) : false;
  const observedStatus = state.status;
  const observedAttemptId = state.attempt?.attempt_id;
  const requestedAt = new Date().toISOString();
  await hooks.beforeStateCommit?.();
  let result = await transitionTaskStateIf(taskRoot, 'cancellation_requested', current =>
    current != null && current.status === observedStatus
      && current.attempt?.attempt_id === observedAttemptId, current => ({
    ...current, status: leaseLive ? 'cancelling' : 'cancelled',
    phase: leaseLive ? 'cancelling' : 'cancelled',
    cancellation: {
      requested_at: current.cancellation?.requested_at ?? requestedAt,
      ...(leaseLive ? {} : {completed_at: new Date().toISOString()}),
    },
    attempt: leaseLive ? current.attempt : undefined,
  }));
  let next = result.state;
  if (!result.changed) return next;
  if (lease && leaseLive) {
    const group = await verifiedExecutionProcessGroup(lease);
    if (group == null) {
      throw new Error('worker identity changed or is not an isolated process group; refusing to signal it');
    }
    signalVerifiedProcessGroup(group, 'SIGTERM');
    for (let index = 0; index < 25 && await executionLeaseIsLive(lease); index += 1) {
      await new Promise(resolveWait => setTimeout(resolveWait, 100));
    }
    if (await executionLeaseIsLive(lease)) {
      const killGroup = await verifiedExecutionProcessGroup(lease);
      if (killGroup == null) {
        throw new Error('worker identity changed before forced stop; refusing to signal it');
      }
      signalVerifiedProcessGroup(killGroup, 'SIGKILL');
    }
    result = await transitionTaskStateIf(taskRoot, 'cancellation_confirmed', current =>
      current?.status === 'cancelling'
        && current.cancellation?.requested_at === next.cancellation?.requested_at
        && current.attempt?.attempt_id === observedAttemptId, current => ({
      ...current, status: 'cancelled', phase: 'cancelled',
      cancellation: {
        requested_at: current.cancellation?.requested_at ?? requestedAt,
        completed_at: new Date().toISOString(),
      },
      attempt: undefined,
    }));
    next = result.state;
  }
  if (next.status === 'cancelled') await quarantineLateResult(taskRoot);
  return next;
}

export async function recoverInterruptedTask(taskRoot: string, state?: TaskState): Promise<TaskState> {
  const current = state ?? await loadTaskState(taskRoot);
  if (!current) throw new Error('task has not been initialized');
  if (current.status !== 'running' && current.status !== 'cancelling') return current;
  const lease = await readExecutionLease(taskRoot);
  const leaseMatchesAttempt = lease != null && lease.token === current.attempt?.lease_token
    && lease.pid === current.attempt?.pid;
  if (lease && leaseMatchesAttempt && await executionLeaseIsLive(lease)) return current;
  const observedStatus = current.status;
  const observedAttemptId = current.attempt?.attempt_id;
  if (current.status === 'cancelling' || current.cancellation) {
    const result = await transitionTaskStateIf(taskRoot, 'cancel_recovered_after_restart', old =>
      old?.status === observedStatus && old.attempt?.attempt_id === observedAttemptId,
    old => ({
      ...old, status: 'cancelled', phase: 'cancelled',
      cancellation: {
        requested_at: old.cancellation?.requested_at
          ?? new Date().toISOString(),
        completed_at: new Date().toISOString(),
      },
      attempt: undefined,
    }));
    if (result.changed) await quarantineLateResult(taskRoot);
    return result.state;
  }
  return (await transitionTaskStateIf(taskRoot, 'execution_interrupted', old =>
    old?.status === observedStatus && old.attempt?.attempt_id === observedAttemptId,
  old => ({
    ...old, status: 'interrupted', phase: old.phase,
    error: {
      message: 'worker exited before recording a terminal result; automatic replay is disabled',
      phase: old.phase,
      external_effect_possible: old.attempt?.external_effect_possible === true,
    },
    attempt: undefined,
  }))).state;
}

async function currentReviewBinding(taskRoot: string): Promise<{
  revision: number; lessonIrSha256: string; reviewSha256: string;
}> {
  const review = await validateReview(taskRoot);
  return {
    revision: review.lesson_ir_revision,
    lessonIrSha256: await sha256File(resolve(taskRoot, 'output/lesson.ir.json')),
    reviewSha256: await sha256File(resolve(taskRoot, 'output/review.json')),
  };
}

async function assertApprovalBinding(taskRoot: string, state: TaskState) {
  const approval = state.approval;
  if (!approval) throw new Error('persisted approval is missing');
  const binding = await currentReviewBinding(taskRoot);
  if (approval.revision !== binding.revision
      || approval.lesson_ir_sha256 !== binding.lessonIrSha256
      || approval.review_sha256 !== binding.reviewSha256) {
    throw new Error('approved storyboard revision or digest changed; execution refused');
  }
  return binding;
}

async function audioManifestIsValid(
  taskRoot: string,
  effects: WorkerEffects,
  input: ApprovedExecutionInput,
): Promise<boolean> {
  try {
    await access(resolve(taskRoot, 'work/audio-manifest.json'));
    await effects.validateAudio(taskRoot, input);
    return true;
  } catch {
    return false;
  }
}

async function persistApprovalSnapshot(
  taskRoot: string,
  approvalId: string,
  binding: {revision: number; lessonIrSha256: string; reviewSha256: string},
): Promise<{lessonIrPath: string; reviewPath: string}> {
  const relativeRoot = `work/approved-input/${approvalId}`;
  const lessonIrPath = `${relativeRoot}/lesson.ir.json`;
  const reviewPath = `${relativeRoot}/review.json`;
  const [lessonBytes, reviewBytes] = await Promise.all([
    readFile(resolve(taskRoot, 'output/lesson.ir.json')),
    readFile(resolve(taskRoot, 'output/review.json')),
  ]);
  if (sha256Bytes(lessonBytes) !== binding.lessonIrSha256
      || sha256Bytes(reviewBytes) !== binding.reviewSha256) {
    throw new Error('storyboard files changed while the approval snapshot was being created');
  }
  await atomicWriteFile(resolve(taskRoot, lessonIrPath), lessonBytes);
  await atomicWriteFile(resolve(taskRoot, reviewPath), reviewBytes);
  if (await sha256File(resolve(taskRoot, lessonIrPath)) !== binding.lessonIrSha256
      || await sha256File(resolve(taskRoot, reviewPath)) !== binding.reviewSha256) {
    throw new Error('approval snapshot digest mismatch');
  }
  return {lessonIrPath, reviewPath};
}

async function approvedExecutionInput(
  taskRoot: string,
  state: TaskState,
): Promise<ApprovedExecutionInput> {
  const approval = state.approval;
  if (!approval?.lesson_ir_snapshot_path || !approval.review_snapshot_path) {
    throw new Error('approved immutable input snapshot is missing');
  }
  const expectedRoot = `work/approved-input/${approval.approval_id}`;
  if (approval.lesson_ir_snapshot_path !== `${expectedRoot}/lesson.ir.json`
      || approval.review_snapshot_path !== `${expectedRoot}/review.json`) {
    throw new Error('approved input snapshot path is invalid');
  }
  const approvedAudio = await loadApprovedAudioInput(taskRoot);
  const lessonIrPath = approvedAudio.lessonIrPath;
  const reviewPath = resolve(taskRoot, approval.review_snapshot_path);
  if (await sha256File(reviewPath) !== approval.review_sha256) {
    throw new Error('approved immutable input snapshot changed');
  }
  return {
    lessonIrPath,
    reviewPath,
    audioBinding: approvedAudio.binding,
    ir: approvedAudio.ir,
    request: approvedAudio.request,
  };
}

async function cancellationRequested(taskRoot: string): Promise<boolean> {
  const state = await loadTaskState(taskRoot);
  return state?.status === 'cancelling' || state?.status === 'cancelled'
    || state?.cancellation != null;
}

async function recordFailure(taskRoot: string, phase: string, error: unknown,
                             externalEffectPossible: boolean,
                             attemptId?: string): Promise<TaskState> {
  return (await transitionTaskStateIf(taskRoot, 'task_failed', current =>
    current != null && current.status === 'running'
      && (!attemptId || current.attempt?.attempt_id === attemptId), current => ({
    ...current, status: 'failed', phase,
    attempt: undefined,
    error: {
      message: safeError(error),
      phase,
      external_effect_possible: externalEffectPossible,
    },
  }))).state;
}

async function quarantineLateResult(taskRoot: string): Promise<void> {
  const result = resolve(taskRoot, 'output/result.json');
  try {
    await access(result);
    await rename(result, resolve(taskRoot, `work/result-after-cancel-${Date.now()}.json`));
  } catch (error: any) {
    if (error?.code !== 'ENOENT') throw error;
  }
}

function newAttempt(phase: string, leaseToken: string) {
  return {
    attempt_id: randomUUID(),
    lease_token: leaseToken,
    pid: process.pid,
    started_at: new Date().toISOString(),
    phase,
    external_effect_possible: false,
  };
}

function requireState(state: TaskState | undefined): TaskState {
  if (!state) throw new Error('task state is missing');
  return state;
}

function requireRunning(state: TaskState | undefined): TaskState {
  const value = requireState(state);
  if (value.status !== 'running') throw new Error(`task left running state: ${value.status}`);
  return value;
}

function assertNotTerminal(state: TaskState): void {
  if (state.status === 'cancelled' || state.status === 'completed') {
    throw new Error(`task is terminal: ${state.status}`);
  }
}

function assertCanGrantApproval(state: TaskState, allowUncertainRetry: boolean): void {
  assertNotTerminal(state);
  if (state.status === 'running' || state.status === 'cancelling') {
    throw new Error('task is running and cannot accept another approval');
  }
  if ((state.status === 'interrupted' || state.status === 'failed') && !allowUncertainRetry) {
    throw new Error('previous execution may have external side effects; inspect it and pass --retry-uncertain to approve one explicit retry');
  }
  if (state.status === 'approved' || state.status === 'ready_to_render') {
    throw new Error('approval was already recorded or consumed');
  }
}

function safeError(error: unknown): string {
  const raw = error instanceof Error ? error.message : String(error);
  return raw.replace(/Bearer\s+\S+/gi, 'Bearer [redacted]')
    .replace(/(?:api[_-]?key|token|secret)\s*[=:]\s*\S+/gi, '$1=[redacted]')
    .slice(0, 500);
}

function signalVerifiedProcessGroup(group: number, signal: NodeJS.Signals): void {
  process.kill(-group, signal);
}

class CancelledError extends Error {}

async function launchExecutionChild(args: string[]): Promise<void> {
  const child = spawn(process.execPath, [
    ...process.execArgv,
    fileURLToPath(import.meta.url),
    ...args,
    '--internal-worker',
  ], {stdio: 'inherit', detached: process.platform !== 'win32'});
  const forward = (signal: NodeJS.Signals) => {
    if (child.pid) {
      try { process.kill(-child.pid, signal); } catch { /* child already stopped */ }
    }
  };
  const onInt = () => forward('SIGINT');
  const onTerm = () => forward('SIGTERM');
  process.once('SIGINT', onInt);
  process.once('SIGTERM', onTerm);
  const code = await new Promise<number>((resolveExit, reject) => {
    child.once('error', reject);
    child.once('exit', value => resolveExit(value ?? 1));
  });
  process.removeListener('SIGINT', onInt);
  process.removeListener('SIGTERM', onTerm);
  if (code !== 0) throw new Error(`worker process exited with code ${code}`);
}

async function cli(): Promise<void> {
  const knownActions = new Set(['init', 'status', 'storyboard', 'approve', 'audio',
    'run', 'render', 'cancel']);
  const actionFirst = knownActions.has(process.argv[2] ?? '');
  const action = actionFirst ? process.argv[2]! : (process.argv[3] ?? 'status');
  const rootArgument = actionFirst ? process.argv[3] : process.argv[2];
  if (!rootArgument) throw new Error('task root is required');
  const taskRoot = resolve(rootArgument);
  const revision = optionNumber('--revision');
  const internal = process.argv.includes('--internal-worker');
  if ((action === 'storyboard' || action === 'run' || action === 'audio' || action === 'render')
      && !internal) {
    const optionStart = actionFirst ? 4 : 4;
    await launchExecutionChild([action, taskRoot,
      ...process.argv.slice(optionStart).filter(value => value !== '--internal-worker')]);
    return;
  }
  let state: TaskState;
  switch (action) {
    case 'init':
    case 'status':
      state = await ensureTaskState(taskRoot);
      break;
    case 'storyboard':
      state = await buildPersistedStoryboard(taskRoot, revision ?? 1);
      break;
    case 'approve':
      if (!revision) throw new Error('approve requires --revision <n>');
      state = await grantApproval(taskRoot, revision, process.argv.includes('--retry-uncertain'));
      break;
    case 'audio':
      state = await runApprovedTask(taskRoot, 'audio', false);
      break;
    case 'run':
      state = await runApprovedTask(taskRoot, 'all', process.argv.includes('--allow-fixture-audio'));
      break;
    case 'render': {
      const approval = optionString('--approval');
      if (approval === 'cancel') {
        state = await cancelTask(taskRoot);
        break;
      }
      if (approval === 'revise') throw new Error('revise requires a new Lesson IR and storyboard review');
      if (approval !== 'approve' || !revision) {
        throw new Error('render requires --approval approve --revision <n>');
      }
      const current = await ensureTaskState(taskRoot);
      if (current.status !== 'approved' && current.status !== 'ready_to_render') {
        await grantApproval(taskRoot, revision, false);
      }
      state = await runApprovedTask(taskRoot, 'render', process.argv.includes('--allow-fixture-audio'));
      break;
    }
    case 'cancel':
      state = await cancelTask(taskRoot);
      break;
    default:
      throw new Error('usage: task-worker.ts <task-root> <init|storyboard|approve|audio|run|render|status|cancel> [options]');
  }
  console.log(JSON.stringify({task_id: state.task_id, status: state.status,
    phase: state.phase, event_cursor: state.event_cursor}));
}

function optionString(name: string): string | undefined {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

function optionNumber(name: string): number | undefined {
  const value = optionString(name);
  if (value == null) return undefined;
  const number = Number(value);
  if (!Number.isInteger(number) || number < 1) throw new Error(`${name} must be a positive integer`);
  return number;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  cli().catch(error => {
    console.error(safeError(error));
    process.exitCode = 1;
  });
}
