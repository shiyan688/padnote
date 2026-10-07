import path from 'node:path';

const URL_OR_PROTOCOL = /^(?:anullsrc=|(?:https?|data|pipe|lavfi|concat|file|fd):|(?!(?:[a-z]:))[a-z][a-z\d+.-]*:)/i;

/** Convert only local file operands at the FFmpeg boundary. Canonical application paths stay unchanged. */
export function ffmpegLocalPath(value: string, platform = process.platform, cwd = process.cwd()): string {
  if (platform !== 'win32' || !value || value === '-' || URL_OR_PROTOCOL.test(value) || value.startsWith('\\\\?\\') || value.startsWith('\\\\.\\')) {
    return value;
  }
  const absolute = path.win32.resolve(cwd, value);
  if (absolute.startsWith('\\\\')) return `\\\\?\\UNC\\${absolute.slice(2)}`;
  return `\\\\?\\${absolute}`;
}

/** FFmpeg concat-demuxer quoting, after local path normalization. */
export function ffmpegConcatFileLine(value: string, platform = process.platform, cwd = process.cwd()): string {
  const file = ffmpegLocalPath(value, platform, cwd);
  return `file '${file.replace(/'/g, "'\\''")}'`;
}
