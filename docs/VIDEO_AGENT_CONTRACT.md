# PadNote 动画讲解视频 Agent 契约 v1

状态：冻结。任务类型：`video.explain.v1`。本文件定义 PadNote 与任意支持 AgentSkills 的外部 Agent 之间的共享文件协议；OpenClaw、Hermes 的网络适配和 PadNote 电脑端 Bridge 不属于本实现。

## 1. 产品与信任边界

正式链路是“格式笔记 → Lesson IR → 分镜审批 → TTS/字幕/动画视频 → 可审计结果”。HTML 仅用于分镜预览；正式输出是 MP4，不使用 PPTX，也不通过录屏 HTML 生成视频。

Agent 的自然语言回复不表示任务成功。分镜阶段只认 `output/review.json`，最终阶段只认 `output/result.json`。`request.json` 和 `input/` 只读；实现只可写 `work/`、`output/`。任何必需文件缺失、哈希不符、Schema 不通过、越界路径或符号链接都必须失败。

## 2. 标准任务工作区

```text
<task-root>/
├── request.json
├── input/
│   ├── manifest.json
│   ├── content.md
│   └── assets/
├── work/
└── output/
```

所有 JSON 中的路径均以 `/` 分隔，必须是相对于 `<task-root>` 的规范安全路径：非空、非绝对路径、不得含 `.` 或 `..` 段、不得含反斜杠、NUL、URL scheme；解析后的真实路径必须仍在任务目录内。输入树和产物不得是符号链接。

`input/manifest.json` 列出输入树中除 manifest 自身外的全部普通文件，按 `path` 字典序排列；每项包含 `path`、`media_type`、`size_bytes`、`sha256`。v1 中 `source.entrypoint` 必须是 `input/content.md`，其 MIME 必须是 `text/markdown`。

`source.bundle_sha256` 是对 manifest 所声明文件的确定性树摘要：依次拼接每项 UTF-8 字节 `path + "\\0" + size_bytes + "\\0" + sha256 + "\\n"`，再取 SHA-256。manifest 自身不进入该摘要。验证器同时核对清单完备性、实际大小与每个文件的 SHA-256。

## 3. request.json

`request.json` 必须符合 `schemas/request.schema.json`。固定字段包括：

- `schema_version = "1.0"`
- `task_type = "video.explain.v1"`
- 唯一 `task_id`
- `source`：笔记 ID、正整数 revision、标题、BCP-47 风格语言、入口和 bundle SHA-256
- `brief`：受众、单一学习目标、先修知识、正整数目标时长（秒）、`9:16`、`clean-academic` 和可选用户指令
- `research.external_research`
- `review.storyboard_required = true`
- `voice.profile` 与 `voice.speed`

请求不得包含 API Key、token、secret、authorization 字段，不得直接包含 CSS、HTML、React/Revideo/Remotion 组件代码、FFmpeg 命令或 base64 数据。`external_research=false` 时不得联网补充事实；为 true 时，新增事实必须在 claim 的 `source_refs` 中记录可审计来源。

## 4. Lesson IR v1

`output/lesson.ir.json` 必须符合 `schemas/lesson-ir.schema.json`，并满足：

- `source.bundle_sha256`、`note_id`、`note_revision` 与请求一致。
- 每个 scene 只有一个非空 `learning_objective`，必须有 `narration` 和 `visual`。
- 视觉类型为 `title`、`formula_steps`、`concept_map`、`process`、`comparison`、`annotated_source`，以及 2026-09-05 为教学样例加入的 `quantity_change`（正数量固定比例变化，`unit` 与 2–4 个含 `label`/`value` 的 `states`）；未知类型直接失败。旧工作器不支持新类型，含该类型的任务必须使用更新后的 Skill，不能静默降级。
- 存在动画时，`animation_intent.kind` 与 `explanation` 均非空；explanation 必须说明教学目的。
- `claim_ids` 只能引用真实 claim；每个 claim 必须有非空 `source_refs`，或 `verification="uncertain"`。
- `narration` 与任一 `screen_text` 规范化后不得整句相同。
- IR 中禁止 CSS、HTML 布局、React/Revideo/Remotion 代码、FFmpeg 命令、绝对屏幕坐标堆砌、大段 base64 和无来源外部素材。
- render profile 固定 `aspect_ratio="9:16"`、1080×1920、30 fps。

视觉 data 的语义形状由 Schema 固定：标题含 title/subtitle；公式步骤含 `steps`；概念图含 nodes/edges；流程含 steps；比较含 left/right；标注源含 `asset_path` 与 annotations。资产只能引用 `input/assets/` 内文件。

## 5. 两阶段协议

### 阶段一：分镜

阶段一只生成脚本、IR 与预览，不得生成完整视频。全部分镜产物关闭并校验后，最后原子写入 `output/review.json`：

```text
output/lesson.ir.json
output/storyboard.html
output/storyboard-*.png
output/review.json
```

review 的 `status` 固定为 `awaiting_storyboard_review`，`lesson_ir_revision` 从 1 开始。`lesson_ir.path` 和 artifact path 相对 `output/`；每项的 MIME、大小和 SHA-256 必须匹配实际文件。storyboard HTML 必须自包含，不得引用 HTTP(S)、协议相对 URL 或运行时网络资源。

### 审批

- `approve`：按当前 `lesson.ir.json` 继续；不得隐式修改 IR。
- `revise`：依据用户反馈修订 IR 和分镜，提高 revision，重新原子写 review；不得开始完整渲染。
- `cancel`：终止，不生成 `result.json`。

第二阶段入口必须收到显式 `approve`，并核对批准的 IR revision 与当前 review。

## 6. TTS 与 audio-manifest

Skill 先检查当前 Agent 是否提供 TTS；本地工作器接受外部 TTS 生成的逐场景 WAV。Agent 将每幕写为 `work/audio/<scene-id>.wav` 后运行 `npm run audio:index -- <task-root>`，由确定性脚本解析并生成 `work/audio-manifest.json`。供应商配置只能由环境变量或 Agent 自身能力注入，不写入请求或仓库。若未配置，正式音频生成入口必须快速失败并输出：

```json
{"code":"tts_not_configured","message":"没有配置可用的语音合成能力"}
```

音频写入 `work/audio/`，`work/audio-manifest.json` 符合对应 Schema。每个 scene 恰有一个真实音频 clip；时长由解析实际音频容器获得，不用字数估算。禁止无声视频、测试音冒充正式配音或自动注册收费服务。测试可显式使用 fixture 音频，但不得将其报告为正式 TTS 全链路。

## 7. 渲染、字幕与 QA

默认且唯一视频引擎为 MIT 许可的 Revideo。渲染器只消费 Lesson IR、audio-manifest 和本地 input assets；派生缓存只写 `work/`，交付只写 `output/`。时间轴由真实 clip 时长驱动。

完整渲染前必须生成每场景关键静帧并通过 9:16 安全区检查。公式必须由数学排版器可视化，不显示 LaTeX 源码；流程/图关系必须转换为图形节点和连线；运行时不得加载网络资源；第三方遥测默认关闭。

输出至少包括 `explanation.mp4`、`captions.srt`、`thumbnail.png`、`render.manifest.json`、`qa-report.json`。字幕最后结束时间必须精确等于最后旁白结束时间；composition 总帧数固定为 `ceil(total_duration_ms * fps / 1000)`，manifest、实际视频和 QA 必须一致。render manifest 记录每场景音频起止、帧范围、关键帧、Revideo 版本、实际 FFmpeg 路径/version/build configuration 与许可证信息。

## 8. result.json 与提交规则

`output/result.json` 必须符合 `schemas/result.schema.json`。其 source、compiler、Lesson IR revision/hash、全部 artifacts 和 QA 结果必须匹配实际状态。artifact path 相对 `output/` 且必须留在 `output/` 内；MIME、字节数、SHA-256 均须复核。

只有 QA 全部通过时才可写 `status="completed"`。所有产物必须已完成、关闭并校验，随后以“同目录临时文件 → rename”最后原子写 `result.json`。验证器通过比较 mtime，要求 `result.json` 不早于其声明的全部产物。取消任务不写 result；失败任务不得伪装 completed。

## 9. 版本与修订

六份 JSON Schema 是本契约的可执行部分。实现若发现契约问题，只在完成报告提出最小修订建议，不擅自改变上述语义。v1 不包含 Codex 接入、PadNote Bridge、OpenClaw/Hermes 网络适配、PPTX 或其他视频引擎。
