import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {access, lstat, mkdir, readFile, rename} from 'node:fs/promises';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {loadApprovedAudioInput} from './approved-audio.js';
import {prepareAudio, TtsAdapterError} from './prepare-audio.js';
import {
  acquireExecutionLease,
  executionLeaseIsLive,
  statePath,
  executionLockPath,
  executionLeaseMutationLockPath,
  initializeTaskStateExact as initializePersistentTaskStateExact,
  initialTaskState,
  loadTaskState,
  readExecutionLease,
  releaseExecutionLease,
  transitionTaskState,
  transitionTaskStateIf,
  transitionTaskStateValidated,
  verifyTaskStateAudit,
  verifiedExecutionProcessId,
  verifiedExecutionProcessGroup,
  type TaskState,
} from './task-state.js';
import {atomicWriteFile, atomicWriteJson, sha256Bytes, sha256File, validateSchema, type JsonObject} from './lib.js';
import type {AudioInputBinding} from './validate-audio.js';
import {validateRequest} from './validate-request.js';
import {validateReview} from './validate-review.js';
import {inspectTask} from './inspect-task.js';
import {inspectReview} from './inspect-review.js';
import {validateIrObject} from './validate-ir.js';
import {readBoundedRegularFile} from './inspect-task.js';
import {validateBoundedInput} from './validate-bounded-input.js';
import {readAuditTail, sourceSnapshotDigest, payloadDigest, validateOperationBinding,
  operationParameters, assertOperationInput,
  canonicalJson, type VideoOperationBinding, type VideoAction, type VideoOperationReceipt} from './video-operation.js';
import {stageReviseOperation, readBoundedFeedbackStdin, type ReviseOperationResult} from './revise-operation.js';
import {revisionParkIsIntact, startRevisionOperation} from './revision-start.js';
import {closeWindowsReader} from './windows-reader.js';

export interface WorkerEffects {
  prepareAudio(taskRoot: string, input: ApprovedExecutionInput, privateTts?: PrivateTtsProvider): Promise<JsonObject>;
  render(taskRoot: string, revision: number, allowFixtureAudio: boolean,
         input: ApprovedExecutionInput): Promise<JsonObject | undefined>;
  validateAudio(taskRoot: string, input: ApprovedExecutionInput): Promise<JsonObject>;
  validateResult(taskRoot: string): Promise<JsonObject>;
}

export interface PrivateTtsProvider {
  api_key: string; base_url: string; model: string; voice: string;
  language?: string; instructions?: string; optimize_instructions?: boolean;
}

export interface ApprovedExecutionInput {
  lessonIrPath: string;
  reviewPath: string;
  audioBinding: AudioInputBinding;
  ir: JsonObject;
  request: JsonObject;
}

const productionEffects: WorkerEffects = {
  prepareAudio: async (taskRoot, input, privateTts) => prepareAudio(taskRoot, {
    irPath: input.lessonIrPath,
    inputBinding: input.audioBinding,
    irObject: input.ir,
    requestObject: input.request,
    ...(privateTts ? {
      adapterCommand: process.execPath,
      adapterArgs: [fileURLToPath(new URL('./dashscope-tts.mjs', import.meta.url))],
      adapterInput: privateTts,
      adapterEnvironment: {PATH: process.env.PATH ?? ''},
    } : {}),
  }),
  render: async (taskRoot, revision, allowFixtureAudio, input) => {
    const {renderApprovedVideo} = await import('./render-video.js');
    return renderApprovedVideo(taskRoot, {
      approval: 'approve', revision, allowFixtureAudio,
      lessonIrPath: input.lessonIrPath,
      expectedLessonIrSha256: input.audioBinding.lesson_ir_sha256,
      audioBinding: input.audioBinding,
    });
  },
  validateAudio: async (taskRoot, input) => {
    const {validateAudio} = await import('./validate-audio.js');
    return validateAudio(taskRoot, undefined, input.audioBinding, input.lessonIrPath);
  },
  validateResult: async taskRoot => {
    const {validateResult} = await import('./validate-result.js');
    return validateResult(taskRoot);
  },
};

const MAX_EXACT_INIT_REQUEST_BYTES = 1024 * 1024;
const MAX_EXACT_IR_BYTES = 2 * 1024 * 1024;
const MAX_EXACT_REVIEW_BYTES = 1024 * 1024;
const MAX_EXACT_RESULT_BYTES = 1024 * 1024;

export interface ReconcileResult extends JsonObject {
  schema_version: 1;
  outcome: 'verified_completed' | 'verified_cancelled' | 'unconfirmed';
  reason: string;
  operation_id: string;
  attempt_id: string;
  task_id: string;
  action: VideoAction;
  payload_digest: string;
  request_sha256: string;
  source_snapshot_digest: string;
  result: JsonObject | null;
  artifacts?: JsonObject[];
  result_manifest_sha256?: string;
}

/** Read-only proof check; it never repairs, recovers, or replays an action. */
export async function reconcileVideoOperation(
  taskRoot: string, taskId: string, binding: VideoOperationBinding,
): Promise<ReconcileResult> {
  const base: ReconcileResult = {schema_version: 1, outcome: 'unconfirmed', reason: 'receipt_missing',
    operation_id: binding.operation_id, attempt_id: binding.attempt_id, task_id: taskId,
    action: binding.action, payload_digest: binding.payload_digest,
    request_sha256: binding.request_sha256, source_snapshot_digest: binding.source_snapshot_digest,
    result: null};
  let produceArtifacts: JsonObject[] | undefined;
  let produceManifestHash: string | undefined;
  let verifiedManifestBytes: Buffer | undefined;
  try {
    validateOperationBinding(binding);
    if (await pathExists(resolve(taskRoot, 'work/.task-state.lock'))
        || await pathExists(executionLockPath(taskRoot))
        || await pathExists(executionLeaseMutationLockPath(taskRoot))) return {...base, reason: 'active_lease'};
    const requestBytes = await readBoundedRegularFile(resolve(taskRoot, 'request.json'), MAX_EXACT_INIT_REQUEST_BYTES);
    if (sha256Bytes(requestBytes) !== binding.request_sha256) return {...base, reason: 'task_binding_changed'};
    const request = parseBoundedRequest(requestBytes);
    if (request.task_id !== taskId) return {...base, reason: 'task_binding_changed'};
    await validateBoundedInput(taskRoot, request);
    if (await sourceSnapshotDigest(taskRoot) !== binding.source_snapshot_digest) return {...base, reason: 'source_changed'};
    if (binding.payload_digest !== payloadDigest(binding.action, operationParameters(binding))) {
      return {...base, reason: 'receipt_mismatch'};
    }
    const inspection = await inspectTask(taskRoot);
    if (inspection.task_id !== taskId) {
      return {...base, reason: 'task_binding_changed'};
    }
    const stateBytes = await readBoundedRegularFile(statePath(taskRoot), 256 * 1024);
    const state = parseBoundedRequest(stateBytes) as TaskState;
    if (state.task_id !== taskId || state.request_sha256 !== binding.request_sha256
        || state.event_cursor !== inspection.event_cursor || state.status !== inspection.status
        || state.phase !== inspection.phase || state.revision !== inspection.revision
        || state.review_sha256 !== inspection.review_sha256
        || state.lesson_ir_sha256 !== inspection.lesson_ir_sha256) return {...base, reason: 'task_binding_changed'};
    const events = await readAuditTail(taskRoot);
    const finalEvent = events.at(-1);
    if (!finalEvent || finalEvent.sequence !== state.event_cursor || finalEvent.task_id !== taskId
        || finalEvent.status !== state.status || finalEvent.phase !== state.phase
        || finalEvent.at !== state.updated_at) return {...base, reason: 'audit_malformed'};
    const receipt = finalEvent.video_operation as VideoOperationReceipt | undefined;
    if (state.status === 'cancelling' && (binding.action === 'storyboard'
        ? matchingStoryboardCancelRequest(events, binding, taskId)
        : binding.action === 'produce' && matchingProduceCancelRequest(events, binding, taskId))) {
      return {...base, reason: 'cancel_pending'};
    }
    if (state.status === 'cancelled' && finalEvent.event === 'video_operation_cancelled'
        && (binding.action === 'storyboard'
          ? matchingStoryboardCancellation(events, binding, taskId, state)
          : binding.action === 'produce' && matchingProduceCancellation(events, binding, taskId, state))) {
      if (await pathExists(resolve(taskRoot, 'work/.task-state.lock'))
          || await pathExists(executionLockPath(taskRoot))
          || await pathExists(executionLeaseMutationLockPath(taskRoot))) return {...base, reason: 'active_lease'};
      const afterRequest = await readBoundedRegularFile(resolve(taskRoot, 'request.json'), MAX_EXACT_INIT_REQUEST_BYTES);
      const afterSource = await sourceSnapshotDigest(taskRoot);
      const afterInspection = await inspectTask(taskRoot);
      const afterEvents = await readAuditTail(taskRoot);
      const stateAfter = await readBoundedRegularFile(statePath(taskRoot), 256 * 1024);
      if (!requestBytes.equals(afterRequest) || !stateBytes.equals(stateAfter)
          || afterSource !== binding.source_snapshot_digest
          || canonicalJson(afterInspection) !== canonicalJson(inspection)
          || canonicalJson(afterEvents.at(-1)) !== canonicalJson(finalEvent)) {
        return {...base, reason: 'receipt_mismatch'};
      }
      return {...base, outcome: 'verified_cancelled', reason: 'cancel_receipt_match', result: null};
    }
    if (!receipt) {
      const legacy = events.some(event => event.event === 'task_initialized_exact'
        || event.event === 'storyboard_ready_exact' || event.event === 'approval_granted_bound');
      return {...base, reason: legacy ? 'legacy_no_receipt' : 'receipt_missing'};
    }
    // A revision commits its receipt only when a later step installs the new
    // revision. Until then there is no event to verify, so the outcome must stay
    // unknown rather than borrowing another action's event name.
    const expectedEventName = binding.action === 'initialize' ? 'task_initialized_operation'
      : binding.action === 'storyboard' ? 'storyboard_ready_exact'
        : binding.action === 'approve' ? 'approval_granted_bound'
          : binding.action === 'produce' ? 'video_operation_completed' : undefined;
    if (!expectedEventName) return {...base, reason: 'receipt_missing'};
    if (finalEvent.event !== expectedEventName) return {...base, reason: 'receipt_mismatch'};
    const expectedInputCursor = binding.input_event_cursor;
    const expectedResultCursor = binding.action === 'produce' ? receipt.result_event_cursor
      : binding.action === 'initialize' ? 1
        : expectedInputCursor + (binding.action === 'storyboard' ? 2 : 1);
    if (receipt.schema_version !== 1 || receipt.operation_id !== binding.operation_id
        || receipt.attempt_id !== binding.attempt_id || receipt.action !== binding.action
        || receipt.payload_digest !== binding.payload_digest
        || receipt.source_snapshot_digest !== binding.source_snapshot_digest
        || receipt.request_sha256 !== binding.request_sha256
        || receipt.input_event_cursor !== expectedInputCursor
        || receipt.result_event_cursor !== expectedResultCursor
        || (binding.revision != null && receipt.revision !== binding.revision)
        || (binding.review_sha256 != null && receipt.review_sha256 !== binding.review_sha256)
        || (binding.lesson_ir_sha256 != null && receipt.lesson_ir_sha256 !== binding.lesson_ir_sha256)
        || state.event_cursor !== expectedResultCursor
        || !validReceiptShape(receipt, binding.action)) return {...base, reason: 'receipt_mismatch'};
    if (binding.action === 'initialize') {
      if (state.status !== 'initialized' || state.phase !== 'idle') return {...base, reason: 'receipt_mismatch'};
    } else {
      const review = await inspectReview(taskRoot);
      if (review.event_cursor !== state.event_cursor || review.revision !== binding.revision
          || review.review_sha256 !== state.review_sha256 || review.lesson_ir_sha256 !== state.lesson_ir_sha256) {
        return {...base, reason: 'receipt_mismatch'};
      }
      if (binding.action === 'storyboard') {
      if (state.status !== 'awaiting_storyboard_review' || state.phase !== 'awaiting_approval'
            || state.event_cursor !== expectedInputCursor + 2 || !matchingStoryboardStart(events, binding, taskId)) {
          return {...base, reason: 'receipt_mismatch'};
        }
        if (receipt.review_sha256 !== state.review_sha256
            || receipt.lesson_ir_sha256 !== state.lesson_ir_sha256
            || review.review_sha256 !== receipt.review_sha256
            || review.lesson_ir_sha256 !== receipt.lesson_ir_sha256) {
          return {...base, reason: 'receipt_mismatch'};
        }
      } else if (binding.action === 'produce') {
        if (state.status !== 'completed' || state.phase !== 'completed'
            || state.event_cursor !== receipt.result_event_cursor
            || !events.some(event => event.event === 'approval_consumed'
              && matchingProduceStart(event, binding, taskId))) {
          return {...base, reason: 'receipt_mismatch'};
        }
        if (receipt.allow_cloud_tts !== true) return {...base, reason: 'receipt_mismatch'};
        const expectedManifestHash = finalEvent.result_manifest_sha256;
        if (typeof expectedManifestHash !== 'string' || !/^[a-f0-9]{64}$/.test(expectedManifestHash)) {
          return {...base, reason: 'receipt_mismatch'};
        }
        const resultPath = resolve(taskRoot, 'output/result.json');
        const manifestBefore = await readBoundedRegularFile(resultPath, MAX_EXACT_RESULT_BYTES);
        if (sha256Bytes(manifestBefore) !== expectedManifestHash) return {...base, reason: 'receipt_mismatch'};
        const {validateResult} = await import('./validate-result.js');
        const validatedResult = await validateResult(taskRoot);
        const manifestAfter = await readBoundedRegularFile(resultPath, MAX_EXACT_RESULT_BYTES);
        if (!manifestBefore.equals(manifestAfter) || sha256Bytes(manifestAfter) !== expectedManifestHash) {
          return {...base, reason: 'receipt_mismatch'};
        }
        produceArtifacts = projectValidatedArtifacts(validatedResult);
        produceManifestHash = expectedManifestHash;
        verifiedManifestBytes = manifestAfter;
      } else {
        if (state.status !== 'approved' || state.phase !== 'approval_pending'
            || state.event_cursor !== expectedInputCursor + 1 || !state.approval
            || receipt.approval_id !== state.approval.approval_id
            || state.approval.revision !== binding.revision
            || state.approval.review_sha256 !== binding.review_sha256
            || state.approval.lesson_ir_sha256 !== binding.lesson_ir_sha256) {
          return {...base, reason: 'receipt_mismatch'};
        }
        const root = `work/approved-input/${state.approval.approval_id}/`;
        if (state.approval.lesson_ir_snapshot_path !== `${root}lesson.ir.json`
            || state.approval.review_snapshot_path !== `${root}review.json`) {
          return {...base, reason: 'approval_snapshot_invalid'};
        }
        if (!await realDirectoryChain(taskRoot, ['work', 'approved-input', state.approval.approval_id])) {
          return {...base, reason: 'approval_snapshot_invalid'};
        }
        const ir = await readBoundedRegularFile(resolve(taskRoot, state.approval.lesson_ir_snapshot_path), MAX_EXACT_IR_BYTES);
        const rev = await readBoundedRegularFile(resolve(taskRoot, state.approval.review_snapshot_path), MAX_EXACT_REVIEW_BYTES);
        if (sha256Bytes(ir) !== binding.lesson_ir_sha256 || sha256Bytes(rev) !== binding.review_sha256) {
          return {...base, reason: 'approval_snapshot_invalid'};
        }
        const [irAgain, reviewAgain] = await Promise.all([
          readBoundedRegularFile(resolve(taskRoot, state.approval.lesson_ir_snapshot_path), MAX_EXACT_IR_BYTES),
          readBoundedRegularFile(resolve(taskRoot, state.approval.review_snapshot_path), MAX_EXACT_REVIEW_BYTES),
        ]);
        if (!ir.equals(irAgain) || !rev.equals(reviewAgain)) return {...base, reason: 'approval_snapshot_invalid'};
      }
    }
    if (await pathExists(resolve(taskRoot, 'work/.task-state.lock'))
        || await pathExists(executionLockPath(taskRoot))
        || await pathExists(executionLeaseMutationLockPath(taskRoot))) return {...base, reason: 'active_lease'};
    const afterRequest = await readBoundedRegularFile(resolve(taskRoot, 'request.json'), MAX_EXACT_INIT_REQUEST_BYTES);
    const afterSource = await sourceSnapshotDigest(taskRoot);
    const afterInspection = await inspectTask(taskRoot);
    const afterEvents = await readAuditTail(taskRoot);
    const stateAfter = await readBoundedRegularFile(statePath(taskRoot), 256 * 1024);
    if (binding.action !== 'initialize') {
      const afterReview = await inspectReview(taskRoot);
      if (afterReview.event_cursor !== inspection.event_cursor
          || afterReview.revision !== binding.revision
          || afterReview.review_sha256 !== inspection.review_sha256
          || afterReview.lesson_ir_sha256 !== inspection.lesson_ir_sha256) {
        return {...base, reason: 'receipt_mismatch'};
      }
    }
    if (!requestBytes.equals(afterRequest) || !stateBytes.equals(stateAfter)
        || afterSource !== binding.source_snapshot_digest
        || canonicalJson(afterInspection) !== canonicalJson(inspection)
        || canonicalJson(afterEvents.at(-1)) !== canonicalJson(finalEvent)) {
      return {...base, reason: 'receipt_mismatch'};
    }
    if (await pathExists(resolve(taskRoot, 'work/.task-state.lock'))
        || await pathExists(executionLockPath(taskRoot))
        || await pathExists(executionLeaseMutationLockPath(taskRoot))) return {...base, reason: 'active_lease'};
    if (verifiedManifestBytes) {
      const finalManifestBytes = await readBoundedRegularFile(resolve(taskRoot, 'output/result.json'), MAX_EXACT_RESULT_BYTES);
      if (!verifiedManifestBytes.equals(finalManifestBytes) || sha256Bytes(finalManifestBytes) !== produceManifestHash) {
        return {...base, reason: 'receipt_mismatch'};
      }
    }
    return {...base, outcome: 'verified_completed', reason: 'receipt_match', result: inspection,
      ...(produceArtifacts ? {artifacts: produceArtifacts, result_manifest_sha256: produceManifestHash} : {})};
  } catch {
    return {...base, reason: 'audit_malformed'};
  }
}

export interface CancelOperationResult extends JsonObject {
  object: 'padnote.video.cancel';
  protocol_version: 1;
  operation_id: string;
  attempt_id: string;
  status: 'verified_cancelled' | 'unconfirmed';
  reason: 'cancel_receipt_match' | 'binding_mismatch' | 'task_binding_changed' | 'source_changed'
    | 'not_running' | 'active_lease_missing' | 'worker_identity_unavailable'
    | 'cancel_pending' | 'process_group_still_alive' | 'cancel_commit_unconfirmed';
}

/** Requests cancellation only for the exact live storyboard process group in this operation. */
export async function cancelStoryboardOperation(
  taskRoot: string,
  taskId: string,
  requestSha256: string,
  eventCursor: number,
  revision: number,
  binding: VideoOperationBinding,
): Promise<CancelOperationResult> {
  const base: CancelOperationResult = {
    object: 'padnote.video.cancel', protocol_version: 1,
    operation_id: binding.operation_id, attempt_id: binding.attempt_id,
    status: 'unconfirmed', reason: 'binding_mismatch',
  };
  try {
    validateExactActionArguments(taskId, requestSha256, revision, eventCursor);
    validateOperationBinding(binding);
    if (binding.action !== 'storyboard' || binding.request_sha256 !== requestSha256
        || binding.input_event_cursor !== eventCursor || binding.revision !== revision
        || binding.payload_digest !== payloadDigest('storyboard', {revision, event_cursor: eventCursor})) {
      return base;
    }
    const sourceDigest = await sourceSnapshotDigest(taskRoot);
    if (sourceDigest !== binding.source_snapshot_digest) return {...base, reason: 'source_changed'};
    await assertOperationInput(taskRoot, binding, 'storyboard', {revision, event_cursor: eventCursor});
    const startedCursor = eventCursor + 1;
    const requestedCursor = eventCursor + 2;
    const completedCursor = eventCursor + 3;
    if (![startedCursor, requestedCursor, completedCursor].every(Number.isSafeInteger)) return base;

    let current = await verifyTaskStateAudit(taskRoot, {
      task_id: taskId, request_sha256: requestSha256,
      event_cursor: (await inspectTask(taskRoot)).event_cursor,
    });
    if (current.status === 'running' && current.phase === 'storyboard'
        && current.event_cursor === startedCursor && current.attempt?.lease_token) {
      const lease = await readExecutionLease(taskRoot);
      if (!lease || lease.token !== current.attempt.lease_token || lease.pid !== current.attempt.pid) {
        return {...base, reason: 'active_lease_missing'};
      }
      if (process.platform !== 'win32') {
        if (!await executionLeaseIsLive(lease)) return {...base, reason: 'active_lease_missing'};
        const group = await verifiedExecutionProcessGroup(lease);
        if (group == null || group !== lease.pid) return {...base, reason: 'worker_identity_unavailable'};
      }
      const events = await readAuditTail(taskRoot);
      if (!matchingStoryboardStart(events, binding, taskId)) return {...base, reason: 'binding_mismatch'};
      current = await transitionTaskStateValidated(taskRoot, 'video_operation_cancel_requested', async locked => {
        await assertOperationInput(taskRoot, binding, 'storyboard', {revision, event_cursor: eventCursor});
        if (locked.status !== 'running' || locked.phase !== 'storyboard'
            || locked.event_cursor !== startedCursor || locked.attempt?.lease_token !== lease.token
            || locked.attempt.pid !== lease.pid || !matchingStoryboardStart(await readAuditTail(taskRoot), binding, taskId)) {
          throw new Error('running storyboard changed before cancellation');
        }
        const latestLease = await readExecutionLease(taskRoot);
        if (!latestLease || latestLease.token !== lease.token || latestLease.pid !== lease.pid
            || process.platform !== 'win32' && !await executionLeaseIsLive(latestLease)) {
          throw new Error('storyboard execution lease is no longer live');
        }
        return {
          ...locked, status: 'cancelling', phase: 'cancelling',
          cancellation: {requested_at: new Date().toISOString()},
        };
      }, {video_operation_cancel_requested: {
        schema_version: 1, operation_id: binding.operation_id, attempt_id: binding.attempt_id,
        action: 'storyboard', payload_digest: binding.payload_digest,
        source_snapshot_digest: binding.source_snapshot_digest,
        request_sha256: binding.request_sha256, input_event_cursor: eventCursor,
        revision, worker_attempt_id: current.attempt.attempt_id,
      }});
    } else if (current.status === 'cancelling' && current.phase === 'cancelling'
        && current.event_cursor === requestedCursor && current.attempt?.lease_token) {
      const events = await readAuditTail(taskRoot);
      if (!matchingStoryboardCancelRequest(events, binding, taskId)) {
        return {...base, reason: 'binding_mismatch'};
      }
    } else {
      return {...base, reason: 'not_running'};
    }

    const workerAttemptId = current.attempt?.attempt_id;
    if (!workerAttemptId) return {...base, reason: 'binding_mismatch'};
    const lease = await readExecutionLease(taskRoot);
    if (!lease || lease.token !== current.attempt?.lease_token || lease.pid !== current.attempt?.pid) {
      return {...base, reason: 'active_lease_missing'};
    }
    if (process.platform === 'win32') {
      // Windows process-tree termination belongs to the Python VideoWorker's
      // verified Job Object. First commit the exact cancel request, then let
      // the owner terminate its Job and invoke this action again to reconcile.
      if (await verifiedExecutionProcessId(lease) !== undefined) return {...base, reason: 'cancel_pending'};
    } else {
      const processGroup = lease.process_identity.process_group_id;
      if (processGroup !== lease.pid || lease.process_identity.platform !== process.platform) {
        return {...base, reason: 'worker_identity_unavailable'};
      }
      if (await processGroupExists(processGroup)) {
        const verifiedGroup = await verifiedExecutionProcessGroup(lease);
        if (verifiedGroup !== processGroup) return {...base, reason: 'worker_identity_unavailable'};
        signalVerifiedProcessGroup(verifiedGroup, 'SIGTERM');
        if (!await waitForProcessGroupExit(processGroup, 1_500)) {
          if (await verifiedExecutionProcessGroup(lease) !== processGroup) {
            return {...base, reason: 'worker_identity_unavailable'};
          }
          try { process.kill(-processGroup, 'SIGKILL'); }
          catch (error: any) { if (error?.code !== 'ESRCH') return {...base, reason: 'worker_identity_unavailable'}; }
        }
      }
      if (!await waitForProcessGroupExit(processGroup, 2_000)) {
        return {...base, reason: 'process_group_still_alive'};
      }
    }
    const events = await readAuditTail(taskRoot);
    const requestEvent = events.find(event => event.sequence === requestedCursor);
    const requested = requestEvent?.video_operation_cancel_requested as JsonObject | undefined;
    if (!matchingStoryboardCancelRequest(events, binding, taskId)
        || requested?.worker_attempt_id !== workerAttemptId) {
      return {...base, reason: 'binding_mismatch'};
    }
    await releaseExecutionLease(taskRoot, lease);
    const completed = await transitionTaskStateValidated(taskRoot, 'video_operation_cancelled', async locked => {
      await assertOperationInput(taskRoot, binding, 'storyboard', {revision, event_cursor: eventCursor});
      const tail = await readAuditTail(taskRoot);
      if (locked.status !== 'cancelling' || locked.phase !== 'cancelling'
          || locked.event_cursor !== requestedCursor
          || locked.attempt?.attempt_id !== workerAttemptId
          || locked.attempt.lease_token !== lease.token
          || !matchingStoryboardCancelRequest(tail, binding, taskId)) return undefined;
      const latestLease = await readExecutionLease(taskRoot);
      if (latestLease || await pathExists(executionLockPath(taskRoot))
          || process.platform === 'win32' && await verifiedExecutionProcessId(lease) !== undefined
          || process.platform !== 'win32' && lease.process_identity.process_group_id != null
            && await processGroupExists(lease.process_identity.process_group_id)) return undefined;
      return {
        ...locked, status: 'cancelled', phase: 'cancelled', attempt: undefined,
        cancellation: {
          requested_at: locked.cancellation?.requested_at ?? new Date().toISOString(),
          completed_at: new Date().toISOString(),
        },
      };
    }, {video_operation_cancelled: {
      schema_version: 1, operation_id: binding.operation_id, attempt_id: binding.attempt_id,
      action: 'storyboard', payload_digest: binding.payload_digest,
      source_snapshot_digest: binding.source_snapshot_digest,
      request_sha256: binding.request_sha256, input_event_cursor: eventCursor,
      revision, worker_attempt_id: workerAttemptId,
    }});
    if (completed.status !== 'cancelled' || completed.event_cursor !== completedCursor) {
      return {...base, reason: 'cancel_commit_unconfirmed'};
    }
    return {...base, status: 'verified_cancelled', reason: 'cancel_receipt_match'};
  } catch {
    return {...base, reason: 'cancel_commit_unconfirmed'};
  }
}

async function processGroupExists(group: number): Promise<boolean> {
  if (!Number.isInteger(group) || group <= 0 || process.platform === 'win32') return false;
  try {
    process.kill(-group, 0);
    return true;
  } catch (error: any) {
    if (error?.code === 'ESRCH') return false;
    if (error?.code === 'EPERM') return true;
    throw error;
  }
}

async function waitForProcessGroupExit(group: number, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (!await processGroupExists(group)) return true;
    await new Promise(resolveWait => setTimeout(resolveWait, 50));
  }
  return !await processGroupExists(group);
}

function matchingStoryboardCancelRequest(events: JsonObject[], binding: VideoOperationBinding,
                                         taskId: string): boolean {
  const request = events.find(item => item.sequence === binding.input_event_cursor + 2);
  const details = request?.video_operation_cancel_requested as JsonObject | undefined;
  return matchingStoryboardStart(events, binding, taskId)
    && request?.event === 'video_operation_cancel_requested'
    && request.task_id === taskId && request.status === 'cancelling' && request.phase === 'cancelling'
    && details?.schema_version === 1 && details.operation_id === binding.operation_id
    && details.attempt_id === binding.attempt_id && details.action === 'storyboard'
    && details.payload_digest === binding.payload_digest
    && details.source_snapshot_digest === binding.source_snapshot_digest
    && details.request_sha256 === binding.request_sha256
    && details.input_event_cursor === binding.input_event_cursor && details.revision === binding.revision
    && typeof details.worker_attempt_id === 'string';
}

function matchingStoryboardCancellation(events: JsonObject[], binding: VideoOperationBinding,
                                         taskId: string, state: TaskState): boolean {
  const receipt = events.at(-1)?.video_operation_cancelled as JsonObject | undefined;
  const requestEvent = events.find(item => item.sequence === binding.input_event_cursor + 2);
  const requested = requestEvent?.video_operation_cancel_requested as JsonObject | undefined;
  const finalEvent = events.at(-1);
  const receiptKeys = ['schema_version', 'operation_id', 'attempt_id', 'action', 'payload_digest',
    'source_snapshot_digest', 'request_sha256', 'input_event_cursor', 'revision', 'worker_attempt_id'];
  return matchingStoryboardCancelRequest(events, binding, taskId)
    && requestEvent?.at === state.cancellation?.requested_at
    && finalEvent?.sequence === binding.input_event_cursor + 3
    && finalEvent.task_id === taskId && finalEvent.status === 'cancelled'
    && finalEvent.phase === 'cancelled' && finalEvent.at === state.cancellation?.completed_at
    && receipt?.schema_version === 1 && receipt.operation_id === binding.operation_id
    && receipt.attempt_id === binding.attempt_id && receipt.action === 'storyboard'
    && receipt.payload_digest === binding.payload_digest
    && receipt.source_snapshot_digest === binding.source_snapshot_digest
    && receipt.request_sha256 === binding.request_sha256
    && receipt.input_event_cursor === binding.input_event_cursor
    && receipt.cancel_input_event_cursor === requested?.cancel_input_event_cursor
    && receipt.revision === binding.revision
    && receipt.worker_attempt_id === requested?.worker_attempt_id
    && canonicalJson(Object.keys(receipt).sort()) === canonicalJson([...receiptKeys].sort())
    && state.status === 'cancelled' && state.phase === 'cancelled'
    && state.attempt == null;
}

async function pathExists(path: string): Promise<boolean> {
  try { await lstat(path); return true; }
  catch (error: any) { if (error?.code === 'ENOENT') return false; throw error; }
}

async function ensureOutputDirectory(taskRoot: string): Promise<void> {
  const path = resolve(taskRoot, 'output');
  try { await mkdir(path); }
  catch (error: any) { if (error?.code !== 'EEXIST') throw error; }
  const info = await lstat(path);
  if (!info.isDirectory() || info.isSymbolicLink()) throw new Error('invalid output directory');
}

async function realDirectoryChain(root: string, segments: string[]): Promise<boolean> {
  let current = root;
  for (const segment of segments) {
    current = resolve(current, segment);
    try {
      const info = await lstat(current);
      if (!info.isDirectory() || info.isSymbolicLink()) return false;
    } catch { return false; }
  }
  return true;
}

function matchingStoryboardStart(events: JsonObject[], binding: VideoOperationBinding, taskId: string): boolean {
  const event = events.find(item => item.sequence === binding.input_event_cursor + 1);
  const start = event?.video_operation_started as JsonObject | undefined;
  return event?.event === 'storyboard_started_exact' && event.task_id === taskId
    && event.sequence === binding.input_event_cursor + 1 && event.status === 'running'
    && event.phase === 'storyboard' && start?.schema_version === 1
    && start.operation_id === binding.operation_id
    && start.attempt_id === binding.attempt_id && start.payload_digest === binding.payload_digest
    && start.source_snapshot_digest === binding.source_snapshot_digest
    && start.action === 'storyboard' && start.request_sha256 === binding.request_sha256
    && start.input_event_cursor === binding.input_event_cursor && start.revision === binding.revision;
}

export async function initializeOperationTask(taskRoot: string, taskId: string,
                                               requestSha256: string, binding: VideoOperationBinding) {
  await assertOperationInput(taskRoot, binding, 'initialize', {});
  return initializeExactTask(taskRoot, taskId, requestSha256, binding);
}

export async function buildOperationStoryboard(taskRoot: string, taskId: string,
                                                requestSha256: string, revision: number,
                                                eventCursor: number, binding: VideoOperationBinding,
                                                authorLessonIr?: () => Promise<JsonObject>) {
  return buildExactStoryboard(taskRoot, taskId, requestSha256, revision, eventCursor, binding, authorLessonIr);
}

function operationStartedDetails(binding: VideoOperationBinding): JsonObject {
  return {
    schema_version: 1, operation_id: binding.operation_id, attempt_id: binding.attempt_id,
    action: 'produce', payload_digest: binding.payload_digest,
    source_snapshot_digest: binding.source_snapshot_digest,
    request_sha256: binding.request_sha256, input_event_cursor: binding.input_event_cursor,
    revision: binding.revision!, review_sha256: binding.review_sha256!,
    lesson_ir_sha256: binding.lesson_ir_sha256!, allow_cloud_tts: true,
  };
}

function matchingProduceStart(event: JsonObject, binding: VideoOperationBinding, taskId: string): boolean {
  const start = event.video_operation_started as JsonObject | undefined;
  return event.task_id === taskId && event.status === 'running'
    && start?.schema_version === 1 && start.operation_id === binding.operation_id
    && start.attempt_id === binding.attempt_id && start.action === 'produce'
    && start.payload_digest === binding.payload_digest
    && start.source_snapshot_digest === binding.source_snapshot_digest
    && start.request_sha256 === binding.request_sha256
    && start.input_event_cursor === binding.input_event_cursor
    && start.revision === binding.revision && start.review_sha256 === binding.review_sha256
    && start.lesson_ir_sha256 === binding.lesson_ir_sha256 && start.allow_cloud_tts === true;
}

function produceCancellationDetails(binding: VideoOperationBinding, taskId: string,
                                    workerAttemptId?: string, cancelInputEventCursor?: number,
                                    cancelRequestedAt?: string): JsonObject {
  return {
    schema_version: 1, operation_id: binding.operation_id, attempt_id: binding.attempt_id,
    action: 'produce', payload_digest: binding.payload_digest,
    source_snapshot_digest: binding.source_snapshot_digest,
    request_sha256: binding.request_sha256, input_event_cursor: binding.input_event_cursor,
    revision: binding.revision!, review_sha256: binding.review_sha256!,
    lesson_ir_sha256: binding.lesson_ir_sha256!, allow_cloud_tts: true,
    task_id: taskId, ...(workerAttemptId ? {worker_attempt_id: workerAttemptId} : {}),
    ...(cancelInputEventCursor !== undefined ? {cancel_input_event_cursor: cancelInputEventCursor} : {}),
    ...(cancelRequestedAt ? {cancel_requested_at: cancelRequestedAt} : {}),
  };
}

function produceCancelRequestEvent(events: JsonObject[], binding: VideoOperationBinding,
                                   taskId: string): JsonObject | undefined {
  return events.find(event => {
    const details = event.video_operation_cancel_requested as JsonObject | undefined;
    return event.event === 'video_operation_cancel_requested' && event.task_id === taskId
      && event.status === 'cancelling' && event.phase === 'cancelling'
      && details?.operation_id === binding.operation_id && details.attempt_id === binding.attempt_id
      && details.action === 'produce' && Number.isSafeInteger(details.cancel_input_event_cursor)
      && event.sequence === Number(details.cancel_input_event_cursor) + 1;
  });
}

function matchingProduceCancelRequest(events: JsonObject[], binding: VideoOperationBinding, taskId: string): boolean {
  const event = produceCancelRequestEvent(events, binding, taskId);
  const details = event?.video_operation_cancel_requested as JsonObject | undefined;
  return event?.event === 'video_operation_cancel_requested' && event.task_id === taskId
    && event.status === 'cancelling' && event.phase === 'cancelling'
    && details?.schema_version === 1 && details.operation_id === binding.operation_id
    && details.attempt_id === binding.attempt_id && details.action === 'produce'
    && details.payload_digest === binding.payload_digest
    && details.source_snapshot_digest === binding.source_snapshot_digest
    && details.request_sha256 === binding.request_sha256
    && details.input_event_cursor === binding.input_event_cursor && details.revision === binding.revision
    && details.review_sha256 === binding.review_sha256
    && details.lesson_ir_sha256 === binding.lesson_ir_sha256 && details.allow_cloud_tts === true
    && typeof details.cancel_input_event_cursor === 'number'
    && typeof details.cancel_requested_at === 'string'
    && typeof details.worker_attempt_id === 'string';
}

function matchingProduceCancellation(events: JsonObject[], binding: VideoOperationBinding,
                                    taskId: string, state: TaskState): boolean {
  const finalEvent = events.at(-1);
  const receipt = finalEvent?.video_operation_cancelled as JsonObject | undefined;
  const requestEvent = produceCancelRequestEvent(events, binding, taskId);
  const requested = requestEvent?.video_operation_cancel_requested as JsonObject | undefined;
  return matchingProduceCancelRequest(events, binding, taskId)
    && requested?.cancel_requested_at === state.cancellation?.requested_at
    && finalEvent?.sequence === requestEvent?.sequence! + 1
    && finalEvent != null
    && finalEvent.task_id === taskId && finalEvent.status === 'cancelled'
    && finalEvent.phase === 'cancelled'
    && receipt?.schema_version === 1 && receipt.operation_id === binding.operation_id
    && receipt.attempt_id === binding.attempt_id && receipt.action === 'produce'
    && receipt.payload_digest === binding.payload_digest
    && receipt.source_snapshot_digest === binding.source_snapshot_digest
    && receipt.request_sha256 === binding.request_sha256
    && receipt.input_event_cursor === binding.input_event_cursor
    && receipt.cancel_input_event_cursor === requested?.cancel_input_event_cursor
    && receipt.cancel_requested_at === requested?.cancel_requested_at
    && receipt.revision === binding.revision && receipt.review_sha256 === binding.review_sha256
    && receipt.lesson_ir_sha256 === binding.lesson_ir_sha256 && receipt.allow_cloud_tts === true
    && receipt.worker_attempt_id === requested?.worker_attempt_id
    && state.status === 'cancelled' && state.phase === 'cancelled' && state.attempt == null;
}

function projectValidatedArtifacts(result: JsonObject): JsonObject[] {
  const roles = ['video', 'captions', 'thumbnail', 'render_manifest', 'qa_report'];
  if (!Array.isArray(result.artifacts) || result.artifacts.length !== roles.length) {
    throw new Error('validated result artifact set is invalid');
  }
  const artifacts = result.artifacts as JsonObject[];
  const roleMediaTypes: Record<string, string> = {video: 'video/mp4', captions: 'application/x-subrip',
    thumbnail: 'image/png', render_manifest: 'application/json', qa_report: 'application/json'};
  if (JSON.stringify(artifacts.map(artifact => artifact.role).sort()) !== JSON.stringify([...roles].sort())) {
    throw new Error('validated result artifact roles are invalid');
  }
  return artifacts.map(artifact => {
    if (Object.keys(artifact).sort().join(',') !== 'id,media_type,path,role,sha256,size_bytes'
        || typeof artifact.id !== 'string' || typeof artifact.path !== 'string'
        || typeof artifact.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(artifact.sha256)
        || !Number.isSafeInteger(artifact.size_bytes) || Number(artifact.size_bytes) < 1
        || artifact.media_type !== roleMediaTypes[String(artifact.role)]) {
      throw new Error('validated result artifact descriptor is invalid');
    }
    return {id: artifact.id, role: artifact.role, path: artifact.path,
      media_type: artifact.media_type, size_bytes: artifact.size_bytes, sha256: artifact.sha256};
  });
}

function validatePrivateTtsBudget(input: ApprovedExecutionInput, provider: PrivateTtsProvider): void {
  const scenes = input.ir.scenes;
  if (!Array.isArray(scenes) || scenes.length < 1 || scenes.length > 24
      || scenes.some(scene => typeof scene?.narration !== 'string'
        || scene.narration.length < 1 || scene.narration.length > 600)
      || scenes.reduce((sum, scene) => sum + String(scene?.narration ?? '').length, 0) > 8_000) {
    throw new Error('approved narration exceeds the configured TTS cost budget');
  }
  let endpoint: URL;
  try { endpoint = new URL(provider.base_url); } catch { throw new Error('unsupported built-in TTS region configuration'); }
  if (endpoint.protocol !== 'https:' || endpoint.hostname !== 'dashscope.aliyuncs.com'
      || (endpoint.port && endpoint.port !== '443') || endpoint.pathname !== '/api/v1'
      || endpoint.search || endpoint.hash) {
    throw new Error('unsupported built-in TTS region configuration');
  }
  if (!/^qwen3-tts-(?:instruct-)?flash(?:-[a-z0-9.-]+)?$/i.test(provider.model)
      || (provider.instructions && provider.instructions.length > 4_000)) {
    throw new Error('unsupported built-in TTS model configuration');
  }
}

export async function grantOperationApproval(taskRoot: string, taskId: string,
                                              requestSha256: string, revision: number,
                                              eventCursor: number, reviewSha256: string,
                                              lessonIrSha256: string, binding: VideoOperationBinding) {
  return grantBoundApproval(taskRoot, taskId, requestSha256, revision, eventCursor,
    reviewSha256, lessonIrSha256, binding);
}

function validReceiptShape(receipt: VideoOperationReceipt, action: VideoAction): boolean {
  // A revision has no committed receipt shape yet, so no receipt may claim to be one.
  if (action === 'revise') return false;
  const common = ['schema_version', 'operation_id', 'attempt_id', 'action', 'payload_digest',
    'source_snapshot_digest', 'request_sha256', 'input_event_cursor', 'result_event_cursor'];
  const extras = action === 'initialize' ? [] : action === 'storyboard'
    ? ['revision', 'lesson_ir_sha256', 'review_sha256']
    : action === 'approve' ? ['revision', 'lesson_ir_sha256', 'review_sha256', 'approval_id']
      : ['revision', 'lesson_ir_sha256', 'review_sha256', 'allow_cloud_tts'];
  return canonicalJson(Object.keys(receipt).sort()) === canonicalJson([...common, ...extras].sort());
}

/** Initializes a task only when its original bounded request and input are exact. */
export async function initializeExactTask(
  taskRoot: string,
  expectedWorkerTaskId: string,
  expectedRequestSha256: string,
  operation?: VideoOperationBinding,
): Promise<TaskState> {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(expectedWorkerTaskId)
      || !/^[a-f0-9]{64}$/.test(expectedRequestSha256)) {
    throw new Error('exact task initialization failed');
  }
  const validateBinding = async () => {
    try {
      if (operation) await assertOperationInput(taskRoot, operation, 'initialize', {});
      const rootInfo = await lstat(taskRoot);
      const workInfo = await lstat(resolve(taskRoot, 'work'));
      if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink()
          || !workInfo.isDirectory() || workInfo.isSymbolicLink()) {
        throw new Error('invalid task directory');
      }
      const requestPath = resolve(taskRoot, 'request.json');
      const requestBytes = await readBoundedRegularFile(requestPath, MAX_EXACT_INIT_REQUEST_BYTES);
      if (sha256Bytes(requestBytes) !== expectedRequestSha256) throw new Error('request digest mismatch');
      const request = parseBoundedRequest(requestBytes);
      if (request.task_id !== expectedWorkerTaskId) throw new Error('request identity mismatch');
      await validateBoundedInput(taskRoot, request);
      const requestBytesAfter = await readBoundedRegularFile(requestPath, MAX_EXACT_INIT_REQUEST_BYTES);
      if (!requestBytes.equals(requestBytesAfter)
          || sha256Bytes(requestBytesAfter) !== expectedRequestSha256) {
        throw new Error('request changed during validation');
      }
    } catch {
      throw new Error('exact task initialization failed');
    }
  };
  const readExistingState = async (): Promise<TaskState> => {
    try {
      const statePathValue = resolve(taskRoot, 'work/task-state.json');
      const stateBytesBefore = await readBoundedRegularFile(statePathValue, 256 * 1024);
      const inspection = await inspectTask(taskRoot);
      const stateBytesAfter = await readBoundedRegularFile(statePathValue, 256 * 1024);
      if (!stateBytesBefore.equals(stateBytesAfter)) throw new Error('state changed during inspection');
      const state = parseBoundedRequest(stateBytesAfter) as TaskState;
      if (state.task_id !== expectedWorkerTaskId
          || state.request_sha256 !== expectedRequestSha256
          || inspection.task_id !== state.task_id
          || inspection.status !== state.status
          || inspection.phase !== state.phase
          || inspection.event_cursor !== state.event_cursor
          || inspection.revision !== state.revision
          || inspection.lesson_ir_sha256 !== state.lesson_ir_sha256
          || inspection.review_sha256 !== state.review_sha256) {
        throw new Error('existing state changed during validation');
      }
      return state;
    } catch {
      throw new Error('exact task initialization failed');
    }
  };
  try {
    const receipt: VideoOperationReceipt | undefined = operation ? {
      ...operation, schema_version: 1, request_sha256: expectedRequestSha256,
      input_event_cursor: 0, result_event_cursor: 1,
    } : undefined;
    return (await initializePersistentTaskStateExact(
      taskRoot, expectedWorkerTaskId, expectedRequestSha256,
      validateBinding, readExistingState,
      operation ? {
        eventType: 'task_initialized_operation',
        details: {video_operation: receipt!},
        existingStateMatches: async state => {
          const events = await readAuditTail(taskRoot);
          const latest = events.at(-1);
          return state.event_cursor === 1 && state.status === 'initialized'
            && latest?.event === 'task_initialized_operation'
            && JSON.stringify(latest?.video_operation) === JSON.stringify(receipt);
        },
      } : undefined,
    )).state;
  } catch {
    // The CLI is local and its stderr is observable; keep paths and request text out.
    throw new Error('exact task initialization failed');
  }
}

/** Builds a new storyboard only from an exact, current task-state cursor. */
export async function buildExactStoryboard(
  taskRoot: string,
  expectedWorkerTaskId: string,
  expectedRequestSha256: string,
  revision: number,
  expectedEventCursor: number,
  operation?: VideoOperationBinding,
  authorLessonIr?: () => Promise<JsonObject>,
): Promise<Awaited<ReturnType<typeof inspectTask>>> {
  validateExactActionArguments(expectedWorkerTaskId, expectedRequestSha256, revision, expectedEventCursor);
  if (!Number.isSafeInteger(expectedEventCursor + 2)) throw new Error('exact storyboard cursor is exhausted');
  const preflight = await exactActionPreflight(
    taskRoot, expectedWorkerTaskId, expectedRequestSha256, expectedEventCursor);
  if (operation) await assertOperationInput(taskRoot, operation, 'storyboard', {
    revision, event_cursor: expectedEventCursor,
  });
  const beforeState = preflight.inspection;
  const expectedRevision = beforeState.status === 'initialized' ? 1 : (beforeState.revision ?? 0) + 1;
  if (revision !== expectedRevision || !Number.isSafeInteger(expectedRevision)) {
    throw new Error('exact storyboard revision is stale');
  }
  if (beforeState.status !== 'initialized' && beforeState.status !== 'awaiting_storyboard_review') {
    throw new Error('exact storyboard requires initialized or awaiting-storyboard-review state');
  }
  if (authorLessonIr && beforeState.status !== 'initialized') {
    throw new Error('built-in Qwen storyboard generation is initial-only; revise is unavailable');
  }
  if (beforeState.status === 'initialized' && beforeState.phase !== 'idle') {
    throw new Error('exact storyboard initial phase is invalid');
  }
  if (beforeState.status === 'awaiting_storyboard_review' && beforeState.phase !== 'awaiting_approval') {
    throw new Error('exact storyboard review phase is invalid');
  }
  // Resolve browser/KaTeX dependencies before the optional paid Qwen call. The
  // light task-worker CLI path stays free of this graph, while storyboard
  // dependency failures still happen before an external model effect starts.
  const {buildStoryboard} = await import('./build-storyboard.js');
  const irPath = resolve(taskRoot, 'output/lesson.ir.json');
  if (authorLessonIr) await ensureOutputDirectory(taskRoot);
  let irBytes: Buffer | undefined;
  let ir: JsonObject | undefined;
  if (!authorLessonIr) {
    irBytes = await readBoundedRegularFile(irPath, MAX_EXACT_IR_BYTES);
    ir = parseBoundedRequest(irBytes);
    await validateIrObject(taskRoot, ir, preflight.request);
    if (!(await readBoundedRegularFile(irPath, MAX_EXACT_IR_BYTES)).equals(irBytes)) {
      throw new Error('exact storyboard Lesson IR changed during validation');
    }
  }
  let lessonIrSha256 = irBytes ? sha256Bytes(irBytes) : '';
  const lease = await acquireExecutionLease(taskRoot);
  let attemptId = '';
  let started = false;
  try {
    const startDetails: JsonObject = operation ? {video_operation_started: {
      schema_version: 1, operation_id: operation.operation_id, attempt_id: operation.attempt_id,
      action: 'storyboard', payload_digest: operation.payload_digest,
      source_snapshot_digest: operation.source_snapshot_digest,
      request_sha256: operation.request_sha256, input_event_cursor: expectedEventCursor,
      revision,
    }} : {};
    await transitionTaskStateValidated(taskRoot, 'storyboard_started_exact', async current => {
      assertExactCurrent(current, expectedWorkerTaskId, expectedRequestSha256, expectedEventCursor);
      if (operation) await assertOperationInput(taskRoot, operation, 'storyboard', {
        revision, event_cursor: expectedEventCursor,
      });
      assertExactStoryboardState(current, revision);
      const currentRequest = await readExactRequestAndValidateInput(
        taskRoot, expectedWorkerTaskId, expectedRequestSha256);
      const currentIrBytes = authorLessonIr ? undefined : await readBoundedRegularFile(irPath, MAX_EXACT_IR_BYTES);
      if (!currentRequest.bytes.equals(preflight.requestBytes)
          || (!authorLessonIr && !irBytes)
          || (currentIrBytes && !currentIrBytes.equals(irBytes!))) {
        throw new Error('exact storyboard source changed before start');
      }
      if (ir) await validateIrObject(taskRoot, ir, currentRequest.request);
      const attempt = newAttempt('storyboard', lease.token);
      if (authorLessonIr) attempt.external_effect_possible = true;
      attemptId = attempt.attempt_id;
      started = true;
      return {
        ...current,
        status: 'running',
        phase: 'storyboard',
        approval: undefined,
        attempt,
        error: undefined,
        cancellation: undefined,
      };
    }, {revision, ...startDetails});

    if (authorLessonIr) {
      ir = await authorLessonIr();
      await validateIrObject(taskRoot, ir, preflight.request);
      await ensureOutputDirectory(taskRoot);
      await atomicWriteJson(irPath, ir);
      irBytes = await readBoundedRegularFile(irPath, MAX_EXACT_IR_BYTES);
      lessonIrSha256 = sha256Bytes(irBytes);
    }
    if (!ir || !irBytes) throw new Error('Lesson IR is unavailable');
    await buildStoryboard(taskRoot, revision, {request: preflight.request, ir});
    const requestAfter = await readExactRequestAndValidateInput(
      taskRoot, expectedWorkerTaskId, expectedRequestSha256);
    const irAfter = await readBoundedRegularFile(irPath, MAX_EXACT_IR_BYTES);
    if (!irBytes.equals(irAfter) || sha256Bytes(irAfter) !== lessonIrSha256
        || !requestAfter.bytes.equals(preflight.requestBytes)) {
      throw new Error('exact storyboard inputs changed during generation');
    }
    const reviewBytes = await readBoundedRegularFile(resolve(taskRoot, 'output/review.json'), MAX_EXACT_REVIEW_BYTES);
    const review = parseBoundedRequest(reviewBytes);
    await validateSchema('review', review);
    if (review.task_id !== expectedWorkerTaskId || review.lesson_ir_revision !== revision
        || review.lesson_ir?.sha256 !== lessonIrSha256) {
      throw new Error('exact storyboard output does not match the validated Lesson IR');
    }
    const reviewSha256 = sha256Bytes(reviewBytes);
    const finalIr = await readBoundedRegularFile(irPath, MAX_EXACT_IR_BYTES);
    const finalReview = await readBoundedRegularFile(resolve(taskRoot, 'output/review.json'), MAX_EXACT_REVIEW_BYTES);
    if (!irAfter.equals(finalIr) || !reviewBytes.equals(finalReview)) {
      throw new Error('exact storyboard outputs changed during validation');
    }
    const receipt: VideoOperationReceipt | undefined = operation ? {
      ...operation, schema_version: 1, request_sha256: expectedRequestSha256,
      input_event_cursor: expectedEventCursor, result_event_cursor: expectedEventCursor + 2,
      revision, lesson_ir_sha256: lessonIrSha256, review_sha256: reviewSha256,
    } : undefined;
    await transitionTaskStateValidated(taskRoot, 'storyboard_ready_exact', async current => {
      assertExactAttempt(current, expectedWorkerTaskId, expectedRequestSha256, attemptId, lease.token);
      if (current.event_cursor !== expectedEventCursor + 1) {
        throw new Error('exact storyboard start cursor changed');
      }
      const requestCurrent = await readExactRequestAndValidateInput(
        taskRoot, expectedWorkerTaskId, expectedRequestSha256);
      const irCurrent = await readBoundedRegularFile(irPath, MAX_EXACT_IR_BYTES);
      const reviewCurrent = await readBoundedRegularFile(
        resolve(taskRoot, 'output/review.json'), MAX_EXACT_REVIEW_BYTES);
      if (!requestCurrent.bytes.equals(preflight.requestBytes) || !irBytes || !ir || !reviewBytes
          || !irCurrent.equals(irBytes)
          || sha256Bytes(irCurrent) !== lessonIrSha256 || !reviewCurrent.equals(reviewBytes)
          || sha256Bytes(reviewCurrent) !== reviewSha256) {
        throw new Error('exact storyboard outputs changed before ready state');
      }
      await validateIrObject(taskRoot, ir, requestCurrent.request);
      return {
        ...current,
        status: 'awaiting_storyboard_review',
        phase: 'awaiting_approval',
        revision,
        lesson_ir_sha256: lessonIrSha256,
        review_sha256: reviewSha256,
        approval: undefined,
        attempt: undefined,
        error: undefined,
        cancellation: undefined,
      };
    }, {revision, ...(receipt ? {video_operation: receipt} : {})});
    return await inspectTask(taskRoot);
  } catch (error) {
    if (started && attemptId) {
      await transitionTaskStateValidated(taskRoot, 'storyboard_failed_exact', async current => {
        if (current.status !== 'running' || current.phase !== 'storyboard'
            || current.attempt?.attempt_id !== attemptId
            || current.attempt.lease_token !== lease.token) return undefined;
        return {
          ...current,
          status: 'failed',
          phase: 'storyboard',
          attempt: undefined,
          error: {message: 'Exact storyboard generation failed', phase: 'storyboard', external_effect_possible: Boolean(authorLessonIr)},
        };
      });
    }
    throw error;
  } finally {
    await releaseExecutionLease(taskRoot, lease);
  }
}

/** Approves exactly the review projection and cursor confirmed by the caller. */
export async function grantBoundApproval(
  taskRoot: string,
  expectedWorkerTaskId: string,
  expectedRequestSha256: string,
  revision: number,
  expectedEventCursor: number,
  expectedReviewSha256: string,
  expectedLessonIrSha256: string,
  operation?: VideoOperationBinding,
): Promise<Awaited<ReturnType<typeof inspectTask>>> {
  validateExactActionArguments(expectedWorkerTaskId, expectedRequestSha256, revision, expectedEventCursor);
  assertSha256(expectedReviewSha256, 'review_sha256');
  assertSha256(expectedLessonIrSha256, 'lesson_ir_sha256');
  if (!Number.isSafeInteger(expectedEventCursor + 1)) throw new Error('exact approval cursor is exhausted');
  const preflight = await exactActionPreflight(
    taskRoot, expectedWorkerTaskId, expectedRequestSha256, expectedEventCursor);
  if (operation) await assertOperationInput(taskRoot, operation, 'approve', {
    revision, event_cursor: expectedEventCursor,
    review_sha256: expectedReviewSha256, lesson_ir_sha256: expectedLessonIrSha256,
  });
  if (preflight.inspection.status !== 'awaiting_storyboard_review'
      || preflight.inspection.phase !== 'awaiting_approval'
      || preflight.inspection.revision !== revision
      || preflight.inspection.review_sha256 !== expectedReviewSha256
      || preflight.inspection.lesson_ir_sha256 !== expectedLessonIrSha256) {
    throw new Error('exact approval binding is stale');
  }

  const lease = await acquireExecutionLease(taskRoot);
  try {
    const approvalId = randomUUID();
    const receipt: VideoOperationReceipt | undefined = operation ? {
      ...operation, schema_version: 1, request_sha256: expectedRequestSha256,
      input_event_cursor: expectedEventCursor, result_event_cursor: expectedEventCursor + 1,
      revision, review_sha256: expectedReviewSha256, lesson_ir_sha256: expectedLessonIrSha256,
      approval_id: approvalId,
    } : undefined;
    await transitionTaskStateValidated(taskRoot, 'approval_granted_bound', async current => {
      assertExactCurrent(current, expectedWorkerTaskId, expectedRequestSha256, expectedEventCursor);
      if (operation) await assertOperationInput(taskRoot, operation, 'approve', {
        revision, event_cursor: expectedEventCursor,
        review_sha256: expectedReviewSha256, lesson_ir_sha256: expectedLessonIrSha256,
      });
      if (current.status !== 'awaiting_storyboard_review' || current.phase !== 'awaiting_approval'
          || current.revision !== revision || current.review_sha256 !== expectedReviewSha256
          || current.lesson_ir_sha256 !== expectedLessonIrSha256 || current.approval || current.attempt) {
        throw new Error('exact approval binding is stale');
      }
      const projection = await inspectReview(taskRoot);
      if (projection.task_id !== expectedWorkerTaskId
          || projection.status !== 'awaiting_storyboard_review'
          || projection.event_cursor !== expectedEventCursor
          || projection.revision !== revision
          || projection.review_sha256 !== expectedReviewSha256
          || projection.lesson_ir_sha256 !== expectedLessonIrSha256) {
        throw new Error('exact approval review changed before commit');
      }
  const snapshot = await persistApprovalSnapshotBounded(taskRoot, approvalId, {
        revision, reviewSha256: expectedReviewSha256,
        lessonIrSha256: expectedLessonIrSha256,
      });
      const afterSnapshot = await inspectReview(taskRoot);
      if (afterSnapshot.task_id !== projection.task_id
          || afterSnapshot.status !== projection.status
          || afterSnapshot.event_cursor !== projection.event_cursor
          || afterSnapshot.revision !== projection.revision
          || afterSnapshot.review_sha256 !== projection.review_sha256
          || afterSnapshot.lesson_ir_sha256 !== projection.lesson_ir_sha256) {
        throw new Error('exact approval review changed while snapshotting');
      }
      return {
        ...current,
        status: 'approved',
        phase: 'approval_pending',
        approval: {
          approval_id: approvalId,
          revision,
          lesson_ir_sha256: expectedLessonIrSha256,
          review_sha256: expectedReviewSha256,
          lesson_ir_snapshot_path: snapshot.lessonIrPath,
          review_snapshot_path: snapshot.reviewPath,
          granted_at: new Date().toISOString(),
        },
        attempt: undefined,
        error: undefined,
      };
    }, {revision, review_sha256: expectedReviewSha256, lesson_ir_sha256: expectedLessonIrSha256,
      ...(receipt ? {video_operation: receipt} : {})});
    return await inspectTask(taskRoot);
  } finally {
    await releaseExecutionLease(taskRoot, lease);
  }
}

async function exactActionPreflight(
  taskRoot: string,
  expectedTaskId: string,
  expectedRequestSha256: string,
  expectedEventCursor: number,
): Promise<{inspection: Awaited<ReturnType<typeof inspectTask>>; request: JsonObject; requestBytes: Buffer}> {
  try {
    const rootInfo = await lstat(taskRoot);
    const workInfo = await lstat(resolve(taskRoot, 'work'));
    const outputInfo = await lstat(resolve(taskRoot, 'output'));
    if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink()
        || !workInfo.isDirectory() || workInfo.isSymbolicLink()
        || !outputInfo.isDirectory() || outputInfo.isSymbolicLink()) {
      throw new Error('invalid task directory');
    }
    const {request, bytes: requestBytes} = await readExactRequestAndValidateInput(
      taskRoot, expectedTaskId, expectedRequestSha256);
    const inspection = await inspectTask(taskRoot);
    if (inspection.task_id !== expectedTaskId || inspection.event_cursor !== expectedEventCursor
        || inspection.status === 'initialized' && inspection.phase !== 'idle'
        || inspection.status === 'awaiting_storyboard_review' && inspection.phase !== 'awaiting_approval') {
      throw new Error('persisted exact task state is stale or inconsistent');
    }
    const state = await verifyTaskStateAudit(taskRoot, {
      task_id: expectedTaskId, request_sha256: expectedRequestSha256,
      event_cursor: expectedEventCursor,
    });
    if (state.status !== inspection.status || state.phase !== inspection.phase
        || state.revision !== inspection.revision || state.review_sha256 !== inspection.review_sha256
        || state.lesson_ir_sha256 !== inspection.lesson_ir_sha256) {
      throw new Error('persisted state changed during exact preflight');
    }
    const after = await readBoundedRegularFile(resolve(taskRoot, 'request.json'), MAX_EXACT_INIT_REQUEST_BYTES);
    if (!requestBytes.equals(after) || sha256Bytes(after) !== expectedRequestSha256) {
      throw new Error('request changed during exact preflight');
    }
    return {inspection, request, requestBytes};
  } catch {
    throw new Error('exact video action preflight failed');
  }
}

async function readExactRequestAndValidateInput(
  taskRoot: string,
  expectedTaskId: string,
  expectedRequestSha256: string,
): Promise<{request: JsonObject; bytes: Buffer}> {
  const path = resolve(taskRoot, 'request.json');
  const before = await readBoundedRegularFile(path, MAX_EXACT_INIT_REQUEST_BYTES);
  if (sha256Bytes(before) !== expectedRequestSha256) throw new Error('request digest mismatch');
  const request = parseBoundedRequest(before);
  if (request.task_id !== expectedTaskId) throw new Error('request task identity mismatch');
  await validateBoundedInput(taskRoot, request);
  const after = await readBoundedRegularFile(path, MAX_EXACT_INIT_REQUEST_BYTES);
  if (!before.equals(after) || sha256Bytes(after) !== expectedRequestSha256) {
    throw new Error('request changed during validation');
  }
  return {request, bytes: before};
}

function validateExactActionArguments(taskId: string, requestSha256: string,
                                      revision: number, eventCursor: number): void {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(taskId)
      || !/^[a-f0-9]{64}$/.test(requestSha256)
      || !Number.isSafeInteger(revision) || revision < 1
      || !Number.isSafeInteger(eventCursor) || eventCursor < 1) {
    throw new Error('exact video action arguments are invalid');
  }
}

function assertExactCurrent(state: TaskState, taskId: string, requestSha256: string,
                            eventCursor: number): void {
  if (state.task_id !== taskId || state.request_sha256 !== requestSha256
      || state.event_cursor !== eventCursor) throw new Error('exact video action state is stale');
}

function assertExactStoryboardState(state: TaskState, revision: number): void {
  const expectedRevision = state.status === 'initialized' ? 1 : (state.revision ?? 0) + 1;
  if (!Number.isSafeInteger(expectedRevision) || revision !== expectedRevision
      || state.status === 'initialized' && state.phase !== 'idle'
      || state.status === 'awaiting_storyboard_review' && state.phase !== 'awaiting_approval'
      || state.status !== 'initialized' && state.status !== 'awaiting_storyboard_review') {
    throw new Error('exact storyboard state or revision is stale');
  }
}

function assertExactAttempt(state: TaskState, taskId: string, requestSha256: string,
                            attemptId: string, leaseToken: string): void {
  if (state.task_id !== taskId || state.request_sha256 !== requestSha256
      || state.status !== 'running' || state.phase !== 'storyboard'
      || state.attempt?.attempt_id !== attemptId || state.attempt.lease_token !== leaseToken) {
    throw new Error('exact storyboard attempt is no longer current');
  }
}

function parseBoundedRequest(bytes: Buffer): JsonObject {
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
  const {buildStoryboard} = await import('./build-storyboard.js');
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

/** Grants approval only for the exact, currently validated review shown to the user. */
export async function grantExactApproval(
  taskRoot: string,
  revision: number,
  expectedReviewSha256: string,
  expectedLessonIrSha256: string,
): Promise<TaskState> {
  if (!Number.isInteger(revision) || revision < 1) {
    throw new Error('approval revision must be a positive integer');
  }
  assertSha256(expectedReviewSha256, 'review_sha256');
  assertSha256(expectedLessonIrSha256, 'lesson_ir_sha256');
  await ensureTaskState(taskRoot);
  const lease = await acquireExecutionLease(taskRoot);
  try {
    const state = requireState(await loadTaskState(taskRoot));
    assertCanGrantApproval(state, false);
    if (state.status !== 'awaiting_storyboard_review') {
      throw new Error('exact approval requires the current awaiting-storyboard-review state');
    }
    const binding = await currentReviewBinding(taskRoot);
    assertExpectedApprovalBinding(binding, revision, expectedReviewSha256, expectedLessonIrSha256);
    const approvalId = randomUUID();
    const snapshot = await persistApprovalSnapshot(taskRoot, approvalId, binding);

    // Revalidate after snapshotting, while holding the same worker lease used by stage 1/2.
    // This catches a same-revision replacement between initial validation and state commit.
    const current = await currentReviewBinding(taskRoot);
    assertExpectedApprovalBinding(current, revision, expectedReviewSha256, expectedLessonIrSha256);
    return await transitionTaskState(taskRoot, 'approval_granted_exact', latest => {
      const checked = requireState(latest);
      assertCanGrantApproval(checked, false);
      if (checked.status !== 'awaiting_storyboard_review'
          || checked.revision !== revision
          || checked.lesson_ir_sha256 !== expectedLessonIrSha256
          || checked.review_sha256 !== expectedReviewSha256) {
        throw new Error('persisted review binding changed before exact approval commit');
      }
      return {
        ...checked,
        status: 'approved',
        phase: 'approval_pending',
        revision,
        lesson_ir_sha256: expectedLessonIrSha256,
        review_sha256: expectedReviewSha256,
        approval: {
          approval_id: approvalId,
          revision,
          lesson_ir_sha256: expectedLessonIrSha256,
          review_sha256: expectedReviewSha256,
          lesson_ir_snapshot_path: snapshot.lessonIrPath,
          review_snapshot_path: snapshot.reviewPath,
          granted_at: new Date().toISOString(),
        },
        attempt: undefined,
        error: undefined,
      };
    }, {revision, review_sha256: expectedReviewSha256, lesson_ir_sha256: expectedLessonIrSha256});
  } finally {
    await releaseExecutionLease(taskRoot, lease);
  }
}

function assertSha256(value: string, name: string): void {
  if (!/^[a-f0-9]{64}$/.test(value)) throw new Error(`${name} must be 64 lowercase hexadecimal characters`);
}

function assertExpectedApprovalBinding(
  binding: {revision: number; lessonIrSha256: string; reviewSha256: string},
  revision: number,
  expectedReviewSha256: string,
  expectedLessonIrSha256: string,
): void {
  if (binding.revision !== revision) {
    throw new Error(`approval revision ${revision} does not match review revision ${binding.revision}`);
  }
  if (binding.reviewSha256 !== expectedReviewSha256
      || binding.lessonIrSha256 !== expectedLessonIrSha256) {
    throw new Error('exact approval digest does not match the current validated review');
  }
}

/** Runs approved Stage 2. No failure or restart automatically creates another approval. */
export async function runApprovedTask(
  taskRoot: string,
  mode: 'audio' | 'render' | 'all',
  allowFixtureAudio = false,
  effects: WorkerEffects = productionEffects,
  operation?: VideoOperationBinding,
  privateTts?: PrivateTtsProvider,
): Promise<TaskState> {
  await ensureTaskState(taskRoot);
  if (operation) {
    await assertOperationInput(taskRoot, operation, 'produce', operationParameters(operation));
    if (operation.allow_cloud_tts !== true) throw new Error('explicit cloud TTS consent is required');
  }
  // Import renderer/result dependencies before approval consumption or any
  // possible cloud TTS call, preserving preflight failure semantics.
  if (mode === 'all' || mode === 'render') {
    await Promise.all([import('./render-video.js'), import('./validate-result.js')]);
  }
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
      if (operation && (state.event_cursor !== operation.input_event_cursor
          || state.revision !== operation.revision || binding.reviewSha256 !== operation.review_sha256
          || binding.lessonIrSha256 !== operation.lesson_ir_sha256)) {
        throw new Error('production operation does not match the approved storyboard');
      }
      if (operation && !privateTts) throw new Error('private DashScope TTS input is required');
      const productionPreflight = await approvedExecutionInput(taskRoot, state);
      if (privateTts) validatePrivateTtsBudget(productionPreflight, privateTts);
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
      }, operation ? {video_operation_started: operationStartedDetails(operation)} : {});
      activeAttemptId = state.attempt?.attempt_id;
      approvedInput = productionPreflight;
    } else if (state.status === 'ready_to_render' && (mode === 'render' || mode === 'all')) {
      await assertApprovalBinding(taskRoot, state);
      state = await transitionTaskState(taskRoot, 'render_resumed', current => ({
        ...requireState(current), status: 'running', phase: 'audio_ready',
        attempt: newAttempt('audio_ready', lease.token), error: undefined,
      }));
      activeAttemptId = state.attempt?.attempt_id;
      approvedInput = await approvedExecutionInput(taskRoot, state);
      if (privateTts) validatePrivateTtsBudget(approvedInput, privateTts);
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
        await effects.prepareAudio(taskRoot, approvedInput, privateTts);
        await effects.validateAudio(taskRoot, approvedInput);
        audioReady = true;
      }
      if (!audioReady) throw new Error('audio preparation did not produce a valid manifest');
      phase = 'audio_ready';
      externalEffectPossible = Boolean(operation && privateTts);
      state = await transitionTaskState(taskRoot, 'audio_ready', current => ({
        ...requireRunning(current), phase, attempt: {
          ...requireState(current).attempt!, phase, external_effect_possible: externalEffectPossible,
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
        ...requireState(current).attempt!, phase, external_effect_possible: externalEffectPossible,
      },
    }));
    await effects.render(taskRoot, state.revision!, allowFixtureAudio, approvedInput);
    const manifestPath = resolve(taskRoot, 'output/result.json');
    const manifestBefore = operation ? await readBoundedRegularFile(manifestPath, MAX_EXACT_RESULT_BYTES) : undefined;
    const validatedResult = await effects.validateResult(taskRoot);
    const manifestAfter = operation ? await readBoundedRegularFile(manifestPath, MAX_EXACT_RESULT_BYTES) : undefined;
    if (manifestBefore && manifestAfter && !manifestBefore.equals(manifestAfter)) {
      throw new Error('result manifest changed during validation');
    }
    const resultManifestSha256 = manifestAfter ? sha256Bytes(manifestAfter) : undefined;
    if (operation && (!resultManifestSha256 || !/^[a-f0-9]{64}$/.test(resultManifestSha256))) {
      throw new Error('result manifest digest is unavailable');
    }
    if (operation) projectValidatedArtifacts(validatedResult);
    if (cancellationSeen || await cancellationRequested(taskRoot)) throw new CancelledError();
    state = await transitionTaskState(taskRoot, 'task_completed', current => ({
      ...requireRunning(current), status: 'completed', phase: 'completed',
      attempt: undefined, error: undefined,
    }));
    if (operation) {
      const manifestAtCommit = await readBoundedRegularFile(manifestPath, MAX_EXACT_RESULT_BYTES);
      if (!manifestAfter || !manifestAtCommit.equals(manifestAfter)
          || sha256Bytes(manifestAtCommit) !== resultManifestSha256) {
        throw new Error('result manifest changed before completion receipt');
      }
      const resultCursor = state.event_cursor + 1;
      const receipt: VideoOperationReceipt = {
        ...operation, schema_version: 1, request_sha256: operation.request_sha256,
        input_event_cursor: operation.input_event_cursor, result_event_cursor: resultCursor,
        revision: operation.revision!, review_sha256: operation.review_sha256!,
        lesson_ir_sha256: operation.lesson_ir_sha256!,
      };
      state = await transitionTaskState(taskRoot, 'video_operation_completed', current => ({
        ...requireState(current),
      }), {video_operation: receipt, result_manifest_sha256: resultManifestSha256});
    }
    return state;
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
    if (error instanceof TtsAdapterError) externalEffectPossible = error.externalEffectPossible;
    await recordFailure(taskRoot, phase, error, externalEffectPossible, activeAttemptId);
    if (operation) {
      const safeCode = error instanceof TtsAdapterError ? error.code : undefined;
      const safeStatus = error instanceof TtsAdapterError && error.httpStatus !== undefined
        ? ` (HTTP ${error.httpStatus})` : '';
      const failure = new Error(safeCode ? `${safeCode}${safeStatus}` : 'Built-in video production failed') as Error & {
        externalEffectPossible?: boolean; safeCode?: string; httpStatus?: number;
      };
      failure.externalEffectPossible = externalEffectPossible;
      if (safeCode) failure.safeCode = safeCode;
      if (error instanceof TtsAdapterError && error.httpStatus !== undefined) failure.httpStatus = error.httpStatus;
      throw failure;
    }
    throw error;
  } finally {
    process.removeListener('SIGINT', onSignal);
    process.removeListener('SIGTERM', onSignal);
    await releaseExecutionLease(taskRoot, lease);
  }
}

export async function cancelTask(
  taskRoot: string,
  hooks: {beforeStateCommit?(): Promise<void>; operation?: VideoOperationBinding} = {},
): Promise<TaskState> {
  let state = await ensureTaskState(taskRoot);
  const operation = hooks.operation;
  if (operation) {
    await assertOperationInput(taskRoot, operation, 'produce', operationParameters(operation));
    const events = await readAuditTail(taskRoot);
    if (!events.some(event => event.sequence === operation.input_event_cursor + 1
        && matchingProduceStart(event, operation, state.task_id))) {
      throw new Error('production cancellation is not bound to the running operation');
    }
    if (state.status === 'cancelling' && !matchingProduceCancelRequest(events, operation, state.task_id)) {
      throw new Error('production cancellation intent does not match this operation');
    }
    if (state.status === 'running' && (state.event_cursor < operation.input_event_cursor + 1
        || state.revision !== operation.revision)) throw new Error('production cancellation state changed');
  }
  if (state.status === 'completed') throw new Error('completed task cannot be cancelled');
  if (state.status === 'cancelled') return state;
  const lease = await readExecutionLease(taskRoot);
  const leaseBelongsToAttempt = lease != null
    && lease.token === state.attempt?.lease_token
    && lease.pid === state.attempt?.pid;
  const leaseLive = leaseBelongsToAttempt && lease
    ? process.platform === 'win32'
      ? await verifiedExecutionProcessId(lease) !== undefined
      : await executionLeaseIsLive(lease)
    : false;
  if (operation && state.status === 'running' && !leaseLive) {
    throw new Error('production worker outcome is uncertain; cancellation cannot prove the paid operation stopped');
  }
  const observedStatus = state.status;
  const observedAttemptId = state.attempt?.attempt_id;
  const requestedAt = new Date().toISOString();
  if (operation && state.status === 'cancelling') {
    if (leaseLive) return state;
    const cancelRequest = produceCancelRequestEvent(await readAuditTail(taskRoot), operation, state.task_id);
    const cancelDetails = cancelRequest?.video_operation_cancel_requested as JsonObject | undefined;
    const cancelCursor = Number(cancelDetails?.cancel_input_event_cursor);
    if (!Number.isSafeInteger(cancelCursor) || state.event_cursor !== cancelCursor + 1) {
      throw new Error('production cancellation cursor changed');
    }
    if (lease && leaseBelongsToAttempt) await releaseExecutionLease(taskRoot, lease);
    const cancelled = await transitionTaskStateIf(taskRoot, 'video_operation_cancelled', current =>
      current?.status === 'cancelling' && current.attempt?.attempt_id === observedAttemptId
        && current.event_cursor === cancelCursor + 1, current => ({
      ...current, status: 'cancelled', phase: 'cancelled', attempt: undefined,
      cancellation: {requested_at: current.cancellation?.requested_at ?? requestedAt,
        completed_at: new Date().toISOString()},
    }), {video_operation_cancelled: produceCancellationDetails(operation, state.task_id, observedAttemptId,
      cancelCursor, String(cancelDetails?.cancel_requested_at ?? ''))});
    if (cancelled.state.status === 'cancelled') await quarantineLateResult(taskRoot);
    return cancelled.state;
  }
  await hooks.beforeStateCommit?.();
  let result = await transitionTaskStateIf(taskRoot, operation ? 'video_operation_cancel_requested' : 'cancellation_requested', current =>
    current != null && current.status === observedStatus
      && current.attempt?.attempt_id === observedAttemptId, current => ({
    ...current, status: leaseLive ? 'cancelling' : 'cancelled',
    phase: leaseLive ? 'cancelling' : 'cancelled',
    cancellation: {
      requested_at: current.cancellation?.requested_at ?? requestedAt,
      ...(leaseLive ? {} : {completed_at: new Date().toISOString()}),
    },
    attempt: leaseLive ? current.attempt : undefined,
  }), operation ? {video_operation_cancel_requested: produceCancellationDetails(operation, state.task_id,
    observedAttemptId, state.event_cursor, requestedAt)} : {});
  let next = result.state;
  if (!result.changed) return next;
  if (lease && leaseLive) {
    if (process.platform === 'win32') {
      // The Python owner holds the kill-on-close Job for the full Node tree.
      // Leave the exact task in cancelling until that owner terminates it and
      // calls cancel again to reconcile the stale lease.
      return next;
    }
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
    result = await transitionTaskStateIf(taskRoot, operation ? 'video_operation_cancelled' : 'cancellation_confirmed', current =>
      current?.status === 'cancelling'
        && current.cancellation?.requested_at === next.cancellation?.requested_at
        && current.attempt?.attempt_id === observedAttemptId, current => ({
      ...current, status: 'cancelled', phase: 'cancelled',
      cancellation: {
        requested_at: current.cancellation?.requested_at ?? requestedAt,
        completed_at: new Date().toISOString(),
      },
      attempt: undefined,
      }), operation ? {video_operation_cancelled: produceCancellationDetails(operation, state.task_id,
        observedAttemptId, next.event_cursor - 1, next.cancellation?.requested_at)} : {});
    next = result.state;
  } else if (lease && leaseBelongsToAttempt) {
    await releaseExecutionLease(taskRoot, lease);
  }
  if (next.status === 'cancelled') await quarantineLateResult(taskRoot);
  return next;
}

/**
 * Whether this `running` state is a revision step 3 parked, not a dead worker.
 *
 * Reads the audit tail because the state alone cannot tell the two apart, and
 * treats every failure to read it as "not parked": recovery's job is to decide
 * about a possibly-damaged task, so an unreadable log must not become a reason
 * to keep a task running. Answering `false` restores the pre-existing behaviour
 * exactly, which is what makes this change additive.
 */
async function isParkedRevision(taskRoot: string, current: TaskState): Promise<boolean> {
  if (current.status !== 'running') return false;
  try {
    return revisionParkIsIntact(current, (await readAuditTail(taskRoot)).at(-1));
  } catch {
    return false;
  }
}

export async function recoverInterruptedTask(taskRoot: string, state?: TaskState): Promise<TaskState> {
  const current = state ?? await loadTaskState(taskRoot);
  if (!current) throw new Error('task has not been initialized');
  if (current.status !== 'running' && current.status !== 'cancelling') return current;
  const lease = await readExecutionLease(taskRoot);
  const leaseMatchesAttempt = lease != null && lease.token === current.attempt?.lease_token
    && lease.pid === current.attempt?.pid;
  if (lease && leaseMatchesAttempt && await executionLeaseIsLive(lease)) return current;
  if (await isParkedRevision(taskRoot, current)) return current;
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

async function persistApprovalSnapshotBounded(
  taskRoot: string,
  approvalId: string,
  binding: {revision: number; lessonIrSha256: string; reviewSha256: string},
): Promise<{lessonIrPath: string; reviewPath: string}> {
  const relativeRoot = `work/approved-input/${approvalId}`;
  const lessonIrPath = `${relativeRoot}/lesson.ir.json`;
  const reviewPath = `${relativeRoot}/review.json`;
  await ensureRealApprovalDirectory(taskRoot, approvalId);
  const lessonBytes = await readBoundedRegularFile(
    resolve(taskRoot, 'output/lesson.ir.json'), MAX_EXACT_IR_BYTES);
  const reviewBytes = await readBoundedRegularFile(
    resolve(taskRoot, 'output/review.json'), MAX_EXACT_REVIEW_BYTES);
  if (sha256Bytes(lessonBytes) !== binding.lessonIrSha256
      || sha256Bytes(reviewBytes) !== binding.reviewSha256) {
    throw new Error('exact approval files changed while snapshotting');
  }
  await atomicWriteFile(resolve(taskRoot, lessonIrPath), lessonBytes);
  await atomicWriteFile(resolve(taskRoot, reviewPath), reviewBytes);
  if (sha256Bytes(await readBoundedRegularFile(resolve(taskRoot, lessonIrPath), MAX_EXACT_IR_BYTES))
        !== binding.lessonIrSha256
      || sha256Bytes(await readBoundedRegularFile(resolve(taskRoot, reviewPath), MAX_EXACT_REVIEW_BYTES))
        !== binding.reviewSha256) {
    throw new Error('exact approval snapshot digest mismatch');
  }
  return {lessonIrPath, reviewPath};
}

async function ensureRealApprovalDirectory(taskRoot: string, approvalId: string): Promise<void> {
  const root = await lstat(taskRoot);
  if (!root.isDirectory() || root.isSymbolicLink()) throw new Error('approval task root is invalid');
  let current = taskRoot;
  for (const segment of ['work', 'approved-input', approvalId]) {
    current = resolve(current, segment);
    try {
      await mkdir(current, {mode: 0o700});
    } catch (error: any) {
      if (error?.code !== 'EEXIST') throw error;
    }
    const info = await lstat(current);
    if (!info.isDirectory() || info.isSymbolicLink()) {
      throw new Error('approval snapshot directory is not a real directory');
    }
  }
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
  if (error instanceof TtsAdapterError) {
    return `${error.code}${error.httpStatus !== undefined ? ` (HTTP ${error.httpStatus})` : ''}`;
  }
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
  const knownActions = new Set(['init', 'init-exact', 'status', 'inspect', 'review', 'storyboard', 'storyboard-exact',
    ...operationActions, 'approve', 'approve-exact', 'approve-bound', 'audio',
    'run', 'render', 'cancel']);
  const actionFirst = knownActions.has(process.argv[2] ?? '');
  const action = actionFirst ? process.argv[2]! : (process.argv[3] ?? 'status');
  if (['init-exact', 'storyboard-exact', 'approve-bound', ...operationActions].includes(action) && !actionFirst) {
    throw new Error('exact actions require action-first CLI syntax');
  }
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
  if (action === 'inspect') {
    console.log(JSON.stringify(await inspectTask(taskRoot)));
    return;
  }
  if (action === 'review') {
    console.log(JSON.stringify(await inspectReview(taskRoot)));
    return;
  }
  if (action === 'init-exact') {
    const binding = exactInitializationOptions();
    await initializeExactTask(taskRoot, binding.taskId, binding.requestSha256);
    console.log(JSON.stringify(await inspectTask(taskRoot)));
    return;
  }
  if (action === 'storyboard-exact') {
    const options = exactStoryboardOptions();
    console.log(JSON.stringify(await buildExactStoryboard(
      taskRoot, options.taskId, options.requestSha256, options.revision, options.eventCursor)));
    return;
  }
  if (action === 'approve-bound') {
    const options = boundApprovalOptions();
    console.log(JSON.stringify(await grantBoundApproval(
      taskRoot, options.taskId, options.requestSha256, options.revision, options.eventCursor,
      options.reviewSha256, options.lessonIrSha256)));
    return;
  }
  if ((operationActions as string[]).includes(action)) {
    const args = operationOptions(action as OperationActionName);
    if (action === 'reconcile-operation') {
      console.log(JSON.stringify(await reconcileVideoOperation(taskRoot, args.taskId, args.binding)));
      return;
    }
    if (action === 'cancel-operation') {
      console.log(JSON.stringify(await cancelStoryboardOperation(
        taskRoot, args.taskId, args.requestSha256, args.eventCursor, args.revision!, args.binding)));
      return;
    }
    if (action === 'revise-operation') {
      const feedback = await readBoundedFeedbackStdin();
      console.log(JSON.stringify(await stageReviseOperation(
        taskRoot, args.taskId, args.requestSha256, args.revision!, args.eventCursor,
        args.reviewSha256!, args.lessonIrSha256!, args.binding, feedback)));
      return;
    }
    if (action === 'revision-start-operation') {
      console.log(JSON.stringify(await startRevisionOperation(
        taskRoot, args.taskId, args.requestSha256, args.revision!, args.eventCursor,
        args.reviewSha256!, args.lessonIrSha256!, args.binding)));
      return;
    }
    if (action === 'revision-install-operation') {
      const {installRevisionOperation} = await import('./revision-install.js');
      console.log(JSON.stringify(await installRevisionOperation(
        taskRoot, args.taskId, args.requestSha256, args.revision!, args.eventCursor,
        args.reviewSha256!, args.lessonIrSha256!, args.binding)));
      return;
    }
    let state: TaskState | Awaited<ReturnType<typeof inspectTask>>;
    if (action === 'initialize-operation') {
      state = await initializeOperationTask(taskRoot, args.taskId, args.requestSha256, args.binding);
      console.log(JSON.stringify(await inspectTask(taskRoot)));
      return;
    }
    if (action === 'storyboard-operation') {
      state = await buildOperationStoryboard(taskRoot, args.taskId, args.requestSha256,
        args.revision!, args.eventCursor, args.binding);
    } else {
      state = await grantOperationApproval(taskRoot, args.taskId, args.requestSha256,
        args.revision!, args.eventCursor, args.reviewSha256!, args.lessonIrSha256!, args.binding);
    }
    console.log(JSON.stringify(state));
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
    case 'approve-exact': {
      if (!revision) throw new Error('approve-exact requires --revision <n>');
      const reviewSha256 = optionString('--review-sha256');
      const lessonIrSha256 = optionString('--lesson-ir-sha256');
      if (!reviewSha256 || !lessonIrSha256) {
        throw new Error('approve-exact requires --review-sha256 <64-hex> and --lesson-ir-sha256 <64-hex>');
      }
      state = await grantExactApproval(taskRoot, revision, reviewSha256, lessonIrSha256);
      break;
    }
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
      throw new Error('usage: task-worker.ts <task-root> <init|init-exact|status|inspect|review|storyboard|approve|approve-exact|audio|run|render|cancel> [options]');
  }
  console.log(JSON.stringify({task_id: state.task_id, status: state.status,
    phase: state.phase, event_cursor: state.event_cursor}));
}

type OperationActionName = 'initialize-operation' | 'storyboard-operation' | 'approve-operation'
  | 'reconcile-operation' | 'cancel-operation' | 'revise-operation' | 'revision-start-operation'
  | 'revision-install-operation';
/**
 * Single source of truth for the operation CLI actions. Both the dispatch in
 * `cli()` and the error labelling in the top-level catch read this list, so a
 * new operation can never be dispatched but mislabelled (or vice versa).
 */
const operationActions: OperationActionName[] = [
  'initialize-operation', 'storyboard-operation', 'approve-operation', 'reconcile-operation',
  'cancel-operation', 'revise-operation', 'revision-start-operation', 'revision-install-operation',
];
function operationOptions(action: OperationActionName): {
  taskId: string; requestSha256: string; revision?: number; eventCursor: number;
  reviewSha256?: string; lessonIrSha256?: string; binding: VideoOperationBinding;
} {
  const raw = process.argv.slice(4);
  const args = raw.at(-1) === '--internal-worker' ? raw.slice(0, -1) : raw;
  // Start and install take the same eleven options in the same order, so they
  // share one projection rather than drifting apart.
  const isRevisionAction = action === 'revise-operation'
    || action === 'revision-start-operation' || action === 'revision-install-operation';
  const expected: string[] = action === 'initialize-operation'
    ? ['--task-id', '--request-sha256', '--operation-id', '--attempt-id', '--payload-digest', '--source-snapshot-sha256']
    : action === 'storyboard-operation'
      ? ['--task-id', '--request-sha256', '--revision', '--event-cursor', '--operation-id', '--attempt-id', '--payload-digest', '--source-snapshot-sha256']
      : isRevisionAction
      ? ['--task-id', '--request-sha256', '--revision', '--event-cursor', '--review-sha256', '--lesson-ir-sha256', '--feedback-sha256', '--operation-id', '--attempt-id', '--payload-digest', '--source-snapshot-sha256']
      : action === 'approve-operation'
      ? ['--task-id', '--request-sha256', '--revision', '--event-cursor', '--review-sha256', '--lesson-ir-sha256', '--operation-id', '--attempt-id', '--payload-digest', '--source-snapshot-sha256']
      : action === 'cancel-operation'
        ? ['--task-id', '--request-sha256', '--event-cursor', '--revision', '--operation-id', '--attempt-id', '--action', '--payload-digest', '--source-snapshot-sha256']
        : ['--task-id', '--request-sha256', '--event-cursor', '--operation-id', '--attempt-id', '--action', '--payload-digest', '--source-snapshot-sha256'];
  const requiredCount = action === 'reconcile-operation' ? 8
    : action === 'initialize-operation' ? 6
      : action === 'storyboard-operation' ? 8
        : action === 'cancel-operation' ? 9
          : isRevisionAction ? 11 : 10;
  if ((action !== 'reconcile-operation' && args.length !== expected.length * 2)
      || (action === 'reconcile-operation' && (args.length < requiredCount * 2 || args.length % 2 !== 0))) {
    throw new Error('invalid operation arguments');
  }
  const values: Record<string, string> = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i]!;
    if (key === '--internal-worker' || values[key] !== undefined) throw new Error('invalid operation arguments');
    values[key] = args[i + 1]!;
  }
  if (action === 'reconcile-operation') {
    const before = ['--task-id', '--request-sha256', '--event-cursor'];
    let index = 0;
    for (const option of before) {
      if (args[index] !== option) throw new Error('invalid operation argument order');
      index += 2;
    }
    if (args[index] === '--revision') { index += 2; }
    if (args[index] === '--review-sha256') {
      if (args[index + 2] !== '--lesson-ir-sha256') throw new Error('invalid operation argument order');
      index += 4;
    }
    if (args[index] === '--allow-cloud-tts') {
      if (args[index + 1] !== 'true') throw new Error('invalid operation arguments');
      index += 2;
    }
    const after = ['--operation-id', '--attempt-id', '--action', '--payload-digest', '--source-snapshot-sha256'];
    for (const option of after) {
      if (args[index] !== option) throw new Error('invalid operation argument order');
      index += 2;
    }
    if (index !== args.length) throw new Error('invalid operation arguments');
  } else {
    for (let i = 0; i < expected.length; i += 1) {
      if (args[i * 2] !== expected[i]) throw new Error('invalid operation argument order');
    }
  }
  const allowed = new Set(action === 'reconcile-operation'
    ? [...expected, '--revision', '--review-sha256', '--lesson-ir-sha256', '--allow-cloud-tts'] : expected);
  if (Object.keys(values).some(key => !allowed.has(key))
      || (action !== 'reconcile-operation' && expected.some(key => values[key] === undefined))
      || (action === 'reconcile-operation' && ['--task-id', '--request-sha256', '--event-cursor',
        '--operation-id', '--attempt-id', '--action', '--payload-digest', '--source-snapshot-sha256']
        .some(key => values[key] === undefined))) {
    throw new Error('invalid operation arguments');
  }
  const taskId = values['--task-id']!;
  const requestSha256 = values['--request-sha256']!;
  const actionName = action === 'initialize-operation' ? 'initialize'
    : action === 'storyboard-operation' ? 'storyboard'
      : action === 'approve-operation' ? 'approve'
        : isRevisionAction ? 'revise'
          : action === 'cancel-operation' ? 'storyboard' : values['--action'] as VideoAction;
  if (!['initialize', 'storyboard', 'approve', 'revise', ...(action === 'reconcile-operation' ? ['produce'] : [])].includes(actionName)
      || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(taskId)
      || !/^[a-f0-9]{64}$/.test(requestSha256)) throw new Error('invalid operation arguments');
  const revision = values['--revision'] ? parseSafePositiveInteger(values['--revision']) : undefined;
  const eventCursor = actionName === 'initialize' ? 0
    : parseSafePositiveInteger(values['--event-cursor'] ?? '');
  if (action === 'cancel-operation' && values['--action'] !== 'storyboard') {
    throw new Error('invalid operation arguments');
  }
  if (action === 'reconcile-operation' && actionName === 'initialize'
      && values['--event-cursor'] !== '0') throw new Error('invalid operation arguments');
  const reviewSha256 = values['--review-sha256'];
  const lessonIrSha256 = values['--lesson-ir-sha256'];
  const feedbackSha256 = values['--feedback-sha256'];
  const allowCloudTts = values['--allow-cloud-tts'];
  if (action === 'reconcile-operation') {
    if (actionName === 'produce') {
      if (allowCloudTts !== 'true' || revision === undefined || !reviewSha256 || !lessonIrSha256) {
        throw new Error('produce reconcile requires exact review binding and cloud TTS authorization');
      }
    } else if (allowCloudTts !== undefined) {
      throw new Error('cloud TTS authorization is valid only for produce reconciliation');
    }
  }
  const binding: VideoOperationBinding = {
    operation_id: values['--operation-id']!, attempt_id: values['--attempt-id']!,
    action: actionName, payload_digest: values['--payload-digest']!,
    source_snapshot_digest: values['--source-snapshot-sha256']!, request_sha256: requestSha256,
    input_event_cursor: eventCursor,
    ...(revision !== undefined ? {revision} : {}),
    ...(reviewSha256 ? {review_sha256: reviewSha256} : {}),
    ...(lessonIrSha256 ? {lesson_ir_sha256: lessonIrSha256} : {}),
    ...(feedbackSha256 ? {feedback_sha256: feedbackSha256} : {}),
    ...(allowCloudTts === 'true' ? {allow_cloud_tts: true as const} : {}),
  };
  validateOperationBinding(binding);
  return {taskId, requestSha256, revision, eventCursor, reviewSha256, lessonIrSha256, binding};
}

function exactInitializationOptions(): {taskId: string; requestSha256: string} {
  const args = process.argv.slice(4);
  const hasInternalWorker = args.length === 5 && args[4] === '--internal-worker';
  if ((args.length !== 4 && !hasInternalWorker) || args[0] !== '--task-id' || args[2] !== '--request-sha256'
      || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(args[1] ?? '')
      || !/^[a-f0-9]{64}$/.test(args[3] ?? '')) {
    throw new Error('init-exact requires --task-id ID --request-sha256 SHA');
  }
  return {taskId: args[1]!, requestSha256: args[3]!};
}

function exactStoryboardOptions(): {
  taskId: string; requestSha256: string; revision: number; eventCursor: number;
} {
  const args = process.argv.slice(4);
  const options = args.at(-1) === '--internal-worker' ? args.slice(0, -1) : args;
  if (options.length !== 8 || options[0] !== '--task-id' || options[2] !== '--request-sha256'
      || options[4] !== '--revision' || options[6] !== '--event-cursor'
      || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(options[1] ?? '')
      || !/^[a-f0-9]{64}$/.test(options[3] ?? '')) {
    throw new Error('invalid exact storyboard arguments');
  }
  return {
    taskId: options[1]!, requestSha256: options[3]!,
    revision: parseSafePositiveInteger(options[5]!),
    eventCursor: parseSafePositiveInteger(options[7]!),
  };
}

function boundApprovalOptions(): {
  taskId: string; requestSha256: string; revision: number; eventCursor: number;
  reviewSha256: string; lessonIrSha256: string;
} {
  const args = process.argv.slice(4);
  const options = args.at(-1) === '--internal-worker' ? args.slice(0, -1) : args;
  if (options.length !== 12 || options[0] !== '--task-id' || options[2] !== '--request-sha256'
      || options[4] !== '--revision' || options[6] !== '--event-cursor'
      || options[8] !== '--review-sha256' || options[10] !== '--lesson-ir-sha256'
      || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(options[1] ?? '')
      || !/^[a-f0-9]{64}$/.test(options[3] ?? '')
      || !/^[a-f0-9]{64}$/.test(options[9] ?? '')
      || !/^[a-f0-9]{64}$/.test(options[11] ?? '')) {
    throw new Error('invalid bound approval arguments');
  }
  return {
    taskId: options[1]!, requestSha256: options[3]!,
    revision: parseSafePositiveInteger(options[5]!),
    eventCursor: parseSafePositiveInteger(options[7]!),
    reviewSha256: options[9]!, lessonIrSha256: options[11]!,
  };
}

function parseSafePositiveInteger(value: string): number {
  if (!/^[1-9][0-9]*$/.test(value)) throw new Error('invalid exact action integer');
  const number = Number(value);
  if (!Number.isSafeInteger(number)) throw new Error('invalid exact action integer');
  return number;
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
    const inspectInvocation = process.argv[2] === 'inspect' || process.argv[3] === 'inspect';
    const reviewInvocation = process.argv[2] === 'review' || process.argv[3] === 'review';
    const exactInitialization = process.argv[2] === 'init-exact' || process.argv[3] === 'init-exact';
    const exactAction = ['storyboard-exact', 'approve-bound'].includes(process.argv[2] ?? '')
      || ['storyboard-exact', 'approve-bound'].includes(process.argv[3] ?? '');
    const operationInvocation = (operationActions as string[]).includes(process.argv[2] ?? '')
      || (operationActions as string[]).includes(process.argv[3] ?? '');
    console.error(inspectInvocation ? 'inspect failed'
      : reviewInvocation ? 'review failed'
        : exactInitialization ? 'exact task initialization failed'
          : operationInvocation ? 'video operation action failed'
            : exactAction ? 'exact video action failed' : safeError(error));
    process.exitCode = 1;
  }).finally(async () => {
    try {
      await closeWindowsReader();
    } catch {
      console.error('Windows file reader cleanup failed');
      process.exitCode = 1;
    }
  });
}
