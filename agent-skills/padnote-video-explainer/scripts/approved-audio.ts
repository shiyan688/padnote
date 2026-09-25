import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {sha256Bytes, sha256File} from './lib.js';
import {loadTaskState} from './task-state.js';
import type {AudioInputBinding} from './validate-audio.js';
import {validateIrObject} from './validate-ir.js';
import {validateRequestObject} from './validate-request.js';
import type {JsonObject} from './lib.js';

/** Returns the immutable, exact-revision input authorized for Stage 2 audio. */
export async function loadApprovedAudioInput(taskRoot: string): Promise<{
  lessonIrPath: string;
  binding: AudioInputBinding;
  ir: JsonObject;
  request: JsonObject;
}> {
  const state = await loadTaskState(taskRoot);
  const approval = state?.approval;
  if (!state || !approval || !approval.lesson_ir_snapshot_path) {
    throw new Error('persisted storyboard approval is required before audio indexing or synthesis');
  }
  const expectedPath = `work/approved-input/${approval.approval_id}/lesson.ir.json`;
  if (approval.lesson_ir_snapshot_path !== expectedPath) {
    throw new Error('approved Lesson IR snapshot path is invalid');
  }
  const lessonIrPath = resolve(taskRoot, expectedPath);
  const [irBytes, requestBytes] = await Promise.all([
    readFile(lessonIrPath),
    readFile(resolve(taskRoot, 'request.json')),
  ]);
  if (sha256Bytes(irBytes) !== approval.lesson_ir_sha256
      || sha256Bytes(requestBytes) !== state.request_sha256
      || await sha256File(resolve(taskRoot, 'output/lesson.ir.json')) !== approval.lesson_ir_sha256) {
    throw new Error('approved Lesson IR or its immutable snapshot changed');
  }
  const request = JSON.parse(requestBytes.toString('utf8')) as JsonObject;
  const ir = JSON.parse(irBytes.toString('utf8')) as JsonObject;
  await validateRequestObject(taskRoot, request);
  await validateIrObject(taskRoot, ir, request);
  return {
    lessonIrPath,
    binding: {
      task_id: state.task_id,
      revision: approval.revision,
      request_sha256: state.request_sha256,
      lesson_ir_sha256: approval.lesson_ir_sha256,
      voice_sha256: sha256Bytes(JSON.stringify(request.voice)),
    },
    ir,
    request,
  };
}
