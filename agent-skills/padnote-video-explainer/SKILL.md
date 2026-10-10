---
name: padnote-video-explainer
description: Turn a PadNote formatted Markdown note into an animated explainer when explicitly requested as a video.explain.v1 task or with this Skill. Do not use for ordinary Markdown cleanup, HTML export, PPT generation, or unrelated video requests.
---

# PadNote Video Explainer

Work only in the supplied `<task-root>`. Treat `request.json` and `input/` as read-only; write only to `work/` and `output/`. Use the schemas and references in this Skill as the portable contract.

## 1. Create and review the storyboard

1. Run `npm run validate:request -- <task-root>`. Read `input/content.md` and only assets listed in `input/manifest.json`. If `research.external_research` is false, do not browse or add outside facts.
2. Create `output/lesson.ir.json` using `references/visual-grammar.md` and `references/instructional-design.md`. Preserve uncertain claims instead of inventing a source.
3. Run `npm run validate:ir -- <task-root>` and `npm run storyboard -- <task-root> --revision <n>`.
4. Return the storyboard for review. The validated `output/review.json`, not a chat response, is the review artifact. Do not synthesize or render yet.
5. For `revise`, update the IR from the user's feedback, increment the revision, and regenerate the storyboard. For `cancel`, stop without creating `output/result.json`.

## 2. Produce only after exact approval

Proceed only after the user explicitly approves the exact revision in `output/review.json`. Persist it with `npm run task:approve -- <task-root> --revision <n>`; approval binds the review and IR digests and is consumed once.

The user or invoking Agent chooses the model and speech capability. Use the Agent's own TTS, an explicitly selected external adapter, or suitable real audio already supplied for the task. Read `references/tts-contract.md` before adapter use. Read `references/hermes.md` only when Hermes is the host; read `references/qwen-adapter.md` only when the optional dedicated Qwen adapter is explicitly selected.

Before sending note content to an external model or calling a potentially paid service, follow the user's existing sharing and payment authorization. Do not ask again for an operation already authorized, and do not infer authorization from storyboard approval alone.

Read narration only from `work/task-state.json` → `approval.lesson_ir_snapshot_path`; supplied audio must match those approved scenes and exact narration.

Provide one non-silent 16-bit PCM WAV per scene in `work/audio/`, then run `npm run audio:index -- <task-root>` for backend validation. If no compatible real audio capability is configured, stop with `{"code":"tts_not_configured","message":"没有配置可用的语音合成能力"}`. Fixture audio is test-only and never counts as successful narration.

Run `npm run render -- <task-root> --approval approve --revision <n>`, then `npm run validate:result -- <task-root>`. Report completion only when validation passes and `output/result.json` exists; consult `references/qa-rubric.md` before describing quality.

Do not automatically retry a TTS or render action whose outcome may be uncertain or charged. Inspect the original task first; retry only after explicit authorization with the supported retry flow. Never delete or edit persistent state to reuse an approval. Do not claim a provider, Hermes integration, or end-to-end run was verified unless it was actually tested.
