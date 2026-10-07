import {constants} from 'node:fs';
import {lstat, open} from 'node:fs/promises';
import {isAbsolute, relative, resolve, sep} from 'node:path';
import {isDeepStrictEqual} from 'node:util';
import {assertOfflineStoryboard} from './validate-review.js';
import {validateIrObject} from './validate-ir.js';
import {validateBoundedInput} from './validate-bounded-input.js';
import {safeRelativePath, sha256Bytes, validateSchema, type JsonObject} from './lib.js';
import {inspectTask, readBoundedRegularFile, type TaskInspection} from './inspect-task.js';
import {readWindowsRegularFile} from './windows-reader.js';

const MAX_REQUEST_BYTES = 1024 * 1024;
const MAX_REVIEW_BYTES = 1024 * 1024;
const MAX_IR_BYTES = 2 * 1024 * 1024;
const MAX_HTML_BYTES = 8 * 1024 * 1024;
const MAX_PNG_BYTES = 8 * 1024 * 1024;
const MAX_TOTAL_ARTIFACT_BYTES = 128 * 1024 * 1024;
const MAX_ARTIFACTS = 61;
const MAX_PROJECTION_BYTES = 2 * 1024 * 1024;
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const HASH = /^[a-f0-9]{64}$/;

export interface ReviewProjection extends JsonObject {
  protocol_version: 1;
  task_id: string;
  status: string;
  event_cursor: number;
  revision: number;
  review_sha256: string;
  lesson_ir_sha256: string;
  episode: {title: string; audience: string; learning_goal: string; language: string};
  scenes: Array<{
    id: string;
    learning_objective: string;
    narration: string;
    screen_text: string[];
    visual_kind: string;
    preview: {
      path: string;
      media_type: 'image/png';
      size_bytes: number;
      sha256: string;
      width: number;
      height: number;
    };
  }>;
}

interface StableFile {
  bytes: Buffer;
  size: number;
  mtimeNs: bigint;
}

/** Validates persisted review material and returns a bounded text/PNG-only projection. */
export async function inspectReview(taskRoot: string): Promise<ReviewProjection> {
  const before = await inspectTask(taskRoot);
  assertReviewableState(before);

  const requestBytes = await readBoundedRegularFile(resolve(taskRoot, 'request.json'), MAX_REQUEST_BYTES);
  const request = parseObject(requestBytes, 'request');
  await validateSchema('request', request);
  await validateBoundedInput(taskRoot, request);
  if (request.task_id !== before.task_id) throw new Error('review request identity is inconsistent');

  const reviewPath = 'output/review.json';
  const reviewFile = await readStableFile(taskRoot, reviewPath, MAX_REVIEW_BYTES);
  const review = parseObject(reviewFile.bytes, 'review');
  await validateSchema('review', review);
  if (review.task_id !== before.task_id) throw new Error('review task identity is inconsistent');
  if (sha256Bytes(reviewFile.bytes) !== before.review_sha256) throw new Error('persisted review digest changed');
  if (review.lesson_ir_revision !== before.revision) throw new Error('persisted review revision changed');

  const irRecord = review.lesson_ir as JsonObject;
  if (irRecord.path !== 'lesson.ir.json' || irRecord.media_type !== 'application/json'
      || !Number.isSafeInteger(irRecord.size_bytes) || irRecord.size_bytes < 1
      || irRecord.size_bytes > MAX_IR_BYTES || !isHash(irRecord.sha256)) {
    throw new Error('review Lesson IR descriptor is invalid');
  }
  const irFile = await readStableFile(taskRoot, 'output/lesson.ir.json', MAX_IR_BYTES, irRecord.size_bytes);
  if (sha256Bytes(irFile.bytes) !== irRecord.sha256
      || irRecord.sha256 !== before.lesson_ir_sha256) {
    throw new Error('persisted Lesson IR digest changed');
  }
  if (reviewFile.mtimeNs <= irFile.mtimeNs) throw new Error('review.json must be newer than the Lesson IR');
  const ir = parseObject(irFile.bytes, 'Lesson IR');
  await validateIrObject(taskRoot, ir, request);
  if (!Array.isArray(ir.scenes) || ir.scenes.length > 60) throw new Error('Lesson IR scene list is invalid');

  const artifacts = review.artifacts as JsonObject[];
  if (artifacts.length !== ir.scenes.length + 1 || artifacts.length > MAX_ARTIFACTS) {
    throw new Error('review artifact list does not match its scenes');
  }
  const records = new Map<string, JsonObject>();
  let totalDeclaredBytes = 0;
  for (const record of artifacts) {
    if (record.role !== 'storyboard' || typeof record.path !== 'string'
        || !Number.isSafeInteger(record.size_bytes) || record.size_bytes < 1
        || !isHash(record.sha256)) {
      throw new Error('review artifact descriptor is invalid');
    }
    const relativePath = safeRelativePath(record.path);
    if (records.has(relativePath)) throw new Error('review artifact paths are duplicated');
    const max = record.media_type === 'text/html' ? MAX_HTML_BYTES
      : record.media_type === 'image/png' ? MAX_PNG_BYTES : 0;
    if (max === 0 || record.size_bytes > max) throw new Error('review artifact exceeds its size limit');
    totalDeclaredBytes += record.size_bytes;
    if (!Number.isSafeInteger(totalDeclaredBytes) || totalDeclaredBytes > MAX_TOTAL_ARTIFACT_BYTES) {
      throw new Error('review artifacts exceed the total size limit');
    }
    records.set(relativePath, record);
  }

  const htmlRecord = records.get('storyboard.html');
  if (!htmlRecord || htmlRecord.media_type !== 'text/html') throw new Error('review HTML is missing');
  const html = await readStableFile(taskRoot, `output/${htmlRecord.path}`, MAX_HTML_BYTES, htmlRecord.size_bytes);
  assertFileRecord(html, htmlRecord);
  assertOfflineStoryboard(decodeUtf8(html.bytes, 'storyboard HTML'));
  if (reviewFile.mtimeNs <= html.mtimeNs) throw new Error('review.json must be newer than storyboard artifacts');

  const scenes: ReviewProjection['scenes'] = [];
  for (const sceneValue of ir.scenes as JsonObject[]) {
    const sceneId = sceneValue.id;
    if (typeof sceneId !== 'string' || !/^[a-z][a-z0-9-]{0,63}$/.test(sceneId)) {
      throw new Error('Lesson IR scene identity is invalid');
    }
    const previewPath = `storyboard-${sceneId}.png`;
    const record = records.get(previewPath);
    if (!record || record.media_type !== 'image/png') throw new Error('review scene preview is missing');
    const png = await readStableFile(taskRoot, `output/${record.path}`, MAX_PNG_BYTES, record.size_bytes);
    assertFileRecord(png, record);
    const dimensions = inspectPngHeader(png.bytes);
    if (reviewFile.mtimeNs <= png.mtimeNs) throw new Error('review.json must be newer than storyboard artifacts');
    const visual = sceneValue.visual as JsonObject;
    scenes.push({
      id: sceneId,
      learning_objective: sceneValue.learning_objective,
      narration: sceneValue.narration,
      screen_text: sceneValue.screen_text,
      visual_kind: visual.type as string,
      preview: {
        path: previewPath,
        media_type: 'image/png',
        size_bytes: png.size,
        sha256: record.sha256 as string,
        width: dimensions.width,
        height: dimensions.height,
      },
    });
  }

  const projection: ReviewProjection = {
    protocol_version: 1,
    task_id: before.task_id,
    status: before.status,
    event_cursor: before.event_cursor,
    revision: before.revision!,
    review_sha256: before.review_sha256!,
    lesson_ir_sha256: before.lesson_ir_sha256!,
    episode: {
      title: ir.episode.title,
      audience: ir.episode.audience,
      learning_goal: ir.episode.learning_goal,
      language: ir.episode.language,
    },
    scenes,
  };
  if (Buffer.byteLength(JSON.stringify(projection), 'utf8') > MAX_PROJECTION_BYTES) {
    throw new Error('review projection exceeds its size limit');
  }

  const [requestAfter, reviewAfter, irAfter, after] = await Promise.all([
    readBoundedRegularFile(resolve(taskRoot, 'request.json'), MAX_REQUEST_BYTES),
    readStableFile(taskRoot, reviewPath, MAX_REVIEW_BYTES),
    readStableFile(taskRoot, 'output/lesson.ir.json', MAX_IR_BYTES),
    inspectTask(taskRoot),
  ]);
  const requestAfterObject = parseObject(requestAfter, 'request');
  await validateBoundedInput(taskRoot, requestAfterObject);
  if (!requestBytes.equals(requestAfter) || !reviewFile.bytes.equals(reviewAfter.bytes)
      || !irFile.bytes.equals(irAfter.bytes) || !isDeepStrictEqual(before, after)
      || reviewFile.mtimeNs !== reviewAfter.mtimeNs || irFile.mtimeNs !== irAfter.mtimeNs
      || sha256Bytes(reviewAfter.bytes) !== after.review_sha256
      || sha256Bytes(irAfter.bytes) !== after.lesson_ir_sha256) {
    throw new Error('review inputs or task state changed during inspection');
  }
  return projection;
}

function assertReviewableState(state: TaskInspection): asserts state is TaskInspection & {
  revision: number; review_sha256: string; lesson_ir_sha256: string;
} {
  if (state.status === 'initialized' || state.status === 'running' || state.status === 'cancelling'
      || !Number.isSafeInteger(state.revision) || (state.revision ?? 0) < 1
      || !isHash(state.review_sha256) || !isHash(state.lesson_ir_sha256)) {
    throw new Error('persisted storyboard review is unavailable for this task state');
  }
}

async function readStableFile(
  taskRoot: string,
  relativePath: string,
  maxBytes: number,
  declaredSize?: number,
): Promise<StableFile> {
  const safePath = safeRelativePath(relativePath);
  const segments = safePath.split('/');
  let current = taskRoot;
  for (const segment of segments.slice(0, -1)) {
    current = resolve(current, segment);
    const directory = await lstat(current);
    if (!directory.isDirectory() || directory.isSymbolicLink()) throw new Error('review path contains a non-directory');
  }
  const absolute = resolve(taskRoot, safePath);
  const root = resolve(taskRoot);
  const fromRoot = relative(root, absolute);
  if (!fromRoot || isAbsolute(fromRoot) || fromRoot === '..' || fromRoot.startsWith(`..${sep}`)) {
    throw new Error('review path escapes task root');
  }
  if (process.platform === 'win32') {
    const result = await readWindowsRegularFile(absolute, maxBytes, declaredSize);
    return {bytes: result.bytes, size: result.bytes.length, mtimeNs: BigInt(result.metadata.mtime_ns)};
  }
  if (typeof constants.O_NONBLOCK !== 'number' || typeof constants.O_NOFOLLOW !== 'number') {
    throw new Error('platform cannot safely inspect review files');
  }
  const file = await open(absolute, constants.O_RDONLY | constants.O_NONBLOCK | constants.O_NOFOLLOW);
  try {
    const before = await file.stat({bigint: true});
    if (!before.isFile() || before.size > BigInt(maxBytes)
        || (declaredSize !== undefined && before.size !== BigInt(declaredSize))) {
      throw new Error('review file is not a bounded regular file');
    }
    const data: Buffer[] = [];
    let total = 0;
    while (total <= maxBytes) {
      const chunk = Buffer.alloc(Math.min(64 * 1024, maxBytes + 1 - total));
      const {bytesRead} = await file.read(chunk, 0, chunk.length, null);
      if (bytesRead === 0) break;
      data.push(chunk.subarray(0, bytesRead));
      total += bytesRead;
    }
    const after = await file.stat({bigint: true});
    if (total > maxBytes || total !== Number(after.size)
        || before.dev !== after.dev || before.ino !== after.ino
        || before.size !== after.size || before.mtimeNs !== after.mtimeNs) {
      throw new Error('review file changed or exceeded its size limit');
    }
    return {bytes: Buffer.concat(data, total), size: total, mtimeNs: after.mtimeNs};
  } finally {
    await file.close();
  }
}

function parseObject(bytes: Buffer, label: string): JsonObject {
  let value: unknown;
  try {
    value = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(bytes));
  } catch {
    throw new Error(`${label} is not valid UTF-8 JSON`);
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label} must be an object`);
  return value as JsonObject;
}

function decodeUtf8(bytes: Buffer, label: string): string {
  try {
    return new TextDecoder('utf-8', {fatal: true}).decode(bytes);
  } catch {
    throw new Error(`${label} is not valid UTF-8`);
  }
}

function assertFileRecord(file: StableFile, record: JsonObject): void {
  if (file.size !== record.size_bytes || sha256Bytes(file.bytes) !== record.sha256) {
    throw new Error('review artifact digest or size mismatch');
  }
}

function inspectPngHeader(bytes: Buffer): {width: number; height: number} {
  if (bytes.length < 33 || !bytes.subarray(0, 8).equals(PNG_SIGNATURE)
      || bytes.readUInt32BE(8) !== 13 || bytes.toString('ascii', 12, 16) !== 'IHDR') {
    throw new Error('review PNG has an invalid IHDR header');
  }
  const width = bytes.readUInt32BE(16);
  const height = bytes.readUInt32BE(20);
  if (width < 1 || width > 4096 || height < 1 || height > 4096 || width * height > 8388608) {
    throw new Error('review PNG dimensions exceed the preview limit');
  }
  return {width, height};
}

function isHash(value: unknown): value is string {
  return typeof value === 'string' && HASH.test(value);
}
