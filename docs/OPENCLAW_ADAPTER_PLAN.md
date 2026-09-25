# OpenClaw 适配前核对

核对日期：2026-09-24。本文记录下一阶段的接口边界，不代表PadNote已经能向OpenClaw提交任务。当前会话功能仍在实现，真实Windows/WSL2与设备验收另行安排。

## 已核对的官方约束

- 交互客户端采用Gateway WebSocket的`operator`角色；`node`用于向Gateway提供设备能力，不等同于平板聊天客户端。[协议入口](https://docs.openclaw.ai/gateway/protocol)
- 设备身份参与挑战签名；配对成功后保存Gateway授予的设备令牌。不能自己拼造一个所谓设备令牌，或只输入总令牌就声称已完成逐设备配对。[认证协议](https://docs.openclaw.ai/gateway/protocol/auth)
- 当前官方客户端指南标注`2026.8.1`包和wire协议4；客户端与Gateway需要成对验证。重连应重新获取权威历史、当前run与订阅；附件同时受解码大小和实际帧大小限制。[客户端指南](https://docs.openclaw.ai/gateway/clients)
- 普通operator客户端要求精确协商当前wire版本；不能套用node的兼容窗口并宣传所有旧Gateway都能连接。[版本规则](https://docs.openclaw.ai/gateway/protocol/versioning)
- `operator.write`及`operator.read`并非每个普通用户的隐私隔离层。共享Gateway的控制权限不能当作不可信多租户隔离；强隔离需要独立Gateway和OS身份。[权限边界](https://docs.openclaw.ai/gateway/operator-scopes)

## PadNote 下一阶段方案

以下为根据上述接口作出的实现建议，尚未实装。延续现有电脑助手：由助手承担OpenClaw适配，平板沿用逐设备、逐Agent的PadNote配对，不复制电脑上的Gateway总凭据。每个连接档案明确绑定Gateway地址、实例、授权设备身份、Agent及协议版本；不自动把失败请求转发给另一个Agent。

首期只请求聊天和确实已实现的审批权限，不申请管理配置或设备的权限。配对仍显示具体设备和请求；电脑上批准后重新检查授予范围。它能减少用户输入，不能跳过Gateway的授权要求。

实现顺序：版本与能力检测 → 挑战签名及配对/撤销 → 发消息并持久记录任务/run身份 → 断线恢复与审批对账 → 附件与结果预览。视频所需Skill、语音、渲染依赖单独检查，不能根据聊天成功就开放“生成视频”。

验收应包含协议不匹配、配对待批准、权限收紧、设备撤销、重复提交、断线时审批变化、run事件乱序/缺口、附件超限及结果来自错误连接。先用协议夹具验证，再对明确版本的真实Gateway验证；未联调版本列为未知。

在真正增加依赖或实现适配时重新核对官方版本与schema，并保留精确锁文件和兼容矩阵。上述版本号只是此次文档核对值，不是当前项目已安装或已测试的依赖。
