import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {cp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
import {promisify} from 'node:util';
import test from 'node:test';
import {
  atomicWriteJson,
  fileDescriptor,
  sha256File,
  skillRoot,
  type JsonObject,
} from '../scripts/lib.js';
import {validateAudio} from '../scripts/validate-audio.js';
import {validateIr} from '../scripts/validate-ir.js';
import {validateRequest} from '../scripts/validate-request.js';
import {validateResult} from '../scripts/validate-result.js';
import {assertOfflineStoryboard, validateReview} from '../scripts/validate-review.js';
import {indexAgentAudio} from '../scripts/index-agent-audio.js';
import {grantApproval} from '../scripts/task-worker.js';
import {validateSchema} from '../scripts/lib.js';

const require = createRequire(import.meta.url);
const ffmpegPath = (require('@ffmpeg-installer/ffmpeg') as {path: string}).path;
const execFileAsync = promisify(execFile);
const fixtureRoot = resolve(skillRoot(), 'tests/fixtures');
const localOutput = resolve(skillRoot(), '.local-output/tests');

test('quantity changes accept positive states and reject zero quantities', async () => {
  const ir = JSON.parse(await readFile(resolve(fixtureRoot, 'concept-process/expected/lesson.ir.json'), 'utf8'));
  ir.scenes[0].visual = {type: 'quantity_change', data: {unit: '元', states: [
    {label: '原价', value: 100}, {label: '降价后', value: 80}, {label: '涨价后', value: 96},
  ]}};
  await validateSchema('lesson-ir', ir);
  ir.scenes[0].visual.data.states[1].value = 0;
  await assert.rejects(validateSchema('lesson-ir', ir), /schema failed/);
});

for (const fixture of ['formula-note', 'concept-process', 'messy-note']) {
  test(`${fixture} request and Lesson IR pass executable validation`, async () => {
    const root = resolve(fixtureRoot, fixture);
    await validateRequest(root);
    await validateIr(root, resolve(root, 'expected/lesson.ir.json'));
  });
}

test('request validator rejects path traversal', async () => {
  const root = resolve(localOutput, 'invalid-traversal');
  await rm(root, {recursive: true, force: true});
  await cp(resolve(fixtureRoot, 'formula-note'), root, {recursive: true});
  const manifestPath = resolve(root, 'input/manifest.json');
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8')) as JsonObject;
  manifest.files[0].path = '../content.md';
  await writeFile(manifestPath, JSON.stringify(manifest));
  await assert.rejects(validateRequest(root), /manifest|path|canonical/);
});

test('Lesson IR validator rejects unsupported visual types', async () => {
  const root = resolve(localOutput, 'invalid-visual');
  await rm(root, {recursive: true, force: true});
  await cp(resolve(fixtureRoot, 'formula-note'), root, {recursive: true});
  const irPath = resolve(root, 'expected/lesson.ir.json');
  const ir = JSON.parse(await readFile(irPath, 'utf8')) as JsonObject;
  ir.scenes[0].visual.type = 'plain_text';
  await writeFile(irPath, JSON.stringify(ir));
  await assert.rejects(validateIr(root, irPath), /lesson-ir schema failed/);
});

test('storyboard validator rejects runtime network resources', () => {
  assert.doesNotThrow(() => assertOfflineStoryboard('<img src="data:image/png;base64,AA=="><a href="#scene">scene</a>'));
  assert.throws(() => assertOfflineStoryboard('<img src="https://example.invalid/image.png">'), /not self-contained/);
  assert.throws(() => assertOfflineStoryboard('<style>@import "theme.css"</style>'), /imports are forbidden/);
});

test('agent audio index derives a validated manifest from scene WAV files', async () => {
  const root = resolve(localOutput, 'agent-audio-index');
  await rm(root, {recursive: true, force: true});
  await mkdir(resolve(root, 'output'), {recursive: true});
  await mkdir(resolve(root, 'work/audio'), {recursive: true});
  await cp(resolve(fixtureRoot, 'formula-note/request.json'), resolve(root, 'request.json'));
  await cp(resolve(fixtureRoot, 'formula-note/input'), resolve(root, 'input'), {recursive: true});
  await cp(resolve(fixtureRoot, 'formula-note/expected/lesson.ir.json'), resolve(root, 'output/lesson.ir.json'));
  const ir = JSON.parse(await readFile(resolve(root, 'output/lesson.ir.json'), 'utf8')) as JsonObject;
  for (const scene of ir.scenes as JsonObject[]) {
    await writeFixtureWav(resolve(root, `work/audio/${scene.id}.wav`), 1250);
  }

  await assert.rejects(indexAgentAudio(root), /persisted storyboard approval is required/);
  await writeFile(resolve(root, 'output/storyboard.html'), '<!doctype html><p>offline</p>');
  const artifacts: JsonObject[] = [{role: 'storyboard', ...await fileDescriptor(
    resolve(root, 'output/storyboard.html'), 'storyboard.html', 'text/html')}];
  for (const scene of ir.scenes as JsonObject[]) {
    const name = `storyboard-${scene.id}.png`;
    await writeFile(resolve(root, 'output', name), Buffer.from(`PNG-${scene.id}`));
    artifacts.push({role: 'storyboard', ...await fileDescriptor(
      resolve(root, 'output', name), name, 'image/png')});
  }
  const request = JSON.parse(await readFile(resolve(root, 'request.json'), 'utf8')) as JsonObject;
  await atomicWriteJson(resolve(root, 'output/review.json'), {
    schema_version: '1.0', task_id: request.task_id,
    status: 'awaiting_storyboard_review', lesson_ir_revision: 1,
    lesson_ir: await fileDescriptor(resolve(root, 'output/lesson.ir.json'),
      'lesson.ir.json', 'application/json'),
    artifacts,
  });
  await grantApproval(root, 1);

  const manifest = await indexAgentAudio(root);

  assert.deepEqual((manifest.clips as JsonObject[]).map(clip => clip.scene_id), (ir.scenes as JsonObject[]).map(scene => scene.id));
  assert.ok((manifest.clips as JsonObject[]).every(clip => clip.duration_ms === 1250 && clip.fixture === undefined));
  assert.ok(manifest.input_binding);
});

test('review, audio, render and result validators accept a complete audited workspace', async () => {
  const root = resolve(localOutput, 'complete-contract');
  await rm(root, {recursive: true, force: true});
  await mkdir(resolve(root, 'work/audio'), {recursive: true});
  await mkdir(resolve(root, 'work/keyframes'), {recursive: true});
  await mkdir(resolve(root, 'output'), {recursive: true});
  await cp(resolve(fixtureRoot, 'formula-note/request.json'), resolve(root, 'request.json'));
  await cp(resolve(fixtureRoot, 'formula-note/input'), resolve(root, 'input'), {recursive: true});
  await cp(resolve(fixtureRoot, 'formula-note/expected/lesson.ir.json'), resolve(root, 'output/lesson.ir.json'));
  const ir = JSON.parse(await readFile(resolve(root, 'output/lesson.ir.json'), 'utf8')) as JsonObject;

  await writeFile(resolve(root, 'output/storyboard.html'), '<!doctype html><meta charset="utf-8"><p>offline storyboard</p>');
  await execFileAsync(ffmpegPath, ['-v', 'error', '-f', 'lavfi', '-i', 'color=c=0xf4f7fb:s=1080x1920', '-frames:v', '1', resolve(root, 'work/keyframes/base.png')]);
  for (const scene of ir.scenes as JsonObject[]) {
    await cp(resolve(root, 'work/keyframes/base.png'), resolve(root, `work/keyframes/${scene.id}.png`));
    await cp(resolve(root, 'work/keyframes/base.png'), resolve(root, `output/storyboard-${scene.id}.png`));
  }
  const storyboardArtifacts: JsonObject[] = [
    {...await fileDescriptor(resolve(root, 'output/storyboard.html'), 'storyboard.html', 'text/html'), role: 'storyboard'},
  ];
  for (const scene of ir.scenes as JsonObject[]) {
    const path = `storyboard-${scene.id}.png`;
    storyboardArtifacts.push({...await fileDescriptor(resolve(root, 'output', path), path, 'image/png'), role: 'storyboard'});
  }
  await atomicWriteJson(resolve(root, 'output/review.json'), {
    schema_version: '1.0', task_id: 'task-fixture-formula', status: 'awaiting_storyboard_review', lesson_ir_revision: 1,
    lesson_ir: await fileDescriptor(resolve(root, 'output/lesson.ir.json'), 'lesson.ir.json', 'application/json'),
    artifacts: storyboardArtifacts,
  });
  await validateReview(root);

  const clips: JsonObject[] = [];
  for (const scene of ir.scenes as JsonObject[]) {
    const relativePath = `work/audio/${scene.id}.wav`;
    const path = resolve(root, relativePath);
    await writeFixtureWav(path, 1000);
    clips.push({scene_id: scene.id, ...await fileDescriptor(path, relativePath, 'audio/wav'), duration_ms: 1000, fixture: true});
  }
  await atomicWriteJson(resolve(root, 'work/audio-manifest.json'), {schema_version: '1.0', clips});
  await validateAudio(root);

  const renderScenes = (ir.scenes as JsonObject[]).map((scene, index) => ({
    scene_id: scene.id,
    audio_path: clips[index]!.path,
    start_ms: index * 1000,
    end_ms: (index + 1) * 1000,
    start_frame: index * 30,
    end_frame: (index + 1) * 30,
    keyframe_path: `work/keyframes/${scene.id}.png`,
  }));
  const renderManifest = {
    schema_version: '1.0', task_id: 'task-fixture-formula',
    lesson_ir_sha256: await sha256File(resolve(root, 'output/lesson.ir.json')),
    audio_manifest_sha256: await sha256File(resolve(root, 'work/audio-manifest.json')),
    width: 1080, height: 1920, fps: 30, total_duration_ms: 4000, total_frames: 120,
    scenes: renderScenes,
    renderer: {name: 'revideo', version: '0.11.0'},
    ffmpeg: {path: ffmpegPath, version: 'N-47683-g0e8eb07980-static', configuration: '--enable-gpl --enable-version3 --enable-static --enable-libx264', license: 'GPL-3.0-or-later build'},
  };
  await atomicWriteJson(resolve(root, 'output/render.manifest.json'), renderManifest);
  await execFileAsync(ffmpegPath, [
    '-v', 'error', '-f', 'lavfi', '-i', 'color=c=0xf4f7fb:s=1080x1920:r=30:d=4',
    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=4',
    '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-shortest', resolve(root, 'output/explanation.mp4'),
  ]);
  await writeFile(resolve(root, 'output/captions.srt'), '1\n00:00:00,000 --> 00:00:01,000\n一\n\n2\n00:00:01,000 --> 00:00:02,000\n二\n\n3\n00:00:02,000 --> 00:00:03,000\n三\n\n4\n00:00:03,000 --> 00:00:04,000\n四\n');
  await cp(resolve(root, 'work/keyframes/base.png'), resolve(root, 'output/thumbnail.png'));
  const checks = ['source_hash_matches', 'scene_timing_matches_audio', 'captions_end_at_audio_end', 'safe_area_passed', 'keyframes_passed', 'composition_frames_match'];
  await atomicWriteJson(resolve(root, 'output/qa-report.json'), {schema_version: '1.0', status: 'passed', checks});
  const resultArtifacts = await Promise.all([
    ['video-main', 'video', 'explanation.mp4', 'video/mp4'],
    ['captions-main', 'captions', 'captions.srt', 'application/x-subrip'],
    ['thumbnail-main', 'thumbnail', 'thumbnail.png', 'image/png'],
    ['render-manifest', 'render_manifest', 'render.manifest.json', 'application/json'],
    ['qa-report', 'qa_report', 'qa-report.json', 'application/json'],
  ].map(async ([id, role, path, mediaType]) => ({id, role, ...await fileDescriptor(resolve(root, 'output', path!), path!, mediaType!)})));
  await atomicWriteJson(resolve(root, 'output/result.json'), {
    schema_version: '1.0', task_id: 'task-fixture-formula', status: 'completed',
    source: {bundle_sha256: ir.source.bundle_sha256, note_revision: 1},
    compiler: {skill: 'padnote-video-explainer', skill_version: '0.1.0', renderer: 'revideo', renderer_version: '0.11.0', agent_backend: 'test-fixture'},
    lesson_ir: {revision: 1, sha256: await sha256File(resolve(root, 'output/lesson.ir.json'))},
    artifacts: resultArtifacts,
    qa: {status: 'passed', checks},
  });
  await validateResult(root);
});

async function writeFixtureWav(path: string, durationMs: number): Promise<void> {
  const sampleRate = 8000;
  const samples = sampleRate * durationMs / 1000;
  const dataLength = samples * 2;
  const wav = Buffer.alloc(44 + dataLength);
  wav.write('RIFF', 0);
  wav.writeUInt32LE(36 + dataLength, 4);
  wav.write('WAVEfmt ', 8);
  wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20);
  wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(sampleRate, 24);
  wav.writeUInt32LE(sampleRate * 2, 28);
  wav.writeUInt16LE(2, 32);
  wav.writeUInt16LE(16, 34);
  wav.write('data', 36);
  wav.writeUInt32LE(dataLength, 40);
  for (let index = 0; index < samples; index += 1) {
    const value = Math.round(4000 * Math.sin(2 * Math.PI * 440 * index / sampleRate));
    wav.writeInt16LE(value, 44 + index * 2);
  }
  await writeFile(path, wav);
}
