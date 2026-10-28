# 开发状态与接手入口

产品目标：在平板上与 AI 在同一张纸上交流学习，方便分享纸面，也能把上下文交给自己选择的电脑 Agent。模型、语音和工具由用户或 Agent 选择。当前不建设 PadNote 自有服务器。

## beta.19 已实现

新建正文默认值与公式入口分开；电脑与 Hermes 检测分开；检测时间独立保存，不从普通状态更新时间推断在线；显式检查最多三秒、被动查询不调用上游；并发检测去重、旧配置结果拒绝；异步任务的上游 POST 不再长期持有连接和实例锁，同一连接可独立接收第二条任务。历史不确定任务禁止自动重发，配对和原任务归属保留。

Android 与 iPad 识别新的检测元数据，旧助手保持兼容。不可用或检查中限制后续提交，已有任务状态和成果查询保留。iPad 尚无签名分发包；桌面 Mock 回归不代替真实 Hermes 完整任务验收。

## 继续推进

1. 完成真实设备上的纸面 → PDF → 所选 Hermes → 结果回传流程，分别记录送达与执行，避免用截图替代执行证据。
2. 继续审查已有任务轮询、停止等网络路径的锁等待、超时和恢复提示；不要只扩大超时来隐藏问题。
3. 用合成资料持续验收长文分页、缩放、公式、PDF 尾页、整库备份与跨端恢复；不得对用户唯一资料做故障注入。
4. 改进连接引导、缺少 Hermes 的安装/配置路径、多 Agent 选择和结果回到原笔记的体验；OpenClaw/Codex 新适配需先明确协议与能力，不假称已可执行。
5. 推进 iPad 签名安装、Windows/iPad 本地化、大字号/键盘/屏幕阅读器与笔输入体验。

## 修改与验证入口

- 电脑助手：`desktop/connection-assistant/padnote_assistant/bridge.py`、`state.py`、`web.py`、`admin_ui.py`。
- 针对性回归：`test_agent_readiness.py`、`test_async_submission.py`、`test_admin_ui.py`。
- Android：`AgentConnectionClient`、`AgentConnectionStore`、`AgentConnectionDialogs` 与 `AgentProtocolTest`。
- iPad：`AgentConnectionClient.swift`、`AgentConnectionStore.swift`、`AgentSettingsViews.swift` 与 `AgentTests.swift`。

检测与任务提交不可调用模型来“测在线”。配置、凭据和实例身份改变后，旧请求结果不得恢复新身份的就绪状态。每个任务持久化后再返回收据，未知提交不得自动重放。提交工作接纳后无法保证被撤回；后续撤销禁止新请求，但已接纳任务继续归属原记录。
