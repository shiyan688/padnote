# 参与 PadNote

PadNote 希望让平板成为更自然的 Agent 入口：用户写、画、圈选，电脑接着完成任务。欢迎从一个能复现的问题、一段使用反馈或一个范围清楚的修复开始。

功能问题请使用 [Issue 模板](https://github.com/shiyan688/padnote/issues/new/choose)。安全漏洞按 [SECURITY.md](SECURITY.md) 私密报告。截图、日志和测试样例不要包含真实笔记、教材原件、配对码或密钥。

## 本地验证

以下命令从仓库根目录运行，不需要模型 API 密钥。

```sh
node tools/static-check.mjs
bash tools/tests/test-build-android-script.sh
bash desktop/connection-assistant/run-tests.sh
python3 tools/tests/test_connection_assistant_http.py
```

Android 使用 JDK 17、SDK Platform 35、Build Tools 35.0.0 和仓库里的 Gradle 8.9 wrapper：

```sh
cd android
bash gradlew :app:testDebugUnitTest :agent-probe:test :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
# 另接入专用模拟器后运行。上面的 assemble 只编译原生测试。
ANDROID_SERIAL=emulator-5554 bash gradlew :app:connectedDebugAndroidTest
```

iPad 使用 Xcode。运行前选一个可用的 iPad 模拟器，不需要分发证书：

```sh
PADNOTE_SIMULATOR_ID='<模拟器 UUID>' bash tools/ci/ipad.sh
```

此入口生成工程后运行单元和 UI 测试，把报告写入 `dist/ci/`。再次运行前，把上一次 `ipad.xcresult` 移到其他位置；Xcode 不覆盖已有结果包。

视频 worker 使用 Node 22.22.0 或更新版本。依赖安装包含 FFmpeg 等平台组件；只在独立开发环境中执行。

```sh
cd agent-skills/padnote-video-explainer
PUPPETEER_SKIP_DOWNLOAD=true npm ci
npm run check
npm test
```

## 提交修改

PR 说明用户遇到的问题、改动后的行为和实际验证结果。数据、授权或协议改动应包含失败路径的回归测试；界面改动提供使用示例数据的截图。不要把编译成功写成真机通过，也不要因为测试失败就删除它原本验证的要求。

依赖与工作流 Action 固定版本；更新通过独立 PR 重新验证。禁止把个人工具链、缓存、`.env`、助手运行状态或构建签名加入源码。主项目与视频子项目的许可证不同，新增依赖和素材按 [LICENSING.md](LICENSING.md) 保留各自许可。

合并检查与仓库后台启用步骤见 [CI 验收](docs/CI.md)。工作流文件入库不等于分支保护已生效。
