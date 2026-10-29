# PadNote Windows 连接助手（x64）

候选构建：`2026-10-29-beta28-r1`（PadNote 0.18.0-beta.28）。助手按普通用户运行。

Windows 本机验收可在解压的候选目录或安装目录运行 `runtime\python.exe verify_windows_startup.py --report NATIVE_VERIFIER_REPORT.json`。请使用普通、未提权的终端。工具使用全新临时数据和空闲端口，检查证书创建/复用、HTTP/TLS、正常退出，并记录临时数据清理结果；不操作现有配对、SSH或防火墙。它会拒绝将管理员身份运行当作普通用户验收。

这是按当前用户安装的 Windows x64 连接助手。安装包自带私有 CPython 运行时，不要求另装 Python、WSL、Node.js、Chrome 或 FFmpeg。安装后可从开始菜单启动图形状态窗口，再点击“启动”运行助手；管理页面保持在 `http://127.0.0.1:8766/`。平板与电脑连接同一 Wi-Fi 后，可使用 TLS 配对二维码。

图形窗口提供启动、打开连接设置、停止、Hermes 一键连接和 Hermes 安装 / 配置入口，并提供明确的“允许同 Wi-Fi 连接”防火墙操作。Hermes 一键连接会在助手就绪或可启动时启用；需要安装时先确认官方来源、安装位置和模型登录要求，已安装时只打开 `hermes setup`。安装和启动均不会改防火墙。点击防火墙按钮后，Windows 会显示 UAC 提示；确认后只为专用网络、本地子网、TCP 8767 添加入站规则，不会开放管理端口或设备 API 端口。若以后要删除规则，可在“Windows Defender 防火墙（高级安全）”的入站规则中删除 **PadNote Connection Assistant (8767)**。

Windows 原生版沿用现有 Agent 发现逻辑：只检查 Windows 的 `HOME` 和 `PATH`，尚未实现自动发现 WSL 中的 Agent。WSL Agent 若已通过本机可访问的 API 对外提供服务，可在管理页面手动配置。此安装包不会自动连接所有 Agent。运行中的连接助手只会接受同网 TLS 配对，不会把 API 或管理页开放到局域网。

助手数据保存在 `%USERPROFILE%\.local\share\padnote-connection-assistant`，与既有默认目录一致。升级和卸载会保留该目录；卸载只移除安装包列出的文件、快捷方式和自身安装目录中的空目录。数据可能包含设备凭据、配对状态和已配对设备正在使用的 TLS 身份。请先备份，再手动清理。

安装不需要管理员权限，默认创建开始菜单快捷方式，可选创建桌面快捷方式。安装结束页会启动图形状态窗口，服务需用户点击“启动助手”后才运行。停止操作先通过本次启动专属的 Windows 事件请求服务正常关闭，超时后才结束本启动器拥有的子进程。启动窗口会显示当前状态和连接步骤。若启动失败，窗口保留并显示原因、退出码和下一步建议；“诊断信息”区域提供可复制、可保存的脱敏诊断，并可直接打开日志文件夹。重新打开窗口时会立即载入已有的当前和上一份日志，不需要先重试；导出优先保留本次运行日志，并包括助手版本、Windows 版本与 build、Python 版本。日志位于 `%USERPROFILE%\.local\share\padnote-connection-assistant\launcher.log`，最近一次日志保留为 `launcher.log.previous`。若端口被占用，助手会提示检查端口占用；不会按端口查杀进程，也不会打开未经确认的本地管理页。启动超过 90 秒仍未报告就绪时，窗口会提示检查诊断，但保留进程供慢速设备继续启动。停止请求在后台等待最多 8 秒，之后只结束本启动器创建的进程。

## 构建

在 Linux 或 macOS 使用 Python 3.10+ 和 NSIS 编译器 `makensis`。构建器先从已验证的归档缓存读取固定版本与 SHA-256 一致的文件；找不到时才从脚本中的 HTTPS 官方地址下载。以下命令从集成后的 `desktop/connection-assistant/windows-setup/` 目录运行，并直接打包相邻的助手源码及项目许可文件：

```sh
python3 build_setup.py --stage-only --assistant-root .. --project-root ../../.. \
  --download-cache /path/to/pinned-archives
python3 build_setup.py --makensis /path/to/makensis --assistant-root .. \
  --project-root ../../.. --download-cache /path/to/pinned-archives
```

第一条命令生成可审阅的 `build/stage/`，不调用 NSIS；第二条命令生成 `build/PadNote-Connection-Assistant-0.18.0-beta.28-Setup-x64.exe`。构建脚本不会全局安装 Python 包或 NSIS。NSIS 可用 `--makensis` 指定绝对路径。beta.28候选的实际构建、签名状态及Windows原生安装/升级/卸载验收情况，以该轮冻结构建与验证回执为准；本说明不替代这些结果。

固定运行时输入：CPython 3.13.15 embeddable x64（python.org，SHA-256 `d1f04d990aee1253d8569e8e5104e30fa9f5fa830899f14843448872d936a2cf`）；cryptography 50.0.1（`55d16b1ef3ee0958d893a977b19777887e546c9954ea81b200c3301a864013f2`）；cffi 2.1.1（`1aa5645c30469b09530c4ebca77ebf8f17618293c58f8549cb1a543a50236e7d`）；pycparser 3.0（`b727414169a36b7d524c1c3e31839a521725078d7b2ff038656844266160a992`）。精确下载地址见 `build_setup.py`，依赖锁见 `requirements-win.lock`。

助手包保留管理页面当前导入的可选视频服务 Python 模块，但不包含视频 Skill、Node、Chrome、FFmpeg 或模型/语音运行时。第三方许可清单在 `THIRD_PARTY_NOTICES.md`；上游许可文件随运行时保留。
