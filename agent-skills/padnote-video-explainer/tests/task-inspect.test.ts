import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {cp, lstat, mkdir, readFile, rm, symlink, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import test from 'node:test';
import {
  buildPersistedStoryboard,
  cancelTask,
  ensureTaskState,
  grantApproval,
  runApprovedTask,
  type WorkerEffects,
} from '../scripts/task-worker.js';
import {inspectTask} from '../scripts/inspect-task.js';
import {makeBuiltinProduceSuccess} from '../scripts/builtin-envelope.js';
import {atomicWriteJson, skillRoot} from '../scripts/lib.js';
import {payloadDigest, sourceSnapshotDigest} from '../scripts/video-operation.js';

const fixture = resolve(skillRoot(), 'tests/fixtures/formula-note');
const roots = resolve(skillRoot(), '.local-output/task-inspect-tests');

test('inspect missing task does not initialize directories or write events', async () => {
  const root = resolve(roots, 'missing');
  await rm(root, {recursive: true, force: true});
  await mkdir(root, {recursive: true});
  await assert.rejects(inspectTask(root));
  await assert.rejects(lstat(resolve(root, 'work')));
});

test('inspect follows worker-generated states without changing persisted state or events', async () => {
  const root = await makeTask('states');
  assert.equal((await inspectWithoutMutation(root)).status, 'initialized');

  await buildPersistedStoryboard(root, 1);
  const awaiting = await inspectWithoutMutation(root);
  assert.equal(awaiting.status, 'awaiting_storyboard_review');
  assert.equal(awaiting.revision, 1);
  assert.match(awaiting.lesson_ir_sha256 ?? '', /^[a-f0-9]{64}$/);
  assert.match(awaiting.review_sha256 ?? '', /^[a-f0-9]{64}$/);
  assert.ok(!JSON.stringify(awaiting).includes('lesson.ir.json'));
  assert.ok(!JSON.stringify(awaiting).includes('content.md'));

  await grantApproval(root, 1);
  const approved = await inspectWithoutMutation(root);
  assert.equal(approved.status, 'approved');
  assert.ok(approved.approval?.approval_id);
  assert.equal(approved.approval?.revision, 1);
  assert.match(approved.approval?.lesson_ir_sha256 ?? '', /^[a-f0-9]{64}$/);
  assert.match(approved.approval?.review_sha256 ?? '', /^[a-f0-9]{64}$/);
  assert.ok(approved.approval?.granted_at);
  assert.equal(approved.approval?.consumed_at, undefined);
  assert.ok(!JSON.stringify(approved).includes('snapshot_path'));
  assert.ok(!JSON.stringify(approved).includes('work/approved-input'));

  const audioReady = await runApprovedTask(root, 'audio', false, fakeEffects());
  assert.equal(audioReady.status, 'ready_to_render');
  const ready = await inspectWithoutMutation(root);
  assert.equal(ready.status, 'ready_to_render');
  assert.ok(ready.approval?.consumed_at);

  const completed = await runApprovedTask(root, 'render', false, fakeEffects());
  assert.equal(completed.status, 'completed');
  const finalInspection = await inspectWithoutMutation(root);
  assert.equal(finalInspection.status, 'completed');
  const binding = {
    operation_id: randomUUID(), attempt_id: randomUUID(), action: 'produce' as const,
    payload_digest: payloadDigest('produce', {
      revision: approved.revision!, event_cursor: approved.event_cursor,
      review_sha256: approved.review_sha256!, lesson_ir_sha256: approved.lesson_ir_sha256!,
      allow_cloud_tts: true,
    }),
    source_snapshot_digest: await sourceSnapshotDigest(root),
    request_sha256: approved.request_sha256!, input_event_cursor: approved.event_cursor,
    revision: approved.revision!, review_sha256: approved.review_sha256!,
    lesson_ir_sha256: approved.lesson_ir_sha256!, allow_cloud_tts: true as const,
  };
  const envelope = makeBuiltinProduceSuccess(finalInspection, binding, [{id: 'fixture'}]);
  assert.deepEqual(Object.keys(envelope).sort(), ['artifacts', 'inspection', 'ok', 'receipt']);
  assert.equal(envelope.ok, true);
  assert.equal(envelope.inspection.protocol_version, 1);
  assert.equal(envelope.inspection.phase, 'completed');
  assert.ok(envelope.inspection.approval?.consumed_at);
  assert.equal(envelope.receipt.result_event_cursor, finalInspection.event_cursor);
  assert.throws(() => makeBuiltinProduceSuccess({...finalInspection, status: 'approved'}, binding, [{id: 'fixture'}]),
    /completed task inspection/);
});

test('inspect reports worker-generated failed and cancelled states with valid combinations', async () => {
  const failedRoot = await makeTask('failed');
  await buildPersistedStoryboard(failedRoot, 1);
  await grantApproval(failedRoot, 1);
  await assert.rejects(runApprovedTask(failedRoot, 'all', false, fakeEffects({
    prepareAudio: async () => { throw new Error('offline fixture failure'); },
  })), /offline fixture failure/);
  const failed = await inspectWithoutMutation(failedRoot);
  assert.equal(failed.status, 'failed');
  assert.ok(failed.approval?.consumed_at);

  const cancelledRoot = await makeTask('cancelled');
  await cancelTask(cancelledRoot);
  const cancelled = await inspectWithoutMutation(cancelledRoot);
  assert.equal(cancelled.status, 'cancelled');
});

test('built-in TTS budget and model preflight do not consume exact approval', async t => {
  for (const scenario of ['long-narration', 'unsupported-model', 'unsupported-region'] as const) {
    await t.test(scenario, async () => {
      const root = await makeTask(`tts-preflight-${scenario}`);
      if (scenario === 'long-narration') {
        const irPath = resolve(root, 'output/lesson.ir.json');
        const ir = JSON.parse(await readFile(irPath, 'utf8'));
        ir.scenes[0].narration = '旁白'.repeat(301);
        await writeFile(irPath, JSON.stringify(ir));
      }
      await buildPersistedStoryboard(root, 1);
      const approved = await grantApproval(root, 1);
      const operation = {
        operation_id: randomUUID(), attempt_id: randomUUID(), action: 'produce' as const,
        payload_digest: '', source_snapshot_digest: await sourceSnapshotDigest(root),
        request_sha256: approved.request_sha256!, input_event_cursor: approved.event_cursor,
        revision: approved.revision!, review_sha256: approved.review_sha256!,
        lesson_ir_sha256: approved.lesson_ir_sha256!, allow_cloud_tts: true as const,
      };
      operation.payload_digest = payloadDigest('produce', {
        revision: operation.revision, event_cursor: operation.input_event_cursor,
        review_sha256: operation.review_sha256, lesson_ir_sha256: operation.lesson_ir_sha256,
        allow_cloud_tts: true,
      });
      const provider = {api_key: 'fake-key',
        base_url: scenario === 'unsupported-region' ? 'https://dashscope-intl.aliyuncs.com/api/v1' : 'https://dashscope.aliyuncs.com/api/v1',
        model: scenario === 'unsupported-model' ? 'qwen-tts' : 'qwen3-tts-instruct-flash', voice: 'Maia'};
      const beforeEvents = await readFile(resolve(root, 'work/task-events.ndjson'));
      await assert.rejects(runApprovedTask(root, 'all', false, fakeEffects(), operation, provider),
        /Built-in video production failed/);
      const after = await inspectTask(root);
      assert.equal(after.status, 'approved');
      assert.equal(after.event_cursor, approved.event_cursor);
      assert.equal(after.approval?.consumed_at, undefined);
      assert.deepEqual(await readFile(resolve(root, 'work/task-events.ndjson')), beforeEvents);
    });
  }
});

test('inspect rejects malformed state, request identity mismatch, and request digest mismatch', async t => {
  const root = await makeTask('invalid');
  const statePath = resolve(root, 'work/task-state.json');
  const originalState = JSON.parse(await readFile(statePath, 'utf8'));
  await t.test('unknown state field', async () => {
    await atomicWriteJson(statePath, {...originalState, unsafe_path: '/private'});
    await assert.rejects(inspectTask(root), /unknown fields/);
  });
  await t.test('unknown status enum', async () => {
    await atomicWriteJson(statePath, {...originalState, status: 'queued'});
    await assert.rejects(inspectTask(root), /invalid required fields/);
  });
  await t.test('inconsistent status and phase', async () => {
    await atomicWriteJson(statePath, {...originalState,
      status: 'approved', phase: 'approval_pending'});
    await assert.rejects(inspectTask(root), /approved task state is inconsistent/);
  });
  await t.test('request task id mismatch', async () => {
    await atomicWriteJson(statePath, {...originalState, task_id: 'different-id'});
    await assert.rejects(inspectTask(root), /task id does not match/);
  });
  await t.test('request raw digest mismatch', async () => {
    await atomicWriteJson(statePath, {...originalState, request_sha256: 'f'.repeat(64)});
    await assert.rejects(inspectTask(root), /request digest/);
  });
  await t.test('corrupt state JSON', async () => {
    await writeFile(statePath, '{secret path body broken');
    const result = runInspectCli(root);
    assert.notEqual(result.status, 0);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, 'inspect failed\n');
  });
});

test('inspect rejects state symlinks and FIFO inputs without blocking', async () => {
  const root = await makeTask('special-files');
  const statePath = resolve(root, 'work/task-state.json');
  const saved = resolve(root, 'work/task-state.saved.json');
  await cp(statePath, saved);
  await rm(statePath);
  await symlink(saved, statePath);
  await assert.rejects(inspectTask(root));

  if (process.platform !== 'win32') {
    await rm(statePath);
    const fifo = spawnSync('/usr/bin/mkfifo', [statePath], {encoding: 'utf8'});
    const fifoError = fifo.error as NodeJS.ErrnoException | null;
    if (fifoError?.code === 'ENOENT') return;
    assert.equal(fifo.status, 0, fifo.stderr);
    const started = Date.now();
    await assert.rejects(inspectTask(root));
    assert.ok(Date.now() - started < 1000, 'FIFO inspection must fail without waiting for a writer');
  }
});

test('inspect CLI prints one safe JSON object and generic path-free errors', async () => {
  const root = await makeTask('cli-output');
  const good = runInspectCli(root);
  assert.equal(good.status, 0, good.stderr);
  assert.equal(good.stdout.trim().split('\n').length, 1);
  const parsed = JSON.parse(good.stdout) as Record<string, unknown>;
  assert.equal(parsed.task_id, 'task-fixture-formula');
  assert.ok(!good.stdout.includes(root));
  assert.ok(!good.stdout.includes('content.md'));

  await writeFile(resolve(root, 'work/task-state.json'), 'not-json');
  const bad = runInspectCli(root);
  assert.notEqual(bad.status, 0);
  assert.equal(bad.stderr, 'inspect failed\n');
  assert.equal(bad.stdout, '');
  assert.ok(!bad.stderr.includes(root));
});

async function makeTask(name: string): Promise<string> {
  const root = resolve(roots, name);
  await rm(root, {recursive: true, force: true});
  await mkdir(resolve(root, 'work'), {recursive: true});
  await mkdir(resolve(root, 'output'), {recursive: true});
  await cp(resolve(fixture, 'request.json'), resolve(root, 'request.json'));
  await cp(resolve(fixture, 'input'), resolve(root, 'input'), {recursive: true});
  await cp(resolve(fixture, 'expected/lesson.ir.json'), resolve(root, 'output/lesson.ir.json'));
  await ensureTaskState(root);
  return root;
}

async function inspectWithoutMutation(root: string) {
  const state = await readFile(resolve(root, 'work/task-state.json'));
  const events = await readFile(resolve(root, 'work/task-events.ndjson'));
  const inspection = await inspectTask(root);
  assert.deepEqual(await readFile(resolve(root, 'work/task-state.json')), state);
  assert.deepEqual(await readFile(resolve(root, 'work/task-events.ndjson')), events);
  return inspection;
}

function fakeEffects(overrides: {
  prepareAudio?: WorkerEffects['prepareAudio'];
  render?: WorkerEffects['render'];
} = {}): WorkerEffects {
  return {
    prepareAudio: async (root, input) => {
      if (overrides.prepareAudio) return overrides.prepareAudio(root, input);
      await writeFile(resolve(root, 'work/audio-manifest.json'), '{}');
      return {};
    },
    render: async (root, revision, fixtureAudio, input) => {
      await overrides.render?.(root, revision, fixtureAudio, input);
      return {};
    },
    validateAudio: async () => ({}),
    validateResult: async () => ({}),
  };
}

function runInspectCli(root: string) {
  const worker = resolve(skillRoot(), 'scripts/task-worker.ts');
  const nodeArgs = process.execArgv.filter(value => value !== '--test');
  return spawnSync(process.execPath, [...nodeArgs, worker, 'inspect', root], {
    encoding: 'utf8', timeout: 30_000, maxBuffer: 64 * 1024,
  });
}
