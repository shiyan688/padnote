import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {cp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import test, {type TestContext} from 'node:test';
import {atomicWriteJson, fileDescriptor, sha256Bytes, skillRoot, type JsonObject} from '../scripts/lib.js';
import {stageReviseOperation} from '../scripts/revise-operation.js';
import {installRevisionOperation} from '../scripts/revision-install.js';
import {revisionAttempt, revisionParkIsIntact, revisionStartedDetails,
  startRevisionOperation} from '../scripts/revision-start.js';
import {transitionTaskState, type TaskState} from '../scripts/task-state.js';
import {payloadDigest, sourceSnapshotDigest, type VideoOperationBinding} from '../scripts/video-operation.js';

const root = skillRoot();
const fixture = resolve(root, 'tests/fixtures/formula-note');
const output = resolve(root, '.local-output/revision-park-tests');
const FEEDBACK = '把开场改得更快进入主题';
/** The one edit the generator makes: a candidate that differs from revision 1. */
const CANDIDATE_NARRATION = '开场就直接说清楚：L1 收敛衡量的是整体误差面积，而不是每个点。';

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

/**
 * The real CLI, in a real subprocess.
 *
 * The defect this file guards against is only observable through a command that
 * runs recovery, so the tests drive the actual entry point rather than
 * `ensureTaskState` directly -- a helper call could pass while the shipped
 * command still destroyed the task.
 */
function cli(taskPath: string, action: string): {code: number; out: string; err: string} {
  const run = spawnSync(process.execPath,
    ['--import', 'tsx', 'scripts/task-worker.ts', taskPath, action],
    {cwd: root, encoding: 'utf8'});
  return {code: run.status ?? -1, out: (run.stdout ?? '').trim(), err: (run.stderr ?? '').trim()};
}

async function stateOf(path: string): Promise<JsonObject> {
  return JSON.parse(await readFile(resolve(path, 'work/task-state.json'), 'utf8')) as JsonObject;
}

async function events(path: string): Promise<JsonObject[]> {
  const text = await readFile(resolve(path, 'work/task-events.ndjson'), 'utf8');
  return text.trim().split('\n').map(line => JSON.parse(line) as JsonObject);
}

async function summaryOf(path: string): Promise<string> {
  const state = await stateOf(path);
  return `${state.status}/${state.phase}`;
}

async function lastEventName(path: string): Promise<string | undefined> {
  return (await events(path)).at(-1)?.event as string | undefined;
}

/** A task awaiting approval for revision 1, exactly as the review flow leaves it. */
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
  const {ensureTaskState} = await import('../scripts/task-worker.js');
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

async function staged(t: TestContext, current: Task): Promise<VideoOperationBinding> {
  const feedbackSha256 = sha256Bytes(Buffer.from(FEEDBACK, 'utf8'));
  const parameters = {revision: current.revision, event_cursor: current.eventCursor,
    review_sha256: current.reviewSha256, lesson_ir_sha256: current.lessonIrSha256,
    feedback_sha256: feedbackSha256};
  const binding: VideoOperationBinding = {
    operation_id: randomUUID(), attempt_id: randomUUID(), action: 'revise',
    payload_digest: payloadDigest('revise', parameters),
    source_snapshot_digest: await sourceSnapshotDigest(current.path),
    request_sha256: current.requestSha256, input_event_cursor: current.eventCursor,
    revision: current.revision, review_sha256: current.reviewSha256,
    lesson_ir_sha256: current.lessonIrSha256, feedback_sha256: feedbackSha256,
  };
  await stageReviseOperation(current.path, current.taskId, current.requestSha256,
    current.revision, current.eventCursor, current.reviewSha256, current.lessonIrSha256,
    binding, FEEDBACK);
  await writeCandidate(current, binding.operation_id);
  return binding;
}

/**
 * The candidate the generator wrote between step 2 and step 4.
 *
 * Staging itself is read-only and never produces this file, so a test that only
 * stages has not reached the state step 4 installs from.
 */
async function writeCandidate(current: Task, operationId: string): Promise<void> {
  const ir = JSON.parse((await readFile(resolve(current.path, 'output/lesson.ir.json')))
    .toString('utf8')) as JsonObject;
  (ir.scenes as JsonObject[])[0]!.narration = CANDIDATE_NARRATION;
  await writeFile(resolve(current.path, 'work/revise', operationId, 'candidate-lesson-ir.json'),
    Buffer.from(`${JSON.stringify(ir, null, 2)}\n`, 'utf8'));
}

/**
 * Part 3's park: `running`/`storyboard`, an attempt, and no live lease.
 *
 * On a platform with a working lease this is the real `startRevisionOperation`.
 * This harness cannot take one, so the fallback writes the same fields through
 * the start's own builders. Either way the observable state matches what the
 * probe recorded for the real park -- `releaseExecutionLease` deletes the lease
 * directory (task-state.ts:443), so a real start also ends with no lease.
 */
async function park(t: TestContext, current: Task,
                    binding: VideoOperationBinding): Promise<void> {
  const started = await startRevisionOperation(current.path, current.taskId,
    current.requestSha256, current.revision, current.eventCursor,
    current.reviewSha256, current.lessonIrSha256, binding).catch(
    async (error: any) => {
      assert.match(error.message, LEASE_IDENTITY_ERROR,
        'a valid start may only fail here on the platform lease limit');
      await transitionTaskState(current.path, 'revision_started', state => ({
        ...state!, status: 'running', phase: 'storyboard', approval: undefined,
        attempt: revisionAttempt(randomUUID()) as TaskState['attempt'],
        error: undefined, cancellation: undefined,
      }), revisionStartedDetails(binding, current.eventCursor, current.revision,
        current.reviewSha256, current.lessonIrSha256, binding.feedback_sha256!));
      t.diagnostic('lease unavailable here: the park is reproduced from the start builders');
      return undefined;
    });
  if (started) assert.equal(started.status, 'running');
}

/** A `running` worker that is not a park: the storyboard flow's own attempt. */
async function crashedStoryboardWorker(current: Task): Promise<void> {
  await transitionTaskState(current.path, 'storyboard_started', state => ({
    ...state!, status: 'running', phase: 'storyboard', approval: undefined,
    attempt: revisionAttempt(randomUUID()) as TaskState['attempt'],
    error: undefined, cancellation: undefined,
  }));
}

test('a parked revision survives an ordinary status read', async t => {
  const current = await reviewedTask(t);
  const binding = await staged(t, current);
  await park(t, current, binding);
  assert.equal(await summaryOf(current.path), 'running/storyboard');
  assert.equal(await lastEventName(current.path), 'revision_started');

  const read = cli(current.path, 'status');

  assert.equal(read.code, 0);
  assert.equal(await summaryOf(current.path), 'running/storyboard',
    'the park must outlive a read that only reports status');
  assert.equal(await lastEventName(current.path), 'revision_started',
    'recovery must not append execution_interrupted to a parked revision');
  assert.deepEqual(JSON.parse(read.out), {
    task_id: current.taskId, status: 'running', phase: 'storyboard',
    event_cursor: current.eventCursor + 1,
  });
});

test('a parked revision survives an init read', async t => {
  // `init` reaches recovery through the same door as `status`, so a fix that
  // only special-cased `status` would still lose the park here.
  const current = await reviewedTask(t);
  await park(t, current, await staged(t, current));

  const read = cli(current.path, 'init');

  assert.equal(read.code, 0);
  assert.equal(await summaryOf(current.path), 'running/storyboard');
});

test('the install still reaches the lease after a parked revision was read', async t => {
  // The payoff, and the closest thing to a regression test for the defect: the
  // install's preflight runs before the lease, so "did the read cost us the
  // park?" is answerable on a host that cannot take a lease.
  const current = await reviewedTask(t);
  const binding = await staged(t, current);
  await park(t, current, binding);
  cli(current.path, 'status');

  let failure: any;
  try {
    await installRevisionOperation(current.path, current.taskId, current.requestSha256,
      current.revision, current.eventCursor, current.reviewSha256,
      current.lessonIrSha256, binding);
  } catch (error: any) {
    failure = error;
  }

  if (failure === undefined) {
    // A host with a working lease completes the install outright, which is the
    // strongest answer: the read did not cost us the park.
    t.diagnostic('lease available: the install completed after the status read');
    assert.equal(await summaryOf(current.path), 'awaiting_storyboard_review/awaiting_approval');
    return;
  }
  assert.doesNotMatch(failure.message, /task_not_running/,
    'the park must not be reported as a task that is not running');
  assert.match(failure.message, LEASE_IDENTITY_ERROR,
    'the install must get all the way to the execution lease');
});

test('a crashed storyboard worker is still interrupted', async t => {
  // The control that bounds the fix: the exemption keys on the audit record, so
  // the flow the heuristic was written for must behave exactly as before.
  const current = await reviewedTask(t);
  await crashedStoryboardWorker(current);
  const before = (await stateOf(current.path)).event_cursor;

  const read = cli(current.path, 'status');

  assert.equal(read.code, 0);
  assert.equal(await summaryOf(current.path), 'interrupted/storyboard');
  assert.equal(await lastEventName(current.path), 'execution_interrupted');
  assert.equal((await stateOf(current.path)).event_cursor, Number(before) + 1);
});

test('a stale revision_started record does not excuse a later state', async t => {
  // Stage 3's record is at an older cursor here, so the worker that took over is
  // the one that must be judged -- and it is gone.
  const current = await reviewedTask(t);
  await park(t, current, await staged(t, current));
  const parkedEvent = await lastEventName(current.path);
  assert.equal(parkedEvent, 'revision_started');
  await transitionTaskState(current.path, 'storyboard_started', state => ({
    ...state!, status: 'running', phase: 'storyboard',
    attempt: revisionAttempt(randomUUID()) as TaskState['attempt'],
  }));

  const read = cli(current.path, 'status');

  assert.equal(read.code, 0);
  assert.equal(await summaryOf(current.path), 'interrupted/storyboard');
});

test('a cancelled parked revision is cancelled rather than preserved', async t => {
  // What this pins is the recovery *branch*, not the predicate's own
  // cancellation conjunct: the premise (`running` while carrying a cancellation)
  // is synthetic, because the CLI's cancellation request always appends its own
  // event (`cancelTask` -> `cancellation_requested`), which the predicate
  // rejects on the event name before it ever looks at `cancellation`. That
  // conjunct is therefore defence in depth and is pinned by the table test
  // below; see the checkpoint for why it is kept rather than trimmed.
  const current = await reviewedTask(t);
  await park(t, current, await staged(t, current));
  await transitionTaskState(current.path, 'cancellation_requested', state => ({
    ...state!, cancellation: {requested_at: '2026-09-27T00:00:00.000Z'},
  }));

  const read = cli(current.path, 'status');

  assert.equal(read.code, 0);
  assert.equal(await summaryOf(current.path), 'cancelled/cancelled',
    'a park carrying a cancellation is work to carry out, not a park to keep');
  assert.equal(await lastEventName(current.path), 'cancel_recovered_after_restart');
});

test('a cancel on a parked revision is a cancellation, not an interruption', async t => {
  // Before the exemption, the cancel path first rewrote the park as
  // `execution_interrupted` and only then cancelled from there, so the record
  // read as crash recovery. It must now read as what the operator asked for.
  const current = await reviewedTask(t);
  await park(t, current, await staged(t, current));

  const cancel = cli(current.path, 'cancel');

  assert.equal(cancel.code, 0);
  assert.equal(await summaryOf(current.path), 'cancelled/cancelled');
  const names = (await events(current.path)).map(event => event.event);
  assert.ok(!names.includes('execution_interrupted'),
    `a cancellation must not be recorded as an interruption: ${names.join(', ')}`);
});

test('an unreadable audit log is not treated as a park', async t => {
  // Recovery decides about a possibly-damaged task, so failing to read the log
  // must not become a reason to leave it running.
  const current = await reviewedTask(t);
  await park(t, current, await staged(t, current));
  await writeFile(resolve(current.path, 'work/task-events.ndjson'), '');

  const read = cli(current.path, 'status');

  assert.equal(read.code, 0);
  assert.equal(await summaryOf(current.path), 'interrupted/storyboard');
});

test('the park predicate rejects every state that is not exactly this park', () => {
  const parked = {task_id: 'task', status: 'running', phase: 'storyboard',
    event_cursor: 7, revision: 3} as unknown as TaskState;
  const record = {sequence: 7, event: 'revision_started', task_id: 'task', revision: 3,
    video_operation_started: {action: 'revise'}} as JsonObject;
  assert.equal(revisionParkIsIntact(parked, record), true, 'the park itself is recognised');

  const wrong: Array<[string, TaskState, JsonObject | undefined]> = [
    ['no record at all', parked, undefined],
    ['a different event', parked, {...record, event: 'storyboard_started'}],
    ['an older cursor', parked, {...record, sequence: 6}],
    ['a newer cursor', parked, {...record, sequence: 8}],
    ['another task', parked, {...record, task_id: 'other'}],
    ['another revision', parked, {...record, revision: 4}],
    ['the storyboard action', parked,
      {...record, video_operation_started: {action: 'storyboard'}}],
    ['no operation record', parked, {...record, video_operation_started: undefined}],
    ['a record without a revision', parked, {...record, revision: undefined}],
    ['not running', {...parked, status: 'cancelling'} as unknown as TaskState, record],
    ['a different phase', {...parked, phase: 'audio_ready'} as unknown as TaskState, record],
    ['a recorded cancellation',
      {...parked, cancellation: {requested_at: 'x'}} as unknown as TaskState, record],
  ];
  for (const [label, state, event] of wrong) {
    assert.equal(revisionParkIsIntact(state, event), false, `${label} must not be a park`);
  }
});
