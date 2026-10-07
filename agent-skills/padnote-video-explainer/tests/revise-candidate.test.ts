import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {cp, mkdir, readFile, rm, symlink, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import test, {type TestContext} from 'node:test';
import {
  MAX_REVISE_FEEDBACK_BYTES,
  readValidatedReviseCandidate,
  stageReviseCandidate,
} from '../scripts/revise-candidate.js';
import {skillRoot, sha256Bytes, type JsonObject} from '../scripts/lib.js';

const root = skillRoot();
const fixture = resolve(root, 'tests/fixtures/formula-note');
const output = resolve(root, '.local-output/revise-candidate-tests');

interface Task {
  path: string;
  taskId: string;
  request: JsonObject;
}

async function task(t: TestContext): Promise<Task> {
  await mkdir(output, {recursive: true});
  const path = resolve(output, randomUUID());
  await mkdir(resolve(path, 'output'), {recursive: true});
  await mkdir(resolve(path, 'work'), {recursive: true});
  await cp(resolve(fixture, 'request.json'), resolve(path, 'request.json'));
  await cp(resolve(fixture, 'input'), resolve(path, 'input'), {recursive: true});
  const irBytes = await readFile(resolve(fixture, 'expected/lesson.ir.json'));
  await writeFile(resolve(path, 'output/lesson.ir.json'), irBytes);
  const request = JSON.parse(
    (await readFile(resolve(fixture, 'request.json'))).toString('utf8')) as JsonObject;
  const review = {
    schema_version: '1.0',
    task_id: request.task_id,
    status: 'awaiting_storyboard_review',
    lesson_ir_revision: 1,
    lesson_ir: {
      path: 'output/lesson.ir.json',
      media_type: 'application/json',
      size_bytes: irBytes.length,
      sha256: sha256Bytes(irBytes),
    },
    artifacts: [
      {role: 'storyboard', path: 'output/storyboard.html', media_type: 'text/html',
        size_bytes: 1, sha256: 'a'.repeat(64)},
      {role: 'storyboard', path: 'output/storyboard-scene-01.png', media_type: 'image/png',
        size_bytes: 1, sha256: 'b'.repeat(64)},
    ],
  };
  await writeFile(resolve(path, 'output/review.json'), JSON.stringify(review, null, 2));
  t.after(async () => rm(path, {recursive: true, force: true}));
  return {path, taskId: request.task_id as string, request};
}

async function staged(t: TestContext, feedback = '把第一句话改得更快进入主题') {
  const current = await task(t);
  const operationId = randomUUID();
  const staging = await stageReviseCandidate(current.path, operationId, feedback);
  return {...current, operationId, staging};
}

function revisedIr(original: JsonObject): JsonObject {
  const clone = JSON.parse(JSON.stringify(original)) as JsonObject;
  const scenes = clone.scenes as JsonObject[];
  scenes[0]!.narration = '修改后的开场：一句话先给结论，再看误差面积如何变小。';
  return clone;
}

test('staging copies materials into an operation-exclusive directory without touching output', async t => {
  const current = await task(t);
  const operationId = randomUUID();
  const beforeIr = await readFile(resolve(current.path, 'output/lesson.ir.json'));
  const beforeReview = await readFile(resolve(current.path, 'output/review.json'));

  const staging = await stageReviseCandidate(current.path, operationId, '节奏太慢，先给结论');

  const dir = resolve(current.path, 'work/revise', operationId);
  assert.equal(staging.candidate_path, `work/revise/${operationId}/candidate-lesson-ir.json`);
  assert.equal(staging.input_lesson_ir_sha256, sha256Bytes(beforeIr));
  assert.equal(staging.input_review_sha256, sha256Bytes(beforeReview));
  const stagedIr = await readFile(resolve(dir, 'input-lesson-ir.json'));
  assert.deepEqual(stagedIr, beforeIr);
  assert.deepEqual(await readFile(resolve(dir, 'input-review.json')), beforeReview);
  assert.equal((await readFile(resolve(dir, 'feedback.txt'))).toString('utf8'), '节奏太慢，先给结论');
  const ledger = JSON.parse((await readFile(resolve(dir, 'staging.json'))).toString('utf8'));
  assert.equal(ledger.operation_id, operationId);
  assert.equal(ledger.input_lesson_ir_sha256, staging.input_lesson_ir_sha256);
  assert.equal(ledger.feedback_sha256, staging.feedback_sha256);

  assert.deepEqual(await readFile(resolve(current.path, 'output/lesson.ir.json')), beforeIr);
  assert.deepEqual(await readFile(resolve(current.path, 'output/review.json')), beforeReview);
  await assert.rejects(readFile(resolve(dir, 'candidate-lesson-ir.json')), /ENOENT/);
});

test('staging is idempotent per operation and rejects conflicting restaging', async t => {
  const {path, operationId, staging} = await staged(t, '节奏太慢，先给结论');
  const again = await stageReviseCandidate(path, operationId, '节奏太慢，先给结论');
  assert.deepEqual(again, staging);
  await assert.rejects(
    stageReviseCandidate(path, operationId, '换成另一种修改意见'),
    /revise staging conflict/);
});

test('read-back validates a real candidate change and reports its digest', async t => {
  const {path, request, operationId, staging} = await staged(t);
  const formalIr = JSON.parse(
    (await readFile(resolve(path, 'output/lesson.ir.json'))).toString('utf8')) as JsonObject;
  const candidate = revisedIr(formalIr);
  const bytes = Buffer.from(JSON.stringify(candidate, null, 2));
  await writeFile(resolve(path, 'work/revise', operationId, 'candidate-lesson-ir.json'), bytes);

  const result = await readValidatedReviseCandidate(path, operationId, staging, request);
  assert.equal(result.candidate_sha256, sha256Bytes(bytes));
  assert.equal(result.candidate_bytes, bytes.length);
  const scenes = result.candidate.scenes as JsonObject[];
  assert.match(scenes[0]!.narration as string, /修改后的开场/);
});

test('an unchanged candidate is explicitly rejected, byte-equal or canonically equal', async t => {
  const {path, request, operationId, staging} = await staged(t);
  const inputIr = await readFile(resolve(path, 'work/revise', operationId, 'input-lesson-ir.json'));

  await writeFile(resolve(path, 'work/revise', operationId, 'candidate-lesson-ir.json'), inputIr);
  await assert.rejects(
    readValidatedReviseCandidate(path, operationId, staging, request),
    /revise candidate is unchanged/);

  const reserialized = Buffer.from(JSON.stringify(JSON.parse(inputIr.toString('utf8')), null, 4));
  await writeFile(resolve(path, 'work/revise', operationId, 'candidate-lesson-ir.json'), reserialized);
  await assert.rejects(
    readValidatedReviseCandidate(path, operationId, staging, request),
    /revise candidate is unchanged/);
});

test('candidates that break source binding or renderer rules are rejected', async t => {
  const {path, request, operationId, staging} = await staged(t);
  const candidatePath = resolve(path, 'work/revise', operationId, 'candidate-lesson-ir.json');

  const wrongSource = revisedIr(JSON.parse(
    (await readFile(resolve(path, 'output/lesson.ir.json'))).toString('utf8')) as JsonObject);
  (wrongSource.source as JsonObject).bundle_sha256 = '0'.repeat(64);
  await writeFile(candidatePath, JSON.stringify(wrongSource));
  await assert.rejects(
    readValidatedReviseCandidate(path, operationId, staging, request),
    /Lesson IR source does not match request/);

  const malicious = revisedIr(JSON.parse(
    (await readFile(resolve(path, 'output/lesson.ir.json'))).toString('utf8')) as JsonObject);
  (malicious.scenes as JsonObject[])[0]!.narration = '看看<script>alert(1)</script>这段';
  await writeFile(candidatePath, JSON.stringify(malicious));
  await assert.rejects(
    readValidatedReviseCandidate(path, operationId, staging, request),
    /forbidden in Lesson IR/);
});

test('tampered staging materials or a wrong expected binding are rejected', async t => {
  const {path, request, operationId, staging} = await staged(t);
  const dir = resolve(path, 'work/revise', operationId);
  const candidate = revisedIr(JSON.parse(
    (await readFile(resolve(path, 'output/lesson.ir.json'))).toString('utf8')) as JsonObject);
  await writeFile(resolve(dir, 'candidate-lesson-ir.json'), JSON.stringify(candidate));

  const inputIrPath = resolve(dir, 'input-lesson-ir.json');
  const original = await readFile(inputIrPath);
  await writeFile(inputIrPath, JSON.stringify(JSON.parse(original.toString('utf8')), null, 4));
  await assert.rejects(
    readValidatedReviseCandidate(path, operationId, staging, request),
    /revise staging does not match the operation binding/);
  await writeFile(inputIrPath, original);

  const wrongExpected = {...staging, feedback_sha256: 'c'.repeat(64)};
  await assert.rejects(
    readValidatedReviseCandidate(path, operationId, wrongExpected, request),
    /revise staging does not match the operation binding/);
});

test('a missing candidate is reported as missing, not validated', async t => {
  const {path, request, operationId, staging} = await staged(t);
  await assert.rejects(
    readValidatedReviseCandidate(path, operationId, staging, request),
    /ENOENT|revise/);
});

test('operation ids and feedback are strictly bounded', async t => {
  const current = await task(t);
  await assert.rejects(
    stageReviseCandidate(current.path, 'not-a-uuid', '反馈'),
    /revise operation id is invalid/);
  await assert.rejects(stageReviseCandidate(current.path, randomUUID(), ''),
    /revise feedback is empty/);
  await assert.rejects(stageReviseCandidate(current.path, randomUUID(), '   \n\t '),
    /revise feedback is empty/);
  // Zero-width characters are Cf (format), not whitespace, so trim() leaves
  // them: invisible-only feedback must still be rejected as empty.
  await assert.rejects(stageReviseCandidate(current.path, randomUUID(), '\u200b\u200c'),
    /revise feedback is empty/);
  await assert.rejects(stageReviseCandidate(current.path, randomUUID(), '\u00a0\u3000'),
    /revise feedback is empty/);
  await assert.rejects(
    stageReviseCandidate(current.path, randomUUID(), 'x'.repeat(MAX_REVISE_FEEDBACK_BYTES + 1)),
    /revise feedback is empty or exceeds its size limit/);
  await assert.rejects(
    readValidatedReviseCandidate(current.path, 'not-a-uuid', {
      input_lesson_ir_sha256: 'a'.repeat(64), input_review_sha256: 'a'.repeat(64),
      feedback_sha256: 'a'.repeat(64),
    }, current.request),
    /revise operation id is invalid/);
});

test('a staging directory or candidate file reached through a symlink is rejected', async t => {
  const {path, request, operationId, staging} = await staged(t);
  const dir = resolve(path, 'work/revise', operationId);
  const candidate = revisedIr(JSON.parse(
    (await readFile(resolve(path, 'output/lesson.ir.json'))).toString('utf8')) as JsonObject);
  const realTarget = resolve(path, 'work/revise', `${operationId}-real-target`);
  await mkdir(realTarget, {recursive: true});
  await writeFile(resolve(realTarget, 'candidate-lesson-ir.json'), JSON.stringify(candidate));

  const candidatePath = resolve(dir, 'candidate-lesson-ir.json');
  await symlink(resolve(realTarget, 'candidate-lesson-ir.json'), candidatePath);
  await assert.rejects(
    readValidatedReviseCandidate(path, operationId, staging, request),
    /ELOOP|not a regular file|revise/);
  await rm(candidatePath);

  await rm(dir, {recursive: true, force: true});
  await symlink(realTarget, dir);
  await assert.rejects(
    readValidatedReviseCandidate(path, operationId, staging, request),
    /revise staging directory is invalid/);
});

test('an uncommitted staging directory holding generator output refuses to be repaired', async t => {
  const current = await task(t);
  const operationId = randomUUID();
  const dir = resolve(current.path, 'work/revise', operationId);
  await mkdir(dir, {recursive: true});
  await writeFile(resolve(dir, 'candidate-lesson-ir.json'), '{"injected": true}');
  await assert.rejects(
    stageReviseCandidate(current.path, operationId, '任何反馈'),
    /revise staging conflict/);
});

test('staging requires a formal review that describes the staged Lesson IR', async t => {
  const current = await task(t);
  const review = JSON.parse(
    (await readFile(resolve(current.path, 'output/review.json'))).toString('utf8')) as JsonObject;
  (review.lesson_ir as JsonObject).sha256 = '0'.repeat(64);
  await writeFile(resolve(current.path, 'output/review.json'), JSON.stringify(review, null, 2));
  await assert.rejects(
    stageReviseCandidate(current.path, randomUUID(), '反馈'),
    /revise staging review does not describe the staged Lesson IR/);
});

test('a staging area left without a ledger is refused instead of being rebuilt', async t => {
  const current = await task(t);
  const operationId = randomUUID();
  const dir = resolve(current.path, 'work/revise', operationId);
  const ir = await readFile(resolve(current.path, 'output/lesson.ir.json'));
  await mkdir(dir, {recursive: true});
  await writeFile(resolve(dir, 'input-lesson-ir.json'), ir);

  await assert.rejects(
    stageReviseCandidate(current.path, operationId, '任何反馈'),
    /revise staging conflict/);
  // The pre-existing bytes survive and no ledger is invented for them.
  assert.deepEqual(await readFile(resolve(dir, 'input-lesson-ir.json')), ir);
  await assert.rejects(readFile(resolve(dir, 'staging.json')), /ENOENT/);
});

test('a candidate staged against a superseded Lesson IR is refused as stale', async t => {
  const {path, request, operationId, staging} = await staged(t);
  const candidate = revisedIr(JSON.parse(
    (await readFile(resolve(path, 'output/lesson.ir.json'))).toString('utf8')) as JsonObject);
  await writeFile(resolve(path, 'work/revise', operationId, 'candidate-lesson-ir.json'),
    JSON.stringify(candidate));

  // Another attempt installs a newer revision while this one is in flight.
  const newer = JSON.parse(JSON.stringify(candidate)) as JsonObject;
  (newer.scenes as JsonObject[])[0]!.narration = '更晚的版本，已安装。';
  await writeFile(resolve(path, 'output/lesson.ir.json'), JSON.stringify(newer, null, 2));

  await assert.rejects(
    readValidatedReviseCandidate(path, operationId, staging, request),
    /revise staging is based on a stale Lesson IR/);
});
