import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {cp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import {
  buildPersistedStoryboard,
  ensureTaskState,
  grantApproval,
} from '../scripts/task-worker.js';
import {payloadDigest, sourceSnapshotDigest} from '../scripts/video-operation.js';
import {eventPath} from '../scripts/task-state.js';
import {skillRoot, sha256Bytes} from '../scripts/lib.js';

const integrationEnabled = process.env.PADNOTE_RUN_BUILTIN_INTEGRATION === '1';
const skill = skillRoot();
const fixture = resolve(skill, 'tests/fixtures/formula-note');
const outputRoot = resolve(skill, '.local-output/builtin-engine-offline-tests');
const engine = resolve(skill, 'scripts/builtin-engine.ts');
const dashscopeKey = 'fake-offline-test-key-never-real';

test('built-in produce CLI returns the exact inspection, receipt, and validated artifact envelope', {
  skip: integrationEnabled ? false : 'set PADNOTE_RUN_BUILTIN_INTEGRATION=1 on a host with the local video runtime',
  timeout: 180_000,
}, async t => {
  await rm(outputRoot, {recursive: true, force: true});
  await mkdir(outputRoot, {recursive: true});
  t.after(async () => rm(outputRoot, {recursive: true, force: true}));
  const taskRoot = resolve(outputRoot, `task-${randomUUID()}`);
  await mkdir(resolve(taskRoot, 'work'), {recursive: true});
  await mkdir(resolve(taskRoot, 'output'), {recursive: true});
  await cp(resolve(fixture, 'request.json'), resolve(taskRoot, 'request.json'));
  await cp(resolve(fixture, 'input'), resolve(taskRoot, 'input'), {recursive: true});
  await cp(resolve(fixture, 'expected/lesson.ir.json'), resolve(taskRoot, 'output/lesson.ir.json'));
  await ensureTaskState(taskRoot);
  await buildPersistedStoryboard(taskRoot, 1);
  const approved = await grantApproval(taskRoot, 1);
  const operation = {
    operation_id: randomUUID(), attempt_id: randomUUID(), action: 'produce' as const,
    payload_digest: '', source_snapshot_digest: await sourceSnapshotDigest(taskRoot),
    request_sha256: approved.request_sha256!, input_event_cursor: approved.event_cursor,
    revision: approved.revision!, review_sha256: approved.review_sha256!,
    lesson_ir_sha256: approved.lesson_ir_sha256!, allow_cloud_tts: true as const,
  };
  operation.payload_digest = payloadDigest('produce', {
    revision: operation.revision, event_cursor: operation.input_event_cursor,
    review_sha256: operation.review_sha256, lesson_ir_sha256: operation.lesson_ir_sha256,
    allow_cloud_tts: true,
  });

  const helpers = await createOfflineNetworkGuard();
  const nodeWrapper = resolve(outputRoot, 'node-offline-wrapper');
  const parentPreload = resolve(outputRoot, 'offline-preload.mjs');
  await writeFile(parentPreload,
    `import process from 'node:process';\nprocess.execPath = ${JSON.stringify(nodeWrapper)};\n`);
  const quotedNode = shellQuote(process.execPath);
  const quotedChildMock = shellQuote(helpers);
  await writeFile(nodeWrapper, `#!/bin/sh\nexec ${quotedNode} --import ${quotedChildMock} "$@"\n`);
  await (await import('node:fs/promises')).chmod(nodeWrapper, 0o700);

  const input = {
    schema_version: 1,
    action: 'produce',
    task_root: taskRoot,
    task_id: 'task-fixture-formula',
    request_sha256: operation.request_sha256,
    revision: operation.revision,
    event_cursor: operation.input_event_cursor,
    operation: {
      action: operation.action, operation_id: operation.operation_id, attempt_id: operation.attempt_id,
      payload_digest: operation.payload_digest, source_snapshot_digest: operation.source_snapshot_digest,
      review_sha256: operation.review_sha256, lesson_ir_sha256: operation.lesson_ir_sha256,
      allow_cloud_tts: operation.allow_cloud_tts,
    },
    provider: {
      base_url: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      model: 'qwen-plus', response_mode: 'json_object', region: 'china',
      tts_model: 'qwen3-tts-instruct-flash', tts_voice: 'Maia',
    },
    credential: {api_key: dashscopeKey},
  };
  const child = spawnSync(process.execPath,
    ['--import', `tsx`, '--import', parentPreload, engine], {
      input: JSON.stringify(input), encoding: 'utf8', timeout: 175_000, maxBuffer: 1024 * 1024,
      env: {...process.env, NODE_OPTIONS: '', PADNOTE_OFFLINE_TEST: '1'},
    });
  assert.equal(child.status, 0, `${child.stderr}\n${child.stdout}`);
  assert.equal(child.stderr, '');
  assert.equal(child.stdout.trim().split('\n').length, 1);
  const response = JSON.parse(child.stdout) as Record<string, any>;
  assert.deepEqual(Object.keys(response).sort(), ['artifacts', 'inspection', 'ok', 'receipt']);
  assert.equal(response.ok, true);
  assert.equal(response.inspection.protocol_version, 1);
  assert.equal(response.inspection.task_id, input.task_id);
  assert.equal(response.inspection.status, 'completed');
  assert.equal(response.inspection.phase, 'completed');
  assert.ok(response.inspection.approval.consumed_at);
  assert.equal(response.receipt.operation_id, operation.operation_id);
  assert.equal(response.receipt.attempt_id, operation.attempt_id);
  assert.equal(response.receipt.payload_digest, operation.payload_digest);
  assert.equal(response.receipt.source_snapshot_digest, operation.source_snapshot_digest);
  assert.equal(response.receipt.request_sha256, operation.request_sha256);
  assert.equal(response.receipt.result_event_cursor, response.inspection.event_cursor);
  assert.ok(Array.isArray(response.artifacts) && response.artifacts.length >= 5);
  assert.ok(response.artifacts.some((artifact: any) => artifact.media_type === 'video/mp4'));
  assert.equal(child.stdout.includes(dashscopeKey), false);

  const reconcileArgs = ['reconcile-operation', taskRoot,
    '--task-id', input.task_id, '--request-sha256', operation.request_sha256,
    '--event-cursor', String(operation.input_event_cursor), '--revision', String(operation.revision),
    '--review-sha256', operation.review_sha256, '--lesson-ir-sha256', operation.lesson_ir_sha256,
    '--allow-cloud-tts', 'true', '--operation-id', operation.operation_id,
    '--attempt-id', operation.attempt_id, '--action', 'produce',
    '--payload-digest', operation.payload_digest, '--source-snapshot-sha256', operation.source_snapshot_digest];
  const worker = resolve(skill, 'scripts/task-worker.ts');
  const reconcile = () => spawnSync(process.execPath, ['--import', 'tsx', worker, ...reconcileArgs], {
    cwd: skill, encoding: 'utf8', timeout: 120_000,
    env: {...process.env, NODE_OPTIONS: '', PADNOTE_OFFLINE_TEST: '1'},
  });
  const proofChild = reconcile();
  assert.equal(proofChild.status, 0, proofChild.stderr);
  const proof = JSON.parse(proofChild.stdout) as Record<string, any>;
  assert.equal(proof.outcome, 'verified_completed');
  assert.equal(proof.result_manifest_sha256, sha256Bytes(await readFile(resolve(taskRoot, 'output/result.json'))));
  assert.deepEqual(proof.artifacts.map((artifact: any) => artifact.role).sort(),
    ['captions', 'qa_report', 'render_manifest', 'thumbnail', 'video']);
  assert.ok(proof.artifacts.every((artifact: any) =>
    Object.keys(artifact).sort().join(',') === 'id,media_type,path,role,sha256,size_bytes'));
  assert.equal(proof.artifacts.some((artifact: any) => artifact.path === 'result.json'), false);

  const audit = resolve(taskRoot, 'work/task-events.ndjson');
  const originalEvents = await readFile(audit);
  const lines = originalEvents.toString('utf8').trimEnd().split('\n');
  const last = JSON.parse(lines.at(-1)!);
  last.result_manifest_sha256 = '0'.repeat(64);
  lines[lines.length - 1] = JSON.stringify(last);
  await writeFile(audit, `${lines.join('\n')}\n`);
  const badBinding = JSON.parse(reconcile().stdout) as Record<string, any>;
  assert.equal(badBinding.outcome, 'unconfirmed');
  assert.equal(badBinding.artifacts, undefined);
  await writeFile(audit, originalEvents);

  const resultPath = resolve(taskRoot, 'output/result.json');
  const originalManifest = await readFile(resultPath);
  const damagedManifest = JSON.parse(originalManifest.toString('utf8'));
  damagedManifest.artifacts.push({...damagedManifest.artifacts[0], id: 'extra-artifact'});
  await writeFile(resultPath, JSON.stringify(damagedManifest));
  const malformedArtifacts = JSON.parse(reconcile().stdout) as Record<string, any>;
  assert.equal(malformedArtifacts.outcome, 'unconfirmed');
  assert.equal(malformedArtifacts.artifacts, undefined);
});

async function createOfflineNetworkGuard(): Promise<string> {
  const path = resolve(outputRoot, 'mock-provider.mjs');
  await writeFile(path, `
globalThis.fetch = async input => {
  const url = new URL(String(input));
  if (url.hostname === 'dashscope.aliyuncs.com'
      && url.pathname === '/api/v1/services/aigc/multimodal-generation/generation') {
    return Response.json({output:{audio:{url:'http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/fake/scene.wav?Signature=fake'}}});
  }
  if (url.hostname === 'dashscope-result-bj.oss-cn-beijing.aliyuncs.com'
      && url.pathname === '/fake/scene.wav') {
    const rate = 24000, samples = rate;
    const wav = Buffer.alloc(44 + samples * 2);
    wav.write('RIFF', 0); wav.writeUInt32LE(wav.length - 8, 4); wav.write('WAVEfmt ', 8);
    wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22);
    wav.writeUInt32LE(rate, 24); wav.writeUInt32LE(rate * 2, 28); wav.writeUInt16LE(2, 32);
    wav.writeUInt16LE(16, 34); wav.write('data', 36); wav.writeUInt32LE(samples * 2, 40);
    for (let index = 0; index < samples; index++) {
      wav.writeInt16LE(Math.round(Math.sin(index * 2 * Math.PI * 440 / rate) * 12000), 44 + index * 2);
    }
    return new Response(wav, {status:200, headers:{'content-length':String(wav.length)}});
  }
  throw new Error('offline test network guard rejected request');
};
`);
  return path;
}

function shellQuote(value: string): string {
  return `'${value.replaceAll("'", "'\\''")}'`;
}
