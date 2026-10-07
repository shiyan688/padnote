import test from 'node:test';
import assert from 'node:assert/strict';
import {promisify} from 'node:util';
import {execFile} from 'node:child_process';
import {mkdtemp, rm, writeFile, stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {createRequire} from 'node:module';
import path from 'node:path';
import {ffmpegConcatFileLine, ffmpegLocalPath} from '../scripts/ffmpeg-paths.js';
const execFileAsync = promisify(execFile);
const require = createRequire(import.meta.url);

test('Windows local FFmpeg operands use extended absolute paths without changing canonical input', () => {
  const original = 'C:\\Users\\wyg\\AppData\\Local\\Temp\\' + 'a'.repeat(224) + '\\audio.wav';
  const actual = ffmpegLocalPath(original, 'win32', 'C:\\work');
  assert.equal(actual, `\\\\?\\${original}`);
  assert.equal(original.length, 266);
});

test('Windows relative and UNC operands normalize, while URLs, protocols, and existing device paths pass through', () => {
  assert.equal(ffmpegLocalPath('audio\\mix.wav', 'win32', 'C:\\work\\case'), '\\\\?\\C:\\work\\case\\audio\\mix.wav');
  assert.equal(ffmpegLocalPath('C:audio.wav', 'win32', 'C:\\work\\case'), '\\\\?\\C:\\work\\case\\audio.wav');
  assert.equal(ffmpegLocalPath('\\\\server\\share\\video.mp4', 'win32'), '\\\\?\\UNC\\server\\share\\video.mp4');
  for (const input of ['https://example.test/a.mp4', 'data:audio/wav;base64,AA==', 'anullsrc=channel_layout=stereo', 'pipe:1', '\\\\?\\C:\\already.wav', '\\\\.\\pipe\\name']) {
    assert.equal(ffmpegLocalPath(input, 'win32', 'C:\\work'), input);
  }
  assert.equal(ffmpegLocalPath('-', 'win32', 'C:\\work'), '-');
});

test('POSIX operands remain byte-for-byte unchanged', () => {
  const input = '/tmp/a long path/audio.wav';
  assert.equal(ffmpegLocalPath(input, 'linux', '/var/tmp'), input);
});

test('concat-list file entries preserve ffmpeg quoting and normalize local paths', () => {
  const line = ffmpegConcatFileLine("C:\\long path\\owner's\\audio.wav", 'win32');
  assert.equal(line, "file '\\\\?\\C:\\long path\\owner'\\''s\\audio.wav'");
  assert.equal(ffmpegConcatFileLine('https://example.test/clip.mp4', 'win32'), "file 'https://example.test/clip.mp4'");
});

test('FFmpeg concat parser accepts local filenames with spaces and apostrophes', async (t) => {
  const ffmpeg = (require('@ffmpeg-installer/ffmpeg') as {path: string}).path;
  const ffprobe = (require('@ffprobe-installer/ffprobe') as {path: string}).path;
  const root = await mkdtemp(path.join(tmpdir(), 'ffmpeg concat quote '));
  const one = path.join(root, "first's audio.wav");
  const two = path.join(root, 'second audio.wav');
  const list = path.join(root, 'concat list.txt');
  const legacyList = path.join(root, 'legacy concat list.txt');
  const output = path.join(root, 'joined.wav');
  try {
    for (const target of [one, two]) {
      await execFileAsync(ffmpeg, ['-y', '-v', 'error', '-f', 'lavfi', '-i', 'anullsrc=channel_layout=stereo:sample_rate=48000', '-t', '0.05', '-c:a', 'pcm_s16le', ffmpegLocalPath(target)]);
    }
    const oldQuote = (value: string) => `file '${value.replace(/'/g, "\\'")}'`;
    await writeFile(legacyList, `${oldQuote(ffmpegLocalPath(one))}\n${oldQuote(ffmpegLocalPath(two))}\n`, 'utf8');
    let legacyFailure: {code?: number | string} | undefined;
    try {
      await execFileAsync(ffmpeg, ['-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-protocol_whitelist', 'file,http,https,tcp,tls', '-i', ffmpegLocalPath(legacyList), '-c', 'copy', ffmpegLocalPath(output)]);
    } catch (error) {
      legacyFailure = error as {code?: number | string};
    }
    assert.ok(legacyFailure, 'legacy apostrophe escaping must fail in the FFmpeg concat parser');

    await writeFile(list, `${ffmpegConcatFileLine(one)}\n${ffmpegConcatFileLine(two)}\n`, 'utf8');
    await execFileAsync(ffmpeg, ['-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-protocol_whitelist', 'file,http,https,tcp,tls', '-i', ffmpegLocalPath(list), '-c', 'copy', ffmpegLocalPath(output)]);
    const {stdout} = await execFileAsync(ffprobe, ['-v', 'error', '-show_entries', 'format=duration', '-of', 'json', ffmpegLocalPath(output)]);
    const duration = Number(JSON.parse(stdout).format.duration);
    const outputBytes = (await stat(output)).size;
    assert.ok(duration > 0);
    assert.ok(outputBytes > 44);
    assert.equal(output, path.join(root, 'joined.wav')); // Node-side canonical path remains unchanged.
    t.diagnostic(JSON.stringify({legacy_concat_exit_code: legacyFailure.code ?? 'nonzero', corrected_concat: 'success', duration_seconds: duration, output_bytes: outputBytes}));
  } finally {
    await rm(root, {recursive: true, force: true});
  }
});
