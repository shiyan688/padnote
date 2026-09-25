import {mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {
  assertExistingFileInside,
  atomicWriteJson,
  fileDescriptor,
  requireCliTaskRoot,
  type JsonObject,
} from './lib.js';
import {parseWav, validateAudio} from './validate-audio.js';
import {audioSceneInputDigest} from './validate-audio.js';
import {loadApprovedAudioInput} from './approved-audio.js';

export async function indexAgentAudio(taskRoot: string): Promise<JsonObject> {
  const approved = await loadApprovedAudioInput(taskRoot);
  const ir = approved.ir;
  await mkdir(resolve(taskRoot, 'work/audio'), {recursive: true});
  const clips: JsonObject[] = [];
  for (const scene of ir.scenes as JsonObject[]) {
    const relativePath = `work/audio/${scene.id}.wav`;
    const path = await assertExistingFileInside(taskRoot, relativePath);
    const wav = await parseWav(path);
    clips.push({
      scene_id: scene.id,
      ...await fileDescriptor(path, relativePath, 'audio/wav'),
      duration_ms: wav.durationMs,
      input_sha256: audioSceneInputDigest(approved.binding, scene),
      provider_idempotency_key: audioSceneInputDigest(approved.binding, scene),
    });
  }
  const manifest = {schema_version: '1.0', input_binding: approved.binding, clips};
  await atomicWriteJson(resolve(taskRoot, 'work/audio-manifest.json'), manifest);
  return validateAudio(taskRoot, undefined, approved.binding, approved.lessonIrPath);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  indexAgentAudio(requireCliTaskRoot()).then(manifest => {
    console.log(`agent audio indexed: ${manifest.clips.length} clips`);
  }).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
