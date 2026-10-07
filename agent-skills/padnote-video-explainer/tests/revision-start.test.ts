import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {cp, mkdir, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import test, {type TestContext} from 'node:test';
import {type TaskInspection} from '../scripts/inspect-task.js';
import {atomicWriteJson, fileDescriptor, sha256Bytes, skillRoot, type JsonObject} from '../scripts/lib.js';
import {stageReviseOperation} from '../scripts/revise-operation.js';
import {revisionStartRefusal, startRevisionOperation,
  type RevisionStartRefusal} from '../scripts/revision-start.js';
import {ensureTaskState} from '../scripts/task-worker.js';
import {transitionTaskState, type TaskApproval} from '../scripts/task-state.js';
import {payloadDigest, sourceSnapshotDigest, type VideoOperationBinding} from '../scripts/video-operation.js';

const root = skillRoot();
const fixture = resolve(root, 'tests/fixtures/formula-note');
const output = resolve(root, '.local-output/revision-start-tests');
const FEEDBACK = '把开场改得更快进入主题';

/** How this harness fails a lease, per scripts/task-state.ts:521-551. */
const LEASE_IDENTITY_ERROR = /cannot establish execution lease mutation identity on darwin/;

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

function approvalFor(current: Task, approvalId: string = randomUUID()): TaskApproval {
  return {
    approval_id: approvalId, revision: current.revision,
    lesson_ir_sha256: current.lessonIrSha256, review_sha256: current.reviewSha256,
    lesson_ir_snapshot_path: `work/approved-input/${approvalId}/lesson.ir.json`,
    review_snapshot_path: `work/approved-input/${approvalId}/review.json`,
    granted_at: new Date().toISOString(),
  };
}

/**
 * A binding whose payload digest is computed from exactly the parameters this
 * call will pass to startRevisionOperation, so the payload check cannot be what
 * refuses the attempt. That keeps each test measuring one thing.
 */
function startBinding(current: Task, sourceSha: string, feedbackSha256: string,
                      at: {revision?: number; eventCursor?: number} = {}): VideoOperationBinding {
  const revision = at.revision ?? current.revision;
  const eventCursor = at.eventCursor ?? current.eventCursor;
  const parameters = {revision, event_cursor: eventCursor,
    review_sha256: current.reviewSha256, lesson_ir_sha256: current.lessonIrSha256,
    feedback_sha256: feedbackSha256};
  return {
    operation_id: randomUUID(), attempt_id: randomUUID(), action: 'revise',
    payload_digest: payloadDigest('revise', parameters), source_snapshot_digest: sourceSha,
    request_sha256: current.requestSha256, input_event_cursor: eventCursor,
    revision, review_sha256: current.reviewSha256,
    lesson_ir_sha256: current.lessonIrSha256, feedback_sha256: feedbackSha256,
  };
}

async function snapshot(path: string): Promise<Record<string, string>> {
  const files: Record<string, string> = {};
  for (const relative of ['request.json', 'work/task-state.json', 'work/task-events.ndjson',
    'output/lesson.ir.json', 'output/review.json']) {
    files[relative] = sha256Bytes(await readFile(resolve(path, relative)));
  }
  return files;
}

/**
 * A persisted state as `inspectTask` reports it, carrying a complete review
 * binding by default. That way the only thing any single case varies is the
 * status/phase pair under test, never a missing field.
 */
function inspection(overrides: Partial<TaskInspection> = {}): TaskInspection {
  return {protocol_version: 1, task_id: 'task-id', status: 'initialized', phase: 'idle',
    event_cursor: 1, revision: 1, lesson_ir_sha256: 'a'.repeat(64), review_sha256: 'b'.repeat(64),
    ...overrides};
}

/** The one reviewable state, so only the field under test differs. */
function awaitingInspection(overrides: Partial<TaskInspection> = {}): TaskInspection {
  return inspection({status: 'awaiting_storyboard_review', phase: 'awaiting_approval', ...overrides});
}

test('the refusal decision is total over every state the whitelist admits', () => {
  // Every status/phase pair a persisted state can legally hold, with the single
  // outcome each one must produce. `undefined` appears exactly once: the one
  // state a revision may start from.
  const table: Array<[string, string, RevisionStartRefusal | undefined]> = [
    ['initialized', 'idle', 'task_not_awaiting_review'],
    ['awaiting_storyboard_review', 'awaiting_approval', undefined],
    ['approved', 'approval_pending', 'storyboard_approved'],
    ['ready_to_render', 'audio_ready', 'render_in_progress'],
    ['running', 'storyboard', 'generation_in_flight'],
    ['running', 'approval_consumed', 'approval_consumed'],
    ['running', 'tts_starting', 'approval_consumed'],
    ['running', 'audio_ready', 'render_in_progress'],
    ['running', 'render_starting', 'render_in_progress'],
    ['cancelling', 'cancelling', 'task_cancelled'],
    ['cancelled', 'cancelled', 'task_cancelled'],
    ['completed', 'completed', 'task_completed'],
    // interrupted and failed carry no phase constraint; a revision is never a
    // way to resume a task that is not awaiting a review.
    ['interrupted', 'storyboard', 'task_not_awaiting_review'],
    ['interrupted', 'idle', 'task_not_awaiting_review'],
    ['failed', 'storyboard', 'task_not_awaiting_review'],
    ['failed', 'render_starting', 'task_not_awaiting_review'],
  ];
  for (const [status, phase, expected] of table) {
    assert.equal(revisionStartRefusal(inspection({status, phase})), expected,
      `${status}/${phase}`);
  }
  // The table covers the whole closed status set, so a status added later cannot
  // be silently left out of it.
  const covered = new Set(table.map(([status]) => status));
  assert.deepEqual([...covered].sort(), ['approved', 'awaiting_storyboard_review', 'cancelled',
    'cancelling', 'completed', 'failed', 'initialized', 'interrupted', 'ready_to_render', 'running']);
});

test('a state that is not awaiting approval is refused rather than allowed through', () => {
  // Fail-closed for values the whitelist does not know yet: an unknown status
  // and an unknown phase must both refuse, never fall through to allowance.
  assert.equal(revisionStartRefusal(inspection({status: 'brand_new_status'})),
    'task_not_awaiting_review');
  assert.notEqual(revisionStartRefusal(inspection({status: 'running', phase: 'brand_new_phase'})),
    undefined);
  assert.equal(revisionStartRefusal(awaitingInspection({phase: 'storyboard'})),
    'review_not_awaiting_approval');
});

test('a revision is refused whenever the review binding is incomplete', () => {
  const base = awaitingInspection();
  assert.equal(revisionStartRefusal(base), undefined);
  assert.equal(revisionStartRefusal({...base, revision: undefined}), 'task_not_awaiting_review');
  assert.equal(revisionStartRefusal({...base, revision: 0}), 'task_not_awaiting_review');
  assert.equal(revisionStartRefusal({...base, review_sha256: undefined}), 'task_not_awaiting_review');
  assert.equal(revisionStartRefusal({...base, lesson_ir_sha256: undefined}), 'task_not_awaiting_review');
  // A carried approval means the storyboard is already approved, not revisable.
  assert.equal(revisionStartRefusal({...base, approval: {
    approval_id: randomUUID(), revision: 1, lesson_ir_sha256: 'a'.repeat(64),
    review_sha256: 'b'.repeat(64), granted_at: new Date().toISOString(),
  }}), 'storyboard_approved');
});

test('starting a revision refuses a state whose approval has been consumed', async t => {
  const current = await reviewedTask(t);
  const stamp = new Date().toISOString();
  const state = await transitionTaskState(current.path, 'test_approval_consumed', snapshotState => ({
    ...snapshotState!, status: 'running', phase: 'approval_consumed',
    approval: {...approvalFor(current), consumed_at: stamp},
    attempt: {attempt_id: randomUUID(), pid: process.pid, started_at: stamp,
      phase: 'approval_consumed', external_effect_possible: true},
  }));
  const started = {...current, eventCursor: state.event_cursor};
  const sourceSha = await sourceSnapshotDigest(current.path);
  const binding = startBinding(started, sourceSha, sha256Bytes(Buffer.from(FEEDBACK, 'utf8')));
  const before = await snapshot(current.path);

  await assert.rejects(startRevisionOperation(current.path, current.taskId, current.requestSha256,
    started.revision, started.eventCursor, started.reviewSha256, started.lessonIrSha256, binding),
  /video revision cannot start: approval_consumed/);
  assert.deepEqual(await snapshot(current.path), before,
    'a refused revision must not touch the task');
});

test('starting a revision refuses a granted-but-unconsumed approval', async t => {
  const current = await reviewedTask(t);
  const state = await transitionTaskState(current.path, 'test_approved', snapshotState => ({
    ...snapshotState!, status: 'approved', phase: 'approval_pending',
    approval: approvalFor(current),
  }));
  const approved = {...current, eventCursor: state.event_cursor};
  const sourceSha = await sourceSnapshotDigest(current.path);
  const binding = startBinding(approved, sourceSha, sha256Bytes(Buffer.from(FEEDBACK, 'utf8')));
  const before = await snapshot(current.path);

  await assert.rejects(startRevisionOperation(current.path, current.taskId, current.requestSha256,
    approved.revision, approved.eventCursor, approved.reviewSha256, approved.lessonIrSha256, binding),
  /video revision cannot start: storyboard_approved/);
  assert.deepEqual(await snapshot(current.path), before);
});

test('starting a revision refuses a cancelled task', async t => {
  const current = await reviewedTask(t);
  const stamp = new Date().toISOString();
  const state = await transitionTaskState(current.path, 'test_cancelled', snapshotState => ({
    ...snapshotState!, status: 'cancelled', phase: 'cancelled',
    cancellation: {requested_at: stamp, completed_at: stamp},
  }));
  const cancelled = {...current, eventCursor: state.event_cursor};
  const sourceSha = await sourceSnapshotDigest(current.path);
  const binding = startBinding(cancelled, sourceSha, sha256Bytes(Buffer.from(FEEDBACK, 'utf8')));

  await assert.rejects(startRevisionOperation(current.path, current.taskId, current.requestSha256,
    cancelled.revision, cancelled.eventCursor, cancelled.reviewSha256, cancelled.lessonIrSha256,
    binding), /video revision cannot start: task_cancelled/);
});

test('starting a revision refuses a binding that does not name the exact inputs', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const feedbackSha256 = sha256Bytes(Buffer.from(FEEDBACK, 'utf8'));
  const binding = startBinding(current, sourceSha, feedbackSha256);
  const before = await snapshot(current.path);

  // A payload digest that is not the digest of these parameters, and a source
  // snapshot that is not this task's, are both caught against the bytes on disk.
  const tampered: Array<[string, VideoOperationBinding]> = [
    ['payload digest', {...binding, payload_digest: '2'.repeat(64)}],
    ['source snapshot', {...binding, source_snapshot_digest: '3'.repeat(64)}],
  ];
  for (const [label, candidate] of tampered) {
    await assert.rejects(startRevisionOperation(current.path, current.taskId, current.requestSha256,
      current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, candidate),
    /video operation binding does not match exact inputs/, label);
  }

  // And the caller's own claim about the request must equal the digest the
  // binding already proved, so a borrowed identifier is refused here too.
  await assert.rejects(startRevisionOperation(current.path, current.taskId, '1'.repeat(64),
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /video operation binding does not match exact inputs/);

  assert.deepEqual(await snapshot(current.path), before);
  await assert.rejects(readdir(resolve(current.path, 'work/revise')), /ENOENT/);
});

test('a revision bound to a different action is refused outright', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const binding = {...startBinding(current, sourceSha, sha256Bytes(Buffer.from(FEEDBACK, 'utf8'))),
    action: 'approve'} as VideoOperationBinding;
  await assert.rejects(startRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /invalid revision start arguments/);

  const good = startBinding(current, sourceSha, sha256Bytes(Buffer.from(FEEDBACK, 'utf8')));
  const bad: Array<[string, string, number, number, string, string]> = [
    ['task id', 'not a task id', current.revision, current.eventCursor,
      current.reviewSha256, current.lessonIrSha256],
    ['request digest', current.taskId, current.revision, current.eventCursor,
      current.reviewSha256, current.lessonIrSha256],
    ['revision', current.taskId, 0, current.eventCursor,
      current.reviewSha256, current.lessonIrSha256],
    ['event cursor', current.taskId, current.revision, 0,
      current.reviewSha256, current.lessonIrSha256],
    ['review digest', current.taskId, current.revision, current.eventCursor,
      'nope', current.lessonIrSha256],
    ['IR digest', current.taskId, current.revision, current.eventCursor,
      current.reviewSha256, 'nope'],
  ];
  for (const [label, taskId, revision, cursor, review, ir] of bad) {
    await assert.rejects(startRevisionOperation(current.path, taskId,
      label === 'request digest' ? 'not-a-digest' : current.requestSha256,
      revision, cursor, review, ir, good), /invalid revision start arguments/, label);
  }
});

test('a revision is refused when the observation no longer matches the task', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const feedbackSha256 = sha256Bytes(Buffer.from(FEEDBACK, 'utf8'));
  const before = await snapshot(current.path);

  // The binding and the arguments agree with each other, so only the bytes on
  // disk disagree. That disagreement is what must be refused as stale.
  const stale = startBinding(current, sourceSha, feedbackSha256,
    {eventCursor: current.eventCursor + 1});
  await assert.rejects(startRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor + 1, current.reviewSha256, current.lessonIrSha256, stale),
  /video revision binding is stale/);

  const wrongRevision = startBinding(current, sourceSha, feedbackSha256,
    {revision: current.revision + 1});
  await assert.rejects(startRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision + 1, current.eventCursor, current.reviewSha256, current.lessonIrSha256,
    wrongRevision), /video revision binding is stale/);

  assert.deepEqual(await snapshot(current.path), before);
});

test('a revision is refused when nothing was staged for it', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const binding = startBinding(current, sourceSha, sha256Bytes(Buffer.from(FEEDBACK, 'utf8')));
  const before = await snapshot(current.path);

  await assert.rejects(startRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /ENOENT/);
  assert.deepEqual(await snapshot(current.path), before);
});

test('a revision is refused when the staged material was tampered with', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const feedbackSha256 = sha256Bytes(Buffer.from(FEEDBACK, 'utf8'));
  const binding = startBinding(current, sourceSha, feedbackSha256);
  await stageReviseOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256,
    binding, FEEDBACK);
  const before = await snapshot(current.path);

  // The ledger still pins the original feedback, so only re-hashing the staged
  // file can detect this. Anything else would execute unverified bytes.
  await writeFile(resolve(current.path, 'work/revise', binding.operation_id, 'feedback.txt'), 'tampered');
  await assert.rejects(startRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /revise staging does not match the operation binding/);
  assert.deepEqual(await snapshot(current.path), before);
});

test('a revision is refused when a newer Lesson IR has already been installed', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const feedbackSha256 = sha256Bytes(Buffer.from(FEEDBACK, 'utf8'));
  const binding = startBinding(current, sourceSha, feedbackSha256);
  await stageReviseOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256,
    binding, FEEDBACK);
  const staged = await snapshot(current.path);

  // Another attempt installed revision 2 in the meantime: the staged material is
  // now based on a Lesson IR that is no longer the formal one.
  const moved = JSON.parse((await readFile(resolve(current.path, 'output/lesson.ir.json')))
    .toString('utf8')) as JsonObject;
  (moved.scenes as JsonObject[])[0]!.narration = '另一个 attempt 安装的 revision 2';
  await atomicWriteJson(resolve(current.path, 'output/lesson.ir.json'), moved);
  const afterInstall = await snapshot(current.path);
  assert.notEqual(afterInstall['output/lesson.ir.json'], staged['output/lesson.ir.json']);

  await assert.rejects(startRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /revise staging is based on a stale Lesson IR/);
  // The refusal itself changes nothing, so the only difference from the staged
  // reading is the Lesson IR this test rewrote by hand.
  const refused = await snapshot(current.path);
  assert.deepEqual(refused, afterInstall);
  for (const key of Object.keys(staged)) {
    if (key !== 'output/lesson.ir.json') assert.equal(refused[key], staged[key], key);
  }
});

test('a fully valid revision start gets past preflight to the execution lease', async t => {
  const current = await reviewedTask(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const feedbackSha256 = sha256Bytes(Buffer.from(FEEDBACK, 'utf8'));
  const binding = startBinding(current, sourceSha, feedbackSha256);
  await stageReviseOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256,
    binding, FEEDBACK);

  try {
    const result = await startRevisionOperation(current.path, current.taskId, current.requestSha256,
      current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding);
    t.diagnostic('lease available: the full start succeeded and is asserted below');
    assert.equal(result.schema_version, 1);
    assert.equal(result.action, 'revise');
    assert.equal(result.task_id, current.taskId);
    assert.equal(result.status, 'running');
    assert.equal(result.phase, 'storyboard');
    assert.equal(result.revision, current.revision);
    assert.equal(result.operation_id, binding.operation_id);
    assert.equal(result.feedback_sha256, feedbackSha256);
    assert.equal(result.input_event_cursor, current.eventCursor);
    assert.equal(result.start_event_cursor, current.eventCursor + 1);

    const state = JSON.parse((await readFile(resolve(current.path, 'work/task-state.json')))
      .toString('utf8')) as JsonObject;
    assert.equal(state.status, 'running');
    assert.equal(state.phase, 'storyboard');
    assert.equal(state.approval, undefined, 'the previous approval must be gone');
    assert.equal((state.attempt as JsonObject).phase, 'storyboard');
    assert.equal((state.attempt as JsonObject).attempt_id, result.attempt_id);

    const events = (await readFile(resolve(current.path, 'work/task-events.ndjson'), 'utf8'))
      .trim().split('\n');
    const last = JSON.parse(events.at(-1)!) as JsonObject;
    assert.equal(last.event, 'revision_started');
    assert.equal(last.status, 'running');
    assert.equal(last.phase, 'storyboard');
    assert.equal(last.sequence, result.start_event_cursor);
    // transitionTaskStateValidated spreads `details` into the event, so the
    // operation record sits at the top level rather than under a `details` key.
    assert.equal(last.revision, current.revision);
    const started = last.video_operation_started as JsonObject;
    assert.ok(started, 'the revision_started event must carry the operation record');
    assert.equal(started.schema_version, 1);
    assert.equal(started.action, 'revise');
    assert.equal(started.operation_id, binding.operation_id);
    assert.equal(started.attempt_id, binding.attempt_id);
    assert.equal(started.payload_digest, binding.payload_digest);
    assert.equal(started.source_snapshot_digest, binding.source_snapshot_digest);
    assert.equal(started.request_sha256, current.requestSha256);
    assert.equal(started.input_event_cursor, current.eventCursor);
    assert.equal(started.review_sha256, current.reviewSha256);
    assert.equal(started.lesson_ir_sha256, current.lessonIrSha256);
    assert.equal(started.feedback_sha256, feedbackSha256);
  } catch (error: any) {
    // On darwin this harness blocks /bin/ps, so no strong worker process
    // identity can be established and the lease cannot be taken (task-state.ts:521).
    // That is the documented platform limit, not a preflight rejection: every
    // preflight error has a different message and would fail this test.
    t.diagnostic(`lease unavailable here: ${error.message}`);
    assert.match(error.message, LEASE_IDENTITY_ERROR);
  }
});

test('the real CLI entry takes the same strictly ordered options and refuses before acting', async t => {
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
  const binding = startBinding(current, sourceSha, feedbackSha256);
  const ordered = [
    '--task-id', current.taskId, '--request-sha256', current.requestSha256,
    '--revision', String(current.revision), '--event-cursor', String(current.eventCursor),
    '--review-sha256', current.reviewSha256, '--lesson-ir-sha256', current.lessonIrSha256,
    '--feedback-sha256', feedbackSha256, '--operation-id', binding.operation_id,
    '--attempt-id', binding.attempt_id, '--payload-digest', binding.payload_digest,
    '--source-snapshot-sha256', sourceSha,
  ];
  const invoke = (options: string[]) => spawnSync(process.execPath,
    ['--import', 'tsx', worker, 'revision-start-operation', current.path, ...options, '--internal-worker'],
    {cwd: root, env, encoding: 'utf8', timeout: 120_000});

  const before = await snapshot(current.path);

  // Same eleven options, only the order is wrong: this exercises the strict
  // order check rather than the count or duplicate check.
  const misordered = [...ordered];
  [misordered[2], misordered[3], misordered[4], misordered[5]] =
    [ordered[4]!, ordered[5]!, ordered[2]!, ordered[3]!];
  const invalidOrder = invoke(misordered);
  assert.notEqual(invalidOrder.status, 0);
  assert.equal(invalidOrder.stderr.trim(), 'video operation action failed');

  // A revision start must carry the feedback digest too, so the ten-option list
  // with the feedback pair removed is refused.
  const shortlist = ordered.filter((_, index) => index < 12 || index >= 14);
  assert.equal(shortlist.length, 20);
  assert.notEqual(invoke(shortlist).status, 0);

  await assert.rejects(readdir(resolve(current.path, 'work/revise')), /ENOENT/,
    'a rejected invocation must not stage anything');
  assert.deepEqual(await snapshot(current.path), before);

  // A well-formed invocation reaches the domain checks and is refused there, on
  // every platform: this task is cancelled, so no revision may begin.
  const stamp = new Date().toISOString();
  await transitionTaskState(current.path, 'test_cancelled', snapshotState => ({
    ...snapshotState!, status: 'cancelled', phase: 'cancelled',
    cancellation: {requested_at: stamp, completed_at: stamp},
  }));
  const cancelled = await snapshot(current.path);
  const refused = invoke(ordered);
  assert.notEqual(refused.status, 0);
  assert.equal(refused.stderr.trim(), 'video operation action failed');
  assert.deepEqual(await snapshot(current.path), cancelled);
});
