# 连接另一台电脑的 Hermes

在电脑上启动 Hermes，再把它的 HTTPS 地址和连接令牌填入 PadNote。下面以 Windows / WSL2 为例；应用内也提供同样内容的离线教程。

## 当前能做什么

本版支持检查 Hermes 服务、令牌和接口能力。测试通过后可导出讲解视频任务包，交给电脑上的 Agent。自动发送任务、查看执行进度、处理审批和接收结果尚未实现。OpenClaw 当前也未接通。

## 1. 准备电脑和平板

电脑的 WSL2 中应已安装并配置好 Hermes，能够在终端正常对话。Windows 和平板分别安装 Tailscale，登录同一私人网络，并保持连接。

下文使用 Tailscale Serve 提供 HTTPS 地址；它只在你的 Tailscale 网络内共享服务。如果已有平板可访问、证书受信任的 HTTPS 入口，可跳到第 6 步。

## 2. 在 WSL 里准备连接令牌

打开安装 Hermes 的 WSL 终端。已有 API Server 时，使用其现有 `API_SERVER_KEY`，不要重复生成。首次配置可运行下面的命令生成随机令牌，将输出保存在自己的密码管理器中：

```sh
openssl rand -hex 32
```

这个令牌用于访问 Hermes，与模型厂商的 API Key 分开。

## 3. 在 WSL 里启用 API Server

编辑 `~/.hermes/.env`，保留原有配置，新增或更新下面四项。把占位文字换成第 2 步的令牌。默认使用 8642 端口；如果更改端口，后面的命令也要一起更改。

```dotenv
API_SERVER_ENABLED=true
API_SERVER_KEY=替换成你的随机令牌
API_SERVER_HOST=127.0.0.1
API_SERVER_PORT=8642
```

使用自定义 `HERMES_HOME` 或 profile 时，请编辑该实例实际使用的配置文件。

## 4. 在 WSL 里启动 Hermes

在同一个 Hermes 环境中运行：

```sh
hermes gateway
```

若 Gateway 已在运行，需在当前任务结束后重启它以加载新配置。确认启动日志中出现 API Server 和 8642 端口；测试期间保持这个 WSL 终端和电脑运行。

## 5. 在 Windows PowerShell 里检查

下面的命令在 Windows PowerShell 中执行，不是在 WSL 中：

```powershell
curl.exe --max-time 10 http://127.0.0.1:8642/health
```

正常应返回包含 `status` 的 JSON。若连接被拒绝或超时，先确认 WSL 中的 API Server 正在运行，以及 Windows 能通过 localhost 访问 WSL 服务；先解决这一步，再配置 HTTPS。

## 5a. 在 Windows PowerShell 里取得 HTTPS 地址

确认 Windows 已安装并连接 Tailscale，再运行：

```powershell
tailscale serve --bg --https=443 http://127.0.0.1:8642
tailscale serve status
```

首次使用时按终端提示启用 HTTPS。复制输出中以 `https://` 开头、通常以 `.ts.net` 结尾的完整地址，保留输出中可能出现的端口号。Serve 会在后台运行；Hermes 仍需保持运行。如提示权限不足，以管理员身份打开 PowerShell 后重试。

## 6. 回到 PadNote 填写

关闭应用内教程，选择“Hermes Agent（HTTPS）”。地址填写第 5a 步的 HTTPS 地址；令牌只填 `API_SERVER_KEY` 的值，不带 `API_SERVER_KEY=`、引号或 `Bearer` 前缀。点击“保存并测试”。

地址不要额外追加 `/v1` 或 `/chat/completions`。PadNote 会自行检查 `/v1/capabilities`。

## 地址和令牌分别从哪里来

- 地址来自 Tailscale Serve 的输出，或你自行配置的 HTTPS 服务入口。
- 令牌来自运行中的 Hermes 实例的 `API_SERVER_KEY`。
- 平板里的 `localhost`、`127.0.0.1` 指向平板自身，不能代表另一台电脑。
- WSL 的 `172.x` 内部地址也不是本教程使用的地址。

## macOS / Linux 怎么操作

Hermes 的配置内容和启动命令相同。把上面的电脑端步骤放在运行 Hermes 的那台电脑上执行；健康检查用 `curl` 替换 Windows 的 `curl.exe`。Tailscale 也安装在这台电脑和平板上。macOS / Linux 无需 Windows 到 WSL 的 localhost 转发步骤。

## 连接失败时先看这里

- **HTTP 401 / 403**：核对正在运行的实例与令牌，修改配置后重启 Gateway；同时确认服务的访问权限。
- **超时 / 找不到主机**：检查电脑、Hermes 和两端 Tailscale 是否在线，地址是否抄完整。
- **HTTP 502**：检查第 5 步，HTTPS 入口可能无法访问 WSL 中的服务。
- **证书错误**：使用 Serve 输出的 HTTPS 域名或受信任的证书，不能只把 `http` 改成 `https`。
- **HTTP 404 / 响应不是 Hermes**：检查地址是否多加了 `/v1`，是否指向了模型接口或网页仪表盘。
- **缺少能力**：服务已响应，但当前 Hermes 版本与 PadNote 的接口要求不匹配。这类错误需要核对两端版本，反复更换令牌没有作用。

## 停止这次共享

如需关闭本教程建立的 HTTPS 入口，在 Windows PowerShell 中执行：

```powershell
tailscale serve --bg --https=443 off
```

它会关闭该设备 443 端口的 Serve 入口；若已将同一入口用于其他服务，请先查看 `tailscale serve status`。关闭 PadNote 或点击“断开”只清除应用连接信息，不会停止电脑上的服务。

## 官方资料

- [Hermes API Server](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server)
- [Hermes 环境变量](https://hermes-agent.nousresearch.com/docs/reference/environment-variables)
- [Tailscale 下载](https://tailscale.com/download)
- [Tailscale Serve 配置](https://tailscale.com/docs/reference/tailscale-cli/serve)
- [Microsoft WSL 网络](https://learn.microsoft.com/en-us/windows/wsl/networking)
