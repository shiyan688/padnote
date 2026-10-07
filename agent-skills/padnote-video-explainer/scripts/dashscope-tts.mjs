#!/usr/bin/env node
// Private stdin adapter for Alibaba Model Studio Qwen-TTS API.
import {mkdir, writeFile, rename} from 'node:fs/promises';
import {dirname} from 'node:path';

class SafeTtsFailure extends Error {
  constructor(code, stage, httpStatus) {
    super('safe TTS failure');
    this.stage = stage;
    this.code = `tts_${code}`;
    if (Number.isInteger(httpStatus) && httpStatus >= 100 && httpStatus <= 599) this.httpStatus = httpStatus;
  }
}

async function main() {
let stage = 'input';
let requestDispatched = false;
try {
const inline = await readInput();
const allowed = ['api_key', 'base_url', 'model', 'voice', 'language', 'instructions', 'optimize_instructions'];
if (!inline || Object.keys(inline).some(key => !allowed.includes(key))
    || typeof inline.api_key !== 'string' || !inline.api_key || inline.api_key.length > 4096
    || typeof inline.base_url !== 'string' || inline.base_url.length > 2048
    || !['qwen3-tts-flash', 'qwen3-tts-instruct-flash'].includes(inline.model)
    || typeof inline.voice !== 'string' || !/^[A-Za-z0-9-]{1,64}$/.test(inline.voice)
    || inline.language !== undefined && !['Auto', 'Chinese', 'English', 'German', 'Italian', 'Portuguese', 'Spanish', 'Japanese', 'Korean', 'French', 'Russian'].includes(inline.language)
    || inline.instructions !== undefined && (typeof inline.instructions !== 'string' || inline.instructions.length > 4000)
    || inline.optimize_instructions !== undefined && typeof inline.optimize_instructions !== 'boolean') {
  throw new Error('invalid private TTS input');
}
const text = process.env.PADNOTE_TTS_TEXT;
const output = process.env.PADNOTE_TTS_OUTPUT;
if (!text || Buffer.byteLength(text, 'utf8') > 1800 || !output) throw new Error('invalid bounded TTS scene');
const endpoint = new URL(`${inline.base_url.replace(/\/$/, '')}/services/aigc/multimodal-generation/generation`);
if (endpoint.protocol !== 'https:' || !isOfficialDashScopeHost(endpoint.hostname)
    || endpoint.username || endpoint.password || (endpoint.port && endpoint.port !== '443')
    || endpoint.pathname !== '/api/v1/services/aigc/multimodal-generation/generation'
    || endpoint.search || endpoint.hash) throw new Error('invalid official TTS endpoint');
const input = {text, voice: inline.voice, language_type: inline.language || 'Auto'};
if (inline.instructions) input.instructions = inline.instructions;
if (inline.optimize_instructions !== undefined) input.optimize_instructions = inline.optimize_instructions === true;
stage = 'provider_request';
requestDispatched = true;
const response = await fetch(endpoint, {
  method: 'POST', headers: {Authorization: `Bearer ${inline.api_key}`, 'Content-Type': 'application/json'},
  body: JSON.stringify({model: inline.model, input}), signal: AbortSignal.timeout(60000), redirect: 'error',
});
stage = 'provider_response';
if (!response.ok) throw new SafeTtsFailure('provider_http_error', 'provider_response', response.status);
const responseBytes = await readResponseBounded(response, 64 * 1024, 'tts_provider_response_too_large');
let result;
try { result = JSON.parse(responseBytes.toString('utf8')); } catch { throw new Error('invalid Qwen TTS response'); }
stage = 'audio_url';
const audioUrl = result?.output?.audio?.url;
if (typeof audioUrl !== 'string') throw new SafeTtsFailure('audio_url_missing', stage);
let downloadUrl;
try { downloadUrl = validateAudioUrl(audioUrl); }
catch { throw new SafeTtsFailure('audio_url_rejected', stage); }
stage = 'audio_download';
let audio;
try { audio = await fetch(downloadUrl, {signal: AbortSignal.timeout(60000), redirect: 'error'}); }
catch { throw new SafeTtsFailure('audio_download_failed', stage); }
if (!audio.ok) throw new SafeTtsFailure('audio_download_http_error', stage, audio.status);
const bytes = await readResponseBounded(audio, 20 * 1024 * 1024, 'tts_audio_download_too_large');
stage = 'audio_format';
if (bytes.toString('ascii', 0, 4) !== 'RIFF' || bytes.toString('ascii', 8, 12) !== 'WAVE'
    || bytes.length < 44 || bytes.readUInt32LE(4) + 8 > bytes.length) throw new SafeTtsFailure('audio_format_invalid', stage);
stage = 'output_write';
await mkdir(dirname(output), {recursive: true});
await writeFile(`${output}.partial`, bytes, {flag: 'wx'});
await rename(`${output}.partial`, output);
emit({ok: true, bytes: bytes.length});
} catch (error) {
  const failure = error instanceof SafeTtsFailure ? error : failureForStage(stage, requestDispatched);
  emit({ok: false, error: {
    stage: failure.stage, code: failure.code,
    external_effect_possible: requestDispatched,
    ...(failure.httpStatus !== undefined ? {http_status: failure.httpStatus} : {}),
  }});
  process.exitCode = 1;
}
}

await main();

function failureForStage(stage, requestDispatched) {
  const code = stage === 'input' ? 'tts_input_invalid'
    : stage === 'provider_request' ? 'tts_provider_request_failed'
      : stage === 'provider_response' ? 'tts_provider_response_invalid'
        : stage === 'audio_url' ? 'tts_audio_url_rejected'
          : stage === 'audio_download' ? 'tts_audio_download_failed'
            : stage === 'audio_format' ? 'tts_audio_format_invalid'
              : stage === 'output_write' ? 'tts_audio_output_write_failed' : 'tts_adapter_failure';
  return {stage, code, httpStatus: undefined, externalEffectPossible: requestDispatched};
}

function emit(value) {
  process.stdout.write(JSON.stringify(value) + '\n');
}

function validateAudioUrl(value) {
  const candidate = new URL(value);
  if (!['http:', 'https:'].includes(candidate.protocol) || candidate.username || candidate.password
      || (candidate.port && candidate.port !== '443') || candidate.hash || !isTrustedOssHost(candidate.hostname)
      || !candidate.pathname.toLowerCase().endsWith('.wav')) throw new Error('untrusted Qwen audio URL');
  // The official response example uses HTTP OSS URLs. Preserve the signed URL's host, path and query,
  // upgrading only the transport to HTTPS; never forward it or its signature to logs/stdout.
  candidate.protocol = 'https:';
  return candidate;
}

function isTrustedOssHost(host) {
  // Exact Beijing output hosts only: the Qwen-TTS REST example documents dashscope-result-bj,
  // and the live Qwen-TTS response for this task returned dashscope-a717. Alibaba documents
  // that exact bucket host for Beijing Model Studio output objects.
  // Do not trust sibling buckets, arbitrary aliyuncs.com names, or caller-selected regions.
  return new Set([
    'dashscope-result-bj.oss-cn-beijing.aliyuncs.com',
    'dashscope-a717.oss-cn-beijing.aliyuncs.com',
    'dashscope-result-wlcb.oss-cn-wulanchabu.aliyuncs.com',
  ]).has(host.toLowerCase());
}
function isOfficialDashScopeHost(host) {
  return ['dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com', 'cn-hongkong.dashscope.aliyuncs.com'].includes(host)
    || /^[a-z0-9-]+\.(?:cn-beijing|ap-southeast-1|us-east-1|cn-hongkong|eu-central-1|ap-northeast-1)\.maas\.aliyuncs\.com$/i.test(host);
}
async function readInput() {
  const chunks = []; let size = 0;
  for await (const value of process.stdin) {
    const chunk = Buffer.from(value); size += chunk.length;
    if (size > 16 * 1024) throw new Error('private TTS input too large');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks, size).toString('utf8')); }
  catch { throw new Error('private TTS input invalid'); }
}
async function readResponseBounded(response, limit, overflowCode) {
  const advertised = Number(response.headers.get('content-length'));
  const stage = overflowCode.startsWith('tts_provider') ? 'provider_response' : 'audio_download';
  if (Number.isFinite(advertised) && advertised > limit) throw new SafeTtsFailure(overflowCode.slice(4), stage);
  const reader = response.body?.getReader(); if (!reader) throw new SafeTtsFailure('tts_provider_response_invalid'.slice(4), stage);
  const chunks = []; let size = 0;
  while (true) {
    const {done, value} = await reader.read(); if (done) break;
    size += value.byteLength; if (size > limit) { await reader.cancel(); throw new SafeTtsFailure(overflowCode.slice(4), stage); }
    chunks.push(Buffer.from(value));
  }
  return Buffer.concat(chunks, size);
}
