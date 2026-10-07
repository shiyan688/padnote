import {execFile} from 'node:child_process';
import {createHash, randomUUID} from 'node:crypto';
import {constants, realpathSync} from 'node:fs';
import {appendFile, chmod, lstat, mkdir, open, readFile, rename, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve} from 'node:path';
import {promisify} from 'node:util';
import {atomicWriteJson, readJson, type JsonObject} from './lib.js';
import {inspectTask, readBoundedRegularFile} from './inspect-task.js';
import {readWindowsFileTail} from './windows-reader.js';

export type TaskStatus =
  | 'initialized'
  | 'awaiting_storyboard_review'
  | 'approved'
  | 'ready_to_render'
  | 'running'
  | 'interrupted'
  | 'failed'
  | 'cancelling'
  | 'cancelled'
  | 'completed';

export interface TaskApproval {
  approval_id: string;
  revision: number;
  lesson_ir_sha256: string;
  review_sha256: string;
  lesson_ir_snapshot_path: string;
  review_snapshot_path: string;
  granted_at: string;
  consumed_at?: string;
}

export interface TaskAttempt {
  attempt_id: string;
  lease_token?: string;
  pid: number;
  started_at: string;
  phase: string;
  external_effect_possible: boolean;
}

export interface TaskState extends JsonObject {
  schema_version: '1.0';
  task_id: string;
  request_sha256: string;
  status: TaskStatus;
  phase: string;
  updated_at: string;
  event_cursor: number;
  revision?: number;
  lesson_ir_sha256?: string;
  review_sha256?: string;
  approval?: TaskApproval;
  attempt?: TaskAttempt;
  cancellation?: {requested_at: string; completed_at?: string};
  error?: {message: string; phase: string; external_effect_possible: boolean};
}

export interface ExecutionLease {
  token: string;
  pid: number;
  acquired_at: string;
  process_identity: ProcessIdentity;
}

export interface ProcessIdentity {
  platform: string;
  boot_id: string;
  start_time: string;
  /** Set only where the OS exposes a process group (Linux/macOS). */
  process_group_id?: number;
  /** Windows has no POSIX process group; this is the verified leader PID. */
  process_control_id?: number;
}

const LOCK_RETRIES = 500;
const LOCK_RETRY_MS = 10;
const LOCK_CREATION_GRACE_MS = 2_000;
const MAX_AUDIT_TAIL_BYTES = 64 * 1024;
const execFileAsync = promisify(execFile);

export function statePath(taskRoot: string): string {
  return resolve(taskRoot, 'work/task-state.json');
}

export function eventPath(taskRoot: string): string {
  return resolve(taskRoot, 'work/task-events.ndjson');
}

function stateLockPath(taskRoot: string): string {
  return resolve(taskRoot, 'work/.task-state.lock');
}

export function executionLockPath(taskRoot: string): string {
  const identity = createHash('sha256').update(realpathSync(resolve(taskRoot))).digest('hex');
  const uid = typeof process.getuid === 'function' ? process.getuid() : 'nouid';
  return resolve(tmpdir(), `padnote-video-worker-${uid}`, identity);
}

export function executionLeaseMutationLockPath(taskRoot: string): string {
  return `${executionLockPath(taskRoot)}.mutation-lock`;
}

export async function loadTaskState(taskRoot: string): Promise<TaskState | undefined> {
  try {
    const value = await readJson(statePath(taskRoot)) as TaskState;
    if (value.schema_version !== '1.0' || typeof value.task_id !== 'string'
        || !Number.isInteger(value.event_cursor)) {
      throw new Error('task-state.json is invalid');
    }
    return value;
  } catch (error: any) {
    if (error?.code === 'ENOENT') return undefined;
    throw error;
  }
}

export function initialTaskState(taskId: string, requestSha256: string): TaskState {
  const now = new Date().toISOString();
  return {
    schema_version: '1.0',
    task_id: taskId,
    request_sha256: requestSha256,
    status: 'initialized',
    phase: 'idle',
    updated_at: now,
    event_cursor: 0,
  };
}

/** Serializes each state transition and appends a metadata-only audit event. */
export async function transitionTaskState(
  taskRoot: string,
  eventType: string,
  update: (current: TaskState | undefined) => TaskState,
  details: JsonObject = {},
): Promise<TaskState> {
  return withStateLock(taskRoot, async () => {
    const current = await loadTaskState(taskRoot);
    const next = update(current);
    const sequence = (current?.event_cursor ?? 0) + 1;
    next.event_cursor = sequence;
    next.updated_at = new Date().toISOString();
    await atomicWriteJson(statePath(taskRoot), next);
    await appendAuditEvent(taskRoot, {
      sequence,
      at: next.updated_at,
      event: eventType,
      task_id: next.task_id,
      status: next.status,
      phase: next.phase,
      ...details,
    });
    return next;
  });
}

/** Performs a state transition only when the freshly locked state still matches. */
export async function transitionTaskStateIf(
  taskRoot: string,
  eventType: string,
  predicate: (current: TaskState | undefined) => boolean,
  update: (current: TaskState) => TaskState,
  details: JsonObject = {},
): Promise<{state: TaskState; changed: boolean}> {
  return withStateLock(taskRoot, async () => {
    const current = await loadTaskState(taskRoot);
    if (!current) throw new Error('task state is missing');
    if (!predicate(current)) return {state: current, changed: false};
    const next = update(current);
    const sequence = current.event_cursor + 1;
    next.event_cursor = sequence;
    next.updated_at = new Date().toISOString();
    await atomicWriteJson(statePath(taskRoot), next);
    await appendAuditEvent(taskRoot, {
      sequence,
      at: next.updated_at,
      event: eventType,
      task_id: next.task_id,
      status: next.status,
      phase: next.phase,
      ...details,
    });
    return {state: next, changed: true};
  });
}

async function readValidatedTaskStateBounded(taskRoot: string): Promise<TaskState> {
  const bytes = await readBoundedRegularFile(statePath(taskRoot), 256 * 1024);
  let value: unknown;
  try {
    value = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(bytes));
  } catch {
    throw new Error('task state is invalid');
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('task state is invalid');
  }
  const state = value as TaskState;
  const inspection = await inspectTask(taskRoot);
  const after = await readBoundedRegularFile(statePath(taskRoot), 256 * 1024);
  if (!bytes.equals(after)) throw new Error('task state changed during exact validation');
  if (inspection.task_id !== state.task_id || inspection.status !== state.status
      || inspection.phase !== state.phase || inspection.event_cursor !== state.event_cursor
      || inspection.revision !== state.revision
      || inspection.lesson_ir_sha256 !== state.lesson_ir_sha256
      || inspection.review_sha256 !== state.review_sha256) {
    throw new Error('task state inspection does not match persisted state');
  }
  return state;
}

/** Executes an async validation/update while the task state and audit tail are locked. */
export async function transitionTaskStateValidated(
  taskRoot: string,
  eventType: string,
  prepare: (current: TaskState) => Promise<TaskState | undefined>,
  details: JsonObject = {},
): Promise<TaskState> {
  return withStateLock(taskRoot, async () => {
    const current = await readValidatedTaskStateBounded(taskRoot);
    if (!current) throw new Error('task state is missing');
    await assertLatestAuditEventMatchesState(taskRoot, current);
    const next = await prepare(current);
    if (!next) return current;
    const sequence = current.event_cursor + 1;
    if (!Number.isSafeInteger(sequence)) throw new Error('task event cursor is exhausted');
    next.event_cursor = sequence;
    next.updated_at = new Date().toISOString();
    await atomicWriteJson(statePath(taskRoot), next);
    await appendAuditEvent(taskRoot, {
      sequence,
      at: next.updated_at,
      event: eventType,
      task_id: next.task_id,
      status: next.status,
      phase: next.phase,
      ...details,
    });
    return next;
  });
}

/** Verifies a read-only exact-state preflight under the task-state lock. */
export async function verifyTaskStateAudit(
  taskRoot: string,
  expected: Pick<TaskState, 'task_id' | 'request_sha256' | 'event_cursor'>,
): Promise<TaskState> {
  return withStateLock(taskRoot, async () => {
    const current = await readValidatedTaskStateBounded(taskRoot);
    if (!current || current.task_id !== expected.task_id
        || current.request_sha256 !== expected.request_sha256
        || current.event_cursor !== expected.event_cursor) {
      throw new Error('exact task state changed during validation');
    }
    await assertLatestAuditEventMatchesState(taskRoot, current);
    return current;
  });
}

/**
 * Creates initial state exactly once after the caller validates the immutable
 * request/input binding under the same state lock. Existing state is read-only.
 */
export async function initializeTaskStateExact(
  taskRoot: string,
  taskId: string,
  requestSha256: string,
  validateBinding: () => Promise<void>,
  readExistingState: () => Promise<TaskState>,
  operation?: {eventType: string; details: JsonObject; existingStateMatches?: (state: TaskState) => Promise<boolean>},
): Promise<{state: TaskState; created: boolean}> {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(taskId)
      || !/^[a-f0-9]{64}$/.test(requestSha256)) {
    throw new Error('exact initialization binding is invalid');
  }
  await validateBinding();
  return withStateLock(taskRoot, async () => {
    await validateBinding();
    let stateExists = false;
    try {
      const info = await lstat(statePath(taskRoot));
      if (!info.isFile() || info.isSymbolicLink()) throw new Error('task state is not a regular file');
      stateExists = true;
    } catch (error: any) {
      if (error?.code !== 'ENOENT') throw error;
    }
    if (stateExists) {
      const current = await readExistingState();
      if (current.task_id !== taskId || current.request_sha256 !== requestSha256) {
        throw new Error('persisted task state does not match exact initialization binding');
      }
      await assertLatestAuditEventMatchesState(taskRoot, current);
      if (operation && (!operation.existingStateMatches
          || !await operation.existingStateMatches(current))) {
        throw new Error('exact operation conflicts with existing state');
      }
      return {state: current, created: false};
    }
    try {
      await lstat(eventPath(taskRoot));
      throw new Error('task event log exists without its state');
    } catch (error: any) {
      if (error?.code !== 'ENOENT') throw error;
    }
    const now = new Date().toISOString();
    const state: TaskState = {
      ...initialTaskState(taskId, requestSha256),
      event_cursor: 1,
      updated_at: now,
    };
    await atomicWriteJson(statePath(taskRoot), state);
    await appendAuditEvent(taskRoot, {
      sequence: 1,
      at: now,
      event: operation?.eventType ?? 'task_initialized_exact',
      task_id: taskId,
      status: state.status,
      phase: state.phase,
      ...(operation?.details ?? {}),
    });
    return {state, created: true};
  });
}

async function assertLatestAuditEventMatchesState(taskRoot: string, state: TaskState): Promise<void> {
  const path = eventPath(taskRoot);
  let bytes: Buffer;
  let start: bigint;
  if (process.platform === 'win32') {
    const result = await readWindowsFileTail(path, MAX_AUDIT_TAIL_BYTES);
    bytes = result.bytes;
    start = BigInt(result.metadata.offset);
    if (BigInt(result.metadata.file_size) <= 0n
        || BigInt(result.metadata.file_size) > BigInt(Number.MAX_SAFE_INTEGER) || bytes.length <= 0) {
      throw new Error('task event log is invalid');
    }
  } else {
    if (typeof constants.O_NONBLOCK !== 'number' || typeof constants.O_NOFOLLOW !== 'number') {
      throw new Error('cannot safely inspect task event log');
    }
    const handle = await open(path, constants.O_RDONLY | constants.O_NONBLOCK | constants.O_NOFOLLOW);
    try {
      const before = await handle.stat({bigint: true});
      if (!before.isFile() || before.size <= 0n || before.size > BigInt(Number.MAX_SAFE_INTEGER)) {
        throw new Error('task event log is invalid');
      }
      const length = Number(before.size > BigInt(MAX_AUDIT_TAIL_BYTES)
        ? BigInt(MAX_AUDIT_TAIL_BYTES) : before.size);
      start = before.size - BigInt(length);
      bytes = Buffer.alloc(length);
      let offset = 0;
      while (offset < length) {
        const result = await handle.read(bytes, offset, length - offset, Number(start) + offset);
        if (result.bytesRead <= 0) throw new Error('task event log changed while being read');
        offset += result.bytesRead;
      }
      const after = await handle.stat({bigint: true});
      if (before.dev !== after.dev || before.ino !== after.ino || before.size !== after.size
          || before.mtimeNs !== after.mtimeNs || before.ctimeNs !== after.ctimeNs) {
        throw new Error('task event log changed while being read');
      }
    } finally {
      await handle.close();
    }
  }
  if (bytes[bytes.length - 1] !== 0x0a) throw new Error('task event log ends with a partial record');
  const previousNewline = bytes.lastIndexOf(0x0a, bytes.length - 2);
  if (previousNewline < 0 && start > 0n) throw new Error('latest task event exceeds the bounded tail');
  const lineStart = previousNewline + 1;
  const lineBytes = bytes.subarray(lineStart, bytes.length - 1);
  if (lineBytes.length === 0 || lineBytes.length > MAX_AUDIT_TAIL_BYTES) {
    throw new Error('latest task event is invalid');
  }
  let event: unknown;
  try {
    event = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(lineBytes));
  } catch {
    throw new Error('latest task event is invalid');
  }
  if (!event || typeof event !== 'object' || Array.isArray(event)) {
    throw new Error('latest task event is invalid');
  }
  const value = event as JsonObject;
  if (value.sequence !== state.event_cursor || value.task_id !== state.task_id
      || value.status !== state.status || value.phase !== state.phase
      || value.at !== state.updated_at) {
    throw new Error('latest task event does not match persisted state');
  }
}

export async function acquireExecutionLease(taskRoot: string): Promise<ExecutionLease> {
  await mkdir(resolve(taskRoot, 'work'), {recursive: true});
  const path = executionLockPath(taskRoot);
  const runtimeRoot = resolve(path, '..');
  await mkdir(runtimeRoot, {recursive: true, mode: 0o700});
  await chmod(runtimeRoot, 0o700);
  for (let attempt = 0; attempt < LOCK_RETRIES; attempt += 1) {
    const acquired = await withExecutionLeaseMutationLock(taskRoot, async () => {
      try {
        await mkdir(path);
        const processIdentity = await readProcessIdentity(process.pid);
        if (!processIdentity) {
          await rm(path, {recursive: true, force: true});
          throw new Error(`cannot establish a strong worker process identity on ${process.platform}`);
        }
        const lease: ExecutionLease = {
          token: randomUUID(),
          pid: process.pid,
          acquired_at: new Date().toISOString(),
          process_identity: processIdentity,
        };
        await atomicWriteJson(resolve(path, 'owner.json'), lease);
        return {lease};
      } catch (error: any) {
        if (error?.code !== 'EEXIST') throw error;
        const owner = await readExecutionLease(taskRoot);
        if (!owner) {
          if (await lockDirectoryPastCreationGrace(path)) await quarantineLockDirectory(path);
          return {};
        }
        if (await executionLeaseIsLive(owner)) return {liveOwner: owner};
        await quarantineLockDirectory(path);
        return {};
      }
    });
    if (acquired.lease) return acquired.lease;
    if (acquired.liveOwner) throw new Error(`task is already running under pid ${acquired.liveOwner.pid}`);
    await new Promise(resolveWait => setTimeout(resolveWait, LOCK_RETRY_MS));
  }
  throw new Error('could not acquire task execution lock');
}

export async function readExecutionLease(taskRoot: string): Promise<ExecutionLease | undefined> {
  try {
    const value = await readJson(resolve(executionLockPath(taskRoot), 'owner.json'));
    if (!value || typeof value.token !== 'string' || !Number.isInteger(value.pid)
        || !validProcessIdentity(value.process_identity)) return undefined;
    return value as ExecutionLease;
  } catch (error: any) {
    if (error?.code === 'ENOENT' || error instanceof SyntaxError) return undefined;
    throw error;
  }
}

export async function releaseExecutionLease(taskRoot: string, lease: ExecutionLease): Promise<void> {
  await withExecutionLeaseMutationLock(taskRoot, async () => {
    const current = await readExecutionLease(taskRoot);
    if (current?.token !== lease.token) return;
    const processIdentity = await readProcessIdentity(process.pid);
    const ownedByThisProcess = current.pid === process.pid && processIdentity != null
      && current.process_identity.platform === processIdentity.platform
      && current.process_identity.boot_id === processIdentity.boot_id
      && current.process_identity.start_time === processIdentity.start_time
      && processControlIdentityMatches(current.process_identity, processIdentity);
    if (!ownedByThisProcess && await executionLeaseIsLive(current)) return;
    await rm(executionLockPath(taskRoot), {recursive: true, force: true});
  });
}

async function withExecutionLeaseMutationLock<T>(taskRoot: string, action: () => Promise<T>): Promise<T> {
  const path = executionLeaseMutationLockPath(taskRoot);
  for (let attempt = 0; attempt < LOCK_RETRIES; attempt += 1) {
    let token = '';
    try {
      await mkdir(path, {mode: 0o700});
      token = randomUUID();
      const processIdentity = await readProcessIdentity(process.pid);
      if (!processIdentity) {
        await rm(path, {recursive: true, force: true});
        throw new Error(`cannot establish execution lease mutation identity on ${process.platform}`);
      }
      await atomicWriteJson(resolve(path, 'owner.json'), {token, pid: process.pid, process_identity: processIdentity});
    } catch (error: any) {
      if (error?.code !== 'EEXIST') throw error;
      try {
        const owner = await readJson(resolve(path, 'owner.json'));
        const identity = validProcessIdentity(owner.process_identity) ? owner.process_identity : undefined;
        const liveIdentity = identity ? await readProcessIdentity(Number(owner.pid)) : undefined;
        const ownerIsLive = liveIdentity != null && liveIdentity.platform === identity?.platform
          && liveIdentity.boot_id === identity?.boot_id && liveIdentity.start_time === identity?.start_time
          && processControlIdentityMatches(liveIdentity, identity!);
        if (!ownerIsLive) throw new Error('execution lease mutation lock is stale');
      } catch (ownerError: any) {
        if (ownerError?.message === 'execution lease mutation lock is stale') throw ownerError;
        if (ownerError?.code === 'ENOENT' || ownerError instanceof SyntaxError) {
          throw new Error('execution lease mutation lock is unavailable');
        }
        throw ownerError;
      }
      await new Promise(resolveWait => setTimeout(resolveWait, LOCK_RETRY_MS));
      continue;
    }
    try {
      return await action();
    } finally {
      try {
        const owner = await readJson(resolve(path, 'owner.json'));
        if (owner.token === token) await rm(path, {recursive: true, force: true});
      } catch {
        // A later lease mutation can recover a stale guard after a process crash.
      }
    }
  }
  throw new Error('execution lease mutation lock is busy');
}

export async function executionLeaseIsLive(lease: ExecutionLease): Promise<boolean> {
  const current = await readProcessIdentity(lease.pid);
  return current != null
    && current.platform === lease.process_identity.platform
    && current.boot_id === lease.process_identity.boot_id
    && current.start_time === lease.process_identity.start_time
    && processControlIdentityMatches(current, lease.process_identity);
}

/** Re-reads OS identity immediately before a signal; caller must not trust task files. */
export async function verifiedExecutionProcessGroup(lease: ExecutionLease): Promise<number | undefined> {
  if (!await executionLeaseIsLive(lease)) return undefined;
  const group = lease.process_identity.process_group_id;
  // Production workers are detached process-group leaders. Refuse a broader group.
  return group === lease.pid ? group : undefined;
}

/** A Windows verified leader PID; use only with a Windows tree-owner mechanism. */
export async function verifiedExecutionProcessId(lease: ExecutionLease): Promise<number | undefined> {
  if (!await executionLeaseIsLive(lease)) return undefined;
  return lease.process_identity.platform === 'win32'
    && lease.process_identity.process_control_id === lease.pid ? lease.pid : undefined;
}

export function pidAlive(pid: number): boolean {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (error: any) {
    return error?.code === 'EPERM';
  }
}

async function readProcessIdentity(pid: number): Promise<ProcessIdentity | undefined> {
  if (!Number.isInteger(pid) || pid <= 0) return undefined;
  try {
    if (process.platform === 'linux') {
      const [bootId, statLine] = await Promise.all([
        readFile('/proc/sys/kernel/random/boot_id', 'utf8'),
        readFile(`/proc/${pid}/stat`, 'utf8'),
      ]);
      const close = statLine.lastIndexOf(')');
      if (close < 0) return undefined;
      const fields = statLine.slice(close + 2).trim().split(/\s+/);
      const processGroupId = Number(fields[2]);
      const startTime = fields[19];
      if (!Number.isInteger(processGroupId) || !startTime) return undefined;
      return {platform: 'linux', boot_id: bootId.trim(), start_time: startTime,
        process_group_id: processGroupId};
    }
    if (process.platform === 'darwin') {
      const [{stdout: boot}, {stdout: processInfo}] = await Promise.all([
        execFileAsync('/usr/sbin/sysctl', ['-n', 'kern.boottime']),
        execFileAsync('/bin/ps', ['-o', 'pgid=,lstart=', '-p', String(pid)]),
      ]);
      const match = /^\s*(\d+)\s+(.+?)\s*$/.exec(processInfo);
      if (!match) return undefined;
      return {platform: 'darwin', boot_id: boot.trim(), start_time: match[2]!,
        process_group_id: Number(match[1])};
    }
    if (process.platform === 'win32') {
      const systemRoot = process.env.SystemRoot ?? 'C:\\Windows';
      const powershell = resolve(systemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe');
      // pid is numeric and validated above, so this fixed query cannot receive
      // task-controlled PowerShell syntax. Windows does not have a process group.
      const script = `$ErrorActionPreference='Stop';$p=Get-CimInstance Win32_Process -Filter 'ProcessId = ${pid}';if($null -eq $p){exit 3};$b=(Get-CimInstance Win32_OperatingSystem).LastBootUpTime;[Console]::WriteLine(('{{"boot_id":"{0}","start_time":"{1}"}}' -f $b.ToUniversalTime().Ticks,$p.CreationDate.ToUniversalTime().Ticks))`;
      const {stdout} = await execFileAsync(powershell, ['-NoLogo', '-NoProfile', '-NonInteractive',
        '-ExecutionPolicy', 'Bypass', '-Command', script], {windowsHide: true, timeout: 3000});
      const value = windowsProcessIdentityFromJson(stdout, pid);
      return value;
    }
    return undefined;
  } catch {
    return undefined;
  }
}

function validProcessIdentity(value: any): value is ProcessIdentity {
  if (!value || typeof value.platform !== 'string' || typeof value.boot_id !== 'string'
      || typeof value.start_time !== 'string') return false;
  if (value.platform === 'linux' || value.platform === 'darwin') {
    return Number.isInteger(value.process_group_id) && value.process_control_id === undefined;
  }
  return value.platform === 'win32' && Number.isInteger(value.process_control_id)
    && value.process_group_id === undefined;
}

function processControlIdentityMatches(left: ProcessIdentity, right: ProcessIdentity): boolean {
  return left.platform === 'win32'
    ? left.process_control_id === right.process_control_id
    : left.process_group_id === right.process_group_id;
}

export function windowsProcessIdentityFromJson(stdout: string, pid: number): ProcessIdentity | undefined {
  try {
    const value = JSON.parse(stdout.trim());
    if (!value || !/^\d{1,20}$/.test(value.boot_id) || !/^\d{1,20}$/.test(value.start_time)) {
      return undefined;
    }
    return {platform: 'win32', boot_id: value.boot_id, start_time: value.start_time,
      process_control_id: pid};
  } catch {
    return undefined;
  }
}

async function withStateLock<T>(taskRoot: string, action: () => Promise<T>): Promise<T> {
  await mkdir(resolve(taskRoot, 'work'), {recursive: true});
  const path = stateLockPath(taskRoot);
  let token = '';
  for (let attempt = 0; attempt < LOCK_RETRIES; attempt += 1) {
    try {
      await mkdir(path);
      token = randomUUID();
      await atomicWriteJson(resolve(path, 'owner.json'), {token, pid: process.pid});
      break;
    } catch (error: any) {
      if (error?.code !== 'EEXIST') throw error;
      try {
        const owner = await readJson(resolve(path, 'owner.json'));
        if (!pidAlive(Number(owner.pid))) {
          await quarantineLockDirectory(path);
          continue;
        }
      } catch (ownerError: any) {
        if (ownerError?.code === 'ENOENT' || ownerError instanceof SyntaxError) {
          if (await lockDirectoryPastCreationGrace(path)) {
            await quarantineLockDirectory(path);
            continue;
          }
        } else {
          throw ownerError;
        }
      }
      await new Promise(resolveWait => setTimeout(resolveWait, LOCK_RETRY_MS));
    }
  }
  if (!token) throw new Error('task state is busy');
  try {
    return await action();
  } finally {
    try {
      const owner = await readJson(resolve(path, 'owner.json'));
      if (owner.token === token) await rm(path, {recursive: true, force: true});
    } catch {
      // A later caller can recover a stale state lock after process death.
    }
  }
}

async function lockDirectoryPastCreationGrace(path: string): Promise<boolean> {
  try {
    const info = await lstat(path);
    return Date.now() - info.mtimeMs >= LOCK_CREATION_GRACE_MS;
  } catch (error: any) {
    if (error?.code === 'ENOENT') return false;
    throw error;
  }
}

async function quarantineLockDirectory(path: string): Promise<void> {
  const stale = `${path}.stale-${randomUUID()}`;
  try {
    await rename(path, stale);
    await rm(stale, {recursive: true, force: true});
  } catch (error: any) {
    if (error?.code !== 'ENOENT') throw error;
  }
}

async function appendAuditEvent(taskRoot: string, event: JsonObject): Promise<void> {
  const path = eventPath(taskRoot);
  await mkdir(resolve(taskRoot, 'work'), {recursive: true});
  const handle = await open(path, 'a', 0o600);
  try {
    await handle.writeFile(`${JSON.stringify(event)}\n`);
    await handle.sync();
  } finally {
    await handle.close();
  }
}
