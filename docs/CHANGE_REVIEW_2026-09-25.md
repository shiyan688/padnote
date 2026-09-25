# PadNote 变更审阅说明（2026-09-25 批次）

| 项 | 值 |
|---|---|
| 审阅分支 | `review/2026-09-25-batched-commits` |
| 对比基线 | 公开 `beta` @ `26dbb78` |
| 规模 | 227 个文件（146 新增 / 81 修改），约 `+38000 / −2000` |
| 提交数 | 10 |
| 性质 | **以「补提交」为主**，不是新功能开发——详见第 2 节 |

---

## 1. 结论先行

1. **这批提交主要是把已经存在、但从未纳入版本控制的工作补进来**，以防丢失。
2. **唯一的功能性修复**是渲染失败标记稳定化（见第 5 节），它已在构建与 JVM 测试层面得到验证。
3. **本批次不等于「已验证可合入」**：构建与 JVM 测试通过，但**设备级验收、iPad 真机、真实模型服务均未验证**。详见第 6、8 节。
4. **本分支是过滤快照**，基于公开 `beta` 构建，不是私有开发历史的镜像。范围说明见第 9 节。
5. 有一处需要你判断：`desktop/` 测试夹具里含一对 `CN=localhost` 自签证书与**私钥**（见 4.9），它可能触发仓库的密钥扫描推送保护。

---

## 2. 为什么会有这一批提交

排查发现工作区积压了大量未提交文件，时间跨度为 9-23 至 9-25。其中包含：

- Android 版本号已从 `41 / 0.18.0-beta.2` 推进到 `46 / 0.18.0-beta.7`——**整整 5 个迭代没有落盘**；
- 整个 iOS 目标（97 个文件）从未提交过；
- agent-skills 的视频解说 worker、`tools/` 的 CI 入口、桌面连接助手同样是首次入库；
- 渲染恢复修复也还只在工作区里。

如果开发机出问题，上述工作会全部丢失。所以本轮优先做的是**分批落盘**，而不是继续写功能。

---

## 3. 提交总览

本分支按职责切成 10 个提交（而非一个整体），便于逐个审阅：

| # | 提交 | 内容 |
|---|---|---|
| 1 | repo 元信息 | `.gitignore` 放宽、`README.md`、`.github/`、`CONTRIBUTING.md`、`SECURITY.md`、`THIRD_PARTY_NOTICES.md` |
| 2 | **核心修复** | 渲染失败标记稳定化 + 两个测试（自带测试，可独立 `bisect`） |
| 3 | Android 主源码 | Agent 桥接、BYOK、数字化、版本号推进、引入 zxing |
| 4 | Android 测试 | 10 个 legacy fixture + 28 个测试套件 |
| 5 | iPad 源码与资源 | SwiftUI/UIKit 客户端、16 个 XCTest 套件、随包 KaTeX / Mermaid |
| 6 | 视频解说 worker | TTS 持久化任务 worker |
| 7 | tools | CI 入口与工具测试 |
| 8 | 桌面连接助手 | 局域网配对配套程序（Python） |
| 9 | docs | 设计与 QA 规格、legacy 与排版 fixture |
| 10 | 审阅说明 | 即本文档（属临时审阅产物，合并后可删） |

关于 `.gitignore`：原文件的 `# Maintainer-only material` 段**原样保留**（`/PROJECT_MEMORY.md`、`/insight/`、`/docs/launch/`），同时新增 `__pycache__/` / `*.py[cod]` 通用规则（此前漏掉了 `tools/` 下的字节码缓存），以及连接助手的本地运行时目录。

---

## 4. 逐项说明与审阅要点

### 4.1 仓库治理

新增 `.github/`（issue 模板、PR 模板、dependabot、`workflows/verify.yml`）、`.gitattributes`、`CONTRIBUTING.md`、`SECURITY.md`、`THIRD_PARTY_NOTICES.md`。

忽略规则的原问题：旧规则只写死某一个子目录的 `__pycache__/`，漏掉了 `tools/tests/` 下的 `.pyc`。已改为通用的 `__pycache__/` + `*.py[cod]`。

- **审阅要点**：确认放宽后的规则没有把需要入库的文件误排除；确认 `THIRD_PARTY_NOTICES.md` 里列出的 KaTeX / Mermaid / zxing / qrcodegen 版本，与实际引入的版本一致。
- 另注：Harmony 侧 `oh-package.json5` 的 `license` 已由 `UNLICENSED` 改为 `MIT`（与 `LICENSING.md` 的双端 MIT 声明一致）。因公开基线已是 MIT，此项在本分支中不产生差异，故未单独成提交。

### 4.2 核心修复（见第 5 节）

### 4.3 Android 主源码

要点：

- **Agent 桥接**：配对客户端、HTTP 传输、设备身份、任务与连接的 Store/Dialogs/Guide、产物下载器，以及随包教程 `assets/agent-connection-guide.json`。
- **自选模型（BYOK）**：会话 Store、读取范围、Vault 快照、OpenAI 兼容客户端、配置 Store。
- **数字化**：笔迹转文字的 Controller 与 Store。
- **构建**：`versionCode 41 → 46`，`versionName 0.18.0-beta.2 → 0.18.0-beta.7`；新增依赖 `zxing-android-embedded:4.3.0` 与 `zxing core:3.5.3`，使配对可**应用内扫二维码**，不再要求另装扫码器；ZXing 许可证文本随包（`assets/licenses/`）。
- **审阅要点**：
  1. 新增第三方依赖是否符合项目的许可与体积要求；
  2. **读取范围（`AiReadScope` / `PageMap`）的边界**是否与 `docs/AI_READ_SCOPE.md` 一致——隐私边界是本项目的高风险区；
  3. `MainActivity.java` 已达约 6800 行、`NoteCanvasView.java` 约 6200 行，是否值得后续拆分（本次不改）。

### 4.4 Android 测试

- **10 个 legacy-notes fixture**（`schema1`…`schema8`，含一个 corrupt-tail 变体与 `schema7` 的源 PDF），用于钉住向后兼容；
- **15 个仪器化测试套件**：Agent 连接/任务的 Store 与 Dialog、AI 会话（含进程重启）、编辑卡片与历史、读取范围、转写复核、数字化、legacy 兼容、文档与 Store 恢复、纸面排版、PDF 导出；
- **13 个新增 JVM 套件**，另更新 `DiagramRenderingTest`、`PdfNoteIOTest`、`VaultToolsTest`。

- **审阅要点**：fixture 与 `docs/fixtures/legacy-notes/` 是**同一套的镜像**，确认两处一致；确认 corrupt-tail 用例确实断言了「损坏尾部可恢复」而不是恰好通过。

### 4.5 iPad 源码与资源

**整个 iOS 目标此前从未提交**，本次首次入库，纯新增。

- 源码结构：`App/Canvas/`（画布、笔迹、橡皮、内联文本编辑、渲染器、排版）、`AI/`（BYOK 客户端、会话、工具引擎）、`Agent/`（配对、连接/任务客户端与视图、视频任务导出）、`Services/Views/`（笔记库、持久化、封面、跳票、书架、编辑器、AI 浮层）、16 个 XCTest 套件 + 1 个 UI 测试目标、`scripts/`（工程与图标生成、`verify.sh`）。
- 离线资源：KaTeX（`katex.min.css/js` + 20 个 woff2 字体）与 Mermaid（`mermaid.min.js`），加 reader 宿主页与应用图标集、上游 LICENSE。

- **审阅要点**：
  1. 这是本批最大的代码面，且**尚未在真机验证**。建议重点看数据持久化与笔记格式的读写路径。
  2. `ios/README.md` 声明对齐 Android `0.18.0-beta.2`，而 Android 本批已到 `beta.7`——**存在版本错位，需要确认是否有意为之**。
  3. `mermaid.min.js` 是本批最大的单个文件（约 2.75 MB）。确认它是上游原始字节、未被改动；确认离线打包不含远程加载回退。

### 4.6 视频解说 TTS worker

- `task-state.ts` / `task-worker.ts`：持久化任务状态 + 监督子进程的 worker；
- `tts-environment.ts`：**显式允许列表**传给子进程的环境变量，不再继承整个宿主环境（这是此前的风险点）；
- `approved-audio.ts`：把「已批准音频」绑定到内容哈希，避免陈旧文件被静默复用；
- `provider-env.mjs` / `dashscope-tts.mjs` 供应商解析与后端；
- 同步更新 `SKILL.md`、`README.md`、`references/`、`schemas/audio-manifest.schema.json`。

- **审阅要点**：环境变量允许列表是否**真的收紧了**——建议逐个核对允许项并确认没有 `*` 回退；哈希绑定是否覆盖「音频被替换但文件名不变」的场景。

### 4.7 tools

- 新增 `tools/ci/android-native.sh`、`tools/ci/ipad.sh`：CI 门禁调用的原生构建入口；
- 新增 `tools/tests/`：APK 构建脚本的 shell 测试、连接助手的 HTTP 测试、一个 Android 视频任务包 fixture；
- 更新 `build-android-apk.sh`、`run-agent-probe.sh`、`static-check.mjs`、emulator 系列脚本、`e2e/run-e2e.sh`、hermes 阶段提示词、`layout-calibration/`。

- **审阅要点**：`emulator/` 脚本会**清空目标模拟器上的 beta 应用数据**（公开 README 已声明）。确认其目标选择逻辑不会误伤非模拟器设备。

### 4.8 桌面连接助手

平板通过局域网配对的本地配套程序（Python）：应用入口与 Web UI、设备发现、桥接、配置档、打包、状态、安全，以及 hermes / codex 两条传输通道；三种平台的启动器（`.command` / `.cmd` / `.ps1` / `.sh`）；5 个测试套件；`vendor/qrcodegen.py` 及其上游许可。

- **审阅要点（安全相关，优先级高）**：`security.py`、`discovery.py`、`web.py` 是本批新增的攻击面。建议确认：局域网发现不会越权暴露、Web UI 的**绑定地址与鉴权**、HTTPS 证书校验是否可被绕过。
- ⚠️ **关于 `tests/fixtures/hermes-localhost-{cert,key}.pem`**：这是一对 `CN=localhost`（SAN `DNS:localhost`）**自签证书与私钥**，生成于测试用途，唯一引用点是 `test_hermes_deadline.py` 的 `load_cert_chain()`。它不签发任何东西、也不被任何服务信任，**不是凭据**。但把私钥提交到公开仓库可能触发密钥扫描的**推送保护**而拦截推送。若希望消除这个摩擦，可改为在测试运行时生成一次性密钥（需要相应改动该测试）。

### 4.9 docs

设计与 QA 规格：AI 契约/会话状态/QA/编辑历史/读取范围/请求生命周期、Agent 集成与连接 QA 与协议、Codex 与 OpenClaw 适配计划、Hermes 连接与传输 QA、渲染恢复方案与 QA、数字化恢复、纸面排版 QA、PDF 导出 QA、库备份计划、CI、支持；以及 `docs/fixtures/` 的 legacy-notes 与 `paper-layout.md`。

- **审阅要点**：`docs/AI_CONTRACT.md` 与 `docs/AGENT_INTEGRATION.md` 本次被修改，确认改后的描述**没有强于实际代码行为**。（项目历史上有过「文档描述过强、后被订正」的先例，值得盯。）

---

## 5. 核心修复详解：渲染失败标记稳定化

### 5.1 缺陷

`NoteTextBoxView` 原先用**活的失败原因字符串**拼出失败面板的无障碍描述（`"显示失败：" + state.message`）。后果：

- 标记文本本身携带了错误内容，导致自动化定位器在**第二个渲染器报错时匹配到多个节点**，抛 `AmbiguousViewMatcherException`；
- 恢复对话框因此**无法被自动化触达**，这条恢复路径实际上没有回归保护。

### 5.2 改法

- 面板改用**常量**标记 `"显示失败，点按查看原因和修复"`；
- 失败原因移入 `renderFailureReason`，**仅用作对话框标题**；
- 测试于是能确定性地定位该标记。

### 5.3 涉及文件

| 文件 | 状态 | 说明 |
|---|---|---|
| `android/.../NoteTextBoxView.java` | 修改 | `+141 / −15` |
| `android/.../androidTest/.../RenderRecoveryInstrumentedTest.java` | 新增 | 256 行，设备级恢复流程 |
| `android/.../test/.../RenderRecoveryTest.java` | 新增 | 52 行，3 个纯契约用例 |

`RenderRecoveryTest` 钉住的是 `CompiledTextWebView` / `AiMathWebView` / `NoteTextBoxView` **三者共用的「token 绑定错误通道」契约**（`window.__padnoteToken` / `window.__padnoteError`）。

### 5.4 验证结论

- 修复**有效**：全部运行中 `AmbiguousViewMatcherException` 计数为 **0**；
- 失败位置从「定位行」**下移**到「对话框断言行」，说明定位问题已解除；
- 隔离探针证明：标记数量恰为 1、旧前缀 `显示失败：` 计数为 0、监听器有效、触摸派发有效；
- **残留的 1 项失败不是产品缺陷**，而是无 KVM 硬件加速的无头模拟器上，Espresso 注入点击未送达视图所致。探针已证明改用 `performClick()` 或合成触摸派发可以打开对话框。

---

## 6. 验证证据与可信度

### 已在一台 Linux 构建机上实测通过

| 项 | 结果 |
|---|---|
| Gradle 构建 | `BUILD SUCCESSFUL` |
| 应用 JVM 测试 | **127 tests / 0 failures** |
| agent-probe 测试 | **3 tests / 0 failures** |
| Lint | 通过 |
| 源清单一致性 | 修复前后共 158 个源码输入：**恰好 2 个变化** = 两个修复文件；0 删除 |
| 隔离探针 | `Tests run: 4, Failures: 1`（唯一失败即上述环境问题） |

### 明确未验证（不可据此合入）

- **设备级验收**：Android 真机、手写笔、实际模型服务均未跑；
- **iPad 真机**：无签名构建 ≠ 真机验证；
- **iPad 与 Android 的版本错位**：iPad 声明对齐 `beta.2`，Android 已到 `beta.7`；
- **视频 worker 的真实 TTS**：未调用真实供应商；
- **桌面连接助手的端到端**：未在真实配对场景跑通。

> 请把本批次理解为「**把这些工作保住、并让唯一的修复通过构建与 JVM 测试**」，而不是「功能已验收」。

---

## 7. 建议的审阅顺序

按「风险 × 代码量」排序：

1. **核心修复**（3 文件）— 先看它，判明修复本身是否正确、测试是否真的钉住了契约。
2. **`.gitignore`** — 决定后续所有文件是否该入库，先确认它。
3. **`desktop/` 的 `security.py` / `web.py` / `discovery.py`** — 新增的网络攻击面，且是首次公开。
4. **`android/` 的 `AiReadScope` / `PageMap`** — 隐私读取边界，项目高风险区。
5. **视频 worker 的 `tts-environment.ts`** — 确认环境变量确实被收紧。
6. **iPad 源码** — 代码面最大，建议抽样 + 重点看持久化与格式读写。
7. **`docs/AI_CONTRACT.md` / `AGENT_INTEGRATION.md` 的改动** — 确认文档没有强于代码。
8. 其余为补提交或文档，抽看即可。

### 可复核的命令

```bash
# 本分支相对基线的全部改动
git log --oneline beta..review/2026-09-25-batched-commits
git diff --stat beta..review/2026-09-25-batched-commits

# 核心修复
git log -p --grep="render-failure marker"

# 确认没有构建产物混入
git ls-files | grep -E '(^|/)(build|dist|node_modules|\.gradle|DerivedData|TestResults|__pycache__)/'

# 确认无硬编码密钥
git grep -nE 'sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|AKIA[A-Z0-9]{16}|BEGIN (RSA |OPENSSH |EC )?PRIVATE KEY'
```

### 仓库卫生（已核）

- 构建产物**零入库**：`**/build/`、`/ios/DerivedData/`、`/ios/build-device/`、`/ios/TestResults/`、`/dist/` 均被忽略；
- 无硬编码密钥（`desktop/` 的 `.pem` 见 4.8 说明，属测试夹具而非凭据）；
- 最大文件为 vendored 的 `mermaid.min.js`（约 2.75 MB）。

---

## 8. 已知问题与未完成事项

| # | 事项 | 状态 |
|---|---|---|
| 1 | 测试弹窗交互段依赖 Espresso 注入，无 KVM 环境跑不通 | 未改；探针已证明可改用 `performClick()` |
| 2 | 探针中的「标记唯一性」断言应并入正式无障碍回归测试 | 未做 |
| 3 | iPad 对齐版本落后于 Android（`beta.2` vs `beta.7`） | 待确认是否有意 |
| 4 | `MainActivity.java` 约 6800 行 / `NoteCanvasView.java` 约 6200 行 | 未拆分 |
| 5 | 仪器化测试需要在带硬件加速的环境中复跑 | 未做 |
| 6 | `desktop/` 测试私钥可能触发推送保护 | 待决定是否改为运行时生成 |

---

## 9. 关于本分支的范围

- 本分支是**过滤快照**，基于公开 `beta`（`26dbb78`）构建，只包含公开安全的文件；它**不是**私有开发历史的镜像，因此提交与私有提交并非逐条对应。
- 未包含维护者专属材料：`/PROJECT_MEMORY.md`、`/insight/`、`/docs/launch/`——与公开 `.gitignore` 中 `# Maintainer-only material` 段的声明一致。同样未包含内部的开发日志与工作笔记。
- 三端源码（`android/`、`ios/`、`desktop/`）、`agent-skills/`、`tools/`、`docs/` 均包含在内。
- 因此本分支可以独立审阅与合并；合并后不影响其他分支。
- ⚠️ 推送前请留意 4.8 提到的私钥与推送保护问题。

---

## 10. 复现构建与测试

```bash
# Android 单元测试
cd android && ./gradlew :app:testDebugUnitTest

# 静态检查（仓库根目录）
node tools/static-check.mjs
bash tools/tests/test-build-android-script.sh

# 桌面连接助手测试
bash desktop/connection-assistant/run-tests.sh

# iPad（在 macOS 上打开工程，选共享 scheme PadNote 与 iPad 模拟器）
open ios/PadNote.xcodeproj
```

仪器化测试需要连接设备或模拟器。

---

*本说明由提交者编写，用于辅助审阅。事实以源码与提交为准；本文件中的判断（如「该失败不是产品缺陷」）均附有对应证据位置，欢迎复核。*
