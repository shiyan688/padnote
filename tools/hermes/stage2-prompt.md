用户已明确批准 percentages 教学分镜第 2 版：approve revision 2。
使用 padnote-video-explainer Skill 执行 Stage 2。

在仓库根目录执行以下路径配置（也可由调用环境显式提供这些变量）：
```bash
export PADNOTE_PROJECT_ROOT="${PADNOTE_PROJECT_ROOT:-$(pwd)}"
export PADNOTE_HERMES_TASK_ROOT="${PADNOTE_HERMES_TASK_ROOT:-$PADNOTE_PROJECT_ROOT/tools/hermes/.runtime/tasks/percentages}"
export PADNOTE_VIDEO_SKILL_DIR="${PADNOTE_VIDEO_SKILL_DIR:-$PADNOTE_PROJECT_ROOT/agent-skills/padnote-video-explainer}"
```
任务根目录为 `$PADNOTE_HERMES_TASK_ROOT`，Skill 目录为 `$PADNOTE_VIDEO_SKILL_DIR`；运行命令前确保它们解析为绝对路径。

先读取 SKILL.md、references/hermes.md、tts-contract.md、qa-rubric.md，验证 review 的 lesson_ir_revision 确为 2。不要修改 IR、输入或分镜。
已配置 PADNOTE_TTS_COMMAND 和私有配置文件路径；使用 audio:prepare，不使用另一个 TTS 服务。不读取 api/、不打印环境变量或凭据、不安装依赖、不修改 Skill。

从 Skill 目录执行命令，每次显式将 `$PADNOTE_VIDEO_SKILL_DIR/.local-cache/node/node-v22.22.0-linux-x64/bin` 加到 PATH 前面（终端登录 shell 可能重置 PATH）。按顺序运行：
1. `npm run validate:review -- "$PADNOTE_HERMES_TASK_ROOT"`
2. `npm run audio:prepare -- "$PADNOTE_HERMES_TASK_ROOT"`
3. `PADNOTE_AGENT_BACKEND=hermes npm run render -- "$PADNOTE_HERMES_TASK_ROOT" --approval approve --revision 2`
4. `npm run validate:result -- "$PADNOTE_HERMES_TASK_ROOT"`

渲染只启动一次，等待同一进程完成，不并行启动重复渲染。不用 fixture 音频。失败时报告具体原因，不把失败写成 completed，不反复付费重合成已成功的音频。只在具体失败时读相关实现，不能修改源码。完成后提供产物路径及实际时长，明确工程校验不代表教学效果已通过观众测试。
