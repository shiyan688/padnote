# OpenClaw / Hermes 对接边界

PadNote 把 OpenClaw 和 Hermes 视为用户电脑上的外部 Agent。两者只在 PadNote 的上层任务模型中统一，底层连接保持各自官方协议，避免用“OpenAI-compatible”假装覆盖 OpenClaw 的设备配对和 Gateway 状态机。

## 在应用中配置

Android 书架和 iPad 侧栏的“电脑 Agent”设置页提供“如何连接另一台电脑？”离线教程，支持复制命令与分享全文。Windows / WSL2 步骤以及 macOS / Linux 补充见 [Hermes 连接教程](HERMES_CONNECTION.md)。两端教程共用 `android/app/src/main/assets/agent-connection-guide.json`，iPad 工程直接将同一文件打包为资源。

“连接测试通过”只表示服务身份、令牌和接口能力检查通过。beta.7 源码新增多连接、扫码配对、Hermes 文字任务和助手任务包收发；公开 beta.6 仍只有连接检查。OpenClaw 的任务协议尚未接通。实现协议见 [电脑连接助手协议](CONNECTION_ASSISTANT_PROTOCOL.md)，验证边界见 [连接验收](AGENT_CONNECTION_QA.md)。

2026-09-24 兼容性核验：客户端和探针检查 `features.run_approval_response`，与 [Hermes 官方源码](https://github.com/NousResearch/hermes-agent/blob/c3e01c753dcbb0e881e693df89fe4141b79b2b03/gateway/platforms/api_server.py#L54-L76)的能力标志一致；`run_approval` 是其端点表中的名称。实际连接仍需核对目标实例的 `/health`、`/v1/capabilities` 与协议版本。beta.6 尚未通过真实 Hermes 实例验收。

## 连接助手与多 Agent（2026-09-24 首版实施）

保留两种入口：用户手动填写已有 Agent 地址/凭据，或由电脑端 PadNote 连接助手检测本机 Agent、按类型引导连接。暂不建设自有服务器或公网中继；跨网可复用 Tailscale 私网。连接助手及其 Bridge 运行在用户电脑上。

beta.7 将旧单连接迁移为独立档案，保存多台电脑和同类多个实例。电脑助手位于 `desktop/connection-assistant/`，先实现 Hermes；OpenClaw 目前只检测和引导。首期状态采用轮询，SSE、跨 Agent 自动协作及视频分镜专用审阅界面仍是后续工作。下列边界同时约束首版和后续扩展，不能视为所有目标已通过真实设备验收。

### 用户操作

1. 电脑助手列出检测到的 Agent 实例，包括系统/WSL 环境、类型和可连接状态。发现候选后，由用户选择要开放给平板的具体实例；无法识别的实例允许手动添加。
2. 助手按实例走相应流程：Hermes 检查 API 和能力，OpenClaw 标为仅检测；其官方设备配对与权限适配尚待实现。未安装、未启动、待授权和缺少任务能力分别提示，检测成功不显示为“任务已可用”。
3. 平板“添加 Agent”提供扫码与手动填写。扫码可授权同一电脑上的选定实例；电脑以后新增 Agent 不自动扩大已有授权。
4. 连接列表显示名称、Agent 类型、电脑/实例、能力与最近检查结果。支持同机多种 Agent、同类多个实例/profile，以及多台电脑。可以设默认值，发送前仍显示实际目标。
5. 会话和任务固定关联创建时的 Agent；多个 Agent 可各自保持会话与在途任务。切换默认只影响新任务，离线任务等待重连或由用户另建任务，不能自动跨 Agent 重投。跨 Agent 自动分工另行设计。

### 数据与协议边界

| 对象 | 身份与职责 |
| --- | --- |
| 电脑 | 助手的已验证身份与网络入口；显示名称不是身份凭证。手动直连无助手时不虚构已验证电脑身份。 |
| Agent 实例 | 类型、所在环境/profile、服务身份与能力；同一类型可以有多个实例，不以类型名唯一化。 |
| 连接档案 | 稳定 `connectionId`、实例引用、显示名称、地址、认证方式、凭据引用、授权范围、配置修订与检查时间。地址变更不等于身份可信地延续。 |
| 会话/任务 | 固定连接与实例引用、材料快照、幂等键、远端 run ID、审批和事件恢复位置；远端 ID 只在所属连接内解释。 |

离线导出的任务 ZIP 保持可移植，连接/认证绑定只属于在线任务记录，不把凭据写入交换文件。

手动直连与助手连接使用同一档案/任务模型，以认证方式和传输适配器区分。Hermes 与 OpenClaw 保留各自原生协议，向上提供检查、提交、事件、审批、停止和产物接口；没有实现的能力明确缺席，不伪装成可执行按钮。

助手对本地 Agent 进行有边界的发现：读取已知安装位置/配置元信息，检查已知服务的健康与能力；候选服务响应不作为可信指令，也不为探测运行真实模型任务。启用接口、调整网络或绑定凭据时展示对应实例与改动，避免扫描时自动接管所有 Agent。

助手配对使用短时单次信息，绑定电脑身份、请求设备与选定实例。Hermes 长期密钥留在电脑，Bridge 为设备到具体实例建立独立授权；撤销某一实例不影响其他实例。OpenClaw 保留原生设备身份、配对、权限和撤销语义，不能用一个全权共享令牌代替。助手只代理已登记的目的服务，不把客户端任意 URL 当转发目标。

凭据按连接隔离保存在 Android Keystore 加密存储 / iOS Keychain 中。探针、SSE、审批、停止、产物响应都携带不可变的连接/实例上下文；配置修改或连接删除后，晚到响应不能更新另一档案。删除配置保留历史来源信息，不等同于服务端撤销或停止任务。

### 迁移与验收

旧单连接迁移为首条档案。保留地址、类型和凭据，旧 `connected` 布尔只代表过去检查结果；新记录与凭据回读成功后才完成迁移，过程幂等、可中断恢复，失败仍保留旧配置。

至少覆盖：同机不同类型、同类不同实例、不同电脑、相同远端 run ID、切换默认时在途任务、单连接离线/撤销、改地址、晚到探针、重启恢复、迁移中断与单凭据损坏。验收必须证明 A 的密钥、材料、审批和结果不会进入 B。

多连接存储、任务归属和 Hermes 助手已进入 beta.7 预览版。下一步使用真实 Windows/WSL2 Hermes 验收，再推进 OpenClaw 适配；每种 Agent 的兼容性都需要各自真实实例的完整验证。

## Phase 0：协议探针

当前可执行入口：

```bash
PADNOTE_AGENT_URL=https://agent.example.com \
PADNOTE_AGENT_TOKEN='本地凭据' \
tools/run-agent-probe.sh hermes

PADNOTE_AGENT_URL=wss://gateway.example.com \
PADNOTE_AGENT_TOKEN='gateway bootstrap token' \
tools/run-agent-probe.sh openclaw
```

- Hermes：读取 `/v1/capabilities`，确认 `run_submission`、`run_status`、`run_events_sse`、`run_stop` 和 `run_approval_response`。只提供 Chat Completions 的旧实例会明确拒绝，不能冒充 Agent 后端。
- OpenClaw：执行 v4 WebSocket `connect.challenge → connect → hello-ok → health`；设备签名采用 Ed25519 v3 payload，只申请 `operator.read`。首次连接可能返回 `PAIRING_REQUIRED`，探针会打印设备 ID 和 requestId，需用户在电脑端审核后再次运行。
- OpenClaw 设备身份保存在 `tools/agent-probe/state/openclaw-device.json`，权限为仅当前用户读写，并被 Git 忽略。删除它会产生一个新设备和新的配对请求。
- 携带 token 时只允许 HTTPS/WSS 或回环地址，地址禁止内嵌账号、查询串和 fragment。探针不发送笔记内容。

## 任务契约与后续扩展

两类适配器最终都只向 PadNote Controller 暴露以下状态：

```text
submit → running → approval_required → completed | failed | cancelled
```

beta.7 支持 Hermes 文字任务，以及经电脑助手发送现有 `video.explain.v1` 笔记任务包。输入在提交前固定为快照；文件回传检查任务归属、大小和 SHA-256，用户选择保存位置，不自动修改原笔记。视频第一阶段先生成分镜，完整视频仍依赖电脑上的 Skill、TTS 和渲染工具。

后续 `note.transform` 可接受选区 PNG、最小笔记上下文和目标说明，并将预览后批准的产物关联到原位置。这项纸面回写流程尚未实现。

本版没有远端调用 PadNote 写工具、后台上传整本笔记或自动覆盖原笔记的接口。Hermes 会使用电脑上已有的工具权限执行用户提交的任务，助手未提供操作系统级沙箱。OpenClaw 的任务权限及执行接口尚未接入。

## 尚未验证

仓库内测试使用本机 Hermes HTTP 假服务和 OpenClaw WebSocket 假 Gateway。Hermes 提交、轮询、审批、停止和助手产物回传已有实现及测试；SSE 和 OpenClaw 任务适配尚未实现。尚未连接用户的真实 Agent，Windows / WSL2 启动与网络、平板扫码及完整视频流程仍需实机验证，具体范围见 [连接验收](AGENT_CONNECTION_QA.md)。
