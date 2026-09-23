// Incremental measurement: the true cost of block i is H(blocks[0..i]) -
// H(blocks[0..i-1]). This captures CSS margin collapsing exactly as rendered,
// instead of measuring blocks in isolation (which double-counts .content padding).
import puppeteer from 'puppeteer';
import { buildHtml } from './measure.mjs';

export async function measureIncremental(cases, { fontSizeSp = 16, widthDp = 360, lineHeight = 1.35 } = {}) {
  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--font-render-hinting=none'],
  });
  try {
    const page = await browser.newPage();
    await page.setViewport({ width: Math.round(widthDp), height: 6000, deviceScaleFactor: 1 });

    const measure = async (source) => {
      await page.setContent(buildHtml('markdown', source, fontSizeSp, lineHeight), { waitUntil: 'load' });
      await page.evaluate(() => document.fonts.ready);
      return page.evaluate(() =>
        document.querySelector('.content').getBoundingClientRect().height);
    };

    const out = [];
    for (const { name, blocks } of cases) {
      const empty = await measure('');           // baseline = .content padding alone
      const cumulative = [];
      for (let i = 1; i <= blocks.length; i++) {
        cumulative.push(await measure(blocks.slice(0, i).join('\n\n')));
      }
      const increments = cumulative.map((h, i) => h - (i === 0 ? empty : cumulative[i - 1]));
      out.push({ name, blocks, empty, cumulative, increments, total: cumulative.at(-1) });
    }
    return out;
  } finally {
    await browser.close();
  }
}

export function classify(block) {
  const t = (block ?? '').trim();
  if (t.startsWith('```')) return 'code';
  if (t.startsWith('\\[') || t.startsWith('$$')) return 'math';
  if (/^#{1,6}\s+/.test(t)) return 'heading';
  if (t.startsWith('>')) return 'quote';
  if (/^([-*+]\s+|\d+[.)]\s+)/.test(t)) return 'listitem';
  return 'para';
}

// Inline math renders far narrower than its LaTeX source. Replace each
// $...$ / \(...\) span with a proxy string whose char count approximates the
// rendered atom count, so line-wrapping estimates stay honest.
export function compressInlineMath(line) {
  const shrink = (tex) => {
    let atoms = 0;
    let rest = tex
      .replace(/\\[a-zA-Z]+/g, () => { atoms += 1.2; return ''; })  // \to, \le, \alpha
      .replace(/[{}]/g, '')
      .replace(/[_^]\s*\w/g, () => { atoms += 0.6; return ''; });   // sub/superscript
    atoms += [...rest].filter((c) => !/\s/.test(c)).length;
    return 'x'.repeat(Math.max(1, Math.round(atoms * 1.1)));        // 1 atom ~ 0.55em ~ 1.1 ascii
  };
  let out = line;
  out = out.replace(/\\\((.+?)\\\)/g, (_, t) => shrink(t));
  out = out.replace(/\$([^$]+)\$/g, (_, t) => shrink(t));
  return out;
}

// LaTeX complexity -> rendered display-formula height in em.
export function displayMathEm(tex) {
  let em = 1.3;
  if (/\\frac|\\dfrac|\\binom/.test(tex)) em += 0.5;
  if (/\\int|\\sum|\\prod|\\oint/.test(tex)) em += 0.6;
  if (/\\lim|\\max|\\min|\\sup|\\inf/.test(tex)) em += 0.5;
  if (/\\begin\{(p|b|v|V|)matrix\}/.test(tex)) {
    const rows = (tex.match(/\\\\/g) || []).length + 1;
    em += rows * 1.2;
  }
  if (/\\sqrt/.test(tex)) em += 0.2;
  if (/[_^]/.test(tex)) em += 0.3;
  return em;
}

// CJK-aware visual line count. CSS advance width: CJK ~1.0em, ASCII ~0.5em.
export function visualLines(block, widthDp, fontSizeSp, { contentPad = 28 } = {}) {
  const usable = Math.max(1, widthDp - contentPad);
  let lines = 0;
  for (const raw of (block ?? '').split('\n')) {
    const line = compressInlineMath(raw);
    let w = 0;
    for (const ch of line) {
      const code = ch.codePointAt(0);
      const wide = (code >= 0x1100 && code <= 0x115F) || (code >= 0x2E80 && code <= 0xA4CF) ||
        (code >= 0xAC00 && code <= 0xD7A3) || (code >= 0xF900 && code <= 0xFAFF) ||
        (code >= 0xFE30 && code <= 0xFE6F) || (code >= 0xFF00 && code <= 0xFF60) ||
        (code >= 0xFFE0 && code <= 0xFFE6) || (code >= 0x20000 && code <= 0x3FFFD);
      w += wide ? fontSizeSp * 1.0 : fontSizeSp * 0.5;
    }
    lines += Math.max(1, Math.ceil(w / usable));
  }
  return Math.max(1, lines);
}
