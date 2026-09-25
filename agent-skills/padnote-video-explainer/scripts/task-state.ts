import {execFile} from 'node:child_process';
import {createHash, randomUUID} from 'node:crypto';
import {realpathSync} from 'node:fs';
import {appendFile, chmod, lstat, mkdir, open, readFile, rename, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve} from 'node:path';
import {promisify} from 'node:util';
import {atomicWriteJson, readJson, type JsonObject} from './lib.js';

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
  process_group_id: number;
}

const LOCK_RETRIES = 500;
const LOCK_RETRY_MS = 10;
const LOCK_CREATION_GRACE_MS = 2_000;
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

export async function acquireExecutionLease(taskRoot: string): Promise<ExecutionLease> {
  await mkdir(resolve(taskRoot, 'work'), {recursive: true});
  const path = executionLockPath(taskRoot);
  const runtimeRoot = resolve(path, '..');
  await mkdir(runtimeRoot, {recursive: true, mode: 0o700});
  await chmod(runtimeRoot, 0o700);
  for (let attempt = 0; attempt < LOCK_RETRIES; attempt += 1) {
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
      return lease;
    } catch (error: any) {
      if (error?.code !== 'EEXIST') throw error;
      const owner = await readExecutionLease(taskRoot);
      if (!owner) {
        if (await lockDirectoryPastCreationGrace(path)) {
          await quarantineLockDirectory(path);
          continue;
        }
        await new Promise(resolveWait => setTimeout(resolveWait, LOCK_RETRY_MS));
        continue;
      }
      if (owner && await executionLeaseIsLive(owner)) {
        throw new Error(`task is already running under pid ${owner.pid}`);
      }
      await quarantineLockDirectory(path);
    }
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
  const current = await readExecutionLease(taskRoot);
  if (current?.token !== lease.token) return;
  await rm(executionLockPath(taskRoot), {recursive: true, force: true});
}

export async function executionLeaseIsLive(lease: ExecutionLease): Promise<boolean> {
  const current = await readProcessIdentity(lease.pid);
  return current != null
    && current.platform === lease.process_identity.platform
    && current.boot_id === lease.process_identity.boot_id
    && current.start_time === lease.process_identity.start_time
    && current.process_group_id === lease.process_identity.process_group_id;
}

/** Re-reads OS identity immediately before a signal; caller must not trust task files. */
export async function verifiedExecutionProcessGroup(lease: ExecutionLease): Promise<number | undefined> {
  if (!await executionLeaseIsLive(lease)) return undefined;
  const group = lease.process_identity.process_group_id;
  // Production workers are detached process-group leaders. Refuse a broader group.
  return group === lease.pid ? group : undefined;
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
    return undefined;
  } catch {
    return undefined;
  }
}

function validProcessIdentity(value: any): value is ProcessIdentity {
  return value && typeof value.platform === 'string' && typeof value.boot_id === 'string'
    && typeof value.start_time === 'string' && Number.isInteger(value.process_group_id);
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
