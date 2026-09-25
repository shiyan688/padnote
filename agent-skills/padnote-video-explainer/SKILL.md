---
name: padnote-video-explainer
description: Generate a two-stage animated explainer video from a PadNote formatted Markdown note. Use only for an explicit video.explain.v1 task, an explicit request to turn a PadNote formatted note into an animated explainer, or an explicit $padnote-video-explainer invocation. Do not use for ordinary Markdown cleanup, HTML export, PPT generation, or general video requests.
---

# PadNote Video Explainer

Operate only on the supplied `<task-root>`. Treat `request.json` and `input/` as read-only. Write only `work/` and `output/`. The portable executable contract is the schemas and references in this directory. The PadNote repository may also provide `docs/VIDEO_AGENT_CONTRACT.md` as integration documentation, but it is not required at runtime.

## Stage 1: author and review the storyboard

Use the schemas and references as the authoring contract. Do not read validator or renderer implementation files before a concrete failure requires diagnosis. Make a compact teaching plan once, write the IR, and use validation feedback rather than repeatedly reconsidering equivalent scene plans.

1. Run `npm run validate:request -- <task-root>` from this skill directory.
2. Read `input/content.md` and only the assets declared by `input/manifest.json`. When `research.external_research` is false, do not browse or add outside facts.
3. Author `output/lesson.ir.json` using the supported visual types in `references/visual-grammar.md`. Follow `references/instructional-design.md` and preserve uncertain claims instead of resolving them without a source.
4. Run `npm run validate:ir -- <task-root>`.
5. Run `npm run storyboard -- <task-root> --revision <n>`. This writes the offline HTML, one PNG per scene, and writes `output/review.json` last.
6. Return the storyboard for review. Do not prepare audio or render video in this stage. Success is the validated `output/review.json`, not the natural-language response.

For `revise`, update the Lesson IR from the user's feedback, increment the revision, repeat steps 4–6, and do not render. For `cancel`, stop and do not create `output/result.json`.

## Stage 2: approved audio and render

Proceed only after explicit `approve` for the exact `lesson_ir_revision` in `output/review.json`.

1. Persist that approval first with `npm run task:approve -- <task-root> --revision <n>`. It is bound to the current review and Lesson IR digests and can be consumed once.
2. If the Agent has a TTS capability, read narration only from the frozen IR named by `work/task-state.json` → `approval.lesson_ir_snapshot_path`, synthesize one 16-bit PCM WAV per scene into `work/audio/<scene-id>.wav`, then run `npm run audio:index -- <task-root>`.
3. Otherwise use the provider-neutral adapter described in `references/tts-contract.md`: set `PADNOTE_TTS_COMMAND` and run `npm run audio:prepare -- <task-root>`.
4. If neither is available, return exactly `{"code":"tts_not_configured","message":"没有配置可用的语音合成能力"}` and stop. Never make a silent video or substitute fixture audio.
5. Run `npm run render -- <task-root> --approval approve --revision <n>`.
6. Treat the task as completed only if `npm run validate:result -- <task-root>` passes and `output/result.json` exists.

Use `npm run task:cancel -- <task-root>` for cancellation. A failed or killed
execution is not replayed automatically; inspect it before granting an explicit
`--retry-uncertain` approval. Never delete or edit the persistent state to reuse
an approval.

`--allow-fixture-audio` is test-only. Use it only for repository fixtures and disclose that the result is not a real TTS end-to-end run.

Read `references/tts-contract.md` before audio generation and `references/qa-rubric.md` before reporting completion.

When Hermes Agent is the host, read `references/hermes.md` before invoking TTS or rendering.
