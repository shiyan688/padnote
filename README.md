# PadNote

**写写画画，与 AI 一起思考。**

写到一半的推导、读不懂的段落、一张还没成形的草图，都可以成为一次对话的起点。PadNote 把手写、阅读和 AI 讨论放在同一张纸上：圈出问题，继续追问，把有用的回答留下，再接着写。

我们的长期目标，是做一个更好的平板 Agent 入口。你用文字和图画表达意图，Agent 返回结果，你在结果上批注，继续交流。手写笔记是这条路线的起点。

Android · iPad · 自选模型 · 本地笔记 · MIT

[下载安装](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.9) · [电脑连接助手](desktop/connection-assistant/README.md) · [提交反馈](https://github.com/shiyan688/padnote/issues) · [English](README.en.md)

## 让回答成为笔记的一部分

- **在原页上讨论。** 圈选笔迹或内容，确认发送范围后提问；可以继续追问，也可以授权 AI 写入笔记。
- **结果可以改。** 文字保留 Markdown，公式保留 LaTeX，图表使用 Mermaid。修改源码、调整位置后，仍能继续书写和批注。
- **长回答按页阅读。** 长文分段、跨页排版；缩放时保留原有文字布局。Android PDF 导出包含纸面文字、笔迹和批注。
- **笔记留在设备上。** 支持多页笔记、PDF 导入、图片、套索、高亮、撤销与重做，以及本地 Markdown 知识库的整理和检索。

模型由每位用户自己选择，填写自己的服务地址、模型名和 API Key，费用由自己的供应商账户承担。使用 AI 时，确认范围内的内容会发送给所选服务。PadNote 没有自动云同步，公式和图表显示可在本地完成。

## 把笔记交给自己的电脑

源码预览版加入了电脑连接助手。平板和电脑在同一个 Wi-Fi 时，电脑生成配对码，平板扫码或粘贴，电脑核对并批准。可以保存多条连接，每个 Agent 和设备独立授权；不需要 PadNote 服务器。

- **已有 Hermes：** 沿用电脑上的安装和模型配置，发送文字或笔记任务包，查询进度、处理审批、取回文件。Windows / WSL2 的步骤见[连接教程](docs/HERMES_CONNECTION.md)。
- **内置视频工作流：** 将笔记文字稿交给自己的电脑，用用户自己的 Qwen API Key 生成分镜；审阅批准后，另行确认配音调用，电脑渲染并回传视频、字幕和封面。“内置”指助手提供这套流程，并非在平板上运行 Qwen 模型。

助手保留 Agent 的长期密钥，平板使用可撤销的设备凭据。文件传输有路径、大小和摘要校验；电脑上的 Agent 仍受它自身的权限与审批配置约束。OpenClaw 目前只有检测和设置引导，Codex 尚未接入。

这些连接和视频流程仍在验收中：真实 Hermes、Windows 原生运行包、WSL2 网络和真实平板尚未完整验证。当前视频材料以文字快照为主，完整手写与原图材料包仍待接入。下载时请以对应 Release 的说明为准。

## 安装与构建

Android 最低 Android 7.0 / API 24。下载 [PadNote Android beta 9 APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.9/PadNote-Android-0.18.0-beta.9-debug.apk)，或查看 [beta 9 发布说明](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.9)。beta 测试包与原 0.17.5 主线使用不同包名，可以共存；跨包迁移请先导出笔记，再导入。

beta.9 使用调试签名。安装前请备份重要笔记并核对发布页提供的 SHA-256。视频等本地附件的迁移范围以具体导出格式为准；项目不提供自动云同步。beta.9 已包含 Agent 任务提交与详情页的本地存储失败修复：读取连接配置失败时，任务显示未发送并保留所选 Agent，避免任务停在提交中或详情页崩溃。与 beta.8 包名和签名相同，可覆盖安装；原 beta.8 下载仍保留。

构建 Android 需要 JDK 17 或更新版本、Android SDK Platform 35 和 Build Tools 34/35。配置 `JAVA_HOME`、`ANDROID_SDK_ROOT` 后运行：

```sh
git clone https://github.com/shiyan688/padnote.git
cd padnote
tools/build-android-apk.sh
```

iPad 最低 iPadOS 17。当前 `main` 源码包含完整的原生 iPad 客户端；用 Xcode 打开 `ios/PadNote.xcodeproj` 并选择 PadNote scheme 运行模拟器，真机需自己的 Apple Development Team。目前暂无可直接安装的 IPA、App Store 或 TestFlight 版本。iPad 验证范围与限制见 [iPad 构建说明](ios/README.md)。iPad 源码更新不代表 beta.9 Android APK 内容变化，也不代表两端功能完全一致。

## 参与开发

欢迎从实际使用中的问题开始。提交 Issue 时请附设备、系统、应用版本、复现步骤和预期结果；模型相关问题再附模型名和脱敏错误。请勿上传密钥或私人笔记。

| 目录 | 内容 |
| --- | --- |
| `android/` | Android 客户端与测试 |
| `ios/` | iPad 客户端与测试 |
| `desktop/connection-assistant/` | 本地电脑连接助手 |
| `agent-skills/` | 视频讲解流程与协议 |
| `docs/` | 连接、AI 权限与文件格式说明 |
| `tools/` | 构建和验证工具 |
| `entry/` | 早期 HarmonyOS 原型 |

Android 单元测试：`cd android && ./gradlew :app:testDebugUnitTest`。模拟器测试不能代替真实手写笔、文件选择器和 Agent 的验收。

## 许可

Android 与 iPad 自研代码采用 [MIT](LICENSE)，允许使用、修改、商业使用与闭源衍生，请保留版权和许可声明。视频 Agent 子项目保留 Apache-2.0；第三方组件遵循各自许可证，见[许可范围](LICENSING.md)和[第三方声明](THIRD_PARTY_NOTICES.md)。
