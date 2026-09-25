#!/usr/bin/env node
import {readFileSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {readSelectedEnvironmentFile} from '../../agent-skills/padnote-video-explainer/scripts/provider-env.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const project = resolve(here, '../..');
const config = JSON.parse(readFileSync(resolve(project, 'api/glm.json'), 'utf8'));
const key = readFileSync(resolve(project, 'api', config.api_key_file), 'utf8').trim();
const skill = resolve(project, 'agent-skills/padnote-video-explainer');
let ttsEnvironment = {};
try {
  ttsEnvironment = await readSelectedEnvironmentFile(resolve(project, 'api/.env'));
} catch (error) {
  if (error?.code !== 'ENOENT') throw error;
}
const env = {
  ...process.env,
  HERMES_HOME: resolve(here, '.runtime/profile'),
  HERMES_DISABLE_LAZY_INSTALLS: '1',
  UV_CACHE_DIR: resolve(here, '.runtime/uv-cache'),
  OPENAI_BASE_URL: config.base_url,
  BIGMODEL_API_KEY: key,
  ...ttsEnvironment,
  PADNOTE_TTS_COMMAND: resolve(skill, 'scripts/dashscope-tts.mjs'),
  PADNOTE_AGENT_BACKEND: 'hermes',
  PATH: `${resolve(skill, '.local-cache/node/node-v22.22.0-linux-x64/bin')}:${process.env.PATH}`,
  OMP_NUM_THREADS: '2',
  OPENBLAS_NUM_THREADS: '2',
  DISABLE_TELEMETRY: 'true',
};
const result = spawnSync(resolve(here, '.runtime/venv/bin/hermes'), process.argv.slice(2), {
  env, stdio: 'inherit', cwd: project,
});
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
