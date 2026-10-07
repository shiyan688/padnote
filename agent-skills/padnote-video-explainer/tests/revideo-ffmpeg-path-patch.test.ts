import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {mkdtemp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {applyFfmpegPathPatch} from '../scripts/apply-revideo-ffmpeg-path-patch.mjs';

const packageRoot = process.env.PADNOTE_FFMPEG_TEST_PACKAGE
  ? path.resolve(process.env.PADNOTE_FFMPEG_TEST_PACKAGE)
  : path.resolve('node_modules/@revideo/ffmpeg');
const expected = new Map([
  ['utils.js', 'ec0029ee91ca5b533552a83588abb91847820d8ea4a03f2c6225cf376a18696e'],
  ['generate-audio.js', '5c9903f8eecb2f008b91170ee06b0c82b317ec386315868e3996fea33e9c486f'],
  ['ffmpeg-exporter-server.js', 'b10e9e7f5c3e64a4afb66c07b35f8fa75e58bc0b11371cb205df07a32b25c0cf'],
  ['video-frame-extractor.js', 'f24baf12af13af927f1bdbef59922a0222628d6466a8297a92f25427912cd3ac'],
]);
const originalFixture = JSON.parse(await readFile(new URL('./fixtures/revideo-ffmpeg-0.11.0-upstream-source.json', import.meta.url), 'utf8')) as {
  license_sha256: string;
  license_text: string;
  files: Record<string, {sha256: string; bytes: number; base64: string}>;
};
function sha(bytes: Buffer): string { return createHash('sha256').update(bytes).digest('hex'); }

async function makeOriginalFixture(): Promise<string> {
  const root = await mkdtemp(path.join(tmpdir(), 'padnote-ffmpeg-original-'));
  const pkg = path.join(root, 'node_modules/@revideo/ffmpeg');
  await mkdir(path.join(pkg, 'dist'), {recursive: true});
  await writeFile(path.join(pkg, 'package.json'), JSON.stringify({version: '0.11.0', gitHead: '73479d620d151792edfee45cca32395fd1b60b94'}));
  for (const [name, entry] of Object.entries(originalFixture.files)) {
    const bytes = Buffer.from(entry.base64, 'base64');
    assert.equal(bytes.length, entry.bytes);
    assert.equal(sha(bytes), entry.sha256);
    await writeFile(path.join(pkg, 'dist', name), bytes);
  }
  const license = Buffer.from(originalFixture.license_text);
  assert.equal(sha(license), originalFixture.license_sha256);
  await writeFile(path.join(pkg, 'LICENSE'), license);
  return root;
}

test('installed Revideo FFmpeg files match exact patched bytes and wrap input/output/probe callsites', async () => {
  for (const [name, hash] of expected) {
    const bytes = await readFile(path.join(packageRoot, 'dist', name));
    assert.equal(sha(bytes), hash, `${name} patched pin`);
    const source = bytes.toString();
    assert.match(source, /padnote-windows-paths/);
    assert.match(source, /ffmpegLocalPath/);
  }
  const utils = await readFile(path.join(packageRoot, 'dist/utils.js'), 'utf8');
  assert.match(utils, /ffmpegConcatFileLine/);
  assert.match(utils, /-safe 0/); // Preserve upstream concat protocol policy.
  assert.equal((utils.match(/ffmpeg\.ffprobe\(\(0, path_adapter_1\.ffmpegLocalPath\)\(filePath\)/g) ?? []).length, 5);
  const extractor = await readFile(path.join(packageRoot, 'dist/video-frame-extractor.js'), 'utf8');
  assert.match(extractor, /ffmpegLocalPath\)\(url\)/); // URL remains a pass-through in the path adapter.
  assert.equal((extractor.match(/ffmpegLocalPath\)\(filePath\)/g) ?? []).length, 2);
});

test('installer transforms exact pinned upstream source bytes, emits exact output pins, and is idempotent', async () => {
  const temp = await makeOriginalFixture();
  const targetRoot = path.join(temp, 'node_modules/@revideo/ffmpeg');
  try {
    assert.equal(await applyFfmpegPathPatch(temp), 'patched');
    for (const [name, hash] of expected) assert.equal(sha(await readFile(path.join(targetRoot, 'dist', name))), hash);
    assert.equal(sha(await readFile(path.join(targetRoot, 'dist/padnote-windows-paths.js'))), '41ed5dd8c9f96a51e5e08b50c4579e7436ffc260287e075de07292b10a9848ef');
    assert.equal(await applyFfmpegPathPatch(temp), 'already-patched');
  } finally {
    await rm(temp, {recursive: true, force: true});
  }
});

test('installer validates every original target before writing any patch when the fourth target is corrupt', async () => {
  const temp = await makeOriginalFixture();
  const targetRoot = path.join(temp, 'node_modules/@revideo/ffmpeg');
  try {
    const firstTargets = ['utils.js', 'generate-audio.js', 'ffmpeg-exporter-server.js'];
    const firstBytes = await Promise.all(firstTargets.map(name => readFile(path.join(targetRoot, 'dist', name))));
    const lastTarget = path.join(targetRoot, 'dist/video-frame-extractor.js');
    await writeFile(lastTarget, Buffer.concat([await readFile(lastTarget), Buffer.from('\n// tampered\n')]));
    await assert.rejects(applyFfmpegPathPatch(temp), /unknown @revideo\/ffmpeg source bytes/);
    for (let index = 0; index < firstTargets.length; index += 1) {
      assert.deepEqual(await readFile(path.join(targetRoot, 'dist', firstTargets[index]!)), firstBytes[index]);
    }
    await assert.rejects(readFile(path.join(targetRoot, 'dist/padnote-windows-paths.js')), {code: 'ENOENT'});
  } finally {
    await rm(temp, {recursive: true, force: true});
  }
});

test('installer repairs a mixed original/patched install without accepting unknown bytes', async () => {
  const temp = await makeOriginalFixture();
  const targetRoot = path.join(temp, 'node_modules/@revideo/ffmpeg');
  try {
    assert.equal(await applyFfmpegPathPatch(temp), 'patched');
    const originalLast = Buffer.from(originalFixture.files['video-frame-extractor.js']!.base64, 'base64');
    await writeFile(path.join(targetRoot, 'dist/video-frame-extractor.js'), originalLast);
    assert.equal(await applyFfmpegPathPatch(temp), 'patched');
    for (const [name, hash] of expected) assert.equal(sha(await readFile(path.join(targetRoot, 'dist', name))), hash);
    assert.equal(await applyFfmpegPathPatch(temp), 'already-patched');
  } finally {
    await rm(temp, {recursive: true, force: true});
  }
});

test('the exact generated CommonJS helper matches Windows conversion semantics', async () => {
  const require = createRequire(import.meta.url);
  const helper = require(path.join(packageRoot, 'dist/padnote-windows-paths.js')) as {
    ffmpegLocalPath(value: string): string;
    ffmpegConcatFileLine(value: string): string;
  };
  const descriptor = Object.getOwnPropertyDescriptor(process, 'platform')!;
  try {
    Object.defineProperty(process, 'platform', {value: 'win32'});
    assert.equal(helper.ffmpegLocalPath('C:\\long\\audio.wav'), '\\\\?\\C:\\long\\audio.wav');
    assert.equal(helper.ffmpegLocalPath('anullsrc=channel_layout=stereo'), 'anullsrc=channel_layout=stereo');
    assert.equal(helper.ffmpegLocalPath('-'), '-');
    const stream = {pipe: true};
    assert.equal((helper.ffmpegLocalPath as (value: unknown) => unknown)(stream), stream);
    assert.equal(helper.ffmpegLocalPath('https://example.test/a.wav'), 'https://example.test/a.wav');
    assert.equal(helper.ffmpegConcatFileLine("C:\\long path\\a'b.wav"), "file '\\\\?\\C:\\long path\\a'\\''b.wav'");
  } finally {
    Object.defineProperty(process, 'platform', descriptor);
  }
});
