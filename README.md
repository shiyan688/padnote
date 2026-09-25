# PadNote

**PadNote 是一个开源的手写笔记应用**，面向 Android 平板和 iPad，把手写、绘画、AI 推理和知识积累放在同一页里完成。笔记默认保存在设备上，模型由你自己配置，MIT 许可，允许商用与闭源衍生。

**写写画画，与 AI 一起思考。**

PadNote 是一个面向 Android 平板和 iPad 的开源项目，探索用手写和绘画与 Agent 沟通。目前从笔记开始：写下推导，圈出疑问，让 AI 解释或整理，再把有用的结果留在原页，接着往下写。

Android · iPad · 自选模型 · 本地笔记 · MIT

[适合谁用](#适合谁用) · [和同类方案的区别](#padnote-与同类方案的区别) · [常见问题](#常见问题-faq) · [下载 Android 测试版](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.6) · [从源码构建](#从源码构建) · [项目方向](#从笔记到-agent-入口) · [反馈问题](https://github.com/shiyan688/padnote/issues)

> **一句话说清它和别的 AI 笔记的区别：大多数 AI 笔记把回答留在聊天窗口里，PadNote 把回答写回纸面——而且你能改。**
> 公式保留 LaTeX 源码、文字支持 Markdown、图表使用 Mermaid，所以 AI 写下的解释可以在原页上继续改，改完接着往下推。

## 适合谁用

**适合：**

- 用平板 + 手写笔做推导、批注、整理的人：数学、物理、电路、算法、读书笔记、会议记录
- 想在**原页上**和 AI 讨论的人，而不是切到另一个聊天窗口、再把结论手动抄回来
- 想自己选模型、用自己的 API Key 的人（支持任意 OpenAI-compatible 服务，可存多套配置）
- 不想把笔记上传到云厂商的人（笔记默认在设备上，没有自动云同步）
- Android 平板与 iPad 双持的人（通过笔记文件交换内容）
- 想读代码、改代码、甚至商用或做闭源衍生的人（MIT）
- 学生、研究者、工程师、自学者——**只要你的思考方式是“手写 + 追问”**

**不适合：**

- 需要 iPad 上一键安装 App Store / TestFlight 版的：目前 iPad 只能从源码构建并自行签名
- 需要多设备自动云同步、或 iPad 与 Android 之间自动实时同步的：目前没有，只支持导出 / 导入
- 想让 AI 全自动写完笔记、不需要自己确认的：写入笔记需要你授权，而且 AI 不能碰你的笔迹
- 需要多人实时协作编辑的
- 主要用键盘打字、基本不手写的（这个工具是手写优先的）
- 要找专业绘画笔刷的：这是笔记应用，不是 Procreate 替代品

## PadNote 与同类方案的区别

对比焦点不是功能多少，是**AI 写下的东西以什么形态存在**。

| | PadNote | GoodNotes 6 | Notability | Apple Notes | AI Canvas |
| --- | --- | --- | --- | --- | --- |
| 平台 | **Android 平板 + iPad** | iPad / Mac / iPhone | iPad / Mac / iPhone | Apple 全平台 | iPad |
| 手写引擎 | 双端各一套自研 | 自研 | 自研 | 系统 | PencilKit |
| AI 能力 | 圈选提问、多轮追问、可写入笔记 | AI for Math（Solve / Teach Me） | 录音 + 笔迹对齐 | 系统写作工具 | 多模态问答 |
| **AI 输出形态** | **写回纸面，保留 LaTeX / Markdown / Mermaid 源码，可继续编辑** | 聊天式卡片 | 不写入笔记 | 不写入笔记 | 卡片 |
| 模型可否自选 | **可以，任意 OpenAI-compatible 服务，多套配置** | 不能 | 不能 | 不能 | 可以（Gemini / ChatGPT / Claude / Groq） |
| 密钥存放 | Android Keystore / iOS Keychain | — | — | — | iOS Keychain |
| 笔记存储 | **默认在设备本地，无自动云同步** | 本地 + iCloud | 本地 + iCloud | iCloud | 本地 |
| AI 能否改动笔迹 | **不能，代码层禁止** | — | — | — | — |
| 许可 | **MIT，允许商用与闭源衍生** | 专有 | 专有 | 专有 | 自定义（非标准） |
| 价格 | 免费开源 | 免费版；Pro 订阅；AI 另有订阅 | 免费版；Plus / Pro 订阅 | 免费 | 免费开源 |

**如果只看一件事：** 别的工具把 AI 的回答当**消息**，PadNote 把 AI 的回答当**笔记的一部分**。消息不能编辑、会滚走；笔记内容有位置、能改、会留下。

**关于“AI 不能碰笔迹”这一点，不是宣传，是代码里的结构约束：**

文件 `android/app/src/main/java/com/padnote/android/NoteTools.java` 定义了模型可调用的工具集：

```
read_page_map       读页面结构（分带、文字框、墨迹簇、自由区域）
write_text          写入文字
set_text_flow_style 调整文字排版
move_text_flow      移动文字块（跨页）
search_vault        检索知识库（只读）
read_vault_note     读知识库笔记（只读）
```

而 `move_ink` / `delete_flow` / `delete_text` / `edit_stroke` 这几个工具名**被显式禁止出现在代码里**——`tools/static-check.mjs` 检查到就会报错。源码注释原文：

> `v1 deliberately grants no authority over ink; without recognition the model cannot know what a stroke cluster means.`

对知识库的访问同样是结构性的：存储层实现的是 `NoteTools.VaultReader`（只读视图），**模型能搜索、能读，不能写**。

> 上表中竞品的价格与功能为 2026-09 公开页面信息，可能已经变动；请以对方官网为准。PadNote 这一列可对着仓库代码逐项核验。

## 在同一页里，把问题想清楚

> **这一节回答：PadNote 的核心机制是什么、有哪些能力。**

写到一半的推导、读到不明白的段落，可以圈选后直接提问。发送前，先确认要交给模型看的内容；回答回来后，可以继续追问，也可以允许 AI 把解释写进笔记。

纸面上的结果仍然可以编辑。公式保留 LaTeX 源码，文字支持 Markdown，图表使用 Mermaid。你可以改一个符号、补一段说明，或移动整段内容；长答案会按内容分段、跨页排版。

笔记积累起来以后，可以整理成 Markdown 知识库，按关键词检索、跨笔记提问，也可以导出到其他工具继续使用。

**能力一览：**

| 日常操作 | 当前支持 |
| --- | --- |
| 写与画 | 压感笔迹、局部橡皮、套索、高亮、几何图形、图片、撤销与重做 |
| 阅读与整理 | 多页笔记、页面管理、PDF 导入批注与导出 |
| 公式与图表 | 可编辑的 LaTeX / Markdown，离线渲染公式与 Mermaid |
| 与 AI 讨论 | 圈选问答、多轮追问、多模型配置、可选“视觉转写 → 文本回答” |
| 留下结果 | AI 通过笔记工具写入内容；用户控制写入权限，整次写入可撤销 |
| 积累知识 | 笔记数字化、本地 Markdown 知识库、检索与导出 |

## 从笔记到 Agent 入口

> **这一节回答：PadNote 想做的是什么、哪些已经能用、哪些还在路线里。**

PadNote 的核心思想，是把平板做成个人的思考与学习工作台，让记录、理解、推导、知识积累和 AI 协作自然连在一起。

更长远的目标，是做一个更好的平板 Agent 入口。写下任务、画一张草图、圈出需要修改的地方，都可以成为表达意图的方式；Agent 返回结果后，人还可以在结果上批注，继续交流。

手写笔记是这条路线的起点。接下来要逐步连接外部 Agent，让纸面上的想法能够交给它处理，再把结果带回画布。当前已提供 Agent 能力检查和视频任务包导出；任务执行、进度跟踪、审批与结果回传仍在开发路线中。

> **成熟度诚实说明：** 上面这段里，“连接外部 Agent”是**路线**，不是已完成的能力。目前已经能用的是手写笔记、圈选问答、可编辑的 AI 结果、PDF 批注和本地知识库；Agent 的完整工作流（执行、进度、审批、回传）**还在开发中**。具体约定见 [Agent 对接说明](docs/AGENT_INTEGRATION.md)。

## 模型自己选，笔记留在本地

> **这一节回答：AI 用什么模型、笔记存在哪里、哪些数据会被发出去。**

PadNote 支持自填 OpenAI-compatible HTTPS 地址、模型和 API Key，可保存多套配置。模型服务及费用由用户自行选择，密钥保存在 Android Keystore 或 iOS Keychain 中。

笔记默认保存在设备上。使用 AI 时，相应的选区、笔记或知识库内容会按确认的范围发送给所选服务；手写转写需要模型，已有公式和图表的显示在本地完成。

Android 与 iPad 通过笔记文件交换内容，目前没有自动云同步。

**这一节可以验证的点：**

- 密钥使用 Android `AndroidKeyStore`（`AES/GCM/NoPadding`）与 iOS Keychain 保存，不是明文存储
- 公式渲染用内置 KaTeX、图表用内置 Mermaid，均为离线资源；渲染用的 WebView 显式设置 `setBlockNetworkLoads(true)`、`setAllowUniversalAccessFromFileURLs(false)`，并带 CSP——**渲染过程不联网**
- Android `AndroidManifest.xml` 设置 `android:allowBackup="false"`，笔记**不进入系统默认备份**
- 提交给模型的内容范围在发送前由你确认

## 下载安装

> **这一节回答：Android 和 iPad 分别怎么装、要什么系统版本。**

Android 可以直接下载安装 APK，最低 Android 7.0 / API 24：

- [最新 beta：0.18.0-beta.6](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.6/PadNote-Android-0.18.0-beta.6-debug.apk)
- [原主线：0.17.5](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.2/PadNote-Android-0.17.5-debug.apk)，供继续使用原主线的用户选择。

下载后在平板上打开 APK，按系统提示安装。目前是调试签名测试包；beta 与原主线可以同时安装，笔记通过导出、导入迁移。版本信息与 SHA-256 校验文件见 [Release](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.6)。

beta.6 改善了纸面文字、公式和图表排版，并修复 Android PDF 导出遗漏文字的问题。可以导入[排版体验笔记](docs/fixtures/paper-layout-demo.padnote.json)，无需模型配置即可查看效果；PDF 检查步骤见[导出验收](docs/PDF_EXPORT_QA.md)。电脑 Agent 设置页已加入离线连接教程。

iPad 源码已公开，最低 iPadOS 17。安装版稍后提供，目前需要通过 Xcode 自行签名运行，暂无 IPA、App Store 或 TestFlight 入口。

> **iPad 用户的现实预期：** 现在这条路需要一台 Mac、Xcode 和一个自己的 Apple 开发者账号（免费账号可用，签名有效期 7 天）。如果你不接受这个过程，**现在还不适合用**——App Store / TestFlight 版本尚未提供。

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

## 常见问题（FAQ）

### PadNote 是什么？

PadNote 是一个开源的手写笔记应用，面向 Android 平板和 iPad。它把手写笔迹、AI 问答和知识积累放在同一份笔记里：你可以圈选写到一半的推导去问 AI，让 AI 把解释写进页面，然后直接在那段解释上继续改、继续推。

### 它和 GoodNotes、Notability 最大的区别是什么？

两处。**第一，AI 写下的结果是可以编辑的笔记内容**：公式保留 LaTeX 源码、文字是 Markdown、图表是 Mermaid，你能改一个符号接着往下推；而那些工具的 AI 输出是聊天卡片，读得到、改不了。**第二，模型和存储都由你决定**：支持任意 OpenAI-compatible 服务、可存多套配置，密钥进 Keystore / Keychain，笔记默认不上传，不绑定任何云服务。

### 免费吗？需要订阅吗？

免费，MIT 许可。没有订阅、没有功能门禁。你自己付给模型服务商的钱是另一回事。

### AI 功能要另外付钱吗？

要，但不是付给 PadNote。PadNote 不自带模型、不转售模型额度，你需要自己填一个 OpenAI-compatible 服务的地址、模型名和 API Key，费用直接结算给你选的服务商。

### 我的笔记会被上传到云端吗？

不会自动上传。笔记默认保存在设备上，没有自动云同步。使用 AI 时，只有你在发送前确认过的选区、笔记或知识库内容会发给**你自己配置的**那个服务。公式和图表的渲染在本地完成，渲染用的 WebView 被显式禁止联网。

### AI 能看到我的手写笔迹吗？能改我的笔迹吗？

**能“看”（在你的授权范围内），不能改。** 圈选问答时，你确认的选区会被转写或作为图像发送给你选的模型。但 AI 对笔迹没有任何写权限——模型可用的工具只有 `read_page_map`、`write_text`、`set_text_flow_style`、`move_text_flow`、`search_vault`、`read_vault_note`；像 `move_ink`、`edit_stroke` 这类工具名在代码里是被禁止出现的。对知识库的访问也是只读的。

### 支持哪些模型？

任何提供 OpenAI-compatible HTTPS 接口的服务都可以，可以保存多套配置，也可以配置“视觉转写 → 文本回答”的两段式路线（一部分模型擅长读手写图像，另一部分擅长推理，可以分别配置）。

### AI 写进笔记的内容可以撤销吗？

可以。写入需要你授权，整次写入可以撤销。

### iPad 上怎么安装？

目前**只能从源码构建**：需要一台 Mac、Xcode，以及你自己的 Apple 开发者账号（免费账号可用，签名 7 天有效）。仓库里有 `ios/PadNote.xcodeproj` 和详细的 [iPad README](ios/README.md)。**暂无 IPA、App Store 或 TestFlight 入口。**

### Android 上怎么安装？

直接下载 [APK](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.6) 安装，最低 Android 7.0 / API 24。目前是调试签名测试包，beta 与原主线可以同时安装。也可以按 [从源码构建](#android) 自行编译。

### Android 和 iPad 之间能同步吗？

不能自动同步。两端通过笔记文件导出 / 导入交换内容，目前没有内置云同步。

### 支持数学公式和图表吗？

支持，而且**是可编辑的**。公式保留 LaTeX 源码，图表用 Mermaid，渲染用内置的 KaTeX 和 Mermaid 离线完成，不联网。

### 能导入和批注 PDF 吗？

能。支持 PDF 导入、批注和导出。

### 笔记能导出到别的工具吗？

能。笔记可以整理成 Markdown 知识库（本地文件），按关键词检索、跨笔记提问，也可以导出到其他工具继续使用。

### 现在成熟度如何？稳定吗？

诚实地说：**是 beta，不是成品。**

- Android 侧已发布 beta 包（当前 `0.18.0-beta.6`），能日常使用；iPad 侧需要自行构建
- 作者自述 beta.6 通过了 Android 构建、59 项 JVM 测试、APK 签名校验，Lint 无错误但保留 8 项界面字符串警告；纸面与 PDF 导出的原生回归**已编译，尚未在 Android 设备运行**
- iPad 侧排版相关 20 项模拟器测试通过
- **手写笔的真机体验**（延迟、压感、防误触，尤其在各家 Android 平板上）仍在持续验证
- AI 回答目前是生成完成后一次性显示，不是逐字流式
- 外部 Agent 的完整工作流还没接通

### 可以商用吗？可以做闭源衍生吗？

可以。Android 与 iPad 客户端的自研代码（含手写引擎）是 MIT，允许商业使用与闭源衍生，保留版权与许可声明即可。视频 Agent 子项目保留 Apache-2.0。详见 [许可范围](LICENSING.md)。

### 我怎么参与？

从你实际遇到的问题开始就好：某支笔写起来不对、某个公式排版出错、某个模型调不动工具、两台设备交换笔记出了问题。附上设备与系统、版本、复现步骤和预期结果，[提 Issue](https://github.com/shiyan688/padnote/issues) 即可。

## 开源许可

Android 与 iPad 客户端的自研代码，包括手写引擎，采用 [MIT 许可证](LICENSE)。欢迎使用、修改和分发，也允许商业使用与闭源衍生；请保留版权与许可声明。

视频 Agent 子项目保留 Apache-2.0，第三方组件遵循各自许可证。详见 [许可范围](LICENSING.md) 与 [第三方声明](THIRD_PARTY_NOTICES.md)。
