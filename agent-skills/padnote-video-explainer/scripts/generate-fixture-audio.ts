import {mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {
  atomicWriteFile,
  atomicWriteJson,
  fileDescriptor,
  requireCliTaskRoot,
  type JsonObject,
} from './lib.js';
import {parseWav} from './validate-audio.js';
import {audioSceneInputDigest} from './validate-audio.js';
import {loadApprovedAudioInput} from './approved-audio.js';

const sampleRate = 24_000;

export async function generateFixtureAudio(taskRoot: string): Promise<JsonObject> {
  if (process.argv[3] !== '--fixture-audio') {
    throw new Error('fixture audio requires the explicit --fixture-audio flag');
  }
  const approved = await loadApprovedAudioInput(taskRoot);
  const ir = approved.ir;
  const audioDir = resolve(taskRoot, 'work/audio');
  await mkdir(audioDir, {recursive: true});
  const clips: JsonObject[] = [];
  for (let index = 0; index < ir.scenes.length; index += 1) {
    const scene = ir.scenes[index] as JsonObject;
    const durationMs = Math.round(scene.estimated_duration_sec * 1000);
    const fileName = `${scene.id}.wav`;
    const path = resolve(audioDir, fileName);
    await atomicWriteFile(path, synthFixtureWav(durationMs, 196 + index * 37));
    const wav = await parseWav(path);
    clips.push({
      scene_id: scene.id,
      ...await fileDescriptor(path, `work/audio/${fileName}`, 'audio/wav'),
      duration_ms: wav.durationMs,
      input_sha256: audioSceneInputDigest(approved.binding, scene),
      provider_idempotency_key: audioSceneInputDigest(approved.binding, scene),
      fixture: true,
    });
  }
  const manifest = {schema_version: '1.0', input_binding: approved.binding, clips};
  await atomicWriteJson(resolve(taskRoot, 'work/audio-manifest.json'), manifest);
  return manifest;
}

function synthFixtureWav(durationMs: number, frequency: number): Buffer {
  const sampleCount = Math.round(durationMs * sampleRate / 1000);
  const dataLength = sampleCount * 2;
  const buffer = Buffer.alloc(44 + dataLength);
  buffer.write('RIFF', 0);
  buffer.writeUInt32LE(36 + dataLength, 4);
  buffer.write('WAVE', 8);
  buffer.write('fmt ', 12);
  buffer.writeUInt32LE(16, 16);
  buffer.writeUInt16LE(1, 20);
  buffer.writeUInt16LE(1, 22);
  buffer.writeUInt32LE(sampleRate, 24);
  buffer.writeUInt32LE(sampleRate * 2, 28);
  buffer.writeUInt16LE(2, 32);
  buffer.writeUInt16LE(16, 34);
  buffer.write('data', 36);
  buffer.writeUInt32LE(dataLength, 40);
  for (let sample = 0; sample < sampleCount; sample += 1) {
    const seconds = sample / sampleRate;
    const pulse = Math.floor(seconds * 2) % 2 === 0 ? 1 : 0.55;
    const envelope = Math.min(1, seconds * 8, (durationMs / 1000 - seconds) * 8);
    const value = Math.round(Math.sin(seconds * frequency * Math.PI * 2) * 2600 * pulse * Math.max(0, envelope));
    buffer.writeInt16LE(value, 44 + sample * 2);
  }
  return buffer;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  generateFixtureAudio(requireCliTaskRoot()).then(manifest => {
    console.log(`FIXTURE AUDIO ONLY: ${manifest.clips.length} clips; this is not TTS`);
  }).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
