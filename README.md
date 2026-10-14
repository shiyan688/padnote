# PadNote

**在同一张纸上写、画、提问，再把完整纸面交给电脑 Agent。**

PadNote 是开源的平板笔记与 AI 学习工具。Android 可在同一份笔记中书写、绘图和交流，导出完整纸面 PDF，或把当前笔记材料交给已配对的 Hermes 电脑助手。

[Android beta.11 测试版](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.11) · [下载 APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.11/PadNote-Android-0.18.0-beta.11-debug.apk) · [Windows 安装程序](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.11/PadNote-Connection-Assistant-0.18.0-beta.11-Setup-x64.exe) · [测试指南](docs/BETA11_TESTING.md) · [源码构建](#从源码构建) · [反馈问题](https://github.com/shiyan688/padnote/issues)

## beta.11 更新

Android 的模型、Agent、导出、任务预设和视频风格选择菜单统一为纸面风格，改善选中状态、多行长标签与触控区域。Windows x64 连接助手提供可直接安装的 per-user setup.exe，并保留已有用户数据。

APK 使用 Android 7.0 / API 24 及以上，包名 `com.padnote.android.beta`，调试签名与 beta.10 相同，可覆盖安装。安装前备份重要笔记。Windows setup 已在 Windows 11 x64 SSH Session 0 验证安装、导入、原生窗口按钮启停、状态、管理服务、覆盖安装和卸载；这不代表普通桌面鼠标操作、同 Wi-Fi 配对或真实 Hermes 任务已验证。安装程序未签名。

## 功能范围与边界

在当前笔记中书写、编辑、缩放并进行 AI 交流；分享完整纸面 PDF；将当前笔记的 PDF 和可读上下文发送给已配对且支持纸面任务的 Hermes Agent。模型、TTS 和视频工具由你或所选 Agent 配置，PadNote 不要求 Qwen。OpenClaw 目前仅提供发现与设置引导，Codex 纸面任务尚未接入。iPad 有源码，暂无可安装的签名 IPA。

电脑助手不附带 Hermes、模型、API 密钥或视频运行时。WSL Hermes 需要已启用且可从 Windows 访问的 API；助手不会自动扫描 WSL。外部模型或工具可能产生费用。发送前检查材料范围、目标和任务。

## 下载与验证

Release 附件包含 Android APK、Windows x64 安装程序、两份演示视频、源码 ZIP、测试指南、许可文件和 `SHA256SUMS.txt`。下载后在 macOS/Linux 使用 `shasum -a 256 <文件>`，Windows 使用 `Get-FileHash <文件> -Algorithm SHA256`，逐项核对校验和。

## 从源码构建

Android 需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0：

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows setup 源码和构建前提见 [`desktop/connection-assistant/windows-setup/README-WINDOWS.md`](desktop/connection-assistant/windows-setup/README-WINDOWS.md)。测试完整步骤见 [`docs/BETA11_TESTING.md`](docs/BETA11_TESTING.md)。

## 许可与反馈

Android 与 iPad 客户端自研代码采用 MIT；视频 Agent 子项目采用 Apache-2.0；第三方依赖许可见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 与 [`LICENSING.md`](LICENSING.md)。反馈时请说明版本、设备、步骤和实际提示。请勿上传 API Key、配对令牌、密码或私人笔记。
