import {lstat, stat} from 'node:fs/promises';
import {resolve} from 'node:path';
import {
  assertExistingFileInside,
  mimeForPath,
  readJson,
  requireCliTaskRoot,
  safeRelativePath,
  sha256Bytes,
  sha256File,
  validateSchema,
  walkRegularFiles,
  type JsonObject,
} from './lib.js';

const forbiddenKeys = /(?:api[_-]?key|token|secret|authorization|password)/i;
const forbiddenPayload = /(?:data:[^\s;]+;base64,|<\s*(?:style|script)\b|\bffmpeg\b|@revideo\/|\bremotion\b|React\.createElement)/i;

export async function validateRequest(taskRoot: string): Promise<JsonObject> {
  const rootInfo = await lstat(taskRoot);
  if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink()) throw new Error('task root must be a real directory');

  const requestPath = await assertExistingFileInside(taskRoot, 'request.json');
  const request = await readJson(requestPath);
  await validateSchema('request', request);
  inspectRequestValue(request, '$');

  const manifestPath = await assertExistingFileInside(taskRoot, 'input/manifest.json');
  const manifest = await readJson(manifestPath);
  validateManifestShape(manifest);

  const declared = manifest.files.map((entry: JsonObject) => entry.path as string);
  const actual = (await walkRegularFiles(resolve(taskRoot, 'input')))
    .map(path => `input/${path}`)
    .filter(path => path !== 'input/manifest.json');
  if (JSON.stringify(declared) !== JSON.stringify([...declared].sort())) {
    throw new Error('input manifest files must be sorted by path');
  }
  if (JSON.stringify(declared) !== JSON.stringify(actual)) {
    throw new Error(`input manifest is not complete: declared=${JSON.stringify(declared)} actual=${JSON.stringify(actual)}`);
  }

  let treeMaterial = '';
  for (const entry of manifest.files as JsonObject[]) {
    const path = safeRelativePath(entry.path);
    if (!path.startsWith('input/') || path === 'input/manifest.json') {
      throw new Error(`manifest path must be below input/: ${path}`);
    }
    const file = await assertExistingFileInside(taskRoot, path);
    const fileStat = await stat(file);
    if (fileStat.size !== entry.size_bytes) throw new Error(`manifest size mismatch: ${path}`);
    if (await sha256File(file) !== entry.sha256) throw new Error(`manifest sha256 mismatch: ${path}`);
    if (mimeForPath(path) !== entry.media_type) throw new Error(`manifest MIME mismatch: ${path}`);
    treeMaterial += `${path}\0${entry.size_bytes}\0${entry.sha256}\n`;
  }
  const bundle = sha256Bytes(treeMaterial);
  if (bundle !== request.source.bundle_sha256) throw new Error('source.bundle_sha256 does not match input manifest');

  const entrypoint = (manifest.files as JsonObject[]).find(entry => entry.path === request.source.entrypoint);
  if (!entrypoint || entrypoint.media_type !== 'text/markdown') {
    throw new Error('v1 entrypoint must be declared as text/markdown');
  }
  return request;
}

function validateManifestShape(manifest: JsonObject): void {
  if (manifest.schema_version !== '1.0' || !Array.isArray(manifest.files) || manifest.files.length === 0) {
    throw new Error('invalid input manifest shape');
  }
  const allowedRoot = new Set(['schema_version', 'files']);
  if (Object.keys(manifest).some(key => !allowedRoot.has(key))) throw new Error('unknown input manifest field');
  const paths = new Set<string>();
  for (const entry of manifest.files as unknown[]) {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) throw new Error('invalid input manifest entry');
    const item = entry as JsonObject;
    const allowed = new Set(['path', 'media_type', 'size_bytes', 'sha256']);
    if (Object.keys(item).some(key => !allowed.has(key))) throw new Error('unknown input manifest entry field');
    if (typeof item.path !== 'string' || typeof item.media_type !== 'string') throw new Error('invalid manifest path or MIME');
    if (!Number.isInteger(item.size_bytes) || item.size_bytes < 0) throw new Error('invalid manifest size');
    if (typeof item.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(item.sha256)) throw new Error('invalid manifest sha256');
    if (paths.has(item.path)) throw new Error(`duplicate manifest path: ${item.path}`);
    paths.add(item.path);
  }
}

function inspectRequestValue(value: unknown, location: string): void {
  if (typeof value === 'string') {
    if (forbiddenPayload.test(value)) throw new Error(`renderer payload is forbidden at ${location}`);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => inspectRequestValue(item, `${location}[${index}]`));
    return;
  }
  if (!value || typeof value !== 'object') return;
  for (const [key, item] of Object.entries(value)) {
    if (forbiddenKeys.test(key)) throw new Error(`credential field is forbidden at ${location}.${key}`);
    inspectRequestValue(item, `${location}.${key}`);
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  validateRequest(requireCliTaskRoot()).then(request => {
    console.log(`request valid: ${request.task_id}`);
  }).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
