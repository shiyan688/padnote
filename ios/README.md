# PadNote iPad

原生 SwiftUI / UIKit 工程，对齐 Android `0.18.0-beta.2`（`beta` / `0bc3a68`）的应用功能范围。最低 iPadOS 17，iPad 专用；版本 `0.18.0`，build `41`。

## 在 Xcode 运行

打开 `ios/PadNote.xcodeproj`，选择共享 scheme `PadNote` 和 iPad Simulator，点击 Run。工程无第三方 Swift Package；离线 KaTeX、Mermaid、字体及许可证随应用打包。

真机运行时，在 Signing & Capabilities 选择自己的 Apple Development Team，然后选择已配对的 iPad。无签名构建不等于已经在真机验证。

## 许可范围

PadNote Android 与 iPad 客户端的自研代码（含手写引擎）采用 [MIT 许可](../LICENSE)，范围见 [LICENSING.md](../LICENSING.md)。MIT 允许商用和闭源衍生版本，要求保留版权与许可声明。第三方资产保留各自许可证，见仓库的 [第三方声明](../THIRD_PARTY_NOTICES.md)；视频 Agent 子项目继续单独使用 Apache-2.0。

## 已接入的功能

- 多笔记书架、新建/搜索/重命名/删除；内置封面、照片或文件封面；四种纸张、A4/屏幕比例及方向。
- UIKit 原始压感笔迹与合并触点采样，Apple Pencil/手指策略；画笔、高亮、精确局部橡皮、矩形/直线/椭圆、HSV 颜色与独立宽度，设置持久化。
- 套索选择、移动/复制/删除，文字调宽/字号、图片等比缩放；30 步撤销重做，可撤销清空。
- 多页连续浏览、缩放与视口保存；缩略图跳页、复制/删除/上移/下移；末页拉出新纸张，边缘停留后拖动文字翻页。原始 PDF 页保持固定前缀。
- 页内 LaTeX/Markdown 源码编辑、即时预览；离线公式与 Mermaid 实际编译到纸面，语义块跨页；源码仍为唯一持久化内容。
- JSON schema 1–8 迁移、Android `.padnote.json` / `.padnote.zip` 交换、PDF 导入批注与平面化 PDF 导出、图片插入；原子保存、损坏文件保留与备份恢复。
- 多模型档案与 Keychain 凭据；直连或视觉转写→文本回答；转写可见、请求取消、上传确认、可拖动/缩放/最小化 AI 卡片。
- 标准工具调用：页面地图、文字写入/移动/样式、Mermaid、知识库检索/读取。默认只创建新内容，修改已有内容需在卡片开启；写入先避让，有限轮次、整个动作一次撤销，编辑冲突阻止旧快照覆盖。
- 本地 Markdown 知识库、数字化/已有文字整理、检索与导出。
- 电脑 Agent HTTPS 配置、Hermes 五项 capabilities 健康检查；连接后导出 `video.explain.v1` 任务 ZIP，包含 Markdown、参数与完整性 manifest。

## 验证与边界

2026-09-22：Xcode 27 / iOS 27 的 iPad Pro 11-inch (M5) 模拟器通过 62 项单元测试、3 项 UI 测试；iPad 真机 arm64 无签名构建通过。结果包为 `ios/TestResults/Beta-final-6.xcresult` 和 `Beta-ai-ui-9.xcresult`，持续状态记录在 `PROJECT_MEMORY.md`。测试涵盖跨平台数据、局部橡皮、文本排版像素、离线数学、模型路由/取消、工具权限/事务、Agent/ZIP、封面及保存重启 UI 流程。

真实模型 endpoint、Hermes 实例及 Apple Pencil 的延迟/压感/防误触仍需使用实际配置与 iPad 验收；当前测试使用隔离的本地夹具。Hermes 任务提交/SSE/审批/产物回传和 OpenClaw Gateway Bridge 与 Android beta 一样仍属下一阶段。

封面保存在本机 sidecar，不加入跨平台笔记 JSON。Markdown 支持安全常用子集；极长且不可分割的公式/图表会等比缩小到页面内。编译缓存仅用于显示，失败时保留源码并使用原生文字回退。

## 重复构建

```sh
python3 ios/scripts/generate-project.py  # 新增 Swift 文件后更新工程
PADNOTE_SIMULATOR_ID=172ECBE4-DB05-400A-8051-7D9B3DB0C775 ios/scripts/verify.sh
```

脚本把 derived data 和日志放入 `ios/build`；测试报告另存于忽略的 `ios/TestResults`。不会安装工具或修改用户签名配置。
