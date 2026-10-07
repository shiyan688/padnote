import assert from 'node:assert/strict';
import {cp, lstat, mkdir, mkdtemp, readFile, rm, symlink, unlink, writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import test from 'node:test';
import {skillRoot, type JsonObject} from '../scripts/lib.js';
import {validateBoundedInput} from '../scripts/validate-bounded-input.js';

const fixtureRoot = resolve(skillRoot(), 'tests/fixtures/formula-note');

async function makeFixture(): Promise<{root: string; request: JsonObject; cleanup: () => Promise<void>}> {
  const root = await mkdtemp(join(tmpdir(), 'padnote-bounded-input-'));
  await cp(fixtureRoot, root, {recursive: true});
  const request = JSON.parse(await readFile(join(root, 'request.json'), 'utf8')) as JsonObject;
  return {root, request, cleanup: () => rm(root, {recursive: true, force: true})};
}

async function readManifest(root: string): Promise<JsonObject> {
  return JSON.parse(await readFile(join(root, 'input/manifest.json'), 'utf8')) as JsonObject;
}

async function writeManifest(root: string, manifest: JsonObject): Promise<void> {
  await writeFile(join(root, 'input/manifest.json'), JSON.stringify(manifest));
}

test('bounded input accepts the formula-note fixture', async () => {
  const fixture = await makeFixture();
  try {
    await validateBoundedInput(fixture.root, fixture.request);
  } finally {
    await fixture.cleanup();
  }
});

test('bounded input rejects an incomplete tree and wrong bundle digest', async () => {
  const fixture = await makeFixture();
  try {
    await unlink(join(fixture.root, 'input/content.md'));
    await assert.rejects(validateBoundedInput(fixture.root, fixture.request), /manifest|input tree/);
  } finally {
    await fixture.cleanup();
  }

  const badDigest = await makeFixture();
  try {
    (badDigest.request.source as JsonObject).bundle_sha256 = '0'.repeat(64);
    await assert.rejects(validateBoundedInput(badDigest.root, badDigest.request), /bundle_sha256/);
  } finally {
    await badDigest.cleanup();
  }
});

test('bounded input rejects same-size content changes by SHA-256', async () => {
  const fixture = await makeFixture();
  try {
    const contentPath = join(fixture.root, 'input/content.md');
    const original = await readFile(contentPath);
    const changed = Buffer.from(original);
    changed[0] = changed[0] === 0x20 ? 0x21 : 0x20;
    assert.equal(changed.byteLength, original.byteLength);
    await writeFile(contentPath, changed);
    await assert.rejects(validateBoundedInput(fixture.root, fixture.request), /manifest sha256 mismatch/);
  } finally {
    await fixture.cleanup();
  }
});

test('bounded input rejects a declared MIME that differs from its path', async () => {
  const fixture = await makeFixture();
  try {
    const manifest = await readManifest(fixture.root);
    manifest.files[0].media_type = 'application/json';
    await writeManifest(fixture.root, manifest);
    await assert.rejects(validateBoundedInput(fixture.root, fixture.request), /manifest MIME mismatch/);
  } finally {
    await fixture.cleanup();
  }
});

test('bounded input rejects a depth-nine directory tree', async () => {
  const fixture = await makeFixture();
  try {
    let directory = join(fixture.root, 'input');
    for (let depth = 1; depth <= 9; depth += 1) {
      directory = join(directory, `level-${depth}`);
      await mkdir(directory);
    }
    await assert.rejects(validateBoundedInput(fixture.root, fixture.request), /maximum depth/);
  } finally {
    await fixture.cleanup();
  }
});

test('bounded input rejects more than 64 declared files and more than 128 tree entries', async () => {
  const tooManyFiles = await makeFixture();
  try {
    const manifest = await readManifest(tooManyFiles.root);
    manifest.files = Array.from({length: 65}, (_, index) => ({
      path: `input/file-${String(index).padStart(2, '0')}.md`,
      media_type: 'text/markdown',
      size_bytes: 0,
      sha256: '0'.repeat(64),
    }));
    await writeManifest(tooManyFiles.root, manifest);
    await assert.rejects(validateBoundedInput(tooManyFiles.root, tooManyFiles.request), /64 files/);
  } finally {
    await tooManyFiles.cleanup();
  }

  const tooManyEntries = await makeFixture();
  try {
    for (let index = 0; index < 129; index += 1) {
      await mkdir(join(tooManyEntries.root, 'input', `empty-${String(index).padStart(3, '0')}`));
    }
    await assert.rejects(validateBoundedInput(tooManyEntries.root, tooManyEntries.request), /128 entries/);
  } finally {
    await tooManyEntries.cleanup();
  }
});

test('bounded input rejects size, path, and special-file budget violations', async () => {
  const tooLarge = await makeFixture();
  try {
    const manifest = await readManifest(tooLarge.root);
    manifest.files[0].size_bytes = 32 * 1024 * 1024 + 1;
    await writeManifest(tooLarge.root, manifest);
    await assert.rejects(validateBoundedInput(tooLarge.root, tooLarge.request), /32 MiB/);
  } finally {
    await tooLarge.cleanup();
  }

  const longPath = await makeFixture();
  try {
    const manifest = await readManifest(longPath.root);
    manifest.files[0].path = `input/${'a'.repeat(240)}/${'b'.repeat(240)}/${'c'.repeat(40)}.md`;
    await writeManifest(longPath.root, manifest);
    await assert.rejects(validateBoundedInput(longPath.root, longPath.request), /512 UTF-8 bytes/);
  } finally {
    await longPath.cleanup();
  }

  const special = await makeFixture();
  try {
    const target = join(special.root, 'input', 'linked.md');
    await symlink('content.md', target);
    await assert.rejects(validateBoundedInput(special.root, special.request), /symlink/);
    const info = await lstat(target);
    assert.ok(info.isSymbolicLink());
    await unlink(target);
    const fifo = join(special.root, 'input', 'pipe.md');
    execFileSync('mkfifo', [fifo]);
    await assert.rejects(validateBoundedInput(special.root, special.request), /special file/);
  } finally {
    await special.cleanup();
  }
});
