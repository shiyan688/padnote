# PadNote

面向学生、科研用户和开发者的平板 Agent 入口 beta：用手写与绘画表达，再结合可编辑文本、圈选 AI 和本地 Markdown 知识库处理内容。

> Android 与 iPad 原生客户端以源码形式开放，处于 beta。手写设备体验和真实模型服务仍需实际验收；请先阅读构建说明与当前边界。

PadNote 的核心思想，是让平板成为个人思考与学习工作台，让记录、理解、推导、知识积累和 AI 协作自然连在一起。长期愿景是做一个更好的平板端 Agent 入口：用户可以通过写写画画与 Agent 沟通。当前产品以手写笔记、可编辑内容、圈选 AI 和有限的笔记工具为基础，处于 beta，尚未实现完整的 Agent 通信、执行与结果回传闭环。

## 可以做什么

- 用手写笔记录，保留原始坐标、压力和时间；支持局部橡皮、套索、撤销、高亮、图形、图片与多页。
- 编辑 LaTeX/Markdown 源码，在纸面离线显示公式和 Mermaid；长内容按文字流跨页。
- 圈选后预览发送内容，使用自己的模型 API 进行讲解和整理；可选视觉转写后交给文本模型回答。
- AI 通过有限的笔记工具写入结论，整轮写入可一次撤销；用户可关闭写入。
- 将笔记整理为本地 Markdown 知识库，搜索与导出；支持 PDF 批注和可编辑笔记文件交换。

## 平台与体验方式

| 平台 | 当前入口 | 验证范围 |
| --- | --- | --- |
| Android | Android Studio 或 Gradle Wrapper 构建 debug beta | 最低 API 24；不同平板与手写笔仍需实际验收 |
| iPad | Xcode 打开 `ios/PadNote.xcodeproj`，scheme `PadNote` | 最低 iPadOS 17；62 单元测试、3 UI 测试和无签名 arm64 构建通过；Pencil/真实模型待验收 |

当前没有已确认的 App Store 或 TestFlight 下载入口。Android 安装包仅在发布版本、文件哈希与下载链接核对后添加；源码构建不等于提供通用签名安装包。

## 本地构建

Android 需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。配置 `JAVA_HOME` 与 `ANDROID_SDK_ROOT`（或 `ANDROID_HOME`），然后：

```sh
tools/build-android-apk.sh
```

脚本调用随项目的 Gradle 8.9 Wrapper，首次构建会下载 Gradle/依赖；构建并 lint debug beta、验证 APK 签名并输出 SHA-256。JVM 测试：

```sh
cd android
./gradlew :app:testDebugUnitTest
```

iPad：在 Xcode 选择 iPad Simulator 后运行。真机选择自己的 Apple Development Team 并完成设备配对。详细步骤见 [iPad 说明](ios/README.md)。

开发辅助：`node tools/static-check.mjs` 检查文件与关键产品约束；`bash tools/tests/test-build-android-script.sh` 使用隔离的模拟工具链检查构建脚本。`tools/emulator/` 是需要预先配置 SDK、AVD 和工具链的 Linux 辅助脚本，UI 测试会清除目标模拟器里的 beta 应用数据。`tools/e2e/` 是真实模型测试，需显式提供 `E2E_API_KEY`、服务地址和模型，运行会调用所选服务。

## 模型与数据

AI 功能需要用户自己的兼容 HTTPS API 与 Key，服务费用取决于所选供应商。密钥通过 Android Keystore/iOS Keychain 保存。笔记先保存在本地；使用圈选 AI、整本数字化或知识库 AI 时，会按已确认范围把相应内容发往用户配置的服务。公式和图表的本地渲染不访问网络。

两端交换的是笔记文件，没有自动云同步。两端排版和设备手感仍需共同验收；iPad 封面目前是本机 sidecar，不随笔记 JSON 导出。

## 当前边界

项目处于 beta。模型兼容性、长文档性能和真实手写笔体验需要持续验证。AI 回复当前非流式。电脑 Agent 当前提供 HTTPS 能力检查和视频任务 ZIP 导出；Hermes run/SSE/审批/产物回传与 OpenClaw Gateway Bridge 尚未完成。

更长期的方向是让手写和绘画成为与平板 Agent 沟通的自然入口；这部分仍属于产品愿景，不代表当前已具备完整的 Agent 通信、执行或回传能力。

早期 HarmonyOS ArkUI 目录如随快照保留，仅作历史原型，不代表当前完整支持 HarmonyOS NEXT。

## 反馈与贡献

反馈请附：平台、设备/手写笔、系统、版本、最小复现步骤、预期/实际结果；模型问题附供应商/模型名称和已脱敏错误信息。请勿提交真实 API Key、私人笔记或签名证书。

优先欢迎：真机验收、数据往返、排版问题、模型兼容性和文档改进。涉及笔记数据格式或 AI 写入权限的改动，请同时给出迁移与回归验证。

## 许可证

PadNote Android 与 iPad 客户端的自研代码（含手写引擎）采用 [MIT 许可](LICENSE)，范围见 [LICENSING.md](LICENSING.md)。MIT 允许商用和闭源衍生版本，要求保留版权与许可声明。第三方资产保留原许可证，见 [第三方声明](THIRD_PARTY_NOTICES.md)。已有视频 Agent 子项目的 Apache-2.0 许可单独保留。
