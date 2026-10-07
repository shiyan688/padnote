import {buildOperationStoryboard, cancelTask, runApprovedTask, type PrivateTtsProvider} from './task-worker.js';
import {cancelStoryboardOperation} from './task-worker.js';
import {inspectTask} from './inspect-task.js';
import {generateQwenStoryboard} from './qwen-storyboard.js';
import {assertOperationInput, operationParameters, payloadDigest, readAuditTail, type VideoOperationBinding} from './video-operation.js';
import {reconcileVideoOperation} from './task-worker.js';
import {isAbsolute} from 'node:path';
import {diagnoseRuntime} from './diagnose-runtime.js';
import {validateResult} from './validate-result.js';
import {makeBuiltinProduceSuccess} from './builtin-envelope.js';
import {closeWindowsReader} from './windows-reader.js';

const MAX_INPUT_BYTES = 64 * 1024;
const MAX_OUTPUT_BYTES = 1024 * 1024;
const protocolStdoutWrite = process.stdout.write.bind(process.stdout);
// Local renderer libraries report progress on stdout. Reserve stdout for the
// single machine-readable response and discard incidental output without filling stderr.
process.stdout.write = ((chunk: any, encoding?: any, callback?: any) => {
  if (typeof encoding === 'function') callback = encoding;
  if (typeof callback === 'function') callback();
  return true;
}) as typeof process.stdout.write;

interface StoryboardInput {
  schema_version: 1;
  action: 'storyboard' | 'produce' | 'cancel';
  task_root: string;
  task_id: string;
  request_sha256: string;
  revision: number;
  event_cursor: number;
  operation: {action?: 'storyboard' | 'produce'; operation_id: string; attempt_id: string; payload_digest: string; source_snapshot_digest: string;
    review_sha256?: string; lesson_ir_sha256?: string; allow_cloud_tts?: true};
  provider?: {base_url: string; model: string; response_mode: 'json_schema' | 'json_object'; region?: string;
    tts_model?: string; tts_voice?: string; tts_language?: string; tts_instructions?: string;
    tts_optimize_instructions?: boolean};
  credential?: {api_key: string};
}

async function main(): Promise<void> {
  let apiKey = '';
  try {
    const input = parseInput(await readStdin());
    if (input.action === 'cancel') {
      const action = input.operation.action;
      if (!action) throw new Error('cancellation operation action is required');
      const binding: VideoOperationBinding = {
        operation_id: input.operation.operation_id, attempt_id: input.operation.attempt_id,
        action, payload_digest: input.operation.payload_digest,
        source_snapshot_digest: input.operation.source_snapshot_digest,
        request_sha256: input.request_sha256, input_event_cursor: input.event_cursor,
        revision: input.revision,
        ...(input.operation.review_sha256 ? {review_sha256: input.operation.review_sha256} : {}),
        ...(input.operation.lesson_ir_sha256 ? {lesson_ir_sha256: input.operation.lesson_ir_sha256} : {}),
        ...(input.operation.allow_cloud_tts === true ? {allow_cloud_tts: true} : {}),
      };
      if (action === 'storyboard') {
        const result = await cancelStoryboardOperation(input.task_root, input.task_id,
          input.request_sha256, input.event_cursor, input.revision, binding);
      emit({ok: result.status === 'verified_cancelled' || result.reason === 'cancel_pending',
          status: result.status === 'verified_cancelled' ? 'verified_cancelled' : 'unconfirmed',
          operation_id: result.operation_id, attempt_id: result.attempt_id, reason: result.reason});
        return;
      }
      await assertProduceCancellationBinding(input.task_root, input.task_id, binding);
      const prior = await inspectTask(input.task_root);
      if (prior.status === 'completed' || prior.status === 'cancelled') {
        const reconciled = await reconcileVideoOperation(input.task_root, input.task_id, binding);
        const verifiedCancelled = reconciled.outcome === 'verified_cancelled';
        emit({ok: verifiedCancelled, status: verifiedCancelled ? 'verified_cancelled' : 'unconfirmed',
          operation_id: binding.operation_id, attempt_id: binding.attempt_id,
          reason: verifiedCancelled ? 'cancel_receipt_match' : prior.status === 'completed' ? 'already_completed' : 'cancel_receipt_unconfirmed'});
        return;
      }
      const next = await cancelTask(input.task_root, action === 'produce' ? {operation: binding} : {});
      emit({ok: next.status === 'cancelled' || next.status === 'cancelling',
        status: next.status === 'cancelled' ? 'verified_cancelled' : 'unconfirmed',
        operation_id: binding.operation_id, attempt_id: binding.attempt_id,
        reason: next.status === 'cancelling' ? 'cancel_pending' : next.status});
      return;
    }
    if (!input.provider || !input.credential) throw new Error('provider settings are required');
    const provider = input.provider;
    apiKey = input.credential.api_key;
    await assertRuntimeReady();
    if (input.action === 'produce') {
      const binding: VideoOperationBinding = {
        ...input.operation, action: 'produce', request_sha256: input.request_sha256,
        input_event_cursor: input.event_cursor, revision: input.revision,
      };
      if (!binding.review_sha256 || !binding.lesson_ir_sha256 || binding.allow_cloud_tts !== true
          || !input.provider.tts_model || !input.provider.tts_voice) {
        throw new Error('production requires exact storyboard hashes, cloud TTS consent, and TTS settings');
      }
      if (binding.payload_digest !== payloadDigest('produce', {
        revision: input.revision, event_cursor: input.event_cursor,
        review_sha256: binding.review_sha256, lesson_ir_sha256: binding.lesson_ir_sha256,
        allow_cloud_tts: true,
      })) throw new Error('operation payload binding mismatch');
      const tts: PrivateTtsProvider = {
        api_key: apiKey, base_url: `${new URL(provider.base_url).origin}/api/v1`,
        model: provider.tts_model!, voice: provider.tts_voice!,
        ...(provider.tts_language ? {language: provider.tts_language} : {}),
        ...(provider.tts_instructions ? {instructions: provider.tts_instructions} : {}),
        ...(provider.tts_optimize_instructions !== undefined
          ? {optimize_instructions: provider.tts_optimize_instructions} : {}),
      };
      await runApprovedTask(input.task_root, 'all', false, undefined, binding, tts);
      const inspection = await inspectTask(input.task_root);
      if (inspection.task_id !== input.task_id) {
        throw new Error('completed task inspection does not match the approved operation');
      }
      const validatedResult = await validateResult(input.task_root);
      emit(makeBuiltinProduceSuccess(inspection, binding, validatedResult.artifacts));
      return;
    }
    const binding: VideoOperationBinding = {
      ...input.operation,
      action: 'storyboard',
      request_sha256: input.request_sha256,
      input_event_cursor: input.event_cursor,
      revision: input.revision,
    };
    const expectedPayload = payloadDigest('storyboard', {
      revision: input.revision, event_cursor: input.event_cursor,
    });
    if (binding.payload_digest !== expectedPayload) throw new Error('operation payload binding mismatch');
    const result = await buildOperationStoryboard(
      input.task_root, input.task_id, input.request_sha256, input.revision, input.event_cursor,
      binding, async () => (await generateQwenStoryboard(input.task_root, {
        apiKey, baseUrl: provider.base_url, model: provider.model,
        responseMode: provider.response_mode, region: provider.region,
      })).ir,
    );
    const receipt = {
      operation_id: binding.operation_id, attempt_id: binding.attempt_id, action: binding.action,
      payload_digest: binding.payload_digest, source_snapshot_digest: binding.source_snapshot_digest,
      request_sha256: binding.request_sha256, input_event_cursor: binding.input_event_cursor,
      result_event_cursor: result.event_cursor, revision: result.revision,
      review_sha256: result.review_sha256, lesson_ir_sha256: result.lesson_ir_sha256,
    };
    emit({ok: true, task_id: result.task_id, status: result.status, phase: result.phase,
      event_cursor: result.event_cursor, revision: result.revision,
      review_sha256: result.review_sha256, lesson_ir_sha256: result.lesson_ir_sha256, receipt});
  } catch (error) {
    const failure = error as Error & {externalEffectPossible?: boolean; safeCode?: string; httpStatus?: number};
    const uncertain = Boolean(failure?.externalEffectPossible);
    const safeCode = typeof failure?.safeCode === 'string' && /^tts_[a-z_]{1,60}$/.test(failure.safeCode)
      ? failure.safeCode : undefined;
    emit({ok: false, code: safeCode ?? (uncertain ? 'external_effect_possible' : 'operation_failed'),
      external_effect_possible: uncertain,
      ...(Number.isSafeInteger(failure?.httpStatus) && failure.httpStatus! >= 100 && failure.httpStatus! <= 599
        ? {http_status: failure.httpStatus} : {}),
      message: uncertain ? 'Provider outcome is uncertain; inspect before any retry' : 'Built-in video operation failed'});
    process.exitCode = 1;
  } finally {
    apiKey = '';
  }
}

async function assertRuntimeReady(): Promise<void> {
  const diagnostic = await diagnoseRuntime();
  const required = ['worker_modules', 'storyboard_browser', 'render_browser', 'ffmpeg', 'ffprobe'] as const;
  if (required.some(name => diagnostic.checks[name].status !== 'available')) {
    throw new Error('required local video runtime dependency is unavailable');
  }
}

function parseInput(bytes: Buffer): StoryboardInput {
  let input: any;
  try { input = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(bytes)); }
  catch { throw new Error('input must be one valid UTF-8 JSON object'); }
  if (!input || input.schema_version !== 1 || !['storyboard', 'produce', 'cancel'].includes(input.action)
      || typeof input.task_root !== 'string' || !isAbsolute(input.task_root)
      || typeof input.task_id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(input.task_id)
      || !/^[a-f0-9]{64}$/.test(input.request_sha256 ?? '')
      || !Number.isSafeInteger(input.revision) || input.revision < 1
      || !Number.isSafeInteger(input.event_cursor) || input.event_cursor < 1
      || !input.operation
      || typeof input.operation.operation_id !== 'string' || typeof input.operation.attempt_id !== 'string'
      || !/^[a-f0-9]{64}$/.test(input.operation.payload_digest ?? '')
      || !/^[a-f0-9]{64}$/.test(input.operation.source_snapshot_digest ?? '')
      || (input.action !== 'cancel' && (!input.provider || !input.credential
        || typeof input.provider.base_url !== 'string' || typeof input.provider.model !== 'string'
        || input.provider.model.length > 128
        || !['json_schema', 'json_object'].includes(input.provider.response_mode)
        || typeof input.credential.api_key !== 'string' || !input.credential.api_key
        || input.credential.api_key.length > 4096))) {
    throw new Error('invalid built-in engine input');
  }
  const allowed = ['schema_version', 'action', 'task_root', 'task_id', 'request_sha256', 'revision',
    'event_cursor', 'operation', 'provider', 'credential'];
  if (Object.keys(input).some(key => !allowed.includes(key))
      || Object.keys(input.operation).some(key => !['action', 'operation_id', 'attempt_id', 'payload_digest', 'source_snapshot_digest',
        'review_sha256', 'lesson_ir_sha256', 'allow_cloud_tts'].includes(key))
      || input.provider && Object.keys(input.provider).some(key => !['base_url', 'model', 'response_mode', 'region', 'tts_model', 'tts_voice',
        'tts_language', 'tts_instructions', 'tts_optimize_instructions'].includes(key))
      || input.credential && Object.keys(input.credential).some(key => key !== 'api_key')) {
    throw new Error('invalid built-in engine input');
  }
  if (input.action === 'cancel') {
    if (!['storyboard', 'produce'].includes(input.operation.action)
        || input.provider !== undefined || input.credential !== undefined
        || input.operation.action === 'produce'
          && (!/^[a-f0-9]{64}$/.test(input.operation.review_sha256 ?? '')
            || !/^[a-f0-9]{64}$/.test(input.operation.lesson_ir_sha256 ?? '')
            || input.operation.allow_cloud_tts !== true)) {
      throw new Error('invalid cancellation binding');
    }
    return input as StoryboardInput;
  }
  if (input.operation.action !== input.action) throw new Error('operation action does not match engine action');
  const endpoint = new URL(input.provider!.base_url);
  const officialHost = endpoint.hostname === 'dashscope.aliyuncs.com'
    || endpoint.hostname === 'dashscope-intl.aliyuncs.com'
    || endpoint.hostname === 'cn-hongkong.dashscope.aliyuncs.com'
    || /^[a-z0-9-]+\.(?:cn-beijing|ap-southeast-1|us-east-1|cn-hongkong|eu-central-1|ap-northeast-1)\.maas\.aliyuncs\.com$/i.test(endpoint.hostname);
  if (endpoint.protocol !== 'https:' || endpoint.username || endpoint.password || endpoint.search || endpoint.hash
      || (endpoint.port && endpoint.port !== '443')
      || endpoint.pathname.replace(/\/$/, '') !== '/compatible-mode/v1' || !officialHost) {
    throw new Error('provider endpoint must be an official Alibaba Model Studio compatible endpoint');
  }
  if (input.provider!.region !== undefined && (typeof input.provider!.region !== 'string' || input.provider!.region.length > 40)) {
    throw new Error('invalid provider region');
  }
  if (input.action === 'produce' && (input.provider!.region !== 'china'
      || input.operation.allow_cloud_tts !== true
      || !/^[a-f0-9]{64}$/.test(input.operation.review_sha256 ?? '')
      || !/^[a-f0-9]{64}$/.test(input.operation.lesson_ir_sha256 ?? '')
      || !['qwen3-tts-flash', 'qwen3-tts-instruct-flash'].includes(input.provider!.tts_model)
      || typeof input.provider!.tts_voice !== 'string' || !/^[A-Za-z0-9-]{1,64}$/.test(input.provider!.tts_voice)
      || input.provider!.tts_language !== undefined
        && !['Auto', 'Chinese', 'English', 'German', 'Italian', 'Portuguese', 'Spanish', 'Japanese', 'Korean', 'French', 'Russian'].includes(input.provider!.tts_language)
      || input.provider!.tts_instructions !== undefined
        && (typeof input.provider!.tts_instructions !== 'string' || input.provider!.tts_instructions.length > 4000)
      || input.provider!.tts_optimize_instructions !== undefined
        && typeof input.provider!.tts_optimize_instructions !== 'boolean')) {
    throw new Error('invalid production binding');
  }
  return input as StoryboardInput;
}

async function assertProduceCancellationBinding(taskRoot: string, taskId: string,
                                                binding: VideoOperationBinding): Promise<void> {
  await assertOperationInput(taskRoot, binding, 'produce', operationParameters(binding));
  const state = await inspectTask(taskRoot);
  if (state.task_id !== taskId) throw new Error('cancellation task binding mismatch');
  const events = await readAuditTail(taskRoot);
  if (!events.some(event => event.event === 'approval_consumed' && matchingProduceStart(event, binding, taskId))) {
    throw new Error('production cancellation operation is not current');
  }
}

function matchingProduceStart(event: Record<string, any>, binding: VideoOperationBinding, taskId: string): boolean {
  const start = event.video_operation_started;
  return event.task_id === taskId && start?.operation_id === binding.operation_id
    && start?.attempt_id === binding.attempt_id && start?.action === 'produce'
    && start?.payload_digest === binding.payload_digest
    && start?.source_snapshot_digest === binding.source_snapshot_digest
    && start?.request_sha256 === binding.request_sha256
    && start?.input_event_cursor === binding.input_event_cursor
    && start?.revision === binding.revision
    && start?.review_sha256 === binding.review_sha256
    && start?.lesson_ir_sha256 === binding.lesson_ir_sha256
    && start?.allow_cloud_tts === true;
}

async function readStdin(): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const value of process.stdin) {
    const chunk = Buffer.from(value);
    size += chunk.length;
    if (size > MAX_INPUT_BYTES) throw new Error('input exceeds the 64 KiB limit');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, size);
}

function emit(value: unknown): void {
  const line = JSON.stringify(value);
  if (Buffer.byteLength(line, 'utf8') > MAX_OUTPUT_BYTES) {
    protocolStdoutWrite('{"ok":false,"code":"output_too_large","external_effect_possible":true,"message":"Built-in video result exceeded its output limit"}\n');
    return;
  }
  protocolStdoutWrite(`${line}\n`);
}

void main().finally(async () => {
  try {
    await closeWindowsReader();
  } catch {
    console.error('Windows file reader cleanup failed');
    process.exitCode = 1;
  }
});
