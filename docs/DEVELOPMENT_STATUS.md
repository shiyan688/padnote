# 开发状态与接手入口

目标：在平板与 AI 在同一张纸上交流学习，方便分享给人，也能把上下文交给自己选择的电脑 Agent。模型、语音和工具由用户或 Agent 选择；当前不建设 PadNote 自有服务器。

## beta.21 的改动

Android 修复 typed-only 文字无法作为 AI 选区，以及混合纸面截图缺少文字流的缺陷。选中具体文字 fragment 或单页套索后，冻结范围、合成文字与原生纸面、裁切/遮罩，并提供异步进度和取消。回调校验笔记、文档修订、选区修订、会话和模型身份；过期结果不能覆盖当前会话。预览与 PNG 相同，跨页范围明确拒绝，初始范围不扩展到整条长文。见 [选区契约](AI_PAPER_SELECTION.md)。

Windows 连接助手继续使用 beta.20，iPad 源码未改。36 段真实合成笔记的保存/重开/缩放及18页PDF完整性已定向验证；捏合焦点受视口边界限制的具体行为仍需观测，不能由正文哈希保持推断焦点稳定。

## beta.20 的改动

保留 beta.19 的正文默认值、分层连接检测和异步任务收据。进一步移除已知任务查询中的连接/实例长锁；停止与审批使用独立两槽持久化后台队列。排队、投递、结果未知、明确未发送分别记录；重启不重放未知审批。两个客户端读取操作响应并保持任务身份与本地版本 CAS；明确未发送才退出停止中。

审批仅从可验证的 canonical pending_controls 与执行代数产生；旧 raw approval 不允许远程批准。信息不足、只含标题或空容器的审批回到电脑处理。HTTP 控制请求要求 receipt header，避免旧客户端忽略异步收据。协议见 [控制操作契约](RUN_CONTROL_CONTRACT.md)。

Android AI 按钮无选区时提供圈选与模型设置引导，支持七语言；打开引导不自动扩大读取范围或发送模型请求。

## 验证与边界

桌面使用隔离合成状态、Fake Hermes、事件阻塞和本机 HTTP 回归；Android 在 h1003 构建、JVM/lint及测试 APK 编译；iPad进行 build-for-testing。实际安装、运行环境与完整学习/Agent往返验证须单独记录，不能以Mock或一次编译代替。

## 后续重点

1. 持续验收真实纸面 → PDF → 所选 Agent → 结果回到原笔记，区分送达与执行；历史不确定任务不重发。
2. 长文分页与缩放、公式与 PDF 尾页、整库备份和跨端恢复；仅用合成资料故障测试。
3. 缺少 Hermes 时的安装配置引导、多 Agent 选择、连接变化恢复与低延迟体验。
4. OpenClaw/Codex真实任务适配；明确协议与能力后再提供执行按钮。当前 OpenClaw 是发现/设置引导，Codex纸面任务尚未接入。
5. iPad签名安装、Windows/iPad本地化、大字号、键盘和屏幕阅读器与笔输入。

实现入口：desktop 的 bridge.py/run_controls.py/hermes.py/state.py/web.py；Android AgentTaskClient/AgentTaskStore/AgentApprovalIdentity；iPad AgentTaskClient/AgentTaskStore。未知写操作禁止自动重试；配置变更不能重定向已接纳请求；撤销后拒绝新操作，既有请求结果仍归原任务。
