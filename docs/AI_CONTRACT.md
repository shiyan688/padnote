# AI 网关契约与实现边界

Android 当前读取范围以 [AI_READ_SCOPE.md](AI_READ_SCOPE.md) 为准，请求取消与重试以 [AI_REQUEST_LIFECYCLE.md](AI_REQUEST_LIFECYCLE.md) 为准。下方领域 JSON 是协议设计示例，不是网络抓包。当前源码与公开 APK 的验证范围分别见 [首用改善验证](FIRST_USE_VALIDATION.md)和 [beta.13 测试记录](BETA13_TESTING.md)；本文不代表真实服务商验收。

页面代码只认 PadNote 的协议，供应商适配器再转换为具体模型 API。这样用户可以填写支持图像输入的 OpenAI-compatible API，也可以后续接入厂商原生协议。

## 本地配置

```json
{
  "provider": "openai-compatible",
  "endpoint": "https://example.com/v1",
  "model": "vision-model-name",
  "apiKeyRef": "secure-storage://padnote/default",
  "extraHeaders": {}
}
```

`apiKeyRef` 指向安全存储，不直接保存密钥。开发调试阶段可以临时输入，但不得写入笔记文件或日志。

## 领域请求

```json
{
  "requestId": "req-...",
  "action": "explain",
  "locale": "zh-CN",
  "selection": {
    "image": {
      "mimeType": "image/png",
      "base64": "..."
    },
    "bounds": { "x": 120, "y": 300, "width": 860, "height": 540 },
    "strokes": []
  },
  "output": {
    "format": "markdown",
    "stream": false
  },
  "instruction": "解释圈选内容，保留公式并指出不确定处"
}
```

`action` 首批取值：

- `explain`：分步骤讲解圈选内容。
- `organize_markdown`：整理为结构化 Markdown。
- `formalize`：改写为正式书面文字。
- `extract_tasks`：提取任务、日期和负责人。
- `custom`：使用用户自己的指令。

## 领域响应

```json
{
  "requestId": "req-...",
  "content": "# 整理结果\n...",
  "format": "markdown",
  "warnings": [],
  "usage": {
    "inputTokens": 0,
    "outputTokens": 0
  }
}
```

流式实现建议统一输出 `delta`、`warning`、`usage`、`done` 四类事件。模型拒答、限流和网络错误必须映射为 PadNote 自己的错误码，不能让页面依赖某家厂商的响应结构。

## Android 当前实现

- 已实现 OpenAI-compatible `/chat/completions` 非流式适配器，endpoint 可填写 API 基址或完整路径。
- endpoint 只接受 HTTPS，并禁止自动跟随重定向，避免 Key 被带到意外主机。
- API Key 使用 Android Keystore 生成的 AES-GCM 密钥加密后存入应用私有配置；不进入笔记 JSON、请求摘要或日志。
- 首个用户消息由文本块与 `data:image/png;base64,...` 图像块组成；后续消息携带多轮 user/assistant 上下文。
- 当前响应上限 4 MB，连接超时 20 秒、读取超时 90 秒；回答非流式。已实现本地取消：失效当前回调并中断、断开本地 HTTP 请求。工具循环有上限（当前 `MAX_AI_TOOL_ROUNDS = 4`），多轮请求仍会累积等待与消费；本地取消不代表服务端已停止或退款。供应商原生适配器不是当前能力。
- 系统提示要求数学内容使用标准 LaTeX：行间公式用 `\[...\]`，行内公式用 `\(...\)`；显示层也兼容 `$$...$$` 和 `$...$`。
- 含公式的 assistant 消息由随包 KaTeX 0.17.0 在本地 WebView 中渲染为 HTML + MathML。WebView 禁止网络加载、通用文件 URL 访问和 DOM 存储；原始 LaTeX 文本继续保留在对话上下文。

## 工具调用协议（0.11.0）

使用工具协议时，模型有两条用途不同的输出通道：

- **回复文字**（`content`）显示在对话卡片。
- **工具调用**（`tool_calls`）请求将选定内容写入或调整笔记。

工具路径把对话说明和留在纸面的内容分开。Android 另有兼容例外：用户选择写入页面、模型尚未使用工具时，会把整段回答直接插入，并显示回退提示；因此不能宣称工具是所有模型唯一的写入途径。iPad 还支持用户主动将回答插入笔记。各路径都应明确权限、来源和撤销行为。

### v1 工具集（0.16.0 起含知识库读者）

| 工具 | 权限 | 用途 |
| --- | --- | --- |
| `read_page_map` | 只读 | 页面占用/空白、笔迹聚类、文字流布局元数据、选区位置；Android 不含正文源码 |
| `write_text` | 空白处新建 | 写入文字，自动跨页，返回落点报告 |
| `set_text_flow_style` | 改动已有 | 字号、行距、行宽 |
| `move_text_flow` | 改动已有 | 移动到新位置 |
| `search_vault` | 只读 | 在知识库（数字化的格式笔记集合）内按关键词检索，返回笔记、页码与原文行 |
| `read_vault_note` | 只读 | 读取一本格式笔记的 Markdown 原文，可按页小节读取；超长截断并提示分页读取 |

Android 知识库读者只拿到 `VaultReader` 这条窄接口（list + read），没有知识库写入路径。默认 `AiReadScope.selectionOnly` 不提供知识库；用户选择具体笔记后，工具只读取本次冻结的 `AiVaultSnapshot`。命中内容会随工具结果发送到用户配置的 endpoint。只读权限限制修改能力，不代表内容不会上传；读取范围与纸面写入权限分开，见下方隐私边界。

权限分三级：只读自动执行；空白处新建自动执行；改动用户已有内容需本次任务授权。**手写笔迹完全不在工具面里**——没有文字识别时模型不知道那团笔画是什么，无从判断它该挪到哪。也没有任何删除工具。

### 四条不变量

1. **模型不做布局算术。** 工具集里没有"这页满了吗"或"加一页"。文字流自动跨页，模型一次调用写完整段内容，然后从返回值读取实际结果：占用哪几页、是否新增页面、推移了哪些已有内容。让模型预判排版会产生它自己检测不到的错误；报告实测结果不会。

2. **模型不输出坐标。** 它看不到渲染结果也量不了高度，任何它编造的数字都错得无法自检；而且坐标随设备宽度失效。改用位置约束：`relativeTo`（`selection` / flowId / clusterId）配 `position`，或 `page` 配 `bands`，或 `page` 配 `slot` 让引擎挑。解析由引擎负责，失败时返回原因与建议，而不是抛异常。

3. **空白容量用内容单位表达。** 报"340×220dp"会迫使模型用它不掌握的行高、字宽和 CJK 全宽规则换算；报"约 8 行、约 120 个中文字符"它就能直接规划。换算是分页估算器的逆运算，因此与真实分页一致。页内纵向位置量化为 8 条带——连续坐标会诱使模型编造它没有的精度。

4. **模型只建议排版，不写几何。** 语义判断（该在哪断句、字号多大、缩字号是否可接受）归模型；最终几何永远由 `layoutFlow` 计算。

### 多轮与事务

Android 工具可在多轮中继续调用，上限由 `MAX_AI_TOOL_ROUNDS` 控制；当前请求非流式，支持本地取消。取消保留已执行的笔记操作；工具已经开始时不允许整轮重放。尚未执行工具的请求可显式重试，但不保证远端幂等或不重复计费。撤销与取消是不同操作；不能把会话内工具 ID 去重等同于跨重启任务幂等。详见 [请求生命周期](AI_REQUEST_LIFECYCLE.md)。iPad 的 Task 取消及文档快照提交应按其客户端单独验证，本轮没有验证 iPad。

Android 页面地图随首轮请求直接发送，只提供布局元数据，不提供文字流正文；后续 `read_page_map` 请求其他页也遵循同一边界。额外正文来自用户明确选择的材料快照，不能用减少轮次作为扩大读取范围的理由。此说明依据 Android `PageMap` 与 `AiReadScope` 源码，不构成 iPad 本轮验收。

### 供应商无关

工具用 PadNote 自己的形式定义（`NoteToolRegistry.describe()`），再由客户端适配器转换为具体供应商的线上格式。接入厂商原生协议时新增一个转换器，不改工具逻辑。若某个 endpoint 完全忽略 `tools` 字段，应用回退到旧的整段写入路径，并明确告知用户模型未使用工具。

## OpenAI-compatible 映射原则

- `selection.image` 映射为图像内容块。
- 用户手写意图和 `instruction` 映射为文本内容块。
- `strokes` 默认不全部塞入提示词；仅在需要几何关系或笔迹重排时作为压缩 JSON 附件。
- 输出默认 Markdown，要求模型对无法辨认的字显式标记，而不是猜测。
- endpoint 必须允许用户填写完整基址；适配器负责补齐具体路径，避免页面字符串拼接。

## 隐私边界

Android 调用前展示选区图片与发送确认；默认使用选区、问题/当前会话历史和不含正文的布局地图。知识库额外材料需要选择具体笔记，并提供预览入口。远程 AI 失败时原始笔记仍留在本机并可继续编辑。

历史审查曾发现圈选外正文与知识库读取范围问题，不能将当时的描述当成当前 Android 行为。当前约束及其请求字节验收方法见 [AI_READ_SCOPE.md](AI_READ_SCOPE.md)，相关生产类为 `PageMap`、`AiReadScope`、`AiVaultSnapshot`。切换材料或接收配置时重建发送上下文；旧可见记录不应自动成为新请求正文。源码检查不替代实际请求与工具返回测试。

# Beta：页内示意图（0.18.0-beta.1）

在原有工具集上新增 `draw_diagram`，权限为 `CREATE_IN_FREE_SPACE`，使用与 `write_text` 相同的任务撤销和位置契约。

```json
{
  "code": "flowchart TD\nA[观察] --> B[提出假设]\nB --> C[实验验证]\nC --> D[修正认识]",
  "placement": {"relativeTo": "selection", "position": "below"}
}
```

- 输入：纯 Mermaid 源码，最多 8000 字符，支持 `flowchart` / `graph` / `sequenceDiagram` / `stateDiagram-v2`；工具拒绝围栏、HTML、初始化指令与点击链接。建议每图最多 8 个节点，非强制图论节点上限。
- 落点：默认相关原文下方，也可传左右侧或 `page` 与 `bands` / `slot`；位置由客户端解释，不接受像素坐标。自动插入会在该水平位置向下避让已有笔迹和文本框，放不下则整图换页，不移动原文。手动移动图表不强制避让。
- 存储：转换为 Markdown 的 mermaid 围栏，作为已有 TextFlow 保存；无新文档格式。继承文本框源码编辑、套索移动、删除、保存和笔记导入导出。旧版手写页面会显示源码，不能显示这版的新图表。
- 渲染：随包 Mermaid、strict 安全模式、禁网 WebView。2026-09-23 排版更新后按图形 viewBox 自然比例适配可用纸宽，只缩小、不放大；原子图整体分页，等待资源/高度就绪有上限。过密或极高的图仍可能缩得太小，应增大可用宽度或拆成多图；不再使用固定 4:3 容器。
- 结果：返回与 `write_text` 相同的实际页码、flowId、是否加页及位置解释。工具成功代表源码已写入；Mermaid 完整语法在 WebView 渲染时检查，失败显示可见错误，**尚未把渲染错误回传模型自动修复**。
- 边界：不是任意代码执行器，不支持 Python、自由 SVG/Canvas、函数绘图或节点级拖拽。普通 Markdown 文本框中的 mermaid 围栏也会渲染；只有单张图表独立写入的路径提供自动避让。

使用：安装 PadNote Beta → 新建笔记（或从原版导出再导入）→ 配置模型 → 圈选 → AI → “画示意图 · Beta”。建议选择“写入笔记”输出方式。Beta 与原版并存，笔记和 API 配置不共享。
