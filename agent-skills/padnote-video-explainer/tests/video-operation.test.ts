import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {cp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import test, {type TestContext} from 'node:test';
import {initializeOperationTask, initializeExactTask, reconcileVideoOperation,
  buildOperationStoryboard, grantOperationApproval} from '../scripts/task-worker.js';
import {payloadDigest, sourceSnapshotDigest, type VideoOperationBinding} from '../scripts/video-operation.js';
import {skillRoot, sha256Bytes} from '../scripts/lib.js';
import {eventPath, statePath, transitionTaskState} from '../scripts/task-state.js';

const root = skillRoot();
const fixture = resolve(root, 'tests/fixtures/formula-note');
const output = resolve(root, '.local-output/video-operation-tests');
const noHeavyImports = resolve(root, 'tests/fixtures/forbid-heavy-task-worker-imports.mjs');

async function task(t: TestContext) {
  await mkdir(output, {recursive: true});
  const path = resolve(output, randomUUID());
  await mkdir(path, {recursive: true});
  await cp(resolve(fixture, 'request.json'), resolve(path, 'request.json'));
  await cp(resolve(fixture, 'input'), resolve(path, 'input'), {recursive: true});
  await cp(resolve(fixture, 'expected/lesson.ir.json'), resolve(path, 'output', 'lesson.ir.json')).catch(async () => {
    await mkdir(resolve(path, 'output'), {recursive: true});
    await cp(resolve(fixture, 'expected/lesson.ir.json'), resolve(path, 'output', 'lesson.ir.json'));
  });
  await mkdir(resolve(path, 'work'), {recursive: true});
  const bytes = await readFile(resolve(path, 'request.json'));
  const request = JSON.parse(bytes.toString('utf8')) as {task_id: string};
  t.after(async () => rm(path, {recursive: true, force: true}));
  return {path, taskId: request.task_id, requestSha: sha256Bytes(bytes)};
}

function binding(taskId: string, requestSha: string, sourceSha: string): VideoOperationBinding {
  const params = {};
  return {operation_id: randomUUID(), attempt_id: randomUUID(), action: 'initialize',
    payload_digest: payloadDigest('initialize', params), source_snapshot_digest: sourceSha,
    request_sha256: requestSha, input_event_cursor: 0};
}

function actionBinding(action: 'storyboard' | 'approve', taskId: string, requestSha: string,
                       sourceSha: string, parameters: Record<string, unknown>, cursor: number,
                       revision: number, hashes?: {review: string; ir: string}): VideoOperationBinding {
  return {operation_id: randomUUID(), attempt_id: randomUUID(), action,
    payload_digest: payloadDigest(action, parameters), source_snapshot_digest: sourceSha,
    request_sha256: requestSha, input_event_cursor: cursor, revision,
    ...(hashes ? {review_sha256: hashes.review, lesson_ir_sha256: hashes.ir} : {})};
}

test('operation initialization persists a correlated completion receipt and reconciles read-only', async t => {
  const current = await task(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  assert.equal(sourceSha, 'ea667b42e932a3b2f1825e1b6eaed9e31aa3291c2bd9b2d9cc0968973958b046');
  const op = binding(current.taskId, current.requestSha, sourceSha);
  const result = await initializeOperationTask(current.path, current.taskId, current.requestSha, op);
  assert.equal(result.status, 'initialized');
  const stateBefore = await readFile(statePath(current.path));
  const eventsBefore = await readFile(eventPath(current.path));
  const proof = await reconcileVideoOperation(current.path, current.taskId, op);
  assert.equal(proof.outcome, 'verified_completed');
  assert.equal(proof.reason, 'receipt_match');
  assert.equal(proof.result?.event_cursor, 1);
  assert.deepEqual(await readFile(statePath(current.path)), stateBefore);
  assert.deepEqual(await readFile(eventPath(current.path)), eventsBefore);

  const variants: VideoOperationBinding[] = [
    {...op, operation_id: randomUUID()},
    {...op, attempt_id: randomUUID()},
    {...op, payload_digest: '0'.repeat(64)},
    {...op, source_snapshot_digest: '0'.repeat(64)},
    {...op, request_sha256: '0'.repeat(64)},
  ];
  for (const variant of variants) {
    const rejected = await reconcileVideoOperation(current.path, current.taskId, variant);
    assert.equal(rejected.outcome, 'unconfirmed');
    assert.equal(rejected.result, null);
  }
  assert.deepEqual(await readFile(statePath(current.path)), stateBefore);
  assert.deepEqual(await readFile(eventPath(current.path)), eventsBefore);
});

test('legacy initialized state cannot be claimed by a new operation or inferred as its completion', async t => {
  const current = await task(t);
  await initializeExactTask(current.path, current.taskId, current.requestSha);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const op = binding(current.taskId, current.requestSha, sourceSha);
  const stateBefore = await readFile(statePath(current.path));
  const eventsBefore = await readFile(eventPath(current.path));
  await assert.rejects(initializeOperationTask(current.path, current.taskId, current.requestSha, op),
    /exact task initialization failed/);
  const proof = await reconcileVideoOperation(current.path, current.taskId, op);
  assert.equal(proof.outcome, 'unconfirmed');
  assert.equal(proof.reason, 'legacy_no_receipt');
  assert.deepEqual(await readFile(statePath(current.path)), stateBefore);
  assert.deepEqual(await readFile(eventPath(current.path)), eventsBefore);
});

test('changed source input remains unconfirmed without reconciliation writes', async t => {
  const current = await task(t);
  const originalSourceSha = await sourceSnapshotDigest(current.path);
  const op = binding(current.taskId, current.requestSha, originalSourceSha);
  await initializeOperationTask(current.path, current.taskId, current.requestSha, op);
  await writeFile(resolve(current.path, 'input/content.md'), 'changed after submission');
  const stateBefore = await readFile(statePath(current.path));
  const eventsBefore = await readFile(eventPath(current.path));
  const inputBefore = await readFile(resolve(current.path, 'input/content.md'));
  const proof = await reconcileVideoOperation(current.path, current.taskId, op);
  assert.equal(proof.outcome, 'unconfirmed');
  assert.equal(proof.result, null);
  assert.deepEqual(await readFile(statePath(current.path)), stateBefore);
  assert.deepEqual(await readFile(eventPath(current.path)), eventsBefore);
  assert.deepEqual(await readFile(resolve(current.path, 'input/content.md')), inputBefore);
});

test('source snapshot depth matches the Python contract and rejects a sixth relative path segment', async t => {
  const current = await task(t);
  const originalPath = resolve(current.path, 'input/content.md');
  const bytes = await readFile(originalPath);
  const nestedPath = 'input/a/b/c/d/e/x.md';
  const nestedAbsolute = resolve(current.path, nestedPath);
  await mkdir(resolve(nestedAbsolute, '..'), {recursive: true});
  await writeFile(nestedAbsolute, bytes);
  const manifestPath = resolve(current.path, 'input/manifest.json');
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8')) as {files: Array<Record<string, unknown>>};
  const digest = sha256Bytes(bytes);
  const size = bytes.length;
  manifest.files.push({path: nestedPath, media_type: 'text/markdown', size_bytes: size, sha256: digest});
  manifest.files.sort((left, right) => String(left.path) < String(right.path) ? -1
    : String(left.path) > String(right.path) ? 1 : 0);
  const requestPath = resolve(current.path, 'request.json');
  const request = JSON.parse(await readFile(requestPath, 'utf8')) as {
    source: {entrypoint: string; bundle_sha256: string};
  };
  request.source.bundle_sha256 = sha256Bytes(manifest.files.map(entry =>
    `${String(entry.path)}\0${String(entry.size_bytes)}\0${String(entry.sha256)}\n`).join(''));
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
  await writeFile(requestPath, `${JSON.stringify(request, null, 2)}\n`);
  await assert.rejects(sourceSnapshotDigest(current.path), /too deep|depth/i);
});

test('lost storyboard and approval responses reconcile only the exact bound revisions', async t => {
  const current = await task(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const init = binding(current.taskId, current.requestSha, sourceSha);
  await initializeOperationTask(current.path, current.taskId, current.requestSha, init);

  const storyParams = {revision: 1, event_cursor: 1};
  const story = actionBinding('storyboard', current.taskId, current.requestSha, sourceSha,
    storyParams, 1, 1);
  // Treat the action response as lost; the durable receipt is the only recovery path.
  await buildOperationStoryboard(current.path, current.taskId, current.requestSha, 1, 1, story);
  const storyProof = await reconcileVideoOperation(current.path, current.taskId, story);
  assert.equal(storyProof.outcome, 'verified_completed');
  assert.equal(storyProof.result?.event_cursor, 3);
  assert.match(String(storyProof.result?.review_sha256), /^[a-f0-9]{64}$/);

  const hashes = {review: String(storyProof.result?.review_sha256), ir: String(storyProof.result?.lesson_ir_sha256)};
  const approveParams = {revision: 1, event_cursor: 3,
    review_sha256: hashes.review, lesson_ir_sha256: hashes.ir};
  const approve = actionBinding('approve', current.taskId, current.requestSha, sourceSha,
    approveParams, 3, 1, hashes);
  await grantOperationApproval(current.path, current.taskId, current.requestSha, 1, 3,
    hashes.review, hashes.ir, approve);
  const approveProof = await reconcileVideoOperation(current.path, current.taskId, approve);
  assert.equal(approveProof.outcome, 'verified_completed');
  assert.equal(approveProof.result?.status, 'approved');
  assert.equal(approveProof.result?.event_cursor, 4);

  const substituted = {...approve, operation_id: randomUUID(), attempt_id: randomUUID()};
  const wrongProof = await reconcileVideoOperation(current.path, current.taskId, substituted);
  assert.equal(wrongProof.outcome, 'unconfirmed');
  assert.equal(wrongProof.result, null);
  const wrongCursor = {...story, input_event_cursor: 2,
    payload_digest: payloadDigest('storyboard', {revision: 1, event_cursor: 2})};
  assert.equal((await reconcileVideoOperation(current.path, current.taskId, wrongCursor)).outcome, 'unconfirmed');
  const wrongApprovalHash = {...approve, review_sha256: '0'.repeat(64),
    payload_digest: payloadDigest('approve', {...approveParams, review_sha256: '0'.repeat(64)})};
  assert.equal((await reconcileVideoOperation(current.path, current.taskId, wrongApprovalHash)).outcome, 'unconfirmed');

  const approved = approveProof.result!;
  const approvalId = (approved.approval as {approval_id: string}).approval_id;
  const snapshotDirectory = resolve(current.path, 'work/approved-input', approvalId);
  const snapshotReview = resolve(snapshotDirectory, 'review.json');
  const reviewBytes = await readFile(snapshotReview);
  const stateBeforeDamage = await readFile(statePath(current.path));
  const eventsBeforeDamage = await readFile(eventPath(current.path));
  await writeFile(snapshotReview, 'corrupt snapshot');
  assert.equal((await reconcileVideoOperation(current.path, current.taskId, approve)).outcome, 'unconfirmed');
  await writeFile(snapshotReview, reviewBytes);
  await rm(snapshotDirectory, {recursive: true});
  const missingSnapshot = await reconcileVideoOperation(current.path, current.taskId, approve);
  assert.equal(missingSnapshot.outcome, 'unconfirmed');
  assert.equal(missingSnapshot.result, null);
  assert.deepEqual(await readFile(statePath(current.path)), stateBeforeDamage);
  assert.deepEqual(await readFile(eventPath(current.path)), eventsBeforeDamage);
});

test('partial state/event, started-only work, malformed/oversized audit and locks stay unconfirmed', async t => {
  const current = await task(t);
  const sourceSha = await sourceSnapshotDigest(current.path);
  const init = binding(current.taskId, current.requestSha, sourceSha);
  await initializeOperationTask(current.path, current.taskId, current.requestSha, init);
  const originalState = await readFile(statePath(current.path));
  const originalEvents = await readFile(eventPath(current.path));

  const state = JSON.parse(originalState.toString('utf8')) as {updated_at: string};
  state.updated_at = new Date(Date.parse(state.updated_at) + 1000).toISOString();
  await writeFile(statePath(current.path), `${JSON.stringify(state)}\n`);
  const partialState = await reconcileVideoOperation(current.path, current.taskId, init);
  assert.equal(partialState.outcome, 'unconfirmed');
  assert.deepEqual(await readFile(eventPath(current.path)), originalEvents);

  await writeFile(statePath(current.path), originalState);
  await writeFile(eventPath(current.path), '{"partial":');
  const partialEventBytes = await readFile(eventPath(current.path));
  const partialEvent = await reconcileVideoOperation(current.path, current.taskId, init);
  assert.equal(partialEvent.outcome, 'unconfirmed');
  assert.deepEqual(await readFile(eventPath(current.path)), partialEventBytes);
  await writeFile(eventPath(current.path), originalEvents);

  const storyboardParams = {revision: 1, event_cursor: 1};
  const storyboard = actionBinding('storyboard', current.taskId, current.requestSha,
    sourceSha, storyboardParams, 1, 1);
  await transitionTaskState(current.path, 'storyboard_started_exact', state => ({
    ...state!, status: 'running', phase: 'storyboard', attempt: {
      attempt_id: storyboard.attempt_id, lease_token: randomUUID(), pid: process.pid,
      started_at: new Date().toISOString(), phase: 'storyboard', external_effect_possible: false,
    },
  }), {revision: 1, video_operation_started: {schema_version: 1,
    operation_id: storyboard.operation_id, attempt_id: storyboard.attempt_id,
    action: 'storyboard', payload_digest: storyboard.payload_digest,
    source_snapshot_digest: storyboard.source_snapshot_digest,
    request_sha256: storyboard.request_sha256, input_event_cursor: 1, revision: 1}});
  const startedOnly = await reconcileVideoOperation(current.path, current.taskId, storyboard);
  assert.equal(startedOnly.outcome, 'unconfirmed');
  assert.equal(startedOnly.result, null);

  const lock = resolve(current.path, 'work/.task-state.lock');
  await mkdir(lock);
  const locked = await reconcileVideoOperation(current.path, current.taskId, storyboard);
  assert.equal(locked.outcome, 'unconfirmed');
  assert.equal(locked.reason, 'active_lease');
  await rm(lock, {recursive: true});

  const currentEvents = await readFile(eventPath(current.path));
  const lines = currentEvents.toString('utf8').trimEnd().split('\n');
  const last = JSON.parse(lines.at(-1)!);
  last.padding = 'x'.repeat(65537);
  lines[lines.length - 1] = JSON.stringify(last);
  await writeFile(eventPath(current.path), `${lines.join('\n')}\n`);
  const oversizedBytes = await readFile(eventPath(current.path));
  const oversized = await reconcileVideoOperation(current.path, current.taskId, storyboard);
  assert.equal(oversized.outcome, 'unconfirmed');
  assert.deepEqual(await readFile(eventPath(current.path)), oversizedBytes);
});

test('operation payload digest matches canonical Python-compatible JSON', () => {
  assert.equal(payloadDigest('storyboard', {revision: 2, event_cursor: 7}),
    'df00844eee2c0dcca4ed144a2aa50a3383d716f268fa901946e6898314b2673c');
});

test('real operation CLI enforces ordered args for each action and reconciles full review flow', async t => {
  const current = await task(t);
  const temporary = resolve(output, 'tmp');
  await mkdir(temporary, {recursive: true});
  const env: NodeJS.ProcessEnv = {
    PATH: `${dirname(process.execPath)}:/usr/bin:/bin`, LANG: 'C', LC_ALL: 'C',
    DISABLE_TELEMETRY: 'true', TMPDIR: temporary, HOME: process.env.HOME,
    ...(process.env.PUPPETEER_CACHE_DIR ? {PUPPETEER_CACHE_DIR: process.env.PUPPETEER_CACHE_DIR} : {}),
    ...(process.env.PUPPETEER_EXECUTABLE_PATH ? {PUPPETEER_EXECUTABLE_PATH: process.env.PUPPETEER_EXECUTABLE_PATH} : {}),
  };
  const worker = resolve(root, 'scripts/task-worker.ts');
  const invoke = (action: string, options: string[], guardHeavyImports = false) => spawnSync(process.execPath,
    ['--import', 'tsx', ...(guardHeavyImports ? ['--import', noHeavyImports] : []),
      worker, action, current.path, ...options, '--internal-worker'],
    {cwd: root, env, encoding: 'utf8', timeout: 120_000});
  const sourceSha = await sourceSnapshotDigest(current.path);
  const init = binding(current.taskId, current.requestSha, sourceSha);
  const invalidOrder = invoke('initialize-operation', [
    '--task-id', current.taskId, '--operation-id', init.operation_id,
    '--request-sha256', current.requestSha, '--attempt-id', init.attempt_id,
    '--payload-digest', init.payload_digest, '--source-snapshot-sha256', sourceSha,
  ]);
  assert.notEqual(invalidOrder.status, 0);
  assert.equal(invalidOrder.stderr.trim(), 'video operation action failed');
  await assert.rejects(readFile(statePath(current.path)), {code: 'ENOENT'});
  await assert.rejects(readFile(eventPath(current.path)), {code: 'ENOENT'});

  const initialized = invoke('initialize-operation', [
    '--task-id', current.taskId, '--request-sha256', current.requestSha,
    '--operation-id', init.operation_id, '--attempt-id', init.attempt_id,
    '--payload-digest', init.payload_digest, '--source-snapshot-sha256', sourceSha,
  ], true);
  for (const [specifier, mode] of [
    ['puppeteer', 'import'],
    ['@ffmpeg-installer/ffmpeg', 'import'],
    ['@ffprobe-installer/ffprobe', 'import'],
    ['@ffmpeg-installer/ffmpeg', 'require'],
    ['@ffprobe-installer/ffprobe', 'require'],
  ] as const) {
    const expression = mode === 'import'
      ? `import(${JSON.stringify(specifier)})`
      : `require(${JSON.stringify(specifier)})`;
    const args = mode === 'import'
      ? ['--input-type=module', '--eval', expression]
      : ['--eval', expression];
    const guardProbe = spawnSync(process.execPath,
      ['--import', noHeavyImports, ...args],
      {cwd: root, env, encoding: 'utf8', timeout: 30_000});
    assert.notEqual(guardProbe.status, 0,
      `the test guard itself must reject ${mode} of ${specifier}`);
    assert.match(guardProbe.stderr,
      new RegExp(`heavy task-worker dependency resolved unexpectedly: ${specifier.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`));
  }
  assert.equal(initialized.status, 0,
    `initialize-only CLI must not resolve Puppeteer/Revideo/renderer modules: ${initialized.stderr}`);
  assert.equal(JSON.parse(initialized.stdout).event_cursor, 1);
  const inspected = invoke('inspect', [], true);
  assert.equal(inspected.status, 0,
    `inspect-only CLI must not resolve Puppeteer/Revideo/renderer modules: ${inspected.stderr}`);
  assert.equal(JSON.parse(inspected.stdout).status, 'initialized');
  const initReconcile = invoke('reconcile-operation', [
    '--task-id', current.taskId, '--request-sha256', current.requestSha, '--event-cursor', '0',
    '--operation-id', init.operation_id, '--attempt-id', init.attempt_id,
    '--action', 'initialize', '--payload-digest', init.payload_digest,
    '--source-snapshot-sha256', sourceSha,
  ]);
  assert.equal(initReconcile.status, 0, initReconcile.stderr);
  assert.equal(JSON.parse(initReconcile.stdout).outcome, 'verified_completed');

  const storyParams = {revision: 1, event_cursor: 1};
  const story = actionBinding('storyboard', current.taskId, current.requestSha, sourceSha,
    storyParams, 1, 1);
  const storyboard = invoke('storyboard-operation', [
    '--task-id', current.taskId, '--request-sha256', current.requestSha,
    '--revision', '1', '--event-cursor', '1', '--operation-id', story.operation_id,
    '--attempt-id', story.attempt_id, '--payload-digest', story.payload_digest,
    '--source-snapshot-sha256', sourceSha,
  ]);
  assert.equal(storyboard.status, 0, storyboard.stderr);
  const storyboardState = JSON.parse(storyboard.stdout) as {event_cursor: number; review_sha256: string; lesson_ir_sha256: string};
  assert.equal(storyboardState.event_cursor, 3);
  const storyReconcile = invoke('reconcile-operation', [
    '--task-id', current.taskId, '--request-sha256', current.requestSha,
    '--event-cursor', '1', '--revision', '1', '--operation-id', story.operation_id,
    '--attempt-id', story.attempt_id, '--action', 'storyboard',
    '--payload-digest', story.payload_digest, '--source-snapshot-sha256', sourceSha,
  ]);
  assert.equal(storyReconcile.status, 0, storyReconcile.stderr);
  assert.equal(JSON.parse(storyReconcile.stdout).outcome, 'verified_completed');

  const stateBeforeLateCancel = await readFile(statePath(current.path));
  const eventsBeforeLateCancel = await readFile(eventPath(current.path));
  const lateCancel = invoke('cancel-operation', [
    '--task-id', current.taskId, '--request-sha256', current.requestSha,
    '--event-cursor', '1', '--revision', '1', '--operation-id', story.operation_id,
    '--attempt-id', story.attempt_id, '--action', 'storyboard',
    '--payload-digest', story.payload_digest, '--source-snapshot-sha256', sourceSha,
  ]);
  assert.equal(lateCancel.status, 0, lateCancel.stderr);
  assert.equal(JSON.parse(lateCancel.stdout).status, 'unconfirmed');
  assert.equal(JSON.parse(lateCancel.stdout).reason, 'not_running');
  assert.deepEqual(await readFile(statePath(current.path)), stateBeforeLateCancel);
  assert.deepEqual(await readFile(eventPath(current.path)), eventsBeforeLateCancel);

  const hashes = {review: storyboardState.review_sha256, ir: storyboardState.lesson_ir_sha256};
  const approveParams = {revision: 1, event_cursor: 3,
    review_sha256: hashes.review, lesson_ir_sha256: hashes.ir};
  const approve = actionBinding('approve', current.taskId, current.requestSha, sourceSha,
    approveParams, 3, 1, hashes);
  const approval = invoke('approve-operation', [
    '--task-id', current.taskId, '--request-sha256', current.requestSha,
    '--revision', '1', '--event-cursor', '3', '--review-sha256', hashes.review,
    '--lesson-ir-sha256', hashes.ir, '--operation-id', approve.operation_id,
    '--attempt-id', approve.attempt_id, '--payload-digest', approve.payload_digest,
    '--source-snapshot-sha256', sourceSha,
  ], true);
  assert.equal(approval.status, 0,
    `approval-only CLI must not resolve Puppeteer/Revideo/renderer modules: ${approval.stderr}`);
  assert.equal(JSON.parse(approval.stdout).status, 'approved');
  const approvalReconcile = invoke('reconcile-operation', [
    '--task-id', current.taskId, '--request-sha256', current.requestSha,
    '--event-cursor', '3', '--revision', '1', '--review-sha256', hashes.review,
    '--lesson-ir-sha256', hashes.ir, '--operation-id', approve.operation_id,
    '--attempt-id', approve.attempt_id, '--action', 'approve',
    '--payload-digest', approve.payload_digest, '--source-snapshot-sha256', sourceSha,
  ]);
  assert.equal(approvalReconcile.status, 0, approvalReconcile.stderr);
  const approvedProof = JSON.parse(approvalReconcile.stdout) as {outcome: string; result: {event_cursor: number}};
  assert.equal(approvedProof.outcome, 'verified_completed');
  assert.equal(approvedProof.result.event_cursor, 4);
});
