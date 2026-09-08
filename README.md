# PadNote

面向平板与手写笔的 AI 原生笔记原型。当前可安装主线是 Android 平板应用，Huawei MatePad Air / HarmonyOS 4.2 是首台真机；后续平台顺序为 Android 平板优先、iPad 跟进，不把产品架构绑定到华为。仓库暂时保留早期 HarmonyOS ArkUI 原生工程，但不与 Android 主线同步扩功能。

项目的当前进度、已确认决策、风险和下一步统一维护在 `PROJECT_MEMORY.md`。

## 当前可用

- 书架采用统一视觉系统（白卡、书写蓝主色、药丸按钮），首启有三步上手引导卡
- 知识库一级可见：空状态即显示转换步骤与隐私说明，标题旁「这是什么？」有完整解释；AI 卡片内提示可跨笔记提问
- ArkUI `Canvas` 原生书写画布
- 记录每个点的坐标、时间和原始压力值
- 按压力动态调整线宽
- 连续 HSV 调色盘与明度调节
- 画笔 0.3–8.0 dp、橡皮 8–64 dp 独立粗细滑杆
- 撤销、清空、手动保存，以及停笔 600 ms 后自动保存当前笔记
- 启动书架、多笔记新建/打开/重命名/删除，按更新时间排列
- 应用私有目录中的原子索引与独立笔记文件；中断写入后可从 `.bak`/`.tmp` 恢复
- 首次升级自动把旧 `padnote-current.json` 复制为书架中的“旧版笔记”，旧文件继续保留
- 通过系统文件选择器无损导入/导出 `.padnote.json`，不申请文件存储权限
- 新建笔记时可选纸张（白纸／横线／方格／点阵）、比例（适应屏幕／A4）和方向（竖／横），并有实时缩略预览；A4 严格保持 1:1.414，两侧可能留白。样式在创建时确定，之后不可更改
- 连续纵向多页面；工具栏可手动加页，书架显示页数
- 默认仅笔模式下单指拖动画布、双指缩放；关闭仅笔后单指编辑、双指仍可导航
- 到达最后一页底部后继续上滑会实时拉出下一页纸张；露出 1/3 后松手添加，不足则平滑回弹
- 页面内可插入 PPT 式透明文字对象；点选后可拖动/缩放，双击编辑，默认 LaTeX、可切换 Markdown
- 长文字默认保持可读字号并按段落、公式和代码块自动跨页；字号可手动调整，套索任一片段即可统一编辑、移动、改宽或删除整组
- 拖动跨页文字的任一片段都会整组移动，拖动时以半透明虚线预览重排后的落点；拖到页面上/下边缘停住约 0.5 秒会自动翻到相邻页，可把文字移到当前看不到的页
- Android M2：纯图标工具栏、画笔、局部矢量橡皮、套索选择
- 选区可用手写笔或手指拖动，并支持复制、删除、取消
- 覆盖书写、擦除、移动、复制、删除和清空的 30 步撤销/重做
- 点击画笔/橡皮图标弹出圆角粗细设置卡，顶部不再常驻尺寸滑杆
- 彩色环调色盘、五个常用快捷色，画笔图标同步显示当前墨水色
- Android M3.1：套索隐私遮罩、最大边 1600 px 的圈内 PNG、AI 讲解/Markdown 操作和发送前预览
- Android M3.2：可拖动、最小化的浮动 AI 对话卡，支持讲解/Markdown 预设、自由输入和多轮追问
- AI 回答中的 `\[...\]`、`\(...\)`、`$$...$$` 和 `$...$` 由随包离线 KaTeX 编译为可视公式与 MathML
- 自定义 OpenAI-compatible HTTPS endpoint/model，API Key 由 Android Keystore 使用 AES-GCM 加密
- 多配置档案管理：多套 AI 配置一键切换（cc-switch 式），内置智谱/DeepSeek/阿里百炼/MiniMax/Kimi 厂商预设（含阿里 Token Plan 与 MiniMax Token Plan 订阅预设，附 Key 获取指引），旧单配置自动迁移
- 可选"转写 + 回答"两段式路线：视觉模型先把圈选手写转成 Markdown/LaTeX 文本并显示供核对，纯文本回答模型再执行讲解与笔记写入；追问复用转写结果，不再重复上传图片
- 知识库：手写笔记一键数字化为 Obsidian 兼容的 Markdown 格式笔记（公式 LaTeX、流程图 Mermaid），书架内阅读/导出/删除，原笔记修改后提示重新生成；Mermaid 与 KaTeX 均随包离线渲染
- AI 可读取知识库：`search_vault` 按关键词跨笔记检索（返回笔记、页码与原文行），`read_vault_note` 读取整本或按页读取格式笔记；两者均为只读，模型不能改动知识库

## Huawei MatePad Air / HarmonyOS 4.2 安装

本设备路线使用已签名的 Android 兼容调试包：`dist/PadNote-MatePadAir-0.17.4-debug.apk`。

SHA-256：`9e9be5b9982290f526298cb635792371e4c4e686990c14a39df83bd1e043bd96`

1. 把 APK 传到平板后点击安装；若系统询问是否允许当前文件管理器安装外部应用，只需对该安装来源临时授权。
2. 启动后先进入书架。可以新建笔记，点卡片打开已有笔记，或从系统文件选择器导入 `.padnote.json`；卡片的“更多”支持重命名和确认删除。
3. 从 0.1.0–0.5.0 覆盖升级时，旧的单笔记会自动复制到书架并命名为“旧版笔记”；原始旧文件不会被删除。
4. 编辑器左上角书架图标返回书架；工具栏的保存和导出图标分别立即保存、通过系统位置选择器导出当前矢量笔记。书架卡片也可直接导出。
5. 默认“仅笔”开启：手写笔负责编辑，单指拖动镜头，双指捏合缩放；套索选框内仍可用单指拖动选区。关闭“仅笔”后单指可书写/擦除/套索，第二根手指落下会取消这次未完成编辑并切换为双指导航。
6. 工具栏的叠页加号图标会在文档末尾添加页面并跳转过去。浏览已有页面和普通上拖始终 1:1 跟手；只有越过末页、下一页纸张真正开始露出的创建段才使用连续橡皮筋阻力。实际露出高度达到 1/3 后松手，预览页原地转为正式页，不足则平滑回弹。
7. 点画笔或橡皮图标，会在图标下方打开圆角设置卡；卡片内可看实时笔画/擦除范围预览，并用滑杆调节粗细。
8. 彩色环图标打开连续 HSV 调色盘；其旁五个色点可直接选常用墨蓝、蓝、朱红、绿和紫色。画笔图标会同步显示当前墨水颜色。
9. 橡皮只移除圆形游标覆盖的部分，快速划动时也会自动补齐擦除轨迹；一次连续擦除可用一次撤销恢复。
10. 选择虚线套索图标，圈住笔画并闭合；蓝色框出现后可用手写笔或手指直接拖动。
11. 选中内容后，点套索旁的闪光 AI 图标打开浮动卡片。按住蓝色标题区域可拖动；点“—”最小化，点“□”恢复，点“×”关闭。请求在最小化后仍会继续。
12. 卡片内可直接点“讲解”或“整理 Markdown”，也可在输入框写自己的要求；收到回答后继续输入即可多轮追问。模型返回的标准 LaTeX 会在本机编译为可视公式，公式显示不访问网络。
13. 首次发送会要求配置 AI。配置以“档案”管理：点 AI 卡片设置图标打开配置列表，可新建多套配置并一键切换当前使用的档案；新建时可选厂商预设（智谱 BigModel／DeepSeek／阿里云百炼／MiniMax／Kimi）自动填地址与推荐模型，再粘贴 API Key 即可。路线二选一：**直连多模态**（一个支持图像的模型看图并回答），或**转写 + 回答两段式**（视觉模型先把圈选手写转成 Markdown/LaTeX 文本并显示在卡片供核对，纯文本回答模型再执行讲解与笔记写入；同一选区的追问复用转写结果，不再重复上传图片）。只接受 HTTPS，Key 经 Android Keystore 使用 AES-GCM 加密，不写入笔记或日志；0.13.x 及更早的单配置会在升级后自动迁移为名为“默认配置”的直连档案。
14. 每次新建对话首次发送前，应用会列明上传 PNG 的尺寸、大小和目标 endpoint；确认后才发送。模型响应当前为非流式，完成后一次性显示在卡片中。
15. 点工具栏文字图标可插入 PPT 式页面文字对象。完成后只显示编译内容，需用套索圈中后才显示边框、右上删除和右下宽度调整；双击任一片段会直接在对象内编辑整组统一源码。默认按 LaTeX 编译，也可切换 Markdown；源码和格式切换会在 90 ms 防抖后实时编译。
16. 长内容不会自动缩小到难以阅读，而是保持当前字号，按段落、标题、列表、公式和代码块边界排到后续页面；页面不足时自动新增。编辑栏的 `A−/A+` 可在 10–32sp 间手动改字号并触发整组重排。套索任一页片段会选中整组，移动、调整宽度、删除和撤销均以同一文字流处理。
17. 支持 function calling 的模型会自行判断哪些内容值得留在笔记里：解法、讲解和整理后的要点写进页面，辨认过程和说明只留在对话卡片。工具栏的「写入页面／仅卡片」现在的含义是**是否允许模型改动笔记**；选「仅卡片」时模型只能读取页面结构。模型一次写入无论跨几页，都可用一次撤销完整回退。若所用模型不支持 function calling，应用会退回旧的整段写入方式并在卡片中说明。
18. 套索选中文字对象后，左上角出现 `A−／A+` 可直接调字号，不需要进入编辑状态；字号作用于整个文本流，改完自动重排。行距在双击进入编辑面板后用 `⇱／⇲` 调节，范围 1.1–2.0。
19. 拖动跨页文字的任意片段都会移动整组，手指下的片段落在放手处；拖动过程中虚线框显示重排后各片段的落点。要移到当前看不见的页，把片段拖到页面上边缘或下边缘停住，画布会自动滚到相邻页。
20. 0.11.1 与 0.1.0–0.11.0 使用同一包名和调试签名，可直接覆盖安装并保留应用私有数据。旧笔记会在打开时自动迁移为新的文本流格式。
21. 书架笔记卡片的"更多 → 转为格式笔记"可把整本手写笔记数字化为知识库格式笔记：逐页发送给视觉模型转写为 Markdown（数学公式 LaTeX、手绘流程图 Mermaid），已有文字对象原样嵌入；产物为 Obsidian 兼容的 `.md` 文件，可在书架"知识库"区块阅读、导出或删除。数字化会上传整页内容（不再是仅圈选区域），转换前会明确确认。原笔记修改后条目会提示重新生成。Mermaid 与 KaTeX 均随包离线渲染，显示不访问网络。
22. AI 现在能读知识库了：圈选后提问"我之前哪本笔记讲过……"之类跨笔记问题时，模型会先用 `search_vault` 检索全部格式笔记（返回命中行所在笔记与页码），再用 `read_vault_note` 读取原文，然后照常决定哪些内容经 `write_text` 写进页面。检索命中的文本会发送到你配置的模型 endpoint；知识库本身对模型严格只读。

APK 包名为 `com.padnote.android.debug`，最低 Android API 为 24，只声明 AI 调用所需的 `INTERNET` 权限；系统文件选择器不需要广泛存储权限。笔记位于应用私有目录的 `notes/<id>.json`，索引为 `padnote-index.json`。新保存文件使用 schemaVersion 6，除页面几何、页数、纸张样式、相对缩放和视口中心外，记录每个 LaTeX/Markdown 文本流的统一源码、格式、字号、行宽和页相对锚点；跨页片段不再逐个保存，而是在打开笔记时按当前排版器重新计算，因此文件体积不随答案长度重复膨胀。schemaVersion 1/2/3/4/5 旧笔记仍可读取，打开时自动迁移并在下次保存时升级。当前是开发调试签名；商业发布前将改用独立 release 签名。

## 在真机运行

当前工程目标是 HarmonyOS 5.0.5 / API 17，原因是压感字段从 API 15 起提供，API 17 对现有 NEXT 平板更稳妥。

本节专指未来的 ArkUI 原生 HAP 路线；HarmonyOS 4.2 的 MatePad Air 请优先使用上面的 APK。

### 安装包说明

- 当前原生鸿蒙工程生成的是签名后的 `.hap`，这是设备安装和运行的基本单元。
- `.app` 是提交应用市场的发布包，内部包含 HAP/HSP。
- `.apk` 是 Android 路线的产物；只有仍兼容 Android 应用的旧鸿蒙设备才可能使用，不能替代 HarmonyOS NEXT 原生 HAP。

1. 使用匹配 HarmonyOS 5.0.5 SDK 的 DevEco Studio 打开本目录。
2. 若 IDE 提示 Hvigor 版本不匹配，使用 `Tools > Upgrade Dependencies` 让工程构建插件与本机 DevEco 配套。
3. 在 `File > Project Structure > Signing Configs` 中开启自动签名。
4. 连接已开启开发者模式的鸿蒙设备，选择 `entry` 后运行。
5. 首次测试请依次验证：手写、轻重压线宽、撤销、退到桌面再打开后的恢复。

本执行环境没有 DevEco Studio、HarmonyOS SDK 或 `hdc`，因此源码已经完成静态校验，但首轮 HAP 编译和真机触控验证需要在你的开发机上进行。若你的设备系统低于 API 15，需要先升级系统或暂时移除压力字段。

仓库静态检查可运行：

```bash
node tools/static-check.mjs
```

本仓库配套的构建工具链位于 `/public/home/wangyg/padnote-tools/`（JDK 17 + Android SDK 35 + Gradle 8.9），Gradle 缓存统一放在其中的 `gradle-home/`，仓库目录不保存工具缓存。构建脚本默认使用它，无需任何环境变量：

```bash
tools/build-android-apk.sh
```

### OpenClaw / Hermes 协议探针

外部 Agent 对接当前处于 Phase 0：仓库已提供独立 JVM 探针，不把实验连接代码塞进 APK。Hermes 探针验证官方 `/v1/capabilities` 的 run、SSE、停止与审批能力；OpenClaw 探针完成 v4 WebSocket challenge、Ed25519 设备签名、配对响应和只读 health RPC。完整边界见 `docs/AGENT_INTEGRATION.md`。

```bash
PADNOTE_AGENT_URL=https://agent.example.com \
PADNOTE_AGENT_TOKEN='本地凭据' \
tools/run-agent-probe.sh hermes

PADNOTE_AGENT_URL=wss://gateway.example.com \
PADNOTE_AGENT_TOKEN='gateway bootstrap token' \
tools/run-agent-probe.sh openclaw
```

OpenClaw 首次连接可能返回 `PAIRING_REQUIRED`；在电脑端审核并批准输出中的 requestId 后，用同一 state 目录再次运行。探针不会发送任何笔记内容，也不申请写权限。

在其他机器上自备 JDK 17 和 Android SDK 35 时，用环境变量覆盖默认路径即可：

```bash
JAVA_HOME=/path/to/jdk17 ANDROID_SDK_ROOT=/path/to/android-sdk tools/build-android-apk.sh
```

### 无界面 Android 平板回归

本机模拟器资产与项目分离：SDK/系统镜像在 `/public/home/wangyg/padnote-tools/android-sdk`，唯一的 API 35 平板 AVD 在 `/public/home/wangyg/padnote-tools/avd`，日志、PID、测试结果和模拟器临时文件统一放在 `/public/home/wangyg/padnote-tools/emulator-runtime`；仓库只保存脚本和测试源码，不使用 `/tmp` 保存 PadNote 运行态。运行下面入口会启动模拟器、构建并安装应用和测试 APK、执行书架 UI 冒烟测试，最后自动关机：

```bash
tools/emulator/run-ui-smoke.sh
```

模拟器固定为低优先级、最多 4 个 CPU、4 GB 客体内存且无窗口/音频/快照，不会后台常驻；若 `/dev/kvm` 可用会自动启用硬件加速。当前宿主未开放 `/dev/kvm`，API 35 x86_64 镜像在软件 TCG 下 20 分钟内无法保持 Android framework 稳定，UI 测试 APK 已编译但尚未实际跑通；脚本和 AVD 已就绪，宿主开放 KVM 后可直接重跑。无论模拟器是否加速，手写延迟、压感、手掌误触、帧率和跟笔性仍必须在实体平板上测。

## 代码入口

- `entry/src/main/ets/pages/Index.ets`：画布、触摸采样和工具栏
- `android/app/src/main/java/com/padnote/android/NoteCanvasView.java`：MatePad Air 的 Android 墨迹层
- `android/app/src/main/java/com/padnote/android/NoteStore.java`：多笔记索引、迁移、原子存储和导入校验
- `android/app/src/main/java/com/padnote/android/MainActivity.java`：启动书架、编辑器、系统导入导出和 AI 卡片
- `android/app/src/main/java/com/padnote/android/AiMathWebView.java`：禁用网络的离线 LaTeX/KaTeX 显示层
- `android/app/src/main/java/com/padnote/android/CompiledTextWebView.java`：文本框的离线 LaTeX/Markdown 编译显示层
- `android/app/src/main/java/com/padnote/android/TextFlow.java`：页面文字的唯一事实源与唯一写入点（源码、字号、行距、页相对锚点）
- `android/app/src/main/java/com/padnote/android/NoteTextBox.java`：由文本流派生的单页片段，不持有独立内容也不参与持久化
- `android/app/src/main/java/com/padnote/android/NoteTools.java`：模型可调用的四个工具（读页面结构、写入文字、调排版、移动）
- `android/app/src/main/java/com/padnote/android/PageMap.java`：给模型看的页面结构——8 条带占用/空白、空白容量以行数与字数表示、笔迹位置聚类
- `android/app/src/main/java/com/padnote/android/PlacementResolver.java`：把「写在选区下方」这类位置约束解析为真实几何
- `tools/layout-calibration/`：用随包真实 KaTeX 校准分页估算器；改渲染层 CSS 后必须重跑
- `entry/src/main/ets/model/InkModel.ets`：可演进的原始笔迹格式
- `entry/src/main/ets/services/NoteStore.ets`：沙箱 JSON 存取
- `docs/ARCHITECTURE.md`：产品架构与接下来怎么做
- `docs/DESIGN_LANGUAGE.md`：设计语言与审美契约（改任何 UI 前必读）
- `docs/AI_CONTRACT.md`：多模态模型的供应商无关接口草案
- `docs/AGENT_INTEGRATION.md`：OpenClaw/Hermes 协议、权限边界与任务/产物方向
- `tools/agent-probe/`：独立 JVM 协议探针和离线 Hermes/OpenClaw 假服务测试

## 下一里程碑

在 MatePad Air 上覆盖安装 0.16.0，按顺序验收：

1. **AI 读取知识库（0.16.0 新增）**：先确保至少有一本数字化格式笔记；圈选手写内容提问"我之前哪本笔记讲过 X""对比这次和上次记的 Y"——确认模型先检索（卡片出现"已执行 search_vault"）、引用注明出处、写入页面的仍只是值得留存的内容；空知识库时提问应得到"知识库为空"类提示而不是报错。
2. **知识库数字化**：找一本含手写公式和推导流程的笔记执行"转为格式笔记"，重点看 Mermaid 流程图转化质量与阅读器内 Mermaid/KaTeX 渲染；编辑原笔记后确认过期标记出现；导出的 `.md` 拷入 Obsidian 验证兼容。
3. **配置迁移与直连回归**：打开 AI 配置确认旧单配置已自动迁移为"默认配置"档案且能直接发送；直连路线行为应与 0.13.2 完全一致。
4. **两段式路线**：新建两段式档案（预设"阿里云百炼"：qwen3.5-ocr 转写 + qwen3.7-flash 回答），圈选手写公式发送——确认转写结果显示在卡片、回答正常写入页面、同一选区追问不再上传图片。
5. **档案管理**：多档案间切换/编辑/删除，确认当前档案即时生效。

同时补验 0.13.2 的周期性手写停顿是否消失（连续快写 30–60 秒），以及 function calling 端到端（需配置支持工具调用的模型）：模型只把值得留存的内容写进页面、一次 AI 写入能用一次撤销完整回退。

随后是模型写入前的半透明预览、MiniMax-M3 思维链回传适配，以及 AI 请求的流式与取消（0.16.0 已交付知识库读取工具，跨笔记问答/MOC 的进一步形态视真机反馈再定）。再往后是页面删除/重排、书架缩略图、HiNote/PDF/图片兼容和 Markdown/PDF 导出。

## 许可状态

PadNote 自研代码尚未对外授予开源许可（`UNLICENSED`），代码所有者仍可自行商业化。Android 公式显示随包包含 MIT 许可的 KaTeX 0.17.0，完整许可证和版本来源见 `THIRD_PARTY_NOTICES.md`；这类宽松许可允许商业使用，但正式发布仍应保留版权与许可声明。
