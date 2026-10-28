# PadNote

<img src="assets/brand/PadNote-128.png" width="88" height="88" alt="PadNote 标志">

**在平板上写、画、提问，把纸面上下文分享为 PDF，或交给自己的电脑 Agent。**

PadNote 将笔记、手写与 AI 学习放在同一张纸面上。可用视频讲解等内容作为学习素材，再把整理后的笔记分享给他人或发送给已配对的电脑 Agent。模型和服务由你选择。

[Android beta.18](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.18) · [下载 APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Android-0.18.0-beta.18-debug.apk) · [Windows 安装版](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Connection-Assistant-0.18.0-beta.18-Setup-x64.exe) · [Windows 便携版](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Connection-Assistant-0.18.0-beta.18-Portable.zip) · [Android beta.18 指南](docs/BETA18_TESTING.md) · [Windows 安装说明](desktop/connection-assistant/windows-setup/README-WINDOWS.md) · [从源码构建](#从源码构建) · [反馈问题](https://github.com/shiyan688/padnote/issues)

## Android beta.18

改进 PDF 任务的状态提示、只读后台刷新、任务入口与收件人显示。发送后可返回笔记，之后查看任务；结果不确定时不会自动重复提交，可复制诊断继续排查。安装与测试步骤见 [beta.18 指南](docs/BETA18_TESTING.md)。

## 历史界面截图

以下图片来自早期 Android 模拟器，展示的是 beta.13 界面，不代表 beta.18 的当前界面。

![beta.13 多语言界面](docs/images/beta13-01-languages.png)

![beta.13 纸面工具](docs/images/beta13-02-tool-icons.png)

![PadNote 品牌标志](docs/images/beta13-03-new-brand.png)

## 演示画面

以下精选画面展示示例纸面、任务说明与预设选择。画面来自 beta.17 UI 的低帧率实机截图拼接；笔记正文为人工输入的合成示例，不含 Agent 执行结果。来源与帧率说明见 [演示素材说明](docs/media/2026-10-28/README.md)。

![纸面示例](docs/media/2026-10-28/beta18-paper-promo-poster.png)

![任务预设选择](docs/media/2026-10-28/beta18-task-presets-poster.png)

![纸面 PDF 分享入口](docs/media/2026-10-28/beta18-share-transition.gif)

[观看教程版（36 秒）](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Android-Tutorial-zh.mp4) · [观看简介版（35 秒）](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Android-Promo-zh.mp4)

## 使用方式

在纸面上书写、绘图、编辑并与所选模型交流；分享完整纸面 PDF；或把笔记材料发送给已配对、支持纸面任务的 Hermes Agent。PadNote 不绑定特定模型供应商。电脑连接助手用于配对与 Agent 连接，不附带 Hermes、模型或 API 密钥。WSL 中的 Hermes 需自行提供 Windows 可访问的 API；目前不自动发现 WSL Agent。OpenClaw 提供发现与设置引导；Codex 纸面任务尚未接入。

Android 与 Windows 连接助手当前均为 beta.18。Windows 创建 Hermes run 使用有界的后台等待；其他请求仍使用 10 秒期限，未知结果不会自动重放。iPad 提供部分源码资源，暂无可安装的签名 IPA。

## 下载与校验

Release 提供 Android APK、Windows 安装版与便携版、完整源码及许可文件。下载后可用 `shasum -a 256 <文件>`（macOS/Linux）或 `Get-FileHash <文件> -Algorithm SHA256`（Windows）核对 `SHA256SUMS.txt`。

## 从源码构建

Android 需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0：

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows 安装程序源码与构建前提见 [`desktop/connection-assistant/windows-setup/README-WINDOWS.md`](desktop/connection-assistant/windows-setup/README-WINDOWS.md)。

## 许可与反馈

Android 与 iPad 自研客户端采用 MIT；视频 Agent 子项目采用 Apache-2.0。第三方组件许可见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 与 [`LICENSING.md`](LICENSING.md)。反馈时请提供版本、设备、步骤和界面提示；不要附上 API 密钥、配对令牌、密码或私人笔记。
