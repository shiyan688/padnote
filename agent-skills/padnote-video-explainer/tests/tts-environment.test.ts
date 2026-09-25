import assert from 'node:assert/strict';
import {chmod, cp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import test from 'node:test';
import {prepareAudio} from '../scripts/prepare-audio.js';
import {buildTtsChildEnvironment} from '../scripts/tts-environment.js';
import {sha256Bytes, sha256File, skillRoot, type JsonObject} from '../scripts/lib.js';
import type {AudioInputBinding} from '../scripts/validate-audio.js';

test('TTS child environment keeps exact runtime, provider, and scene fields only', () => {
  const child = buildTtsChildEnvironment({
    sceneId: 'scene-1',
    text: '旁白',
    language: 'zh-CN',
    voiceProfile: 'teacher-neutral',
    speed: '1',
    output: '/tmp/scene-1.wav',
  }, {
    PATH: '/fixture/bin',
    LANG: 'zh_CN.UTF-8',
    PADNOTE_TTS_ENV_FILE: '/private/tts.env',
    DASHSCOPE_API_KEY: 'tts-key',
    DASHSCOPE_TTS_MODEL: 'tts-model',
    PADNOTE_UNRELATED_SECRET: 'must-not-pass',
    OPENAI_API_KEY: 'must-not-pass',
    AWS_SECRET_ACCESS_KEY: 'must-not-pass',
    NODE_OPTIONS: '--require=/tmp/untrusted.cjs',
    HOME: '/private/home',
  });
  assert.deepEqual(child, {
    PATH: '/fixture/bin',
    LANG: 'zh_CN.UTF-8',
    PADNOTE_TTS_ENV_FILE: '/private/tts.env',
    DASHSCOPE_API_KEY: 'tts-key',
    DASHSCOPE_TTS_MODEL: 'tts-model',
    PADNOTE_TTS_SCENE_ID: 'scene-1',
    PADNOTE_TTS_TEXT: '旁白',
    PADNOTE_TTS_LANGUAGE: 'zh-CN',
    PADNOTE_TTS_VOICE_PROFILE: 'teacher-neutral',
    PADNOTE_TTS_SPEED: '1',
    PADNOTE_TTS_OUTPUT: '/tmp/scene-1.wav',
  });
});

test('audio preparation adapter cannot read unrelated host secrets', async () => {
  const root = resolve(skillRoot(), '.local-output/tests/tts-environment');
  await rm(root, {recursive: true, force: true});
  await cp(resolve(skillRoot(), 'tests/fixtures/formula-note'), root, {recursive: true});
  await mkdir(resolve(root, 'output'), {recursive: true});
  await cp(resolve(root, 'expected/lesson.ir.json'), resolve(root, 'output/lesson.ir.json'));
  const adapter = resolve(root, 'fixture-tts.mjs');
  const observations = resolve(root, 'observed-environment.ndjson');
  await writeFile(adapter, fixtureAdapterSource(observations));
  await chmod(adapter, 0o700);

  const previous = new Map<string, string | undefined>();
  const configured = {
    PADNOTE_TTS_COMMAND: adapter,
    PADNOTE_TTS_ENV_FILE: '/private/tts-only.env',
    DASHSCOPE_API_KEY: 'fixture-tts-key',
    DASHSCOPE_BASE_URL: 'https://tts.example.test',
    PADNOTE_UNRELATED_SECRET: 'padnote-secret-must-not-pass',
    OPENAI_API_KEY: 'openai-secret-must-not-pass',
    AWS_SECRET_ACCESS_KEY: 'aws-secret-must-not-pass',
    NODE_OPTIONS: '--definitely-not-a-valid-node-option',
  };
  for (const [name, value] of Object.entries(configured)) {
    previous.set(name, process.env[name]);
    process.env[name] = value;
  }
  try {
    await prepareAudio(root);
  } finally {
    for (const [name, value] of previous) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  }

  const lines = (await readFile(observations, 'utf8')).trim().split('\n');
  assert.ok(lines.length > 0);
  for (const line of lines) {
    const environment = JSON.parse(line) as Record<string, string>;
    assert.equal(environment.DASHSCOPE_API_KEY, 'fixture-tts-key');
    assert.equal(environment.PADNOTE_TTS_ENV_FILE, '/private/tts-only.env');
    assert.ok(environment.PADNOTE_TTS_SCENE_ID);
    assert.ok(environment.PADNOTE_TTS_TEXT);
    for (const forbidden of [
      'PADNOTE_TTS_COMMAND',
      'PADNOTE_UNRELATED_SECRET',
      'OPENAI_API_KEY',
      'AWS_SECRET_ACCESS_KEY',
      'NODE_OPTIONS',
      'HOME',
    ]) {
      assert.equal(environment[forbidden], undefined, `${forbidden} leaked to TTS adapter`);
    }
  }
});

test('mixed provider file exposes only DashScope fields to a real adapter child', async () => {
  const root = resolve(skillRoot(), '.local-output/tests/tts-provider-file');
  await rm(root, {recursive: true, force: true});
  await cp(resolve(skillRoot(), 'tests/fixtures/formula-note'), root, {recursive: true});
  await mkdir(resolve(root, 'output'), {recursive: true});
  await cp(resolve(root, 'expected/lesson.ir.json'), resolve(root, 'output/lesson.ir.json'));
  const providerFile = resolve(root, 'mixed-providers.env');
  await writeFile(providerFile, [
    'DASHSCOPE_API_KEY=dashscope-only',
    'DASHSCOPE_BASE_URL=https://dashscope.example.test',
    'DASHSCOPE_TTS_MODEL=fixture-model',
    'DASHSCOPE_TTS_VOICE=fixture-voice',
    'OPENAI_API_KEY=must-not-load',
    'OTHER_SECRET=must-not-load',
    '',
  ].join('\n'));
  const observations = resolve(root, 'observed-provider.json');
  const adapter = resolve(root, 'provider-file-adapter.mjs');
  const providerModule = resolve(skillRoot(), 'scripts/provider-env.mjs');
  await writeFile(adapter, providerFileAdapterSource(observations, providerModule));
  await chmod(adapter, 0o700);

  const names = ['PADNOTE_TTS_COMMAND', 'PADNOTE_TTS_ENV_FILE', 'DASHSCOPE_API_KEY',
    'DASHSCOPE_BASE_URL', 'DASHSCOPE_TTS_MODEL', 'DASHSCOPE_TTS_VOICE',
    'OPENAI_API_KEY', 'OTHER_SECRET'];
  const previous = new Map(names.map(name => [name, process.env[name]]));
  for (const name of names) delete process.env[name];
  process.env.PADNOTE_TTS_COMMAND = adapter;
  process.env.PADNOTE_TTS_ENV_FILE = providerFile;
  try {
    await prepareAudio(root);
  } finally {
    for (const [name, value] of previous) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  }

  const observed = JSON.parse(await readFile(observations, 'utf8')) as {
    selected: Record<string, string>; processOther?: string; processOpenAi?: string;
  };
  assert.equal(observed.selected.DASHSCOPE_API_KEY, 'dashscope-only');
  assert.equal(observed.selected.DASHSCOPE_TTS_MODEL, 'fixture-model');
  assert.equal(observed.selected.OPENAI_API_KEY, undefined);
  assert.equal(observed.selected.OTHER_SECRET, undefined);
  assert.equal(observed.processOther, undefined);
  assert.equal(observed.processOpenAi, undefined);
});

test('provider file parser bounds reads and redacts invalid quoted secrets', async () => {
  const root = resolve(skillRoot(), '.local-output/tests/tts-provider-parser');
  await rm(root, {recursive: true, force: true});
  await mkdir(root, {recursive: true});
  const providerModule = await import(pathToFileURL(
    resolve(skillRoot(), 'scripts/provider-env.mjs')).href);

  const oversized = resolve(root, 'oversized.env');
  await writeFile(oversized, `DASHSCOPE_API_KEY=${'x'.repeat(70 * 1024)}\n`);
  await assert.rejects(providerModule.readSelectedEnvironmentFile(oversized),
    /environment file is too large/);

  const secret = 'TEST_SECRET_MUST_NOT_APPEAR_779';
  const invalid = resolve(root, 'invalid.env');
  await writeFile(invalid, `DASHSCOPE_API_KEY="${secret}\\q"\n`);
  let message = '';
  try {
    await providerModule.readSelectedEnvironmentFile(invalid);
  } catch (error) {
    message = error instanceof Error ? error.message : String(error);
  }
  assert.match(message, /invalid quoted value/);
  assert.equal(message.includes(secret), false);
});

test('scene checkpoints resume completed clips and bind audio to revision and voice', async () => {
  const root = resolve(skillRoot(), '.local-output/tests/tts-scene-checkpoints');
  await rm(root, {recursive: true, force: true});
  await cp(resolve(skillRoot(), 'tests/fixtures/formula-note'), root, {recursive: true});
  await mkdir(resolve(root, 'output'), {recursive: true});
  const irPath = resolve(root, 'output/lesson.ir.json');
  await cp(resolve(root, 'expected/lesson.ir.json'), irPath);
  const calls = resolve(root, 'tts-calls.ndjson');
  const failMarker = resolve(root, 'scene-02-failed-once');
  const adapter = resolve(root, 'checkpoint-adapter.mjs');
  await writeFile(adapter, checkpointAdapterSource(calls, failMarker));
  await chmod(adapter, 0o700);
  const previous = process.env.PADNOTE_TTS_COMMAND;
  process.env.PADNOTE_TTS_COMMAND = adapter;
  try {
    const requestPath = resolve(root, 'request.json');
    const request = JSON.parse(await readFile(requestPath, 'utf8')) as JsonObject;
    const binding = await audioBinding(request, requestPath, irPath);
    await assert.rejects(prepareAudio(root, {irPath, inputBinding: binding}), /Command failed/);
    const manifest = await prepareAudio(root, {irPath, inputBinding: binding});
    assert.deepEqual(manifest.input_binding, binding);
    let records = (await readFile(calls, 'utf8')).trim().split('\n')
      .map(line => JSON.parse(line) as JsonObject);
    assert.equal(records.filter(record => record.scene === 'scene-01').length, 1);
    assert.equal(records.filter(record => record.scene === 'scene-02').length, 2);
    assert.equal(new Set(records.filter(record => record.scene === 'scene-02')
      .map(record => record.idempotency)).size, 1);

    request.voice.profile = 'changed-voice';
    await writeFile(requestPath, `${JSON.stringify(request, null, 2)}\n`);
    const changedBinding = await audioBinding(request, requestPath, irPath);
    assert.notEqual(changedBinding.voice_sha256, binding.voice_sha256);
    await prepareAudio(root, {irPath, inputBinding: changedBinding});
    records = (await readFile(calls, 'utf8')).trim().split('\n')
      .map(line => JSON.parse(line) as JsonObject);
    assert.equal(records.filter(record => record.scene === 'scene-01').length, 2,
      'a changed voice must not reuse prior audio');
  } finally {
    if (previous === undefined) delete process.env.PADNOTE_TTS_COMMAND;
    else process.env.PADNOTE_TTS_COMMAND = previous;
  }
});

async function audioBinding(
  request: JsonObject,
  requestPath: string,
  irPath: string,
): Promise<AudioInputBinding> {
  return {
    task_id: request.task_id,
    revision: 1,
    request_sha256: await sha256File(requestPath),
    lesson_ir_sha256: await sha256File(irPath),
    voice_sha256: sha256Bytes(JSON.stringify(request.voice)),
  };
}

function fixtureAdapterSource(observations: string): string {
  return `#!/usr/bin/env node
import {appendFile, writeFile} from 'node:fs/promises';
await appendFile(${JSON.stringify(observations)}, JSON.stringify(process.env) + '\\n');
const sampleRate = 8000;
const samples = 800;
const dataLength = samples * 2;
const wav = Buffer.alloc(44 + dataLength);
wav.write('RIFF', 0);
wav.writeUInt32LE(36 + dataLength, 4);
wav.write('WAVEfmt ', 8);
wav.writeUInt32LE(16, 16);
wav.writeUInt16LE(1, 20);
wav.writeUInt16LE(1, 22);
wav.writeUInt32LE(sampleRate, 24);
wav.writeUInt32LE(sampleRate * 2, 28);
wav.writeUInt16LE(2, 32);
wav.writeUInt16LE(16, 34);
wav.write('data', 36);
wav.writeUInt32LE(dataLength, 40);
for (let index = 0; index < samples; index += 1) {
  wav.writeInt16LE(Math.round(4000 * Math.sin(2 * Math.PI * 440 * index / sampleRate)), 44 + index * 2);
}
await writeFile(process.env.PADNOTE_TTS_OUTPUT, wav);
`;
}

function providerFileAdapterSource(observations: string, providerModule: string): string {
  return `#!/usr/bin/env node
import {writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
const {loadDashScopeEnvironment} = await import(pathToFileURL(${JSON.stringify(providerModule)}));
const selected = await loadDashScopeEnvironment(process.env);
await writeFile(${JSON.stringify(observations)}, JSON.stringify({
  selected,
  processOther: process.env.OTHER_SECRET,
  processOpenAi: process.env.OPENAI_API_KEY,
}));
const sampleRate = 8000;
const samples = 800;
const dataLength = samples * 2;
const wav = Buffer.alloc(44 + dataLength);
wav.write('RIFF', 0);
wav.writeUInt32LE(36 + dataLength, 4);
wav.write('WAVEfmt ', 8);
wav.writeUInt32LE(16, 16);
wav.writeUInt16LE(1, 20);
wav.writeUInt16LE(1, 22);
wav.writeUInt32LE(sampleRate, 24);
wav.writeUInt32LE(sampleRate * 2, 28);
wav.writeUInt16LE(2, 32);
wav.writeUInt16LE(16, 34);
wav.write('data', 36);
wav.writeUInt32LE(dataLength, 40);
for (let index = 0; index < samples; index += 1) {
  wav.writeInt16LE(Math.round(4000 * Math.sin(2 * Math.PI * 440 * index / sampleRate)), 44 + index * 2);
}
await writeFile(process.env.PADNOTE_TTS_OUTPUT, wav);
`;
}

function checkpointAdapterSource(calls: string, failMarker: string): string {
  return `#!/usr/bin/env node
import {appendFile, access, writeFile} from 'node:fs/promises';
await appendFile(${JSON.stringify(calls)}, JSON.stringify({
  scene: process.env.PADNOTE_TTS_SCENE_ID,
  idempotency: process.env.PADNOTE_TTS_IDEMPOTENCY_KEY,
}) + '\\n');
if (process.env.PADNOTE_TTS_SCENE_ID === 'scene-02') {
  try { await access(${JSON.stringify(failMarker)}); }
  catch { await writeFile(${JSON.stringify(failMarker)}, 'failed'); process.exit(23); }
}
const sampleRate = 8000;
const samples = 800;
const dataLength = samples * 2;
const wav = Buffer.alloc(44 + dataLength);
wav.write('RIFF', 0); wav.writeUInt32LE(36 + dataLength, 4); wav.write('WAVEfmt ', 8);
wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22);
wav.writeUInt32LE(sampleRate, 24); wav.writeUInt32LE(sampleRate * 2, 28);
wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34); wav.write('data', 36);
wav.writeUInt32LE(dataLength, 40);
for (let index = 0; index < samples; index += 1) {
  wav.writeInt16LE(Math.round(4000 * Math.sin(2 * Math.PI * 440 * index / sampleRate)), 44 + index * 2);
}
await writeFile(process.env.PADNOTE_TTS_OUTPUT, wav);
`;
}
