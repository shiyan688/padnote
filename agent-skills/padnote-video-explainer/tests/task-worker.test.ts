import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {cp, mkdir, readFile, rm, utimes, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import test from 'node:test';
import {
  cancelTask,
  ensureTaskState,
  grantApproval,
  recoverInterruptedTask,
  runApprovedTask,
  type WorkerEffects,
} from '../scripts/task-worker.js';
import {atomicWriteJson, fileDescriptor, skillRoot, type JsonObject} from '../scripts/lib.js';
import {
  acquireExecutionLease,
  executionLockPath,
  loadTaskState,
  releaseExecutionLease,
  transitionTaskState,
} from '../scripts/task-state.js';

const fixtures = resolve(skillRoot(), 'tests/fixtures');
const outputRoot = resolve(skillRoot(), '.local-output/task-worker-tests');

test('approval is bound once and the execution lock rejects a concurrent worker', async () => {
  const root = await reviewedTask('approval-and-lock');
  const approved = await grantApproval(root, 1);
  assert.equal(approved.status, 'approved');
  await assert.rejects(grantApproval(root, 1), /already recorded|consumed/);

  let releasePrepare!: () => void;
  const prepareGate = new Promise<void>(resolveGate => { releasePrepare = resolveGate; });
  let prepareCalls = 0;
  let renderCalls = 0;
  const effects = fakeEffects({
    prepare: async taskRoot => {
      prepareCalls += 1;
      await prepareGate;
      await writeFile(resolve(taskRoot, 'work/audio-manifest.json'), '{}');
    },
    render: async () => { renderCalls += 1; },
  });
  const first = runApprovedTask(root, 'all', false, effects);
  await waitForState(root, state => state.phase === 'tts_starting');
  await assert.rejects(runApprovedTask(root, 'all', false, effects), /already running/);
  releasePrepare();
  const completed = await first;
  assert.equal(completed.status, 'completed');
  assert.equal(prepareCalls, 1);
  assert.equal(renderCalls, 1);
  assert.ok(completed.approval?.consumed_at);
});

test('uncertain TTS failure never auto-replays and requires a new explicit retry approval', async () => {
  const root = await reviewedTask('uncertain-tts');
  await grantApproval(root, 1);
  let prepareCalls = 0;
  const failing = fakeEffects({prepare: async () => {
    prepareCalls += 1;
    throw new Error('provider response was lost');
  }});
  await assert.rejects(runApprovedTask(root, 'all', false, failing), /response was lost/);
  const failed = await loadTaskState(root);
  assert.equal(failed?.status, 'failed');
  assert.equal(failed?.error?.external_effect_possible, true);
  await assert.rejects(runApprovedTask(root, 'all', false, failing), /fresh.*approval/);
  assert.equal(prepareCalls, 1, 'a retry must not charge the provider automatically');
  await assert.rejects(grantApproval(root, 1), /--retry-uncertain/);

  await grantApproval(root, 1, true);
  const recovered = fakeEffects({prepare: async taskRoot => {
    prepareCalls += 1;
    await writeFile(resolve(taskRoot, 'work/audio-manifest.json'), '{}');
  }});
  const completed = await runApprovedTask(root, 'all', false, recovered);
  assert.equal(completed.status, 'completed');
  assert.equal(prepareCalls, 2);
});

test('approved storyboard bytes cannot be replaced before execution', async () => {
  const root = await reviewedTask('approval-binding');
  await grantApproval(root, 1);
  const irPath = resolve(root, 'output/lesson.ir.json');
  const original = await readFile(irPath, 'utf8');
  await writeFile(irPath, `${original}\n`);
  let prepareCalls = 0;
  const effects = fakeEffects({prepare: async () => { prepareCalls += 1; }});
  await assert.rejects(runApprovedTask(root, 'all', false, effects),
    /size mismatch|hash mismatch|changed/);
  assert.equal(prepareCalls, 0);
});

test('paid effects read the approval snapshot even if output IR changes after the gate', async () => {
  const root = await reviewedTask('approval-snapshot-toctou');
  const approved = await grantApproval(root, 1);
  const originalIr = JSON.parse(await readFile(resolve(root, 'output/lesson.ir.json'), 'utf8')) as JsonObject;
  const originalRequest = JSON.parse(await readFile(resolve(root, 'request.json'), 'utf8')) as JsonObject;
  const changedIr = structuredClone(originalIr);
  changedIr.scenes[0].narration = '未审批的替换旁白';
  // Force the reuse-validation hook to run after approval was checked but
  // before the paid preparation effect receives its immutable input.
  await mkdir(resolve(root, 'work'), {recursive: true});
  await writeFile(resolve(root, 'work/audio-manifest.json'), '{}');
  let firstValidation = true;
  let observedNarration = '';
  let observedVoice = '';
  const effects: WorkerEffects = {
    validateAudio: async (_taskRoot, input) => {
      if (firstValidation) {
        firstValidation = false;
        await writeFile(resolve(root, 'output/lesson.ir.json'), JSON.stringify(changedIr));
        await writeFile(input.lessonIrPath, JSON.stringify(changedIr));
        const changedRequest = structuredClone(originalRequest);
        changedRequest.voice.profile = 'unapproved-voice';
        await writeFile(resolve(root, 'request.json'), JSON.stringify(changedRequest));
        throw new Error('no reusable manifest');
      }
      return {};
    },
    prepareAudio: async (_taskRoot, input) => {
      observedNarration = input.ir.scenes[0].narration;
      observedVoice = input.request.voice.profile;
      await writeFile(resolve(root, 'work/audio-manifest.json'), '{}');
      return {};
    },
    render: async () => ({}),
    validateResult: async () => ({}),
  };
  const ready = await runApprovedTask(root, 'audio', false, effects);
  assert.equal(ready.status, 'ready_to_render');
  assert.equal(observedNarration, originalIr.scenes[0].narration);
  assert.notEqual(observedNarration, changedIr.scenes[0].narration);
  assert.equal(observedVoice, originalRequest.voice.profile);
  assert.ok(approved.approval?.lesson_ir_snapshot_path);
});

test('SIGKILL becomes interrupted on restart instead of replaying work', async () => {
  const root = await reviewedTask('strong-kill');
  await ensureTaskState(root);
  const child = await startHoldWorker(root);
  process.kill(-child.pid!, 'SIGKILL');
  await new Promise<void>(resolveExit => child.once('exit', () => resolveExit()));
  const recovered = await recoverInterruptedTask(root);
  assert.equal(recovered.status, 'interrupted');
  assert.equal(recovered.error?.external_effect_possible, true);
  assert.match(recovered.error?.message ?? '', /automatic replay is disabled/);
});

test('cancel stops the worker process group and persists a terminal state', async () => {
  const root = await reviewedTask('cancel-process-group');
  await ensureTaskState(root);
  const child = await startHoldWorker(root);
  const cancelled = await cancelTask(root);
  assert.equal(cancelled.status, 'cancelled');
  await new Promise<void>(resolveExit => {
    if (child.exitCode != null || child.signalCode != null) resolveExit();
    else child.once('exit', () => resolveExit());
  });
  assert.equal((await ensureTaskState(root)).status, 'cancelled');
  await assert.rejects(grantApproval(root, 1, true), /terminal/);
});

test('task-root PID metadata cannot authorize killing an unrelated reused process', async () => {
  const root = await reviewedTask('forged-task-root-lease');
  const unrelated = spawn(process.execPath, ['-e', 'setInterval(()=>{},60000)'], {
    detached: process.platform !== 'win32', stdio: 'ignore',
  });
  assert.ok(unrelated.pid);
  await transitionTaskState(root, 'forged_running_state', current => ({
    ...current!, status: 'running', phase: 'forged',
    attempt: {attempt_id: 'forged', pid: unrelated.pid!,
      started_at: new Date().toISOString(), phase: 'forged',
      external_effect_possible: true},
  }));
  const forge = resolve(root, 'work/.task-execution.lock');
  await mkdir(forge, {recursive: true});
  await writeFile(resolve(forge, 'owner.json'), JSON.stringify({
    token: 'attacker-controlled', pid: unrelated.pid,
    process_identity: {platform: process.platform, boot_id: 'fake',
      start_time: 'fake', process_group_id: unrelated.pid},
  }));

  const cancelled = await cancelTask(root);
  assert.equal(cancelled.status, 'cancelled');
  assert.doesNotThrow(() => process.kill(unrelated.pid!, 0));
  process.kill(-unrelated.pid!, 'SIGKILL');
  await new Promise<void>(resolveExit => unrelated.once('exit', () => resolveExit()));
});

test('stale recovery and cancellation snapshots cannot overwrite completed state', async () => {
  const recoveryRoot = await reviewedTask('terminal-recovery-cas');
  const stale = await transitionTaskState(recoveryRoot, 'fake_running', current => ({
    ...current!, status: 'running', phase: 'render',
    attempt: {attempt_id: 'old-attempt', lease_token: 'old-lease', pid: 999999,
      started_at: new Date().toISOString(), phase: 'render', external_effect_possible: false},
  }));
  await transitionTaskState(recoveryRoot, 'won_completion_race', current => ({
    ...current!, status: 'completed', phase: 'completed', attempt: undefined,
  }));
  assert.equal((await recoverInterruptedTask(recoveryRoot, stale)).status, 'completed');

  const cancelRoot = await reviewedTask('terminal-cancel-cas');
  await ensureTaskState(cancelRoot);
  const child = await startHoldWorker(cancelRoot);
  const completed = await cancelTask(cancelRoot, {beforeStateCommit: async () => {
    await transitionTaskState(cancelRoot, 'won_completion_race', current => ({
      ...current!, status: 'completed', phase: 'completed', attempt: undefined,
    }));
  }});
  assert.equal(completed.status, 'completed');
  assert.doesNotThrow(() => process.kill(child.pid!, 0));
  process.kill(-child.pid!, 'SIGKILL');
  await new Promise<void>(resolveExit => child.once('exit', () => resolveExit()));
});

test('abandoned empty state and execution lock directories are recovered', async () => {
  const root = await reviewedTask('abandoned-locks');
  const old = new Date(Date.now() - 10_000);
  const stateLock = resolve(root, 'work/.task-state.lock');
  await mkdir(stateLock, {recursive: true});
  await utimes(stateLock, old, old);
  const transitioned = await transitionTaskState(root, 'after_abandoned_lock', current => ({
    ...current!, phase: 'lock_recovered',
  }));
  assert.equal(transitioned.phase, 'lock_recovered');

  const executionLock = executionLockPath(root);
  await rm(executionLock, {recursive: true, force: true});
  await mkdir(executionLock, {recursive: true});
  await utimes(executionLock, old, old);
  const lease = await acquireExecutionLease(root);
  assert.ok(lease.process_identity.start_time);
  await releaseExecutionLease(root, lease);
});

async function reviewedTask(name: string): Promise<string> {
  const root = resolve(outputRoot, name);
  await rm(root, {recursive: true, force: true});
  await mkdir(resolve(root, 'output'), {recursive: true});
  await cp(resolve(fixtures, 'formula-note/request.json'), resolve(root, 'request.json'));
  await cp(resolve(fixtures, 'formula-note/input'), resolve(root, 'input'), {recursive: true});
  await cp(resolve(fixtures, 'formula-note/expected/lesson.ir.json'),
    resolve(root, 'output/lesson.ir.json'));
  const ir = JSON.parse(await readFile(
    resolve(root, 'output/lesson.ir.json'), 'utf8')) as JsonObject;
  const request = JSON.parse(await readFile(resolve(root, 'request.json'), 'utf8')) as JsonObject;
  await writeFile(resolve(root, 'output/storyboard.html'), '<!doctype html><p>offline</p>');
  const artifacts: JsonObject[] = [{
    role: 'storyboard',
    ...await fileDescriptor(resolve(root, 'output/storyboard.html'),
      'storyboard.html', 'text/html'),
  }];
  for (const scene of ir.scenes as JsonObject[]) {
    const path = `storyboard-${scene.id}.png`;
    await writeFile(resolve(root, 'output', path), Buffer.from(`PNG-${scene.id}`));
    artifacts.push({role: 'storyboard', ...await fileDescriptor(
      resolve(root, 'output', path), path, 'image/png')});
  }
  await atomicWriteJson(resolve(root, 'output/review.json'), {
    schema_version: '1.0', task_id: request.task_id,
    status: 'awaiting_storyboard_review', lesson_ir_revision: 1,
    lesson_ir: await fileDescriptor(resolve(root, 'output/lesson.ir.json'),
      'lesson.ir.json', 'application/json'),
    artifacts,
  });
  await ensureTaskState(root);
  return root;
}

function fakeEffects(overrides: {
  prepare?: (taskRoot: string) => Promise<void>;
  render?: (taskRoot: string) => Promise<void>;
} = {}): WorkerEffects {
  return {
    prepareAudio: async taskRoot => {
      await (overrides.prepare?.(taskRoot) ?? writeFile(
        resolve(taskRoot, 'work/audio-manifest.json'), '{}'));
      return {};
    },
    render: async taskRoot => {
      await overrides.render?.(taskRoot);
      return {};
    },
    validateAudio: async () => ({}),
    validateResult: async () => ({}),
  };
}

async function waitForState(root: string, predicate: (state: JsonObject) => boolean) {
  for (let index = 0; index < 100; index += 1) {
    const state = await loadTaskState(root);
    if (state && predicate(state)) return state;
    await new Promise(resolveWait => setTimeout(resolveWait, 10));
  }
  throw new Error('timed out waiting for task state');
}

async function startHoldWorker(root: string) {
  const child = spawn(process.execPath, [...process.execArgv.filter(value => value !== '--test'),
    resolve(skillRoot(), 'tests/worker-hold.ts'), root], {
    detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'inherit'],
  });
  await new Promise<void>((resolveReady, reject) => {
    child.once('error', reject);
    child.stdout!.on('data', chunk => {
      if (String(chunk).includes('READY')) resolveReady();
    });
    child.once('exit', code => reject(new Error(`hold worker exited early: ${code}`)));
  });
  return child;
}
