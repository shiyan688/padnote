import {createHash, randomUUID} from 'node:crypto';
import {readFile, rename, unlink, writeFile} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const skillRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const pins = {
  'utils.js': '9b03fa46b6b646746d92e5933341672bcde74c2fbf9be643cc123e699b816a19',
  'generate-audio.js': '8c1de51822ea0e3c6879f3678b7073f359b577bb7ec073a4b842d0956c9d75fa',
  'ffmpeg-exporter-server.js': 'e5a5e7e5b79a96f0b6b84a922ed38cd9a4435b2669f412faa7bf5de4dda9d50e',
  'video-frame-extractor.js': 'e4e95a625fb35b0fabf65d60017a395a42508d91c37cee14d3209ab65d3f150f',
};
const patchedPins = {
  'utils.js': 'ec0029ee91ca5b533552a83588abb91847820d8ea4a03f2c6225cf376a18696e',
  'generate-audio.js': '5c9903f8eecb2f008b91170ee06b0c82b317ec386315868e3996fea33e9c486f',
  'ffmpeg-exporter-server.js': 'b10e9e7f5c3e64a4afb66c07b35f8fa75e58bc0b11371cb205df07a32b25c0cf',
  'video-frame-extractor.js': 'f24baf12af13af927f1bdbef59922a0222628d6466a8297a92f25427912cd3ac',
};
const helperSha256 = '41ed5dd8c9f96a51e5e08b50c4579e7436ffc260287e075de07292b10a9848ef';
const patches = {
  'utils.js': [
    ['const settings_1 = require("./settings");', 'const settings_1 = require("./settings");\nconst path_adapter_1 = require("./padnote-windows-paths");'],
    ['.input(tempFile)', '.input((0, path_adapter_1.ffmpegLocalPath)(tempFile))'],
    ['.save(outputFile);', '.save((0, path_adapter_1.ffmpegLocalPath)(outputFile));'],
    ['.save(filePath);', '.save((0, path_adapter_1.ffmpegLocalPath)(filePath));'],
    ['ffmpeg.ffprobe(filePath,', 'ffmpeg.ffprobe((0, path_adapter_1.ffmpegLocalPath)(filePath),', 5],
    ['.input(videoPath)', '.input((0, path_adapter_1.ffmpegLocalPath)(videoPath))'],
    ['.input(audioPath)', '.input((0, path_adapter_1.ffmpegLocalPath)(audioPath))'],
    ['.save(outputPath);', '.save((0, path_adapter_1.ffmpegLocalPath)(outputPath));'],
    ['ffmpeg.ffprobe(file,', 'ffmpeg.ffprobe((0, path_adapter_1.ffmpegLocalPath)(file),'],
  ],
  'generate-audio.js': [
    ['const utils_1 = require("./utils");', 'const utils_1 = require("./utils");\nconst path_adapter_1 = require("./padnote-windows-paths");'],
    ['ffmpeg(resolvedPath)', 'ffmpeg((0, path_adapter_1.ffmpegLocalPath)(resolvedPath))'],
    ['.save(outputPath);', '.save((0, path_adapter_1.ffmpegLocalPath)(outputPath));'],
    ['command.input(filename);', 'command.input((0, path_adapter_1.ffmpegLocalPath)(filename));'],
    ['.save(path.join(tempDir, `audio.wav`));', '.save((0, path_adapter_1.ffmpegLocalPath)(path.join(tempDir, `audio.wav`)));'],
  ],
  'ffmpeg-exporter-server.js': [
    ['const settings_1 = require("./settings");', 'const settings_1 = require("./settings");\nconst path_adapter_1 = require("./padnote-windows-paths");'],
    ['.output(path.join(this.jobFolder, `visuals.${exports.extensions[this.format]}`))', '.output((0, path_adapter_1.ffmpegLocalPath)(path.join(this.jobFolder, `visuals.${exports.extensions[this.format]}`)))'],
  ],
  'video-frame-extractor.js': [
    ['const settings_1 = require("./settings");', 'const settings_1 = require("./settings");\nconst path_adapter_1 = require("./padnote-windows-paths");'],
    ['ffmpeg.ffprobe(url,', 'ffmpeg.ffprobe((0, path_adapter_1.ffmpegLocalPath)(url),'],
    ['ffmpeg(url)', 'ffmpeg((0, path_adapter_1.ffmpegLocalPath)(url))'],
    ['.output(outputPath)', '.output((0, path_adapter_1.ffmpegLocalPath)(outputPath))'],
    ['ffmpeg(filePath)', 'ffmpeg((0, path_adapter_1.ffmpegLocalPath)(filePath))', 2],
  ],
};

function sha(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
function replaceOnce(source, before, after, name, expectedCount = 1) {
  let count = 0, cursor = 0, result = '';
  while (true) {
    const i = source.indexOf(before, cursor);
    if (i < 0) { result += source.slice(cursor); break; }
    count += 1; result += source.slice(cursor, i) + after; cursor = i + before.length;
  }
  if (count !== expectedCount) throw new Error(`FFmpeg patch anchor mismatch: ${name}:${JSON.stringify(before)}:${count}`);
  return result;
}
export async function applyFfmpegPathPatch(root = skillRoot) {
  const pkgPath = resolve(root, 'node_modules/@revideo/ffmpeg/package.json');
  const pkg = JSON.parse(await readFile(pkgPath, 'utf8'));
  if (pkg.version !== '0.11.0' || pkg.gitHead !== '73479d620d151792edfee45cca32395fd1b60b94') throw new Error('unsupported @revideo/ffmpeg package');
  const targetRoot = resolve(root, 'node_modules/@revideo/ffmpeg/dist');
  const pending = [];
  for (const [name, expected] of Object.entries(pins)) {
    const target = resolve(targetRoot, name);
    const original = await readFile(target);
    if (sha(original) === patchedPins[name]) continue;
    if (sha(original) !== expected) throw new Error(`unknown @revideo/ffmpeg source bytes: ${name}`);
    let next = original.toString('utf8');
    for (const [before, after, count] of patches[name]) next = replaceOnce(next, before, after, name, count ?? 1);
    if (name === 'utils.js') {
      const start = next.indexOf('.map(file =>');
      const end = next.indexOf('\n        .join', start);
      if (start < 0 || end < 0 || next.indexOf('.map(file =>', start + 1) >= 0) throw new Error('FFmpeg concat anchor mismatch');
      next = next.slice(0, start) + '.map(file => (0, path_adapter_1.ffmpegConcatFileLine)(file))' + next.slice(end);
    }
    if (sha(Buffer.from(next)) !== patchedPins[name]) throw new Error(`@revideo/ffmpeg patch output pin mismatch: ${name}`);
    pending.push([target, Buffer.from(next)]);
  }
  const helperPath = resolve(targetRoot, 'padnote-windows-paths.js');
  const helper = `"use strict";\nconst path = require("node:path");\nconst protocol = /^(?:anullsrc=|(?:https?|data|pipe|lavfi|concat|file|fd):|(?!(?:[a-z]:))[a-z][a-z\\d+.-]*:)/i;\nfunction ffmpegLocalPath(value) { if (process.platform !== "win32" || typeof value !== "string" || !value || value === "-" || protocol.test(value) || value.startsWith("\\\\\\\\?\\\\") || value.startsWith("\\\\\\\\.\\\\")) return value; const absolute = path.win32.resolve(value); if (absolute.startsWith("\\\\\\\\")) return "\\\\\\\\?\\\\UNC\\\\" + absolute.slice(2); return "\\\\\\\\?\\\\" + absolute; }\nfunction ffmpegConcatFileLine(value) { const file = ffmpegLocalPath(value); return "file '" + file.replace(/'/g, "'\\\\''") + "'"; }\nmodule.exports = {ffmpegLocalPath, ffmpegConcatFileLine};\n`;
  const helperBytes = Buffer.from(helper);
  if (sha(helperBytes) !== helperSha256) throw new Error(`FFmpeg path helper output pin mismatch: ${sha(helperBytes)}`);
  let createHelper = false;
  try {
    if (sha(await readFile(helperPath)) !== helperSha256) throw new Error('unknown @revideo/ffmpeg path helper bytes');
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    createHelper = true;
  }
  if (createHelper) await writeFile(helperPath, helperBytes, {flag: 'wx'});
  for (const [target, output] of pending) {
    const temp = `${target}.${process.pid}.${randomUUID()}.padnote-patch.tmp`;
    try { await writeFile(temp, output, {flag: 'wx'}); await rename(temp, target); }
    catch (error) { await unlink(temp).catch(() => undefined); throw error; }
  }
  return pending.length ? 'patched' : 'already-patched';
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  applyFfmpegPathPatch().then(x => console.log(`@revideo/ffmpeg Windows path patch: ${x}`)).catch(() => { console.error('@revideo/ffmpeg Windows path patch failed: PATCH_REJECTED'); process.exitCode = 1; });
}
