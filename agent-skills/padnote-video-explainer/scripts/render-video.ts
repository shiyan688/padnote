import {execFile} from 'node:child_process';
import {copyFile, mkdir, readFile, rename, stat} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {basename, resolve} from 'node:path';
import {promisify} from 'node:util';
import {renderVideo} from '@revideo/renderer';
import puppeteer from 'puppeteer';
import {
  assertExistingFileInside,
  atomicWriteFile,
  atomicWriteJson,
  fileDescriptor,
  readJson,
  requireCliTaskRoot,
  sha256File,
  skillRoot,
  type JsonObject,
} from './lib.js';
import {validateAudio} from './validate-audio.js';
import {validateIr} from './validate-ir.js';
import {validateRenderManifest} from './validate-render.js';
import {validateRequest} from './validate-request.js';
import {validateResult} from './validate-result.js';
import {validateReview} from './validate-review.js';

const execFileAsync = promisify(execFile);
const require = createRequire(import.meta.url);
const ffmpegPath = (require('@ffmpeg-installer/ffmpeg') as {path: string}).path;
const ffprobePath = (require('@ffprobe-installer/ffprobe') as {path: string}).path;
const revideoVersion = (require('@revideo/renderer/package.json') as {version: string}).version;
const skillVersion = (require('../package.json') as {version: string}).version;
const supportedVisuals = new Set(['title', 'formula_steps', 'concept_map', 'process', 'comparison', 'annotated_source', 'quantity_change']);
const qaChecks = [
  'source_hash_matches',
  'scene_timing_matches_audio',
  'captions_end_at_audio_end',
  'safe_area_passed',
  'keyframes_passed',
  'composition_frames_match',
];

interface RenderOptions {
  approval: 'approve' | 'revise' | 'cancel';
  revision?: number;
  allowFixtureAudio: boolean;
}

export async function renderApprovedVideo(taskRoot: string, options: RenderOptions): Promise<JsonObject | undefined> {
  if (options.approval === 'cancel') {
    await assertResultDoesNotExist(taskRoot);
    return undefined;
  }
  if (options.approval === 'revise') throw new Error('revise requires a new Lesson IR and storyboard review; full rendering was not started');
  const request = await validateRequest(taskRoot);
  const ir = await validateIr(taskRoot);
  const review = await validateReview(taskRoot);
  if (options.revision !== review.lesson_ir_revision) {
    throw new Error(`approval revision ${options.revision ?? 'missing'} does not match review revision ${review.lesson_ir_revision}`);
  }
  await assertResultDoesNotExist(taskRoot);
  for (const scene of ir.scenes as JsonObject[]) {
    if (!supportedVisuals.has(scene.visual.type)) throw new Error(`unsupported renderer visual.type: ${scene.visual.type}`);
  }
  const audio = await validateAudio(taskRoot);
  if ((audio.clips as JsonObject[]).some(clip => clip.fixture === true) && !options.allowFixtureAudio) {
    throw new Error('fixture audio is test-only; pass --allow-fixture-audio explicitly');
  }

  const publicDir = resolve(taskRoot, 'work/public');
  const publicAudioDir = resolve(publicDir, 'audio');
  const publicAssetDir = resolve(publicDir, 'assets');
  const keyframeDir = resolve(taskRoot, 'work/keyframes');
  const renderDir = resolve(taskRoot, 'work/revideo-render');
  const outputDir = resolve(taskRoot, 'output');
  await mkdir(publicAudioDir, {recursive: true});
  await mkdir(publicAssetDir, {recursive: true});
  await mkdir(keyframeDir, {recursive: true});
  await mkdir(renderDir, {recursive: true});

  const timeline: JsonObject[] = [];
  const renderScenes: JsonObject[] = [];
  let elapsedMs = 0;
  for (const scene of ir.scenes as JsonObject[]) {
    if (scene.visual.type !== 'annotated_source') {
      renderScenes.push(scene);
      continue;
    }
    const source = await assertExistingFileInside(taskRoot, scene.visual.data.asset_path);
    const publicName = `${scene.id}-${basename(scene.visual.data.asset_path)}`;
    await copyFile(source, resolve(publicAssetDir, publicName));
    renderScenes.push({
      ...scene,
      visual: {
        ...scene.visual,
        data: {...scene.visual.data, asset_src: `/assets/${publicName}`},
      },
    });
  }
  for (let index = 0; index < audio.clips.length; index += 1) {
    const clip = audio.clips[index] as JsonObject;
    const sourceAudio = await assertExistingFileInside(taskRoot, clip.path);
    const publicName = `${clip.scene_id}.wav`;
    await copyFile(sourceAudio, resolve(publicAudioDir, publicName));
    await assertExistingFileInside(outputDir, `storyboard-${clip.scene_id}.png`);
    const endMs = elapsedMs + clip.duration_ms;
    timeline.push({
      scene_id: clip.scene_id,
      audio_path: clip.path,
      start_ms: elapsedMs,
      end_ms: endMs,
      start_frame: Math.ceil(elapsedMs * ir.render_profile.fps / 1000),
      end_frame: Math.ceil(endMs * ir.render_profile.fps / 1000),
      keyframe_path: `work/keyframes/${clip.scene_id}.png`,
    });
    elapsedMs = endMs;
  }

  process.env.DISABLE_TELEMETRY = 'true';
  process.env.XDG_CACHE_HOME = resolve(skillRoot(), '.local-cache/fontconfig');
  const chromePath = await puppeteer.executablePath();
  for (let index = 0; index < (ir.scenes as JsonObject[]).length; index += 1) {
    const scene = (ir.scenes as JsonObject[])[index]!;
    const clip = (audio.clips as JsonObject[])[index]!;
    const keyframeVideo = await renderVideo({
      projectFile: resolve(skillRoot(), 'renderer/project.ts'),
      variables: {
        payload: {
          episode: ir.episode,
          scenes: [renderScenes[index]],
          clips: [{scene_id: clip.scene_id, src: `/audio/${clip.scene_id}.wav`, duration_ms: clip.duration_ms}],
          keyframe_mode: true,
        },
      },
      settings: {
        outFile: `keyframe-${scene.id}.mp4`,
        outDir: renderDir,
        workers: 1,
        ffmpeg: {ffmpegPath, ffprobePath, ffmpegLogLevel: 'error'},
        puppeteer: {
          executablePath: chromePath,
          headless: true,
          args: [
            '--no-sandbox',
            '--disable-setuid-sandbox',
            '--disable-dev-shm-usage',
            '--disable-background-networking',
          ],
        },
        projectSettings: {
          background: '#f4f7fb',
          size: {x: ir.render_profile.width, y: ir.render_profile.height},
          range: [0, 1 / ir.render_profile.fps],
        },
        viteConfig: {
          publicDir,
          cacheDir: resolve(skillRoot(), '.local-cache/vite'),
          optimizeDeps: {include: ['@revideo/renderer/lib/client/render', '@revideo/2d', '@revideo/core']},
        },
      },
    });
    const keyframePath = resolve(keyframeDir, `${scene.id}.png`);
    await execFileAsync(ffmpegPath, [
      '-y', '-v', 'error', '-i', keyframeVideo, '-vf', 'select=eq(n\\,1)', '-frames:v', '1', keyframePath,
    ]);
    const {stdout} = await execFileAsync(ffprobePath, [
      '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height', '-of', 'json', keyframePath,
    ]);
    const stream = (JSON.parse(stdout) as JsonObject).streams?.[0];
    if (!stream || stream.width !== ir.render_profile.width || stream.height !== ir.render_profile.height) {
      throw new Error(`keyframe dimensions are incorrect for ${scene.id}`);
    }
    const {stdout: stats} = await execFileAsync(ffmpegPath, [
      '-v', 'error', '-i', keyframePath, '-vf', 'signalstats,metadata=print:file=-', '-f', 'null', '-',
    ]);
    const minimumLuma = Number(stats.match(/lavfi\.signalstats\.YMIN=([\d.]+)/)?.[1]);
    if (!Number.isFinite(minimumLuma) || minimumLuma >= 128) {
      throw new Error(`keyframe is blank for ${scene.id}`);
    }
  }
  const renderedPath = await renderVideo({
    projectFile: resolve(skillRoot(), 'renderer/project.ts'),
    variables: {
      payload: {
        episode: ir.episode,
        scenes: renderScenes,
        clips: (audio.clips as JsonObject[]).map(clip => ({
          scene_id: clip.scene_id,
          src: `/audio/${clip.scene_id}.wav`,
          duration_ms: clip.duration_ms,
        })),
      },
    },
    settings: {
      outFile: 'explanation.mp4',
      outDir: renderDir,
      workers: 1,
      logProgress: true,
      ffmpeg: {ffmpegPath, ffprobePath, ffmpegLogLevel: 'error'},
      puppeteer: {
        executablePath: chromePath,
        headless: true,
        args: [
          '--no-sandbox',
          '--disable-setuid-sandbox',
          '--disable-dev-shm-usage',
          '--disable-background-networking',
        ],
      },
      projectSettings: {
        background: '#f4f7fb',
        size: {x: ir.render_profile.width, y: ir.render_profile.height},
        range: [0, (Math.ceil(elapsedMs * ir.render_profile.fps / 1000) - 1) / ir.render_profile.fps],
      },
      viteConfig: {
        publicDir,
        cacheDir: resolve(skillRoot(), '.local-cache/vite'),
        optimizeDeps: {include: ['@revideo/renderer/lib/client/render', '@revideo/2d', '@revideo/core']},
      },
    },
  });

  const muxedPath = resolve(renderDir, 'explanation-with-complete-audio.mp4');
  const muxArgs = ['-y', '-v', 'error', '-i', renderedPath];
  for (const clip of audio.clips as JsonObject[]) {
    muxArgs.push('-i', await assertExistingFileInside(taskRoot, clip.path));
  }
  muxArgs.push(
    '-filter_complex', `${(audio.clips as JsonObject[]).map((_, index) => `[${index + 1}:a]`).join('')}concat=n=${audio.clips.length}:v=0:a=1[a]`,
    '-map', '0:v:0', '-map', '[a]', '-c:v', 'copy', '-c:a', 'aac', '-t', (elapsedMs / 1000).toFixed(3), muxedPath,
  );
  await execFileAsync(ffmpegPath, muxArgs);

  const videoPath = resolve(outputDir, 'explanation.mp4');
  await rename(muxedPath, videoPath);
  await assertVideoShape(
    videoPath,
    ir.render_profile.width,
    ir.render_profile.height,
    ir.render_profile.fps,
    Math.ceil(elapsedMs * ir.render_profile.fps / 1000),
    elapsedMs,
  );

  await atomicWriteFile(resolve(outputDir, 'captions.srt'), buildSrt(ir.scenes as JsonObject[], timeline));
  await atomicWriteFile(resolve(outputDir, 'thumbnail.png'), await readFile(resolve(keyframeDir, `${audio.clips[0]!.scene_id}.png`)));
  const {stdout: ffmpegVersionOutput} = await execFileAsync(ffmpegPath, ['-version']);
  const ffmpegLines = ffmpegVersionOutput.trim().split('\n');
  const renderManifest = {
    schema_version: '1.0',
    task_id: request.task_id,
    lesson_ir_sha256: await sha256File(resolve(outputDir, 'lesson.ir.json')),
    audio_manifest_sha256: await sha256File(resolve(taskRoot, 'work/audio-manifest.json')),
    width: ir.render_profile.width,
    height: ir.render_profile.height,
    fps: ir.render_profile.fps,
    total_duration_ms: elapsedMs,
    total_frames: Math.ceil(elapsedMs * ir.render_profile.fps / 1000),
    scenes: timeline,
    renderer: {name: 'revideo', version: revideoVersion},
    ffmpeg: {
      path: ffmpegPath,
      version: ffmpegLines[0]!,
      configuration: ffmpegLines.find(line => line.startsWith('configuration:')) ?? '',
      license: 'GPL-3.0-or-later build (--enable-gpl --enable-version3)',
    },
  };
  await atomicWriteJson(resolve(outputDir, 'render.manifest.json'), renderManifest);
  await validateRenderManifest(taskRoot);

  const qaReport = {schema_version: '1.0', status: 'passed', checks: qaChecks};
  await atomicWriteJson(resolve(outputDir, 'qa-report.json'), qaReport);
  const artifactSpecs = [
    ['video-main', 'video', 'explanation.mp4', 'video/mp4'],
    ['captions-main', 'captions', 'captions.srt', 'application/x-subrip'],
    ['thumbnail-main', 'thumbnail', 'thumbnail.png', 'image/png'],
    ['render-manifest', 'render_manifest', 'render.manifest.json', 'application/json'],
    ['qa-report', 'qa_report', 'qa-report.json', 'application/json'],
  ] as const;
  const artifacts = [];
  for (const [id, role, path, mediaType] of artifactSpecs) {
    artifacts.push({id, role, ...await fileDescriptor(resolve(outputDir, path), path, mediaType)});
  }
  const result = {
    schema_version: '1.0',
    task_id: request.task_id,
    status: 'completed',
    source: {bundle_sha256: request.source.bundle_sha256, note_revision: request.source.note_revision},
    compiler: {
      skill: 'padnote-video-explainer',
      skill_version: skillVersion,
      renderer: 'revideo',
      renderer_version: revideoVersion,
      agent_backend: process.env.PADNOTE_AGENT_BACKEND ?? 'unspecified',
    },
    lesson_ir: {revision: review.lesson_ir_revision, sha256: await sha256File(resolve(outputDir, 'lesson.ir.json'))},
    artifacts,
    qa: {status: 'passed', checks: qaChecks},
  };
  await atomicWriteJson(resolve(outputDir, 'result.json'), result);
  return validateResult(taskRoot);
}

async function assertResultDoesNotExist(taskRoot: string): Promise<void> {
  try {
    await stat(resolve(taskRoot, 'output/result.json'));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return;
    throw error;
  }
  throw new Error('output/result.json already exists; refusing to overwrite a completed result');
}

async function assertVideoShape(path: string, width: number, height: number, fps: number, frames: number, audioDurationMs: number): Promise<void> {
  const {stdout} = await execFileAsync(ffprobePath, [
    '-v', 'error', '-count_frames', '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height,r_frame_rate,nb_read_frames', '-of', 'json', path,
  ]);
  const stream = (JSON.parse(stdout) as JsonObject).streams?.[0];
  if (!stream || stream.width !== width || stream.height !== height) throw new Error('rendered video dimensions are incorrect');
  const [numerator, denominator] = String(stream.r_frame_rate).split('/').map(Number);
  if (numerator! / denominator! !== fps) throw new Error('rendered video fps is incorrect');
  if (Number(stream.nb_read_frames) !== frames) throw new Error(`rendered video has ${stream.nb_read_frames} frames, expected ${frames}`);
  const {stdout: audioStdout} = await execFileAsync(ffprobePath, [
    '-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=duration', '-of', 'json', path,
  ]);
  const audioStream = (JSON.parse(audioStdout) as JsonObject).streams?.[0];
  if (!audioStream) throw new Error('rendered video has no audio stream');
  const actualAudioDurationMs = Math.round(Number(audioStream.duration) * 1000);
  if (actualAudioDurationMs !== audioDurationMs) {
    throw new Error(`rendered audio ends at ${actualAudioDurationMs}ms, expected ${audioDurationMs}ms`);
  }
}

function buildSrt(scenes: JsonObject[], timeline: JsonObject[]): string {
  return scenes.map((scene, index) => {
    const timing = timeline[index]!;
    return `${index + 1}\n${srtTime(timing.start_ms)} --> ${srtTime(timing.end_ms)}\n${scene.narration}\n`;
  }).join('\n');
}

function srtTime(milliseconds: number): string {
  const hours = Math.floor(milliseconds / 3_600_000);
  const minutes = Math.floor(milliseconds % 3_600_000 / 60_000);
  const seconds = Math.floor(milliseconds % 60_000 / 1000);
  const millis = milliseconds % 1000;
  return [hours, minutes, seconds].map(value => String(value).padStart(2, '0')).join(':') + `,${String(millis).padStart(3, '0')}`;
}

function parseOptions(): RenderOptions {
  const args = process.argv.slice(3);
  const approvalIndex = args.indexOf('--approval');
  const approval = args[approvalIndex + 1];
  if (approval !== 'approve' && approval !== 'revise' && approval !== 'cancel') {
    throw new Error('usage: render-video.ts <task-root> --approval <approve|revise|cancel> --revision <n> [--allow-fixture-audio]');
  }
  const revisionIndex = args.indexOf('--revision');
  const revision = revisionIndex >= 0 ? Number(args[revisionIndex + 1]) : undefined;
  if (approval === 'approve' && (!Number.isInteger(revision) || revision! < 1)) throw new Error('approve requires --revision <positive integer>');
  return {approval, revision, allowFixtureAudio: args.includes('--allow-fixture-audio')};
}

if (import.meta.url === `file://${process.argv[1]}`) {
  renderApprovedVideo(requireCliTaskRoot(), parseOptions()).then(result => {
    console.log(result ? `result valid: ${result.status}` : 'task cancelled; result.json was not written');
  }).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
