# 界面与操作指引

## Android 平板

打开一份笔记，顶部可以直接进入 AI 对话、任务与结果、分享 / Agent。窗口变窄或语言标签较长时，主操作会移到标题下方；无需从横向工具栏里寻找。工具栏按书写、颜色、撤销与重做、选区、页面和文件操作分组，仍可横向滑动。

AI 对话沿用当前选区规则，打开入口本身不会请求模型。分享 / Agent 仍先预览纸面材料，再由你确认发送。操作菜单使用统一的行距和选中反馈；长选项可以换行。

建议用一份可丢弃的测试笔记检查：

1. 切换常用语言，横竖屏或分屏查看顶部三个入口，确认可读、可点击。
2. 写字、套索选择、撤销和重做；缩放后确认纸面位置与内容正常。
3. 打开 AI 对话后返回，再打开分享 / Agent；只进入界面，不需要发出请求。
4. 在任务、接收 Agent 和导出等选择菜单中切换选项，检查长名称、选中状态和返回操作。

## Windows 电脑助手

安装包与便携包都包含新的启动器和浏览器管理页。启动器分开显示助手服务与 Agent 状态，启动、连接设置和停止操作按当前状态排列。日志、复制诊断、保存诊断文本和打开日志目录集中在诊断区域。窗口较矮时可滚动；Tab 导航会把下方获得焦点的操作滚动到可见区域。

在浏览器管理页，先检测要使用的 Agent，再生成二维码，平板扫码后在电脑批准。已有有效二维码时，可以从页面顶部直接跳到配对区。状态未知与检测过期分别提示；检测过期后，“重新检测”成为主操作，不会自动发起检查或发送任务。

升级前结束你明确知道可以结束的任务，退出旧助手再运行安装包。遇到“结果未知”的旧任务，先查询和核对，避免重复提交。新界面不改变配对权限、网络规则或 Agent 执行能力；Windows 助手仍需要你自行配置可用的 Agent。

## 验证记录

Android 正式构建为 versionCode 64 / `0.18.0-beta.28-debug`：54 suites、412 项 JVM 测试通过，lint 0 errors、163 warnings，保留原测试签名和四种 ABI。正式 instrumentation APK 已编译。按钮保留至少 48dp 的触控目标，可按较大字体增高。

电脑管理页通过 18 项相关 HTTP / UI 回归，以及四种窗口宽度的隔离浏览器检查，包括键盘跳转、二维码显示、检测状态过期后的交互变化。另在 Windows 11 / 96 DPI 使用新版便携运行时验证了两种合成状态的原生窗口与下方诊断按钮焦点可见性；这不代表真实 Agent 执行或安装、升级、卸载流程验收。Android 设备检查结果单独记入开发状态。

## English

The Android editor keeps AI, tasks, and sharing visible and groups paper tools by purpose. Narrow windows and longer localized labels use a second action row. Opening an AI or sharing entry does not submit a request.

The Windows launcher separates service state from Agent state and groups log and diagnostic controls. The management page guides Agent checks, QR pairing, and device approval; an expired check promotes recheck without making an automatic request. Use a disposable note to inspect labels, selection menus, undo/redo, and the sharing preview. Existing uncertain tasks should be checked before resubmission.
