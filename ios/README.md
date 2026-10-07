# PadNote iPad

PadNote 是面向 iPadOS 17 及以上版本的原生 SwiftUI / UIKit 笔记应用。此目录包含当前 iPad 客户端源码和 Xcode 工程。iPad 与 Android 功能范围相关，但两端并非完全一致。

## 构建与运行

在 Xcode 中打开 `ios/PadNote.xcodeproj`，选择共享 scheme `PadNote`，即可在 iPad Simulator 上运行。真机运行需在 Signing & Capabilities 中选择自己的 Apple Development Team。仓库目前不提供签名 IPA、App Store 或 TestFlight 版本。

工程没有第三方 Swift Package 依赖。离线 KaTeX、Mermaid、字体和对应声明随工程提供。构建脚本生成的中间文件和测试结果位于 `ios/build`、`ios/TestResults`，不属于源码发布内容。

运行完整单元测试时，先将占位符替换为一台已启动的 iPad Simulator UDID：

```sh
SIMULATOR_UDID="<替换为已启动的 iPad Simulator UDID>"
xcodebuild \
  -project ios/PadNote.xcodeproj \
  -scheme PadNote \
  -destination "platform=iOS Simulator,id=${SIMULATOR_UDID}" \
  -only-testing:PadNoteTests \
  -collect-test-diagnostics never \
  -enableCodeCoverage NO \
  test
```

这条命令仅运行 `PadNoteTests` 单元测试 target；scheme 中的 UI 测试需另行运行。

## 当前功能

- 多笔记书架、多页手写、Apple Pencil 输入、选区、图形、撤销重做、图片和 PDF 导入/导出。
- Markdown 与 LaTeX 文本流、离线公式和 Mermaid 渲染；支持 JSON schema 1–8 迁移，以及与 Android 的 `.padnote.json` / `.padnote.zip` 文件交换。
- 用户自选 AI 服务地址、模型和 API Key；密钥保存在本机 Keychain。发送内容由用户确认，服务商费用由用户自己的账户承担。
- 本地 Markdown 知识库、PDF 或既有文字的数字化处理及检查点保存。
- 通过 HTTPS 连接电脑 Agent。客户端支持 Hermes 文字任务；电脑视频流程取决于连接助手报告的能力和电脑端配置。OpenClaw 任务执行当前不受 iPad 客户端支持。
- 整库本地归档包括已保存笔记、原始 PDF、已分配封面、用户封面预设、知识库条目和本机已验证的 MP4 附件。恢复预览通过校验后按新副本恢复，不覆盖现有笔记；Vault 与视频关联会映射到新笔记。归档不包含连接凭据或授权、AI 对话、电脑任务历史及在途工作。此功能不是云同步。

## 已验证

排版失败的文字可在“管理文字”中查看和编辑源码；修复后保存并重新打开，内容仍会保留。备份导入按文件类型处理：ZIP 进入归档检查，PNG 进入封面预设导入；取消或空选择不会启动导入。

241 项单元测试已通过；6 项选定 UI 场景均有通过记录，覆盖新建保存重开、文字修复、视频关联、备份选择器取消和两种辅助功能字号下的任务目标显示。一次合并运行通过 246/247 项；随后单独复测受影响的两项目标显示场景，2/2 通过。上述单元测试和 UI 场景结果来自分批运行，并非一次 247 项全通过。

## 后续验证

上述结果来自模拟器单元与选定 UI 测试。真实 iPad、签名与 IPA 分发、真实模型服务和凭据、实际服务费用、真实 Hermes 电脑连接及电脑端视频渲染环境仍需单独验证。测试使用本地夹具和注入客户端，不代表外部服务或真实设备验收。测试通过也不证明两端完全一致或完成跨平台全媒体往返。

## 许可

PadNote Android 与 iPad 自研代码（含手写引擎）采用 [MIT 许可](../LICENSE)，保留版权与许可声明。视频 Agent 子项目继续采用 Apache-2.0；第三方资产遵循各自许可，见 [LICENSING.md](../LICENSING.md) 和 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
