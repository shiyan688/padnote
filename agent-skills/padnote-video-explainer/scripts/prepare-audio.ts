import {execFile} from 'node:child_process';
import {isAbsolute, resolve} from 'node:path';
import {access, mkdir, rm} from 'node:fs/promises';
import {promisify} from 'node:util';
import {
  atomicWriteJson,
  fileDescriptor,
  readJson,
  requireCliTaskRoot,
  sha256Bytes,
  sha256File,
  verifyFileRecord,
  type JsonObject,
} from './lib.js';
import {
  audioSceneInputDigest,
  parseWav,
  validateAudio,
  type AudioInputBinding,
} from './validate-audio.js';
import {validateIr} from './validate-ir.js';
import {validateRequest} from './validate-request.js';
import {buildTtsChildEnvironment} from './tts-environment.js';

const execFileAsync = promisify(execFile);

export interface AudioPreparationOptions {
  irPath?: string;
  inputBinding?: AudioInputBinding;
  irObject?: JsonObject;
  requestObject?: JsonObject;
}

export async function prepareAudio(
  taskRoot: string,
  options: AudioPreparationOptions = {},
): Promise<JsonObject> {
  const command = process.env.PADNOTE_TTS_COMMAND;
  if (!command) throw new TtsNotConfiguredError();
  if (!isAbsolute(command)) throw new Error('PADNOTE_TTS_COMMAND must be an absolute executable path');
  const request = options.requestObject ?? await validateRequest(taskRoot);
  const ir = options.irObject ?? await validateIr(taskRoot, options.irPath);
  const audioDir = resolve(taskRoot, 'work/audio');
  const checkpointDir = resolve(taskRoot, 'work/audio-checkpoints');
  await mkdir(audioDir, {recursive: true});
  await mkdir(checkpointDir, {recursive: true});
  const clips: JsonObject[] = [];
  for (const scene of ir.scenes as JsonObject[]) {
    const fileName = `${scene.id}.wav`;
    const output = resolve(audioDir, fileName);
    const checkpointPath = resolve(checkpointDir, `${scene.id}.json`);
    const inputDigest = options.inputBinding
      ? audioSceneInputDigest(options.inputBinding, scene)
      : sha256Bytes(JSON.stringify({
        request_voice: request.voice,
        lesson_ir_sha256: await sha256File(options.irPath ?? resolve(taskRoot, 'output/lesson.ir.json')),
        scene_id: scene.id,
        narration: scene.narration,
      }));
    const previous = await optionalJson(checkpointPath);
    if (previous?.input_sha256 === inputDigest && previous.status === 'completed') {
      const clip = previous.clip as JsonObject;
      await verifyFileRecord(taskRoot, 'task', clip);
      await parseWav(output);
      clips.push(clip);
      continue;
    }
    // A prior process may have finished writing the deterministic output but
    // died before recording completion. Reconcile it without a second charge.
    if (previous?.input_sha256 === inputDigest && previous.status === 'started'
        && await fileExists(output)) {
      const clip = await describeClip(taskRoot, scene, output, fileName, inputDigest);
      await atomicWriteJson(checkpointPath, {
        schema_version: '1.0', status: 'completed', scene_id: scene.id,
        input_sha256: inputDigest, provider_idempotency_key: inputDigest,
        started_at: previous.started_at, completed_at: new Date().toISOString(), clip,
      });
      clips.push(clip);
      continue;
    }
    await rm(output, {force: true});
    await atomicWriteJson(checkpointPath, {
      schema_version: '1.0', status: 'started', scene_id: scene.id,
      input_sha256: inputDigest, provider_idempotency_key: inputDigest,
      started_at: new Date().toISOString(),
    });
    await execFileAsync(command, [], {
      env: buildTtsChildEnvironment({
        sceneId: String(scene.id),
        text: String(scene.narration),
        language: String(ir.episode.language),
        voiceProfile: String(request.voice.profile),
        speed: String(request.voice.speed),
        output,
        idempotencyKey: inputDigest,
      }),
      maxBuffer: 1024 * 1024,
    });
    const clip = await describeClip(taskRoot, scene, output, fileName, inputDigest);
    await atomicWriteJson(checkpointPath, {
      schema_version: '1.0', status: 'completed', scene_id: scene.id,
      input_sha256: inputDigest, provider_idempotency_key: inputDigest,
      started_at: (await readJson(checkpointPath)).started_at,
      completed_at: new Date().toISOString(), clip,
    });
    clips.push(clip);
  }
  const manifest = {
    schema_version: '1.0',
    ...(options.inputBinding ? {input_binding: options.inputBinding} : {}),
    clips,
  };
  await atomicWriteJson(resolve(taskRoot, 'work/audio-manifest.json'), manifest);
  return validateAudio(taskRoot, undefined, options.inputBinding, options.irPath);
}

async function describeClip(
  taskRoot: string,
  scene: JsonObject,
  output: string,
  fileName: string,
  inputDigest: string,
): Promise<JsonObject> {
  const wav = await parseWav(output);
  return {
    scene_id: scene.id,
    ...await fileDescriptor(output, `work/audio/${fileName}`, 'audio/wav'),
    duration_ms: wav.durationMs,
    input_sha256: inputDigest,
    provider_idempotency_key: inputDigest,
  };
}

async function optionalJson(path: string): Promise<JsonObject | undefined> {
  try {
    return await readJson(path);
  } catch (error: any) {
    if (error?.code === 'ENOENT' || error instanceof SyntaxError) return undefined;
    throw error;
  }
}

async function fileExists(path: string): Promise<boolean> {
  try {
    await access(path);
    return true;
  } catch (error: any) {
    if (error?.code === 'ENOENT') return false;
    throw error;
  }
}

class TtsNotConfiguredError extends Error {
  readonly payload = {code: 'tts_not_configured', message: '没有配置可用的语音合成能力'};

  constructor() {
    super('TTS is not configured');
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  prepareAudio(requireCliTaskRoot()).then(manifest => {
    console.log(`audio prepared: ${manifest.clips.length} clips`);
  }).catch(error => {
    if (error instanceof TtsNotConfiguredError) console.error(JSON.stringify(error.payload));
    else console.error(error);
    process.exitCode = 1;
  });
}
