// Simulates layoutTextFlow's fill loop with old vs new estimates against the
// measured truth, to show how much page-bottom whitespace each one leaves.
import { measureIncremental, classify, visualLines, displayMathEm } from './incremental.mjs';

const FONT = 16, WIDTH = 360;
const PAGE_CAPACITY = 640; // typical page height minus margins, in dp

function estOld(block) {
  const charW = Math.max(5, FONT * 0.56);
  const cpl = Math.max(10, Math.floor((WIDTH - 24) / charW));
  let lines = 0;
  for (const l of block.split('\n')) lines += Math.max(1, Math.ceil(Math.max(1, l.length) / cpl));
  const t = block.trim();
  if (t.startsWith('\\[') || t.startsWith('$$')) lines = Math.max(3, lines + 1);
  else if (t.startsWith('#')) lines += 1;
  else if (t.startsWith('```')) lines += 1;
  return 10 + Math.max(FONT * 1.58, lines * FONT * 1.58);
}

const SAFETY = 1.06;
const LH = { para: 1.55, listitem: 1.55, heading: 1.25, quote: 1.55, code: 1.35 };
const MARG = { para: 5, listitem: 5, heading: 8, quote: 7, code: 5, math: 8 };
const HS = { 1: 2.0, 2: 1.5, 3: 1.17, 4: 1.0, 5: 0.83, 6: 0.67 };

function estNew(block, prev) {
  const type = classify(block);
  const t = block.trim();
  if (type === 'math') {
    const tex = t.replace(/^(\\\[|\$\$)/, '').replace(/(\\\]|\$\$)$/, '').trim();
    const own = MARG.math, m = prev === null ? own : Math.max(MARG[prev], own);
    return m + displayMathEm(tex) * FONT + 2 * FONT + 16;
  }
  let fs = FONT, body = t, indent = 0, extra = 0;
  if (type === 'heading') {
    const lvl = (t.match(/^(#{1,6})/) || [, '#'])[1].length;
    fs = FONT * (HS[lvl] ?? 1); body = t.replace(/^#{1,6}\s+/, '');
  } else if (type === 'listitem') { indent = 24; body = t.replace(/^([-*+]|\d+[.)])\s+/, ''); }
  else if (type === 'quote') { extra = 10; body = t.replace(/^>\s*/, ''); }
  else if (type === 'code') { extra = 18; fs = FONT * 1.2; body = block.replace(/^```.*$/gm, ''); }
  else body = block;
  const lines = visualLines(body, WIDTH, fs, { contentPad: 28 + indent });
  let h = (lines * fs * LH[type] + extra) * SAFETY;
  if (type === 'listitem') h += 4;
  let m;
  if (prev === null) m = MARG[type];
  else if (type === 'listitem' && prev === 'listitem') m = 0;
  else m = Math.max(MARG[prev], MARG[type]);
  return m + h;
}

function fill(blocks, estimator, realIncrements) {
  // returns list of fragments: {count, estUsed, realUsed}
  const frags = [];
  let i = 0;
  while (i < blocks.length) {
    let estUsed = 24, realUsed = 24, n = 0, prev = null;
    while (i < blocks.length) {
      const e = estimator(blocks[i], prev);
      if (n > 0 && estUsed + e > PAGE_CAPACITY) break;
      estUsed += e; realUsed += realIncrements[i]; n++; prev = classify(blocks[i]); i++;
      if (estUsed >= PAGE_CAPACITY) break;
    }
    frags.push({ count: n, estUsed, realUsed });
  }
  return frags;
}

const DOC = `# 实分析复习笔记
## 控制收敛定理
设 $\\{f_n\\}$ 为可测函数序列，且 $f_n\\to f$ 几乎处处成立。
- 若存在可积函数 $g$ 使得 $|f_n|\\le g$ 对所有 $n$ 成立
- 那么积分与极限可以交换次序
- 这个条件比一致可积更强但更容易验证
\\[\\lim_{n\\to\\infty}\\int_{\\mathbb R} f_n\\,d\\mu = \\int_{\\mathbb R} f\\,d\\mu\\]
> 注意：一致可积是更弱的充分条件，实际使用中往往更灵活。
下面这个反例说明，如果没有控制函数，结论会失效。
- 取 $f_n = n$ 在区间 $[0,1/n]$ 上，其他地方为零
- 每个 $f_n$ 的积分恒等于一，不随 $n$ 变化
- 但逐点极限是零函数，积分为零
所以质量没有散失而是集中到了一点，这正是控制条件要排除的情形。
## 一致可积性
一致可积的定义要求积分的尾部一致地小，这比存在单个控制函数更宽松。
在概率论中这个条件对应鞅的收敛性质，是很多极限定理的核心假设。`;

function splitBlocks(source) {
  const blocks = []; let cur = []; let fenced = false, mathClose = null;
  const flush = () => { if (cur.length) { blocks.push(cur.join('\n')); cur = []; } };
  for (const line of source.replace(/\r\n?/g, '\n').split('\n')) {
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
  return blocks;
}

// Heading + list heavy: the typical shape of an AI-organised Markdown answer,
// which is where the old estimator over-reserved and left page-bottom gaps.
const DOC_AI = `# 梯度下降变体对比
## 随机梯度下降
- 每次只用一个样本估计梯度
- 方差大但计算便宜
- 需要精心设计学习率衰减
## 动量法
- 累积历史梯度方向
- 在峡谷型损失面上收敛更快
- 引入额外超参数 $\\beta$
## Adam
- 同时维护一阶和二阶矩估计
- 对稀疏梯度更鲁棒
- 默认超参数在多数任务上够用
## 实践建议
- 先用 Adam 快速验证可行性
- 追求最终精度时改回 SGD 加动量
- 学习率永远是最重要的超参数`;

const CASES = [['中文密集(混排)', DOC], ['标题+列表密集(AI 整理形态)', DOC_AI]];

console.log('='.repeat(80));
console.log(`分页模拟  页容量 ${PAGE_CAPACITY} dp, fontSizeSp=${FONT}`);
console.log('注：末片段剩余空间是文档自然结束，不计入浪费；只统计非末片段页底空白');
console.log('='.repeat(80));

for (const [docName, docSource] of CASES) {
  const blocks = splitBlocks(docSource);
  const [{ increments }] = await measureIncremental([{ name: docName, blocks }],
    { fontSizeSp: FONT, widthDp: WIDTH });
  console.log(`\n${'#'.repeat(3)} ${docName}  (${blocks.length} blocks)`);
  for (const [label, est] of [['旧', (b) => estOld(b)], ['新', estNew]]) {
    const frags = fill(blocks, est, increments);
    let wasted = 0, clipped = 0;
    const detail = frags.map((f, i) => {
      const isFinal = i === frags.length - 1;
      const gap = PAGE_CAPACITY - f.realUsed;
      if (!isFinal && gap > 0) wasted += gap;
      if (gap < 0) clipped += -gap;
      return `${f.count}blk/实占${f.realUsed.toFixed(0)}${gap < 0 ? `(溢出${(-gap).toFixed(0)})` : ''}`;
    }).join('  ');
    console.log(`  ${label}: ${frags.length} 片段  [${detail}]`);
    console.log(`      非末页空白 ${wasted.toFixed(0)} dp` +
      (clipped > 0 ? `，裁切 ${clipped.toFixed(0)} dp` : '，无裁切'));
  }
}
console.log('\n' + '='.repeat(80));
