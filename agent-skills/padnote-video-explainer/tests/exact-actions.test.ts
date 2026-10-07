import assert from 'node:assert/strict';
import {spawn, spawnSync} from 'node:child_process';
import {cp, mkdir, readFile, rm, symlink, writeFile} from 'node:fs/promises';
import {createHash, randomUUID} from 'node:crypto';
import {realpathSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import test, {type TestContext} from 'node:test';
import {
  buildExactStoryboard,
  grantBoundApproval,
  initializeExactTask,
} from '../scripts/task-worker.js';
import {atomicWriteFile, sha256Bytes, skillRoot} from '../scripts/lib.js';
import {eventPath, loadTaskState, statePath, transitionTaskState} from '../scripts/task-state.js';
import {payloadDigest, sourceSnapshotDigest, type VideoOperationBinding} from '../scripts/video-operation.js';

const rootSkill = skillRoot();
const fixture = resolve(rootSkill, 'tests/fixtures/formula-note');
const worker = resolve(rootSkill, 'scripts/task-worker.ts');
const outputRoot = resolve(rootSkill, '.local-output/exact-action-tests');

type ExactTask = {root: string; taskId: string; requestSha256: string};

async function makeTask(t: TestContext): Promise<ExactTask> {
  await mkdir(outputRoot, {recursive: true});
  const root = resolve(outputRoot, randomUUID());
  await mkdir(root, {recursive: true});
  await cp(resolve(fixture, 'request.json'), resolve(root, 'request.json'));
  await cp(resolve(fixture, 'input'), resolve(root, 'input'), {recursive: true});
  await mkdir(resolve(root, 'work'));
  await mkdir(resolve(root, 'output'));
  const requestBytes = await readFile(resolve(root, 'request.json'));
  const request = JSON.parse(requestBytes.toString('utf8')) as {task_id: string};
  await cp(resolve(fixture, 'expected/lesson.ir.json'), resolve(root, 'output/lesson.ir.json'));
  const task = {root, taskId: request.task_id, requestSha256: sha256Bytes(requestBytes)};
  await initializeExactTask(root, task.taskId, task.requestSha256);
  t.after(async () => { await rm(root, {recursive: true, force: true}); });
  return task;
}

async function fileBytes(path: string): Promise<Buffer | undefined> {
  try { return await readFile(path); } catch (error: any) {
    if (error?.code === 'ENOENT') return undefined;
    throw error;
  }
}

test('exact storyboard and approval advance only their bound cursors and revisions', async t => {
  const task = await makeTask(t);
  const first = await buildExactStoryboard(task.root, task.taskId, task.requestSha256, 1, 1);
  assert.equal(first.status, 'awaiting_storyboard_review');
  assert.equal(first.phase, 'awaiting_approval');
  assert.equal(first.revision, 1);
  assert.equal(first.event_cursor, 3, 'storyboard records start and ready events');
  const firstIrSha = first.lesson_ir_sha256;
  const firstReviewSha = first.review_sha256;
  assert.ok(firstIrSha && firstReviewSha);

  const irPath = resolve(task.root, 'output/lesson.ir.json');
  const ir = JSON.parse(await readFile(irPath, 'utf8')) as {episode: {title: string}};
  ir.episode.title += ' · 第二版';
  await writeFile(irPath, `${JSON.stringify(ir, null, 2)}\n`);
  const second = await buildExactStoryboard(
    task.root, task.taskId, task.requestSha256, 2, first.event_cursor);
  assert.equal(second.status, 'awaiting_storyboard_review');
  assert.equal(second.revision, 2);
  assert.equal(second.event_cursor, 5);
  assert.notEqual(second.lesson_ir_sha256, firstIrSha);
  assert.notEqual(second.review_sha256, firstReviewSha);

  const stateBeforeStale = await fileBytes(statePath(task.root));
  const eventsBeforeStale = await fileBytes(eventPath(task.root));
  await assert.rejects(grantBoundApproval(
    task.root, task.taskId, task.requestSha256, 2, first.event_cursor,
    second.review_sha256!, second.lesson_ir_sha256!), /preflight failed|stale/);
  assert.deepEqual(await fileBytes(statePath(task.root)), stateBeforeStale);
  assert.deepEqual(await fileBytes(eventPath(task.root)), eventsBeforeStale);

  const approved = await grantBoundApproval(
    task.root, task.taskId, task.requestSha256, 2, second.event_cursor,
    second.review_sha256!, second.lesson_ir_sha256!);
  assert.equal(approved.status, 'approved');
  assert.equal(approved.phase, 'approval_pending');
  assert.equal(approved.event_cursor, second.event_cursor + 1);
  assert.equal(approved.approval?.review_sha256, second.review_sha256);
  assert.equal(approved.approval?.lesson_ir_sha256, second.lesson_ir_sha256);
});

test('invalid input or audit tail cannot start exact storyboard or change persisted bytes', async t => {
  const task = await makeTask(t);
  const statePathValue = statePath(task.root);
  const eventPathValue = eventPath(task.root);
  const stateBefore = await fileBytes(statePathValue);
  const eventsBefore = await fileBytes(eventPathValue);
  await writeFile(resolve(task.root, 'input/content.md'), 'changed input');
  await assert.rejects(buildExactStoryboard(task.root, task.taskId, task.requestSha256, 1, 1),
    /preflight failed/);
  assert.deepEqual(await fileBytes(statePathValue), stateBefore);
  assert.deepEqual(await fileBytes(eventPathValue), eventsBefore);

  // Restore the input, then corrupt only the latest audit projection.
  await cp(resolve(fixture, 'input/content.md'), resolve(task.root, 'input/content.md'));
  const event = JSON.parse((eventsBefore!.toString('utf8').trim().split('\n')[0])!);
  event.status = 'failed';
  await writeFile(eventPathValue, `${JSON.stringify(event)}\n`);
  const corruptedEvents = await fileBytes(eventPathValue);
  await assert.rejects(buildExactStoryboard(task.root, task.taskId, task.requestSha256, 1, 1),
    /preflight failed/);
  assert.deepEqual(await fileBytes(statePathValue), stateBefore);
  assert.deepEqual(await fileBytes(eventPathValue), corruptedEvents);
});

test('bound approval refuses a symlinked snapshot parent without writing outside task root', async t => {
  const task = await makeTask(t);
  const review = await buildExactStoryboard(task.root, task.taskId, task.requestSha256, 1, 1);
  const outside = resolve(outputRoot, `outside-${randomUUID()}`);
  await mkdir(outside);
  t.after(async () => { await rm(outside, {recursive: true, force: true}); });
  const sentinel = resolve(outside, 'sentinel.txt');
  await writeFile(sentinel, 'untouched');
  const approvedInput = resolve(task.root, 'work/approved-input');
  await symlink(outside, approvedInput, 'dir');
  const stateBefore = await fileBytes(statePath(task.root));
  const eventsBefore = await fileBytes(eventPath(task.root));
  await assert.rejects(grantBoundApproval(
    task.root, task.taskId, task.requestSha256, 1, review.event_cursor,
    review.review_sha256!, review.lesson_ir_sha256!), /real directory/);
  assert.equal(await readFile(sentinel, 'utf8'), 'untouched');
  assert.deepEqual(await fileBytes(statePath(task.root)), stateBefore);
  assert.deepEqual(await fileBytes(eventPath(task.root)), eventsBefore);
});

test('an IR replacement during storyboard generation cannot be marked ready', async t => {
  const task = await makeTask(t);
  const building = buildExactStoryboard(task.root, task.taskId, task.requestSha256, 1, 1);
  await waitForState(task.root, state => state.status === 'running' && state.phase === 'storyboard');
  const irPath = resolve(task.root, 'output/lesson.ir.json');
  const ir = JSON.parse(await readFile(irPath, 'utf8')) as {episode: {title: string}};
  ir.episode.title += ' changed during generation';
  await writeFile(irPath, `${JSON.stringify(ir, null, 2)}\n`);
  await assert.rejects(building, /changed during generation|exact storyboard/);
  const final = await loadTaskState(task.root);
  assert.equal(final?.status, 'failed');
  assert.equal(final?.error?.message, 'Exact storyboard generation failed');
});

test('ready and failure commits cannot replace a newer storyboard attempt', async t => {
  const task = await makeTask(t);
  const building = buildExactStoryboard(task.root, task.taskId, task.requestSha256, 1, 1);
  await waitForState(task.root, state => state.status === 'running' && state.phase === 'storyboard');
  const replacementAttemptId = randomUUID();
  await transitionTaskState(task.root, 'test_replace_storyboard_attempt', current => ({
    ...current!,
    attempt: {
      ...current!.attempt!,
      attempt_id: replacementAttemptId,
      lease_token: randomUUID(),
      started_at: new Date().toISOString(),
    },
  }));
  const replacement = await loadTaskState(task.root);
  const eventsAfterReplacement = await fileBytes(eventPath(task.root));
  await assert.rejects(building, /attempt is no longer current/);
  const final = await loadTaskState(task.root);
  assert.equal(final?.status, 'running');
  assert.equal(final?.attempt?.attempt_id, replacementAttemptId);
  assert.equal(final?.event_cursor, replacement?.event_cursor);
  assert.deepEqual(await fileBytes(eventPath(task.root)), eventsAfterReplacement);
});

test('atomic temporary naming ignores a pre-existing legacy .tmp symlink', async t => {
  await mkdir(outputRoot, {recursive: true});
  const directory = resolve(outputRoot, randomUUID());
  await mkdir(directory);
  t.after(async () => { await rm(directory, {recursive: true, force: true}); });
  const sentinel = resolve(directory, 'outside.txt');
  const target = resolve(directory, 'state.json');
  await writeFile(sentinel, 'untouched');
  await symlink(sentinel, `${target}.tmp`);
  await atomicWriteFile(target, '{"safe":true}\n');
  assert.equal(await readFile(sentinel, 'utf8'), 'untouched');
  assert.equal(await readFile(target, 'utf8'), '{"safe":true}\n');
});

test('exact action CLI requires action-first ordered unique arguments and fixed errors', async t => {
  const task = await makeTask(t);
  const env = {
    PATH: `${dirname(process.execPath)}:/usr/bin:/bin`, LANG: 'C', LC_ALL: 'C',
    DISABLE_TELEMETRY: 'true', TMPDIR: outputRoot,
  };
  const args = [
    '--import', 'tsx', worker, 'storyboard-exact', task.root,
    '--task-id', task.taskId, '--request-sha256', task.requestSha256,
    '--revision', '1', '--event-cursor', '1', '--internal-worker',
  ];
  const stateBefore = await fileBytes(statePath(task.root));
  const eventsBefore = await fileBytes(eventPath(task.root));
  const wrongOrder = spawnSync(process.execPath, [
    '--import', 'tsx', worker, task.root, 'storyboard-exact', ...args.slice(5),
  ], {cwd: rootSkill, env, encoding: 'utf8', timeout: 30_000});
  assert.notEqual(wrongOrder.status, 0);
  assert.equal(wrongOrder.stderr.trim(), 'exact video action failed');
  const duplicate = spawnSync(process.execPath, [
    ...args.slice(0, -1), '--internal-worker', '--internal-worker',
  ], {cwd: rootSkill, env, encoding: 'utf8', timeout: 30_000});
  assert.notEqual(duplicate.status, 0);
  assert.equal(duplicate.stderr.trim(), 'exact video action failed');
  assert.deepEqual(await fileBytes(statePath(task.root)), stateBefore);
  assert.deepEqual(await fileBytes(eventPath(task.root)), eventsBefore);
});

test('cancel-operation signals only the exact live storyboard process and reconciles its receipt', async t => {
  if (process.platform === 'win32') return t.skip('isolated process-group cancellation is supported only on Linux and macOS');
  const task = await makeTask(t);
  const sourceSha = await sourceSnapshotDigest(task.root);
  const operationId = randomUUID();
  const attemptId = randomUUID();
  const parameters = {revision: 1, event_cursor: 1};
  const binding: VideoOperationBinding = {
    operation_id: operationId, attempt_id: attemptId, action: 'storyboard',
    payload_digest: payloadDigest('storyboard', parameters), source_snapshot_digest: sourceSha,
    request_sha256: task.requestSha256, input_event_cursor: 1, revision: 1,
  };
  const env: NodeJS.ProcessEnv = {
    PATH: `${dirname(process.execPath)}:/usr/bin:/bin`, LANG: 'C', LC_ALL: 'C',
    DISABLE_TELEMETRY: 'true', TMPDIR: outputRoot,
  };
  const worker = resolve(rootSkill, 'scripts/task-worker.ts');
  const heldWorker = resolve(rootSkill, 'tests/fixtures/held-storyboard-worker.ts');
  const child = spawn(process.execPath, ['--import', 'tsx', heldWorker], {
    cwd: rootSkill, env: {...env, PADNOTE_TEST_TASK_ROOT: task.root,
      PADNOTE_TEST_VIDEO_BINDING: JSON.stringify(binding)}, detached: true, stdio: 'ignore',
  });
  t.after(async () => {
    if (child.exitCode == null && child.signalCode == null && child.pid) {
      try { process.kill(-child.pid, 'SIGKILL'); } catch { /* already exited */ }
      await waitForChildExit(child);
    }
  });
  await waitForState(task.root, state => state.status === 'running' && state.phase === 'storyboard');
  const runningInspection = await runNodeCli(rootSkill, [worker, 'inspect', task.root], env);
  assert.equal(runningInspection.code, 0, runningInspection.stderr);
  const inspected = JSON.parse(runningInspection.stdout) as {status: string; phase: string; event_cursor: number};
  assert.equal(inspected.status, 'running');
  assert.equal(inspected.phase, 'storyboard');
  assert.equal(inspected.event_cursor, 2);
  const leaseOwnerPath = testLeaseOwnerPath(task.root, outputRoot);
  const lease = JSON.parse((await readFile(leaseOwnerPath)).toString('utf8')) as {pid: number};
  assert.equal(lease.pid, child.pid);
  assert.doesNotThrow(() => process.kill(child.pid!, 0));

  const invoke = async (action: string, options: string[]) => runNodeCli(rootSkill,
    [worker, action, task.root, ...options, '--internal-worker'], env);
  const cancelArgs = (op: VideoOperationBinding, taskId = task.taskId, requestSha256 = task.requestSha256) => [
    '--task-id', taskId, '--request-sha256', requestSha256,
    '--event-cursor', '1', '--revision', '1', '--operation-id', op.operation_id,
    '--attempt-id', op.attempt_id, '--action', 'storyboard',
    '--payload-digest', op.payload_digest, '--source-snapshot-sha256', op.source_snapshot_digest,
  ];
  const beforeInvalid = await fileBytes(statePath(task.root));
  const invalidCases: Array<{options: string[]; reason?: string}> = [
    {options: cancelArgs({...binding, attempt_id: randomUUID()}), reason: 'binding_mismatch'},
    {options: cancelArgs({...binding, operation_id: randomUUID()}), reason: 'binding_mismatch'},
    {options: cancelArgs({...binding, source_snapshot_digest: '0'.repeat(64)}), reason: 'source_changed'},
    {options: cancelArgs(binding, task.taskId, '0'.repeat(64))},
    {options: cancelArgs(binding, `wrong-${task.taskId}`)},
  ];
  for (const invalidCase of invalidCases) {
    const invalid = await invoke('cancel-operation', invalidCase.options);
    assert.equal(invalid.code, 0, invalid.stderr);
    const invalidResponse = JSON.parse(invalid.stdout) as {status: string; reason: string};
    assert.equal(invalidResponse.status, 'unconfirmed');
    if (invalidCase.reason) assert.equal(invalidResponse.reason, invalidCase.reason);
    assert.equal(child.exitCode, null, 'a wrong operation binding must not signal the worker');
    assert.equal(child.signalCode, null, 'a wrong operation binding must not signal the worker');
    assert.deepEqual(await fileBytes(statePath(task.root)), beforeInvalid);
  }
  const originalLeaseOwner = await readFile(leaseOwnerPath);
  const tamperedLease = JSON.parse(originalLeaseOwner.toString('utf8')) as {
    process_identity: {start_time: string};
  };
  tamperedLease.process_identity.start_time = 'not-the-running-process';
  await writeFile(leaseOwnerPath, JSON.stringify(tamperedLease));
  const invalidLease = await invoke('cancel-operation', cancelArgs(binding));
  assert.equal(invalidLease.code, 0, invalidLease.stderr);
  assert.equal(JSON.parse(invalidLease.stdout).status, 'unconfirmed');
  assert.equal(child.exitCode, null);
  assert.equal(child.signalCode, null);
  assert.deepEqual(await fileBytes(statePath(task.root)), beforeInvalid);
  await writeFile(leaseOwnerPath, originalLeaseOwner);

  const cancelled = await invoke('cancel-operation', cancelArgs(binding));
  assert.equal(cancelled.code, 0, cancelled.stderr);
  const response = JSON.parse(cancelled.stdout) as {
    object: string; protocol_version: number; operation_id: string; attempt_id: string;
    status: string; reason: string;
  };
  assert.deepEqual(response, {
    object: 'padnote.video.cancel', protocol_version: 1,
    operation_id: operationId, attempt_id: attemptId,
    status: 'verified_cancelled', reason: 'cancel_receipt_match',
  });
  await waitForChildExit(child);
  const finalState = await loadTaskState(task.root);
  assert.equal(finalState?.status, 'cancelled');
  assert.equal(finalState?.event_cursor, 4);
  assert.equal(await fileBytes(leaseOwnerPath), undefined, 'confirmed cancellation removes only its stopped lease');
  const eventNames = (await readFile(eventPath(task.root), 'utf8')).trim().split('\n')
    .map(line => (JSON.parse(line) as {event: string}).event);
  assert.deepEqual(eventNames, [
    'task_initialized_exact', 'storyboard_started_exact',
    'video_operation_cancel_requested', 'video_operation_cancelled',
  ], 'late ready and failure CAS attempts must not append events after cancellation');

  const reconciliation = await invoke('reconcile-operation', [
    '--task-id', task.taskId, '--request-sha256', task.requestSha256,
    '--event-cursor', '1', '--revision', '1', '--operation-id', operationId,
    '--attempt-id', attemptId, '--action', 'storyboard', '--payload-digest', binding.payload_digest,
    '--source-snapshot-sha256', sourceSha,
  ]);
  assert.equal(reconciliation.code, 0, reconciliation.stderr);
  const proof = JSON.parse(reconciliation.stdout) as {outcome: string; reason: string; result: unknown};
  assert.equal(proof.outcome, 'verified_cancelled');
  assert.equal(proof.reason, 'cancel_receipt_match');
  assert.equal(proof.result, null);
});

test('cancel-operation cannot mark an initialized storyboard as cancelled', async t => {
  const task = await makeTask(t);
  const sourceSha = await sourceSnapshotDigest(task.root);
  const operationId = randomUUID();
  const attemptId = randomUUID();
  const binding: VideoOperationBinding = {
    operation_id: operationId, attempt_id: attemptId, action: 'storyboard',
    payload_digest: payloadDigest('storyboard', {revision: 1, event_cursor: 1}),
    source_snapshot_digest: sourceSha, request_sha256: task.requestSha256,
    input_event_cursor: 1, revision: 1,
  };
  const env = {PATH: `${dirname(process.execPath)}:/usr/bin:/bin`, LANG: 'C', LC_ALL: 'C',
    DISABLE_TELEMETRY: 'true', TMPDIR: outputRoot};
  const worker = resolve(rootSkill, 'scripts/task-worker.ts');
  const args = ['--task-id', task.taskId, '--request-sha256', task.requestSha256,
    '--event-cursor', '1', '--revision', '1', '--operation-id', operationId,
    '--attempt-id', attemptId, '--action', 'storyboard', '--payload-digest', binding.payload_digest,
    '--source-snapshot-sha256', sourceSha, '--internal-worker'];
  const initialState = await fileBytes(statePath(task.root));
  const initialEvents = await fileBytes(eventPath(task.root));
  const cancel = await runNodeCli(rootSkill, [worker, 'cancel-operation', task.root, ...args], env);
  assert.equal(cancel.code, 0, cancel.stderr);
  assert.equal(JSON.parse(cancel.stdout).status, 'unconfirmed');
  assert.equal(JSON.parse(cancel.stdout).reason, 'not_running');
  assert.deepEqual(await fileBytes(statePath(task.root)), initialState);
  assert.deepEqual(await fileBytes(eventPath(task.root)), initialEvents);
});

test('cancel-operation confirms exit only after SIGKILL actually removes the process group', async t => {
  if (process.platform === 'win32') return t.skip('isolated process-group cancellation is supported only on Linux and macOS');
  const task = await makeTask(t);
  const sourceSha = await sourceSnapshotDigest(task.root);
  const operationId = randomUUID();
  const attemptId = randomUUID();
  const binding: VideoOperationBinding = {
    operation_id: operationId, attempt_id: attemptId, action: 'storyboard',
    payload_digest: payloadDigest('storyboard', {revision: 1, event_cursor: 1}),
    source_snapshot_digest: sourceSha, request_sha256: task.requestSha256,
    input_event_cursor: 1, revision: 1,
  };
  const env: NodeJS.ProcessEnv = {
    PATH: `${dirname(process.execPath)}:/usr/bin:/bin`, LANG: 'C', LC_ALL: 'C',
    DISABLE_TELEMETRY: 'true', TMPDIR: outputRoot,
    PADNOTE_TEST_TASK_ROOT: task.root,
    PADNOTE_TEST_VIDEO_BINDING: JSON.stringify(binding),
    PADNOTE_TEST_IGNORE_SIGTERM: '1',
  };
  const child = spawn(process.execPath, ['--import', 'tsx', resolve(rootSkill, 'tests/fixtures/held-storyboard-worker.ts')], {
    cwd: rootSkill, env, detached: true, stdio: 'ignore',
  });
  t.after(async () => {
    if (child.exitCode == null && child.signalCode == null && child.pid) {
      try { process.kill(-child.pid, 'SIGKILL'); } catch { /* already exited */ }
      await waitForChildExit(child);
    }
  });
  await waitForState(task.root, state => state.status === 'running' && state.phase === 'storyboard');
  const worker = resolve(rootSkill, 'scripts/task-worker.ts');
  const runningInspection = await runNodeCli(rootSkill, [worker, 'inspect', task.root], env);
  assert.equal(runningInspection.code, 0, runningInspection.stderr);
  const inspected = JSON.parse(runningInspection.stdout) as {status: string; phase: string; event_cursor: number};
  assert.equal(inspected.status, 'running');
  assert.equal(inspected.phase, 'storyboard');
  assert.equal(inspected.event_cursor, 2);
  const result = await runNodeCli(rootSkill, [worker, 'cancel-operation', task.root,
    '--task-id', task.taskId, '--request-sha256', task.requestSha256,
    '--event-cursor', '1', '--revision', '1', '--operation-id', operationId,
    '--attempt-id', attemptId, '--action', 'storyboard', '--payload-digest', binding.payload_digest,
    '--source-snapshot-sha256', sourceSha, '--internal-worker'], env);
  assert.equal(result.code, 0, result.stderr);
  const response = JSON.parse(result.stdout) as {status: string; reason: string};
  assert.equal(response.status, 'verified_cancelled', `cancel result was ${response.reason}`);
  await waitForChildExit(child);
  assert.equal(child.signalCode, 'SIGKILL');
  const finalState = await loadTaskState(task.root);
  assert.equal(finalState?.status, 'cancelled');
  assert.equal(finalState?.attempt, undefined);
  assert.equal(await fileBytes(testLeaseOwnerPath(task.root, outputRoot)), undefined);
});

async function waitForState(
  taskRoot: string,
  predicate: (state: NonNullable<Awaited<ReturnType<typeof loadTaskState>>>) => boolean,
): Promise<void> {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const state = await loadTaskState(taskRoot);
    if (state && predicate(state)) return;
    await new Promise(resolveWait => setTimeout(resolveWait, 5));
  }
  assert.fail('task did not reach the requested state');
}

async function runNodeCli(
  cwd: string, args: string[], env: NodeJS.ProcessEnv,
): Promise<{code: number | null; signal: NodeJS.Signals | null; stdout: string; stderr: string}> {
  const child = spawn(process.execPath, ['--import', 'tsx', ...args], {
    cwd, env, stdio: ['ignore', 'pipe', 'pipe'],
  });
  let stdout = '';
  let stderr = '';
  child.stdout.setEncoding('utf8').on('data', chunk => { stdout += chunk; });
  child.stderr.setEncoding('utf8').on('data', chunk => { stderr += chunk; });
  return await new Promise((resolveResult, reject) => {
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error('video CLI exceeded test timeout'));
    }, 15_000);
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('close', (code, signal) => {
      clearTimeout(timer);
      resolveResult({code, signal, stdout, stderr});
    });
  });
}

async function waitForChildExit(child: ReturnType<typeof spawn>): Promise<void> {
  if (child.exitCode != null || child.signalCode != null) return;
  await new Promise<void>(resolveExit => child.once('exit', () => resolveExit()));
}

function testLeaseOwnerPath(taskRoot: string, temporaryRoot: string): string {
  const digest = createHash('sha256').update(realpathSync(taskRoot)).digest('hex');
  const uid = typeof process.getuid === 'function' ? process.getuid() : 'nouid';
  return resolve(temporaryRoot, `padnote-video-worker-${uid}`, digest, 'owner.json');
}
