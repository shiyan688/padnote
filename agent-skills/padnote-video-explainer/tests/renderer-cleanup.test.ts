import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdtemp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import vm from 'node:vm';
import {applyRendererPatch} from '../scripts/apply-revideo-renderer-patch.mjs';
import patch from '../patches/revideo-renderer-0.11.0.json' with {type: 'json'};

const skill = path.resolve(import.meta.dirname, '..');
const rendererRelative = 'node_modules/@revideo/renderer/lib/server/render-video.js';
const hash = (data: Buffer | string) => createHash('sha256').update(data).digest('hex');

function replaceOnce(source: string, before: string, after: string): string {
  const index = source.indexOf(before);
  assert.notEqual(index, -1);
  assert.equal(source.indexOf(before, index + before.length), -1);
  return source.slice(0, index) + after + source.slice(index + before.length);
}

test('renderer patch accepts only pinned upstream bytes, is idempotent, and rejects drift', async t => {
  const installed = await readFile(path.join(skill, rendererRelative), 'utf8');
  let upstream = installed;
  for (const replacement of [...patch.replacements].reverse()) {
    upstream = replaceOnce(upstream, replacement.patched, replacement.original);
  }
  assert.equal(hash(upstream), patch.original_sha256);
  const root = await mkdtemp(path.join(os.tmpdir(), 'padnote-renderer-patch-'));
  t.after(() => rm(root, {recursive: true, force: true}));
  const target = path.join(root, rendererRelative);
  await mkdir(path.dirname(target), {recursive: true});
  await writeFile(path.join(root, 'node_modules/@revideo/renderer/package.json'), JSON.stringify({version: patch.version}));
  await writeFile(target, upstream);
  assert.equal(await applyRendererPatch(root), 'patched');
  assert.equal(hash(await readFile(target)), patch.patched_sha256);
  assert.equal(await applyRendererPatch(root), 'already-patched');
  await writeFile(target, Buffer.concat([await readFile(target), Buffer.from('\n// unexpected') ]));
  await assert.rejects(applyRendererPatch(root), /unknown @revideo\/renderer source bytes/);
  await writeFile(path.join(root, 'node_modules/@revideo/renderer/package.json'), JSON.stringify({version: '0.11.1'}));
  await assert.rejects(applyRendererPatch(root), /unsupported @revideo\/renderer version/);
});

function loadRenderer(mode: 'goto-error' | 'browser-error' | 'render-error' | 'success' | 'launch-error' | 'listen-error' | 'expose-error' | 'new-page-error' | 'disconnect-error' | 'page-error', closeError = false, platform = 'win32') {
  const source = awaitableReadRenderer();
  return source.then(code => {
    let intervalCount = 0, browserCloseCount = 0, serverCloseCount = 0;
    let launchArgs: string[] = [];
    const exposed: Record<string, (...args: any[]) => Promise<void> | void> = {};
    const browserEvents: Record<string, (...args: any[]) => void> = {};
    const pageEvents: Record<string, (...args: any[]) => void> = {};
    const page: any = {
      on(name: string, callback: (...args: any[]) => void) { pageEvents[name] = callback; },
      async exposeFunction(name: string, callback: (...args: any[]) => Promise<void> | void) {
        if (mode === 'expose-error' && name === 'onRenderComplete') throw new Error('expose failure sentinel');
        exposed[name] = callback;
      },
      async goto() {
        if (mode === 'goto-error') throw new Error('goto failure sentinel');
        if (mode === 'browser-error') await exposed.browserError!('browser failure sentinel');
        else if (mode === 'render-error') await exposed.onRenderFailed!('render failure sentinel');
        else if (mode === 'disconnect-error') setImmediate(() => browserEvents.disconnected!());
        else if (mode === 'page-error') setImmediate(() => pageEvents.error!(new Error('page failure sentinel')));
        else await exposed.onRenderComplete!();
      },
    };
    const browser: any = {
      async newPage() { if (mode === 'new-page-error') throw new Error('new-page failure sentinel'); return page; },
      on(name: string, callback: (...args: any[]) => void) { browserEvents[name] = callback; },
      async close() {
        browserCloseCount += 1;
        browserEvents.disconnected?.();
        pageEvents.close?.();
        if (closeError) throw new Error('close failure sentinel');
      },
    };
    const server: any = {
      httpServer: {address: () => ({port: 9999})},
      async listen() { if (mode === 'listen-error') throw new Error('listen failure sentinel'); },
      async close() { serverCloseCount += 1; if (closeError) throw new Error('close failure sentinel'); },
    };
    const modules: Record<string, any> = {
      '@revideo/ffmpeg': {extensions: {mp4: 'mp4'}, audioCodecs: {}, doesFileExist: async () => true,
        getVideoDuration: async () => 1, concatenateMedia: async () => {}, mergeAudioWithVideo: async () => {}},
      '@revideo/telemetry': {sendEvent() {}, EventName: {RenderStarted: 'render-started'}},
      '@revideo/vite-plugin': {__esModule: true, default: () => ({})},
      fs: {promises: {rm: async () => {}, unlink: async () => {}}, existsSync: () => true},
      os: {tmpdir: () => '/tmp'}, path,
      puppeteer: {launch: async (options: {args: string[]}) => { launchArgs = options.args; if (mode === 'launch-error') throw new Error('launch failure sentinel'); return browser; }},
      vite: {createServer: async () => server},
      './renderer-plugin': {rendererPlugin: () => ({})},
      './validate-settings': {getParamDefaultsAndCheckValidity: () => ({outputFileName: 'out', outputFolderName: 'out', numOfWorkers: 1, hiddenFolderId: 'id', format: 'mp4'})},
    };
    const exports: Record<string, any> = {};
    const context: any = {
      exports, require: (name: string) => { if (!(name in modules)) throw new Error(`unexpected module: ${name}`); return modules[name]; },
      process: {cwd: () => '/tmp', platform}, console: {log() {}, error() {}}, Buffer, Promise, Map,
      setInterval: () => ++intervalCount, clearInterval: () => { intervalCount -= 1; },
      setImmediate,
    };
    vm.runInNewContext(code, context, {filename: 'revideo-render-video.js'});
    return {renderVideo: exports.renderVideo, counts: () => ({intervalCount, browserCloseCount, serverCloseCount}), launchArgs: () => launchArgs};
  });
}

async function awaitableReadRenderer(): Promise<string> {
  return readFile(path.join(skill, rendererRelative), 'utf8');
}

const settings = {outFile: 'out.mp4', outDir: 'out', workers: 1, puppeteer: {}, viteConfig: {}, projectSettings: {}};

test('render success still resolves and closes renderer resources once', async () => {
  const renderer = await loadRenderer('success');
  await renderer.renderVideo({projectFile: 'project.ts', variables: {}, settings});
  assert.deepEqual(renderer.counts(), {intervalCount: 0, browserCloseCount: 1, serverCloseCount: 1});
});

test('Windows keeps Revideo Chromium multi-process defaults', async () => {
  const renderer = await loadRenderer('success', false, 'win32');
  await renderer.renderVideo({projectFile: 'project.ts', variables: {}, settings});
  assert.equal(renderer.launchArgs().includes('--single-process'), false);
});

test('non-Windows retains the pinned renderer single-process behavior', async () => {
  const renderer = await loadRenderer('success', false, 'darwin');
  await renderer.renderVideo({projectFile: 'project.ts', variables: {}, settings});
  assert.equal(renderer.launchArgs().includes('--single-process'), true);
});

for (const mode of ['goto-error', 'browser-error', 'render-error'] as const) {
  test(`${mode} propagates failure and closes renderer resources once`, async () => {
    const renderer = await loadRenderer(mode);
    await assert.rejects(renderer.renderVideo({projectFile: 'project.ts', variables: {}, settings}), /failure sentinel/);
    assert.deepEqual(renderer.counts(), {intervalCount: 0, browserCloseCount: 1, serverCloseCount: 1});
  });
}

for (const mode of ['disconnect-error', 'page-error'] as const) {
  test(`${mode} after successful navigation rejects and closes both resources`, async () => {
    const renderer = await loadRenderer(mode);
    await assert.rejects(renderer.renderVideo({projectFile: 'project.ts', variables: {}, settings}),
      mode === 'disconnect-error' ? /Renderer browser disconnected/ : /page failure sentinel/);
    assert.deepEqual(renderer.counts(), {intervalCount: 0, browserCloseCount: 1, serverCloseCount: 1});
  });
}

test('partial initialization closes the server when Chromium launch fails', async () => {
  const renderer = await loadRenderer('launch-error');
  await assert.rejects(renderer.renderVideo({projectFile: 'project.ts', variables: {}, settings}), /launch failure sentinel/);
  assert.deepEqual(renderer.counts(), {intervalCount: 0, browserCloseCount: 0, serverCloseCount: 1});
});

test('partial initialization closes the server when Vite listen fails', async () => {
  const renderer = await loadRenderer('listen-error');
  await assert.rejects(renderer.renderVideo({projectFile: 'project.ts', variables: {}, settings}), /listen failure sentinel/);
  assert.deepEqual(renderer.counts(), {intervalCount: 0, browserCloseCount: 1, serverCloseCount: 1});
});

for (const mode of ['new-page-error', 'expose-error'] as const) {
  test(`${mode} clears progress and closes both resources`, async () => {
    const renderer = await loadRenderer(mode);
    await assert.rejects(renderer.renderVideo({projectFile: 'project.ts', variables: {}, settings}), /failure sentinel/);
    assert.deepEqual(renderer.counts(), {intervalCount: 0, browserCloseCount: 1, serverCloseCount: 1});
  });
}

test('cleanup failures do not replace the original render failure', async () => {
  const renderer = await loadRenderer('goto-error', true);
  await assert.rejects(renderer.renderVideo({projectFile: 'project.ts', variables: {}, settings}), /goto failure sentinel/);
  assert.deepEqual(renderer.counts(), {intervalCount: 0, browserCloseCount: 1, serverCloseCount: 1});
});
