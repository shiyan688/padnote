# 多 Agent 与连接助手验收

范围：Android 0.18.0-beta.7、iPad 0.18.0 build 45、电脑连接助手预览版。开发验证使用临时数据和假 Hermes，不读取真实 Agent 令牌、不触发模型调用。

## 已有验证证据

- Android 最终快照：69 项 JVM 测试通过，0 失败/错误/跳过；lint 0 错误、7 项警告（DefaultLocale 2、SetTextI18n 5）；debug APK 构建和原生测试编译通过。Android 原生测试尚未运行。
- iPad：18 项 AgentTests 模拟器测试通过，0 失败；arm64 Release 无签名设备构建成功。测试覆盖迁移失败恢复、凭据更新/删除后跨进程清理、独立任务存储、固定提交身份、能力校验、审批、重定向拒绝和任务包摘要。没有可直接分发安装的签名 IPA。
- 助手 11 项单元测试通过，覆盖配置/密钥改变后旧授权不得调用新后端、等待期间撤销授权的二次校验、持久化失败不发布内存授权、重配隔离、真实任务包校验、超大文件先拒绝后计算摘要及文件遍历数量上限。
- `tools/tests/test_connection_assistant_http.py` 的 7 项独立 HTTP 集成测试通过。它实际启动独立管理端口、设备端口和假 Hermes，经管理页表单发起操作，没有直接跳过配对和授权步骤。
- 任务包夹具由当前 Android `VideoTaskBundleIO.java` 实际运行产生，见 `tools/tests/fixtures/agent-video-task-android.md`。验证了原 `video.explain.v1` request、清单与笔记版本不会被网络提交正文覆盖。
- 浏览器已目检电脑管理页的布局与入口。Windows 启动器尚未在 Windows 上运行，扫码相机尚未真机验收。

独立 HTTP 测试覆盖：

1. 管理端口与设备路由隔离，Host、Origin、CSRF 与显示内容转义。
2. 两个实例使用不同凭据；即使平板自报相同 device_id，也不能读取另一次配对的任务；撤销 A 不影响 B。
3. Android 任务包经过真实 HTTP 提交，中文 Markdown 产物下载后逐字节一致，大小与 SHA-256 相符；重开助手保留终态。
4. 同键同正文重试返回同一任务，同键不同正文拒绝；当前审批 ID 按 Hermes 的 request_id/choice 原样转换；停止后查询确认取消。
5. 四个并发相同提交只创建一个任务和一次上游提交。
6. 上游已接受但返回损坏 JSON 时保留“结果待确认”，使用相同身份重试不会创建第二个上游任务。
7. 超出上游 24 小时幂等窗口的未知提交不会自动重新执行。

从完整源码运行独立验收：

```sh
python3 tools/tests/test_connection_assistant_http.py
python3 -m unittest discover -s desktop/connection-assistant/tests -t desktop/connection-assistant -v
```

这些命令需要允许本机回环端口。它们不需要互联网、模型 API Key 或真实 Hermes。拒绝监听端口属于测试环境限制，不能记为网络协议已通过。

Android 交付 APK 的 SHA-256 为 `f6e11c45c821fe0722646a8071681b8aba3f5b30ef4af4f1bf37e04afe1f8ee4`，2,278,354 字节，包名 `com.padnote.android.beta`，versionCode 46。v2 签名证书 SHA-256 仍为 `484b93ed4dabd71a0708b6f4ae48c665716ed9729b6950b6203ffa4e09e9b9f7`，与先前 beta 连续。签名核验说明升级身份匹配，不代替真机覆盖安装和数据迁移验收。

102 项 Android 源码及构建文件与隔离构建目录逐项 SHA-256 一致。清单与测试日志保存在本地交付目录 `dist/agent-connect-beta7/verification/`，其中源码清单聚合摘要为 `a5e8f1f83d9364daa354a9622565c3df145632bb32797ccc9b5f1fd30bcdecdd`。

## Windows / WSL2 与平板验收

在正式宣传完整连接能力前，使用用户实际的 WSL Hermes 逐项记录结果：

- 启动助手选择正确发行版；从已安装 Hermes 中选择配置，确认没有改写原 .env、模型和已有会话。
- WSL 内接口检查、Windows localhost、Tailscale HTTPS 三层分别可达；平板看到的是 HTTPS 电脑地址。
- Android/iPad 相机扫码、拒绝相机权限后的粘贴入口、过期码、拒绝配对、电脑批准后保存连接。
- 添加同机第二个 Hermes 和另一台电脑；修改默认后，旧任务保持原目标；删除连接后历史可读，电脑任务不会被误报为停止。
- 发一个不操作文件的小任务并取回文字。发送真实笔记任务包，核对本次选中内容与修订；安装视频 Skill 和 TTS 后另行验证完整视频流程。
- 触发一次真实审批，核对具体动作、仅本次允许与拒绝。停止任务后等远端返回终态。
- 提交时断网、电脑休眠、WSL 退出、重启两端；未知提交只通过原任务重试，已接受任务可恢复查询。
- 下载中文文件和较大文件，校验失败时不提供损坏文件；前后台切换停止/恢复轮询。
- 从 beta.6 升级：原连接、笔记和文件保留，迁移失败不清空旧凭据；Android APK 签名保持一致。

## 尚未覆盖的能力

OpenClaw 和 Codex 的任务协议未接入。SSE、视频分镜的专用审阅界面、操作系统级 Agent 沙箱、Windows 签名安装器和 iPad 可分发签名包仍需后续工作。文件范围限制和提示词不等于进程沙箱；本地假服务测试也不能证明任意 Hermes 版本或设备均兼容。
