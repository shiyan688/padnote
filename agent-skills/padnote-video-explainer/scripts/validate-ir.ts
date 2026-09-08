import {relative, resolve, sep} from 'node:path';
import {
  assertExistingFileInside,
  normalizeSentence,
  readJson,
  requireCliTaskRoot,
  safeRelativePath,
  unique,
  validateSchema,
  type JsonObject,
} from './lib.js';
import {validateRequest} from './validate-request.js';

const forbiddenIr = /(?:data:[^\s;]+;base64,|<\s*(?:html|style|script|div|span)\b|\bffmpeg\b|@revideo\/|\bremotion\b|React\.createElement|\bposition\s*:\s*(?:absolute|fixed))/i;

export async function validateIr(taskRoot: string, irPath?: string): Promise<JsonObject> {
  const request = await validateRequest(taskRoot);
  const requestedPath = resolve(irPath ?? resolve(taskRoot, 'output/lesson.ir.json'));
  const relativePath = relative(taskRoot, requestedPath).split(sep).join('/');
  const file = await assertExistingFileInside(taskRoot, relativePath);
  const ir = await readJson(file);
  await validateSchema('lesson-ir', ir);

  if (
    ir.source.bundle_sha256 !== request.source.bundle_sha256 ||
    ir.source.note_id !== request.source.note_id ||
    ir.source.note_revision !== request.source.note_revision
  ) throw new Error('Lesson IR source does not match request');
  if (ir.episode.language !== request.source.language) throw new Error('Lesson IR language does not match request');

  inspectIrStrings(ir, '$');
  const claims = ir.claims as JsonObject[];
  const scenes = ir.scenes as JsonObject[];
  unique(claims.map(claim => claim.id), 'claim id');
  unique(scenes.map(scene => scene.id), 'scene id');
  const claimIds = new Set(claims.map(claim => claim.id));
  for (const claim of claims) {
    if (claim.source_refs.length === 0 && claim.verification !== 'uncertain') {
      throw new Error(`claim ${claim.id} has no source_refs and is not uncertain`);
    }
    if (!request.research.external_research && claim.verification === 'external-source') {
      throw new Error(`external claim ${claim.id} is forbidden when external_research=false`);
    }
  }

  for (const scene of scenes) {
    for (const claimId of scene.claim_ids) {
      if (!claimIds.has(claimId)) throw new Error(`scene ${scene.id} references missing claim ${claimId}`);
    }
    const narration = normalizeSentence(scene.narration);
    for (const screenText of scene.screen_text) {
      if (narration === normalizeSentence(screenText)) {
        throw new Error(`scene ${scene.id} repeats narration verbatim as screen text`);
      }
    }
    await validateVisualReferences(taskRoot, scene);
  }
  return ir;
}

function inspectIrStrings(value: unknown, location: string): void {
  if (typeof value === 'string') {
    if (forbiddenIr.test(value)) throw new Error(`renderer implementation is forbidden in Lesson IR at ${location}`);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => inspectIrStrings(item, `${location}[${index}]`));
    return;
  }
  if (!value || typeof value !== 'object') return;
  for (const [key, item] of Object.entries(value)) inspectIrStrings(item, `${location}.${key}`);
}

async function validateVisualReferences(taskRoot: string, scene: JsonObject): Promise<void> {
  const visual = scene.visual as JsonObject;
  if (visual.type === 'concept_map') {
    const nodes = visual.data.nodes as JsonObject[];
    unique(nodes.map(node => node.id), `node id in ${scene.id}`);
    const ids = new Set(nodes.map(node => node.id));
    for (const edge of visual.data.edges as JsonObject[]) {
      if (!ids.has(edge.from) || !ids.has(edge.to)) throw new Error(`broken concept_map edge in ${scene.id}`);
    }
  }
  if (visual.type === 'annotated_source') {
    const assetPath = safeRelativePath(visual.data.asset_path);
    if (!assetPath.startsWith('input/assets/')) throw new Error(`annotated source escapes assets in ${scene.id}`);
    await assertExistingFileInside(taskRoot, assetPath);
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const taskRoot = requireCliTaskRoot();
  validateIr(taskRoot, process.argv[3]).then(ir => {
    console.log(`Lesson IR valid: ${ir.scenes.length} scenes`);
  }).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
