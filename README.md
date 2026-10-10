# PadNote

**在同一张纸上写、画、提问，再把完整纸面交给电脑 Agent。**

PadNote 是开源的平板笔记与 AI 学习工具。你可以在笔记里书写、绘图和交流，也可以把当前笔记的 PDF 与可读上下文交给同一局域网中已配对的电脑 Agent。

长期愿景是让平板成为 Agent 入口：用写、画和圈选表达意图，再在纸面结果上继续批注和沟通。

[Android beta.10 测试版](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.10) · [测试步骤](docs/BETA10_TESTING.md) · [从源码构建](#从源码构建) · [反馈问题](https://github.com/shiyan688/padnote/issues)

## 在纸面上学习

PadNote 将手写笔迹、AI 交流和笔记内容放在同一张纸面上。Android 可创建多页笔记，书写、缩放、继续提问，并将有用结果留在页面中。你也可以分享整份纸面 PDF，保留页面背景、笔迹和可读文字。

当你需要电脑上的 Agent 帮忙时，可以发送当前笔记的冻结 PDF、正文和本笔记中可读取的交流记录。发送前可检查材料范围、目标电脑和任务内容。PadNote 不会把 PDF 当作已经识别出的文字，也不会把其他笔记或知识库带入任务。

| 能力 | 当前范围 |
| --- | --- |
| 手写与页面 | Android 多页笔记、书写与编辑、缩放、PDF 导入与导出 |
| AI 学习 | 在当前笔记中交流并保留有用结果；使用你已配置的模型服务 |
| 分享纸面 | 导出当前完整笔记 PDF，通过系统分享面板交给其他应用 |
| 电脑 Agent | 将当前笔记 PDF 与可读上下文交给已配对、支持纸面任务的 Hermes 电脑助手 |
| 任务预设 | 继续工作、整理可分享讲义、找理解漏洞并出练习、生成可分享讲解视频 |
| 视频风格 | 可选讲义、手写白板、类比讲解、逐步推导或一分钟复习提示 |

模型、语音合成和视频工具由你或所选 Agent 配置。PadNote 不要求 Qwen，也不把视频预设绑定到某个供应商。通用文件成果可先保存到设备，再用文件管理器分享；视频文件另有应用内播放、保存与分享操作。

## Android 测试版

beta.10 测试包面向 Android 7.0 / API 24 及以上，应用包名为 `com.padnote.android.beta`。它沿用 beta.9 的签名，可覆盖安装；安装前仍建议备份重要笔记。完整安装步骤、电脑助手 ZIP 和测试反馈模板见[测试指南](docs/BETA10_TESTING.md)。

- [下载 beta.10 APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.10/PadNote-Android-0.18.0-beta.10-debug.apk)
- [下载电脑连接助手与测试套装](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.10/PadNote-beta10-test-kit.zip)
- [校验和与发布说明](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.10)

这是调试签名测试版，不是 Play 商店版本。当前真实 Windows/WSL、平板与 Hermes 任务仍需用户测试；测试包中的模拟器和本机回环检查不代表这些环境已完成验收。

## 电脑连接助手

连接助手运行在你自己的电脑上，并将平板任务交给已经安装和配置的 Hermes。助手不附带 Hermes、模型、API 密钥或视频运行时。Windows/WSL2 用户应先看[测试指南](docs/BETA10_TESTING.md)中的网络要求；同一 Wi-Fi、WSL 路由和防火墙设置都可能影响配对。

OpenClaw 目前用于发现与设置引导，尚未接入纸面任务工作流；Codex 也尚未接入。iPad 目前提供源代码，没有可直接安装的 IPA、TestFlight 或 App Store 版本。

## 隐私与费用

笔记默认保存在设备上。发起模型或 Agent 任务时，PadNote 会按发送前显示的范围准备当前笔记材料。外部服务可能产生费用或接收你选择发送的内容；请使用已有授权，并在需要时先检查其服务条款和隐私设置。电脑助手保留在本机的配对和任务数据，不把长期 Hermes 凭据发送到平板。

## 从源码构建

先克隆仓库：

```sh
git clone https://github.com/shiyan688/padnote.git
cd padnote
```

### Android

准备 JDK 17、Android SDK Platform 35、Build Tools 35.0.0 和 NDK 29.0.14206865，配置 `JAVA_HOME` 与 `ANDROID_SDK_ROOT`（或 `ANDROID_HOME`），然后运行：

```sh
tools/build-android-apk.sh
```

脚本使用仓库中的 Gradle Wrapper；首次构建需要下载 Gradle 和依赖。

### iPad

用 Xcode 打开 `ios/PadNote.xcodeproj`，选择 `PadNote` scheme 和 iPad Simulator 后运行。连接真机时，在 Signing & Capabilities 中选择自己的 Apple Development Team。当前没有可直接安装的签名 IPA。

详细步骤见 [iPad README](ios/README.md)。

## 开发与反馈

代码入口：

| 目录 | 内容 |
| --- | --- |
| `android/` | Android 客户端与测试 |
| `ios/` | SwiftUI / UIKit iPad 客户端与测试 |
| `desktop/connection-assistant/` | 电脑连接助手 |
| `docs/` | 接口、格式、隐私与开发说明 |
| `agent-skills/` | 可选的视频讲解 Agent Skill |
| `tools/` | 构建、检查与开发辅助脚本 |

[提交问题或反馈](https://github.com/shiyan688/padnote/issues)时，请写明版本、设备与系统、复现步骤和预期结果。请勿上传 API Key、配对令牌或私人笔记全文。

## 开源许可

Android 与 iPad 客户端的自研代码采用 [MIT 许可证](LICENSE)。视频 Agent 子项目保留 Apache-2.0；第三方组件遵循各自许可。请同时查看 [许可范围](LICENSING.md) 与 [第三方声明](THIRD_PARTY_NOTICES.md)。
