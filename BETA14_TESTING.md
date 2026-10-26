# PadNote beta.14：Windows 电脑连接助手

本次更新电脑助手。Android 继续使用 [beta.13](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.13)，安装包与版本号未改。

## 这次改了什么

- 调整 Windows 启动窗口的字体、颜色、按钮层级和日志区域；就绪后可打开连接设置。
- 重做连接设置页：Agent 卡片、清楚的状态和下一步、同网配对引导、自动更新连接申请。
- 生成二维码时明确目标 Agent。切换 Agent 后隐藏旧码，过期后提示重新生成；备用配对内容折叠显示。
- 启动失败可在窗口查看、复制或保存诊断，并直接打开日志目录。重试保留上一份日志，停止服务时窗口保持响应。
- 修正普通用户创建 TLS 身份时的权限设置顺序：先保留当前用户与 SYSTEM 授权，再移除继承并验证最终权限。
- 修正管理页表单可能被误拒绝的问题，保留 Host、Origin 与 CSRF 校验。

## 下载与使用

从 [Release](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.14) 下载：

- `PadNote-Connection-Assistant-0.18.0-beta.14-Setup-x64.exe`：Windows x64 安装程序，自带 Python 和依赖。安装程序未签名。
- `PadNote-Connection-Assistant-0.18.0-beta.14-Portable.zip`：解压后使用。双击 `runtime\pythonw.exe` 不会直接打开助手；请在解压目录运行 `runtime\pythonw.exe launcher.py`。
- `PadNote-Source-0.18.0-beta.14.zip`：完整公开源码快照。

正常使用以普通用户启动。安装版从开始菜单打开 **PadNote Connection Assistant**，点击“启动助手”，就绪后点击“打开连接设置”。更新前正常关闭旧助手窗口。升级保留现有数据与配对信息。

在连接设置页添加或导入 Hermes 配置，检测通过后选择目标 Agent，生成同网二维码。平板和电脑使用同一网络；平板提交申请后，在电脑核对设备和目标 Agent，再批准连接。

手动填写的 Hermes 凭据仅在本次助手进程内使用；重启后可重新填写，或导入可读取的本机配置。当前自动发现不扫描 WSL；WSL 中的 Hermes 需先提供 Windows 可访问的 API 地址。OpenClaw 目前只提供发现与引导，尚不能作为可执行任务的连接目标。

## 请优先测试

1. 以普通用户启动，是否仍出现“启动期间退出”；失败时诊断能否直接复制或保存。
2. 启动、停止、再次启动，以及关闭窗口、重新打开后查看最近日志。
3. 添加、填写凭据、检测；多个 Agent 之间切换时，选择项和二维码目标是否一致。
4. 平板扫码、申请自动出现、批准连接、单独撤销设备。
5. 系统 125%/150% 缩放、较小窗口、键盘操作；文字和按钮是否完整可读。

反馈时附上助手导出的诊断与复现步骤，隐藏密钥、配对码和私人笔记。

## 可选：让本机 Agent 自动检查启动

在安装或便携目录，以普通、未提权的终端运行：

```powershell
.\runtime\python.exe .\verify_windows_startup.py --report .\PadNote-Windows-Verification.json
```

脚本使用全新临时数据与空闲端口，检查实际 Windows ACL、首次与再次启动、HTTP/TLS、正常退出和临时数据清理，不操作原有配对与配置。它不会验证图形窗口、剪贴板或真实平板。

## 已验证与待验证

64 项相关回归通过；安装器已由 NSIS 构建，安装/便携载荷与冻结源码及校验清单核对通过。本机真实浏览器已检查添加、填写凭据、检测、二维码目标切换、过期隐藏、批准、撤销，以及自动更新保留未提交草稿。浏览器检查使用合成 Agent 和设备数据。

新版本的 Windows 普通用户实际启动、原生窗口、高 DPI、安装升级，以及真实平板与 Hermes 联调，仍需本轮测试反馈。iPad 目前没有签名安装版。

## 界面

以下为本机浏览器实屏，使用合成测试数据；二维码仅为示例。

![Agent 管理](https://raw.githubusercontent.com/shiyan688/padnote/v0.18.0-beta.14/docs/images/beta14-01-agents.png)

![平板配对](https://raw.githubusercontent.com/shiyan688/padnote/v0.18.0-beta.14/docs/images/beta14-03-pairing.png)
