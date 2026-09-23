使用 padnote-video-explainer Skill，为以下任务执行 Stage 1。

在仓库根目录执行以下路径配置（也可由调用环境显式提供这些变量）：
```bash
export PADNOTE_PROJECT_ROOT="${PADNOTE_PROJECT_ROOT:-$(pwd)}"
export PADNOTE_HERMES_TASK_ROOT="${PADNOTE_HERMES_TASK_ROOT:-$PADNOTE_PROJECT_ROOT/tools/hermes/.runtime/tasks/percentages}"
export PADNOTE_VIDEO_SKILL_DIR="${PADNOTE_VIDEO_SKILL_DIR:-$PADNOTE_PROJECT_ROOT/agent-skills/padnote-video-explainer}"
```
任务根目录为 `$PADNOTE_HERMES_TASK_ROOT`，Skill 目录为 `$PADNOTE_VIDEO_SKILL_DIR`；运行命令前确保它们解析为绝对路径。

这是用户授权的自编教学样例。先阅读 request.json、input/content.md 和 Skill 的必要 references、schemas；禁止读 api/ 或任何凭据文件，禁止打印环境变量。不调用外部研究，不安装依赖，不修改 Skill 或输入。只写该任务的 work/ 和 output/。

简要规划一次就写 IR；不要反复讨论同一个方案。只在校验出现具体失败后才读实现代码。执行 npm 命令时显式在 PATH 前加 `$PADNOTE_VIDEO_SKILL_DIR/.local-cache/node/node-v22.22.0-linux-x64/bin`；已安装全部视频依赖，不需检查系统 Node 或重复探索目录。

目标不是逐句复述笔记。以“相同的20%为什么不抵消”为问题，用 quantity_change 展示100→80→96的变化（unit 元，states 标签简短），指出两次的基数，再引出乘法与自测。旁白用自然中文口述数学，不直接朗读 LaTeX。面向初中生，约60秒，不强求固定镜头数。允许报告当前视觉组件的局限，不能谎称已达到高水平科普视频质量。

将 lesson.ir.json 写入 output/；从 Skill 目录执行 validate:ir、storyboard --revision 1 和 validate:review。命令都需要使用绝对任务路径。只在校验失败时根据具体错误修正。完成后停止，返回分镜路径与当前视觉限制。不要生成音频、不要渲染视频，不要自行批准分镜。
