# PadNote 电脑连接助手协议 v1

状态：2026-09-24 实现中，尚未发布。该协议仅在用户电脑上运行，跨网使用用户已有 HTTPS/私网入口，不要求 PadNote 自有服务器。

## 网络与身份

- 两个独立回环监听：电脑管理 UI 默认 `127.0.0.1:8766`，设备 API 默认 `127.0.0.1:8765`。只把设备 API 端口通过 Tailscale Serve 暴露。API 端口绝不提供管理路由；不能以转发后的 loopback 地址判断管理员身份。
- 管理 UI 验证 Host、Origin 和 CSRF，密钥字段写入不回显。设备 API 禁止浏览器跨源请求，要求 JSON 与应用凭据；不依赖 CORS 代替鉴权。
- 正式平板连接只允许 HTTPS，根地址禁止 userinfo/query/fragment；手动地址可有固定路径前缀。拒绝重定向；测试夹具可显式注入本地 transport，不能在生产全局放开 HTTP。
- Bridge 有持久随机 `bridge_id`，每个注册 Agent 有独立 `instance_id`；设备获授权仅限所选实例，每个实例分别发一枚 32 字节以上随机 Bearer，服务端持久化摘要。原生后端凭据不返回平板。
- API 路径均相对设备 API 的 HTTPS 根地址，前缀 `/padnote/v1`。请求/响应 UTF-8 JSON，错误 `{"error":{"code":"...","message":"可显示的脱敏说明"}}`，禁止回显原始凭据/请求正文。

## 配对

桌面选择实例、填写外部 HTTPS 根地址后，产生 5 分钟有效的高熵单次码。二维码内容是以下 JSON；同一内容可复制粘贴，作为没有扫码能力时的入口。数据解析只读，需显示电脑和地址后由用户发起连接。

```json
{"type":"padnote-pair","version":1,"url":"https://computer.example.ts.net","bridge_id":"uuid","code":"opaque-high-entropy-code"}
```

1. `POST /padnote/v1/pair/request`：`{code, device_id, device_name}`。成功 202：`{request_id, poll_token, expires_at, status:"pending"}`。单次码原子消费，绑定申请设备和桌面选中的实例。限频、有界 pending 队列，失败不得泄露码是否对应某个实例。
2. 电脑管理 UI 显示申请设备和所选 Agent，用户同意/拒绝。批准前不签发访问凭据。
3. `POST /padnote/v1/pair/claim`：`{request_id, poll_token}`。202 仍等待；403 拒绝；410 过期/已领取。200：`{bridge_id, device_id, connections:[{instance_id, kind:"hermes", name, token}]}`。kind 使用小写 `hermes` / `openclaw`；仅已实现的协议进入可操作连接。申请/领取凭据不得写入日志，领取后销毁临时配对材料；响应丢失时重新配对，旧未领取连接可在电脑撤销，不能把已签发token作为普通查询返回。
4. 平板以新 UUID 保存每一连接，transport=`bridge`、endpoint=原 HTTPS 根地址，instanceId/bridgeId 分别保存。多条保存失败要明确显示未完成条目，不能清空原有连接。取消配对需停止轮询；撤销已配对连接由电脑完成，平板删除配置不声称撤销成功。

## 连接检测

`GET /padnote/v1/agents/{instance_id}/capabilities`，Bearer 为该设备该实例的 token。返回：

```json
{"object":"padnote.agent.capabilities","protocol_version":1,"bridge_id":"uuid","instance_id":"uuid","kind":"hermes","name":"家用电脑 Hermes","features":{"run_submission":true,"run_status":true,"run_stop":true,"run_approval_response":true,"task_bundle":true,"artifacts":true}}
```

能力反映真实上游检查和当前已实现功能；不宣传未知/未实现的能力。该响应不等于视频 Skill/TTS/渲染依赖已可用。OpenClaw 本轮先检测和引导，其原生配对/任务适配未完成时不得签发可执行连接。

## 任务

- 首期采用有界状态轮询，避免三端各自猜测 SSE 恢复语义；SSE 为后续可选能力，不因缺少 SSE 假报离线。前台 2–3 秒刷新，失败退避；切后台/关闭页停止轮询，重开按持久任务ID恢复。保留用户手动刷新。
- `POST /padnote/v1/agents/{instance_id}/runs`，Bearer + `Idempotency-Key: <client_task_id>`。JSON：`{client_task_id, title, input, source:{note_id, note_revision}, bundle_base64?, bundle_sha256?}`。客户端先持久化任务与不可变 payload，再提交；网络结果不明使用同一 key 与完全相同正文重试。
- input 最多 128 KiB，title 最多 256 字符；可选 ZIP 原始大小最多 8 MiB，整个请求最多 12 MiB。素材必须来自用户明确选定的当前内容快照，不能后台上传整本笔记。
- Bridge 将任务绑定到服务端签发的 connection_id/实例，创建随机任务目录。device_id 为客户端自报信息，只作显示关联，不能授予旧配对的任务访问权限；重新配对不自动继承旧 connection_id 的任务。ZIP 只接受 `request.json`、`input/manifest.json`、`input/content.md` 和空 `work/.keep`、`output/.keep`，拒绝其他条目、重复路径、绝对路径、符号链接、越界、过大解压及哈希不符；校验后写入专属目录，不直接 extractall。
- ZIP 中的 `request.json` 沿用已有 `video.explain.v1` 契约，必须保留原内容，不能用 Bridge 的网络提交正文覆盖。验证其 source.note_id/note_revision 与本次提交对应，并核对 manifest 和内容摘要。Bridge 提交信息可另存 `work/padnote-submission.json`；两种 task_id 各有自己的命名空间，不要求相等。
- Bridge 调用 Hermes `POST /v1/runs`，只传 input（含已确认的说明、任务目录位置）、可选 session_id/instructions；Hermes 不支持直接上传此 ZIP。助手须与 Hermes 文件系统互通，WSL 场景优先在同一 distro 运行。不能把提示词路径限制称为 OS 沙箱。
- Bridge 使用带服务端 connection_id/实例命名空间的确定幂等键调用上游，持久化原始 payload 摘要、状态和映射后再发起调用；同键异文返回 409。结果不明保留 submitting，超过上游幂等保留窗口不自动重发。
- 返回 202：`{task_id, instance_id, status:"running"}`。task_id 为 Bridge 的不透明任务ID，不能直接暴露远端 run ID 为授权依据。
- `GET /padnote/v1/agents/{instance_id}/runs/{task_id}` 返回 `{task_id,instance_id,status,output?,error?,approval?,artifacts:[]}`。标准状态：submitting / running / waiting_for_approval / stopping / completed / failed / cancelled / interrupted。终态对账后持久化，避免上游短时保留导致历史丢失。
- `POST .../runs/{task_id}/stop`：空 JSON，请求后显示 stopping，最终 cancelled 只能由上游状态确认。
- `POST .../runs/{task_id}/approval`：`{approval_id, decision:"once"|"deny"}`。只接受当前任务当前待审批ID；禁止任意工具全局 allow。已核验 Hermes 固定版本 `c3e01c753dcbb0e881e693df89fe4141b79b2b03`：上游待审批字段为 `approval.request_id`，上游 POST 正文必须是 `{request_id: approval_id, choice: decision}`，不是把 Bridge 的字段直接转发。只支持 once/deny，显式拒绝 all/resolve_all/session/always；上游缺少 request_id 时显示等待电脑处理，不发无绑定审批。
- 规范化待审批对象为 `{approval_id, title, description}`；只从当前上游 `approval.request_id` 构造 ID，title/description 为受长度限制的纯文本（可引用已脱敏 command/reason），不可当 HTML/指令执行。
- 每项路由先校验 device token 与 instance、任务的服务端 connection_id owner 一致。旧响应、删除连接、改地址与同名远端 run 不得更新另一任务；客户端按 connectionId、连接修订、task_id 匹配。离线任务包仍可自由导出，不依赖任何连接。

## 产物

Bridge 仅从当前任务的 `output/` 收集允许类型的普通文件：Markdown/text、PDF、PNG/JPEG、MP4、PPTX，拒绝符号链接和目录越界。响应条目 `{id,name,media_type,size_bytes,sha256}`；id 不包含文件路径。

`GET .../runs/{task_id}/artifacts/{id}` 在相同授权下返回二进制。单件默认最大 100 MiB，任务目录总量默认 256 MiB；有限枚举/读取，客户端下载到临时文件，校验大小和SHA256后再提供预览/保存，不跟随产物给出的外部URL，也不自动执行或写回笔记。上游 output 文本作为可读结果先返回；文件生成取决于所选Agent的Skill/工具实际能力。

## 第一轮验收

使用不含真实密钥的本地假 Hermes 验证：发现、请求/电脑批准/领取、两实例独立授权、幂等提交、跨设备/实例拒绝、状态恢复、停止、审批过期、产物路径与哈希、撤销即时生效、重启、超时。客户端采用同一套JSON夹具；Android/iPad 构建和数据迁移测试通过后，再于用户 Windows/WSL2 + 平板做真实端到端验收。假服务与模拟器不能替代真实 Agent、Windows 网络及真机体验证据。

## Hermes 核验来源

固定版本的 [任务路由源码](https://github.com/NousResearch/hermes-agent/blob/c3e01c753dcbb0e881e693df89fe4141b79b2b03/gateway/platforms/api_server_runs.py#L772-L820) 是 approval 字段依据；[API Server 文档](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server)说明运行状态与幂等保留期。新版本须重新协商能力；不能仅因接口名称相似就复用。
