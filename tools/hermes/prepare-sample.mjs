import {readFile, mkdir, writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '.runtime/tasks/percentages');
const content = await readFile(resolve(here, 'sample-note.md'));
const digest = createHash('sha256').update(content).digest('hex');
const manifest = {schema_version: '1.0', files: [{path: 'input/content.md',
  media_type: 'text/markdown', size_bytes: content.length, sha256: digest}]};
const bundle = createHash('sha256').update(`input/content.md\0${content.length}\0${digest}\n`).digest('hex');
const request = {
  schema_version: '1.0', task_type: 'video.explain.v1', task_id: 'task-hermes-percentages',
  source: {note_id: 'sample-percentages', note_revision: 1,
    title: '为什么打八折再涨两成，回不到原价？', language: 'zh-CN',
    entrypoint: 'input/content.md', bundle_sha256: bundle},
  brief: {audience: '已学百分数的初中生', learning_goal: '理解连续百分比变化的基数不同，并能计算恢复原价的涨幅',
    prerequisites: ['百分数', '四则运算'], target_duration_sec: 60,
    aspect_ratio: '9:16', style_preset: 'clean-academic'},
  research: {external_research: false}, review: {storyboard_required: true},
  voice: {profile: 'default-zh', speed: 1.0},
};
await mkdir(resolve(root, 'input'), {recursive: true});
await mkdir(resolve(root, 'output'), {recursive: true});
await writeFile(resolve(root, 'input/content.md'), content, {flag: 'wx'});
await writeFile(resolve(root, 'input/manifest.json'), JSON.stringify(manifest, null, 2), {flag: 'wx'});
await writeFile(resolve(root, 'request.json'), JSON.stringify(request, null, 2), {flag: 'wx'});
console.log(root);
