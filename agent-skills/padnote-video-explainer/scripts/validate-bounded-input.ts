import type {BigIntStats} from 'node:fs';
import {opendir, lstat} from 'node:fs/promises';
import {join, resolve, sep} from 'node:path';
import {mimeForPath, safeRelativePath, sha256Bytes, validateSchema, type JsonObject} from './lib.js';
import {readBoundedRegularFile} from './inspect-task.js';
import {inspectRequestValue, validateManifestShape} from './validate-request.js';

const MAX_MANIFEST_BYTES = 1024 * 1024;
const MAX_INPUT_FILES = 64;
const MAX_TREE_ENTRIES = 128;
const MAX_PATH_DEPTH = 8;
const MAX_PATH_BYTES = 512;
const MAX_TOTAL_INPUT_BYTES = 32 * 1024 * 1024;

interface TreeEntry {
  path: string;
  kind: 'file' | 'directory';
  dev: bigint;
  ino: bigint;
  mode: bigint;
  size: bigint;
  mtimeNs: bigint;
}

/**
 * Performs the existing request and manifest semantic checks without unbounded
 * directory reads or file hashing. This is a bounded integrity check, not an
 * operating-system sandbox against a concurrent writer.
 */
export async function validateBoundedInput(taskRoot: string, request: JsonObject): Promise<void> {
  await validateSchema('request', request);
  inspectRequestValue(request, '$');

  const rootPath = resolve(taskRoot);
  const inputRoot = resolve(rootPath, 'input');
  const rootInfo = await lstat(rootPath, {bigint: true});
  const inputInfo = await lstat(inputRoot, {bigint: true});
  if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink()
      || !inputInfo.isDirectory() || inputInfo.isSymbolicLink()) {
    throw new Error('task and input roots must be real directories');
  }

  const manifestPath = resolve(inputRoot, 'manifest.json');
  await assertRealParentChain(rootPath, 'input/manifest.json');
  const manifestBytes = await readBoundedRegularFile(manifestPath, MAX_MANIFEST_BYTES);
  const manifest = parseManifest(manifestBytes);
  validateManifestShape(manifest);
  if (manifest.files.length > MAX_INPUT_FILES) throw new Error('input manifest exceeds 64 files');

  const paths: string[] = [];
  let totalBytes = 0;
  for (const raw of manifest.files as unknown[]) {
    const entry = raw as JsonObject;
    if (!Number.isSafeInteger(entry.size_bytes) || entry.size_bytes < 0) {
      throw new Error('input manifest size is outside the supported range');
    }
    const path = safeRelativePath(entry.path);
    if (!path.startsWith('input/') || path === 'input/manifest.json') {
      throw new Error('manifest paths must name files below input/');
    }
    if (Buffer.byteLength(path, 'utf8') > MAX_PATH_BYTES) throw new Error('input path exceeds 512 UTF-8 bytes');
    const depth = path.split('/').length - 1;
    if (depth > MAX_PATH_DEPTH) throw new Error('input path exceeds maximum depth');
    if (totalBytes > MAX_TOTAL_INPUT_BYTES - entry.size_bytes) {
      throw new Error('declared input files exceed 32 MiB total');
    }
    totalBytes += entry.size_bytes;
    if (mimeForPath(path) !== entry.media_type) throw new Error(`manifest MIME mismatch: ${path}`);
    paths.push(path);
  }
  if (JSON.stringify(paths) !== JSON.stringify([...paths].sort())) {
    throw new Error('input manifest files must be sorted by path');
  }

  const beforeTree = await enumerateInputTree(rootPath);
  const declaredPaths = paths;
  const actualFiles = beforeTree.filter(entry => entry.kind === 'file' && entry.path !== 'input/manifest.json')
    .map(entry => entry.path).sort();
  if (JSON.stringify(actualFiles) !== JSON.stringify(declaredPaths)) {
    throw new Error('input manifest does not exactly describe the input tree');
  }
  if (!beforeTree.some(entry => entry.kind === 'file' && entry.path === 'input/manifest.json')) {
    throw new Error('input manifest is missing from the input tree');
  }

  const treeMaterial: string[] = [];
  for (const raw of manifest.files as unknown[]) {
    const entry = raw as JsonObject;
    const path = entry.path as string;
    await assertRealParentChain(rootPath, path);
    const bytes = await readBoundedRegularFile(resolve(rootPath, path), entry.size_bytes as number);
    if (bytes.byteLength !== entry.size_bytes) throw new Error(`manifest size mismatch: ${path}`);
    if (sha256Bytes(bytes) !== entry.sha256) throw new Error(`manifest sha256 mismatch: ${path}`);
    treeMaterial.push(`${path}\0${entry.size_bytes}\0${entry.sha256}\n`);
  }
  if (sha256Bytes(treeMaterial.join('')) !== request.source.bundle_sha256) {
    throw new Error('source.bundle_sha256 does not match input manifest');
  }
  if (!manifest.files.some((entry: JsonObject) => entry.path === request.source.entrypoint
      && entry.media_type === 'text/markdown')) {
    throw new Error('v1 entrypoint must be declared as text/markdown');
  }

  await assertRealParentChain(rootPath, 'input/manifest.json');
  const finalManifestBytes = await readBoundedRegularFile(manifestPath, MAX_MANIFEST_BYTES);
  if (!manifestBytes.equals(finalManifestBytes)) throw new Error('input manifest changed during validation');
  const afterTree = await enumerateInputTree(rootPath);
  if (!sameTree(beforeTree, afterTree)) throw new Error('input tree changed during validation');
}

async function enumerateInputTree(rootPath: string): Promise<TreeEntry[]> {
  const inputRoot = resolve(rootPath, 'input');
  const initialInput = await lstat(inputRoot, {bigint: true});
  if (!initialInput.isDirectory() || initialInput.isSymbolicLink()) {
    throw new Error('input root must be a real directory');
  }
  const entries: TreeEntry[] = [];
  const pending: Array<{absolute: string; relativePath: string; depth: number; identity: TreeEntry}> = [
    {absolute: inputRoot, relativePath: 'input', depth: 0, identity: treeEntry('input', 'directory', initialInput)},
  ];
  let count = 0;
  while (pending.length > 0) {
    const directory = pending.pop()!;
    const beforeOpen = await lstat(directory.absolute, {bigint: true});
    if (!beforeOpen.isDirectory() || beforeOpen.isSymbolicLink()
        || !sameTreeEntry(directory.identity, treeEntry(directory.relativePath, 'directory', beforeOpen))) {
      throw new Error('input directory changed during enumeration');
    }
    const handle = await opendir(directory.absolute);
    const afterOpen = await lstat(directory.absolute, {bigint: true});
    if (!afterOpen.isDirectory() || afterOpen.isSymbolicLink()
        || !sameTreeEntry(directory.identity, treeEntry(directory.relativePath, 'directory', afterOpen))) {
      await handle.close();
      throw new Error('input directory changed during enumeration');
    }
    for await (const child of handle) {
      count += 1;
      if (count > MAX_TREE_ENTRIES) throw new Error('input tree exceeds 128 entries');
      const childPath = join(directory.absolute, child.name);
      const relativePath = `${directory.relativePath}/${child.name}`.split(sep).join('/');
      if (Buffer.byteLength(relativePath, 'utf8') > MAX_PATH_BYTES) {
        throw new Error('input path exceeds 512 UTF-8 bytes');
      }
      const depth = directory.depth + 1;
      if (depth > MAX_PATH_DEPTH) throw new Error('input tree exceeds maximum depth');
      const info = await lstat(childPath, {bigint: true});
      if (child.isSymbolicLink() || info.isSymbolicLink()) throw new Error('symlinks are forbidden in input tree');
      if (child.isDirectory() && info.isDirectory()) {
        const entry = treeEntry(relativePath, 'directory', info);
        entries.push(entry);
        pending.push({absolute: childPath, relativePath, depth, identity: entry});
      } else if (child.isFile() && info.isFile()) {
        entries.push(treeEntry(relativePath, 'file', info));
      } else {
        throw new Error('input tree contains a special file');
      }
    }
    const afterDirectory = await lstat(directory.absolute, {bigint: true});
    if (!sameTreeEntry(directory.identity, treeEntry(directory.relativePath, 'directory', afterDirectory))) {
      throw new Error('input directory changed during enumeration');
    }
  }
  return entries.sort((left, right) => left.path.localeCompare(right.path));
}

function treeEntry(path: string, kind: TreeEntry['kind'], info: BigIntStats): TreeEntry {
  return {path, kind, dev: info.dev, ino: info.ino, mode: info.mode, size: info.size, mtimeNs: info.mtimeNs};
}

function sameTree(left: TreeEntry[], right: TreeEntry[]): boolean {
  return left.length === right.length && left.every((entry, index) => sameTreeEntry(entry, right[index]!));
}

function sameTreeEntry(left: TreeEntry, right: TreeEntry): boolean {
  return left.path === right.path && left.kind === right.kind && left.dev === right.dev
    && left.ino === right.ino && left.mode === right.mode && left.size === right.size
    && left.mtimeNs === right.mtimeNs;
}

async function assertRealParentChain(rootPath: string, relativePath: string): Promise<void> {
  const parts = relativePath.split('/');
  let current = rootPath;
  for (const part of parts.slice(0, -1)) {
    current = resolve(current, part);
    const info = await lstat(current, {bigint: true});
    if (!info.isDirectory() || info.isSymbolicLink()) {
      throw new Error('input parent path must contain only real directories');
    }
  }
}

function parseManifest(bytes: Buffer): JsonObject {
  let value: unknown;
  try {
    value = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(bytes));
  } catch {
    throw new Error('input manifest is not valid UTF-8 JSON');
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('input manifest must be a JSON object');
  }
  return value as JsonObject;
}
