import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import type {JsonObject} from './lib.js';
import {validateRequest} from './validate-request.js';
import {validateIrObject} from './validate-ir.js';
import {readBoundedRegularFile} from './inspect-task.js';
import {sha256Bytes} from './lib.js';

export interface QwenStoryboardConfig {
  apiKey: string;
  baseUrl: string;
  model: string;
  responseMode: 'json_schema' | 'json_object';
  region?: string;
  fetchImpl?: typeof fetch;
  timeoutMs?: number;
}

const MAX_SOURCE_BYTES = 256 * 1024;
const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
const MAX_SCHEMA_BYTES = 512 * 1024;
const MAX_SCENES = 24;
const MAX_NARRATION_CHARS = 8_000;

/** A single non-retrying Qwen call. Any failure after dispatch is charge-uncertain. */
export async function generateQwenStoryboard(
  taskRoot: string,
  config: QwenStoryboardConfig,
): Promise<{ir: JsonObject; externalEffectPossible: boolean}> {
  validateConfig(config);
  const request = await validateRequest(taskRoot);
  const manifestBytes = await readBoundedRegularFile(resolve(taskRoot, 'input/manifest.json'), 1024 * 1024);
  const manifestText = manifestBytes.toString('utf8');
  const manifest = JSON.parse(manifestText) as {files: Array<{path: string; size_bytes: number; sha256: string}>};
  const entry = manifest.files.find(file => file.path === 'input/content.md');
  if (!entry || entry.size_bytes > MAX_SOURCE_BYTES) throw new Error('Qwen source content is unavailable or too large');
  const content = await readBoundedRegularFile(resolve(taskRoot, 'input/content.md'), MAX_SOURCE_BYTES);
  if (content.length !== entry.size_bytes || sha256Bytes(content) !== entry.sha256) {
    throw new Error('Qwen source content no longer matches its validated manifest');
  }
  const schemaBytes = await readFile(new URL('../schemas/lesson-ir.schema.json', import.meta.url));
  if (schemaBytes.length > MAX_SCHEMA_BYTES) throw new Error('Lesson IR schema exceeds the configured size limit');
  const schema = JSON.parse(schemaBytes.toString('utf8')) as JsonObject;
  const outputSchema = authoringSchema(schema);
  const prompt = [
    'Create the initial PadNote explainer storyboard Lesson IR for the supplied source.',
    'Return one JSON object with exactly two top-level properties: claims and scenes. Do not add any other fields. Do not invent facts beyond the source.',
    'AUTHORING OUTPUT JSON SCHEMA (follow this structure exactly):\n' + JSON.stringify(outputSchema),
    'Each claim requires id, text, source_refs, verification (user-provided|external-source|uncertain), confidence (low|medium|high). Each scene requires id, learning_objective, claim_ids, narration, screen_text, visual, estimated_duration_sec. Visual must use one of the schema-listed visual types and its matching data object.',
    `The application fills trusted source identity, episode settings, and render profile from this request: ${JSON.stringify(request.source)} ${JSON.stringify(request.brief)}.`,
    `Research policy: external_research=${String((request.research as JsonObject).external_research)}.`,
    'Claims must preserve uncertainty when evidence is missing. Keep narration concise, instructional, and in the requested language.',
    'Use supported visual types only. Each scene must teach one step toward the learning goal.',
    'SOURCE CONTENT (UTF-8):\n' + content.toString('utf8'),
    'INPUT MANIFEST (UTF-8):\n' + manifestText,
  ].join('\n\n');
  if (Buffer.byteLength(prompt, 'utf8') > MAX_SOURCE_BYTES + MAX_SCHEMA_BYTES + 64 * 1024) {
    throw new Error('Qwen prompt exceeds the configured size limit');
  }
  const endpoint = new URL(`${config.baseUrl.replace(/\/$/, '')}/chat/completions`);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.timeoutMs ?? 120_000);
  let dispatched = false;
  try {
    dispatched = true;
    const response = await (config.fetchImpl ?? fetch)(endpoint, {
      method: 'POST',
      headers: {Authorization: `Bearer ${config.apiKey}`, 'Content-Type': 'application/json'},
      body: JSON.stringify({
          model: config.model,
          max_tokens: 4096,
        messages: [
          {role: 'system', content: 'You are an instructional designer. Return a schema-conforming Lesson IR.'},
          {role: 'user', content: prompt},
        ],
        response_format: config.responseMode === 'json_schema'
          ? {type: 'json_schema', json_schema: {name: 'padnote_lesson_ir', strict: true, schema: outputSchema}}
          : {type: 'json_object'},
        ...(config.responseMode === 'json_object' ? {enable_thinking: false} : {}),
      }),
      signal: controller.signal,
      redirect: 'error',
    });
    const body = await readResponseBounded(response, MAX_RESPONSE_BYTES);
    if (!response.ok) throw new Error(`Qwen request failed with HTTP ${response.status}`);
    let result: any;
    try { result = JSON.parse(body.toString('utf8')); }
    catch { throw new Error('Qwen returned an invalid response envelope'); }
    const choice = result?.choices?.[0];
    if (choice?.finish_reason === 'length') throw new Error('Qwen response was truncated');
    const text = choice?.message?.content;
    if (typeof text !== 'string' || !text) throw new Error('Qwen response did not contain Lesson IR JSON');
    let authored: JsonObject;
    try { authored = JSON.parse(text) as JsonObject; }
    catch { throw new Error('Qwen response was not valid Lesson IR JSON'); }
    if (!authored || Object.keys(authored).length !== 2
        || !Object.hasOwn(authored, 'claims') || !Object.hasOwn(authored, 'scenes')) {
      throw new Error('Qwen response contains fields outside the authoring schema');
    }
    const authoredScenes = authored.scenes;
    if (!Array.isArray(authoredScenes) || authoredScenes.length > MAX_SCENES
        || authoredScenes.some(scene => typeof scene?.narration !== 'string' || scene.narration.length > 600)
        || authoredScenes.reduce((total, scene) => total + (typeof scene?.narration === 'string' ? scene.narration.length : 0), 0) > MAX_NARRATION_CHARS) {
      throw new Error('Qwen storyboard exceeds the configured scene and narration budget');
    }
    const ir: JsonObject = {
      schema_version: '1.0',
      source: {
        bundle_sha256: request.source.bundle_sha256,
        note_id: request.source.note_id,
        note_revision: request.source.note_revision,
      },
      episode: {
        title: request.source.title,
        audience: request.brief.audience,
        learning_goal: request.brief.learning_goal,
        language: request.source.language,
        target_duration_sec: request.brief.target_duration_sec,
      },
      claims: authored.claims,
      scenes: authored.scenes,
      render_profile: {aspect_ratio: '9:16', width: 1080, height: 1920, fps: 30},
    };
    await validateIrObject(taskRoot, ir, request);
    return {ir, externalEffectPossible: dispatched};
  } catch (error) {
    const failure = new Error(dispatched ? 'Qwen request outcome is uncertain' : 'Qwen storyboard could not be prepared') as Error & {externalEffectPossible?: boolean};
    failure.externalEffectPossible = dispatched;
    throw failure;
  } finally {
    clearTimeout(timer);
  }
}

function validateConfig(config: QwenStoryboardConfig): void {
  if (!config.apiKey || config.apiKey.length > 4096) throw new Error('Qwen API key is missing or invalid');
  if (!config.model || config.model.length > 128 || /[\r\n]/.test(config.model)) throw new Error('Qwen model is invalid');
  let url: URL;
  try { url = new URL(config.baseUrl); } catch { throw new Error('Qwen base URL is invalid'); }
  if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443') || url.search || url.hash
      || url.pathname.replace(/\/$/, '') !== '/compatible-mode/v1' || !isOfficialDashScopeHost(url.hostname)) {
    throw new Error('Qwen base URL must be an official Alibaba Model Studio HTTPS endpoint');
  }
  if (!['json_schema', 'json_object'].includes(config.responseMode)) throw new Error('Qwen structured output mode is required');
  if (config.responseMode === 'json_schema') {
    const supported = /^qwen3\.(?:7-(?:plus|flash|max)|8-(?:flash|max))(?:-|$)/i.test(config.model);
    if (!supported) {
      throw new Error('Qwen JSON Schema mode requires a model listed by Alibaba as supported');
    }
  }
  if (!Number.isInteger(config.timeoutMs ?? 120_000) || (config.timeoutMs ?? 120_000) < 1000
      || (config.timeoutMs ?? 120_000) > 180_000) throw new Error('Qwen timeout is invalid');
}

function authoringSchema(fullSchema: JsonObject): JsonObject {
  const properties = fullSchema.properties as JsonObject;
  return {
    type: 'object', additionalProperties: false, required: ['claims', 'scenes'],
    properties: {claims: properties.claims, scenes: properties.scenes},
    $defs: fullSchema.$defs,
  };
}

function isOfficialDashScopeHost(host: string): boolean {
  return ['dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com', 'cn-hongkong.dashscope.aliyuncs.com'].includes(host)
    || /^[a-z0-9-]+\.(?:cn-beijing|ap-southeast-1|us-east-1|cn-hongkong|eu-central-1|ap-northeast-1)\.maas\.aliyuncs\.com$/i.test(host);
}

async function readResponseBounded(response: Response, limit: number): Promise<Buffer> {
  if (Number(response.headers.get('content-length')) > limit) throw new Error('Qwen response exceeds the configured size limit');
  const reader = response.body?.getReader();
  if (!reader) throw new Error('Qwen response body is missing');
  const chunks: Buffer[] = [];
  let size = 0;
  while (true) {
    const {done, value} = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > limit) {
      await reader.cancel();
      throw new Error('Qwen response exceeds the configured size limit');
    }
    chunks.push(Buffer.from(value));
  }
  return Buffer.concat(chunks, size);
}
