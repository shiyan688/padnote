import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {cp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import test, {type TestContext} from 'node:test';
import {inspectTask, type TaskInspection} from '../scripts/inspect-task.js';
import {atomicWriteJson, fileDescriptor, sha256Bytes, skillRoot, type JsonObject} from '../scripts/lib.js';
import {stageReviseOperation} from '../scripts/revise-operation.js';
import {installRevisionOperation, revisionInstallRefusal,
  type RevisionInstallExpectation, type RevisionInstallRefusal} from '../scripts/revision-install.js';
import {revisionAttempt, revisionStartedDetails, startRevisionOperation,
  type RevisionStartResult} from '../scripts/revision-start.js';
import {ensureTaskState} from '../scripts/task-worker.js';
import {transitionTaskState, type TaskState} from '../scripts/task-state.js';
import {payloadDigest, sourceSnapshotDigest, type VideoOperationBinding} from '../scripts/video-operation.js';

const root = skillRoot();
const fixture = resolve(root, 'tests/fixtures/formula-note');
const output = resolve(root, '.local-output/revision-install-tests');
const FEEDBACK = '把开场改得更快进入主题';
/** The one edit the generator makes: a candidate that differs from revision 1. */
const CANDIDATE_NARRATION = '开场就直接说清楚：L1 收敛衡量的是整体误差面积，而不是每个点。';

/** How this harness fails a lease, per scripts/task-state.ts:521-551. */
const LEASE_IDENTITY_ERROR = /cannot establish execution lease mutation identity on darwin/;

/** Two stable identifiers, so a fabricated audit record reads as a real one. */
const OPERATION_ID = '11111111-1111-4111-8111-111111111111';
const ATTEMPT_ID = '22222222-2222-4222-8222-222222222222';

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

/**
 * A binding whose payload digest is computed from exactly the parameters this
 * call will pass to the operation, so the payload check cannot be what refuses
 * the attempt. That keeps each test measuring one thing.
 */
function revisionBinding(current: Task, sourceSha: string, feedbackSha256: string,
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

async function snapshot(path: string, operationId?: string): Promise<Record<string, string>> {
  const files: Record<string, string> = {};
  const relatives = ['request.json', 'work/task-state.json', 'work/task-events.ndjson',
    'output/lesson.ir.json', 'output/review.json'];
  if (operationId) relatives.push(`work/revise/${operationId}/candidate-lesson-ir.json`);
  for (const relative of relatives) {
    files[relative] = sha256Bytes(await readFile(resolve(path, relative)));
  }
  return files;
}

/** The candidate the generator is expected to have written, as the plan's step 2 allows. */
async function candidateIr(path: string): Promise<Buffer> {
  const ir = JSON.parse((await readFile(resolve(path, 'output/lesson.ir.json')))
    .toString('utf8')) as JsonObject;
  (ir.scenes as JsonObject[])[0]!.narration = CANDIDATE_NARRATION;
  return Buffer.from(`${JSON.stringify(ir, null, 2)}\n`, 'utf8');
}

async function writeCandidate(task: Task, operationId: string, bytes: Buffer): Promise<void> {
  await writeFile(resolve(task.path, 'work/revise', operationId, 'candidate-lesson-ir.json'), bytes);
}

/** Stages one operation, exactly as the revision plan's step 2 does. */
async function stagedRevision(t: TestContext, current: Task): Promise<VideoOperationBinding> {
  const sourceSha = await sourceSnapshotDigest(current.path);
  const feedbackSha256 = sha256Bytes(Buffer.from(FEEDBACK, 'utf8'));
  const binding = revisionBinding(current, sourceSha, feedbackSha256);
  await stageReviseOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256,
    binding, FEEDBACK);
  return binding;
}

/**
 * Brings the task to exactly the state step 3 leaves behind.
 *
 * On a platform where the execution lease is available this is the real
 * `startRevisionOperation`. This harness cannot take a lease (`/bin/ps` is
 * blocked on darwin, task-state.ts:521), so the fallback reproduces the writes
 * the start performs *through the very builders the start uses* --
 * `revisionAttempt` and `revisionStartedDetails` -- rather than by hand-copying
 * them. Every assertion below still runs against the real install path.
 *
 * After this call the task sits at `reading.eventCursor + 1`, which is precisely
 * the `install_cursor` an install is given when it is handed `reading` itself.
 */
async function startedRevision(t: TestContext, reading: Task,
                               binding: VideoOperationBinding): Promise<void> {
  const started: RevisionStartResult | undefined = await startRevisionOperation(reading.path,
    reading.taskId, reading.requestSha256, reading.revision, reading.eventCursor,
    reading.reviewSha256, reading.lessonIrSha256, binding).catch(async (error: any) => {
      assert.match(error.message, LEASE_IDENTITY_ERROR,
        'a valid start may only fail here on the platform lease limit');
      await transitionTaskState(reading.path, 'revision_started', state => ({
        ...state!, status: 'running', phase: 'storyboard', approval: undefined,
        attempt: revisionAttempt(randomUUID()) as TaskState['attempt'],
        error: undefined, cancellation: undefined,
      }), revisionStartedDetails(binding, reading.eventCursor, reading.revision,
        reading.reviewSha256, reading.lessonIrSha256, binding.feedback_sha256!));
      t.diagnostic('lease unavailable here: the started state is reproduced from the start builders');
      return undefined;
    });
  if (started) assert.equal(started.status, 'running');
}

/**
 * A storyboard generation that has nothing to do with any revision. It lands on
 * exactly the cursor an install would be given, which is the only way the cursor
 * check gets exercised on its own.
 */
async function storyboardInFlight(reading: Task): Promise<void> {
  await transitionTaskState(reading.path, 'storyboard_started', state => ({
    ...state!, status: 'running', phase: 'storyboard', approval: undefined,
    attempt: revisionAttempt(randomUUID()) as TaskState['attempt'],
    error: undefined, cancellation: undefined,
  }));
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

/** The one installable state, so only the field under test differs. */
function runningInspection(overrides: Partial<TaskInspection> = {}): TaskInspection {
  return inspection({status: 'running', phase: 'storyboard', event_cursor: 5, ...overrides});
}

const expectation = (overrides: Partial<RevisionInstallExpectation> = {}): RevisionInstallExpectation => ({
  task_id: 'task-id', install_cursor: 5, revision: 1,
  operation_id: OPERATION_ID, attempt_id: ATTEMPT_ID, ...overrides});

/**
 * A `revision_started` audit record as `readAuditTail` returns it. The
 * `video_operation_started` body is built from the same identifiers the
 * operation used, so a single override varies one claim at a time.
 */
function startedEvent(overrides: {
  sequence?: number; event?: string; action?: string; revision?: number;
  operation_id?: string; attempt_id?: string; started?: JsonObject | undefined;
} = {}): JsonObject {
  const started = 'started' in overrides ? overrides.started : {
    schema_version: 1,
    operation_id: overrides.operation_id ?? OPERATION_ID,
    attempt_id: overrides.attempt_id ?? ATTEMPT_ID,
    action: overrides.action ?? 'revise',
  };
  return {
    sequence: overrides.sequence ?? 5,
    at: '2026-09-27T00:00:00.000Z',
    event: overrides.event ?? 'revision_started',
    task_id: 'task-id',
    status: 'running',
    phase: 'storyboard',
    revision: overrides.revision ?? 1,
    video_operation_started: started,
  };
}

test('the install refusal decision is total over every state the whitelist admits', () => {
  // Every status/phase pair a persisted state can legally hold, with the single
  // outcome each one must produce. `undefined` appears exactly once: the one
  // state a revision may be installed from. Cursor 5 is the installable one.
  const table: Array<[string, string, number, RevisionInstallRefusal | undefined]> = [
    ['running', 'storyboard', 5, undefined],
    ['running', 'storyboard', 4, 'cursor_not_at_revision_start'],
    ['running', 'storyboard', 6, 'cursor_not_at_revision_start'],
    ['running', 'approval_consumed', 5, 'phase_not_storyboard'],
    ['running', 'tts_starting', 5, 'phase_not_storyboard'],
    ['running', 'audio_ready', 5, 'phase_not_storyboard'],
    ['running', 'render_starting', 5, 'phase_not_storyboard'],
    ['initialized', 'idle', 5, 'task_not_running'],
    ['awaiting_storyboard_review', 'awaiting_approval', 5, 'task_not_running'],
    ['approved', 'approval_pending', 5, 'task_not_running'],
    ['ready_to_render', 'audio_ready', 5, 'task_not_running'],
    ['interrupted', 'storyboard', 5, 'task_not_running'],
    ['failed', 'storyboard', 5, 'task_not_running'],
    ['cancelling', 'cancelling', 5, 'task_cancelled'],
    ['cancelled', 'cancelled', 5, 'task_cancelled'],
    ['completed', 'completed', 5, 'task_completed'],
  ];
  for (const [status, phase, cursor, expected] of table) {
    assert.equal(revisionInstallRefusal(inspection({status, phase, event_cursor: cursor}),
      expectation(), startedEvent()), expected, `${status}/${phase}@${cursor}`);
  }
  // The table covers the whole closed status set, so a status added later cannot
  // be silently left out of it.
  const covered = new Set(table.map(([status]) => status));
  assert.deepEqual([...covered].sort(), ['approved', 'awaiting_storyboard_review', 'cancelled',
    'cancelling', 'completed', 'failed', 'initialized', 'interrupted', 'ready_to_render', 'running']);
});

test('an install is refused unless the audit record is this operation version of revision_started', () => {
  // The cursor is necessary but not sufficient. Each case below holds the
  // installable status, phase and cursor, and varies only the record.
  const cases: Array<[string, JsonObject | undefined]> = [
    ['no audit record at all', undefined],
    ['a record for another operation', startedEvent({operation_id: randomUUID()})],
    ['a record for another attempt', startedEvent({attempt_id: randomUUID()})],
    ['a record for another revision', startedEvent({revision: 2})],
    ['a record at another sequence', startedEvent({sequence: 6})],
    ['an event that is not a revision start', startedEvent({event: 'storyboard_started'})],
    ['a record for another action', startedEvent({action: 'approve'})],
    ['a record carrying no operation body', startedEvent({started: undefined})],
  ];
  for (const [label, record] of cases) {
    assert.equal(revisionInstallRefusal(runningInspection(), expectation(), record),
      'revision_not_started', label);
  }
  // Exactly the record the start writes is accepted, so the guard is a
  // restriction on the record rather than a blanket refusal.
  assert.equal(revisionInstallRefusal(runningInspection(), expectation(),
    startedEvent({operation_id: OPERATION_ID, attempt_id: ATTEMPT_ID})), undefined);
});

test('an install fails closed on values the whitelist does not know yet', () => {
  assert.equal(revisionInstallRefusal(inspection({status: 'brand_new_status'}), expectation(),
    startedEvent()), 'task_not_running');
  assert.equal(revisionInstallRefusal(inspection({status: 'running', phase: 'brand_new_phase',
    event_cursor: 5}), expectation(), startedEvent()), 'phase_not_storyboard');
  assert.equal(revisionInstallRefusal(inspection({task_id: 'another-task', status: 'running',
    phase: 'storyboard', event_cursor: 5}), expectation(), startedEvent()), 'task_identity_mismatch');
});

test('an install is refused for an operation that was staged but never started', async t => {
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  await writeCandidate(current, binding.operation_id, await candidateIr(current.path));

  // A storyboard generation that has nothing to do with this revision occupies
  // exactly the cursor the install is handed. Step 2 takes no lease and writes
  // only its own directory, so this state is reachable; without the audit-record
  // check the install would adopt this execution as if it had started it.
  await storyboardInFlight(current);
  // The in-flight generation really does sit on the install cursor, so the
  // refusal below cannot be the cursor check in disguise.
  assert.equal((await inspectTask(current.path)).event_cursor, current.eventCursor + 1);
  const before = await snapshot(current.path, binding.operation_id);

  await assert.rejects(installRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /video revision cannot be installed: revision_not_started/);
  assert.deepEqual(await snapshot(current.path, binding.operation_id), before,
    'a refused install must not touch the task');
});

test('an install is refused once the task has moved on from the started revision', async t => {
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  await writeCandidate(current, binding.operation_id, await candidateIr(current.path));
  await startedRevision(t, current, binding);

  const stamp = new Date().toISOString();
  await transitionTaskState(current.path, 'test_approval_consumed', state => ({
    ...state!, status: 'running', phase: 'approval_consumed',
    attempt: {...(state!.attempt as JsonObject), phase: 'approval_consumed'} as TaskState['attempt'],
  }));
  const before = await snapshot(current.path, binding.operation_id);

  // The phase is checked before the cursor, so a state that both moved on and
  // changed the cursor is still reported as the more specific reason.
  await assert.rejects(installRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /video revision cannot be installed: phase_not_storyboard/);
  assert.deepEqual(await snapshot(current.path, binding.operation_id), before);

  await transitionTaskState(current.path, 'test_cancelled', state => ({
    ...state!, status: 'cancelled', phase: 'cancelled', attempt: undefined,
    cancellation: {requested_at: stamp, completed_at: stamp},
  }));
  const cancelled = await snapshot(current.path, binding.operation_id);
  await assert.rejects(installRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /video revision cannot be installed: task_cancelled/);
  assert.deepEqual(await snapshot(current.path, binding.operation_id), cancelled);
});

test('an install is refused when no candidate was ever written', async t => {
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  await startedRevision(t, current, binding);
  const before = await snapshot(current.path);

  await assert.rejects(installRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /ENOENT/);
  assert.deepEqual(await snapshot(current.path), before);
});

test('an install is refused when the candidate does not change the Lesson IR', async t => {
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  await startedRevision(t, current, binding);
  // The generator returning the input unchanged is not a revision, and
  // installing it would raise the revision number without changing anything.
  await writeCandidate(current, binding.operation_id,
    await readFile(resolve(current.path, 'output/lesson.ir.json')));
  const before = await snapshot(current.path, binding.operation_id);

  await assert.rejects(installRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /revise candidate is unchanged/);
  assert.deepEqual(await snapshot(current.path, binding.operation_id), before);
});

test('an install is refused when the candidate is not a valid Lesson IR', async t => {
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  await startedRevision(t, current, binding);

  const broken = JSON.parse((await candidateIr(current.path)).toString('utf8')) as JsonObject;
  (broken.source as JsonObject).bundle_sha256 = '0'.repeat(64);
  await writeCandidate(current, binding.operation_id,
    Buffer.from(`${JSON.stringify(broken, null, 2)}\n`, 'utf8'));
  await assert.rejects(installRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /Lesson IR source does not match request/);

  // Bytes that are not JSON at all never reach a schema check.
  await writeCandidate(current, binding.operation_id, Buffer.from('{ not json', 'utf8'));
  const before = await snapshot(current.path, binding.operation_id);
  await assert.rejects(installRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /bounded JSON is invalid/);
  assert.deepEqual(await snapshot(current.path, binding.operation_id), before);
});

test('an install is refused when the staged material was tampered with', async t => {
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  await startedRevision(t, current, binding);
  await writeCandidate(current, binding.operation_id, await candidateIr(current.path));
  const before = await snapshot(current.path, binding.operation_id);

  // The binding still pins the original feedback, so only re-hashing the staged
  // file can detect this. Anything else would install a candidate the user never
  // asked for.
  await writeFile(resolve(current.path, 'work/revise', binding.operation_id, 'feedback.txt'),
    'tampered');
  await assert.rejects(installRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /revise staging does not match the operation binding/);
  assert.deepEqual(await snapshot(current.path, binding.operation_id), before);
});

test('an install is refused when a newer Lesson IR has already been installed', async t => {
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  await startedRevision(t, current, binding);
  await writeCandidate(current, binding.operation_id, await candidateIr(current.path));
  const staged = await snapshot(current.path, binding.operation_id);

  // Another attempt installed revision 2 in the meantime: the candidate is now a
  // revision of a Lesson IR that is no longer the formal one.
  const moved = JSON.parse((await readFile(resolve(current.path, 'output/lesson.ir.json')))
    .toString('utf8')) as JsonObject;
  (moved.scenes as JsonObject[])[0]!.narration = '另一个 attempt 安装的 revision 2';
  await atomicWriteJson(resolve(current.path, 'output/lesson.ir.json'), moved);
  const afterInstall = await snapshot(current.path, binding.operation_id);
  assert.notEqual(afterInstall['output/lesson.ir.json'], staged['output/lesson.ir.json']);

  await assert.rejects(installRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /revise staging is based on a stale Lesson IR/);
  const refused = await snapshot(current.path, binding.operation_id);
  assert.deepEqual(refused, afterInstall);
  for (const key of Object.keys(staged)) {
    if (key !== 'output/lesson.ir.json') assert.equal(refused[key], staged[key], key);
  }
});

test('an install is refused when the caller claims a request digest the task does not have', async t => {
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  await startedRevision(t, current, binding);
  await writeCandidate(current, binding.operation_id, await candidateIr(current.path));
  const before = await snapshot(current.path, binding.operation_id);

  // The binding is valid and the ledger matches, so only the caller's own claim
  // about request.json can be what refuses this.
  await assert.rejects(installRevisionOperation(current.path, current.taskId, '1'.repeat(64),
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding),
  /request digest mismatch/);
  assert.deepEqual(await snapshot(current.path, binding.operation_id), before);
});

test('an install bound to a different action, or with malformed arguments, is refused outright', async t => {
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  await startedRevision(t, current, binding);
  await writeCandidate(current, binding.operation_id, await candidateIr(current.path));
  const before = await snapshot(current.path, binding.operation_id);

  const wrongAction = {...binding, action: 'approve'} as VideoOperationBinding;
  await assert.rejects(installRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, wrongAction),
  /invalid revision install arguments/);

  const bad: Array<[string, string, string, number, number, string, string]> = [
    ['task id', 'not a task id', current.requestSha256, current.revision, current.eventCursor,
      current.reviewSha256, current.lessonIrSha256],
    ['request digest', current.taskId, 'not-a-digest', current.revision, current.eventCursor,
      current.reviewSha256, current.lessonIrSha256],
    ['revision', current.taskId, current.requestSha256, 0, current.eventCursor,
      current.reviewSha256, current.lessonIrSha256],
    ['event cursor', current.taskId, current.requestSha256, current.revision, 0,
      current.reviewSha256, current.lessonIrSha256],
    ['review digest', current.taskId, current.requestSha256, current.revision, current.eventCursor,
      'nope', current.lessonIrSha256],
    ['IR digest', current.taskId, current.requestSha256, current.revision, current.eventCursor,
      current.reviewSha256, 'nope'],
  ];
  for (const [label, taskId, request, revision, cursor, review, ir] of bad) {
    await assert.rejects(installRevisionOperation(current.path, taskId, request, revision, cursor,
      review, ir, binding), /invalid revision install arguments/, label);
  }
  assert.deepEqual(await snapshot(current.path, binding.operation_id), before);
});

test('a fully valid install gets past the candidate to the execution lease', async t => {
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  const candidateBytes = await candidateIr(current.path);
  await writeCandidate(current, binding.operation_id, candidateBytes);
  await startedRevision(t, current, binding);

  try {
    const result = await installRevisionOperation(current.path, current.taskId, current.requestSha256,
      current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding);
    t.diagnostic('lease available: the full install succeeded and is asserted below');
    assert.equal(result.schema_version, 1);
    assert.equal(result.action, 'revise');
    assert.equal(result.task_id, current.taskId);
    assert.equal(result.operation_id, binding.operation_id);
    assert.equal(result.status, 'awaiting_storyboard_review');
    assert.equal(result.phase, 'awaiting_approval');
    assert.equal(result.previous_revision, 1);
    assert.equal(result.revision, 2);
    assert.equal(result.candidate_sha256, sha256Bytes(candidateBytes));
    assert.equal(result.lesson_ir_sha256, sha256Bytes(candidateBytes));
    assert.equal(result.input_event_cursor, current.eventCursor);
    // The commit-to-revision appends one event; the install appends two: the
    // takeover (`revision_installing`) and the ready state.
    assert.equal(result.install_event_cursor, current.eventCursor + 3);

    const state = JSON.parse((await readFile(resolve(current.path, 'work/task-state.json')))
      .toString('utf8')) as JsonObject;
    assert.equal(state.status, 'awaiting_storyboard_review');
    assert.equal(state.phase, 'awaiting_approval');
    assert.equal(state.revision, 2);
    assert.equal(state.approval, undefined, 'no approval may be restored by an install');
    assert.equal(state.attempt, undefined);
    assert.equal(state.lesson_ir_sha256, sha256Bytes(candidateBytes));
    assert.equal(state.review_sha256, result.review_sha256);

    // The formal Lesson IR is now the candidate, and the review describes it.
    assert.equal(sha256Bytes(await readFile(resolve(current.path, 'output/lesson.ir.json'))),
      sha256Bytes(candidateBytes));
    const review = JSON.parse((await readFile(resolve(current.path, 'output/review.json')))
      .toString('utf8')) as JsonObject;
    assert.equal(review.lesson_ir_revision, 2);
    assert.equal((review.lesson_ir as JsonObject).sha256, sha256Bytes(candidateBytes));

    const events = (await readFile(resolve(current.path, 'work/task-events.ndjson'), 'utf8'))
      .trim().split('\n');
    const last = JSON.parse(events.at(-1)!) as JsonObject;
    const takeover = JSON.parse(events.at(-2)!) as JsonObject;
    assert.equal(takeover.event, 'revision_installing');
    assert.equal((takeover.video_operation_installing as JsonObject).operation_id, binding.operation_id);
    assert.equal(last.event, 'revision_ready');
    assert.equal(last.status, 'awaiting_storyboard_review');
    assert.equal(last.sequence, result.install_event_cursor);
    assert.equal(last.revision, 2);
    const installed = last.video_operation_installed as JsonObject;
    assert.equal(installed.schema_version, 1);
    assert.equal(installed.action, 'revise');
    assert.equal(installed.operation_id, binding.operation_id);
    assert.equal(installed.attempt_id, binding.attempt_id);
    assert.equal(installed.previous_revision, 1);
    assert.equal(installed.revision, 2);
    assert.equal(installed.candidate_sha256, sha256Bytes(candidateBytes));
    assert.equal(installed.input_event_cursor, current.eventCursor);

    // The revision that was replaced survives as staged history.
    assert.equal(sha256Bytes(await readFile(
      resolve(current.path, 'work/revise', binding.operation_id, 'input-lesson-ir.json'))),
    current.lessonIrSha256);
    assert.equal(sha256Bytes(await readFile(
      resolve(current.path, 'work/revise', binding.operation_id, 'input-review.json'))),
    current.reviewSha256);
  } catch (error: any) {
    // On darwin this harness blocks /bin/ps, so no strong worker process
    // identity can be established and the lease cannot be taken (task-state.ts:521).
    // That is the documented platform limit, not a domain rejection: the install
    // reached the lease, which the refusal tests above prove is not the default.
    t.diagnostic(`lease unavailable here: ${error.message}`);
    assert.match(error.message, LEASE_IDENTITY_ERROR);
  }
});

test('an install is refused when the attempt is not the storyboard execution', async t => {
  // The bounded inspection deliberately hides the attempt, so this guard can
  // only be evaluated inside the state lock: the state looks installable, and
  // only the attempt reveals that another phase owns it.
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  await writeCandidate(current, binding.operation_id, await candidateIr(current.path));
  await transitionTaskState(current.path, 'revision_started', state => ({
    ...state!, status: 'running', phase: 'storyboard', approval: undefined,
    attempt: {...revisionAttempt(randomUUID()), phase: 'audio_ready'} as TaskState['attempt'],
    error: undefined, cancellation: undefined,
  }), revisionStartedDetails(binding, current.eventCursor, current.revision,
    current.reviewSha256, current.lessonIrSha256, binding.feedback_sha256!));
  const before = await snapshot(current.path, binding.operation_id);

  let failure: any;
  try {
    await installRevisionOperation(current.path, current.taskId, current.requestSha256,
      current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding);
  } catch (error: any) {
    failure = error;
  }
  if (failure && LEASE_IDENTITY_ERROR.test(failure.message)) {
    t.diagnostic(`lease unavailable here: ${failure.message}`);
  } else {
    assert.match(failure?.message ?? 'the install unexpectedly succeeded', /attempt_missing/);
  }
  assert.deepEqual(await snapshot(current.path, binding.operation_id), before,
    'a refused install must not touch the task');
});

test('a failed install leaves the task failed and never restores the approval', async t => {
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  const candidateBytes = await candidateIr(current.path);
  await writeCandidate(current, binding.operation_id, candidateBytes);
  await startedRevision(t, current, binding);

  // Interrupt the publish between the IR swap and the review rebuild: the
  // storyboard target can no longer be replaced by a file. Nothing before this
  // point reads it, so the install reaches the swap and then fails.
  await rm(resolve(current.path, 'output/storyboard.html'));
  await mkdir(resolve(current.path, 'output/storyboard.html'));

  let failure: any;
  try {
    await installRevisionOperation(current.path, current.taskId, current.requestSha256,
      current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding);
  } catch (error: any) {
    failure = error;
  }
  if (failure && LEASE_IDENTITY_ERROR.test(failure.message)) {
    t.diagnostic(`lease unavailable here: ${failure.message}`);
    return;
  }
  assert.ok(failure, 'a blocked storyboard rewrite must fail the install');

  const state = JSON.parse((await readFile(resolve(current.path, 'work/task-state.json')))
    .toString('utf8')) as JsonObject;
  assert.equal(state.status, 'failed');
  assert.equal(state.phase, 'storyboard');
  assert.equal(state.attempt, undefined);
  assert.equal(state.approval, undefined, 'a failed install must not restore the previous approval');
  assert.equal((state.error as JsonObject).external_effect_possible, false);

  const events = (await readFile(resolve(current.path, 'work/task-events.ndjson'), 'utf8'))
    .trim().split('\n');
  const last = JSON.parse(events.at(-1)!) as JsonObject;
  assert.equal(last.event, 'revision_failed');
  assert.equal(last.status, 'failed');

  // Worth stating plainly rather than leaving implicit: the IR swap already
  // happened, so the formal Lesson IR is the candidate while the review still
  // describes revision 1. The pair is inconsistent, which `inspectReview`
  // refuses; no rollback is attempted, and the replaced material survives under
  // work/revise/ for an operator to inspect.
  assert.equal(sha256Bytes(await readFile(resolve(current.path, 'output/lesson.ir.json'))),
    sha256Bytes(candidateBytes));
  const review = JSON.parse((await readFile(resolve(current.path, 'output/review.json')))
    .toString('utf8')) as JsonObject;
  assert.equal(review.lesson_ir_revision, 1);
  assert.equal(sha256Bytes(await readFile(
    resolve(current.path, 'work/revise', binding.operation_id, 'input-lesson-ir.json'))),
  current.lessonIrSha256);
  assert.equal(sha256Bytes(await readFile(
    resolve(current.path, 'work/revise', binding.operation_id, 'candidate-lesson-ir.json'))),
  sha256Bytes(candidateBytes));
});

/**
 * An unconsumed approval that satisfies every binding rule for this task.
 *
 * `running` is the one status whose approval is unconstrained by
 * `validateTaskState`, so this state is representable even though the start
 * always clears the approval. It exists to measure the install's own clearing
 * rather than the start's.
 */
function carriedApproval(current: Task, approvalId = randomUUID()): JsonObject {
  return {
    approval_id: approvalId, revision: current.revision,
    lesson_ir_sha256: current.lessonIrSha256, review_sha256: current.reviewSha256,
    lesson_ir_snapshot_path: `work/approved-input/${approvalId}/lesson.ir.json`,
    review_snapshot_path: `work/approved-input/${approvalId}/review.json`,
    granted_at: '2026-09-27T00:00:00.000Z',
  };
}

test('an install clears a carried approval instead of letting it survive', async t => {
  // The plan requires the previous approval to stop being executable. Step 3
  // clears it; this test removes step 3 from the picture and measures the
  // install's own clearing, so the guarantee does not rest on one call site.
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  await writeCandidate(current, binding.operation_id, await candidateIr(current.path));
  await transitionTaskState(current.path, 'revision_started', state => ({
    ...state!, status: 'running', phase: 'storyboard',
    approval: carriedApproval(current) as TaskState['approval'],
    attempt: revisionAttempt(randomUUID()) as TaskState['attempt'],
    error: undefined, cancellation: undefined,
  }), revisionStartedDetails(binding, current.eventCursor, current.revision,
    current.reviewSha256, current.lessonIrSha256, binding.feedback_sha256!));

  let failure: any;
  try {
    await installRevisionOperation(current.path, current.taskId, current.requestSha256,
      current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding);
  } catch (error: any) {
    failure = error;
  }
  if (failure && LEASE_IDENTITY_ERROR.test(failure.message)) {
    t.diagnostic(`lease unavailable here: ${failure.message}`);
    return;
  }
  assert.equal(failure, undefined, 'a carried approval must not stop a valid install');

  const state = JSON.parse((await readFile(resolve(current.path, 'work/task-state.json')))
    .toString('utf8')) as JsonObject;
  assert.equal(state.status, 'awaiting_storyboard_review');
  assert.equal(state.revision, 2);
  assert.equal(state.approval, undefined, 'no approval may remain executable after an install');
  assert.equal(state.attempt, undefined);
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
  const binding = await stagedRevision(t, current);
  const sourceSha = binding.source_snapshot_digest;
  const ordered = [
    '--task-id', current.taskId, '--request-sha256', current.requestSha256,
    '--revision', String(current.revision), '--event-cursor', String(current.eventCursor),
    '--review-sha256', current.reviewSha256, '--lesson-ir-sha256', current.lessonIrSha256,
    '--feedback-sha256', binding.feedback_sha256!, '--operation-id', binding.operation_id,
    '--attempt-id', binding.attempt_id, '--payload-digest', binding.payload_digest,
    '--source-snapshot-sha256', sourceSha,
  ];
  const invoke = (options: string[]) => spawnSync(process.execPath,
    ['--import', 'tsx', worker, 'revision-install-operation', current.path, ...options, '--internal-worker'],
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

  // An install must carry the feedback digest too, so the ten-option list with
  // the feedback pair removed is refused.
  const shortlist = ordered.filter((_, index) => index < 12 || index >= 14);
  assert.equal(shortlist.length, 20);
  assert.notEqual(invoke(shortlist).status, 0);
  assert.deepEqual(await snapshot(current.path), before,
    'a rejected invocation must not touch the task');

  // A well-formed invocation reaches the domain checks and is refused there, on
  // every platform: this task is still awaiting a storyboard review, so no
  // revision may be installed into it.
  const refused = invoke(ordered);
  assert.notEqual(refused.status, 0);
  assert.equal(refused.stderr.trim(), 'video operation action failed');
  assert.deepEqual(await snapshot(current.path), before);
});

test('two operations staged from one reading cannot both be installed', async t => {
  // Step 2 takes no lease and writes only its own directory, so both staging
  // calls succeed against the same reading -- that is the reachable form of the
  // hole the audit-record check closes. Only the operation that actually started
  // may install; the other must be refused even though its ledger, its staged
  // digests and its cursor all match the persisted task.
  const current = await reviewedTask(t);
  const first = await stagedRevision(t, current);
  const second = await stagedRevision(t, current);
  assert.notEqual(first.operation_id, second.operation_id);
  assert.equal(second.input_event_cursor, current.eventCursor);

  await writeCandidate(current, first.operation_id, await candidateIr(current.path));
  await writeCandidate(current, second.operation_id,
    Buffer.from(`${JSON.stringify({schema_version: '1.0', task_id: current.taskId})}\n`, 'utf8'));
  const started = await startedRevision(t, current, first);
  const before = await snapshot(current.path, second.operation_id);

  await assert.rejects(installRevisionOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, second),
  /video revision cannot be installed: revision_not_started/);
  assert.deepEqual(await snapshot(current.path, second.operation_id), before);
});

/** Byte digests of every file under output/, so "untouched" means untouched. */
async function outputFiles(path: string): Promise<Record<string, string>> {
  const {readdir} = await import('node:fs/promises');
  const files: Record<string, string> = {};
  for (const name of (await readdir(resolve(path, 'output'))).sort()) {
    files[name] = sha256Bytes(await readFile(resolve(path, 'output', name)));
  }
  return files;
}

test('a candidate that fails the layout check never reaches output/', async t => {
  // The layout check runs inside buildStoryboard. It used to run after the IR
  // swap, so a too-long title left output/ half new and half old.
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  const ir = JSON.parse((await readFile(resolve(current.path, 'output/lesson.ir.json')))
    .toString('utf8')) as JsonObject;
  const title = (ir.scenes as JsonObject[])[0]!.visual as JsonObject;
  (title.data as JsonObject).title = '收敛'.repeat(45);
  await writeCandidate(current, binding.operation_id,
    Buffer.from(`${JSON.stringify(ir, null, 2)}\n`, 'utf8'));
  await startedRevision(t, current, binding);
  const before = await outputFiles(current.path);

  let failure: any;
  try {
    await installRevisionOperation(current.path, current.taskId, current.requestSha256,
      current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256, binding);
  } catch (error: any) {
    failure = error;
  }
  if (failure && LEASE_IDENTITY_ERROR.test(failure.message)) {
    t.diagnostic(`lease unavailable here: ${failure.message}`);
    return;
  }
  assert.match(failure?.message ?? 'the install unexpectedly succeeded', /storyboard layout failed/);
  assert.deepEqual(await outputFiles(current.path), before,
    'a rejected candidate must leave every published file byte-identical');
  const state = JSON.parse((await readFile(resolve(current.path, 'work/task-state.json')))
    .toString('utf8')) as JsonObject;
  assert.equal(state.status, 'failed');
  assert.equal(state.approval, undefined);
  assert.equal(state.lesson_ir_sha256, current.lessonIrSha256);
});

test('an install takes the attempt over, so a crash mid-install is not a park', async t => {
  // After the takeover the tail is `revision_installing` and the attempt names
  // the installer's lease. If that process dies, recovery must see an ordinary
  // dead worker, not a revision still parked by step 3.
  const current = await reviewedTask(t);
  const binding = await stagedRevision(t, current);
  await writeCandidate(current, binding.operation_id, await candidateIr(current.path));
  await startedRevision(t, current, binding);
  await transitionTaskState(current.path, 'revision_installing', state => ({
    ...state!, attempt: revisionAttempt(randomUUID()) as TaskState['attempt'],
  }), {revision: current.revision});

  const read = spawnSync(process.execPath, ['--import', 'tsx', 'scripts/task-worker.ts',
    current.path, 'status'], {cwd: root, encoding: 'utf8'});

  assert.equal(read.status, 0, read.stderr);
  const state = JSON.parse((await readFile(resolve(current.path, 'work/task-state.json')))
    .toString('utf8')) as JsonObject;
  assert.equal(state.status, 'interrupted');
  const events = (await readFile(resolve(current.path, 'work/task-events.ndjson'), 'utf8'))
    .trim().split('\n');
  assert.equal((JSON.parse(events.at(-1)!) as JsonObject).event, 'execution_interrupted');
});
