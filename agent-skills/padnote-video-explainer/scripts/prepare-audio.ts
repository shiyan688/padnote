import {execFile} from 'node:child_process';
import {isAbsolute, resolve} from 'node:path';
import {mkdir} from 'node:fs/promises';
import {promisify} from 'node:util';
import {atomicWriteJson, fileDescriptor, requireCliTaskRoot, type JsonObject} from './lib.js';
import {parseWav, validateAudio} from './validate-audio.js';
import {validateIr} from './validate-ir.js';
import {validateRequest} from './validate-request.js';

const execFileAsync = promisify(execFile);

export async function prepareAudio(taskRoot: string): Promise<JsonObject> {
  const command = process.env.PADNOTE_TTS_COMMAND;
  if (!command) throw new TtsNotConfiguredError();
  if (!isAbsolute(command)) throw new Error('PADNOTE_TTS_COMMAND must be an absolute executable path');
  const request = await validateRequest(taskRoot);
  const ir = await validateIr(taskRoot);
  const audioDir = resolve(taskRoot, 'work/audio');
  await mkdir(audioDir, {recursive: true});
  const clips: JsonObject[] = [];
  for (const scene of ir.scenes as JsonObject[]) {
    const fileName = `${scene.id}.wav`;
    const output = resolve(audioDir, fileName);
    await execFileAsync(command, [], {
      env: {
        ...process.env,
        PADNOTE_TTS_SCENE_ID: scene.id,
        PADNOTE_TTS_TEXT: scene.narration,
        PADNOTE_TTS_LANGUAGE: ir.episode.language,
        PADNOTE_TTS_VOICE_PROFILE: request.voice.profile,
        PADNOTE_TTS_SPEED: String(request.voice.speed),
        PADNOTE_TTS_OUTPUT: output,
      },
      maxBuffer: 1024 * 1024,
    });
    const wav = await parseWav(output);
    clips.push({
      scene_id: scene.id,
      ...await fileDescriptor(output, `work/audio/${fileName}`, 'audio/wav'),
      duration_ms: wav.durationMs,
    });
  }
  const manifest = {schema_version: '1.0', clips};
  await atomicWriteJson(resolve(taskRoot, 'work/audio-manifest.json'), manifest);
  return validateAudio(taskRoot);
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
