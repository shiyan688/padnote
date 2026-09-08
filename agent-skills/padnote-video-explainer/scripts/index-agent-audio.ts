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
import {validateIr} from './validate-ir.js';

export async function indexAgentAudio(taskRoot: string): Promise<JsonObject> {
  const ir = await validateIr(taskRoot);
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
    });
  }
  const manifest = {schema_version: '1.0', clips};
  await atomicWriteJson(resolve(taskRoot, 'work/audio-manifest.json'), manifest);
  return validateAudio(taskRoot);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  indexAgentAudio(requireCliTaskRoot()).then(manifest => {
    console.log(`agent audio indexed: ${manifest.clips.length} clips`);
  }).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
