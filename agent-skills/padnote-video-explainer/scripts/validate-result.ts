import {execFile} from 'node:child_process';
import {readFile, stat} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {relative, resolve, sep} from 'node:path';
import {promisify} from 'node:util';
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
import {validateRenderManifest} from './validate-render.js';
import {validateRequest} from './validate-request.js';
import {validateReview} from './validate-review.js';

const requiredRoles = ['video', 'captions', 'thumbnail', 'render_manifest', 'qa_report'];
const require = createRequire(import.meta.url);
const ffprobePath = (require('@ffprobe-installer/ffprobe') as {path: string}).path;
const execFileAsync = promisify(execFile);

export async function validateResult(taskRoot: string, resultPath?: string): Promise<JsonObject> {
  const request = await validateRequest(taskRoot);
  await validateIr(taskRoot);
  const review = await validateReview(taskRoot);
  const absolute = resolve(resultPath ?? resolve(taskRoot, 'output/result.json'));
  const relativePath = relative(taskRoot, absolute).split(sep).join('/');
  const file = await assertExistingFileInside(taskRoot, relativePath);
  const result = await readJson(file);
  await validateSchema('result', result);
  if (result.task_id !== request.task_id) throw new Error('result task_id mismatch');
  if (result.source.bundle_sha256 !== request.source.bundle_sha256 || result.source.note_revision !== request.source.note_revision) {
    throw new Error('result source mismatch');
  }
  const irHash = await sha256File(resolve(taskRoot, 'output/lesson.ir.json'));
  if (result.lesson_ir.sha256 !== irHash) throw new Error('result Lesson IR hash mismatch');
  if (result.lesson_ir.revision !== review.lesson_ir_revision) throw new Error('result Lesson IR revision mismatch');

  const artifacts = result.artifacts as JsonObject[];
  unique(artifacts.map(artifact => artifact.id), 'artifact id');
  unique(artifacts.map(artifact => artifact.path), 'artifact path');
  const roles = artifacts.map(artifact => artifact.role).sort();
  if (JSON.stringify(roles) !== JSON.stringify([...requiredRoles].sort())) throw new Error('result must contain exactly the five required artifact roles');
  for (const artifact of artifacts) await verifyFileRecord(taskRoot, 'output', artifact);

  const renderArtifact = artifacts.find(artifact => artifact.role === 'render_manifest')!;
  const renderManifest = await validateRenderManifest(taskRoot, resolve(taskRoot, 'output', renderArtifact.path));
  const videoArtifact = artifacts.find(artifact => artifact.role === 'video')!;
  await validateVideo(resolve(taskRoot, 'output', videoArtifact.path), renderManifest);
  const captionsArtifact = artifacts.find(artifact => artifact.role === 'captions')!;
  await validateCaptions(resolve(taskRoot, 'output', captionsArtifact.path), renderManifest.total_duration_ms);
  const thumbnailArtifact = artifacts.find(artifact => artifact.role === 'thumbnail')!;
  const thumbnail = await readFile(resolve(taskRoot, 'output', thumbnailArtifact.path));
  if (thumbnail.length < 24 || thumbnail.toString('hex', 0, 8) !== '89504e470d0a1a0a') throw new Error('thumbnail is not a PNG');
  const qaArtifact = artifacts.find(artifact => artifact.role === 'qa_report')!;
  const qa = await readJson(resolve(taskRoot, 'output', qaArtifact.path));
  if (qa.status !== 'passed' || !Array.isArray(qa.checks)) throw new Error('qa-report.json is not passed');
  if (JSON.stringify([...qa.checks].sort()) !== JSON.stringify([...result.qa.checks].sort())) throw new Error('result QA checks do not match qa-report.json');

  const resultMtime = (await stat(file, {bigint: true})).mtimeNs;
  for (const artifact of artifacts) {
    const artifactM = (await stat(resolve(taskRoot, 'output', artifact.path), {bigint: true})).mtimeNs;
    if (resultMtime <= artifactM) throw new Error('result.json must be written after every artifact');
  }
  return result;
}

async function validateVideo(path: string, manifest: JsonObject): Promise<void> {
  const {stdout} = await execFileAsync(ffprobePath, [
    '-v', 'error', '-count_frames', '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height,r_frame_rate,nb_read_frames', '-of', 'json', path,
  ]);
  const probe = JSON.parse(stdout) as JsonObject;
  const stream = probe.streams?.[0] as JsonObject | undefined;
  if (!stream) throw new Error('MP4 has no video stream');
  if (stream.width !== manifest.width || stream.height !== manifest.height) throw new Error('MP4 dimensions do not match render manifest');
  const [numerator, denominator] = String(stream.r_frame_rate).split('/').map(Number);
  if (numerator! / denominator! !== manifest.fps) throw new Error('MP4 fps does not match render manifest');
  if (Number(stream.nb_read_frames) !== manifest.total_frames) throw new Error('MP4 frame count does not match render manifest');
  const {stdout: audioStdout} = await execFileAsync(ffprobePath, [
    '-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=duration', '-of', 'json', path,
  ]);
  const audioStream = (JSON.parse(audioStdout) as JsonObject).streams?.[0] as JsonObject | undefined;
  if (!audioStream) throw new Error('MP4 has no audio stream');
  const audioEndMs = Math.round(Number(audioStream.duration) * 1000);
  if (audioEndMs !== manifest.total_duration_ms) throw new Error('MP4 audio duration does not match render manifest');
}

async function validateCaptions(path: string, expectedEndMs: number): Promise<void> {
  const source = await readFile(path, 'utf8');
  const matches = [...source.matchAll(/-->\s*(\d{2}):(\d{2}):(\d{2}),(\d{3})/g)];
  const last = matches.at(-1);
  if (!last) throw new Error('captions.srt contains no cue');
  const endMs = ((Number(last[1]) * 60 + Number(last[2])) * 60 + Number(last[3])) * 1000 + Number(last[4]);
  if (endMs !== expectedEndMs) throw new Error(`captions end at ${endMs}ms, expected ${expectedEndMs}ms`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  validateResult(requireCliTaskRoot(), process.argv[3]).then(result => {
    console.log(`result valid: ${result.status}`);
  }).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
