// Measures the REAL rendered height of PadNote text blocks by loading the
// app's own bundled KaTeX assets in headless Chrome, reproducing
// CompiledTextWebView.buildHtml() exactly. Emits per-block truth in CSS px,
// which equals dp inside an Android WebView at default zoom.
import puppeteer from 'puppeteer';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('../../', import.meta.url));
const ASSETS = path.join(projectRoot, 'android/app/src/main/assets/katex');

// ---- port of CompiledTextWebView markdown pipeline (only what affects height) ----
const HEADING = /^(#{1,6})\s+(.+)$/;
const UNORDERED = /^\s*[-*+]\s+(.+)$/;
const ORDERED = /^\s*\d+[.)]\s+(.+)$/;

function escapeHtml(v) {
  return v.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// flattenDisplayMathBlocks: collapse multi-line \[...\] and $$...$$ into one line
function flattenDelimited(source, open, close) {
  let out = '', cursor = 0;
  for (;;) {
    const openIndex = source.indexOf(open, cursor);
    if (openIndex < 0) { out += source.slice(cursor); break; }
    const contentStart = openIndex + open.length;
    const closeIndex = source.indexOf(close, contentStart);
    if (closeIndex < 0) { out += source.slice(cursor); break; }
    out += source.slice(cursor, openIndex);
    out += open + source.slice(contentStart, closeIndex).replace(/\s*\n\s*/g, ' ') + close;
    cursor = closeIndex + close.length;
  }
  return out;
}
const flattenMath = (s) => flattenDelimited(flattenDelimited(s, '\\[', '\\]'), '$$', '$$');

function mathSpans(value) {
  // Replace $...$ / \(...\) / \[...\] / $$...$$ with katex placeholder elements
  const rules = [
    { open: '\\[', close: '\\]', display: true },
    { open: '$$', close: '$$', display: true },
    { open: '\\(', close: '\\)', display: false },
    { open: '$', close: '$', display: false },
  ];
  let out = '', cursor = 0;
  outer: while (cursor < value.length) {
    let best = null;
    for (const r of rules) {
      const i = value.indexOf(r.open, cursor);
      if (i >= 0 && (best === null || i < best.i)) best = { i, r };
    }
    if (!best) { out += escapeHtml(value.slice(cursor)); break outer; }
    const fs = best.i + best.r.open.length;
    const fe = value.indexOf(best.r.close, fs);
    if (fe < 0) { out += escapeHtml(value.slice(cursor)); break outer; }
    out += escapeHtml(value.slice(cursor, best.i));
    const tag = best.r.display ? 'div' : 'span';
    out += `<${tag} class="math-${best.r.display ? 'display' : 'inline'}" data-display="${best.r.display ? 1 : 0}" data-tex="${escapeHtml(value.slice(fs, fe).trim())}"></${tag}>`;
    cursor = fe + best.r.close.length;
  }
  return out;
}

function markdownToHtml(source) {
  let html = '', code = '', fenced = false, ul = false, ol = false;
  const lines = flattenMath(source.replace(/\r\n?/g, '\n')).split('\n');
  for (const line of lines) {
    if (line.trim().startsWith('```')) {
      if (fenced) { html += `<pre><code>${escapeHtml(code)}</code></pre>`; code = ''; }
      else { if (ul) { html += '</ul>'; ul = false; } if (ol) { html += '</ol>'; ol = false; } }
      fenced = !fenced; continue;
    }
    if (fenced) { code += (code ? '\n' : '') + line; continue; }
    const mu = UNORDERED.exec(line), mo = ORDERED.exec(line);
    if (!mu && ul) { html += '</ul>'; ul = false; }
    if (!mo && ol) { html += '</ol>'; ol = false; }
    if (mu) { if (!ul) { html += '<ul>'; ul = true; } html += `<li>${mathSpans(mu[1])}</li>`; continue; }
    if (mo) { if (!ol) { html += '<ol>'; ol = true; } html += `<li>${mathSpans(mo[1])}</li>`; continue; }
    if (!line.trim()) continue;
    const mh = HEADING.exec(line);
    if (mh) html += `<h${mh[1].length}>${mathSpans(mh[2])}</h${mh[1].length}>`;
    else if (line.startsWith('>')) html += `<blockquote>${mathSpans(line.slice(1).trim())}</blockquote>`;
    else html += `<p>${mathSpans(line)}</p>`;
  }
  if (fenced) html += `<pre><code>${escapeHtml(code)}</code></pre>`;
  if (ul) html += '</ul>';
  if (ol) html += '</ol>';
  return html || '<p></p>';
}

function normalizeLatex(source) {
  const t = source.trim();
  for (const [o, c] of [['\\[', '\\]'], ['$$', '$$'], ['\\(', '\\)'], ['$', '$']]) {
    if (t.startsWith(o) && t.endsWith(c) && t.length >= o.length + c.length) {
      return t.slice(o.length, t.length - c.length).trim();
    }
  }
  return t;
}

const CSS = readFileSync(path.join(ASSETS, 'katex.min.css'), 'utf8');
const JS = readFileSync(path.join(ASSETS, 'katex.min.js'), 'utf8');

export function buildHtml(format, source, fontSizeSp, lineHeight = 1.35) {
  const fs = Math.max(10, Math.min(32, fontSizeSp));
  const body = format === 'markdown'
    ? markdownToHtml(source)
    : `<div class="latex-root" data-display="1" data-tex="${escapeHtml(normalizeLatex(source))}"></div>`;
  // Must mirror CompiledTextWebView.buildHtml exactly, including the
  // leading-scaled block gaps, or the calibration is measuring a different page.
  const lh = Math.max(1.1, Math.min(2.0, lineHeight));
  const blockGap = Math.max(2, Math.round(fs * (lh - 1) * 0.55));
  const headingTop = blockGap + Math.max(1, Math.round(fs * 0.14));
  const mathGap = blockGap + 2;
  const listIndent = Math.round(fs * 1.5);
  return `<!doctype html><html><head><meta charset="utf-8">
<style>${CSS}</style>
<style>html,body{margin:0;padding:0;background:transparent;color:#17212b;
font-family:system-ui,-apple-system,sans-serif;font-size:${fs}px;line-height:${lh}}
.content{padding:10px 12px;overflow-wrap:anywhere}.latex-root{text-align:center;
padding:${mathGap}px 4px;overflow:visible}.math-display{display:block;text-align:center;
overflow:visible;margin:${mathGap}px 0}.math-inline{display:inline-block;margin:0 2px}
.latex-root .katex-html,.math-display .katex-html{white-space:normal}
.latex-root .katex-html>.base,.math-display .katex-html>.base{display:inline-block;white-space:nowrap;max-width:100%}
h1,h2,h3,h4,h5,h6{margin:${headingTop}px 0 ${blockGap}px;line-height:1.2;color:#1f2933}
h1{font-size:1.55em}h2{font-size:1.32em}h3{font-size:1.16em}h4{font-size:1em}h5,h6{font-size:.92em}
p{margin:${blockGap}px 0}ul,ol{margin:${blockGap}px 0;padding-left:${listIndent}px}
li{margin:0 0 ${Math.max(1, Math.floor(blockGap / 2))}px}
blockquote{margin:${mathGap}px 0;padding:${blockGap}px 10px;
border-left:3px solid #7894b8;background:#eef3f8}code{font-family:monospace;
background:#eef0f2;border-radius:4px;padding:1px 4px}pre{white-space:pre-wrap;
margin:${blockGap}px 0;
background:#eef0f2;border-radius:7px;padding:8px}.md-link{color:#285ea8}
.katex-error{color:#8f2f2b}</style></head><body><div class="content">${body}</div>
<script>${JS}</script><script>
document.querySelectorAll('[data-tex]').forEach(function(el){
katex.render(el.getAttribute('data-tex'),el,{displayMode:el.getAttribute('data-display')==='1',
throwOnError:false,strict:'ignore',trust:false,output:'htmlAndMathml'});});
function __padnoteFitMath(){document.querySelectorAll('.latex-root,.math-display').forEach(function(el){
var k=el.querySelector('.katex');if(!k)return;k.style.fontSize='1em';var available=el.clientWidth;
var bases=k.querySelectorAll('.katex-html>.base');bases.forEach(function(base){
base.style.fontSize='1em';var baseWidth=base.scrollWidth;if(baseWidth>available&&available>0){
base.style.fontSize=(available/baseWidth*.995)+'em';}});
var r=k.getBoundingClientRect();var availableHeight=Math.max(12,window.innerHeight-el.getBoundingClientRect().top-12);
var scale=Math.min(1,available/Math.max(1,Math.max(k.scrollWidth,r.width)),availableHeight/Math.max(1,r.height));
if(scale<1)k.style.fontSize=(scale*.995)+'em';});}
if(document.fonts&&document.fonts.ready)document.fonts.ready.then(__padnoteFitMath);else __padnoteFitMath();
</script></body></html>`;
}

export async function measureBlocks(blocks, { format = 'markdown', fontSizeSp = 16, widthDp = 360 } = {}) {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--font-render-hinting=none'],
  });
  try {
    const page = await browser.newPage();
    await page.setViewport({ width: Math.round(widthDp), height: 4000, deviceScaleFactor: 1 });
    const results = [];
    for (const block of blocks) {
      await page.setContent(buildHtml(format, block, fontSizeSp), { waitUntil: 'load' });
      await page.evaluate(() => document.fonts.ready);
      await page.evaluate(() => __padnoteFitMath());
      const h = await page.evaluate(() => {
        const c = document.querySelector('.content');
        const r = c.getBoundingClientRect();
        const cs = getComputedStyle(c);
        // content box height incl. its own padding = what the fragment must reserve
        return { total: r.height, pad: parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom) };
      });
      results.push({ block, height: h.total, pad: h.pad });
    }
    // whole-document height (margins collapse across blocks -> the real truth)
    await page.setContent(buildHtml(format, blocks.join('\n\n'), fontSizeSp), { waitUntil: 'load' });
    await page.evaluate(() => document.fonts.ready);
    await page.evaluate(() => __padnoteFitMath());
    const whole = await page.evaluate(() =>
      document.querySelector('.content').getBoundingClientRect().height);
    return { results, whole };
  } finally {
    await browser.close();
  }
}
