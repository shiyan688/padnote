# PadNote Video Explainer

An AgentSkills-compatible worker for PadNote `video.explain.v1` tasks. It turns an Agent-authored Lesson IR into an offline storyboard and, after explicit approval and real scene audio, a Revideo MP4, captions, thumbnail, QA report, and result manifest.

The invoking user or Agent selects the model and speech tool. The Skill supports native Agent TTS, an explicitly configured adapter, or compatible real audio already supplied for the task. It does not provide a universal model/provider picker. The dedicated Qwen adapter is an optional Qwen-only path; see [its adapter guide](references/qwen-adapter.md) only when that path is deliberately enabled.

The worker does not connect to PadNote, OpenClaw, Hermes, or a paid TTS vendor by itself. Those systems can pass the shared file contract from outside. It does not generate PPTX.

## Requirements

- Linux x64, Node.js `>=22.22.0`, npm, and enough disk for Puppeteer's Chromium cache.
- `npm ci` from this directory. The lock file is authoritative.
- Dependencies and caches remain in `node_modules/` and `.local-cache/`; generated tasks and samples remain in `.local-output/`.

Rendering disables Revideo telemetry and uses a local Vite endpoint. Scenes do not load external runtime resources.

## Two-stage command line

Validate the request and create the storyboard:

```bash
npm run validate:request -- /absolute/task-root
npm run validate:ir -- /absolute/task-root
npm run storyboard -- /absolute/task-root --revision 1
```

After the user approves revision 1, persist approval:

```bash
npm run task:approve -- /absolute/task-root --revision 1
```

Choose exactly one audio path. For the selected Agent's own TTS or compatible audio already supplied for the task:

```bash
npm run audio:index -- /absolute/task-root
```

For an explicitly selected external adapter:

```bash
export PADNOTE_TTS_COMMAND=/absolute/path/to/your-tts-adapter
npm run audio:prepare -- /absolute/task-root
```

After the chosen path has produced and validated per-scene WAV files, render and verify:

```bash
npm run render -- /absolute/task-root --approval approve --revision 1
npm run validate:result -- /absolute/task-root
```

`storyboard`, `audio:prepare`, and `render` share `work/task-state.json`, an append-only metadata event log, and one execution lock. Approval binds the review revision and Lesson IR/review digests and is consumed once. For a single guarded process, use `npm run task -- /absolute/task-root run` after approval. Inspect or cancel the original task with `npm run task:status -- /absolute/task-root` or `npm run task:cancel -- /absolute/task-root`.

If a possibly charged call has an uncertain outcome, the worker does not repeat it automatically. Inspect the original provider and task state before explicitly authorizing a supported retry. A cancelled task is terminal.

The selected audio path must produce one non-silent 16-bit PCM WAV per scene. The backend derives actual duration and file digests from closed files. See [the provider-neutral TTS contract](references/tts-contract.md). Fixture audio is synthetic test data, not a TTS fallback or completed narration.

## Agent hosts

Expose this directory as the `padnote-video-explainer` Skill in the chosen AgentSkills host. When Hermes is the host, read [Hermes-specific guidance](references/hermes.md); provider and TTS selection remain in Hermes configuration. The PadNote repository also documents its integration side in `../../docs/VIDEO_AGENT_CONTRACT.md`.

Schemas are in `schemas/`; committed examples are in `tests/fixtures/`.
