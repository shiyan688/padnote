import assert from 'node:assert/strict';
import {mkdir, mkdtemp, realpath, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {after, test} from 'node:test';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {closeWindowsReader, readWindowsFileTail, readWindowsRegularFile} from '../scripts/windows-reader.js';

const fixture = fileURLToPath(new URL('./fixtures/fake_windows_reader.py', import.meta.url));
const root = await mkdtemp(join(tmpdir(), 'padnote-windows-reader-'));
const old = new Map<string, string | undefined>();
for (const key of ['PADNOTE_WINDOWS_READER_PYTHON', 'PADNOTE_WINDOWS_READER_SCRIPT', 'PADNOTE_WINDOWS_READER_ROOT']) {
  old.set(key, process.env[key]);
}

test('the private reader IPC is bounded, bound to its root, and fails closed', async t => {
  const py = process.env.PADNOTE_TEST_PYTHON;
  if (!py) {
    t.skip('set PADNOTE_TEST_PYTHON to an absolute Python executable to run the Windows reader IPC fixture');
    return;
  }
  const trustedPython = await realpath(py);
  const task = join(root, 'task');
  await mkdir(task, {recursive: true});
  const file = join(task, 'sample.bin');
  await writeFile(file, Buffer.from('0123456789abcdef'));
  await writeFile(join(task, 'bad-response.fixture'), Buffer.from('x'));
  for (const name of ['wrong-id.fixture', 'oversize.fixture', 'timeout.fixture', 'exit.fixture',
    'delayed-extra.fixture', 'hold-open.fixture', 'queue-close.fixture']) {
    await writeFile(join(task, name), Buffer.from('x'));
  }
  process.env.PADNOTE_WINDOWS_READER_PYTHON = trustedPython;
  process.env.PADNOTE_WINDOWS_READER_SCRIPT = resolve(fixture);
  process.env.PADNOTE_WINDOWS_READER_ROOT = resolve(root);

  const whole = await readWindowsRegularFile(file, 32);
  assert.equal(whole.bytes.toString(), '0123456789abcdef');
  assert.deepEqual(whole.metadata, {file_size:'16',offset:'0',dev:'1',ino:'2',mtime_ns:'3',ctime_ns:'4'});
  const tail = await readWindowsFileTail(file, 6);
  assert.equal(tail.bytes.toString(), 'abcdef');
  assert.equal(tail.metadata.offset, '10');
  await assert.rejects(readWindowsRegularFile(join(task, 'missing.bin'), 10), error =>
    error instanceof Error && (error as Error & {code?: string}).code === 'ENOENT');
  // A bounded, allowlisted file error is a known read result, so the same helper remains usable.
  assert.equal((await readWindowsRegularFile(file, 32)).bytes.toString(), '0123456789abcdef');
  await assert.rejects(readWindowsRegularFile(join(root, '..', 'outside'), 10), /outside the trusted task root/);
  await assert.rejects(readWindowsRegularFile(join(task, 'bad-response.fixture'), 10), /invalid response/);
  await assert.rejects(readWindowsRegularFile(file, 10), /unavailable/);
  await closeWindowsReader();

  for (const name of ['wrong-id.fixture', 'oversize.fixture', 'timeout.fixture', 'exit.fixture',
    'delayed-extra.fixture', 'hold-open.fixture', 'queue-close.fixture']) {
    const result = await runPoisonedHelperCase(trustedPython, root, name);
    assert.equal(result.code, 0, `${name}: ${result.stderr}`);
    assert.equal(result.stdout, 'closed\n');
    assert.equal(result.stderr, '');
  }
});

function runPoisonedHelperCase(python: string, readerRoot: string, filename: string): Promise<{
  code: number | null; stdout: string; stderr: string;
}> {
  const moduleUrl = new URL('../scripts/windows-reader.js', import.meta.url).href;
  const childSource = `
    const api = await import(process.env.PADNOTE_TEST_READER_MODULE);
    const target = process.env.PADNOTE_TEST_READER_TARGET;
    if (target.endsWith('exit.fixture')) {
      const path = await import('node:path');
      const sample = path.join(path.dirname(target), 'sample.bin');
      await api.readWindowsRegularFile(sample, 32);
      const requests = [target, sample, sample, sample].map(item => api.readWindowsRegularFile(item, 32));
      const result = await Promise.race([
        Promise.allSettled(requests),
        new Promise(resolve => setTimeout(() => resolve(undefined), 1000)),
      ]);
      if (!result || result.some(item => item.status !== 'rejected')) process.exitCode = 8;
      await api.closeWindowsReader();
      console.log('closed');
    } else if (target.endsWith('queue-close.fixture')) {
      const started = Date.now();
      const calls = Array.from({length: 100}, () => api.readWindowsRegularFile(target, 10).catch(() => undefined));
      await api.closeWindowsReader();
      await Promise.all(calls);
      if (Date.now() - started > 1500) process.exitCode = 7;
      console.log('closed');
    } else if (target.endsWith('delayed-extra.fixture')) {
      try { await api.readWindowsRegularFile(target, 10); } catch { process.exitCode = 2; }
      await new Promise(resolve => setTimeout(resolve, 300));
      try { await api.readWindowsRegularFile(target, 10); process.exitCode = 3; } catch { /* poisoned idle stream */ }
      await api.closeWindowsReader();
      console.log('closed');
    } else if (target.endsWith('hold-open.fixture')) {
      const cp = await import('node:child_process');
      const prototype = cp.ChildProcess.prototype;
      const originalKill = prototype.kill;
      prototype.kill = function() { return true; };
      try {
        await api.readWindowsRegularFile(target, 10);
        try { await api.closeWindowsReader(); process.exitCode = 4; } catch { /* expected bounded close failure */ }
        try { await api.readWindowsRegularFile(target, 10); process.exitCode = 5; } catch { /* retained closing state */ }
      } finally { prototype.kill = originalKill; }
      const retryStarted = Date.now();
      await api.closeWindowsReader();
      if (Date.now() - retryStarted > 1500) process.exitCode = 6;
      console.log('closed');
    } else {
      try { await api.readWindowsRegularFile(target, 10); process.exitCode = 2; }
      catch { /* expected transport/protocol failure */ }
      try { await api.closeWindowsReader(); console.log('closed'); }
      catch { process.exitCode = 3; }
    }
  `;
  const env: NodeJS.ProcessEnv = {
    PATH: process.env.PATH,
    ...(process.env.SystemRoot ? {SystemRoot: process.env.SystemRoot} : {}),
    ...(process.env.WINDIR ? {WINDIR: process.env.WINDIR} : {}),
    ...(process.env.TEMP ? {TEMP: process.env.TEMP} : {}),
    ...(process.env.TMP ? {TMP: process.env.TMP} : {}),
    PADNOTE_WINDOWS_READER_PYTHON: python,
    PADNOTE_WINDOWS_READER_SCRIPT: resolve(fixture),
    PADNOTE_WINDOWS_READER_ROOT: readerRoot,
    PADNOTE_TEST_READER_MODULE: moduleUrl,
    PADNOTE_TEST_READER_TARGET: join(readerRoot, 'task', filename),
  };
  return new Promise(resolveResult => {
    const child = spawn(process.execPath, ['--import', 'tsx', '--input-type=module', '-e', childSource], {
      cwd: process.cwd(), env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
    });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8').on('data', chunk => { stdout += chunk; });
    child.stderr.setEncoding('utf8').on('data', chunk => { stderr += chunk; });
    child.once('close', code => resolveResult({code, stdout, stderr}));
  });
}

after(async () => {
  await closeWindowsReader().catch(() => undefined);
  for (const [key, value] of old) {
    if (value === undefined) delete process.env[key]; else process.env[key] = value;
  }
  await rm(root, {recursive: true, force: true});
});
