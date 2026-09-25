# 纸面排版验收

2026-09-23；对应 Android 0.18.0-beta.5 / code 44 和 iPad build 44。自动检查与真机验收分开记录。

## 本轮改进

- 新写入正文利用纸张可用宽度，保留用户明确设置的宽度；标题、正文、列表和段间距保持清晰层次。
- 长公式在合法断点换行，不可拆的公式块按可用宽度缩小；横向和纵向图表保持自然比例。
- 标题尽量与后面的内容同页。Android 的图表可以与标题、图后正文共用一页，只有余量不足时换页；每张图单独记录实测高度，避免纵图把同一文字流中的横图撑高。
- 两端有序列表保留起始编号，空行或分页后的步骤不再全部从 1 开始；连续 `1./1./1.` 仍按 Markdown 规则显示为 1/2/3。
- Android 调整文本宽度后重新渲染和测量，公式、图表与分页跟随新宽度更新。

这些改进在本地渲染器完成，没有增加模型请求。实际平板的等待时间仍需真机测量。

## 直接体验

1. Android 安装 `dist/PadNote-Android-0.18.0-beta.5-debug.apk`。首次测试先保留旧笔记的可编辑导出备份。
2. 在书架点击“导入笔记”，选择 `dist/PadNote-排版体验.padnote.json`。无需配置模型或电脑 Agent。
3. 检查这份笔记的标题、导数推导、3–7 的步骤编号、横向流程图、图后正文及长公式。它是一份普通可编辑笔记，可以调整宽度、字号和行距。

补充压力样例见 [paper-layout.md](fixtures/paper-layout.md)，可粘贴进 Markdown 文本编辑器。

## 真机检查

1. 正文利用可用纸面宽度，标题、段落和列表层次清楚，Markdown 空行不产生大片留白。
2. 推导与长公式右端完整；缩放纸面后不缺字、不重叠。极长不可断行公式可能需要放大阅读，或编辑成多行推导。
3. 横向与纵向流程图的边框、文字、箭头完整，保持比例；小图没有被无故单独推到下一页；图后正文连续出现。代码示例中的空行应保留。
4. 标题与后续内容接近页尾时，空间不足应一起换页。检查编号继续，内容不丢失，不在最后一行重叠。
5. 一侧已有手写时让 AI 写入正文，检查落点和原有笔迹。分别检查直接插入文本与 AI 回答插入两个入口。
6. 先设置较窄宽度，再调宽，随后修改字号或行距。检查公式/图表重新适配，分页更新，源文本仍可编辑。
7. 返回书架并重新打开，检查笔迹、源文本和显式宽度保留；撤销、重做一次新写入。

出现问题时记录设备型号、系统/WebView 版本、文本宽度和可复现源文本。使用新建测试笔记截图即可，无需提供私人笔记或连接令牌。

## 本轮验证记录

- Android：59 项 JVM 测试通过，0 失败、0 错误、0 跳过；APK 构建通过。Lint 0 错误，8 项既有 `SetTextI18n` 警告；原生 instrumentation 测试编译通过，未运行。
- Android 原生测试：包含长公式、纵向图、短图与前后正文同页、两张图的高度隔离、页尾标题与图一起翻页。当前无可用原生设备；构建机无 KVM 访问权限，上一轮软件模拟器 240 秒内未启动，本轮不重复尝试。编译通过不能当作这些场景已在设备上运行通过。
- iPad：iOS 27.0 / iPad Pro 11-inch (M5) 模拟器中，CanvasTests、CompiledTextTests、MathRenderingTests 共 20 项通过，0 失败；arm64 无签名设备构建通过。
- iPad 原生画布：已检查 `dist/layout-review/beta5/ipad-paper-reviewed.png`，标题、3/4/5 步骤、导数公式与横向图完整同页。
- 导入样例的 iPad reader：672 CSS px 离线浏览器渲染无水平溢出，10 处公式、图表无报错，3–7 编号正确；高度约 970 px。此记录验证渲染器，不替代原生导入或实际设备验收。
- 先前 beta.4 的 336/520/736 CSS px 多宽度检查、53 项 Android JVM 测试、26 项 iPad 测试保留在 `dist/layout-review/validation.json`。它们是上一版的历史证据，不合并为本版测试次数。

## 已知边界

- 超长不可拆公式和超高原子图表仍可能缩小，需要放大或编辑源码分段。
- Android 原生设备、iPad 真机/Pencil 和实际端到端延迟尚未验证。本轮未运行真实模型或电脑 Agent 任务。
- **beta.5 的 Android PDF 导出会遗漏文字流**：后续 beta.6 已实现独立合成路径修复，详见 [PDF 导出验收](PDF_EXPORT_QA.md)。该修复不在本文 beta.5 的验证范围内，新增 PDF 原生回归仍需设备执行。
- iPad 工程已更新，设备构建没有签名，不能作为可安装 IPA 分发。GitHub 公开版本仍为 beta.2，本轮未发布远端。

## 产物与记录

- Android 安装包：`dist/PadNote-Android-0.18.0-beta.5-debug.apk`，1,543,197 bytes，SHA-256 `ac177c8f2586517a0bec0095ac1fdaf4294e1e32221bdc3c7024ab2b5ac3f2f0`。包名 `com.padnote.android.beta` / code 44，APK v2 签名有效，证书与公开 beta.2 及本地 beta.3/4 相同；本机/服务器文件哈希一致，包内连接教程与源 JSON 一致。
- 体验笔记：`dist/PadNote-排版体验.padnote.json`；SHA-256 `c86a4bd340328fd1c195c367b5d6912f3eb4ed40df77781e1f945e7fd841da81`。
- iPad 测试结果：`ios/TestResults/Paper-layout-beta5-20260923.xcresult`。
- iPad 日志：`ios/build/reports/paper-layout-beta5-test.log`、`ios/build/reports/paper-layout-beta5-device.log`。
- Android 构建、Lint、签名核验记录：`dist/layout-review/beta5/android-build.log`、`android-lint.html`、`android-verification.txt`。
- 本版核验元数据：`dist/layout-review/beta5/validation.json`。
- 原生截图与浏览器渲染报告：`dist/layout-review/beta5/`。

以上 `dist/`、`ios/` 路径均相对于项目根目录。
