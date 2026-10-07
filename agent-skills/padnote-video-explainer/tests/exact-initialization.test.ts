import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {cp, mkdir, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {dirname, resolve} from 'node:path';
import test, {type TestContext} from 'node:test';
import {initializeExactTask} from '../scripts/task-worker.js';
import {sha256Bytes, skillRoot} from '../scripts/lib.js';
import {eventPath, loadTaskState, statePath, transitionTaskState} from '../scripts/task-state.js';
import {inspectTask} from '../scripts/inspect-task.js';

const fixture = resolve(skillRoot(), 'tests/fixtures/formula-note');
const roots = resolve(skillRoot(), '.local-output/exact-initialization-tests');
const worker = resolve(skillRoot(), 'scripts/task-worker.ts');

async function makeTask(t: TestContext): Promise<{root: string; taskId: string; requestSha256: string}> {
  const root = resolve(roots, `${Date.now()}-${randomUUID()}`);
  await mkdir(root, {recursive: true});
  await cp(resolve(fixture, 'request.json'), resolve(root, 'request.json'));
  await cp(resolve(fixture, 'input'), resolve(root, 'input'), {recursive: true});
  await mkdir(resolve(root, 'work'));
  const raw = await readFile(resolve(root, 'request.json'));
  const request = JSON.parse(raw.toString('utf8')) as {task_id: string};
  t.after(async () => { await rm(root, {recursive: true, force: true}); });
  return {root, taskId: request.task_id, requestSha256: sha256Bytes(raw)};
}

async function optionalBytes(path: string): Promise<Buffer | undefined> {
  try { return await readFile(path); } catch (error: any) {
    if (error?.code === 'ENOENT') return undefined;
    throw error;
  }
}

function invokeCli(args: string[]) {
  return spawnSync(process.execPath, ['--import', 'tsx', worker, ...args], {
    cwd: skillRoot(), encoding: 'utf8', timeout: 30_000,
    env: {
      PATH: `${dirname(process.execPath)}:/usr/bin:/bin`,
      LANG: 'C', LC_ALL: 'C', DISABLE_TELEMETRY: 'true', TMPDIR: roots,
    },
  });
}

test('rejects a wrong raw request digest or worker identity before writing state or events', async t => {
  const task = await makeTask(t);
  const originalEntries = await readdir(resolve(task.root, 'work'));

  await assert.rejects(initializeExactTask(task.root, task.taskId, 'a'.repeat(64)),
    /exact task initialization failed/);
  await assert.rejects(initializeExactTask(task.root, 'different-worker-id', task.requestSha256),
    /exact task initialization failed/);

  assert.equal(await optionalBytes(statePath(task.root)), undefined);
  assert.equal(await optionalBytes(eventPath(task.root)), undefined);
  assert.deepEqual(await readdir(resolve(task.root, 'work')), originalEntries);
});

test('creates exact initial state once and concurrent same-binding calls are read-only/idempotent', async t => {
  const task = await makeTask(t);
  const [first, second] = await Promise.all([
    initializeExactTask(task.root, task.taskId, task.requestSha256),
    initializeExactTask(task.root, task.taskId, task.requestSha256),
  ]);
  assert.equal(first.status, 'initialized');
  assert.equal(first.event_cursor, 1);
  assert.deepEqual(first, second);
  const persisted = await loadTaskState(task.root);
  assert.equal(persisted?.task_id, task.taskId);
  assert.equal(persisted?.request_sha256, task.requestSha256);
  const events = (await readFile(eventPath(task.root), 'utf8')).trim().split('\n');
  assert.equal(events.length, 1);
  assert.equal(JSON.parse(events[0]!).event, 'task_initialized_exact');
});

test('cold schema cache accepts simultaneous request validations without duplicate Ajv compilation', async () => {
  await mkdir(roots, {recursive: true});
  const schemaModule = new URL('../scripts/lib.ts', import.meta.url).href;
  const requestPath = resolve(fixture, 'request.json');
  const source = [
    "import {readFile} from 'node:fs/promises';",
    `import {validateSchema} from ${JSON.stringify(schemaModule)};`,
    `const request = JSON.parse(await readFile(${JSON.stringify(requestPath)}, 'utf8'));`,
    "await Promise.all(Array.from({length: 8}, () => validateSchema('request', request)));",
  ].join('\n');
  const result = spawnSync(process.execPath, [
    '--import', 'tsx', '--input-type=module', '--eval', source,
  ], {
    cwd: skillRoot(), encoding: 'utf8', timeout: 20_000,
    env: {
      PATH: `${dirname(process.execPath)}:/usr/bin:/bin`,
      LANG: 'C', LC_ALL: 'C', DISABLE_TELEMETRY: 'true', TMPDIR: roots,
    },
  });
  assert.equal(result.status, 0, 'fresh concurrent validation must compile the request schema once');
});

test('returns an existing interrupted task without recovering or appending an event', async t => {
  const task = await makeTask(t);
  await initializeExactTask(task.root, task.taskId, task.requestSha256);
  await transitionTaskState(task.root, 'test_interrupted', current => ({
    ...current!, status: 'interrupted', phase: 'storyboard',
    error: {message: 'worker ended before recording a result', phase: 'storyboard', external_effect_possible: false},
  }));
  const stateBefore = await readFile(statePath(task.root));
  const eventsBefore = await readFile(eventPath(task.root));

  const returned = await initializeExactTask(task.root, task.taskId, task.requestSha256);
  assert.equal(returned.status, 'interrupted');
  assert.deepEqual(await readFile(statePath(task.root)), stateBefore);
  assert.deepEqual(await readFile(eventPath(task.root)), eventsBefore);
  assert.equal((await inspectTask(task.root)).status, 'interrupted');
});

test('CLI requires exact flags, rejects extras, and returns the inspect projection', async t => {
  const missing = await makeTask(t);
  const missingArgs = invokeCli(['init-exact', missing.root, '--task-id', missing.taskId]);
  assert.notEqual(missingArgs.status, 0);
  assert.equal(await optionalBytes(statePath(missing.root)), undefined);
  assert.equal(await optionalBytes(eventPath(missing.root)), undefined);

  const task = await makeTask(t);
  const args = ['init-exact', task.root, '--task-id', task.taskId,
    '--request-sha256', task.requestSha256];
  const completed = invokeCli([...args, '--internal-worker']);
  assert.equal(completed.status, 0, completed.stderr);
  const projection = JSON.parse(completed.stdout);
  assert.equal(projection.protocol_version, 1);
  assert.equal(projection.task_id, task.taskId);
  assert.equal(projection.status, 'initialized');
  assert.equal(projection.event_cursor, 1);
  assert.ok(!completed.stdout.includes(task.root));
  const extra = invokeCli([...args, '--internal-worker', '--internal-worker']);
  assert.notEqual(extra.status, 0);
  assert.ok(!extra.stderr.includes(task.root));
});

test('rejects a mismatched existing persisted request digest without changing bytes', async t => {
  const task = await makeTask(t);
  await initializeExactTask(task.root, task.taskId, task.requestSha256);
  const originalState = await readFile(statePath(task.root));
  const originalEvents = await readFile(eventPath(task.root));
  const mutated = JSON.parse(originalState.toString('utf8'));
  mutated.request_sha256 = 'b'.repeat(64);
  await writeFile(statePath(task.root), JSON.stringify(mutated));
  const changedState = await readFile(statePath(task.root));
  const changedEvents = await readFile(eventPath(task.root));
  await assert.rejects(initializeExactTask(task.root, task.taskId, task.requestSha256));
  assert.deepEqual(await readFile(statePath(task.root)), changedState);
  assert.deepEqual(await readFile(eventPath(task.root)), changedEvents);
  assert.notDeepEqual(changedState, originalState);
});

test('refuses to initialize over an orphaned event log', async t => {
  const task = await makeTask(t);
  const orphan = Buffer.from('{"sequence":1,"event":"orphan"}\n');
  await writeFile(eventPath(task.root), orphan);
  await assert.rejects(initializeExactTask(task.root, task.taskId, task.requestSha256));
  assert.equal(await optionalBytes(statePath(task.root)), undefined);
  assert.deepEqual(await readFile(eventPath(task.root)), orphan);
});

test('rejects missing, stale, or partial latest audit records without repairing either file', async t => {
  for (const corruption of ['missing', 'stale-cursor', 'partial-line'] as const) {
    const task = await makeTask(t);
    await initializeExactTask(task.root, task.taskId, task.requestSha256);
    const originalState = await readFile(statePath(task.root));
    const originalEvents = await readFile(eventPath(task.root));
    if (corruption === 'missing') {
      await rm(eventPath(task.root));
    } else if (corruption === 'stale-cursor') {
      const records = originalEvents.toString('utf8').trimEnd().split('\n');
      const latest = JSON.parse(records[records.length - 1]!);
      latest.sequence -= 1;
      records[records.length - 1] = JSON.stringify(latest);
      await writeFile(eventPath(task.root), `${records.join('\n')}\n`);
    } else {
      await writeFile(eventPath(task.root), originalEvents.subarray(0, originalEvents.length - 1));
    }
    const corruptedEvents = await optionalBytes(eventPath(task.root));
    const corruptedState = await readFile(statePath(task.root));

    await assert.rejects(initializeExactTask(task.root, task.taskId, task.requestSha256));

    assert.deepEqual(await readFile(statePath(task.root)), corruptedState);
    assert.deepEqual(await optionalBytes(eventPath(task.root)), corruptedEvents);
    assert.deepEqual(originalState, await optionalBytes(statePath(task.root)));
  }
});

test('checks only a bounded latest event while allowing a long valid history', async t => {
  const task = await makeTask(t);
  await initializeExactTask(task.root, task.taskId, task.requestSha256);
  await transitionTaskState(task.root, 'large_historical_event', current => current!, {
    historical_detail: 'x'.repeat(70 * 1024),
  });
  await transitionTaskState(task.root, 'short_latest_event', current => current!);

  const eventBytes = await readFile(eventPath(task.root));
  assert.ok(eventBytes.length > 64 * 1024);
  assert.equal((await initializeExactTask(task.root, task.taskId, task.requestSha256)).event_cursor, 3);

  await transitionTaskState(task.root, 'oversized_latest_event', current => current!, {
    detail: 'y'.repeat(70 * 1024),
  });
  const stateBefore = await readFile(statePath(task.root));
  const eventsBefore = await readFile(eventPath(task.root));
  await assert.rejects(initializeExactTask(task.root, task.taskId, task.requestSha256));
  assert.deepEqual(await readFile(statePath(task.root)), stateBefore);
  assert.deepEqual(await readFile(eventPath(task.root)), eventsBefore);
});
