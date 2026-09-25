# 排版估算器校准工程

`NoteCanvasView.estimateTextBlockHeight` 必须和 `CompiledTextWebView` 实际渲染的
CSS 盒模型保持一致，否则分页会在两个方向上出错：高估导致页底留白，低估导致内容
裁切。这套脚本用 headless Chromium 加载**随 APK 自托管的真实 KaTeX 资源**测量真实
DOM 高度，作为估算器的事实基准。

## 依赖

不在项目依赖内，按需临时安装（约 200MB，会下载 Chromium）：

```bash
npm install puppeteer@23.11.1
```

## 用法

```bash
node fit.mjs [fontSizeSp] [widthDp]   # 分类型残差，默认 16 360
node pagination.mjs                   # 端到端分页模拟：旧估算器 vs 新估算器
```

`fit.mjs` 输出每种 block 类型的 `est/real` 比值。判读标准：

- 落在 1.00–1.10x 之间为健康（保留单侧安全余量）
- 低于 1.00x 有裁切风险，必须修正
- 高于 1.20x 会产生可见留白

## 方法要点

- `measure.mjs` 复刻 `CompiledTextWebView.buildHtml` 中影响文本高度的 Markdown、
  KaTeX 和 inline CSS，并在 `document.fonts.ready` 后执行同一套“先按 `.base`
  换行、再缩小不可拆原子”的公式适配。Mermaid 的最终尺寸由真实 SVG viewBox 和
  Android ready 回传决定，不使用这个文本估算器。
- `incremental.mjs` 用增量法测量：第 i 个 block 的真实成本 =
  `H(blocks[0..i]) − H(blocks[0..i−1])`。这样 CSS 相邻 margin 合并的行为被自然
  包含进来，不需要推测合并规则。**不要**单独测量每个 block——那会让每个 block 都
  重复计入一次 `.content` 的上下 padding。
- 单位是 CSS px，在 Android WebView 默认缩放下等于 dp；Java 侧的 `dp()` 再乘密度，
  两边可直接比较。

## 已知局限

校准基准是 Blink。HarmonyOS 4.2 系统 WebView 的字体度量若不同，应调整
`NoteCanvasView.BLOCK_HEIGHT_SAFETY` 而不是重写估算器。终态方案是让禁网 WebView
回传实测高度并做"测量—分页—稳定"迭代，本工程只负责把预估口径校准到接近实测。
