import {lstat, mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {readBoundedRegularFile} from './inspect-task.js';
import {atomicWriteFile, atomicWriteJson, sha256Bytes, validateSchema, type JsonObject} from './lib.js';
import {validateBoundedInput} from './validate-bounded-input.js';
import {validateIrObject} from './validate-ir.js';
import {canonicalJson} from './video-operation.js';

/**
 * Revision candidate boundary (VIDEO_REVISION_PLAN step 1).
 *
 * Stages the exact materials a revision generator may see (current Lesson IR,
 * current review, bounded feedback) into an operation-exclusive directory and
 * reads back the generator's candidate with full validation. Neither stage
 * touches the formal output/ tree: the candidate stays isolated until a later,
 * separately implemented atomic install.
 *
 * The trust anchor for read-back is the digest triple supplied by the caller
 * (derived from the operation binding), never the on-disk staging ledger.
 */

export const MAX_REVISE_FEEDBACK_BYTES = 16 * 1024;
export const MAX_REVISE_IR_BYTES = 2 * 1024 * 1024;
export const MAX_REVISE_REVIEW_BYTES = 1024 * 1024;
export const MAX_REVISE_REQUEST_BYTES = 1024 * 1024;
const MAX_STAGING_LEDGER_BYTES = 4096;
const OPERATION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export interface ReviseStagingDigests {
  input_lesson_ir_sha256: string;
  input_review_sha256: string;
  feedback_sha256: string;
}

export interface ReviseStaging extends ReviseStagingDigests {
  /** Path of the only file the revision generator may write, relative to the task root. */
  candidate_path: string;
}

export interface ReviseCandidate {
  candidate: JsonObject;
  candidate_sha256: string;
  candidate_bytes: number;
}

interface StagingLedger {
  schema_version: 1;
  operation_id: string;
  input_lesson_ir_sha256: string;
  input_review_sha256: string;
  feedback_sha256: string;
}

const STAGING_LEDGER_KEYS = ['schema_version', 'operation_id', 'input_lesson_ir_sha256',
  'input_review_sha256', 'feedback_sha256'] as const;

function assertOperationId(operationId: string): void {
  if (!OPERATION_ID.test(operationId)) throw new Error('revise operation id is invalid');
}

/**
 * `trim()` removes Unicode whitespace, but zero-width characters are Cf
 * (format) rather than whitespace, so it leaves them in place. Feedback made
 * only of those is invisible to the user and must still count as empty.
 */
function isBlankFeedback(value: string): boolean {
  return value.replace(/[\u200b\u200c\u200d]/g, '').trim().length === 0;
}

async function pathExists(path: string): Promise<boolean> {
  try {
    await lstat(path);
    return true;
  } catch (error: any) {
    if (error?.code === 'ENOENT') return false;
    throw error;
  }
}

function parseBoundedJson(bytes: Buffer): JsonObject {
  let value: unknown;
  try {
    value = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(bytes));
  } catch {
    throw new Error('bounded JSON is invalid');
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('bounded JSON must be an object');
  }
  return value as JsonObject;
}

async function assertRealDirectory(path: string): Promise<void> {
  const info = await lstat(path);
  if (!info.isDirectory() || info.isSymbolicLink()) throw new Error('revise staging directory is invalid');
}

function assertDigestTriple(actual: ReviseStagingDigests, expected: ReviseStagingDigests): void {
  if (actual.input_lesson_ir_sha256 !== expected.input_lesson_ir_sha256
      || actual.input_review_sha256 !== expected.input_review_sha256
      || actual.feedback_sha256 !== expected.feedback_sha256) {
    throw new Error('revise staging does not match the operation binding');
  }
}

/** Stages current IR, review and bounded feedback for one revision operation. */
export async function stageReviseCandidate(
  taskRoot: string,
  operationId: string,
  feedback: string,
): Promise<ReviseStaging> {
  assertOperationId(operationId);
  if (typeof feedback !== 'string') throw new Error('revise feedback is invalid');
  const feedbackBytes = Buffer.from(feedback, 'utf8');
  if (feedbackBytes.length < 1 || feedbackBytes.length > MAX_REVISE_FEEDBACK_BYTES
      || isBlankFeedback(feedback)) {
    throw new Error('revise feedback is empty or exceeds its size limit');
  }
  await assertRealDirectory(taskRoot);

  const requestBytes = await readBoundedRegularFile(
    resolve(taskRoot, 'request.json'), MAX_REVISE_REQUEST_BYTES);
  const request = parseBoundedJson(requestBytes);
  await validateBoundedInput(taskRoot, request);

  const irPath = resolve(taskRoot, 'output/lesson.ir.json');
  const irBytes = await readBoundedRegularFile(irPath, MAX_REVISE_IR_BYTES);
  const ir = parseBoundedJson(irBytes);
  await validateIrObject(taskRoot, ir, request);
  if (!(await readBoundedRegularFile(irPath, MAX_REVISE_IR_BYTES)).equals(irBytes)) {
    throw new Error('revise staging Lesson IR changed during validation');
  }

  const reviewPath = resolve(taskRoot, 'output/review.json');
  const reviewBytes = await readBoundedRegularFile(reviewPath, MAX_REVISE_REVIEW_BYTES);
  const review = parseBoundedJson(reviewBytes);
  await validateSchema('review', review);
  if (review.lesson_ir?.sha256 !== sha256Bytes(irBytes)
      || !Number.isSafeInteger(review.lesson_ir_revision) || review.lesson_ir_revision < 1) {
    throw new Error('revise staging review does not describe the staged Lesson IR');
  }

  const digests: ReviseStagingDigests = {
    input_lesson_ir_sha256: sha256Bytes(irBytes),
    input_review_sha256: sha256Bytes(reviewBytes),
    feedback_sha256: sha256Bytes(feedbackBytes),
  };
  const relativeDir = `work/revise/${operationId}`;
  const candidateDir = resolve(taskRoot, relativeDir);

  const workPath = resolve(taskRoot, 'work');
  const reviseRoot = resolve(workPath, 'revise');
  for (const existing of [workPath, reviseRoot]) {
    try {
      await assertRealDirectory(existing);
    } catch (error: any) {
      if (error?.code !== 'ENOENT') throw error;
    }
  }
  await mkdir(reviseRoot, {recursive: true});
  await assertRealDirectory(reviseRoot);

  // An operation directory that already exists must carry a committed ledger.
  // Anything else -- a half-written area, or one the generator has touched --
  // is refused rather than rebuilt in place, so no candidate is ever silently
  // discarded and no unknown candidate is ever adopted.
  if (await pathExists(candidateDir)) {
    await assertRealDirectory(candidateDir);
    let ledger: StagingLedger;
    try {
      ledger = await readStagingLedger(candidateDir);
    } catch (error: any) {
      if (error?.code === 'ENOENT') throw new Error('revise staging conflict');
      throw error;
    }
    if (ledger.operation_id !== operationId) throw new Error('revise staging conflict');
    const stagedIr = await readBoundedRegularFile(
      resolve(candidateDir, 'input-lesson-ir.json'), MAX_REVISE_IR_BYTES);
    const stagedReview = await readBoundedRegularFile(
      resolve(candidateDir, 'input-review.json'), MAX_REVISE_REVIEW_BYTES);
    const stagedFeedback = await readBoundedRegularFile(
      resolve(candidateDir, 'feedback.txt'), MAX_REVISE_FEEDBACK_BYTES);
    if (sha256Bytes(stagedIr) !== ledger.input_lesson_ir_sha256
        || sha256Bytes(stagedReview) !== ledger.input_review_sha256
        || sha256Bytes(stagedFeedback) !== ledger.feedback_sha256) {
      throw new Error('revise staging conflict');
    }
    if (ledger.input_lesson_ir_sha256 !== digests.input_lesson_ir_sha256
        || ledger.input_review_sha256 !== digests.input_review_sha256
        || ledger.feedback_sha256 !== digests.feedback_sha256) {
      throw new Error('revise staging conflict');
    }
    return {...digests, candidate_path: `${relativeDir}/candidate-lesson-ir.json`};
  }

  try {
    await mkdir(candidateDir);
  } catch (error: any) {
    if (error?.code === 'EEXIST') throw new Error('revise staging conflict');
    throw error;
  }
  await atomicWriteFile(resolve(candidateDir, 'input-lesson-ir.json'), irBytes);
  await atomicWriteFile(resolve(candidateDir, 'input-review.json'), reviewBytes);
  await atomicWriteFile(resolve(candidateDir, 'feedback.txt'), feedbackBytes);
  const ledger: StagingLedger = {schema_version: 1, operation_id: operationId, ...digests};
  await atomicWriteJson(resolve(candidateDir, 'staging.json'), ledger);
  const stagedLedger = await readStagingLedger(candidateDir);
  if (stagedLedger.operation_id !== operationId
      || stagedLedger.input_lesson_ir_sha256 !== digests.input_lesson_ir_sha256
      || stagedLedger.input_review_sha256 !== digests.input_review_sha256
      || stagedLedger.feedback_sha256 !== digests.feedback_sha256) {
    throw new Error('revise staging could not be verified after writing');
  }
  return {...digests, candidate_path: `${relativeDir}/candidate-lesson-ir.json`};
}

async function readStagingLedger(candidateDir: string): Promise<StagingLedger> {
  const bytes = await readBoundedRegularFile(resolve(candidateDir, 'staging.json'), MAX_STAGING_LEDGER_BYTES);
  const value = parseBoundedJson(bytes) as unknown;
  if (!value || typeof value !== 'object'
      || canonicalJson(Object.keys(value as Record<string, unknown>).sort())
        !== canonicalJson([...STAGING_LEDGER_KEYS].sort())) {
    throw new Error('revise staging ledger is invalid');
  }
  const ledger = value as StagingLedger;
  if (ledger.schema_version !== 1 || !OPERATION_ID.test(ledger.operation_id)
      || !/^[a-f0-9]{64}$/.test(ledger.input_lesson_ir_sha256)
      || !/^[a-f0-9]{64}$/.test(ledger.input_review_sha256)
      || !/^[a-f0-9]{64}$/.test(ledger.feedback_sha256)) {
    throw new Error('revise staging ledger is invalid');
  }
  return ledger;
}

/**
 * Re-proves that the staging area still holds exactly the pinned materials.
 *
 * An execution step must act on the revision it was bound to, not on whatever
 * happens to be on disk. The expected triple therefore comes from the operation
 * binding, and the ledger plus all three staged files are re-hashed against it.
 * The formal Lesson IR is compared too, so a staging area left over from an
 * earlier revision is refused rather than executed.
 */
export async function assertReviseStagingIntact(
  taskRoot: string,
  operationId: string,
  expected: ReviseStagingDigests,
): Promise<void> {
  assertOperationId(operationId);
  await assertRealDirectory(taskRoot);
  await assertRealDirectory(resolve(taskRoot, 'work'));
  await assertRealDirectory(resolve(taskRoot, 'work/revise'));
  const candidateDir = resolve(taskRoot, 'work/revise', operationId);
  await assertRealDirectory(candidateDir);

  const ledger = await readStagingLedger(candidateDir);
  if (ledger.operation_id !== operationId) {
    throw new Error('revise staging does not match the operation binding');
  }
  assertDigestTriple({
    input_lesson_ir_sha256: ledger.input_lesson_ir_sha256,
    input_review_sha256: ledger.input_review_sha256,
    feedback_sha256: ledger.feedback_sha256,
  }, expected);

  const stagedIr = await readBoundedRegularFile(
    resolve(candidateDir, 'input-lesson-ir.json'), MAX_REVISE_IR_BYTES);
  const stagedReview = await readBoundedRegularFile(
    resolve(candidateDir, 'input-review.json'), MAX_REVISE_REVIEW_BYTES);
  const stagedFeedback = await readBoundedRegularFile(
    resolve(candidateDir, 'feedback.txt'), MAX_REVISE_FEEDBACK_BYTES);
  if (sha256Bytes(stagedIr) !== expected.input_lesson_ir_sha256
      || sha256Bytes(stagedReview) !== expected.input_review_sha256
      || sha256Bytes(stagedFeedback) !== expected.feedback_sha256) {
    throw new Error('revise staging does not match the operation binding');
  }

  const formalIrBytes = await readBoundedRegularFile(
    resolve(taskRoot, 'output/lesson.ir.json'), MAX_REVISE_IR_BYTES);
  if (sha256Bytes(formalIrBytes) !== expected.input_lesson_ir_sha256) {
    throw new Error('revise staging is based on a stale Lesson IR');
  }
}

/** Reads and fully validates the generator's candidate against the pinned binding. */
export async function readValidatedReviseCandidate(
  taskRoot: string,
  operationId: string,
  expected: ReviseStagingDigests,
  request: JsonObject,
): Promise<ReviseCandidate> {
  assertOperationId(operationId);
  await assertRealDirectory(taskRoot);
  await assertRealDirectory(resolve(taskRoot, 'work'));
  await assertRealDirectory(resolve(taskRoot, 'work/revise'));
  const candidateDir = resolve(taskRoot, 'work/revise', operationId);
  await assertRealDirectory(candidateDir);

  const ledger = await readStagingLedger(candidateDir);
  if (ledger.operation_id !== operationId) {
    throw new Error('revise staging does not match the operation binding');
  }
  assertDigestTriple({
    input_lesson_ir_sha256: ledger.input_lesson_ir_sha256,
    input_review_sha256: ledger.input_review_sha256,
    feedback_sha256: ledger.feedback_sha256,
  }, expected);

  const stagedIrBytes = await readBoundedRegularFile(
    resolve(candidateDir, 'input-lesson-ir.json'), MAX_REVISE_IR_BYTES);
  if (sha256Bytes(stagedIrBytes) !== expected.input_lesson_ir_sha256) {
    throw new Error('revise staging does not match the operation binding');
  }
  const stagedReviewBytes = await readBoundedRegularFile(
    resolve(candidateDir, 'input-review.json'), MAX_REVISE_REVIEW_BYTES);
  if (sha256Bytes(stagedReviewBytes) !== expected.input_review_sha256) {
    throw new Error('revise staging does not match the operation binding');
  }
  const stagedFeedbackBytes = await readBoundedRegularFile(
    resolve(candidateDir, 'feedback.txt'), MAX_REVISE_FEEDBACK_BYTES);
  if (sha256Bytes(stagedFeedbackBytes) !== expected.feedback_sha256) {
    throw new Error('revise staging does not match the operation binding');
  }

  // The candidate must still describe a revision of the *current* formal
  // Lesson IR. If another attempt has since installed a newer revision, the
  // staged material is stale and must not be installed on top of it.
  const formalIrBytes = await readBoundedRegularFile(
    resolve(taskRoot, 'output/lesson.ir.json'), MAX_REVISE_IR_BYTES);
  if (sha256Bytes(formalIrBytes) !== expected.input_lesson_ir_sha256) {
    throw new Error('revise staging is based on a stale Lesson IR');
  }

  const candidatePath = resolve(candidateDir, 'candidate-lesson-ir.json');
  const candidateBytes = await readBoundedRegularFile(candidatePath, MAX_REVISE_IR_BYTES);
  const candidate = parseBoundedJson(candidateBytes);
  await validateIrObject(taskRoot, candidate, request);

  const inputIr = parseBoundedJson(stagedIrBytes);
  if (canonicalJson(candidate) === canonicalJson(inputIr)) {
    throw new Error('revise candidate is unchanged');
  }

  const candidateAfter = await readBoundedRegularFile(candidatePath, MAX_REVISE_IR_BYTES);
  if (!candidateAfter.equals(candidateBytes)) {
    throw new Error('revise candidate changed during validation');
  }
  return {
    candidate,
    candidate_sha256: sha256Bytes(candidateBytes),
    candidate_bytes: candidateBytes.length,
  };
}
