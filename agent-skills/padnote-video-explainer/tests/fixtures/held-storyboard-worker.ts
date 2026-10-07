import {releaseExecutionLease, transitionTaskStateIf, transitionTaskStateValidated} from '../../scripts/task-state.js';
import type {VideoOperationBinding} from '../../scripts/video-operation.js';

const taskRoot = process.env.PADNOTE_TEST_TASK_ROOT;
const serialized = process.env.PADNOTE_TEST_VIDEO_BINDING;
if (!taskRoot || !serialized) throw new Error('test worker binding is missing');
const binding = JSON.parse(serialized) as VideoOperationBinding;

async function main(): Promise<void> {
  const {acquireExecutionLease} = await import('../../scripts/task-state.js');
  const lease = await acquireExecutionLease(taskRoot!);
  const workerAttemptId = 'worker-attempt-for-process-test';
  await transitionTaskStateValidated(taskRoot!, 'storyboard_started_exact', async current => {
    if (current.status !== 'initialized' || current.phase !== 'idle' || current.event_cursor !== binding.input_event_cursor) {
      throw new Error('fixture state is stale');
    }
    return {
      ...current, status: 'running', phase: 'storyboard',
      attempt: {
        attempt_id: workerAttemptId, lease_token: lease.token, pid: process.pid,
        started_at: new Date().toISOString(), phase: 'storyboard', external_effect_possible: false,
      },
    };
  }, {video_operation_started: {
    schema_version: 1, operation_id: binding.operation_id, attempt_id: binding.attempt_id,
    action: binding.action, payload_digest: binding.payload_digest,
    source_snapshot_digest: binding.source_snapshot_digest,
    request_sha256: binding.request_sha256, input_event_cursor: binding.input_event_cursor,
    revision: binding.revision,
  }});

  if (process.env.PADNOTE_TEST_IGNORE_SIGTERM === '1') {
    process.on('SIGTERM', () => undefined);
  } else process.once('SIGTERM', async () => {
    // Model the exact worker's late ready/failure CAS after cancellation wins.
    await transitionTaskStateIf(taskRoot!, 'test_late_storyboard_ready', current =>
      current?.status === 'running' && current.attempt?.attempt_id === workerAttemptId,
    current => ({...current, status: 'awaiting_storyboard_review', phase: 'awaiting_approval', attempt: undefined}));
    await transitionTaskStateIf(taskRoot!, 'test_late_storyboard_failure', current =>
      current?.status === 'running' && current.attempt?.attempt_id === workerAttemptId,
    current => ({...current, status: 'failed', phase: 'storyboard', attempt: undefined,
      error: {message: 'test failure', phase: 'storyboard', external_effect_possible: false}}));
    await releaseExecutionLease(taskRoot!, lease);
    process.exit(0);
  });

  process.once('SIGUSR1', async () => {
    await transitionTaskStateValidated(taskRoot!, 'test_storyboard_completed_first', async current => {
      if (current.status !== 'running' || current.attempt?.attempt_id !== workerAttemptId) return undefined;
      return {...current, status: 'awaiting_storyboard_review', phase: 'awaiting_approval',
        attempt: undefined, review_sha256: 'a'.repeat(64), lesson_ir_sha256: 'b'.repeat(64)};
    });
    await releaseExecutionLease(taskRoot!, lease);
  });

  setInterval(() => undefined, 60_000);
}

void main();
