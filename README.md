# PadNote

<img src="assets/brand/PadNote-128.png" width="88" height="88" alt="PadNote 标志">

**把想法写在纸上，让 AI 看懂，再把工作交给电脑。**

PadNote 是面向平板的开源 AI 笔记应用。读一份材料，写下推导，画出关系，围绕同一张纸面交流。整理好的内容可以直接导出 PDF 分享，也可以连同上下文发送给同网络中已配对的电脑 Agent，接着执行任务。模型与服务由你选择。

[![30 秒看一份学习笔记](docs/media/2026-10-28/paper-learning-tour-preview.png)](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.24/PadNote-Learning-Tour-20261028.mp4)

[观看学习笔记导览（30 秒）](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.24/PadNote-Learning-Tour-20261028.mp4) · [下载应用与电脑助手](https://github.com/shiyan688/padnote/releases) · [English](README.en.md)

演示使用合成学习内容和实机截图，展示纸面与分享效果；不是 AI 或电脑 Agent 执行过程的录像。[素材来源](docs/media/2026-10-28/README.md)。

| 步骤 | 在 PadNote 中做什么 | 结果 |
| --- | --- | --- |
| 1. 学习与整理 | 在平板纸面上阅读、手写、绘图，并与所选模型交流 | 笔记与想法留在同一张纸面上 |
| 2. 分享纸面 | 导出当前笔记的 PDF，再通过系统分享发送 | 收件人可阅读纸面版式 |
| 3. 交给电脑 Agent | 将纸面材料发送给同一网络中已配对、支持笔记任务的 Hermes Agent | 电脑接收纸面材料并建立任务；在电脑任务列表查看处理进度，并在任务详情查看返回内容 |

[下载与安装包（GitHub Releases）](https://github.com/shiyan688/padnote/releases) · [PDF 分享指南](docs/BETA25_TESTING.md) · [文字编辑与草稿](docs/BETA26_TESTING.md) · [笔记移除与恢复](docs/BETA27_TESTING.md) · [纸面选区与手势](docs/AI_PAPER_SELECTION.md) · [Windows 连接助手说明](desktop/connection-assistant/windows-setup/README-WINDOWS.md) · [从源码构建](#从源码构建) · [反馈问题](https://github.com/shiyan688/padnote/issues)

## 演示画面

电脑连接助手把 Agent 准备、网络选择、扫码配对和带时间的检查结果放在同一页。下图由当前页面渲染器使用合成数据生成，只展示界面布局。

![电脑助手检测与配对界面（合成演示数据）](docs/media/2026-10-28/beta19-desktop-readiness.jpg)

以下画面展示平板纸面、任务说明与预设选择，来自合成示例的实机截图。[拍摄与帧率说明](docs/media/2026-10-28/README.md)。

![纸面示例](docs/media/2026-10-28/beta18-paper-promo-poster.png)

![任务预设选择](docs/media/2026-10-28/beta18-task-presets-poster.png)

![纸面 PDF 分享入口](docs/media/2026-10-28/beta18-share-transition.gif)

[观看教程版（36 秒）](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Android-Tutorial-zh.mp4) · [观看简介版（35 秒）](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Android-Promo-zh.mp4)

### 纸面导出为 PDF

把学习成果发给别人时，纸张大小也应可预期。A4 比例笔记按 A4 导出，屏幕比例笔记保留原形状；带 PDF 背景的笔记沿用源文件第一页的显示尺寸，统一整本笔记。导出时等比例放置内容，文字与图表不会为填满纸张而变形。[查看尺寸与分享说明](docs/BETA25_TESTING.md)。

下面是 Android 平板实际导出的示例页面，文字由人工输入。PDF 保留纸面内容，便于通过系统分享交给他人阅读。

![平板实际导出的示例 PDF 页面](docs/media/2026-10-28/paper-pdf-export.png)

长文也可以按纸面形式分享。下面取自一份在平板上实际导出的 18 页测试笔记，分别展示首、中、末页；36 段双语文字均为合成测试内容。

![18 页长文 PDF 的首、中、末页](docs/media/2026-10-28/paper-long-text-pdf.jpg)

[观看长文 PDF 效果（30 秒）](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.21/PadNote-Paper-PDF-Preview-zh.mp4)。这是实际导出页面的展示视频；素材来源见[说明](docs/media/2026-10-28/README.md)。

### 一页学习示例

这份五页的贝叶斯检测示例把文字、公式、笔迹和分支图放在一起。文字由人工编写，蓝色笔迹为合成样例，流程图为手工绘制。首页的 30 秒导览由这些实机截图制作；截图与裁切信息见[演示素材说明](docs/media/2026-10-28/README.md)。


<p align="center"><img src="docs/media/2026-10-28/paper-learning-app.png" alt="PadNote 合成学习示例的完整应用页面" width="820"></p>

<table>
  <tr>
    <td width="50%"><img src="docs/media/2026-10-28/paper-learning-formula.png" alt="学习示例中的贝叶斯公式" width="100%"></td>
    <td width="50%"><img src="docs/media/2026-10-28/paper-learning-diagram.png" alt="学习示例中的检测结果分支图" width="100%"></td>
  </tr>
</table>

<details>
<summary>早期 Android 界面截图</summary>

以下图片来自早期 Android 模拟器，展示的是 beta.13 界面。

![beta.13 多语言界面](docs/images/beta13-01-languages.png)

![beta.13 纸面工具](docs/images/beta13-02-tool-icons.png)

![PadNote 品牌标志](docs/images/beta13-03-new-brand.png)

</details>

## 平台与连接

- **Android 平板：**可从 GitHub Releases 获取可安装 APK。
- **Windows：**连接助手是单独分发的配套程序，用于配对与连接 Agent；它不附带 Hermes、模型或 API 密钥。
- **iPad：**提供源码，暂无可安装的签名 IPA。

电脑任务目前面向已配对且支持 PadNote 笔记任务的 Hermes Agent。任务会先保存到电脑，再等待 Hermes 返回运行编号；结果未知时不会自动重放。模型供应商由用户配置。OpenClaw 提供发现与设置引导，但这不代表 PadNote 笔记任务已接入 OpenClaw；Codex 纸面任务尚未接入。WSL 中的 Hermes 需要自行提供 Windows 可访问的 API，目前不会自动发现 WSL Agent。

更多边界与开发状态见[开发状态与后续工作](docs/DEVELOPMENT_STATUS.md)。

## 下载与校验

Android APK、Windows 连接助手、完整源码和许可文件从[GitHub Releases](https://github.com/shiyan688/padnote/releases)进入。下载后可用 `shasum -a 256 <文件>`（macOS/Linux）或 `Get-FileHash <文件> -Algorithm SHA256`（Windows）核对 `SHA256SUMS.txt`。

## 从源码构建

Android 需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0：

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows 连接助手的源码与构建前提见[`Windows 安装说明`](desktop/connection-assistant/windows-setup/README-WINDOWS.md)。

## 许可与反馈

Android 与 iPad 自研客户端采用 MIT；视频 Agent 子项目采用 Apache-2.0。第三方组件许可见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 与 [`LICENSING.md`](LICENSING.md)。反馈时请提供版本、设备、步骤和界面提示；不要附上 API 密钥、配对令牌、密码或私人笔记。
