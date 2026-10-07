import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {cp, mkdir, readFile, readdir, rm, stat, writeFile} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import test, {type TestContext} from 'node:test';
import {atomicWriteJson, fileDescriptor, sha256Bytes, skillRoot, type JsonObject} from '../scripts/lib.js';
import {readValidatedReviseCandidate} from '../scripts/revise-candidate.js';
import {readBoundedFeedbackStdin, stageReviseOperation} from '../scripts/revise-operation.js';
import {ensureTaskState, reconcileVideoOperation} from '../scripts/task-worker.js';
import {eventPath, statePath, transitionTaskState, type TaskApproval} from '../scripts/task-state.js';
import {operationParameters, payloadDigest, sourceSnapshotDigest,
  validateOperationBinding, type VideoOperationBinding} from '../scripts/video-operation.js';

const root = skillRoot();
const fixture = resolve(root, 'tests/fixtures/formula-note');
const output = resolve(root, '.local-output/revise-operation-tests');
const FEEDBACK = '把开场改得更快进入主题';

interface Task {
  path: string;
  taskId: string;
  requestSha256: string;
  lessonIrSha256: string;
  reviewSha256: string;
  eventCursor: number;
  revision: number;
}

async function reviewedTask(t: TestContext, name = randomUUID()): Promise<Task> {
  await mkdir(output, {recursive: true});
  const path = resolve(output, name);
  await mkdir(resolve(path, 'output'), {recursive: true});
  await mkdir(resolve(path, 'work'), {recursive: true});
  await cp(resolve(fixture, 'request.json'), resolve(path, 'request.json'));
  await cp(resolve(fixture, 'input'), resolve(path, 'input'), {recursive: true});
  const requestBytes = await readFile(resolve(path, 'request.json'));
  const request = JSON.parse(requestBytes.toString('utf8')) as JsonObject;
  const irBytes = await readFile(resolve(fixture, 'expected/lesson.ir.json'));
  await writeFile(resolve(path, 'output/lesson.ir.json'), irBytes);
  await writeFile(resolve(path, 'output/storyboard.html'), '<!doctype html><p>offline</p>');
  await writeFile(resolve(path, 'output/storyboard-scene-01.png'), Buffer.from('PNG'));
  const review = {
    schema_version: '1.0',
    task_id: request.task_id,
    status: 'awaiting_storyboard_review',
    lesson_ir_revision: 1,
    lesson_ir: await fileDescriptor(resolve(path, 'output/lesson.ir.json'), 'lesson.ir.json', 'application/json'),
    artifacts: [
      {role: 'storyboard',
        ...await fileDescriptor(resolve(path, 'output/storyboard.html'), 'storyboard.html', 'text/html')},
      {role: 'storyboard',
        ...await fileDescriptor(resolve(path, 'output/storyboard-scene-01.png'), 'storyboard-scene-01.png', 'image/png')},
    ],
  };
  await atomicWriteJson(resolve(path, 'output/review.json'), review);
  const reviewBytes = await readFile(resolve(path, 'output/review.json'));
  await ensureTaskState(path);
  const state = await transitionTaskState(path, 'test_review_ready', current => ({
    ...current!, status: 'awaiting_storyboard_review', phase: 'awaiting_approval',
    revision: 1, lesson_ir_sha256: sha256Bytes(irBytes), review_sha256: sha256Bytes(reviewBytes),
  }));
  t.after(async () => rm(path, {recursive: true, force: true}));
  return {
    path, taskId: request.task_id as string, requestSha256: sha256Bytes(requestBytes),
    lessonIrSha256: sha256Bytes(irBytes), reviewSha256: sha256Bytes(reviewBytes),
    eventCursor: state.event_cursor, revision: 1,
  };
}

function reviseBinding(current: Task, sourceSha: string, feedbackSha256: string,
                       overrides: Partial<VideoOperationBinding> = {}): VideoOperationBinding {
  const parameters = {revision: current.revision, event_cursor: current.eventCursor,
    review_sha256: current.reviewSha256, lesson_ir_sha256: current.lessonIrSha256,
    feedback_sha256: feedbackSha256};
  return {
    operation_id: randomUUID(), attempt_id: randomUUID(), action: 'revise',
    payload_digest: payloadDigest('revise', parameters), source_snapshot_digest: sourceSha,
    request_sha256: current.requestSha256, input_event_cursor: current.eventCursor,
    revision: current.revision, review_sha256: current.reviewSha256,
    lesson_ir_sha256: current.lessonIrSha256, feedback_sha256: feedbackSha256,
    ...overrides,
  };
}

/**
 * A state approval object that satisfies inspect-task.ts's binding rules: the
 * snapshot paths are derived from the approval id and every digest mirrors the
 * task's current review binding.
 */
function approvalFor(current: Task, approvalId: string = randomUUID()): TaskApproval {
  return {
    approval_id: approvalId, revision: current.revision,
    lesson_ir_sha256: current.lessonIrSha256, review_sha256: current.reviewSha256,
    lesson_ir_snapshot_path: `work/approved-input/${approvalId}/lesson.ir.json`,
    review_snapshot_path: `work/approved-input/${approvalId}/review.json`,
    granted_at: new Date().toISOString(),
  };
}

/** Moves a reviewed task into a valid approved state (approval not consumed yet). */
async function approveTask(current: Task, details: JsonObject = {}): Promise<Task> {
  const state = await transitionTaskState(current.path, 'test_approved', snapshotState => ({
    ...snapshotState!, status: 'approved', phase: 'approval_pending',
    approval: approvalFor(current),
  }), details);
  return {...current, eventCursor: state.event_cursor};
}

/** Moves a reviewed task into a valid terminal cancelled state. */
async function cancelTask(current: Task): Promise<Task> {
  const stamp = new Date().toISOString();
  const state = await transitionTaskState(current.path, 'test_cancelled', snapshotState => ({
    ...snapshotState!, status: 'cancelled', phase: 'cancelled',
    cancellation: {requested_at: stamp, completed_at: stamp},
  }));
  return {...current, eventCursor: state.event_cursor};
}

async function snapshot(path: string): Promise<Record<string, string>> {
  const files: Record<string, string> = {};
  for (const relative of ['request.json', 'work/task-state.json', 'work/task-events.ndjson',
    'output/lesson.ir.json', 'output/review.json', 'output/storyboard.html']) {
    files[relative] = sha256Bytes(await readFile(resolve(path, relative)));
  }
  return files;
}

test('staging a revision pins the observed revision and leaves the task untouched', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const feedbackSha256 = sha256Bytes(Buffer.from(FEEDBACK, 'utf8'));
  const binding = reviseBinding(current, sourceSha, feedbackSha256);
  const before = await snapshot(current.path);
  const workBefore = await readdir(resolve(current.path, 'work'));

  const result = await stageReviseOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256,
    binding, FEEDBACK);

  assert.equal(result.schema_version, 1);
  assert.equal(result.action, 'revise');
  assert.equal(result.task_id, current.taskId);
  assert.equal(result.revision, current.revision);
  assert.equal(result.input_event_cursor, current.eventCursor);
  assert.equal(result.review_sha256, current.reviewSha256);
  assert.equal(result.lesson_ir_sha256, current.lessonIrSha256);
  assert.equal(result.feedback_sha256, feedbackSha256);
  assert.equal(result.payload_digest, binding.payload_digest);
  assert.equal(result.candidate_path,
    `work/revise/${binding.operation_id}/candidate-lesson-ir.json`);

  // The operation must not create state, an audit event, or a candidate.
  assert.deepEqual(await snapshot(current.path), before);
  const workAfter = (await readdir(resolve(current.path, 'work'))).sort();
  assert.deepEqual(workAfter, [...workBefore, 'revise'].sort());
  await assert.rejects(readFile(resolve(current.path, 'work/revise', binding.operation_id,
    'candidate-lesson-ir.json')), /ENOENT/);

  // Later steps read the candidate through the same pinned binding.
  const candidate = JSON.parse(
    (await readFile(resolve(current.path, 'output/lesson.ir.json'))).toString('utf8')) as JsonObject;
  (candidate.scenes as JsonObject[])[0]!.narration = '修改后的开场：先给结论。';
  await writeFile(resolve(current.path, 'work/revise', binding.operation_id,
    'candidate-lesson-ir.json'), JSON.stringify(candidate));
  const read = await readValidatedReviseCandidate(current.path, binding.operation_id, {
    input_lesson_ir_sha256: result.lesson_ir_sha256,
    input_review_sha256: result.review_sha256,
    feedback_sha256: result.feedback_sha256,
  }, JSON.parse((await readFile(resolve(current.path, 'request.json'))).toString('utf8')) as JsonObject);
  assert.match((read.candidate.scenes as JsonObject[])[0]!.narration as string, /先给结论/);
});

test('the operation parameter projection reproduces the revise payload digest', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const binding = reviseBinding(current, sourceSha, sha256Bytes(Buffer.from(FEEDBACK, 'utf8')));
  assert.equal(payloadDigest('revise', operationParameters(binding)), binding.payload_digest);
  assert.deepEqual(operationParameters(binding), {
    revision: current.revision, event_cursor: current.eventCursor,
    review_sha256: current.reviewSha256, lesson_ir_sha256: current.lessonIrSha256,
    feedback_sha256: binding.feedback_sha256,
  });
});

test('a revise binding must pin both digests and the feedback, and no other action may carry one', () => {
  const base = {
    operation_id: randomUUID(), attempt_id: randomUUID(), action: 'revise',
    payload_digest: 'a'.repeat(64), source_snapshot_digest: 'b'.repeat(64),
    request_sha256: 'c'.repeat(64), input_event_cursor: 2, revision: 1,
    review_sha256: 'd'.repeat(64), lesson_ir_sha256: 'e'.repeat(64),
    feedback_sha256: 'f'.repeat(64),
  } as VideoOperationBinding;
  assert.doesNotThrow(() => validateOperationBinding(base));
  for (const key of ['review_sha256', 'lesson_ir_sha256', 'feedback_sha256'] as const) {
    assert.throws(() => validateOperationBinding({...base, [key]: undefined}),
      /video revision binding is invalid/);
  }
  assert.throws(() => validateOperationBinding({...base, revision: 0}),
    /video operation revision is invalid/);
  // Feedback on an action that has no feedback must be refused outright.
  assert.throws(() => validateOperationBinding({...base, action: 'initialize', input_event_cursor: 0,
    revision: undefined}), /video operation binding is invalid/);
  assert.throws(() => validateOperationBinding({...base, action: 'approve'}),
    /video operation binding is invalid/);
});

test('feedback must match the digest the binding commits to', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const binding = reviseBinding(current, sourceSha, sha256Bytes(Buffer.from('别的反馈', 'utf8')));
  await assert.rejects(stageReviseOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256,
    binding, FEEDBACK), /revise feedback does not match the operation binding/);
  await assert.rejects(readdir(resolve(current.path, 'work/revise')), /ENOENT/);
});

test('an observed request digest that the binding does not pin is refused', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  // The binding still names the correct request on disk, so the payload check
  // passes; only the caller's own claim about the request disagrees. The result
  // must never echo a request digest that was not the verified one.
  const binding = reviseBinding(current, sourceSha, sha256Bytes(Buffer.from(FEEDBACK, 'utf8')));
  await assert.rejects(stageReviseOperation(current.path, current.taskId, '1'.repeat(64),
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256,
    binding, FEEDBACK), /video revision binding does not match exact inputs/);
  await assert.rejects(readdir(resolve(current.path, 'work/revise')), /ENOENT/);
});

test('a stale observation is refused before anything is staged', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const feedbackSha256 = sha256Bytes(Buffer.from(FEEDBACK, 'utf8'));
  const stage = (task: Task, binding: VideoOperationBinding) => stageReviseOperation(
    task.path, task.taskId, task.requestSha256, task.revision, task.eventCursor,
    task.reviewSha256, task.lessonIrSha256, binding, FEEDBACK);

  const variants: Array<[string, Task]> = [
    ['event cursor', {...current, eventCursor: current.eventCursor + 1}],
    ['revision', {...current, revision: current.revision + 1}],
    ['review digest', {...current, reviewSha256: '0'.repeat(64)}],
    ['IR digest', {...current, lessonIrSha256: '0'.repeat(64)}],
  ];
  for (const [label, observed] of variants) {
    // The binding is fully consistent with the caller's observation, so only the
    // bytes on disk disagree. That mismatch is what must be refused as stale.
    await assert.rejects(stage(observed, reviseBinding(observed, sourceSha, feedbackSha256)),
      (error: Error) => /video revision binding is stale/.test(error.message),
      `${label} must be refused as stale`);
  }
  await assert.rejects(readdir(resolve(current.path, 'work/revise')), /ENOENT/,
    'no staging may be created by a refused observation');
});

test('a revision is refused once the revision has already been approved', async t => {
  const current = await reviewedTask(t);
  const approved = await approveTask(current);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const binding = reviseBinding(approved, sourceSha, sha256Bytes(Buffer.from(FEEDBACK, 'utf8')));
  await assert.rejects(stageReviseOperation(current.path, current.taskId, current.requestSha256,
    approved.revision, approved.eventCursor, approved.reviewSha256, approved.lessonIrSha256,
    binding, FEEDBACK), /video revision binding is stale/);
  await assert.rejects(readdir(resolve(current.path, 'work/revise')), /ENOENT/);
});

test('a revision is refused from a terminal state', async t => {
  const current = await reviewedTask(t);
  const cancelled = await cancelTask(current);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const binding = reviseBinding(cancelled, sourceSha, sha256Bytes(Buffer.from(FEEDBACK, 'utf8')));
  await assert.rejects(stageReviseOperation(current.path, current.taskId, current.requestSha256,
    cancelled.revision, cancelled.eventCursor, cancelled.reviewSha256, cancelled.lessonIrSha256,
    binding, FEEDBACK), /video revision binding is stale/);
  await assert.rejects(readdir(resolve(current.path, 'work/revise')), /ENOENT/);
});

test('a revision is never reported as a receipt for another action', async t => {
  const current = await reviewedTask(t);
  const approved = await approveTask(current, {video_operation: {
    schema_version: 1, operation_id: randomUUID(), attempt_id: randomUUID(), action: 'approve',
    payload_digest: 'a'.repeat(64), source_snapshot_digest: 'b'.repeat(64),
    request_sha256: current.requestSha256, input_event_cursor: current.eventCursor,
    result_event_cursor: current.eventCursor + 1, revision: current.revision,
    review_sha256: current.reviewSha256, lesson_ir_sha256: current.lessonIrSha256,
    approval_id: randomUUID(),
  }});
  const sourceSha = await sourceSnapshotDigest(current.path);
  const binding = reviseBinding(approved, sourceSha, sha256Bytes(Buffer.from(FEEDBACK, 'utf8')));
  const before = await snapshot(current.path);
  const proof = await reconcileVideoOperation(current.path, current.taskId, binding);
  assert.equal(proof.outcome, 'unconfirmed');
  assert.equal(proof.reason, 'receipt_missing');
  assert.equal(proof.result, null);
  assert.deepEqual(await snapshot(current.path), before);
});

test('the real CLI entry takes strictly ordered arguments and reads feedback from stdin', async t => {
  const current = await reviewedTask(t);
  const temporary = resolve(output, 'tmp');
  await mkdir(temporary, {recursive: true});
  const env: NodeJS.ProcessEnv = {
    PATH: `${dirname(process.execPath)}:/usr/bin:/bin`, LANG: 'C', LC_ALL: 'C',
    DISABLE_TELEMETRY: 'true', TMPDIR: temporary, HOME: process.env.HOME,
  };
  const worker = resolve(root, 'scripts/task-worker.ts');
  const sourceSha = await sourceSnapshotDigest(current.path);
  const feedbackSha256 = sha256Bytes(Buffer.from(FEEDBACK, 'utf8'));
  const binding = reviseBinding(current, sourceSha, feedbackSha256);
  const ordered = [
    '--task-id', current.taskId, '--request-sha256', current.requestSha256,
    '--revision', String(current.revision), '--event-cursor', String(current.eventCursor),
    '--review-sha256', current.reviewSha256, '--lesson-ir-sha256', current.lessonIrSha256,
    '--feedback-sha256', feedbackSha256, '--operation-id', binding.operation_id,
    '--attempt-id', binding.attempt_id, '--payload-digest', binding.payload_digest,
    '--source-snapshot-sha256', sourceSha,
  ];
  const invoke = (options: string[], input?: string) => spawnSync(process.execPath,
    ['--import', 'tsx', worker, 'revise-operation', current.path, ...options, '--internal-worker'],
    {cwd: root, env, encoding: 'utf8', timeout: 120_000, ...(input === undefined ? {} : {input})});

  // Same eleven options, same count, no duplicates -- only the order is wrong,
  // so this exercises the strict order check rather than the duplicate check.
  const misordered = [...ordered];
  [misordered[2], misordered[3], misordered[4], misordered[5]] =
    [ordered[4]!, ordered[5]!, ordered[2]!, ordered[3]!];
  const invalidOrder = invoke(misordered);
  assert.notEqual(invalidOrder.status, 0);
  assert.equal(invalidOrder.stderr.trim(), 'video operation action failed');
  await assert.rejects(readdir(resolve(current.path, 'work/revise')), /ENOENT/,
    'a rejected invocation must not stage anything');

  const missingFeedback = invoke(ordered);
  assert.notEqual(missingFeedback.status, 0);

  const oversize = invoke(ordered, 'x'.repeat(16 * 1024 + 1));
  assert.notEqual(oversize.status, 0);

  const before = await snapshot(current.path);
  const ok = invoke(ordered, FEEDBACK);
  assert.equal(ok.status, 0, ok.stderr);
  const result = JSON.parse(ok.stdout.trim()) as JsonObject;
  assert.equal(result.action, 'revise');
  assert.equal(result.feedback_sha256, feedbackSha256);
  assert.equal(result.candidate_path, `work/revise/${binding.operation_id}/candidate-lesson-ir.json`);
  assert.deepEqual(await snapshot(current.path), before);
  assert.equal((await readFile(resolve(current.path, 'work/revise', binding.operation_id,
    'feedback.txt'))).toString('utf8'), FEEDBACK);
});

test('the stdin reader rejects oversize and non-UTF-8 feedback', async () => {
  // Exercised through a child so the real stdin stream is used.
  const script = `
    import {readBoundedFeedbackStdin} from ${JSON.stringify(resolve(root, 'scripts/revise-operation.ts'))};
    process.stdout.write(await readBoundedFeedbackStdin(8));
  `;
  const run = (input: Buffer) => spawnSync(process.execPath,
    ['--import', 'tsx', '--input-type=module', '--eval', script],
    {cwd: root, encoding: 'utf8', input});
  assert.equal(run(Buffer.from('12345678')).stdout, '12345678');
  assert.notEqual(run(Buffer.from('123456789')).status, 0);
  assert.notEqual(run(Buffer.from([0xff, 0xfe, 0xfd])).status, 0);
});

test('re-staging the same operation is idempotent, and a tampered staging area conflicts', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const feedbackSha256 = sha256Bytes(Buffer.from(FEEDBACK, 'utf8'));
  const binding = reviseBinding(current, sourceSha, feedbackSha256);
  const stage = () => stageReviseOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256,
    binding, FEEDBACK);
  const first = await stage();
  assert.deepEqual(await stage(), first);

  // Once a ledger is committed, anything written into the staging area
  // afterwards disagrees with it. That must be refused, never silently rebuilt
  // and never handed to a later install as if it were the staged material.
  const staging = resolve(current.path, 'work/revise', binding.operation_id);
  await writeFile(resolve(staging, 'feedback.txt'), 'tampered');
  await assert.rejects(stage(), /revise staging conflict/);
  assert.ok((await stat(staging)).isDirectory(), 'the conflicted staging area is left intact');
  assert.equal((await readFile(resolve(staging, 'feedback.txt'))).toString('utf8'), 'tampered');
});
