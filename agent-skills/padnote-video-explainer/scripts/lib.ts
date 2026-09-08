import {createHash} from 'node:crypto';
import {
  lstat,
  mkdir,
  open,
  readFile,
  readdir,
  realpath,
  rename,
  stat,
} from 'node:fs/promises';
import {dirname, extname, isAbsolute, relative, resolve, sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import {Ajv2020, type ErrorObject, type ValidateFunction} from 'ajv/dist/2020.js';

export type JsonObject = Record<string, any>;

const scriptRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ajv = new Ajv2020({allErrors: true, strict: true});
const validators = new Map<string, ValidateFunction>();

export function skillRoot(): string {
  return scriptRoot;
}

export async function readJson(path: string): Promise<JsonObject> {
  return JSON.parse(await readFile(path, 'utf8')) as JsonObject;
}

export async function validateSchema(name: string, value: unknown): Promise<void> {
  let validate = validators.get(name);
  if (!validate) {
    const schema = await readJson(resolve(scriptRoot, 'schemas', `${name}.schema.json`));
    const compiled = ajv.compile(schema);
    validators.set(name, compiled);
    validate = compiled;
  }
  if (!validate(value)) {
    throw new Error(`${name} schema failed:\n${formatAjvErrors(validate.errors)}`);
  }
}

function formatAjvErrors(errors: ErrorObject[] | null | undefined): string {
  return (errors ?? []).map(error => `${error.instancePath || '/'} ${error.message}`).join('\n');
}

export function safeRelativePath(value: string): string {
  if (
    value.length === 0 ||
    isAbsolute(value) ||
    value.includes('\\') ||
    value.includes('\0') ||
    /^[A-Za-z][A-Za-z0-9+.-]*:/.test(value)
  ) {
    throw new Error(`unsafe relative path: ${value}`);
  }
  const parts = value.split('/');
  if (parts.some(part => part === '' || part === '.' || part === '..')) {
    throw new Error(`non-canonical relative path: ${value}`);
  }
  return value;
}

export function resolveInside(root: string, value: string): string {
  safeRelativePath(value);
  const target = resolve(root, value);
  const rel = relative(root, target);
  if (rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel)) {
    throw new Error(`path escapes task root: ${value}`);
  }
  return target;
}

export async function assertExistingFileInside(root: string, value: string): Promise<string> {
  const target = resolveInside(root, value);
  const info = await lstat(target);
  if (!info.isFile() || info.isSymbolicLink()) {
    throw new Error(`expected regular non-symlink file: ${value}`);
  }
  const realRoot = await realpath(root);
  const realTarget = await realpath(target);
  const rel = relative(realRoot, realTarget);
  if (rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel)) {
    throw new Error(`resolved path escapes task root: ${value}`);
  }
  return target;
}

export async function sha256File(path: string): Promise<string> {
  return createHash('sha256').update(await readFile(path)).digest('hex');
}

export function sha256Bytes(value: string | Buffer): string {
  return createHash('sha256').update(value).digest('hex');
}

export async function fileDescriptor(absolutePath: string, path: string, mediaType: string): Promise<JsonObject> {
  const info = await stat(absolutePath);
  return {
    path,
    media_type: mediaType,
    size_bytes: info.size,
    sha256: await sha256File(absolutePath),
  };
}

export async function verifyFileRecord(
  taskRoot: string,
  base: 'task' | 'output',
  record: JsonObject,
): Promise<string> {
  const baseRoot = base === 'output' ? resolve(taskRoot, 'output') : taskRoot;
  const path = await assertExistingFileInside(baseRoot, record.path);
  const info = await stat(path);
  if (info.size !== record.size_bytes) {
    throw new Error(`size mismatch for ${record.path}: ${record.size_bytes} != ${info.size}`);
  }
  const digest = await sha256File(path);
  if (digest !== record.sha256) {
    throw new Error(`sha256 mismatch for ${record.path}`);
  }
  const expectedMime = mimeForPath(record.path);
  if (expectedMime !== record.media_type) {
    throw new Error(`MIME mismatch for ${record.path}: ${record.media_type} != ${expectedMime}`);
  }
  return path;
}

export function mimeForPath(path: string): string {
  switch (extname(path).toLowerCase()) {
    case '.json': return 'application/json';
    case '.html': return 'text/html';
    case '.png': return 'image/png';
    case '.mp4': return 'video/mp4';
    case '.srt': return 'application/x-subrip';
    case '.wav': return 'audio/wav';
    case '.md': return 'text/markdown';
    case '.svg': return 'image/svg+xml';
    default: throw new Error(`unsupported media extension: ${path}`);
  }
}

export async function atomicWriteJson(path: string, value: unknown): Promise<void> {
  await atomicWriteFile(path, `${JSON.stringify(value, null, 2)}\n`);
}

export async function atomicWriteFile(path: string, value: string | Buffer): Promise<void> {
  await mkdir(dirname(path), {recursive: true});
  const temporary = `${path}.tmp`;
  const handle = await open(temporary, 'w');
  try {
    await handle.writeFile(value);
    await handle.sync();
  } finally {
    await handle.close();
  }
  await rename(temporary, path);
}

export async function walkRegularFiles(root: string): Promise<string[]> {
  const found: string[] = [];
  async function visit(directory: string): Promise<void> {
    for (const entry of await readdir(directory, {withFileTypes: true})) {
      const path = resolve(directory, entry.name);
      if (entry.isSymbolicLink()) throw new Error(`symlink is forbidden: ${path}`);
      if (entry.isDirectory()) await visit(path);
      else if (entry.isFile()) found.push(relative(root, path).split(sep).join('/'));
      else throw new Error(`unsupported input entry: ${path}`);
    }
  }
  await visit(root);
  return found.sort();
}

export function normalizeSentence(value: string): string {
  return value.normalize('NFKC').replace(/[\s\p{P}\p{S}]+/gu, '').toLowerCase();
}

export function unique(values: string[], label: string): void {
  if (new Set(values).size !== values.length) throw new Error(`duplicate ${label}`);
}

export function requireCliTaskRoot(): string {
  const value = process.argv[2];
  if (!value) throw new Error('usage: <script> <task-root> [file]');
  return resolve(value);
}
