# Beta 41 验收说明

## 中文

### Windows 连接助手

Windows beta.37 原位升级到 beta.41 已通过验收。Setup 正常退出，243 个安装文件核验通过，打开 beta.41 窗口；安装目录下没有遗留 Python 字节码缓存。助手自有本地管理端点返回 HTTP 200，TLS 身份另行核对并保持一致。配对状态、任务记录摘要和源码目录 ACL 均保持不变。升级过程未重启 Hermes，也未重提旧任务。卸载流程未测试。

连接状态只有在最近 60 秒内检查成功且确认具备任务执行能力后才显示就绪。状态过期时由用户手动重新检查；打开界面不会在后台探测或启动 Hermes。安装清理只移除已知字节码缓存，不递归删除未知文件。

### 手写页与 Agent 图

左图为作者授权公开的原始手写页面。真实 Hermes Agent API 返回了独立 `y=x²` 示例的 SVG；PNG 在 Mac 本地由 SVG 渲染。Android beta.40 在隔离模拟器中通过系统图片选择器手动导入 PNG：第 1 页原手写内容保留，图像放在第 2 页；保存、回到书架、重开后图像仍在。应用导出的两页 PDF 已逐页检查。

该函数图不是手写题的答案；没有声称已解出原方程。Android 演示是手动图片导入，不是自动获取跨设备 Agent 任务结果。Android beta.41 APK 尚无，本版继续提供 beta.40（versionCode 73）；iPad 提供源码，不含签名 IPA。

素材来源、SVG/PNG 哈希和图像详情见 `docs/REAL_HANDWRITING_AGENT_DEMO.md`。Windows 原生验收回执 SHA-256：`10de4be7fdc66c9c9e0bdbafb73cc6fdf7ea2d301568b5e59e72eaac762f1890`。Android 导入、重开和 PDF 回执 SHA-256：`8f68289e5d4a0617718de5a7eb7bf55f2019c97c68aea3538bcb4a618e3d5eff`。

## English

### Windows Connection Assistant

The native in-place beta.37-to-41 upgrade passed. Setup exited successfully, all 243 installed files were verified, and the beta.41 window opened; no Python bytecode cache remained under the install tree. The assistant's local management endpoint returned HTTP 200, and its TLS identity was independently verified and retained. Pairing state, task-record summary, and source-directory ACL were unchanged. The upgrade did not restart Hermes or resubmit an old task. Uninstall was not tested.

The assistant reports ready only after a successful check within the last 60 seconds and confirmed task-execution capability. An expired result requires a user-triggered recheck; opening the interface does not probe or start Hermes in the background. Installer cleanup removes only known bytecode caches and leaves unknown files alone.

### Handwriting and Agent graph

The left image is the author's authorized original handwriting. A real Hermes Agent API returned an SVG for a separate `y=x²` example; its PNG was rendered locally on a Mac. On an isolated Android beta.40 emulator, the PNG was manually imported through the system image picker. The original handwriting remained on page 1 and the graph was placed on page 2. After saving, returning to the bookshelf, and reopening, the graph remained visible. The app's two-page PDF export was reviewed page by page.

The graph is not an answer to the handwritten problem; no solution to the original equation is claimed. This Android demonstration is manual image import, not automatic cross-device task-result retrieval. No beta.41 Android APK is included; Android remains beta.40 (versionCode 73). iPad source is included without a signed IPA.

See `docs/REAL_HANDWRITING_AGENT_DEMO.md` for media provenance and SVG/PNG hashes. Windows native receipt SHA-256: `10de4be7fdc66c9c9e0bdbafb73cc6fdf7ea2d301568b5e59e72eaac762f1890`. Android import/reopen/PDF receipt SHA-256: `8f68289e5d4a0617718de5a7eb7bf55f2019c97c68aea3538bcb4a618e3d5eff`.
