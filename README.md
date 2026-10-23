# PadNote

**在 Android 平板上写笔记、圈选难点，让 AI 讲解并把有用内容留在纸面。**

适合一边看讲义、一边手写推导和提问。可以新建笔记，也可以导入 PDF 批注；普通书写不需要模型或电脑 Agent。AI 使用你配置的服务，费用由所选服务商收取。

[下载 Android APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.13/PadNote-Android-0.18.0-beta.13-debug.apk) · [第一次使用](docs/QUICK_START.md) · [常见问题](docs/FAQ.md) · [English](README.en.md) · [反馈](https://github.com/shiyan688/padnote/issues)

## 先完成一份学习笔记

1. **写或导入**：新建笔记写几行内容，或用“导入笔记”打开 PDF。
2. **圈选**：用套索选中一小块内容，点 AI，核对卡片中的选区图片。
3. **配置模型**：AI 卡片 → 设置 → 新建配置。首次建议直连多模态，填写配置名称、HTTPS API 地址、支持图像输入的模型及自己的 API Key。预设可修改，模型可用性与价格以服务商为准。
4. **讲解与保留**：需要落在纸面时先选“允许写入”，再点“讲解”；只想阅读时选“仅卡片”。确认发送材料和地址，检查回答，保存笔记。AI 写入 PDF 笔记时使用空白附注页。

这条流程不需要安装 Windows 连接助手、Hermes 或视频工具。更详细的操作、配置失败排查和材料范围见[快速指南](docs/QUICK_START.md)。

![Android 平板中的纸面工具栏](docs/images/beta13-02-tool-icons.png)

截图来自 beta.13 Android 模拟器，画面中的文字是本地界面示例，不是这轮真实模型回答的验收。

## 下载前了解

| 项目 | 当前状态 |
| --- | --- |
| Android | 可下载 beta.13 调试签名 APK，Android 7.0 / API 24 起；属于测试版 |
| iPad | 提供源码，需要 Xcode 构建并自行签名；没有可安装的签名 IPA |
| AI | 自备支持图像输入的 OpenAI-compatible 服务；AI 非离线能力，不附送模型额度 |
| 电脑 Agent | 可选进阶流程；配置并配对受支持的 Hermes 目标后使用 |
| 数据 | 笔记默认保存在设备；调用模型会发送确认范围内的材料；没有自动云同步 |

公开下载仍为 [v0.18.0-beta.13](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.13)。本轮源码新增的首用快捷入口、首次配置直接打开表单和配置表单滚动尚未打包发布，下载 APK 不会自动包含这些变化。

beta.13 有七种界面语言。已有验收记录包含 Android API 35 模拟器界面检查和 329 项 JVM 测试；该轮没有实体平板、真实模型或真实 Agent 请求。完整范围见[测试记录](docs/BETA13_TESTING.md)，不能据此推断触笔手感或每家模型均已验证。

## 后续再试

- 将纸面导出为标准 PDF 分享；用可编辑笔记包在设备间手动转移。
- 将笔记转为 Markdown 格式笔记，按需选择为 AI 额外材料。
- 通过“分享/Agent”交给已配对、支持纸面上下文的 Hermes 目标。OpenClaw 当前提供发现与设置引导，Codex 纸面任务尚未接入。见[核心流程](docs/CORE_PRODUCT_WORKFLOW.md)。

[Windows beta.11 连接助手](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.11/PadNote-Connection-Assistant-0.18.0-beta.11-Setup-x64.exe)是可选组件，不附带 Hermes、模型、API Key 或视频运行时，安装程序未签名。WSL Hermes 需暴露 Windows 可访问的 API；不自动扫描 WSL。

## 验证下载与源码构建

覆盖安装前备份重要笔记。使用 Release 中的 `SHA256SUMS.txt` 核验下载：Windows 用 `Get-FileHash <文件> -Algorithm SHA256`，macOS/Linux 用 `shasum -a 256 <文件>`。

Android 构建需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0，以及项目指定的 NDK 29.0.14206865：

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

本轮改动与本机检查范围见[首用改善验证](docs/FIRST_USE_VALIDATION.md)。Windows 构建见[安装程序说明](desktop/connection-assistant/windows-setup/README-WINDOWS.md)。

## 许可与反馈

Android 与 iPad 客户端自研代码采用 MIT；视频 Agent 子项目采用 Apache-2.0；第三方组件见 [许可范围](LICENSING.md)和[第三方声明](THIRD_PARTY_NOTICES.md)。反馈请说明版本、设备、步骤及实际提示，勿上传 API Key、配对令牌或私人笔记。
