import {mkdtemp, readFile, rm, stat, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import assert from 'node:assert/strict';

const node = process.execPath;
const adapter = resolve(import.meta.dirname, '../scripts/dashscope-tts.mjs');

test('Qwen3 TTS uses documented REST schema and safely upgrades only trusted signed OSS URL', async () => {
  const root = await mkdtemp(resolve(tmpdir(), 'padnote-tts-contract-'));
  try {
    const output = resolve(root, 'scene.wav');
    const capture = resolve(root, 'capture.json');
    const preload = await writeMockFetch(root, capture);
    const key = 'fake-test-key-never-a-real-secret';
    const child = runAdapter(preload, output, capture, key);
    assert.equal(child.status, 0, child.stderr.toString());
    assert.equal(child.stderr.length, 0);
    assert.equal(child.stdout.toString().includes(key), false);
    assert.equal(child.stdout.toString().includes('Signature='), false);
    const request = JSON.parse(await readFile(capture, 'utf8'));
    assert.equal(request.calls.length, 2);
    assert.equal(request.calls[0].url, 'https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation');
    assert.equal(request.calls[0].authorization, `Bearer ${key}`);
    assert.deepEqual(JSON.parse(request.calls[0].body), {
      model: 'qwen3-tts-instruct-flash',
      input: {text: '一段短旁白', voice: 'Maia', language_type: 'Chinese',
        instructions: 'calm', optimize_instructions: true},
    });
    assert.equal(request.calls[0].redirect, 'error');
    assert.equal(request.calls[1].url, 'https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/path/audio.wav?Expires=123&Signature=private');
    assert.equal(request.calls[1].redirect, 'error');
    const audio = await readFile(output);
    assert.equal(audio.toString('ascii', 0, 4), 'RIFF');
    assert.equal((await stat(output)).size, 44);
  } finally { await rm(root, {recursive: true, force: true}); }
});

test('Qwen TTS accepts the observed exact Beijing output bucket', async () => {
  const root = await mkdtemp(resolve(tmpdir(), 'padnote-tts-observed-host-'));
  try {
    const output = resolve(root, 'scene.wav');
    const capture = resolve(root, 'capture.json');
    const preload = await writeMockFetch(root, capture);
    const child = runAdapter(preload, output, capture, 'fake-test-key', 'observed_host');
    assert.equal(child.status, 0, child.stderr.toString());
    const request = JSON.parse(await readFile(capture, 'utf8'));
    assert.equal(request.calls[1].url, 'https://dashscope-a717.oss-cn-beijing.aliyuncs.com/path/audio.wav?Expires=123&Signature=private');
    assert.equal((await stat(output)).size, 44);
  } finally { await rm(root, {recursive: true, force: true}); }
});

test('Qwen TTS accepts the exact documented Wulanchabu output bucket', async () => {
  const root = await mkdtemp(resolve(tmpdir(), 'padnote-tts-wlcb-host-'));
  try {
    const output = resolve(root, 'scene.wav');
    const capture = resolve(root, 'capture.json');
    const preload = await writeMockFetch(root, capture);
    const child = runAdapter(preload, output, capture, 'fake-test-key', 'wlcb_host');
    assert.equal(child.status, 0, child.stderr.toString());
    const request = JSON.parse(await readFile(capture, 'utf8'));
    assert.equal(request.calls[1].url, 'https://dashscope-result-wlcb.oss-cn-wulanchabu.aliyuncs.com/path/audio.wav?Expires=123&Signature=private');
    assert.equal((await stat(output)).size, 44);
  } finally { await rm(root, {recursive: true, force: true}); }
});

test('Qwen TTS rejects untrusted host variants, userinfo, ports, and redirects without following them', async () => {
  const root = await mkdtemp(resolve(tmpdir(), 'padnote-tts-url-boundary-'));
  try {
    const key = 'fake-test-key';
    for (const scenario of ['lookalike', 'userinfo', 'port']) {
      const output = resolve(root, `${scenario}.wav`);
      const capture = resolve(root, `${scenario}.json`);
      const preload = await writeMockFetch(root, capture);
      const child = runAdapter(preload, output, capture, key, scenario);
      assert.notEqual(child.status, 0, scenario);
      const result = JSON.parse(child.stdout.toString());
      assert.equal(result.error.code, 'tts_audio_url_rejected', scenario);
      assert.equal(JSON.stringify(result).includes('Signature='), false, scenario);
      assert.equal(JSON.parse(await readFile(capture, 'utf8')).calls.length, 1, scenario);
    }
    const capture = resolve(root, 'redirect.json');
    const preload = await writeMockFetch(root, capture);
    const child = runAdapter(preload, resolve(root, 'redirect.wav'), capture, key, 'redirect');
    assert.notEqual(child.status, 0);
    const result = JSON.parse(child.stdout.toString());
    assert.equal(result.error.code, 'tts_audio_download_http_error');
    assert.equal(JSON.stringify(result).includes('Signature='), false);
    const request = JSON.parse(await readFile(capture, 'utf8'));
    assert.equal(request.calls.length, 2);
    assert.equal(request.calls[1].redirect, 'error');
  } finally { await rm(root, {recursive: true, force: true}); }
});

test('Qwen TTS provider failures do not expose credential or signed URL', async () => {
  const root = await mkdtemp(resolve(tmpdir(), 'padnote-tts-error-'));
  try {
    const output = resolve(root, 'scene.wav');
    const capture = resolve(root, 'capture.json');
    const key = 'fake-test-key-never-a-real-secret';
    const scenarios = [
      ['provider_http', 'tts_provider_http_error', 'provider_response', 429],
      ['url_rejected', 'tts_audio_url_rejected', 'audio_url', undefined],
      ['download_http', 'tts_audio_download_http_error', 'audio_download', 503],
      ['download_network', 'tts_audio_download_failed', 'audio_download', undefined],
      ['format_invalid', 'tts_audio_format_invalid', 'audio_format', undefined],
    ] as const;
    for (const [scenario, code, stage, status] of scenarios) {
      const preload = await writeMockFetch(root, capture);
      const child = runAdapter(preload, output, capture, key, scenario);
      assert.notEqual(child.status, 0, scenario);
      const diagnostic = child.stdout.toString() + child.stderr.toString();
      assert.equal(diagnostic.includes(key), false, scenario);
      assert.equal(diagnostic.includes('Signature='), false, scenario);
      assert.equal(diagnostic.includes('provider-private-error'), false, scenario);
      const lines = child.stdout.toString().trim().split('\n');
      assert.equal(lines.length, 1, scenario);
      const result = JSON.parse(lines[0]!);
      assert.deepEqual(Object.keys(result).sort(), ['error', 'ok'], scenario);
      assert.equal(result.ok, false, scenario);
      assert.equal(result.error.code, code, scenario);
      assert.equal(result.error.stage, stage, scenario);
      assert.equal(result.error.external_effect_possible, true, scenario);
      if (status !== undefined) assert.equal(result.error.http_status, status, scenario);
      assert.equal(JSON.stringify(result).includes('Signature='), false, scenario);
      assert.equal((await stat(capture)).isFile(), true);
    }
  } finally { await rm(root, {recursive: true, force: true}); }
});

function runAdapter(preload: string, output: string, capture: string, key: string, scenario = 'ok') {
  return spawnSync(node, ['--import', preload, adapter], {
    input: JSON.stringify({api_key: key, base_url: 'https://dashscope.aliyuncs.com/api/v1',
      model: 'qwen3-tts-instruct-flash', voice: 'Maia', language: 'Chinese',
      instructions: 'calm', optimize_instructions: true}),
    timeout: 5000,
    env: {PATH: process.env.PATH, MOCK_CAPTURE: capture, MOCK_SCENARIO: scenario,
      PADNOTE_TTS_INPUT_MODE: 'stdin', PADNOTE_TTS_TEXT: '一段短旁白', PADNOTE_TTS_OUTPUT: output},
  });
}

async function writeMockFetch(root: string, capture: string): Promise<string> {
  const preload = resolve(root, 'mock-fetch.mjs');
  await writeFile(preload, `
import {writeFile} from 'node:fs/promises';
const calls=[];
globalThis.fetch=async (input, init={})=>{
 const url=String(input); calls.push({url,authorization:init.headers?.Authorization,body:init.body,redirect:init.redirect});
 await writeFile(process.env.MOCK_CAPTURE, JSON.stringify({calls}));
 if (url.includes('/generation')) {
   if (process.env.MOCK_SCENARIO==='provider_http') return new Response('provider-private-error Signature=private', {status:429});
   if (process.env.MOCK_SCENARIO==='url_rejected') return Response.json({output:{audio:{url:'https://attacker.invalid/audio.wav?Signature=private'}}});
   const urls={
    lookalike:'https://dashscope-a717.oss-cn-beijing.aliyuncs.com.evil.test/path/audio.wav?Signature=private',
    userinfo:'https://user:pass@dashscope-a717.oss-cn-beijing.aliyuncs.com/path/audio.wav?Signature=private',
    port:'https://dashscope-a717.oss-cn-beijing.aliyuncs.com:8443/path/audio.wav?Signature=private',
    observed_host:'http://dashscope-a717.oss-cn-beijing.aliyuncs.com/path/audio.wav?Expires=123&Signature=private',
    wlcb_host:'http://dashscope-result-wlcb.oss-cn-wulanchabu.aliyuncs.com/path/audio.wav?Expires=123&Signature=private',
   };
   const url=urls[process.env.MOCK_SCENARIO] || 'http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/path/audio.wav?Expires=123&Signature=private';
   return Response.json({status_code:200,output:{finish_reason:'stop',audio:{url}}});
 }
 if (process.env.MOCK_SCENARIO==='redirect') return new Response(null, {status:302,headers:{location:'https://attacker.invalid/audio.wav'}});
 if (process.env.MOCK_SCENARIO==='download_http') return new Response('private download error Signature=private', {status:503});
 if (process.env.MOCK_SCENARIO==='download_network') throw new Error('network error Signature=private');
 if (process.env.MOCK_SCENARIO==='format_invalid') return new Response('not a WAV Signature=private', {status:200});
 const wav=Buffer.alloc(44); wav.write('RIFF',0); wav.writeUInt32LE(36,4); wav.write('WAVE',8); wav.write('fmt ',12); wav.writeUInt32LE(16,16); wav.writeUInt16LE(1,20); wav.writeUInt16LE(1,22); wav.writeUInt32LE(24000,24); wav.writeUInt32LE(48000,28); wav.writeUInt16LE(2,32); wav.writeUInt16LE(16,34); wav.write('data',36); wav.writeUInt32LE(0,40);
 return new Response(wav, {status:200,headers:{'content-length':'44'}});
};
`);
  return preload;
}
