# PadNote

**写写画画，与 AI 一起思考。**

PadNote 是一个面向 Android 平板和 iPad 的开源项目，探索用手写和绘画与 Agent 沟通。目前从笔记开始：写下推导，圈出疑问，让 AI 解释或整理，再把有用的结果留在原页，接着往下写。

Android · iPad · 自选模型 · 本地笔记 · MIT

[下载 Android 测试版](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.6) · [从源码构建](#从源码构建) · [项目方向](#从笔记到-agent-入口) · [反馈问题](https://github.com/shiyan688/padnote/issues)

## 在同一页里，把问题想清楚

写到一半的推导、读到不明白的段落，可以圈选后直接提问。发送前，先确认要交给模型看的内容；回答回来后，可以继续追问，也可以允许 AI 把解释写进笔记。

纸面上的结果仍然可以编辑。公式保留 LaTeX 源码，文字支持 Markdown，图表使用 Mermaid。你可以改一个符号、补一段说明，或移动整段内容；长答案会按内容分段、跨页排版。

笔记积累起来以后，可以整理成 Markdown 知识库，按关键词检索、跨笔记提问，也可以导出到其他工具继续使用。

| 日常操作 | 当前支持 |
| --- | --- |
| 写与画 | 压感笔迹、局部橡皮、套索、高亮、几何图形、图片、撤销与重做 |
| 阅读与整理 | 多页笔记、页面管理、PDF 导入批注与导出 |
| 公式与图表 | 可编辑的 LaTeX / Markdown，离线渲染公式与 Mermaid |
| 与 AI 讨论 | 圈选问答、多轮追问、多模型配置、可选“视觉转写 → 文本回答” |
| 留下结果 | AI 通过笔记工具写入内容；用户控制写入权限，整次写入可撤销 |
| 积累知识 | 笔记数字化、本地 Markdown 知识库、检索与导出 |

## 从笔记到 Agent 入口

PadNote 的核心思想，是把平板做成个人的思考与学习工作台，让记录、理解、推导、知识积累和 AI 协作自然连在一起。

更长远的目标，是做一个更好的平板 Agent 入口。写下任务、画一张草图、圈出需要修改的地方，都可以成为表达意图的方式；Agent 返回结果后，人还可以在结果上批注，继续交流。

手写笔记是这条路线的起点。接下来要逐步连接外部 Agent，让纸面上的想法能够交给它处理，再把结果带回画布。当前已提供 Agent 能力检查和视频任务包导出；任务执行、进度跟踪、审批与结果回传仍在开发路线中。

## 模型自己选，笔记留在本地

PadNote 支持自填 OpenAI-compatible HTTPS 地址、模型和 API Key，可保存多套配置。模型服务及费用由用户自行选择，密钥保存在 Android Keystore 或 iOS Keychain 中。

笔记默认保存在设备上。使用 AI 时，相应的选区、笔记或知识库内容会按确认的范围发送给所选服务；手写转写需要模型，已有公式和图表的显示在本地完成。

Android 与 iPad 通过笔记文件交换内容，目前没有自动云同步。

## 下载安装

Android 可以直接下载安装 APK，最低 Android 7.0 / API 24：

- [最新 beta：0.18.0-beta.6](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.6/PadNote-Android-0.18.0-beta.6-debug.apk)
- [原主线：0.17.5](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.2/PadNote-Android-0.17.5-debug.apk)，供继续使用原主线的用户选择。

下载后在平板上打开 APK，按系统提示安装。目前是调试签名测试包；beta 与原主线可以同时安装，笔记通过导出、导入迁移。版本信息与 SHA-256 校验文件见 [Release](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.6)。

beta.6 改善了纸面文字、公式和图表排版，并修复 Android PDF 导出遗漏文字的问题。可以导入[排版体验笔记](docs/fixtures/paper-layout-demo.padnote.json)，无需模型配置即可查看效果；PDF 检查步骤见[导出验收](docs/PDF_EXPORT_QA.md)。电脑 Agent 设置页已加入离线连接教程。

iPad 源码已公开，最低 iPadOS 17。安装版稍后提供，目前需要通过 Xcode 自行签名运行，暂无 IPA、App Store 或 TestFlight 入口。

## 从源码构建

先克隆仓库：

```sh
git clone https://github.com/shiyan688/padnote.git
cd padnote
```

### Android

准备 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0，配置 `JAVA_HOME` 与 `ANDROID_SDK_ROOT`（或 `ANDROID_HOME`），然后运行：

```sh
tools/build-android-apk.sh
```

脚本使用仓库中的 Gradle 8.9 Wrapper，完成编译、Lint 和 APK 签名校验，将调试安装包输出到 `dist/`。首次构建需要下载 Gradle 和依赖。

### iPad

用 Xcode 打开 `ios/PadNote.xcodeproj`，选择 `PadNote` scheme 和 iPad Simulator 后运行。连接真机时，在 Signing & Capabilities 中选择自己的 Apple Development Team。

详细步骤和功能说明见 [iPad README](ios/README.md)。

### 目前还需要打磨的地方

- Apple Pencil 的延迟、压感和防误触，以及不同 Android 平板的书写体验，需要继续做真机验证。
- 模型对手写的识别和工具调用能力各有差异；当前 AI 回答会在生成完成后一次显示。
- 长文档性能、两端排版与文件往返仍需要更多实际使用反馈。iPad 封面目前只保存在本机，不随笔记文件交换。
- 外部 Agent 的完整工作流尚未接通，具体设计见 [Agent 对接说明](docs/AGENT_INTEGRATION.md)。

## 参与开发

欢迎从你实际遇到的问题开始：某支笔写起来不对、某个公式排版出错、某个模型无法调用工具，或者一份笔记在两台设备之间交换时出了问题。

[提交 Issue](https://github.com/shiyan688/padnote/issues) 时，请附设备与系统、使用版本、复现步骤和预期结果；模型相关问题再附模型名称和脱敏后的错误信息。请勿上传 API Key 或私人笔记。

代码入口：

| 目录 | 内容 |
| --- | --- |
| `android/` | Android 原生客户端与测试 |
| `ios/` | SwiftUI / UIKit iPad 客户端与测试 |
| `docs/` | AI 工具、PDF 与 Agent 接口约定 |
| `agent-skills/` | 视频讲解 Agent 子项目 |
| `tools/` | 构建、检查与开发辅助脚本 |
| `entry/` | 早期 HarmonyOS ArkUI 原型，未与双端主线同步 |

涉及笔记格式或 AI 写入权限的改动，请一并说明迁移方式和验证结果。

<details>
<summary>测试与发布验证</summary>

beta.6 的 Android 构建、59 项 JVM 测试和 APK 签名校验通过；Lint 无错误，保留 8 项既有界面字符串警告。纸面与 PDF 导出的原生回归已编译，尚未在 Android 设备运行。iPad 排版相关 20 项模拟器测试及 arm64 无签名构建通过。测试结果不替代实际平板、手写笔和模型服务的使用验证。

Android 单元测试：

```sh
cd android
./gradlew :app:testDebugUnitTest
```

仓库根目录下可运行 `node tools/static-check.mjs` 检查文件与关键约束，或运行 `bash tools/tests/test-build-android-script.sh` 检查构建脚本。

`tools/emulator/` 是 Linux 模拟器辅助脚本，需预先配置 SDK、AVD 和工具链，测试会清除目标模拟器中的 beta 应用数据。`tools/e2e/` 用于真实模型测试，需显式提供 `E2E_API_KEY`、服务地址和模型，运行会调用所选服务。

</details>

## 开源许可

Android 与 iPad 客户端的自研代码，包括手写引擎，采用 [MIT 许可证](LICENSE)。欢迎使用、修改和分发，也允许商业使用与闭源衍生；请保留版权与许可声明。

视频 Agent 子项目保留 Apache-2.0，第三方组件遵循各自许可证。详见 [许可范围](LICENSING.md) 与 [第三方声明](THIRD_PARTY_NOTICES.md)。
