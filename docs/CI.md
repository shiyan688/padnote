# CI 与维护验收

选择 TODO 26 的 GitHub Actions 路线。`Verify` 为每个 PR 运行五组独立检查；签名和发布不在此工作流中。

| 检查名称 | 实际执行内容 | 不代表 |
| --- | --- | --- |
| Source and assistant | 源码静态检查、构建脚本夹具、助手单元、回环 HTTP 集成 | 真实 Hermes / WSL 联调 |
| Android JVM, lint and test compilation | JVM、Agent probe、Lint、应用与 instrumentation 编译 | 原生测试已运行 |
| Android API 35 native tests | 一次性 Linux 模拟器运行全部 instrumentation | 手写延迟、相机或真机升级通过 |
| iPad simulator tests | 可用 iPad 模拟器运行单元和 UI 测试 | Apple Pencil 或签名分发通过 |
| Video worker contracts | 锁定依赖安装、TypeScript、协议与子进程测试 | 已生成真实配音视频 |

所有 job 只授予 `contents: read`，checkout 不持久保存 Git 凭据，不引用仓库 secrets，不使用 `pull_request_target`，也不在用户的常用电脑上执行外部 PR。第三方 Actions 固定完整提交；这些提交于 2026-09-24 从各自官方 tag 核对。Dependabot 每周提出更新，不能自动绕过回归检查。

托管 runner 镜像仍会更新，因此日志记录工具版本，runner 标签指定 `ubuntu-24.04` / `macos-26`。这不是完全可重现构建的证明；发布追溯另按 TODO 25 验收。可用标签依据 [GitHub 官方 runner 清单](https://github.com/actions/runner-images)。

## 本地配置完成后仍需远端核验

这些步骤需要仓库管理员权限。当前文档不声称后台设置已经启用。

1. 将工作流发布到仓库，确认五组检查实际产生结果。记录失败日志，修复后复跑；不要把未运行的 job 加成“通过”。
2. 对主分支启用 ruleset 或分支保护，把上述五个名称设为必需检查，要求 PR 审查。通过临时 PR 放入一个必然失败的测试，确认合并被拒绝，再撤掉该测试；删除工作流不是通过验收。
3. 启用私密漏洞报告。用另一个有报告权限的账号确认入口可见、报告保持私密，仓库维护者能收到通知并回复；不要拿真实漏洞做公开演示。
4. 核对 fork PR 和 Dependabot PR 无法取得签名、模型、Agent 或发布凭据，记录检查时间与维护者。
5. 每次新增子项目、协议或需要原生运行的测试，把它加入相应 job。每周查看依赖更新；协议变更和数据迁移需要明确的兼容回归。

私密报告的启用流程见 [GitHub 官方说明](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting/configure-for-a-repository)。当前仅完成可审查的仓库配置，不把远端分支保护、通知接收或 CI 首跑标成完成。
