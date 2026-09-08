import {readFile} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import katex from 'katex';
import puppeteer from 'puppeteer';
import {
  atomicWriteFile,
  atomicWriteJson,
  fileDescriptor,
  requireCliTaskRoot,
  safeRelativePath,
  validateSchema,
  type JsonObject,
} from './lib.js';
import {validateIr} from './validate-ir.js';
import {validateReview} from './validate-review.js';
import {validateRequest} from './validate-request.js';

export async function buildStoryboard(taskRoot: string, lessonIrRevision = 1): Promise<JsonObject> {
  if (!Number.isInteger(lessonIrRevision) || lessonIrRevision < 1) throw new Error('lesson_ir_revision must be a positive integer');
  const request = await validateRequest(taskRoot);
  const ir = await validateIr(taskRoot);
  const output = resolve(taskRoot, 'output');
  const htmlPath = resolve(output, 'storyboard.html');
  const html = await buildHtml(taskRoot, ir);
  await atomicWriteFile(htmlPath, html);

  const browser = await puppeteer.launch({
    headless: 'shell',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--no-zygote', '--single-process', '--disable-dev-shm-usage', '--disable-background-networking'],
  });
  try {
    const page = await browser.newPage();
    await page.setRequestInterception(true);
    page.on('request', request => {
      const url = request.url();
      if (url.startsWith('http://') || url.startsWith('https://')) request.abort();
      else request.continue();
    });
    await page.setViewport({width: 1080, height: 1920, deviceScaleFactor: 1});
    await page.goto(`file://${htmlPath}`, {waitUntil: 'networkidle0'});
    const checks = await page.evaluate(() => {
      return [...document.querySelectorAll<HTMLElement>('.scene')].map(scene => {
        const outer = scene.getBoundingClientRect();
        const safe = scene.querySelector<HTMLElement>('.safe-content')!.getBoundingClientRect();
        const overflows = [...scene.querySelectorAll<HTMLElement>('[data-check]')]
          .filter(node => node.scrollWidth > node.clientWidth + 1 || node.scrollHeight > node.clientHeight + 1)
          .map(node => ({label: node.dataset.check, scrollWidth: node.scrollWidth, clientWidth: node.clientWidth, scrollHeight: node.scrollHeight, clientHeight: node.clientHeight}));
        return {
          id: scene.dataset.sceneId,
          safe: safe.left - outer.left >= 72 && outer.right - safe.right >= 72 && safe.top - outer.top >= 120 && outer.bottom - safe.bottom >= 120,
          overflows,
        };
      });
    });
    for (const check of checks) {
      if (!check.safe || check.overflows.length > 0) throw new Error(`storyboard layout failed for ${check.id}: ${JSON.stringify(check)}`);
    }
    for (const scene of ir.scenes as JsonObject[]) {
      const element = await page.$(`[data-scene-id="${scene.id}"]`);
      if (!element) throw new Error(`storyboard element missing: ${scene.id}`);
      await element.screenshot({path: resolve(output, `storyboard-${scene.id}.png`)});
    }
  } finally {
    await browser.close();
  }

  const lessonPath = resolve(output, 'lesson.ir.json');
  const artifacts: JsonObject[] = [
    {...await fileDescriptor(htmlPath, 'storyboard.html', 'text/html'), role: 'storyboard'},
  ];
  for (const scene of ir.scenes as JsonObject[]) {
    const name = `storyboard-${scene.id}.png`;
    artifacts.push({...await fileDescriptor(resolve(output, name), name, 'image/png'), role: 'storyboard'});
  }
  const review = {
    schema_version: '1.0',
    task_id: request.task_id,
    status: 'awaiting_storyboard_review',
    lesson_ir_revision: lessonIrRevision,
    lesson_ir: await fileDescriptor(lessonPath, 'lesson.ir.json', 'application/json'),
    artifacts,
  };
  await validateSchema('review', review);
  await atomicWriteJson(resolve(output, 'review.json'), review);
  await validateReview(taskRoot);
  return review;
}

async function buildHtml(taskRoot: string, ir: JsonObject): Promise<string> {
  const cssPath = fileURLToPath(import.meta.resolve('katex/dist/katex.min.css'));
  let katexCss = await readFile(cssPath, 'utf8');
  const fontMatches = [...katexCss.matchAll(/url\((fonts\/[^)]+\.woff2)\)/g)];
  for (const match of fontMatches) {
    const font = await readFile(resolve(dirname(cssPath), match[1]!));
    katexCss = katexCss.replaceAll(`url(${match[1]})`, `url(data:font/woff2;base64,${font.toString('base64')})`);
  }
  katexCss = katexCss.replace(/,url\(fonts\/[^)]+\.woff\) format\("woff"\),url\(fonts\/[^)]+\.ttf\) format\("truetype"\)/g, '');
  const scenes: string[] = [];
  for (const scene of ir.scenes as JsonObject[]) scenes.push(await sceneHtml(taskRoot, scene));
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${escapeHtml(ir.episode.title)} · 分镜</title><style>${katexCss}\n${storyboardCss()}</style></head><body>${scenes.join('\n')}</body></html>`;
}

async function sceneHtml(taskRoot: string, scene: JsonObject): Promise<string> {
  const visual = scene.visual as JsonObject;
  let body: string;
  switch (visual.type) {
    case 'quantity_change': {
      const states = visual.data.states as Array<{label: string; value: number}>;
      const max = Math.max(...states.map(state => state.value));
      body = `<div style="width:100%">${states.map(state => `<article style="margin:48px 0"><p data-check="quantity-label" style="font-size:38px">${escapeHtml(state.label)} · ${state.value} ${escapeHtml(visual.data.unit)}</p><div style="width:100%;height:80px;background:#dce5f2;border-radius:12px"><div style="height:80px;width:${state.value / max * 100}%;background:#315efb;border-radius:12px"></div></div></article>`).join('')}<p style="font-size:28px">所有长度使用相同比例；视频中连续改变长度。</p></div>`;
      break;
    }
    case 'title':
      body = `<div class="title-visual"><div class="eyebrow">PADNOTE LESSON</div><h1 data-check="title">${escapeHtml(visual.data.title)}</h1>${visual.data.subtitle ? `<p>${escapeHtml(visual.data.subtitle)}</p>` : ''}</div>`;
      break;
    case 'formula_steps':
      body = `<div class="formula-list">${(visual.data.steps as string[]).map((step, index) => `<div class="formula-step" data-check="formula-${index}"><span>${String(index + 1).padStart(2, '0')}</span>${katex.renderToString(step, {displayMode: true, throwOnError: true, trust: false, strict: 'error'})}</div>`).join('')}</div>`;
      break;
    case 'process':
      body = `<div class="process">${(visual.data.steps as string[]).map((step, index, steps) => `<div class="process-step" data-check="process-${index}"><span>${index + 1}</span><strong>${escapeHtml(step)}</strong></div>${index < steps.length - 1 ? '<div class="arrow">↓</div>' : ''}`).join('')}</div>`;
      break;
    case 'comparison':
      body = `<div class="comparison">${comparisonSide(visual.data.left, 'A')}${comparisonSide(visual.data.right, 'B')}</div>`;
      break;
    case 'concept_map':
      body = conceptMap(visual.data);
      break;
    case 'annotated_source': {
      const assetPath = safeRelativePath(visual.data.asset_path);
      const media = await readFile(resolve(taskRoot, assetPath));
      const mime = assetPath.endsWith('.svg') ? 'image/svg+xml' : 'image/png';
      body = `<div class="annotated"><img data-check="annotated-image" src="data:${mime};base64,${media.toString('base64')}"><ol>${(visual.data.annotations as string[]).map((text, index) => `<li data-check="annotation-${index}"><span>${index + 1}</span>${escapeHtml(text)}</li>`).join('')}</ol></div>`;
      break;
    }
    default: throw new Error(`unsupported visual.type: ${visual.type}`);
  }
  return `<section class="scene" data-scene-id="${scene.id}"><div class="safe-content"><header><span data-check="scene-id">${escapeHtml(scene.id)}</span><p data-check="objective">${escapeHtml(scene.learning_objective)}</p></header><main>${body}</main><footer>${(scene.screen_text as string[]).map(text => `<span data-check="screen-text">${escapeHtml(text)}</span>`).join('')}</footer></div></section>`;
}

function comparisonSide(side: JsonObject, marker: string): string {
  return `<article><span class="marker">${marker}</span><h2 data-check="comparison-title">${escapeHtml(side.title)}</h2><ul>${(side.items as string[]).map(item => `<li data-check="comparison-item">${escapeHtml(item)}</li>`).join('')}</ul></article>`;
}

function conceptMap(data: JsonObject): string {
  const nodes = data.nodes as JsonObject[];
  const edges = data.edges as JsonObject[];
  return `<div class="concept"><div class="concept-nodes">${nodes.map(node => `<div data-check="concept-node"><small>${escapeHtml(node.id)}</small><strong>${escapeHtml(node.label)}</strong></div>`).join('')}</div><div class="concept-edges">${edges.map(edge => `<p data-check="concept-edge"><b>${escapeHtml(nodes.find(node => node.id === edge.from)!.label)}</b><span>→</span><b>${escapeHtml(nodes.find(node => node.id === edge.to)!.label)}</b>${edge.label ? `<em>${escapeHtml(edge.label)}</em>` : ''}</p>`).join('')}</div></div>`;
}

function escapeHtml(value: unknown): string {
  return String(value).replace(/[&<>"']/g, character => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[character]!));
}

function storyboardCss(): string {
  return `*{box-sizing:border-box}html,body{margin:0;background:#dde5ef;color:#172438;font-family:"Noto Sans CJK SC","Source Han Sans SC","Microsoft YaHei",sans-serif}.scene{position:relative;width:1080px;height:1920px;overflow:hidden;background:linear-gradient(155deg,#f9fbff 0%,#edf4fb 52%,#fbf8ef 100%);border-bottom:2px solid #ccd7e4}.scene:before{content:"";position:absolute;width:540px;height:540px;border-radius:50%;background:#dbe9f8;right:-210px;top:-170px}.safe-content{position:absolute;inset:120px 72px;display:grid;grid-template-rows:190px 1fr 220px;gap:28px}header{display:flex;align-items:flex-start;gap:28px;border-bottom:3px solid #cad8e8;padding-bottom:28px}header>span{flex:0 0 auto;display:block;font:700 28px/1.2 monospace;color:#285ea8;letter-spacing:2px}header p{margin:0;font-size:42px;line-height:1.35;font-weight:650;max-width:760px}main{display:flex;align-items:center;justify-content:center;min-height:0}footer{display:flex;align-items:center;justify-content:center;flex-wrap:wrap;gap:18px;padding:28px 36px;background:#172438;border-radius:36px;color:#fff;font-size:34px;line-height:1.35;text-align:center}.title-visual{text-align:left;width:100%}.eyebrow{color:#285ea8;font-weight:750;letter-spacing:5px;font-size:26px}.title-visual h1{font-size:102px;line-height:1.08;margin:32px 0 36px;padding-bottom:8px;max-height:330px;overflow:hidden}.title-visual p{font-size:48px;line-height:1.35;color:#4d617a;margin:0}.formula-list{width:100%;display:flex;flex-direction:column;gap:28px}.formula-step{min-height:180px;padding:30px 38px;border:2px solid #d6e0eb;background:rgba(255,255,255,.9);border-radius:30px;display:grid;grid-template-columns:54px 1fr;align-items:center;overflow:hidden}.formula-step>span{font:700 25px/1 monospace;color:#7290b0}.formula-step .katex-display{margin:0;font-size:1.65em;overflow:hidden}.process{display:flex;flex-direction:column;align-items:stretch;width:76%;gap:12px}.process-step{height:145px;border-radius:30px;background:#fff;border:2px solid #d7e1ec;display:flex;align-items:center;padding:0 40px;gap:30px;font-size:42px;box-shadow:0 12px 36px rgba(31,67,105,.08)}.process-step span{width:62px;height:62px;border-radius:50%;background:#285ea8;color:#fff;display:grid;place-items:center;font-size:28px}.arrow{text-align:center;color:#6a87a6;font-size:48px;line-height:42px}.comparison{width:100%;display:grid;grid-template-columns:1fr 1fr;gap:28px}.comparison article{min-height:750px;background:#fff;border:2px solid #d7e1ec;border-radius:34px;padding:42px}.marker{display:grid;place-items:center;width:66px;height:66px;border-radius:20px;background:#dceafb;color:#285ea8;font:800 30px/1 monospace}.comparison h2{font-size:55px;margin:28px 0 34px}.comparison ul{margin:0;padding-left:36px}.comparison li{font-size:35px;line-height:1.5;margin:24px 0}.concept{width:100%;display:flex;flex-direction:column;gap:34px}.concept-nodes{display:grid;grid-template-columns:1fr 1fr;gap:24px}.concept-nodes div{height:170px;border-radius:30px;background:#fff;border:2px solid #d7e1ec;padding:30px;display:flex;flex-direction:column;justify-content:center}.concept-nodes small{color:#6b84a0;font:600 20px/1.4 monospace}.concept-nodes strong{font-size:40px}.concept-edges{display:grid;grid-template-columns:1fr 1fr;gap:14px}.concept-edges p{margin:0;min-height:92px;background:#e2eefb;border-radius:22px;padding:18px 22px;font-size:25px;display:flex;align-items:center;gap:12px}.concept-edges em{margin-left:auto;color:#8f5d2e}.annotated{width:100%;display:grid;grid-template-rows:650px auto;gap:28px}.annotated img{width:100%;height:650px;object-fit:contain;background:#fff;border-radius:28px;border:2px solid #d7e1ec}.annotated ol{margin:0;padding:0;display:grid;grid-template-columns:1fr 1fr;gap:20px;list-style:none}.annotated li{min-height:120px;background:#fff;border-radius:24px;padding:22px;font-size:28px;line-height:1.35;display:flex;gap:18px;align-items:center}.annotated li span{flex:0 0 48px;height:48px;border-radius:50%;background:#8f5d2e;color:#fff;display:grid;place-items:center}@media(max-width:1080px){body{transform-origin:top left}}`;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const revisionIndex = process.argv.indexOf('--revision');
  const revision = revisionIndex >= 0 ? Number(process.argv[revisionIndex + 1]) : 1;
  buildStoryboard(requireCliTaskRoot(), revision).then(review => {
    console.log(`storyboard built: ${review.artifacts.length} artifacts`);
  }).catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
}
