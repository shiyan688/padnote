// Fits a structural replacement for estimateTextBlockHeight() against measured
// incremental block costs, then reports residuals per block type and per doc.
import { measureIncremental, classify, visualLines, displayMathEm } from './incremental.mjs';

// ---- current Java estimator, for the before/after comparison ----
function estimateCurrent(block, widthDp, fontSizeSp) {
  const charW = Math.max(5, fontSizeSp * 0.56);
  const cpl = Math.max(10, Math.floor((widthDp - 24) / Math.max(1, charW)));
  let lines = 0;
  for (const line of (block ?? '').split('\n')) {
    lines += Math.max(1, Math.ceil(Math.max(1, line.length) / cpl));
  }
  const t = (block ?? '').trim();
  if (t.startsWith('\\[') || t.startsWith('$$')) lines = Math.max(3, lines + 1);
  else if (t.startsWith('#')) lines += 1;
  else if (t.startsWith('```')) lines += 1;
  const lh = fontSizeSp * 1.58;
  return 10 + Math.max(lh, lines * lh);
}

// ---- proposed structural estimator (mirrors the real CSS box model) ----
// Per type: collapsed top margin + n * lineHeight + intrinsic box extras.
// Mirrors NoteCanvasView.estimateTextBlockHeight after leading became a
// per-flow property: gaps scale with (lineHeight - 1), headings keep 1.2.
const LINE_HEIGHT = Number(process.env.PADNOTE_LH ?? 1.35);
function gaps(fs, lh) {
  const blockGap = Math.max(2, Math.round(fs * (lh - 1) * 0.55));
  return { blockGap, headingTop: blockGap + Math.max(1, Math.round(fs * 0.14)), mathGap: blockGap + 2 };
}
const MODEL = {
  para:     { lh: LINE_HEIGHT, extra: 0 },
  listitem: { lh: LINE_HEIGHT, extra: 0, indentEm: 1.5 },
  heading:  { lh: 1.2, extra: 0 },
  quote:    { lh: LINE_HEIGHT, extra: 'quote' },
  code:     { lh: Math.min(LINE_HEIGHT, 1.35), extra: 16, mono: true },
  math:     { lh: 1.0, extra: 0 },
};

const HEADING_SCALE = { 1: 1.55, 2: 1.32, 3: 1.16, 4: 1.0, 5: 0.92, 6: 0.92 };

function estimateProposed(block, widthDp, fontSizeSp, prevType) {
  const type = classify(block);
  const m = MODEL[type];
  const t = (block ?? '').trim();
  const g = gaps(fontSizeSp, LINE_HEIGHT);
  const SAFETY = 1.06;

  let fs = fontSizeSp;
  if (type === 'heading') {
    const level = (t.match(/^(#{1,6})/) || [, '#'])[1].length;
    fs = fontSizeSp * (HEADING_SCALE[level] ?? 1);
  }

  let height;
  if (type === 'math') {
    const tex = t.replace(/^(\\\[|\$\$)/, '').replace(/(\\\]|\$\$)$/, '').trim();
    height = displayMathEm(tex) * fs + 2 * fs + g.mathGap * 2;
  } else {
    let body = block;
    if (type === 'code') body = block.replace(/^```.*$/gm, '');
    if (type === 'listitem') body = t.replace(/^\s*([-*+]|\d+[.)])\s+/, '');
    if (type === 'heading') body = t.replace(/^#{1,6}\s+/, '');
    if (type === 'quote') body = t.replace(/^>\s*/, '');
    const indent = m.indentEm ? Math.round(fontSizeSp * m.indentEm) : 0;
    const pad = 24 + indent;
    const lines = visualLines(body, widthDp, m.mono ? fs * 1.2 : fs, { contentPad: pad });
    const extra = m.extra === 'quote' ? g.blockGap * 2 : (m.extra ?? 0);
    height = (lines * fs * m.lh + extra) * SAFETY;
    if (type === 'listitem') height += 4;
  }

  // collapsed margin against previous sibling
  const own = (k) => (k === 'heading' || k === 'quote' || k === 'math')
    ? g.blockGap + 2 : g.blockGap;
  let topMargin;
  if (prevType === null) topMargin = own(type);
  else if (type === 'listitem' && prevType === 'listitem') topMargin = 0;
  else topMargin = Math.max(own(prevType), own(type));

  return topMargin + height;
}

const DOCS = {
  'list-heavy': `## 收敛性小结
- 逐点收敛不足以推出积分收敛
- 需要一致可积或控制收敛
- 反例：移动的窄高峰
- 结论对 L^1 成立
这一节的要点是：在没有额外控制条件时，逐点收敛与积分收敛之间没有蕴含关系。`,
  'prose': `设 $f_n$ 为可测函数序列。若逐点收敛到 $f$，并不能直接得到积分收敛。
需要额外的一致可积性条件，或者引入控制函数。
下面给出一个标准反例，说明结论在没有控制时会失效。`,
  'mixed': `# 实分析笔记
## 控制收敛定理
设 $\\{f_n\\}$ 可测且 $f_n\\to f$ 几乎处处收敛。
- 若存在可积 $g$ 使 $|f_n|\\le g$
- 则积分与极限可交换
\\[\\int \\lim f_n = \\lim \\int f_n\\]
> 注意：一致可积是更弱的充分条件。
最后提醒：反例的关键是质量不散失但集中。`,
  'long-cn': `机器学习中的过拟合问题通常表现为训练误差持续下降而验证误差开始上升，这说明模型开始记忆训练集中的噪声而非学习普适规律。
常见的缓解手段包括正则化、早停、数据增强以及集成方法。
- 正则化通过在损失函数中加入参数范数惩罚来限制模型复杂度
- 早停依赖验证集监控，在泛化性能转折点终止训练
其中权重衰减对应 $L^2$ 正则，等价于对参数施加高斯先验。`,
};

// same splitter as NoteCanvasView.splitTextFlowBlocks
function splitBlocks(source) {
  const blocks = []; let cur = []; let fenced = false, mathClose = null;
  const flush = () => { if (cur.length) { blocks.push(cur.join('\n')); cur = []; } };
  for (const line of (source ?? '').replace(/\r\n?/g, '\n').split('\n')) {
    const t = line.trim();
    if (mathClose) { cur.push(line); if (t.includes(mathClose)) { flush(); mathClose = null; } continue; }
    if (fenced) { cur.push(line); if (t.startsWith('```')) { flush(); fenced = false; } continue; }
    if (t.startsWith('```')) { flush(); cur.push(line); fenced = true; continue; }
    if ((t.startsWith('\\[') && !t.includes('\\]')) || (t.startsWith('$$') && t.indexOf('$$', 2) < 0)) {
      flush(); cur.push(line); mathClose = t.startsWith('\\[') ? '\\]' : '$$'; continue;
    }
    if (!t) { flush(); continue; }
    if (t.startsWith('#') || t.startsWith('>') || /^[-*+]\s+/.test(t) || /^\d+[.)]\s+/.test(t)) {
      flush(); blocks.push(line);
    } else cur.push(line);
  }
  flush();
  if (!blocks.length) blocks.push(source ?? '');
  return blocks;
}

const FONT = Number(process.argv[2] ?? 16);
const WIDTH = Number(process.argv[3] ?? 360);

const cases = Object.entries(DOCS).map(([name, doc]) => ({ name, blocks: splitBlocks(doc) }));
const measured = await measureIncremental(cases, { fontSizeSp: FONT, widthDp: WIDTH, lineHeight: LINE_HEIGHT });

console.log('='.repeat(88));
console.log(`增量测量校准  fontSizeSp=${FONT}  widthDp=${WIDTH}  lineHeight=${LINE_HEIGHT}  (dp)`);
console.log('='.repeat(88));

const byType = {};
let sum = { cur: 0, prop: 0, real: 0 };

for (const { name, blocks, increments, total, empty } of measured) {
  console.log(`\n### ${name}   (.content padding = ${empty.toFixed(1)} dp)`);
  console.log('  ' + 'type'.padEnd(10) + 'block'.padEnd(30) +
    'real'.padStart(8) + 'cur'.padStart(8) + 'prop'.padStart(8) +
    'cur/re'.padStart(8) + 'prop/re'.padStart(9));
  let prevType = null;
  let curTotal = empty, propTotal = empty;
  blocks.forEach((b, i) => {
    const type = classify(b);
    const real = increments[i];
    const cur = estimateCurrent(b, WIDTH, FONT);
    const prop = estimateProposed(b, WIDTH, FONT, prevType);
    curTotal += cur; propTotal += prop;
    (byType[type] ??= []).push({ real, cur, prop });
    console.log('  ' + type.padEnd(10) + b.replace(/\n/g, '\\n').slice(0, 28).padEnd(30) +
      real.toFixed(1).padStart(8) + cur.toFixed(1).padStart(8) + prop.toFixed(1).padStart(8) +
      (cur / real).toFixed(2).padStart(8) + (prop / real).toFixed(2).padStart(9));
    prevType = type;
  });
  console.log('  ' + '-'.repeat(84));
  console.log('  ' + 'DOC TOTAL'.padEnd(40) + total.toFixed(1).padStart(8) +
    curTotal.toFixed(1).padStart(8) + propTotal.toFixed(1).padStart(8) +
    (curTotal / total).toFixed(2).padStart(8) + (propTotal / total).toFixed(2).padStart(9));
  sum.real += total; sum.cur += curTotal; sum.prop += propTotal;
}

console.log('\n' + '='.repeat(88));
console.log('按类型残差 (est/real 均值):');
for (const [type, rows] of Object.entries(byType)) {
  const mc = rows.reduce((s, r) => s + r.cur / r.real, 0) / rows.length;
  const mp = rows.reduce((s, r) => s + r.prop / r.real, 0) / rows.length;
  console.log(`  ${type.padEnd(10)} n=${String(rows.length).padStart(2)}  current ${mc.toFixed(2)}x   proposed ${mp.toFixed(2)}x`);
}
console.log('-'.repeat(88));
console.log(`合计  real ${sum.real.toFixed(0)}  current ${sum.cur.toFixed(0)} (${(sum.cur / sum.real).toFixed(2)}x)  proposed ${sum.prop.toFixed(0)} (${(sum.prop / sum.real).toFixed(2)}x)`);
console.log('='.repeat(88));
