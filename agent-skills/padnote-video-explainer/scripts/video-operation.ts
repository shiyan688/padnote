import {createHash} from 'node:crypto';
import {constants} from 'node:fs';
import {lstat, open, opendir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {readBoundedRegularFile} from './inspect-task.js';
import {readWindowsFileTail} from './windows-reader.js';
import {validateBoundedInput} from './validate-bounded-input.js';
import {sha256Bytes, type JsonObject} from './lib.js';

const MAX_OPERATION_REQUEST_BYTES = 1024 * 1024;

export type VideoAction = 'initialize' | 'storyboard' | 'approve' | 'revise' | 'produce';
export interface VideoOperationBinding {
  operation_id: string; attempt_id: string; action: VideoAction;
  payload_digest: string; source_snapshot_digest: string;
  request_sha256: string; input_event_cursor: number;
  revision?: number; review_sha256?: string; lesson_ir_sha256?: string;
  feedback_sha256?: string;
  allow_cloud_tts?: true;
}
export interface VideoOperationReceipt extends VideoOperationBinding {
  schema_version: 1; result_event_cursor: number; approval_id?: string;
}
export interface ReconcileArgs extends VideoOperationBinding {}

export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  const record = value as Record<string, unknown>;
  return `{${Object.keys(record).sort().map(key => `${JSON.stringify(key)}:${canonicalJson(record[key])}`).join(',')}}`;
}

export function payloadDigest(action: VideoAction, parameters: JsonObject): string {
  return createHash('sha256').update(canonicalJson({action, parameters}), 'utf8').digest('hex');
}

export function validateOperationBinding(binding: VideoOperationBinding): void {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(binding.operation_id)
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(binding.attempt_id)
      || !/^[a-f0-9]{64}$/.test(binding.payload_digest)
      || !/^[a-f0-9]{64}$/.test(binding.source_snapshot_digest)
      || !/^[a-f0-9]{64}$/.test(binding.request_sha256)
      || !Number.isSafeInteger(binding.input_event_cursor) || binding.input_event_cursor < 0) {
    throw new Error('video operation binding is invalid');
  }
  if (binding.action === 'initialize' && (binding.input_event_cursor !== 0 || binding.revision != null)) {
    throw new Error('video initialize binding is invalid');
  }
  if (binding.action !== 'initialize' && (!Number.isSafeInteger(binding.revision) || (binding.revision ?? 0) < 1)) {
    throw new Error('video operation revision is invalid');
  }
  if (binding.action === 'approve'
      && (!/^[a-f0-9]{64}$/.test(binding.review_sha256 ?? '')
        || !/^[a-f0-9]{64}$/.test(binding.lesson_ir_sha256 ?? ''))) {
    throw new Error('video approval binding is invalid');
  }
  if (binding.action === 'revise'
      && (!/^[a-f0-9]{64}$/.test(binding.review_sha256 ?? '')
        || !/^[a-f0-9]{64}$/.test(binding.lesson_ir_sha256 ?? '')
        || !/^[a-f0-9]{64}$/.test(binding.feedback_sha256 ?? ''))) {
    throw new Error('video revision binding is invalid');
  }
  if (binding.action === 'produce'
      && (!/^[a-f0-9]{64}$/.test(binding.review_sha256 ?? '')
        || !/^[a-f0-9]{64}$/.test(binding.lesson_ir_sha256 ?? '')
        || binding.allow_cloud_tts !== true)) {
    throw new Error('video production binding is invalid');
  }
  if (binding.action !== 'produce' && binding.allow_cloud_tts != null) {
    throw new Error('video operation binding is invalid');
  }
  // A feedback digest only means something for a revision; carrying one on any
  // other action would let a caller smuggle unbound bytes past the payload digest.
  if (binding.action !== 'revise' && binding.feedback_sha256 != null) {
    throw new Error('video operation binding is invalid');
  }
}

/** The canonical parameter projection a binding's payload digest commits to. */
export function operationParameters(binding: VideoOperationBinding): JsonObject {
  if (binding.action === 'initialize') return {};
  const common: JsonObject = {revision: binding.revision!, event_cursor: binding.input_event_cursor};
  if (binding.action === 'approve') {
    common.review_sha256 = binding.review_sha256!;
    common.lesson_ir_sha256 = binding.lesson_ir_sha256!;
  }
  if (binding.action === 'revise') {
    common.review_sha256 = binding.review_sha256!;
    common.lesson_ir_sha256 = binding.lesson_ir_sha256!;
    common.feedback_sha256 = binding.feedback_sha256!;
  }
  if (binding.action === 'produce') {
    common.review_sha256 = binding.review_sha256!;
    common.lesson_ir_sha256 = binding.lesson_ir_sha256!;
    common.allow_cloud_tts = true;
  }
  return common;
}

/**
 * Proves a binding names this task's exact request, source and payload. This is
 * deliberately a pure digest comparison against the current bytes on disk, so a
 * caller cannot reach an action by supplying stale or borrowed identifiers.
 */
export async function assertOperationInput(taskRoot: string, binding: VideoOperationBinding,
                                           action: VideoAction, parameters: JsonObject): Promise<void> {
  validateOperationBinding(binding);
  if (binding.action !== action || binding.payload_digest !== payloadDigest(action, parameters)
      || binding.request_sha256 !== sha256Bytes(await readBoundedRegularFile(
        resolve(taskRoot, 'request.json'), MAX_OPERATION_REQUEST_BYTES))
      || binding.source_snapshot_digest !== await sourceSnapshotDigest(taskRoot)) {
    throw new Error('video operation binding does not match exact inputs');
  }
}

export async function sourceSnapshotDigest(taskRoot: string): Promise<string> {
  const requestBytes = await readBoundedRegularFile(resolve(taskRoot, 'request.json'), 1024 * 1024);
  const request = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(requestBytes)) as JsonObject;
  await validateBoundedInput(taskRoot, request);
  const pending: Array<{path: string; depth: number}> = [{path: resolve(taskRoot, 'input'), depth: 0}];
  let entryCount = 0;
  while (pending.length) {
    const current = pending.pop()!;
    const directory = await opendir(current.path);
    for await (const child of directory) {
      entryCount += 1;
      if (entryCount > 32) throw new Error('source snapshot contains too many entries');
      const childPath = resolve(current.path, child.name);
      const info = await lstat(childPath);
      if (info.isSymbolicLink()) throw new Error('source snapshot contains a symlink');
      if (info.isDirectory()) {
        if (current.depth >= 5) throw new Error('source snapshot path is too deep');
        pending.push({path: childPath, depth: current.depth + 1});
      } else if (!info.isFile()) throw new Error('source snapshot contains a special file');
      else if (current.depth + 1 > 5) throw new Error('source snapshot path is too deep');
    }
  }
  const manifestBytes = await readBoundedRegularFile(resolve(taskRoot, 'input/manifest.json'), 1024 * 1024);
  const manifest = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(manifestBytes)) as {files: Array<{path: string; size_bytes: number; sha256: string}>};
  const hash = createHash('sha256');
  const entries = [
    {name: 'manifest.json', path: 'input/manifest.json', size_bytes: manifestBytes.length, sha256: sha256Bytes(manifestBytes)},
    ...manifest.files.map(entry => ({...entry, name: entry.path.replace(/^input\//, '')})),
  ].sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
  if (entries.length > 8) throw new Error('source snapshot contains too many files');
  let totalBytes = 0;
  for (const entry of entries) {
    const bytes = await readBoundedRegularFile(resolve(taskRoot, entry.path), entry.size_bytes);
    if (sha256Bytes(bytes) !== entry.sha256) throw new Error('source snapshot changed');
    totalBytes += bytes.length;
    if (totalBytes > 32 * 1024 * 1024) throw new Error('source snapshot exceeds size limit');
    const name = Buffer.from(entry.name, 'utf8');
    const size = Buffer.alloc(8); size.writeBigUInt64BE(BigInt(bytes.length));
    hash.update(Buffer.from([name.length >>> 24, name.length >>> 16, name.length >>> 8, name.length]));
    hash.update(name); hash.update(size); hash.update(Buffer.from(entry.sha256, 'hex'));
  }
  return hash.digest('hex');
}

export async function readAuditTail(taskRoot: string): Promise<JsonObject[]> {
  const path = resolve(taskRoot, 'work/task-events.ndjson');
  let bytes: Buffer;
  let start: bigint;
  if (process.platform === 'win32') {
    const result = await readWindowsFileTail(path, 65536);
    bytes = result.bytes;
    start = BigInt(result.metadata.offset);
    if (BigInt(result.metadata.file_size) <= 0n) throw new Error('audit unavailable');
  } else {
    const handle = await open(path, constants.O_RDONLY | constants.O_NONBLOCK | constants.O_NOFOLLOW);
    try {
      const before = await handle.stat({bigint: true});
      if (!before.isFile() || before.size <= 0n) throw new Error('audit unavailable');
      const length = Number(before.size > 65536n ? 65536n : before.size);
      start = before.size - BigInt(length);
      bytes = Buffer.alloc(length);
      let offset = 0;
      while (offset < length) {
        const read = await handle.read(bytes, offset, length - offset, Number(start) + offset);
        if (read.bytesRead <= 0) throw new Error('audit changed');
        offset += read.bytesRead;
      }
      const after = await handle.stat({bigint: true});
      if (before.dev !== after.dev || before.ino !== after.ino || before.size !== after.size
          || before.mtimeNs !== after.mtimeNs || before.ctimeNs !== after.ctimeNs) throw new Error('audit changed');
    } finally { await handle.close(); }
  }
  if (bytes.at(-1) !== 10 || (start > 0n && bytes.indexOf(10) < 0)) throw new Error('audit changed');
  const lines = new TextDecoder('utf-8', {fatal: true}).decode(bytes).split('\n');
  if (lines.at(-1) !== '') throw new Error('audit partial line');
  const events: JsonObject[] = [];
  for (const line of lines.slice(start > 0n ? 1 : 0, -1)) {
    if (!line || Buffer.byteLength(line) > 65536) throw new Error('audit line invalid');
    const value = JSON.parse(line);
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('audit event invalid');
    events.push(value as JsonObject);
  }
  return events;
}
