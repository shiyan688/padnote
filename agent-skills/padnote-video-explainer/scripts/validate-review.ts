import {relative, resolve, sep} from 'node:path';
import {readFile, stat} from 'node:fs/promises';
import {
  assertExistingFileInside,
  readJson,
  requireCliTaskRoot,
  sha256File,
  unique,
  validateSchema,
  verifyFileRecord,
  type JsonObject,
} from './lib.js';
import {validateIr} from './validate-ir.js';
import {validateRequest} from './validate-request.js';

export async function validateReview(taskRoot: string, reviewPath?: string): Promise<JsonObject> {
  const request = await validateRequest(taskRoot);
  const absolute = resolve(reviewPath ?? resolve(taskRoot, 'output/review.json'));
  const path = relative(taskRoot, absolute).split(sep).join('/');
  const reviewFile = await assertExistingFileInside(taskRoot, path);
  const review = await readJson(reviewFile);
  await validateSchema('review', review);
  if (review.task_id !== request.task_id) throw new Error('review task_id does not match request');

  const irPath = resolve(taskRoot, 'output', review.lesson_ir.path);
  const ir = await validateIr(taskRoot, irPath);
  await verifyFileRecord(taskRoot, 'output', review.lesson_ir);
  if (review.lesson_ir.path !== 'lesson.ir.json' || review.lesson_ir.media_type !== 'application/json') {
    throw new Error('review lesson_ir must point to output/lesson.ir.json');
  }
  if (review.lesson_ir.sha256 !== await sha256File(irPath)) throw new Error('review Lesson IR hash mismatch');

  const artifacts = review.artifacts as JsonObject[];
  unique(artifacts.map(artifact => artifact.path), 'review artifact path');
  for (const artifact of artifacts) {
    const path = await verifyFileRecord(taskRoot, 'output', artifact);
    if (artifact.media_type === 'text/html') assertOfflineStoryboard(await readFile(path, 'utf8'));
  }
  if (!artifacts.some(artifact => artifact.path === 'storyboard.html' && artifact.media_type === 'text/html')) {
    throw new Error('review is missing storyboard.html');
  }
  for (const scene of ir.scenes as JsonObject[]) {
    const path = `storyboard-${scene.id}.png`;
    if (!artifacts.some(artifact => artifact.path === path && artifact.media_type === 'image/png')) {
      throw new Error(`review is missing ${path}`);
    }
  }
  const reviewMtime = (await stat(reviewFile, {bigint: true})).mtimeNs;
  for (const path of [irPath, ...artifacts.map(artifact => resolve(taskRoot, 'output', artifact.path))]) {
    if (reviewMtime <= (await stat(path, {bigint: true})).mtimeNs) throw new Error('review.json must be written after every storyboard artifact');
  }
  return review;
}

export function assertOfflineStoryboard(source: string): void {
  for (const match of source.matchAll(/\b(?:src|href)\s*=\s*["']([^"']+)["']/gi)) {
    if (!match[1]!.startsWith('data:') && !match[1]!.startsWith('#')) {
      throw new Error(`storyboard HTML is not self-contained: ${match[1]}`);
    }
  }
  for (const match of source.matchAll(/\burl\(\s*["']?([^)'"\s]+)["']?\s*\)/gi)) {
    if (!match[1]!.startsWith('data:')) throw new Error(`storyboard CSS is not self-contained: ${match[1]}`);
  }
  if (/@import\b/i.test(source)) throw new Error('storyboard CSS imports are forbidden');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  validateReview(requireCliTaskRoot(), process.argv[3]).then(review => {
    console.log(`review valid: revision ${review.lesson_ir_revision}`);
  }).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
