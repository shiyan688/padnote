# 真实手写页与 Agent 函数图回到纸面

## 中文

输入图 `media/2026-10-31/real-handwriting-note.png` 是作者明确授权公开的 Android 平板原始截图（2800×1840），SHA-256：`2adb7f473c0380154c17feada48e9efe746df2d0c43dc325e4b163c8fac6a449`。原始图像字节和手写内容未改动。

独立的 Hermes Agent 后续请求返回了函数图 SVG，SHA-256：`9e3942cd8434fc4409bcf0802836a4770d22ca14e6a2e8d066e113f3a243b79e`。展示用 PNG（2000×1440，148,244 字节，SHA-256：`da389d30f0b846909926eed8176e1798391261eef5ed46a7a6cc0380f961612f`）由 Mac 本地渲染这个 SVG 得到；PNG 并非 Agent API 或 Windows 返回。函数图是独立的 `y=x²` 示例，不是上方手写方程的解。

已在 Android beta.40 隔离模拟器中通过系统图片选择器手动导入该 PNG：原手写页保留在第 1 页，图像放在第 2 页；保存后返回书架并重开，图像仍在。应用导出的两页 PDF 已逐页查看。这个过程验证的是实际图片导入、保存、重开与 PDF 展示，不是 Android 自动获取另一台设备上的 Agent 任务结果。

纸面截图 `media/2026-11-02/agent-graph-on-paper.png`（240,425 字节，SHA-256：`0b98a0302a3a4e7f478162ce893439022ddb0d23e27ebbab417b9d5e95765a1f`）来自重开后的 Android 笔记。两页 PDF 的验收回执保留在发布准备证据中。

## English

The input image, `media/2026-10-31/real-handwriting-note.png`, is the author's explicitly authorized original Android-tablet capture (2800×1840), SHA-256 `2adb7f473c0380154c17feada48e9efe746df2d0c43dc325e4b163c8fac6a449`. Its pixels and handwriting are unchanged.

A separate follow-up request to a real Hermes Agent returned an SVG graph (SHA-256 `9e3942cd8434fc4409bcf0802836a4770d22ca14e6a2e8d066e113f3a243b79e`). The display PNG (2000×1440, 148,244 bytes; SHA-256 `da389d30f0b846909926eed8176e1798391261eef5ed46a7a6cc0380f961612f`) was rendered locally on a Mac from that SVG; it was not returned by the Agent API or Windows. The graph is a separate `y=x²` example, not a solution to the handwritten equation.

On an isolated Android beta.40 emulator, the PNG was manually imported through the system image picker. The original handwriting remained on page 1 and the graph was placed on page 2. After saving, returning to the bookshelf, and reopening the note, the graph remained visible. The app's two-page PDF export was reviewed page by page. This verifies normal image import, save, reopen, and PDF presentation; it does not claim that Android automatically fetched an Agent task result from another device.

The paper screenshot, `media/2026-11-02/agent-graph-on-paper.png` (240,425 bytes; SHA-256 `0b98a0302a3a4e7f478162ce893439022ddb0d23e27ebbab417b9d5e95765a1f`), is from the reopened Android note. The two-page PDF acceptance receipt is retained with the release-preparation evidence.
