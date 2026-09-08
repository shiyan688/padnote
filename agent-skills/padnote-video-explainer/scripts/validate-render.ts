import {readFile} from 'node:fs/promises';
import {relative, resolve, sep} from 'node:path';
import {
  assertExistingFileInside,
  readJson,
  requireCliTaskRoot,
  sha256File,
  validateSchema,
  type JsonObject,
} from './lib.js';
import {validateAudio} from './validate-audio.js';
import {validateIr} from './validate-ir.js';
import {validateRequest} from './validate-request.js';

export async function validateRenderManifest(taskRoot: string, manifestPath?: string): Promise<JsonObject> {
  const request = await validateRequest(taskRoot);
  const ir = await validateIr(taskRoot);
  const audio = await validateAudio(taskRoot);
  const absolute = resolve(manifestPath ?? resolve(taskRoot, 'output/render.manifest.json'));
  const relativePath = relative(taskRoot, absolute).split(sep).join('/');
  const file = await assertExistingFileInside(taskRoot, relativePath);
  const manifest = await readJson(file);
  await validateSchema('render-manifest', manifest);
  if (manifest.task_id !== request.task_id) throw new Error('render manifest task_id mismatch');
  if (manifest.lesson_ir_sha256 !== await sha256File(resolve(taskRoot, 'output/lesson.ir.json'))) throw new Error('render manifest Lesson IR hash mismatch');
  if (manifest.audio_manifest_sha256 !== await sha256File(resolve(taskRoot, 'work/audio-manifest.json'))) throw new Error('render manifest audio hash mismatch');

  let elapsedMs = 0;
  const scenes = manifest.scenes as JsonObject[];
  if (scenes.length !== ir.scenes.length || scenes.length !== audio.clips.length) throw new Error('render scene count mismatch');
  for (let index = 0; index < scenes.length; index += 1) {
    const scene = scenes[index]!;
    const irScene = (ir.scenes as JsonObject[])[index]!;
    const clip = (audio.clips as JsonObject[])[index]!;
    if (scene.scene_id !== irScene.id || scene.scene_id !== clip.scene_id) throw new Error(`render scene order mismatch at ${index}`);
    if (scene.audio_path !== clip.path) throw new Error(`render audio path mismatch for ${scene.scene_id}`);
    if (scene.start_ms !== elapsedMs || scene.end_ms !== elapsedMs + clip.duration_ms) throw new Error(`render timing mismatch for ${scene.scene_id}`);
    const expectedStartFrame = Math.ceil(scene.start_ms * manifest.fps / 1000);
    const expectedEndFrame = Math.ceil(scene.end_ms * manifest.fps / 1000);
    if (scene.start_frame !== expectedStartFrame || scene.end_frame !== expectedEndFrame) throw new Error(`render frame boundary mismatch for ${scene.scene_id}`);
    const keyframe = await assertExistingFileInside(taskRoot, scene.keyframe_path);
    const png = await readFile(keyframe);
    if (png.length < 24 || png.toString('hex', 0, 8) !== '89504e470d0a1a0a') throw new Error(`invalid keyframe PNG for ${scene.scene_id}`);
    if (png.readUInt32BE(16) !== 1080 || png.readUInt32BE(20) !== 1920) throw new Error(`keyframe dimensions are not 1080x1920 for ${scene.scene_id}`);
    elapsedMs = scene.end_ms;
  }
  if (manifest.total_duration_ms !== elapsedMs) throw new Error('render total_duration_ms mismatch');
  if (manifest.total_frames !== Math.ceil(elapsedMs * manifest.fps / 1000)) throw new Error('render total_frames mismatch');
  return manifest;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  validateRenderManifest(requireCliTaskRoot(), process.argv[3]).then(manifest => {
    console.log(`render manifest valid: ${manifest.total_frames} frames`);
  }).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
