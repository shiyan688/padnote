# 演示素材说明

`beta19-desktop-readiness.jpg` 是 beta.19 电脑助手页面的浏览器截图，使用当前渲染器和手工合成的三个 Agent 状态。页面顶部保留“界面预览 · 合成演示数据”标识；地址使用文档示例网段，未生成配对码或真实设备记录。它展示界面布局，不作为真实 Agent 检测或执行完成的证据。

精选画面来自 2026-10-28 的 PadNote 平板演示流程，展示示例纸面、PDF 分享入口和任务预设。采集使用 beta.17 UI，因此这些素材不代表 beta.18 UI 验收。

素材由约 1.7 fps 的真实设备截图序列拼接而成，视频导出为 30 fps；并非连续屏幕录制。笔记正文为人工输入的合成示例，不含 AI 自动生成内容或 Agent 执行结果。剪辑仅增加标题卡、解说字幕和片尾说明，没有重绘应用界面。

公开视频中的接收方信息已做定时遮挡；未遮挡的原始录制保留在本地准备目录，不纳入源码或发布附件。两段成片均不展示 Agent 执行结果：

- `PadNote-Android-Tutorial-zh.mp4` — SHA-256 `8a647797d33f0c4b3fd424e7936a4003c0686102e6e71be62097c0eacdf43c48`
- `PadNote-Android-Promo-zh.mp4` — SHA-256 `50fa545d07b9a62e8fc42257f41d8df12f00cc97d1c466308b0fe5cca6f4e556`

`paper-pdf-export.png` 来自 beta.17 已安装 Android 平板实际导出的 `PadNote Paper Demo` PDF，2026-10-28 由 Poppler 渲染为 PNG。仅有人工输入的合成示例文字，无私人笔记或 Agent 结果；PDF 为扁平化页面，文字不作为可搜索文本保留。此图展示导出页面，不作为 beta.20 新界面验收证据。

`paper-long-text-pdf.jpg` 与 `PadNote-Paper-PDF-Preview-zh.mp4` 展示 2026-10-28 平板实际导出的 18 页合成长文，使用第1、9、18页的 Poppler 渲染图。原始文字为 36 段中英双语测试资料，7820字符；不含私人资料、模型输出或 Agent 结果。视频是导出页面的编排展示，不是应用操作录屏。原 PDF SHA-256 为 `01ea5c9d8c28cee4cba7ea7264a4a3fb6f794eed3be36b305ec46d7c82570b94`。导出设备安装 beta.17，其 NoteCanvasView、PdfNoteIO、TextFlow、TextFlowCapacity 与 NoteTextBox 五个核心源文件与 beta.20 相同；这些素材不作为 beta.21 新选区界面的验收证据。
