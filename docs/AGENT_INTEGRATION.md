# OpenClaw / Hermes 对接边界

PadNote 把 OpenClaw 和 Hermes 视为用户电脑上的外部 Agent。两者只在 PadNote 的上层任务模型中统一，底层连接保持各自官方协议，避免用“OpenAI-compatible”假装覆盖 OpenClaw 的设备配对和 Gateway 状态机。

## 在应用中配置

Android 书架和 iPad 侧栏的“电脑 Agent”设置页提供“如何连接另一台电脑？”离线教程，支持复制命令与分享全文。Windows / WSL2 步骤以及 macOS / Linux 补充见 [Hermes 连接教程](HERMES_CONNECTION.md)。两端教程共用 `android/app/src/main/assets/agent-connection-guide.json`，iPad 工程直接将同一文件打包为资源。

“连接测试通过”只表示服务身份、令牌和接口能力检查通过。当前应用不会向电脑自动发送任务或接收执行结果；OpenClaw 选项尚未接通。

2026-09-24 兼容性核验：客户端和探针检查 `features.run_approval_response`，与 [Hermes 官方源码](https://github.com/NousResearch/hermes-agent/blob/c3e01c753dcbb0e881e693df89fe4141b79b2b03/gateway/platforms/api_server.py#L54-L76)的能力标志一致；`run_approval` 是其端点表中的名称。实际连接仍需核对目标实例的 `/health`、`/v1/capabilities` 与协议版本。beta.6 尚未通过真实 Hermes 实例验收。

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

## PadNote 上层任务契约（下一阶段）

两类适配器最终都只向 PadNote Controller 暴露以下状态：

```text
submit → running → approval_required → completed | failed | cancelled
```

首个垂直任务固定为 `note.transform`：输入是用户明确确认的选区 PNG、最小笔记上下文和目标说明；输出只接受声明 MIME、字节数和 SHA-256 的 Artifact（首批 Markdown、PPTX、MP4）。远端返回物先进入暂存区，不直接修改笔记；只有用户预览并批准后，PadNote 才把 Artifact 与 source anchor 关联。

暂不开放：任意远程命令、远端直接调用 PadNote 写工具、后台上传整本笔记、自动覆盖已有文字或笔迹。OpenClaw 的 `operator.write` 与 Hermes 的真实 run 提交都等真实实例完成 Phase 0 后再申请/启用。

## 尚未验证

仓库内测试使用离线 Hermes HTTP 假服务和 OpenClaw WebSocket 假 Gateway，验证协议形状与状态流。Android 与 iPad 设置页已实现；尚未连接用户的真实 OpenClaw/Hermes 实例，任务提交、SSE/事件消费和 Artifact 回传仍未实现。Windows / WSL2 教程按官方文档核对，尚待用户在其电脑和平板上验证。
