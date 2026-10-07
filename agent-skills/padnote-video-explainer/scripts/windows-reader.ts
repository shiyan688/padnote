import {randomUUID} from 'node:crypto';
import {spawn, type ChildProcessWithoutNullStreams} from 'node:child_process';
import {lstat} from 'node:fs/promises';
import {isAbsolute, relative, resolve, sep} from 'node:path';

const MAX_WHOLE_BYTES = 32 * 1024 * 1024;
const MAX_TAIL_BYTES = 64 * 1024;
const MAX_REQUEST_BYTES = 16 * 1024;
const MAX_RELATIVE_PATH_BYTES = 4096;
const MAX_RESPONSE_LINE_BYTES = Math.ceil(MAX_WHOLE_BYTES / 3) * 4 + 16 * 1024;
const REQUEST_TIMEOUT_MS = 5000;
const HELPER_ERROR_CODES = new Set([
  'invalid_request', 'invalid_path', 'size_limit', 'file_unavailable', 'not_found',
  'unsafe_file', 'file_changed', 'read_failed', 'request_limit', 'total_limit', 'protocol_error',
]);

interface ReaderMetadata {
  file_size: string;
  offset: string;
  dev: string;
  ino: string;
  mtime_ns: string;
  ctime_ns: string;
}

interface ReaderFile {
  bytes: Buffer;
  metadata: ReaderMetadata;
}

interface ReaderSuccess {
  schema_version: 1;
  id: string;
  ok: true;
  mode: 'whole' | 'tail';
  data_base64: string;
  file_size: string;
  offset: string;
  dev: string;
  ino: string;
  mtime_ns: string;
  ctime_ns: string;
}

interface ReaderFailure {
  schema_version: 1;
  id: string;
  ok: false;
  code: string;
}

class ReaderFileError extends Error {
  readonly code?: string;
  constructor(code: string) {
    super(code === 'not_found' ? 'Windows file is missing' : 'Windows file could not be read safely');
    this.code = code === 'not_found' ? 'ENOENT' : undefined;
  }
}

let helper: ChildProcessWithoutNullStreams | undefined;
let helperFailed = false;
let helperClosing = false;
let responseBuffer = Buffer.alloc(0);
let activeRequest: PendingRequest | undefined;
let requestQueue: QueuedRequest[] = [];
let pumping = false;

interface PendingRequest {
  id: string;
  mode: 'whole' | 'tail';
  maxBytes: number;
  declaredSize?: number;
  resolve: (value: ReaderFile) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

interface QueuedRequest {
  mode: 'whole' | 'tail';
  path: string;
  maxBytes: number;
  declaredSize?: number;
  resolve: (value: ReaderFile) => void;
  reject: (error: Error) => void;
}

/** Open and verify a bounded file through the trusted Windows handle reader. */
export async function readWindowsRegularFile(path: string, maxBytes: number,
                                             declaredSize?: number): Promise<ReaderFile> {
  return exchange('whole', path, maxBytes, declaredSize);
}

/** Read a bounded tail from one stable Windows file handle. */
export async function readWindowsFileTail(path: string, maxBytes: number): Promise<ReaderFile> {
  return exchange('tail', path, maxBytes);
}

/** Close the per-CLI helper before this Node process exits. */
export async function closeWindowsReader(): Promise<void> {
  const current = helper;
  helperClosing = true;
  for (const request of requestQueue) request.reject(new Error('Windows file reader is closing'));
  requestQueue = [];
  if (activeRequest) {
    const pending = activeRequest;
    activeRequest = undefined;
    clearTimeout(pending.timer);
    pending.reject(new Error('Windows file reader is closing'));
    if (current) poisonHelper(current);
  }
  if (!current) return;
  current.stdin.end();
  try {
    if (await waitForExit(current, 300)) return;
    current.kill();
    if (!await waitForExit(current, 300)) {
      helperFailed = true;
      throw new Error('Windows file reader did not stop');
    }
  } finally {
    // A kill request is not proof of exit. The exit/close handlers retain
    // ownership of this reference until Node confirms the child is gone.
    if (current.exitCode !== null || current.signalCode !== null) clearExitedHelper(current);
  }
}

function waitForExit(child: ChildProcessWithoutNullStreams, timeoutMs: number): Promise<boolean> {
  if (child.exitCode !== null || child.signalCode !== null) return Promise.resolve(true);
  return new Promise(resolveDone => {
    let settled = false;
    const finish = (exited: boolean) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      child.removeListener('exit', onExit);
      resolveDone(exited);
    };
    const onExit = () => finish(true);
    const timer = setTimeout(() => finish(false), timeoutMs);
    child.once('exit', onExit);
  });
}

function exchange(mode: 'whole' | 'tail', path: string, maxBytes: number,
                  declaredSize?: number): Promise<ReaderFile> {
  if (helperClosing || helperFailed) return Promise.reject(new Error('Windows file reader is unavailable'));
  if (requestQueue.length + (activeRequest ? 1 : 0) >= 64) {
    return Promise.reject(new Error('Windows file reader request queue is full'));
  }
  return new Promise<ReaderFile>((resolveResult, rejectResult) => {
    requestQueue.push({mode, path, maxBytes, declaredSize, resolve: resolveResult, reject: rejectResult});
    void pumpQueue();
  });
}

async function pumpQueue(): Promise<void> {
  if (pumping || activeRequest || helperClosing || helperFailed) return;
  pumping = true;
  try {
    while (!activeRequest && requestQueue.length > 0 && !helperClosing && !helperFailed) {
      const request = requestQueue.shift()!;
      try {
        request.resolve(await performRequest(request.mode, request.path, request.maxBytes, request.declaredSize));
      } catch (error) {
        request.reject(error instanceof Error ? error : new Error('Windows file reader failed'));
      }
    }
  } finally {
    pumping = false;
    if (!activeRequest && requestQueue.length > 0 && !helperClosing && !helperFailed) void pumpQueue();
  }
}

async function performRequest(mode: 'whole' | 'tail', path: string, maxBytes: number,
                              declaredSize?: number): Promise<ReaderFile> {
  const max = mode === 'whole' ? MAX_WHOLE_BYTES : MAX_TAIL_BYTES;
  if (!Number.isSafeInteger(maxBytes) || maxBytes < (mode === 'tail' ? 1 : 0) || maxBytes > max
      || declaredSize !== undefined && (!Number.isSafeInteger(declaredSize)
        || declaredSize < 0 || declaredSize > maxBytes)) {
    throw new Error('Windows file read exceeds its bound');
  }
  const root = trustedAbsoluteEnv('PADNOTE_WINDOWS_READER_ROOT');
  const relativePath = relativeFilePath(root, path);
  const id = randomUUID();
  const request = {
    schema_version: 1,
    id,
    mode,
    relative_path: relativePath,
    max_bytes: maxBytes,
    ...(declaredSize === undefined ? {} : {declared_size: declaredSize}),
  };
  const requestBytes = Buffer.from(`${JSON.stringify(request)}\n`, 'utf8');
  if (requestBytes.length > MAX_REQUEST_BYTES) throw new Error('Windows file read request is too large');
  const child = await ensureHelper();
  return new Promise<ReaderFile>((resolveResult, rejectResult) => {
    const timer = setTimeout(() => {
      poisonHelper(child);
      finishPending(new Error('Windows file reader timed out'));
    }, REQUEST_TIMEOUT_MS);
    activeRequest = {id, mode, maxBytes, declaredSize, resolve: resolveResult, reject: rejectResult, timer};
    child.stdin.write(requestBytes, error => {
      if (error) {
        poisonHelper(child);
        finishPending(new Error('Windows file reader process failed'));
      }
    });
  });
}

async function ensureHelper(): Promise<ChildProcessWithoutNullStreams> {
  if (helperFailed || helperClosing) throw new Error('Windows file reader is unavailable');
  if (helper) return helper;
  const python = trustedAbsoluteEnv('PADNOTE_WINDOWS_READER_PYTHON');
  const script = trustedAbsoluteEnv('PADNOTE_WINDOWS_READER_SCRIPT');
  await assertTrustedRegularFile(python);
  await assertTrustedRegularFile(script);
  const root = trustedAbsoluteEnv('PADNOTE_WINDOWS_READER_ROOT');
  if (helperFailed || helperClosing) throw new Error('Windows file reader is unavailable');
  const childEnv: NodeJS.ProcessEnv = {};
  for (const name of ['PATH', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP']) {
    if (process.env[name] !== undefined) childEnv[name] = process.env[name];
  }
  const child = spawn(python, ['-I', '-B', '-u', script, '--root', root], {
    cwd: root,
    env: childEnv,
    stdio: 'pipe',
    windowsHide: true,
  });
  helper = child;
  responseBuffer = Buffer.alloc(0);
  child.stdout.on('data', chunk => handleStdout(child, chunk));
  child.stderr.on('data', () => {
    // Helper diagnostics are intentionally private and are never relayed.
  });
  child.once('error', () => {
    poisonHelper(child);
    finishPending(new Error('Windows file reader process failed'));
  });
  child.once('exit', () => onHelperExit(child));
  child.once('close', () => onHelperExit(child));
  return child;
}

function handleStdout(child: ChildProcessWithoutNullStreams, chunk: Buffer): void {
  const pending = activeRequest;
  if (helper !== child || !pending) {
    poisonHelper(child);
    return;
  }
  const newlineInChunk = chunk.indexOf(0x0a);
  const prefixLength = responseBuffer.length + (newlineInChunk < 0 ? chunk.length : newlineInChunk);
  const hasExtraBytes = newlineInChunk >= 0 && newlineInChunk !== chunk.length - 1;
  if (prefixLength > MAX_RESPONSE_LINE_BYTES || hasExtraBytes) {
    poisonHelper(child);
    finishPending(new Error(prefixLength > MAX_RESPONSE_LINE_BYTES
      ? 'Windows file reader response exceeded its bound'
      : 'Windows file reader emitted unexpected output'));
    return;
  }
  responseBuffer = Buffer.concat([responseBuffer, chunk]);
  if (newlineInChunk < 0) return;
  const line = responseBuffer.subarray(0, responseBuffer.length - 1);
  responseBuffer = Buffer.alloc(0);
  try {
    const result = parseResponse(line, pending.id, pending.mode, pending.maxBytes, pending.declaredSize);
    finishPending(undefined, result);
  } catch (error) {
    if (error instanceof ReaderFileError) finishPending(error);
    else {
      poisonHelper(child);
      finishPending(new Error('Windows file reader returned an invalid response'));
    }
  }
}

function finishPending(error?: Error, value?: ReaderFile): void {
  const pending = activeRequest;
  if (!pending) return;
  activeRequest = undefined;
  clearTimeout(pending.timer);
  if (error) pending.reject(error);
  else pending.resolve(value!);
  void pumpQueue();
}

function onHelperExit(child: ChildProcessWithoutNullStreams): void {
  if (child.exitCode === null && child.signalCode === null) return;
  if (!helperClosing) {
    helperFailed = true;
    rejectQueued(new Error('Windows file reader exited before replying'));
    finishPending(new Error('Windows file reader exited before replying'));
  }
  clearExitedHelper(child);
}

function clearExitedHelper(child: ChildProcessWithoutNullStreams): void {
  if (helper !== child) return;
  helper = undefined;
  responseBuffer = Buffer.alloc(0);
}

function parseResponse(line: Buffer, id: string, mode: 'whole' | 'tail', maxBytes: number,
                       declaredSize?: number): ReaderFile {
  let value: unknown;
  try {
    value = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(line));
  } catch {
    throw new Error('invalid JSON');
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid response');
  const response = value as Record<string, unknown>;
  if (response.schema_version !== 1 || response.id !== id || typeof response.ok !== 'boolean') {
    throw new Error('invalid response identity');
  }
  if (response.ok === false) {
    if (Object.keys(response).sort().join(',') !== 'code,id,ok,schema_version'
        || typeof response.code !== 'string' || !HELPER_ERROR_CODES.has(response.code)) {
      throw new Error('invalid error response');
    }
    throw new ReaderFileError(response.code);
  }
  if (response.mode !== mode) throw new Error('invalid response mode');
  const expectedKeys = ['schema_version', 'id', 'ok', 'mode', 'data_base64', 'file_size',
    'offset', 'dev', 'ino', 'mtime_ns', 'ctime_ns'].sort().join(',');
  if (Object.keys(response).sort().join(',') !== expectedKeys || typeof response.data_base64 !== 'string') {
    throw new Error('invalid success response fields');
  }
  const metadata: ReaderMetadata = {
    file_size: decimal(response.file_size), offset: decimal(response.offset),
    dev: decimal(response.dev), ino: decimal(response.ino),
    mtime_ns: decimal(response.mtime_ns), ctime_ns: decimal(response.ctime_ns),
  };
  const encoded = response.data_base64;
  const maxEncoded = Math.ceil(maxBytes / 3) * 4;
  if (encoded.length > maxEncoded || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encoded)) {
    throw new Error('invalid encoded data');
  }
  const bytes = Buffer.from(encoded, 'base64');
  if (bytes.length > maxBytes || bytes.toString('base64') !== encoded) throw new Error('invalid data length');
  const fileSize = BigInt(metadata.file_size);
  const offset = BigInt(metadata.offset);
  if (offset + BigInt(bytes.length) !== fileSize
      || mode === 'whole' && (offset !== 0n || bytes.length !== Number(fileSize))
      || mode === 'tail' && (bytes.length > maxBytes || fileSize > BigInt(maxBytes) && bytes.length !== maxBytes)
      || declaredSize !== undefined && bytes.length !== declaredSize) {
    throw new Error('inconsistent file metadata');
  }
  return {bytes, metadata};
}

function decimal(value: unknown): string {
  if (typeof value !== 'string' || value.length > 40 || !/^(0|[1-9][0-9]*)$/.test(value)) {
    throw new Error('invalid metadata');
  }
  return value;
}

function trustedAbsoluteEnv(name: string): string {
  const value = process.env[name];
  if (!value || !isAbsolute(value) || resolve(value) !== value) throw new Error('trusted Windows reader configuration is invalid');
  return value;
}

function relativeFilePath(root: string, path: string): string {
  const target = resolve(path);
  const value = relative(root, target);
  if (!value || isAbsolute(value) || value === '..' || value.startsWith(`..${sep}`)) {
    throw new Error('Windows file path is outside the trusted task root');
  }
  const normalized = value.split(sep).join('/');
  const components = normalized.split('/');
  if (Buffer.byteLength(normalized, 'utf8') > MAX_RELATIVE_PATH_BYTES
      || components.some(part => !part || part === '.' || part === '..' || part.includes(':') || part.includes('\\'))) {
    throw new Error('Windows file path is invalid');
  }
  return normalized;
}

async function assertTrustedRegularFile(path: string): Promise<void> {
  const info = await lstat(path);
  if (!info.isFile() || info.isSymbolicLink()
      || (info as typeof info & {attributes?: number}).attributes !== undefined
        && ((info as typeof info & {attributes: number}).attributes & 0x400) !== 0) {
    throw new Error('trusted Windows reader executable is invalid');
  }
}

function poisonHelper(child: ChildProcessWithoutNullStreams): void {
  helperFailed = true;
  if (helper === child) responseBuffer = Buffer.alloc(0);
  rejectQueued(new Error('Windows file reader is unavailable'));
  if (child.exitCode === null && child.signalCode === null) child.kill();
}

function rejectQueued(error: Error): void {
  const waiting = requestQueue;
  requestQueue = [];
  for (const request of waiting) request.reject(error);
}
