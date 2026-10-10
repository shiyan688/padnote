import {constants} from 'node:fs';
import {open} from 'node:fs/promises';

export const TTS_RUNTIME_ENV_ALLOWLIST = [
  'PATH',
  'Path',
  'SystemRoot',
  'SYSTEMROOT',
  'WINDIR',
  'COMSPEC',
  'PATHEXT',
  'TEMP',
  'TMP',
  'TMPDIR',
  'LANG',
  'LC_ALL',
  'LC_CTYPE',
  'TZ',
  'SSL_CERT_FILE',
  'SSL_CERT_DIR',
  'NODE_EXTRA_CA_CERTS',
] as const;

// Fixed legacy surface for the bundled DashScope adapter. Generic adapters use
// PADNOTE_TTS_ENV_NAMES to opt in to a small, explicit set of provider variables.
export const TTS_PROVIDER_ENV_ALLOWLIST = [
  'PADNOTE_TTS_ENV_NAMES',
  'PADNOTE_TTS_ENV_FILE',
  'DASHSCOPE_API_KEY',
  'DASHSCOPE_BASE_URL',
  'DASHSCOPE_TTS_MODEL',
  'DASHSCOPE_TTS_VOICE',
  'DASHSCOPE_TTS_LANGUAGE',
  'DASHSCOPE_TTS_INSTRUCTIONS',
  'DASHSCOPE_TTS_OPTIMIZE_INSTRUCTIONS',
] as const;

const MAX_PROVIDER_ENV_NAMES = 8;
const PROVIDER_ENV_NAME = /^[A-Z][A-Z0-9_]{0,63}$/;
const FORBIDDEN_PROVIDER_ENV_NAME = /^(?:PATH|HOME|USERPROFILE|HOMEDRIVE|HOMEPATH|SYSTEMROOT|WINDIR|COMSPEC|PATHEXT|TEMP|TMP|TMPDIR|LANG|LC_ALL|LC_CTYPE|TZ|SSL_CERT_FILE|SSL_CERT_DIR|NODE_EXTRA_CA_CERTS|NODE_OPTIONS|NODE_PATH|NODE_[A-Z0-9_]*|PADNOTE_[A-Z0-9_]*|LD_[A-Z0-9_]*|DYLD_[A-Z0-9_]*|PYTHON[A-Z0-9_]*|BASH_ENV|ENV|ZDOTDIR|SHELLOPTS|BASHOPTS|RUBY[A-Z0-9_]*|PERL[A-Z0-9_]*|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|CLASSPATH|GCONV_PATH)$/i;

/** Parse the user's explicit, bounded provider-variable selection. */
export function parseTtsProviderEnvironmentNames(value: string | undefined): string[] | undefined {
  if (value === undefined) return undefined;
  if (value.length > 8 * 65) throw new Error('PADNOTE_TTS_ENV_NAMES is too long');
  const names = value.split(',');
  if (names.length < 1 || names.length > MAX_PROVIDER_ENV_NAMES
      || names.some(name => !PROVIDER_ENV_NAME.test(name) || FORBIDDEN_PROVIDER_ENV_NAME.test(name))
      || new Set(names).size !== names.length) {
    throw new Error('PADNOTE_TTS_ENV_NAMES must list unique safe variable names');
  }
  return names;
}

export interface TtsSceneEnvironment {
  sceneId: string;
  text: string;
  language: string;
  voiceProfile: string;
  speed: string;
  output: string;
  idempotencyKey?: string;
}

export function buildTtsChildEnvironment(
  scene: TtsSceneEnvironment,
  hostEnvironment: NodeJS.ProcessEnv = process.env,
  selectedProviderEnvironment: NodeJS.ProcessEnv = {},
): NodeJS.ProcessEnv {
  const child: NodeJS.ProcessEnv = {};
  for (const name of TTS_RUNTIME_ENV_ALLOWLIST) {
    const value = hostEnvironment[name];
    if (value !== undefined) child[name] = value;
  }
  const providerNames = parseTtsProviderEnvironmentNames(hostEnvironment.PADNOTE_TTS_ENV_NAMES);
  if (providerNames) {
    child.PADNOTE_TTS_ENV_NAMES = providerNames.join(',');
    for (const name of providerNames) {
      const value = selectedProviderEnvironment[name] ?? hostEnvironment[name];
      if (value !== undefined) child[name] = value;
    }
  } else {
    // Preserve the legacy Qwen adapter's exact inheritance surface.
    for (const name of TTS_PROVIDER_ENV_ALLOWLIST) {
      if (!name.startsWith('DASHSCOPE_') && name !== 'PADNOTE_TTS_ENV_FILE') continue;
      const value = hostEnvironment[name];
      if (value !== undefined) child[name] = value;
    }
  }
  child.PADNOTE_TTS_SCENE_ID = scene.sceneId;
  child.PADNOTE_TTS_TEXT = scene.text;
  child.PADNOTE_TTS_LANGUAGE = scene.language;
  child.PADNOTE_TTS_VOICE_PROFILE = scene.voiceProfile;
  child.PADNOTE_TTS_SPEED = scene.speed;
  child.PADNOTE_TTS_OUTPUT = scene.output;
  if (scene.idempotencyKey) child.PADNOTE_TTS_IDEMPOTENCY_KEY = scene.idempotencyKey;
  return child;
}

/** Read only selected names from an optional dotenv file in the parent process. */
export async function loadSelectedTtsProviderEnvironment(
  hostEnvironment: NodeJS.ProcessEnv = process.env,
): Promise<NodeJS.ProcessEnv> {
  const names = parseTtsProviderEnvironmentNames(hostEnvironment.PADNOTE_TTS_ENV_NAMES);
  if (!names) return {};
  const selected: NodeJS.ProcessEnv = {};
  const filePath = hostEnvironment.PADNOTE_TTS_ENV_FILE;
  if (filePath) Object.assign(selected, await readSelectedProviderEnvironmentFile(filePath, names));
  // Explicit host values override the optional file.
  for (const name of names) {
    if (hostEnvironment[name] !== undefined) selected[name] = hostEnvironment[name];
  }
  return selected;
}

async function readSelectedProviderEnvironmentFile(path: string, names: string[]): Promise<NodeJS.ProcessEnv> {
  const maxBytes = 64 * 1024;
  const handle = await open(path, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
  let source: string;
  try {
    const info = await handle.stat();
    if (!info.isFile()) throw new Error('TTS provider environment file must be a regular file');
    if (info.size > maxBytes) throw new Error('TTS provider environment file is too large');
    const buffer = Buffer.alloc(maxBytes + 1);
    let used = 0;
    while (used < buffer.length) {
      const {bytesRead} = await handle.read(buffer, used, buffer.length - used, used);
      if (bytesRead === 0) break;
      used += bytesRead;
    }
    if (used > maxBytes) throw new Error('TTS provider environment file is too large');
    source = buffer.toString('utf8', 0, used);
  } finally {
    await handle.close();
  }
  const allowed = new Set(names);
  const selected: NodeJS.ProcessEnv = {};
  for (const rawLine of source.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const match = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (!match) throw new Error('TTS provider environment file contains an invalid line');
    const name = match[1]!;
    if (!allowed.has(name)) continue;
    selected[name] = decodeEnvironmentValue(match[2]!);
  }
  return selected;
}

function decodeEnvironmentValue(raw: string): string {
  const value = raw.trim();
  if (value.startsWith('"')) {
    if (!value.endsWith('"')) throw new Error('TTS provider environment file has an unterminated quote');
    try { return JSON.parse(value) as string; }
    catch { throw new Error('TTS provider environment file has an invalid quoted value'); }
  }
  if (value.startsWith("'")) {
    if (!value.endsWith("'")) throw new Error('TTS provider environment file has an unterminated quote');
    return value.slice(1, -1);
  }
  const comment = value.search(/\s+#/);
  return (comment >= 0 ? value.slice(0, comment) : value).trim();
}
