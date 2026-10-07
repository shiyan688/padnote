import {execFile, spawn} from 'node:child_process';
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
  adapterCommand?: string;
  adapterArgs?: string[];
  adapterInput?: JsonObject;
  adapterEnvironment?: NodeJS.ProcessEnv;
}

const TTS_ADAPTER_CODES = new Set([
  'tts_input_invalid', 'tts_provider_request_failed', 'tts_provider_http_error',
  'tts_provider_response_invalid', 'tts_provider_response_too_large',
  'tts_audio_url_missing', 'tts_audio_url_rejected', 'tts_audio_download_failed',
  'tts_audio_download_http_error', 'tts_audio_download_too_large', 'tts_audio_format_invalid',
  'tts_audio_output_write_failed', 'tts_adapter_failure', 'tts_adapter_result_invalid',
  'tts_adapter_start_failed',
]);

export class TtsAdapterError extends Error {
  readonly code: string;
  readonly stage: string;
  readonly externalEffectPossible: boolean;
  readonly httpStatus?: number;

  constructor(code: string, stage: string, externalEffectPossible: boolean, httpStatus?: number) {
    super(code);
    this.name = 'TtsAdapterError';
    this.code = TTS_ADAPTER_CODES.has(code) ? code : 'tts_adapter_result_invalid';
    this.stage = /^[a-z_]{1,40}$/.test(stage) ? stage : 'adapter';
    this.externalEffectPossible = externalEffectPossible;
    if (Number.isSafeInteger(httpStatus) && httpStatus! >= 100 && httpStatus! <= 599) this.httpStatus = httpStatus;
  }
}

export async function prepareAudio(
  taskRoot: string,
  options: AudioPreparationOptions = {},
): Promise<JsonObject> {
  const command = options.adapterCommand ?? process.env.PADNOTE_TTS_COMMAND;
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
    if (options.adapterInput && previous?.input_sha256 === inputDigest && previous.status === 'started') {
      const uncertain = new Error('TTS outcome is uncertain; refusing an automatic paid retry') as Error & {externalEffectPossible?: boolean};
      uncertain.externalEffectPossible = true;
      throw uncertain;
    }
    await rm(output, {force: true});
    await atomicWriteJson(checkpointPath, {
      schema_version: '1.0', status: 'started', scene_id: scene.id,
      input_sha256: inputDigest, provider_idempotency_key: inputDigest,
      started_at: new Date().toISOString(),
    });
    const childEnv = buildTtsChildEnvironment({
        sceneId: String(scene.id),
        text: String(scene.narration),
        language: String(ir.episode.language),
        voiceProfile: String(request.voice.profile),
        speed: String(request.voice.speed),
        output,
        idempotencyKey: inputDigest,
      }, options.adapterEnvironment ?? process.env);
    if (options.adapterInput) childEnv.PADNOTE_TTS_INPUT_MODE = 'stdin';
    const childOptions = {
      env: childEnv,
      maxBuffer: 1024 * 1024,
    };
    if (options.adapterInput) {
      await execAdapterWithInput(command, options.adapterArgs ?? [], childOptions.env,
        JSON.stringify(options.adapterInput));
    } else {
      await execFileAsync(command, options.adapterArgs ?? [], childOptions);
    }
    let clip: JsonObject;
    try {
      clip = await describeClip(taskRoot, scene, output, fileName, inputDigest);
    } catch {
      if (options.adapterInput) throw new TtsAdapterError('tts_audio_format_invalid', 'audio_format', true);
      throw new Error('audio clip validation failed');
    }
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

function execAdapterWithInput(command: string, args: string[], env: NodeJS.ProcessEnv,
                              input: string): Promise<void> {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(command, args, {env, stdio: ['pipe', 'pipe', 'ignore'], windowsHide: true});
    const chunks: Buffer[] = [];
    let size = 0;
    let outputOverflow = false;
    child.stdout.on('data', (chunk: Buffer) => {
      size += chunk.length;
      if (size > 16 * 1024) {
        outputOverflow = true;
        child.kill();
        return;
      }
      chunks.push(Buffer.from(chunk));
    });
    child.stdin.on('error', () => {});
    child.once('error', () => rejectPromise(new TtsAdapterError('tts_adapter_start_failed', 'adapter', false)));
    child.once('close', code => {
      if (outputOverflow) {
        rejectPromise(new TtsAdapterError('tts_adapter_result_invalid', 'adapter', true));
        return;
      }
      let result: any;
      try { result = JSON.parse(Buffer.concat(chunks, size).toString('utf8')); }
      catch {
        rejectPromise(new TtsAdapterError('tts_adapter_result_invalid', 'adapter', true));
        return;
      }
      if (code === 0) {
        if (exactKeys(result, ['ok', 'bytes']) && result.ok === true
            && typeof result.bytes === 'number' && Number.isSafeInteger(result.bytes)
            && result.bytes >= 44 && result.bytes <= 20 * 1024 * 1024) {
          resolvePromise();
        } else {
          rejectPromise(new TtsAdapterError('tts_adapter_result_invalid', 'adapter', true));
        }
        return;
      }
      const error = result?.error;
      if (!exactKeys(result, ['ok', 'error']) || result.ok !== false || !error
          || !exactKeys(error, error?.http_status === undefined
            ? ['stage', 'code', 'external_effect_possible']
            : ['stage', 'code', 'external_effect_possible', 'http_status'])
          || typeof error.code !== 'string' || !TTS_ADAPTER_CODES.has(error.code)
          || typeof error.stage !== 'string'
          || typeof error.external_effect_possible !== 'boolean'
          || error.http_status !== undefined && (!Number.isSafeInteger(error.http_status)
            || typeof error.http_status !== 'number' || error.http_status < 100 || error.http_status > 599)) {
        rejectPromise(new TtsAdapterError('tts_adapter_result_invalid', 'adapter', true));
        return;
      }
      rejectPromise(new TtsAdapterError(error.code as string, error.stage as string,
        error.external_effect_possible as boolean, error.http_status as number | undefined));
    });
    child.stdin.end(input);
  });
}

function exactKeys(value: unknown, keys: string[]): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).sort().join(',') === [...keys].sort().join(',');
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
