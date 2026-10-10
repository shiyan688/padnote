# PadNote beta.10 测试指南

这套测试包用于 Android 平板上的日常试用。按顺序安装 APK、检查已有笔记、体验纸面和 PDF，再决定是否连接你已经配置好的电脑 Hermes。无需编译，也不要求新增付费模型调用。

## 下载与安装

从本次 GitHub Release 下载 `PadNote-Android-0.18.0-beta.10-debug.apk` 和 `PadNote-beta10-test-kit.zip`。APK 支持 Android 7.0 及以上，包名 `com.padnote.android.beta`，与 beta.9 使用相同签名。

1. 如果笔记重要，先在应用内导出备份。
2. 直接安装 beta.10 APK，选择更新/覆盖安装。不要先卸载 beta.9。
3. 打开 PadNote，检查书架、几份已有笔记、页面和附件是否仍在。

## 主要测试

1. **同一张纸上写与交流。** 打开一份笔记，写几笔、缩放并切换页面；在当前笔记中继续 AI 交流，检查页面位置和文字是否仍可编辑。若使用模型，请沿用已配置且已获授权的服务；此步骤不要求新开付费请求。
2. **检查长内容。** 查看较长的 AI 文字，滚动到末尾并调整缩放，确认尾页可读、笔迹和文字没有明显错位。
3. **分享完整纸面 PDF。** 打开当前笔记菜单“分享与交给 Agent”→“分享当前纸面 PDF…”，通过系统分享面板打开或保存 PDF。检查整份页数、背景、手写和排版文字。
4. **准备电脑助手（可选）。** 在 Windows 11 + WSL2 上解压测试套装中的 `PadNote-Desktop-Assistant-beta10.zip`。在安装 Hermes 的同一个 WSL 发行版进入 `PadNote-Desktop-Assistant`，运行 `bash ./start-padnote.sh`，保持终端运行。在电脑浏览器打开终端显示的管理页（默认 `http://127.0.0.1:8766/`），选择现有 Hermes 配置并检查 API Server。助手不会安装 Hermes 或提供模型密钥。
5. **同网配对（可选）。** 平板和电脑接入同一 Wi-Fi。在助手管理页选择 Hermes 实例并生成配对二维码；PadNote 打开“电脑 Agent”扫码，检查目标地址后申请，再回电脑批准。只批准你认识且信任的设备。
6. **发送纸面任务。** 回到同一笔记，打开“分享与交给 Agent”→“交给电脑 Agent…”。选一个支持纸面上下文的 Hermes 目标和任务预设，检查即将发送的 PDF、正文、交流摘要/内容和任务说明，再确认发送。进入任务详情查看进度。
7. **查看成果。** 对通用 PDF 等文件成果选择“保存产物”，再用系统文件管理器打开或分享。视频文件可在应用内播放、保存或分享。若结果页提示需要先保存，请按提示操作。
8. **视频预设（可选）。** 只有所选 Agent 已自行配置合适的视频、模型和 TTS 工具时才测试。可选择并编辑风格提示；工具和供应商由你或 Agent 决定，Qwen 不是必需项。没有配置时记为“未测试”。

## 电脑与网络前提

助手用于转接到你自己的 Hermes，不含 Hermes、模型或凭据。Hermes 需要启用 API Server，并提供任务运行、状态查询和停止能力；仅能在终端聊天不代表这些接口已经可用。Windows 11 + WSL2 用户应让助手和 Hermes 运行在同一 WSL 发行版，并确保平板能访问设备接口 TCP 8767。WSL 2 22H2 及更新版本可参考 mirrored networking；Windows 防火墙规则只应按助手说明限制在专用网络和本地子网。管理页 TCP 8766 仅供电脑本机使用，不要转发或开放到局域网，也不要为测试关闭整个防火墙。

- [Microsoft：WSL 网络](https://learn.microsoft.com/en-us/windows/wsl/networking)
- [Microsoft：Hyper-V 防火墙](https://learn.microsoft.com/en-us/windows/security/operating-system-security/network-security/windows-firewall/hyper-v-firewall)
- [Hermes：API Server](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/api-server.md)

如果连接、能力检查或配对没有出现预期状态，请记录界面提示，不要绕过校验、重复发送同一任务或粘贴密钥到反馈。

## 测试边界与反馈

beta.10 已通过构建、签名以及 Android 35 模拟器的覆盖安装、旧样例笔记保留和系统 PDF 分享检查。这些检查不代表你的平板、Windows/WSL 或真实 Hermes 已验收。iPad 目前没有可直接安装的签名 IPA；OpenClaw 只有发现与设置引导，Codex 尚未接入纸面任务。

请用套装中的 `FEEDBACK.md` 记录设备、步骤、通过/失败/未测试和实际提示。不要发送私人笔记全文、API Key、令牌、设备地址或包含这些信息的截图。
