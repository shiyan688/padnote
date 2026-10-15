# PadNote 0.18.0-beta.13 测试指南

## Android

Android 版本为 0.18.0-beta.13-debug，versionCode 52。h1003 集中构建通过；44 个套件中的 329 项 JVM 测试全部通过，0 失败、0 错误、0 跳过。Lint 为 0 错误、138 警告，包含复数提示、未使用资源、图标形状等，未将警告清零作为可用性结论。

构建执行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。APK 支持 arm64-v8a、armeabi-v7a、x86、x86_64；沿用此前测试版签名，证书 SHA-256 为 `484b93ed4dabd71a0708b6f4ae48c665716ed9729b6950b6203ffa4e09e9b9f7`。APK SHA-256 为 `e4271a1a292f8db3b5ba80466526dc49e3d36608f3b4e8ac352a5fec3b72468d`。

Android API 35 平板模拟器已完成七种语言切换、中文书写/套索与 AI 卡片检查；德语 150% 字号竖屏已检查发送按钮、底部状态栏和长标题书架卡片，普通字号也已复查。未发送草稿在关闭/重开卡片及覆盖安装后保留，新建笔记的纸张样式标签已本地化。最终 APK 的三张实际截图随 Release 提供。

本轮没有连接实体平板，没有验证手写笔压、真机兼容性或局域网真实 Agent，也没有发出模型或 Agent 请求。

请在 Android 7.0（API 24）或更新版本安装。覆盖安装前备份重要笔记。默认语言跟随系统；语言切换入口在书架右上角。可检查书写、工具栏功能图标、封面与表单、AI 卡片、纸面分享和语言切换。翻译仍待母语者审核；机器或 Agent 原始错误可能保留原文，模型回答不会自动翻译。

## Windows 与 iPad

Release 中的 Windows x64 安装程序是 beta.11 原包，未包含本轮共享品牌图标更新。其既有 Windows 11 x64 SSH Session 0 验证范围是安装、运行库导入、启动/停止、服务就绪、同版本重装与卸载后数据保留；不覆盖普通桌面鼠标操作、同 Wi-Fi 配对或真实 Hermes 任务。

iPad 仅更新图标源码资源，没有签名 IPA，也没有可安装版本。

## 服务与数据边界

本轮验收不代表外部模型、真实 Hermes Agent 或 OpenClaw/Codex 纸面任务经过验证。模型和服务由用户选择，可能产生费用。分享或发送前确认笔记范围、目标和服务配置。

反馈时请提供版本、设备/系统、复现步骤和实际结果。分享截图或日志前，请移除私人笔记内容、API 密钥和配对令牌。
