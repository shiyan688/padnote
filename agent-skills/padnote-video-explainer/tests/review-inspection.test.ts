import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {cp, lstat, mkdir, readFile, rename, rm, symlink, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import test from 'node:test';
import {buildPersistedStoryboard, ensureTaskState} from '../scripts/task-worker.js';
import {inspectReview} from '../scripts/inspect-review.js';
import {atomicWriteJson, sha256Bytes, skillRoot} from '../scripts/lib.js';
import {loadTaskState} from '../scripts/task-state.js';

const fixture = resolve(skillRoot(), 'tests/fixtures/formula-note');
const roots = resolve(skillRoot(), '.local-output/review-inspection-tests');

test('review inspection projects persisted storyboard without changing state or events', async t => {
  const root = await makeTask('projection');
  await buildPersistedStoryboard(root, 1);
  const statePath = resolve(root, 'work/task-state.json');
  const eventsPath = resolve(root, 'work/task-events.ndjson');
  const reviewPath = resolve(root, 'output/review.json');
  const irPath = resolve(root, 'output/lesson.ir.json');
  const initialState = await readFile(statePath);
  const initialEvents = await readFile(eventsPath);
  const reviewOriginal = await readFile(reviewPath);
  const irOriginal = await readFile(irPath);
  const reviewValue = JSON.parse(reviewOriginal.toString('utf8')) as Record<string, any>;
  const irValue = JSON.parse(irOriginal.toString('utf8')) as Record<string, any>;
  const stateValue = JSON.parse(initialState.toString('utf8')) as Record<string, any>;

  const projection = await inspectReview(root);
  assert.equal(projection.protocol_version, 1);
  assert.equal(projection.task_id, stateValue.task_id);
  assert.equal(projection.status, 'awaiting_storyboard_review');
  assert.equal(projection.revision, 1);
  assert.equal(projection.scenes.length, irValue.scenes.length);
  assert.deepEqual(Object.keys(projection.scenes[0]!).sort(), [
    'id', 'learning_objective', 'narration', 'preview', 'screen_text', 'visual_kind',
  ]);
  assert.ok(projection.scenes.every(scene => scene.preview.media_type === 'image/png'));
  assert.ok(!JSON.stringify(projection).includes('storyboard.html'));
  assert.ok(!JSON.stringify(projection).includes(root));
  assert.deepEqual(await readFile(statePath), initialState);
  assert.deepEqual(await readFile(eventsPath), initialEvents);
  const actionFirst = runReviewCli('review', root);
  assert.equal(actionFirst.status, 0, actionFirst.stderr);
  assert.deepEqual(JSON.parse(actionFirst.stdout), projection);
  const legacy = runReviewCli(root, 'review');
  assert.equal(legacy.status, 0, legacy.stderr);
  assert.deepEqual(JSON.parse(legacy.stdout), projection);
  assert.deepEqual(await readFile(statePath), initialState);
  assert.deepEqual(await readFile(eventsPath), initialEvents);

  await t.test('requires persistent binding and supported state', async () => {
    try {
      await atomicWriteJson(statePath, {...stateValue, status: 'initialized', phase: 'idle',
        revision: undefined, lesson_ir_sha256: undefined, review_sha256: undefined});
      await assert.rejects(inspectReview(root), /unavailable/);
    } finally {
      await writeFile(statePath, initialState);
    }
  });

  await t.test('rejects mismatched state revision and review hash', async () => {
    try {
      await atomicWriteJson(statePath, {...stateValue, revision: 9});
      await assert.rejects(inspectReview(root), /revision/);
      await atomicWriteJson(statePath, {...stateValue, review_sha256: 'f'.repeat(64)});
      await assert.rejects(inspectReview(root), /digest/);
    } finally {
      await writeFile(statePath, initialState);
    }
  });

  await t.test('rejects review task identity even when its state digest is updated', async () => {
    try {
      const changed = {...reviewValue, task_id: 'different-task'};
      await atomicWriteJson(reviewPath, changed);
      const changedBytes = await readFile(reviewPath);
      await atomicWriteJson(statePath, {...stateValue, review_sha256: sha256Bytes(changedBytes)});
      await assert.rejects(inspectReview(root), /identity/);
    } finally {
      await restoreReviewBaseline(reviewPath, reviewOriginal, statePath, initialState);
    }
    await inspectReview(root);
  });

  await t.test('rejects same-revision Lesson IR replacement and invalid IR digest', async () => {
    try {
      const titleBytes = Buffer.byteLength(irValue.episode.title, 'utf8');
      const changedTitle = 'x'.repeat(titleBytes);
      const originalTitle = JSON.stringify(irValue.episode.title);
      const replacement = JSON.stringify(changedTitle);
      const changedText = irOriginal.toString('utf8').replace(originalTitle, replacement);
      const changedBytes = Buffer.from(changedText, 'utf8');
      assert.equal(changedBytes.length, irOriginal.length);
      assert.doesNotThrow(() => JSON.parse(changedText));
      await writeFile(irPath, changedBytes);
      await assert.rejects(inspectReview(root), /digest/);
    } finally {
      await restoreReviewBaseline(reviewPath, reviewOriginal, statePath, initialState, irPath, irOriginal);
    }
    await inspectReview(root);
  });

  const pngPath = resolve(root, 'output', `storyboard-${irValue.scenes[0].id}.png`);
  const pngOriginal = await readFile(pngPath);
  await t.test('rejects PNG symlink and FIFO without blocking', async () => {
    const saved = resolve(root, 'saved-preview.png');
    let moved = false;
    try {
      await rename(pngPath, saved);
      moved = true;
      await symlink(saved, pngPath);
      await assert.rejects(inspectReview(root));
      await rm(pngPath);
      const fifo = spawnSync('/usr/bin/mkfifo', [pngPath], {encoding: 'utf8'});
      const fifoError = fifo.error as NodeJS.ErrnoException | null;
      if (fifoError?.code !== 'ENOENT') {
        assert.equal(fifo.status, 0, fifo.stderr);
        const started = Date.now();
        await assert.rejects(inspectReview(root));
        assert.ok(Date.now() - started < 1000, 'FIFO inspection must not wait for a writer');
      }
    } finally {
      await rm(pngPath, {force: true});
      if (moved) {
        await rename(saved, pngPath);
      }
    }
  });

  await t.test('rejects artifact size declarations before reading the artifact', async () => {
    try {
      const changed = structuredClone(reviewValue);
      const png = changed.artifacts.find((item: any) => item.media_type === 'image/png');
      png.size_bytes = 8 * 1024 * 1024 + 1;
      await atomicWriteJson(reviewPath, changed);
      const digest = sha256Bytes(await readFile(reviewPath));
      await atomicWriteJson(statePath, {...stateValue, review_sha256: digest});
      await assert.rejects(inspectReview(root), /size limit/);
    } finally {
      await restoreReviewBaseline(reviewPath, reviewOriginal, statePath, initialState);
    }
    await inspectReview(root);
  });

  await t.test('rejects aggregate declared artifact budget before opening artifacts', async () => {
    try {
      const changedIr = {...irValue, scenes: Array.from({length: 17}, (_, index) => ({
        ...irValue.scenes[index % irValue.scenes.length], id: `budget-${index}`,
      }))};
      await atomicWriteJson(irPath, changedIr);
      const changedIrBytes = await readFile(irPath);
      const changedReview = {
        ...reviewValue,
        lesson_ir: {...reviewValue.lesson_ir, size_bytes: changedIrBytes.length,
          sha256: sha256Bytes(changedIrBytes)},
        artifacts: [
          {...reviewValue.artifacts.find((item: any) => item.media_type === 'text/html'), size_bytes: 1},
          ...changedIr.scenes.map((scene: any) => ({
            role: 'storyboard', path: `storyboard-${scene.id}.png`, media_type: 'image/png',
            size_bytes: 8 * 1024 * 1024, sha256: 'a'.repeat(64),
          })),
        ],
      };
      await atomicWriteJson(reviewPath, changedReview);
      const changedReviewBytes = await readFile(reviewPath);
      await atomicWriteJson(statePath, {
        ...stateValue,
        lesson_ir_sha256: sha256Bytes(changedIrBytes),
        review_sha256: sha256Bytes(changedReviewBytes),
      });
      await assert.rejects(inspectReview(root), /total size limit/);
    } finally {
      await restoreReviewBaseline(reviewPath, reviewOriginal, statePath, initialState, irPath, irOriginal);
    }
    await inspectReview(root);
  });

  await t.test('rejects truncated or oversized PNG geometry', async () => {
    const before = await loadTaskState(root);
    try {
      const truncated = Buffer.concat([pngOriginal.subarray(0, 8), Buffer.alloc(16)]);
      await writeFile(pngPath, truncated);
      await updateArtifactBinding(root, reviewValue, stateValue, reviewPath, statePath, pngPath, truncated);
      await assert.rejects(inspectReview(root), /IHDR/);
      const oversized = Buffer.from(pngOriginal);
      if (oversized.length >= 33) oversized.writeUInt32BE(4097, 16);
      await writeFile(pngPath, oversized);
      await updateArtifactBinding(root, reviewValue, stateValue, reviewPath, statePath, pngPath, oversized);
      await assert.rejects(inspectReview(root), /dimensions/);
    } finally {
      await writeFile(pngPath, pngOriginal);
      await restoreReviewBaseline(reviewPath, reviewOriginal, statePath, initialState);
    }
    assert.deepEqual(await loadTaskState(root), before);
    await inspectReview(root);
  });

  assert.deepEqual(await readFile(statePath), initialState);
  assert.deepEqual(await readFile(eventsPath), initialEvents);
});

test('review CLI fails safely when persisted review state is missing', async () => {
  const root = resolve(roots, 'missing-review');
  await rm(root, {recursive: true, force: true});
  await mkdir(root, {recursive: true});
  const result = runReviewCli('review', root);
  assert.notEqual(result.status, 0);
  assert.equal(result.stdout, '');
  assert.equal(result.stderr, 'review failed\n');
});

async function makeTask(name: string): Promise<string> {
  const root = resolve(roots, name);
  await rm(root, {recursive: true, force: true});
  await mkdir(resolve(root, 'work'), {recursive: true});
  await mkdir(resolve(root, 'output'), {recursive: true});
  await cp(resolve(fixture, 'request.json'), resolve(root, 'request.json'));
  await cp(resolve(fixture, 'input'), resolve(root, 'input'), {recursive: true});
  await cp(resolve(fixture, 'expected/lesson.ir.json'), resolve(root, 'output/lesson.ir.json'));
  await ensureTaskState(root);
  return root;
}

function runReviewCli(actionOrRoot: string, rootOrAction: string) {
  const worker = resolve(skillRoot(), 'scripts/task-worker.ts');
  const nodeArgs = process.execArgv.filter(value => value !== '--test');
  return spawnSync(process.execPath, [...nodeArgs, worker, actionOrRoot, rootOrAction], {
    encoding: 'utf8', timeout: 30_000, maxBuffer: 2 * 1024 * 1024,
  });
}

async function updateArtifactBinding(
  root: string,
  originalReview: Record<string, any>,
  originalState: Record<string, any>,
  reviewPath: string,
  statePath: string,
  pngPath: string,
  png: Buffer,
): Promise<void> {
  const changed = structuredClone(originalReview);
  const relativePath = pngPath.slice(resolve(root, 'output').length + 1);
  const record = changed.artifacts.find((item: any) => item.path === relativePath);
  record.size_bytes = png.length;
  record.sha256 = sha256Bytes(png);
  await atomicWriteJson(reviewPath, changed);
  const reviewBytes = await readFile(reviewPath);
  await atomicWriteJson(statePath, {...originalState, review_sha256: sha256Bytes(reviewBytes)});
}

async function restoreReviewBaseline(
  reviewPath: string,
  review: Buffer,
  statePath: string,
  state: Buffer,
  irPath?: string,
  ir?: Buffer,
): Promise<void> {
  if (irPath && ir) await writeFile(irPath, ir);
  await writeFile(reviewPath, review);
  await writeFile(statePath, state);
}
