# PadNote

<img src="assets/brand/PadNote-128.png" width="88" height="88" alt="PadNote 标志">

**和ai在同一张纸上写、画、提问，再分享或交给电脑 Agent。**

PadNote 是面向平板的笔记与 AI 学习工具。你可以在同一份笔记中书写、绘图和提问，再分享纸面或交给已配对的电脑 Agent。模型和服务由你选择。

[Android beta.17](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.17) · [下载 APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.17/PadNote-Android-0.18.0-beta.17-debug.apk) · [Windows 安装程序](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.16/PadNote-Connection-Assistant-0.18.0-beta.16-Setup-x64.exe) · [便携版 ZIP](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.16/PadNote-Connection-Assistant-0.18.0-beta.16-Portable.zip) · [Windows 测试指南](docs/BETA16_TESTING.md) · [Android beta.17 测试指南](docs/BETA17_TESTING.md) · [源码构建](#从源码构建) · [反馈问题](https://github.com/shiyan688/padnote/issues)

Android 模拟器实录，图中文字为本地界面示例。

![多语言界面](docs/images/beta13-01-languages.png)

![纸面工具图标](docs/images/beta13-02-tool-icons.png)

![新品牌标志](docs/images/beta13-03-new-brand.png)

## beta.13 更新

Android 界面新增简体中文、英语、日语、韩语、西班牙语、法语和德语，并统一 21 个功能图标与新品牌标志。默认语言跟随系统，也可在书架中切换。翻译尚未经过母语者逐条审核；少数机器或 Agent 返回的原始错误仍可能保留原文，模型回答不会随界面语言翻译。

同时修复大字号下发送按钮裁字、底部状态重叠和长标题书架按钮遮挡。未发送草稿按笔记保留，关闭卡片后可继续编辑。

Windows 连接助手在 beta.16 更新。iPad 本轮仍只有图标源码资源，没有可安装的签名 IPA。Android 安装范围、构建结果和实测项目见 [Android beta.13 测试指南](docs/BETA13_TESTING.md)；Windows 实测范围见 [beta.16 测试指南](docs/BETA16_TESTING.md)。安装程序未签名。

## 功能范围与边界

在当前笔记中书写、编辑、缩放并进行 AI 交流；分享完整纸面 PDF；将当前笔记材料发送给已配对且支持纸面任务的 Hermes Agent。模型与服务由你或所选 Agent 配置，PadNote 不要求特定供应商。OpenClaw 目前提供发现与设置引导，Codex 纸面任务尚未接入。iPad 有源码，暂无可安装的签名 IPA。

电脑助手不附带 Hermes、模型、API 密钥或视频运行时。WSL Hermes 需要启用可从 Windows 访问的 API；助手不会自动扫描 WSL。外部模型或工具可能产生费用。发送前检查材料范围、目标和任务。

## 下载与验证

本 beta.16 版本提供 Windows 安装程序、便携版 ZIP、源码 ZIP、测试指南、许可文件和 SHA-256 校验和。Android beta.13 仍可从原版本下载。

下载后在 macOS/Linux 使用 `shasum -a 256 <文件>`，Windows 使用 `Get-FileHash <文件> -Algorithm SHA256` 核对校验和。

## 从源码构建

Android 需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0：

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows 安装程序源码和构建前提见 [`desktop/connection-assistant/windows-setup/README-WINDOWS.md`](desktop/connection-assistant/windows-setup/README-WINDOWS.md)。

## 许可与反馈

Android 与 iPad 客户端自研代码采用 MIT；视频 Agent 子项目采用 Apache-2.0；第三方依赖许可见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 与 [`LICENSING.md`](LICENSING.md)。反馈时请说明版本、设备、步骤和实际提示。请勿上传 API Key、配对令牌、密码或私人笔记。

### Windows beta.15（2026-10-27）

[下载 Windows 安装版、便携版与源码](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.15)。本版修复任务提交状态关联与未知结果防重复，增加已有 Hermes 发现/绑定、一键检查启动和首次连接教程；未安装 Hermes 时提供固定官方安装脚本的可见确认入口。API/服务配置与模型登录仍需用户完成，模型供应商由用户选择。更新前请停止并关闭旧助手；安装会保留配对与设置。Android 继续使用 beta.13。

### Windows beta.16（2026-10-27）

本版补上助手尚未启动时的一键启动与 Hermes 连接；保留 beta.15 的任务状态修复、防止不确定提交重复执行和连接教程。Android 继续使用 beta.13。

### Android beta.17（2026-10-27）

更新笔记向电脑 Agent 发送 PDF 任务时的目标卡片选择、发送阶段与后台状态。发送后可返回笔记或离开页面；任务处理可能需要电脑端时间。结果未知时不会自动重发，可复制诊断继续排查。Windows 电脑助手继续 beta.16。
