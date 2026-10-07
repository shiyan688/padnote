import {constants} from 'node:fs';
import {open} from 'node:fs/promises';

export const DASHSCOPE_ENV_NAMES = Object.freeze([
  'DASHSCOPE_API_KEY',
  'DASHSCOPE_BASE_URL',
  'DASHSCOPE_TTS_MODEL',
  'DASHSCOPE_TTS_VOICE',
  'DASHSCOPE_TTS_LANGUAGE',
  'DASHSCOPE_TTS_INSTRUCTIONS',
  'DASHSCOPE_TTS_OPTIMIZE_INSTRUCTIONS',
]);

/** Reads a dedicated or mixed dotenv file without sourcing it or mutating process.env. */
export async function readSelectedEnvironmentFile(path, allowedNames = DASHSCOPE_ENV_NAMES) {
  const maximumBytes = 64 * 1024;
  const handle = await open(path, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
  let source;
  try {
    const info = await handle.stat();
    if (!info.isFile()) throw new Error('TTS environment file must be a regular file');
    if (info.size > maximumBytes) throw new Error('TTS environment file is too large');
    const buffer = Buffer.alloc(maximumBytes + 1);
    let used = 0;
    while (used < buffer.length) {
      const {bytesRead} = await handle.read(buffer, used, buffer.length - used, used);
      if (bytesRead === 0) break;
      used += bytesRead;
    }
    if (used > maximumBytes) throw new Error('TTS environment file is too large');
    source = buffer.toString('utf8', 0, used);
  } finally {
    await handle.close();
  }
  const allowed = new Set(allowedNames);
  const selected = {};
  for (const rawLine of source.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const match = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (!match) throw new Error('TTS environment file contains an invalid line');
    const name = match[1];
    if (!allowed.has(name)) continue;
    selected[name] = decodeDotenvValue(match[2]);
  }
  return selected;
}

export async function loadDashScopeEnvironment(hostEnvironment = process.env) {
  const selected = {};
  if (hostEnvironment.PADNOTE_TTS_ENV_FILE) {
    Object.assign(selected, await readSelectedEnvironmentFile(hostEnvironment.PADNOTE_TTS_ENV_FILE));
  }
  // Explicit variables override the file, but only this fixed provider set is copied.
  for (const name of DASHSCOPE_ENV_NAMES) {
    if (hostEnvironment[name] !== undefined) selected[name] = hostEnvironment[name];
  }
  return selected;
}

function decodeDotenvValue(raw) {
  const value = raw.trim();
  if (value.startsWith('"')) {
    if (!value.endsWith('"')) throw new Error('TTS environment file has an unterminated quote');
    try {
      return JSON.parse(value);
    } catch {
      throw new Error('TTS environment file has an invalid quoted value');
    }
  }
  if (value.startsWith("'")) {
    if (!value.endsWith("'")) throw new Error('TTS environment file has an unterminated quote');
    return value.slice(1, -1);
  }
  const comment = value.search(/\s+#/);
  return (comment >= 0 ? value.slice(0, comment) : value).trim();
}
