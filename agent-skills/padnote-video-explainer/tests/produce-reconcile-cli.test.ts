import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {resolve} from 'node:path';
import {tmpdir} from 'node:os';
import test from 'node:test';
import {payloadDigest} from '../scripts/video-operation.js';
import {skillRoot} from '../scripts/lib.js';

test('reconcile CLI requires exact produce hashes and literal cloud TTS consent', () => {
  const taskRoot = resolve(tmpdir(), `padnote-reconcile-missing-${randomUUID()}`);
  const requestHash = 'a'.repeat(64), sourceHash = 'b'.repeat(64);
  const reviewHash = 'c'.repeat(64), irHash = 'd'.repeat(64);
  const parameters = {revision: 1, event_cursor: 2, review_sha256: reviewHash,
    lesson_ir_sha256: irHash, allow_cloud_tts: true};
  const payload = payloadDigest('produce', parameters);
  const common = ['--task-id', 'task-fixture-formula', '--request-sha256', requestHash,
    '--event-cursor', '2', '--revision', '1', '--review-sha256', reviewHash,
    '--lesson-ir-sha256', irHash];
  const after = ['--operation-id', randomUUID(), '--attempt-id', randomUUID(), '--action', 'produce',
    '--payload-digest', payload, '--source-snapshot-sha256', sourceHash];
  const script = resolve(skillRoot(), 'scripts/task-worker.ts');
  const nodeArgs = process.execArgv.filter(value => value !== '--test');
  const run = (flags: string[]) => spawnSync(process.execPath,
    [...nodeArgs, script, 'reconcile-operation', taskRoot, ...flags],
    {encoding: 'utf8', timeout: 10_000});

  const success = run([...common, '--allow-cloud-tts', 'true', ...after]);
  assert.equal(success.status, 0, success.stderr);
  assert.equal(JSON.parse(success.stdout).action, 'produce');

  const missing = run([...common, ...after]);
  assert.notEqual(missing.status, 0);
  assert.equal(missing.stderr, 'video operation action failed\n');

  const falseConsent = run([...common, '--allow-cloud-tts', 'false', ...after]);
  assert.notEqual(falseConsent.status, 0);
  assert.equal(falseConsent.stderr, 'video operation action failed\n');
});
