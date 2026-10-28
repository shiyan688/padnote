# 任务停止与审批控制契约

## 已确认的问题

已用事件测试复现旧任务 GET 占住连接/实例锁，拖住其他任务提交；候选20已先修读取路径，保持锁获取顺序、忙时返回本地状态、上游查询三秒与返回前授权复核。

旧审批在 HTTP 超时后没有持久化投递记录，后续可能再发相同决定。旧停止也只有 stopping 状态，没有明确的一次投递记录。另一个实际协议风险：Windows当前审查过的旧Hermes API代码没有使用传入 request_id 来选择审批。不能把“客户端传了ID”当作服务端已经绑定。

当前官方 [API文档](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/api-server.md) 明确：canonical 运行返回 pending_controls 与 execution_generation，审批需要精确 request_id、execution_generation、choice；旧身份拒绝。当前官方 [处理代码](https://github.com/NousResearch/hermes-agent/blob/main/gateway/platforms/api_server_authority_runs.py) 也校验该组合。兼容旧API不能猜测其绑定能力或发送“测试审批”来探测。只允许本次能验证身份的 canonical 审批；旧格式仍显示说明，但在电脑处理，不伪称平板审批已支持。

## 审批身份

规范化审批仅从严格的 canonical pending_controls 中产生。执行代数必须是非布尔正整数且不超过2^53-1；prompt_id必须非空、无首尾空白及控制字符。使用内部不透明 token `pc1:<generation>:<prompt_id>`，UTF-8总字节数不超过256，沿用现有客户端持久化字段。它包含完整的代数和原ID，不靠哈希猜回目标。

Bridge把token作为既有approval_id与当前保存的审批核对。HermesClient在发送前解码，POST体严格为 request_id/执行代数/choice。旧 raw approval 不能通过该发送路径。单纯在 GET 后发送旧请求ID仍有竞态，不能作为旧服务的原子绑定保证。Direct模式也必须遵守同样绑定，不能绕过Bridge保护。

## 持久化记录

每个run新增可选 control_actions 字典，旧run缺失时视为空，不主动改写无控制记录的旧任务。最多128条，key为stop或approval加token的SHA256。记录严格只含 kind、approval_id、decision、state、attempt_id、requested_at、updated_at，不存密钥、原始错误或模型内容。

状态为 queued、sending、unknown、confirmed、rejected、not_sent、superseded。queued先落盘，再启动工作线程；sending先落盘，再做一次上游请求。unknown/confirmed/superseded不会再发，重复相同请求只返回原记录，变更已接纳的审批决定被拒绝。只有明确未发送或上游明确拒绝的操作可由用户重新发起。重启时 queued→not_sent、sending→unknown，不自动恢复网络写入。

## 执行与锁

停止与审批统一经两槽后台队列，立即返回202持久化收据。独立于45秒模型提交线程，避免模型接口繁忙挡住停止操作。两槽满时明确拒绝且不联系Hermes。

接纳时在连接/实例短锁内捕获固定客户端与身份；工作前再次验证授权与身份。获取任务锁必须 nonblocking；忙时释放所有连接/实例锁，再在外部有界等待后重试。绝不持任务锁回头等待连接锁，锁顺序始终 connection→instance→task。保持任务锁进行网络，释放前两种锁。等待上限15秒，上游控制请求10秒；关闭助手等待已接纳线程，超时保留状态目录所有权。

工作开始前核对最新task、run ID、审批token/decision和attempt_id；目标已变或任务已结束时不发写入。接纳后的配置变化不可重定向旧客户端；撤销先于实际工作接纳时不联系上游，已经接纳的网络不能被撤回，结果仍归原任务。

## 返回与客户端

复用既有任务状态。排队/发送/未知的停止显示 stopping 与准确提示；尚在执行不能伪称已取消。审批已排队或结果未确认时，公共projection暂不提供可再次点击的approval_id，并显示解释；同一ID未知时继续查询，不重新批准。新的canonical审批ID/代数产生新的独立决定。

Android/iPad应使用控制POST返回的任务projection立即更新本地记录，不能忽略响应后直接把操作当成功；已有身份修订/远端task ID CAS保持。Direct请求必须用经过验证的canonical token，旧服务给出回电脑处理的说明。

Android原状态机禁止STOPPING回到RUNNING；排队后的控制若明确未发送/被拒绝，这条规则会让UI永久卡住。公共projection增加严格枚举stop_delivery。只有Bridge报告not_sent/rejected/superseded且完整任务身份通过验证，客户端才能恢复为活动状态；unknown/confirmed仍继续等待，不用一次普通running响应取消停止意图。控制收据额外核对本地记录更新时间，忽略已被更新状态超越的旧收据。

为避免旧Android忽略收据后无条件标STOPPING，异步控制API要求客户端发送 `X-PadNote-Control-Receipt: 1`。未声明的控制请求在认证后明确拒绝并提示升级，且不入队、不联系上游；普通提交和状态查询不受影响。capabilities的顶层run_control_receipts_version=1声明这项扩展，不增加授权。新平板向旧助手发送该头是无害的，仍按同步回执解析。新版停止/审批需配套升级平板；不得悄悄让旧客户端陷入新增的不可恢复状态。

新增控制元数据仅作明确状态信息，不授予额外权限。所有回归使用合成任务，不触碰两个真实未知PDF，不运行模型或自动重启Agent。
