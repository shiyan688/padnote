import assert from 'node:assert/strict';
import {test} from 'node:test';
import {
  diagnoseCliArguments,
  diagnoseRuntime,
  executableStatIsUsable,
  type RuntimeResolver,
} from '../scripts/diagnose-runtime.js';

function resolver(overrides: Partial<RuntimeResolver> = {}): RuntimeResolver {
  return {
    resolveModule: () => undefined,
    renderBrowserPath: async () => '/private/render/chrome',
    storyboardBrowserPath: async () => '/private/storyboard/chrome-headless-shell',
    packagedExecutablePath: name => `/private/${name.includes('ffprobe') ? 'ffprobe' : 'ffmpeg'}`,
    executableExists: async () => true,
    ...overrides,
  };
}

test('uses Windows executable-file semantics without weakening POSIX execute-bit checks', () => {
  const windowsExe = {
    mode: 0o100666, nlink: 1, isFile: () => true, isSymbolicLink: () => false,
  };
  assert.equal(executableStatIsUsable('C:\\Program Files\\PadNote\\node.exe', windowsExe, 'win32'), true);
  assert.equal(executableStatIsUsable('C:\\Program Files\\PadNote\\node.cmd', windowsExe, 'win32'), false);
  assert.equal(executableStatIsUsable('relative\\node.exe', windowsExe, 'win32'), false);
  assert.equal(executableStatIsUsable('C:\\Program Files\\PadNote\\node.exe',
    {...windowsExe, isSymbolicLink: () => true}, 'win32'), false);
  assert.equal(executableStatIsUsable('C:\\Program Files\\PadNote\\node.exe',
    {...windowsExe, nlink: 2}, 'win32'), false);
  assert.equal(executableStatIsUsable('/private/bin/ffmpeg',
    {...windowsExe, mode: 0o100755}, 'darwin'), true);
  assert.equal(executableStatIsUsable('/private/bin/ffmpeg',
    {...windowsExe, mode: 0o100644}, 'darwin'), false);
});

test('resolves the two Puppeteer executable modes independently without claiming runtime readiness', async () => {
  const seen: string[] = [];
  const diagnostic = await diagnoseRuntime(resolver({
    renderBrowserPath: async () => { seen.push('render'); return '/private/render/chrome'; },
    storyboardBrowserPath: async () => { seen.push('storyboard'); return '/private/storyboard/headless-shell'; },
    executableExists: async path => path.startsWith('/private/'),
  }));
  assert.deepEqual(seen, ['render', 'storyboard']);
  assert.equal(diagnostic.checks.render_browser.reason, 'installed_executable_found');
  assert.equal(diagnostic.checks.storyboard_browser.reason, 'installed_executable_found');
  assert.equal(diagnostic.runtime_verified, false);
  assert.equal(diagnostic.video_ready, false);
  assert.equal(JSON.stringify(diagnostic).includes('/private/'), false);
});

test('reports module, executable, and Puppeteer configuration failures with fixed reasons', async () => {
  const diagnostic = await diagnoseRuntime(resolver({
    resolveModule: name => { if (name === 'tsx') throw new Error('/secret/path'); },
    renderBrowserPath: async () => { throw new Error('/private/config'); },
    storyboardBrowserPath: async () => '/missing/shell',
    executableExists: async path => path === '/private/ffmpeg' || path === '/private/ffprobe',
  }));
  assert.deepEqual(diagnostic.checks.worker_modules, {status: 'missing', reason: 'module_missing'});
  assert.deepEqual(diagnostic.checks.render_browser, {status: 'unchecked', reason: 'configuration_unavailable'});
  assert.deepEqual(diagnostic.checks.storyboard_browser, {status: 'missing', reason: 'executable_missing'});
  assert.equal(JSON.stringify(diagnostic).includes('/private/'), false);
  assert.equal(JSON.stringify(diagnostic).includes('secret'), false);
});

test('does not inspect host TTS variables or credentials', async () => {
  const previousCommand = process.env.PADNOTE_TTS_COMMAND;
  const previousKey = process.env.DASHSCOPE_API_KEY;
  process.env.PADNOTE_TTS_COMMAND = '/private/provider';
  process.env.DASHSCOPE_API_KEY = 'secret-test-value';
  try {
    const diagnostic = await diagnoseRuntime(resolver());
    assert.deepEqual(diagnostic.checks.tts, {status: 'not_configured', reason: 'adapter_not_configured'});
    assert.equal(JSON.stringify(diagnostic).includes('/private/provider'), false);
    assert.equal(JSON.stringify(diagnostic).includes('secret-test-value'), false);
  } finally {
    if (previousCommand === undefined) delete process.env.PADNOTE_TTS_COMMAND;
    else process.env.PADNOTE_TTS_COMMAND = previousCommand;
    if (previousKey === undefined) delete process.env.DASHSCOPE_API_KEY;
    else process.env.DASHSCOPE_API_KEY = previousKey;
  }
});

test('rejects all CLI arguments and returns only a fixed non-ready schema', async () => {
  let resolverCalled = false;
  const diagnostic = await diagnoseCliArguments(['/tmp/task'], resolver({
    resolveModule: () => { resolverCalled = true; },
  }));
  assert.equal(resolverCalled, false);
  assert.equal(diagnostic.schema_version, '1.0');
  assert.equal(diagnostic.runtime_verified, false);
  assert.equal(diagnostic.video_ready, false);
  assert.deepEqual(diagnostic.checks.storyboard_browser, {
    status: 'unchecked', reason: 'configuration_unavailable',
  });
  assert.equal(JSON.stringify(diagnostic).includes('/tmp/task'), false);
});
