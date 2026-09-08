#!/usr/bin/env node
// Provider adapter for PADNOTE_TTS_COMMAND. Credentials are never logged.
import {mkdir, writeFile, rename} from 'node:fs/promises';
import {dirname} from 'node:path';
import {loadEnvFile} from 'node:process';

if (process.env.PADNOTE_TTS_ENV_FILE) loadEnvFile(process.env.PADNOTE_TTS_ENV_FILE);
const env = process.env;
for (const name of ['DASHSCOPE_API_KEY', 'DASHSCOPE_BASE_URL', 'DASHSCOPE_TTS_MODEL',
  'DASHSCOPE_TTS_VOICE', 'PADNOTE_TTS_TEXT', 'PADNOTE_TTS_OUTPUT']) {
  if (!env[name]) throw new Error(`Missing ${name}`);
}
const endpoint = new URL(`${env.DASHSCOPE_BASE_URL.replace(/\/$/, '')}/services/aigc/multimodal-generation/generation`);
if (endpoint.protocol !== 'https:') throw new Error('TTS endpoint must use HTTPS');
const input = {
  text: env.PADNOTE_TTS_TEXT,
  voice: env.DASHSCOPE_TTS_VOICE,
  language_type: env.DASHSCOPE_TTS_LANGUAGE || 'Auto',
};
if (env.DASHSCOPE_TTS_INSTRUCTIONS) input.instructions = env.DASHSCOPE_TTS_INSTRUCTIONS;
if (env.DASHSCOPE_TTS_OPTIMIZE_INSTRUCTIONS) {
  input.optimize_instructions = env.DASHSCOPE_TTS_OPTIMIZE_INSTRUCTIONS === 'true';
}
const response = await fetch(endpoint, {
  method: 'POST',
  headers: {Authorization: `Bearer ${env.DASHSCOPE_API_KEY}`, 'Content-Type': 'application/json'},
  body: JSON.stringify({model: env.DASHSCOPE_TTS_MODEL, input}),
  signal: AbortSignal.timeout(60000),
  redirect: 'error',
});
if (!response.ok) throw new Error(`DashScope synthesis failed: HTTP ${response.status}`);
const result = await response.json();
const audioUrl = result.output?.audio?.url;
if (!audioUrl) throw new Error('DashScope response has no audio URL');
const downloadUrl = new URL(audioUrl);
// DashScope can return an HTTP OSS URL; download the same resource over TLS.
if (downloadUrl.protocol === 'http:') downloadUrl.protocol = 'https:';
if (downloadUrl.protocol !== 'https:') throw new Error('Audio URL must use HTTPS');
const audio = await fetch(downloadUrl, {signal: AbortSignal.timeout(60000), redirect: 'error'});
if (!audio.ok) throw new Error(`Audio download failed: HTTP ${audio.status}`);
const bytes = Buffer.from(await audio.arrayBuffer());
if (bytes.toString('ascii', 0, 4) !== 'RIFF' || bytes.toString('ascii', 8, 12) !== 'WAVE') {
  throw new Error('Provider did not return WAV audio');
}
await mkdir(dirname(env.PADNOTE_TTS_OUTPUT), {recursive: true});
await writeFile(`${env.PADNOTE_TTS_OUTPUT}.partial`, bytes);
await rename(`${env.PADNOTE_TTS_OUTPUT}.partial`, env.PADNOTE_TTS_OUTPUT);
console.log(`TTS WAV written (${bytes.length} bytes)`);
