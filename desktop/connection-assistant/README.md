# PadNote 电脑连接助手

把平板上的笔记交给用户选择的本地 Agent，审阅分镜，再取回讲解视频。也可以连接已有的 Hermes，发送文字或任务包、处理审批、查看结果。

助手运行在你自己的电脑上，不需要 PadNote 服务器或 PadNote 账号。视频 Skill 使用调用方 Agent 中由用户选择的模型和语音能力；助手本身不替所有 Agent 提供通用模型/TTS 选择器。现有专用 Qwen 视频适配是可单独启用的可选路径，不是其他 Agent 或 TTS 路径的前置条件。

平板和电脑在**同一局域网，例如同一个 Wi‑Fi**时，可以扫码配对。电脑端保留模型和 Agent 的长期密钥；平板取得独立、可撤销的设备凭据。

以下介绍仓库中的开发预览实现。公开 APK 仍是 beta.6，尚不包含这套电脑助手和完整视频流程。Android 与 iPad 使用同一配对协议；Windows 原生运行包与真实设备仍待验收。

## 视频 Skill：先做一次短任务

1. 在调用视频 Skill 的 Agent 中配置用户选择的模型和语音能力。若明确选择专用 Qwen 集成，按其独立配置启用；不需要为其他 Agent 路径填写 Qwen Key。
2. 运行依赖检查，确认本机 Node、浏览器、FFmpeg 和工作目录可用，再为平板生成配对码。电脑审核设备申请后，平板保存这条连接。
3. 在 PadNote 选择一份短的格式笔记文字稿并发送。当前材料包只含文字快照，原图与手写图形的完整材料包仍待接入。发送外部模型前须遵循用户对内容分享与付费的既有授权。
4. 查看分镜图文并批准当前版本。**批准只记录审阅结果，不启动配音。**
5. 按所选 Agent 的语音路径完成逐场景音频校验，再渲染并验证结果后取回视频、字幕、封面、渲染清单 `render.manifest.json` 和质量报告 `qa-report.json`。iPad 可播放或通过系统分享保存；Android 提供播放、保存到文件及保存后分享；新实现仍待编译和设备验收。任务保留原笔记与材料版本的绑定，当前从任务详情取回成果；原笔记里的视频附件入口仍待完善。

遇到“结果待核对”时，保留原任务并使用“核对结果”。核对不会再次请求模型或配音；不要反复新建相同任务。目前不支持分镜修订，API 会拒绝这类请求。

## Windows 原生视频运行目录

内置视频不要求 WSL 或 Hermes。当前仓库提供原生 Windows x64 打包器，但**还没有经过 Windows 实机验收的可安装包**。在原生 Windows 构建机上，从仓库根目录运行：

```powershell
py -3 tools/package-builtin-video.py
```

生成目录包含 Python、Node、Chrome、FFmpeg、Skill 和启动器。目录中的 `Start-PadNote.cmd` 启动原生助手；它不再选择 WSL 发行版。完整步骤和未完成的原生/许可验收见 [Windows 打包说明](../../tools/WINDOWS_BUILTIN_VIDEO_PACKAGE.md)。该输出仅供本地测试，不能作为已签名或已验证的公开安装包。

同网 HTTPS 默认使用 8767 端口。防火墙仅放行专用网络的局域网来源；管理页 8766 始终留在本机。助手监听 `0.0.0.0` 不代表只监听 Wi‑Fi 网卡，网络范围还需由系统防火墙限制。

## 已有 Hermes：Windows / WSL2

适合已经能在 WSL 终端中使用 Hermes 的电脑。沿用现有安装和模型配置。

1. 在安装 Hermes 的同一 WSL 发行版中打开助手源码目录，运行 `python3 start.py --lan`。需要 Python 3.10 或更新版本，以及系统 OpenSSL。原生视频目录的 `Start-PadNote.cmd` 不用于启动 WSL Hermes。
2. 在打开的电脑管理页中选择检测到的 Hermes 配置，点击“使用这套配置”。助手只在你选择后解析该文件的 API Server 设置，不会执行配置文件或覆盖内容。未自动找到的实例也可手动添加。
3. 检查接口。Hermes 终端能对话但未启用 API Server 时，按照管理页和 [连接教程](../../docs/HERMES_CONNECTION.md) 补充设置并重启 Gateway；随后回助手刷新发现，再次点击“使用这套配置”，然后检测实例。默认本机接口是 `http://127.0.0.1:8642`。
4. 让同一 Wi‑Fi 的平板能连到 WSL（管理员 PowerShell，只需一次）：
   - Windows 11 22H2 及以上：在 `%UserProfile%\.wslconfig` 写入 `[wsl2]` 与 `networkingMode=mirrored`，执行 `wsl --shutdown` 后重新打开 WSL。
   - Windows 10：用端口转发（WSL 地址变化后需重跑）：

     ```powershell
     $wslIp = (wsl hostname -I).Split(" ")[0]
     netsh interface portproxy add v4tov4 listenport=8767 listenaddress=0.0.0.0 connectport=8767 connectaddress=$wslIp
     ```

   - 两者都需只对“专用网络”放行 8767，并把当前 Wi‑Fi 设为专用网络：

     ```powershell
     New-NetFirewallRule -DisplayName "PadNote LAN" -Direction Inbound -Protocol TCP -LocalPort 8767 -Action Allow -Profile Private -RemoteAddress LocalSubnet
     ```

   - WSL mirrored 网络还可能受 Hyper‑V 防火墙控制。按 [Microsoft WSL 网络指引](https://learn.microsoft.com/windows/wsl/networking)，只为 8767 建立规则，并把下面示例网段改成自己的局域网网段：

     ```powershell
     New-NetFirewallHyperVRule -Name "PadNote-WSL-LAN" -DisplayName "PadNote WSL LAN" -Direction Inbound -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' -Protocol TCP -LocalPorts 8767 -RemoteAddresses "192.168.1.0/24" -Action Allow
     ```

5. 在管理页“4. 生成平板配对”的“同一个 Wi‑Fi（推荐）”里选实例和本机局域网地址，生成二维码。PadNote 中扫码或粘贴配对内容，核对地址后申请，回电脑点击“刷新待批准设备”，核对后批准。

`--lan` 启动时，助手额外在 8767 端口提供**只含设备接口**的 HTTPS。证书由助手首次启动时自己生成（私钥只在电脑的数据目录里），证书指纹写进配对二维码，平板只信任这一张证书，不依赖域名或证书机构。访问仍需一次性配对码、电脑上的显式批准和逐设备令牌。删除数据目录里的 `lan-cert.pem` / `lan-key.pem` 会轮换证书，所有平板需重新配对。

电脑管理页默认为 `http://127.0.0.1:8766`，**始终只在本机**，不会出现在局域网上。

助手和 Hermes 应运行在同一个 WSL 发行版中，任务文件才能直接供 Hermes 使用。WSL、Hermes 和助手都需保持运行；电脑休眠后不会继续响应平板。

### 可选：平板不在同一个网络时

跨网络连接不是必需的。确实需要时，可以自行使用 Tailscale 等私人网络：两端登录同一私人网络，在 Windows PowerShell 先查看 `tailscale serve status`，确认没有占用同一入口的其他服务后运行 `tailscale serve --bg --https=443 http://127.0.0.1:8765`，再在管理页“平板不在同一个网络时”填入输出的 HTTPS 地址生成二维码。**Serve 只转发 8765**，不要转发管理页 8766。

## macOS / Linux

使用 Hermes 时，在运行 Hermes 的同一台电脑启动助手：macOS 使用 `Start-PadNote.command`，Linux 使用 `start-padnote.sh`。需要 Python 3.10 或更新版本。随后按同样的管理页流程操作。

开发视频 Skill 集成时，还需先安装 Skill 锁定的 npm 依赖及本地浏览器/FFmpeg，使用 Node 22.22 或更新版本，并将实际 Node 路径和 Skill 目录传给助手，例如：

```sh
python3 start.py --lan --video-node /absolute/path/to/node --video-skill-root /absolute/path/to/padnote/agent-skills/padnote-video-explainer
```

也可从终端运行：

```sh
python3 start.py --lan
```

`--lan` 开启同网连接（默认端口 8767，可用 `--lan-port` 改）；不加时只监听本机回环，适合只走 Tailscale 等外部入口的场景。`--no-browser` 不自动打开浏览器；`--state-dir` 可指定独立数据目录。默认数据目录为 `~/.local/share/padnote-connection-assistant`。管理页与回环设备接口端口可分别用 `--admin-port`、`--api-port` 指定，二者始终仅监听本机回环。生成证书需要系统里有 `openssl`（macOS 与常见 Linux 发行版自带）。

同一个数据目录一次只能由一个助手进程使用；通过符号链接指向同一目录也视为同一个目录。第二个进程会在读取状态前退出并提示先关闭正在运行的助手。助手使用操作系统文件锁，正常退出或进程被终止后会自动释放；目录中的 `.owner.lock` 是固定锁入口，请勿删除，也无需用它判断进程是否仍在运行。助手不会因锁冲突删除状态、配对或凭据。

## 多个 Agent

同一个助手可以登记视频任务目标和多个 Hermes 实例，每个实例单独配对。另一台电脑运行自己的助手，再在平板添加连接即可。实例名称可以相同，授权仍按独立身份隔离；建议使用能区分电脑和用途的名称。

每次配对产生独立的设备凭据。Hermes 的长期 API 密钥不发给平板。电脑端可以撤销某个设备对某个实例的访问，不影响其他连接。平板删除连接不等于撤销授权，也不会自动停止电脑上的任务。

Hermes 从已选择配置文件加载的凭据，在重启时会按记录复核。Hermes 手动输入的密钥只保留在当前进程，重启后需再次填写同一密钥。若单独启用专用 Qwen 视频集成，其 Key 使用管理页显示的系统安全存储策略；不能持久保存时也只在本次启动有效。API 地址或密钥改变后，助手会停用旧实例；重新选择配置或填写新密钥会建立新身份，需要重新配对。旧授权和旧任务不能自动继承新身份。

## 先验证一次小任务

对 Hermes 新连接，可在 PadNote 发送“只回复：连接成功，不要操作文件”。收到结果后，再尝试笔记任务包。视频 Skill 不接受普通聊天，请按上方短笔记流程验证。任务结果页保留任务来源；中断后通过原任务查询或重试，不要反复新建相同任务。

当前开发源码支持 `note.work.v1` 纸面任务包：包含整份纸面 PDF、可读正文和本笔记的可见 AI 交流，交由选中的 Hermes 执行用户填写的要求。助手检查纸面任务能力、来源修订、文件大小与摘要；旧连接需重新检查能力才能在平板的纸面发送入口中出现。用户可配置多个 Hermes，单次发送选一个目标。视频是通用任务的一种预设，风格可编辑，不自动启动专用视频执行器。完整步骤与验收范围见[核心流程](../../docs/CORE_PRODUCT_WORKFLOW.md)。

旧视频任务包保留 `video.explain.v1` 格式，并绑定选定笔记的快照。视频 Skill 的模型和 TTS 由调用方 Agent 配置决定；外部 Hermes 任务仍需要该实例具备相应 Skill、TTS 和渲染工具。专用 Qwen 适配仅适用于明确启用该集成时。文字回复、上传成功和分镜批准都不等于视频已经完成。

## 当前范围

- 源码实现视频任务的分镜、精确批准、本机渲染及成果取回；模型与 TTS 选择属于用户配置的 Agent，助手不提供通用聊天或任意电脑工具执行。
- 保留 Hermes 配对、文字及任务包提交、状态查询、一次性审批、停止、产物下载与逐连接撤销。
- 使用状态轮询；没有承诺实时逐字输出。已记录的任务和终态可在助手重启后保留。
- OpenClaw 目前只做发现与设置引导，任务协议尚未接入。Codex 尚未接入。
- 文件传输限制在当前任务目录，校验路径、大小和摘要；这不等于 Hermes 的工具执行已处于操作系统沙箱。Hermes 仍使用其自身的权限和审批配置。
- 同网连接只支持局域网私有 IPv4 地址；没有自动发现，二维码是唯一入口。跨网络需自备 Tailscale 等工具。
- Windows 原生执行、WSL2 网络、扫码相机及真实 Hermes/平板任务仍需实际设备验收。Mac/Linux 离线跨层测试不证明 Windows 包可用。

开发协议见 [CONNECTION_ASSISTANT_PROTOCOL.md](../../docs/CONNECTION_ASSISTANT_PROTOCOL.md)，测试范围见 [AGENT_CONNECTION_QA.md](../../docs/AGENT_CONNECTION_QA.md)。配对二维码在本机生成，第三方许可保存在 `vendor/`。
