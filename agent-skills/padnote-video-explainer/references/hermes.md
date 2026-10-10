# Hermes host

Read this guide only when Hermes is the Agent hosting the Skill. Choose the model and TTS capability in the Hermes configuration that the user selected. PadNote does not select a vendor, receive provider credentials, or promise that a real Hermes installation has been tested.

Expose the parent Skill directory in the active Hermes profile:

```yaml
skills:
  external_dirs:
    - /absolute/path/to/padnote/agent-skills
```

Invoke `/padnote-video-explainer` with the absolute task root and request Stage 1. Stop after `output/review.json` validates. Do not synthesize or render until the user approves the exact storyboard revision.

After approval, persist the approval and choose one configured audio path. If Hermes has a TTS tool that can write the required WAV files, read only `approval.lesson_ir_snapshot_path` from `work/task-state.json` and synthesize each scene's exact narration to `work/audio/<scene-id>.wav`. Do not read narration from the mutable `output/lesson.ir.json` after approval. Then run:

```bash
npm run task:approve -- <task-root> --revision <n>
npm run audio:index -- <task-root>
PADNOTE_AGENT_BACKEND=hermes npm run render -- <task-root> --approval approve --revision <n>
npm run validate:result -- <task-root>
```

If Hermes TTS is not suitable, use an explicitly selected adapter according to [the provider-neutral TTS contract](tts-contract.md). Its adapter output must still pass `audio:index`; do not relabel compressed audio as WAV. Do not copy or print provider configuration or credentials in Agent messages.

The selected path must produce a non-silent 16-bit PCM WAV for every scene. If it cannot, stop and report the validation error. Validate the final result before claiming completion. Do not retry a possibly charged call automatically; inspect the original task and authorize a supported retry explicitly.

The Hermes API server, PadNote network adapter, and task transport are outside this Skill. They exchange the standard task root defined by the schemas and portable references in this directory.
