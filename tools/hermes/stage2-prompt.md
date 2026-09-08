用户已明确批准 percentages 教学分镜第 2 版：approve revision 2。
使用 padnote-video-explainer Skill 执行 Stage 2。
任务根目录：/public/home/wangyg/padnote/tools/hermes/.runtime/tasks/percentages
Skill 目录：/public/home/wangyg/padnote/agent-skills/padnote-video-explainer

先读取 SKILL.md、references/hermes.md、tts-contract.md、qa-rubric.md，验证 review 的 lesson_ir_revision 确为 2。不要修改 IR、输入或分镜。
已配置 PADNOTE_TTS_COMMAND 和私有配置文件路径；使用 audio:prepare，不使用另一个 TTS 服务。不读取 api/、不打印环境变量或凭据、不安装依赖、不修改 Skill。

从 Skill 目录执行命令，每次显式将 /public/home/wangyg/padnote/agent-skills/padnote-video-explainer/.local-cache/node/node-v22.22.0-linux-x64/bin 加到 PATH 前面（终端登录 shell 可能重置 PATH）。按顺序运行：
1. npm run validate:review -- <任务根目录>
2. npm run audio:prepare -- <任务根目录>
3. PADNOTE_AGENT_BACKEND=hermes npm run render -- <任务根目录> --approval approve --revision 2
4. npm run validate:result -- <任务根目录>

渲染只启动一次，等待同一进程完成，不并行启动重复渲染。不用 fixture 音频。失败时报告具体原因，不把失败写成 completed，不反复付费重合成已成功的音频。只在具体失败时读相关实现，不能修改源码。完成后提供产物路径及实际时长，明确工程校验不代表教学效果已通过观众测试。
