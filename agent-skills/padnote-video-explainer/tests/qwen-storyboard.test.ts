import {mkdtemp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve} from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import {generateQwenStoryboard} from '../scripts/qwen-storyboard.js';

const fixture = resolve(import.meta.dirname, 'fixtures/formula-note');

test('Qwen adapter sends one schema constrained chat completion and validates the local IR', async () => {
  const taskRoot = await makeTaskRoot();
  try {
    const expected = JSON.parse(await readFile(resolve(fixture, 'expected/lesson.ir.json'), 'utf8'));
    let calls = 0;
    let sentBody: any;
    const response = await generateQwenStoryboard(taskRoot, {
      apiKey: 'fake-provider-key', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      model: 'qwen-plus', responseMode: 'json_object',
      fetchImpl: async (_input, init) => {
        calls += 1;
        sentBody = JSON.parse(String(init?.body));
        const userPrompt = sentBody.messages.find((message: any) => message.role === 'user').content;
        assert.match(userPrompt, /AUTHORING OUTPUT JSON SCHEMA/);
        assert.match(userPrompt, /"claims"/);
        assert.match(userPrompt, /L1 收敛/);
        assert.match(userPrompt, /input\/content\.md/);
        assert.match(userPrompt, /721c0492bde5d85d6a6ebeef72d36f5838535535796f8ddafa779b4d6cec0d8e/);
        return Response.json({choices: [{finish_reason: 'stop', message: {content: JSON.stringify({
          claims: expected.claims, scenes: expected.scenes,
        })}}]});
      },
    });
    assert.equal(calls, 1);
    assert.equal(sentBody.model, 'qwen-plus');
    assert.deepEqual(sentBody.response_format, {type: 'json_object'});
    assert.equal(sentBody.enable_thinking, false);
    assert.equal(JSON.stringify(sentBody).includes('fake-provider-key'), false);
    assert.equal(response.externalEffectPossible, true);
    assert.equal(response.ir.source.bundle_sha256, expected.source.bundle_sha256);
    assert.deepEqual(Object.keys(response.ir.source).sort(), ['bundle_sha256', 'note_id', 'note_revision']);
    assert.deepEqual(response.ir.render_profile, {aspect_ratio: '9:16', width: 1080, height: 1920, fps: 30});
  } finally { await rm(taskRoot, {recursive: true, force: true}); }
});

test('Qwen adapter does not retry an uncertain provider failure', async () => {
  const taskRoot = await makeTaskRoot();
  try {
    let calls = 0;
    await assert.rejects(generateQwenStoryboard(taskRoot, {
      apiKey: 'fake-provider-key', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      model: 'qwen-plus', responseMode: 'json_object',
      fetchImpl: async () => { calls += 1; throw new Error('connection closed'); },
    }), (error: Error & {externalEffectPossible?: boolean}) => {
      assert.equal(error.externalEffectPossible, true);
      return true;
    });
    assert.equal(calls, 1);
  } finally { await rm(taskRoot, {recursive: true, force: true}); }
});

test('Qwen schema mode rejects a model outside Alibaba supported series before fetch', async () => {
  const taskRoot = await makeTaskRoot();
  try {
    let calls = 0;
    await assert.rejects(generateQwenStoryboard(taskRoot, {
      apiKey: 'fake-provider-key', baseUrl: 'https://workspace.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1',
      model: 'qwen-plus', responseMode: 'json_schema', region: 'ap-southeast-1',
      fetchImpl: async () => { calls += 1; return Response.json({}); },
    }), /model listed by Alibaba as supported/);
    assert.equal(calls, 0);
  } finally { await rm(taskRoot, {recursive: true, force: true}); }
});

async function makeTaskRoot(): Promise<string> {
  const root = await mkdtemp(resolve(tmpdir(), 'padnote-qwen-test-'));
  await mkdir(resolve(root, 'input/assets'), {recursive: true});
  await writeFile(resolve(root, 'request.json'), await readFile(resolve(fixture, 'request.json')));
  await writeFile(resolve(root, 'input/content.md'), await readFile(resolve(fixture, 'input/content.md')));
  await writeFile(resolve(root, 'input/manifest.json'), await readFile(resolve(fixture, 'input/manifest.json')));
  return root;
}
