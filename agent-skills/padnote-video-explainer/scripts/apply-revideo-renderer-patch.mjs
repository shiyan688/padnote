import {createHash, randomUUID} from 'node:crypto';
import {readFile, rename, unlink, writeFile} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import patch from '../patches/revideo-renderer-0.11.0.json' with {type: 'json'};

const skillRoot = dirname(dirname(fileURLToPath(import.meta.url)));

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function replaceExactlyOnce(source, before, after) {
  const first = source.indexOf(before);
  if (first < 0 || source.indexOf(before, first + before.length) >= 0) {
    throw new Error('renderer patch anchor mismatch');
  }
  return source.slice(0, first) + after + source.slice(first + before.length);
}

export async function applyRendererPatch(root = skillRoot) {
  const packageJson = JSON.parse(await readFile(resolve(root, 'node_modules/@revideo/renderer/package.json'), 'utf8'));
  if (packageJson.version !== patch.version) throw new Error('unsupported @revideo/renderer version');
  const target = resolve(root, 'node_modules/@revideo/renderer', patch.relative_path);
  const current = await readFile(target);
  const currentHash = sha256(current);
  if (currentHash === patch.patched_sha256) return 'already-patched';
  if (currentHash !== patch.original_sha256) throw new Error('unknown @revideo/renderer source bytes');

  let next = current.toString('utf8');
  for (const replacement of patch.replacements) {
    next = replaceExactlyOnce(next, replacement.original, replacement.patched);
  }
  const output = Buffer.from(next, 'utf8');
  if (sha256(output) !== patch.patched_sha256) throw new Error('renderer patch output hash mismatch');
  const temporary = `${target}.${process.pid}.${randomUUID()}.padnote-patch.tmp`;
  try {
    await writeFile(temporary, output, {flag: 'wx'});
    await rename(temporary, target);
  } catch (error) {
    await unlink(temporary).catch(() => undefined);
    throw error;
  }
  return 'patched';
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  applyRendererPatch().then(result => console.log(`@revideo/renderer cleanup patch: ${result}`)).catch(error => {
    const code = typeof error?.code === 'string' && /^[A-Z0-9_]{1,32}$/.test(error.code) ? error.code : 'PATCH_REJECTED';
    console.error(`@revideo/renderer cleanup patch failed: ${code}`);
    process.exitCode = 1;
  });
}
