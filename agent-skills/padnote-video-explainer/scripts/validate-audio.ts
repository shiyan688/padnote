import {readFile} from 'node:fs/promises';
import {relative, resolve, sep} from 'node:path';
import {
  assertExistingFileInside,
  readJson,
  requireCliTaskRoot,
  sha256Bytes,
  unique,
  validateSchema,
  verifyFileRecord,
  type JsonObject,
} from './lib.js';
import {validateIr} from './validate-ir.js';

export interface WavInfo {
  durationMs: number;
  dataOffset: number;
  dataLength: number;
  audioFormat: number;
  channels: number;
  sampleRate: number;
  byteRate: number;
  bitsPerSample: number;
}

export interface AudioInputBinding extends JsonObject {
  task_id: string;
  revision: number;
  request_sha256: string;
  lesson_ir_sha256: string;
  voice_sha256: string;
}

export async function parseWav(path: string): Promise<WavInfo> {
  const data = await readFile(path);
  if (data.length < 44 || data.toString('ascii', 0, 4) !== 'RIFF' || data.toString('ascii', 8, 12) !== 'WAVE') {
    throw new Error(`invalid WAV container: ${path}`);
  }
  let offset = 12;
  let format: Omit<WavInfo, 'durationMs' | 'dataOffset' | 'dataLength'> | undefined;
  let dataOffset = -1;
  let dataLength = -1;
  while (offset + 8 <= data.length) {
    const id = data.toString('ascii', offset, offset + 4);
    const length = data.readUInt32LE(offset + 4);
    const body = offset + 8;
    if (body + length > data.length) throw new Error(`truncated WAV chunk: ${path}`);
    if (id === 'fmt ') {
      if (length < 16) throw new Error(`invalid WAV fmt chunk: ${path}`);
      format = {
        audioFormat: data.readUInt16LE(body),
        channels: data.readUInt16LE(body + 2),
        sampleRate: data.readUInt32LE(body + 4),
        byteRate: data.readUInt32LE(body + 8),
        bitsPerSample: data.readUInt16LE(body + 14),
      };
    } else if (id === 'data') {
      dataOffset = body;
      dataLength = length;
    }
    offset = body + length + (length % 2);
  }
  if (!format || dataOffset < 0 || dataLength <= 0) throw new Error(`WAV is missing fmt or data: ${path}`);
  if (format.audioFormat !== 1 || format.bitsPerSample !== 16) throw new Error(`v1 requires 16-bit PCM WAV: ${path}`);
  if (format.byteRate !== format.sampleRate * format.channels * 2) throw new Error(`invalid WAV byte rate: ${path}`);
  let peak = 0;
  for (let index = dataOffset; index + 1 < dataOffset + dataLength; index += 2) {
    peak = Math.max(peak, Math.abs(data.readInt16LE(index)));
  }
  if (peak < 32) throw new Error(`silent WAV is forbidden: ${path}`);
  return {
    ...format,
    dataOffset,
    dataLength,
    durationMs: Math.round(dataLength * 1000 / format.byteRate),
  };
}

export async function validateAudio(
  taskRoot: string,
  manifestPath?: string,
  expectedBinding?: AudioInputBinding,
  irPath?: string,
): Promise<JsonObject> {
  const ir = await validateIr(taskRoot, irPath);
  const absolute = resolve(manifestPath ?? resolve(taskRoot, 'work/audio-manifest.json'));
  const relativePath = relative(taskRoot, absolute).split(sep).join('/');
  const file = await assertExistingFileInside(taskRoot, relativePath);
  const manifest = await readJson(file);
  await validateSchema('audio-manifest', manifest);
  if (expectedBinding && JSON.stringify(manifest.input_binding) !== JSON.stringify(expectedBinding)) {
    throw new Error('audio manifest input binding does not match the approved revision and voice');
  }
  const clips = manifest.clips as JsonObject[];
  unique(clips.map(clip => clip.scene_id), 'audio scene_id');
  unique(clips.map(clip => clip.path), 'audio path');
  const sceneIds = (ir.scenes as JsonObject[]).map(scene => scene.id);
  if (JSON.stringify(clips.map(clip => clip.scene_id)) !== JSON.stringify(sceneIds)) {
    throw new Error('audio clips must match Lesson IR scenes in order');
  }
  for (const clip of clips) {
    if (expectedBinding) {
      const scene = (ir.scenes as JsonObject[]).find(value => value.id === clip.scene_id)!;
      const expectedInput = audioSceneInputDigest(expectedBinding, scene);
      if (clip.input_sha256 !== expectedInput || clip.provider_idempotency_key !== expectedInput) {
        throw new Error(`audio clip input binding mismatch for ${clip.scene_id}`);
      }
    }
    const path = await verifyFileRecord(taskRoot, 'task', clip);
    const wav = await parseWav(path);
    if (wav.durationMs !== clip.duration_ms) {
      throw new Error(`actual WAV duration mismatch for ${clip.scene_id}: ${wav.durationMs} != ${clip.duration_ms}`);
    }
  }
  return manifest;
}

export function audioSceneInputDigest(binding: AudioInputBinding, scene: JsonObject): string {
  return sha256Bytes(JSON.stringify({binding, scene_id: scene.id, narration: scene.narration}));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  validateAudio(requireCliTaskRoot(), process.argv[3]).then(manifest => {
    console.log(`audio manifest valid: ${manifest.clips.length} clips`);
  }).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
