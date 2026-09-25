# PadNote Video Explainer

Local AgentSkills-compatible worker for PadNote `video.explain.v1` tasks. It turns an Agent-authored Lesson IR into an offline storyboard and, after explicit approval plus real per-scene WAV audio, a Revideo MP4, SRT captions, thumbnail, render manifest, QA report, and final result manifest.

The worker does not connect to PadNote, OpenClaw, Hermes, Codex, or a paid TTS vendor. It does not generate PPTX. Those systems call this shared file contract from outside.

## Requirements and install

- Linux x64, Node.js `>=22.22.0`, npm, and enough disk for Puppeteer's Chromium cache.
- `npm ci` from this directory. The lock file is authoritative.
- Dependencies and caches remain in `node_modules/` and `.local-cache/`; both are ignored by Git.
- Generated tasks and samples remain in `.local-output/`, also ignored by Git.

Revideo telemetry is disabled by the render command. Rendering serves only a local Vite endpoint; no scene loads an external runtime resource.

## Two-stage command line

```bash
npm run validate:request -- /absolute/task-root
npm run validate:ir -- /absolute/task-root
npm run storyboard -- /absolute/task-root --revision 1
```

After the user approves revision 1:

```bash
npm run task:approve -- /absolute/task-root --revision 1
export PADNOTE_TTS_COMMAND=/absolute/path/to/your-tts-adapter
npm run audio:prepare -- /absolute/task-root
npm run render -- /absolute/task-root --approval approve --revision 1
npm run validate:result -- /absolute/task-root
```

`storyboard`, `audio:prepare`, and `render` now share `work/task-state.json`, an
append-only metadata event log, and one execution lock. Approval is bound to the
review revision plus the Lesson IR/review digests and is consumed once. To run
adapter TTS and rendering in one guarded process, use `npm run task --
/absolute/task-root run` after `task:approve`. Inspect or cancel with:

```bash
npm run task:status -- /absolute/task-root
npm run task:cancel -- /absolute/task-root
```

A crash records or recovers to `interrupted`/`failed`; it never automatically
repeats a possibly charged TTS call. After inspecting provider/output state, an
operator may explicitly authorize one retry with `task:approve ...
--retry-uncertain`. A cancelled task is terminal and cannot be revived.

The TTS adapter is the only provider-specific layer. API keys stay in provider-specific environment variables; never put them in `request.json`. See `references/tts-contract.md` for the exact adapter environment.

For DashScope Qwen TTS, use the included `scripts/dashscope-tts.mjs` executable
with Node 22.22+ on `PATH`. Set `PADNOTE_TTS_COMMAND` to its absolute path and
`PADNOTE_TTS_ENV_FILE` to your private dotenv file. Required settings are
`DASHSCOPE_API_KEY`, `DASHSCOPE_BASE_URL`, `DASHSCOPE_TTS_MODEL`, and
`DASHSCOPE_TTS_VOICE`; optional language and instruction settings use the
`DASHSCOPE_TTS_LANGUAGE`, `DASHSCOPE_TTS_INSTRUCTIONS`, and
`DASHSCOPE_TTS_OPTIMIZE_INSTRUCTIONS` variables. Do not commit the dotenv file.
The adapter downloads provider WAV audio over HTTPS; `audio:prepare` validates
PCM format, non-silence, and actual duration using the existing worker contract.
It uses the configured voice/instructions, not the request's abstract voice profile
or speed. `DASHSCOPE_TTS_GAP_MS` is not consumed; no extra silence is appended.
The worker passes the adapter only the documented scene fields, a small runtime
allowlist, and the exact DashScope settings above. Other host secrets and
unrecognized `PADNOTE_*` values are not inherited. The bundled adapter parses a
dotenv file without sourcing it and selects only its documented DashScope names;
other provider entries stay unavailable. Adapters for another provider require
an explicit code-reviewed allowlist update.
API reference: https://www.alibabacloud.com/help/en/model-studio/non-realtime-tts-user-guide

An Agent with a native TTS tool, including Hermes, reads narration from the
approved snapshot recorded in `work/task-state.json`, writes one
`work/audio/<scene-id>.wav` per scene, and then runs `npm run audio:index --
/absolute/task-root`. Hermes-specific execution instructions are in
`references/hermes.md`.

Repository-only fixture flow:

```bash
npm run task:approve -- /absolute/task-root --revision 1
npm run audio:fixture -- /absolute/task-root --fixture-audio
npm run render -- /absolute/task-root --approval approve --revision 1 --allow-fixture-audio
```

Fixture audio is synthetic test audio and must never be presented as narration or a real TTS integration.

## External Agent entrypoint

Install or expose this directory as the `padnote-video-explainer` Agent Skill. OpenClaw, Hermes, or another AgentSkills host should invoke `SKILL.md`, mount/provide one standard task root, and execute the commands above from this directory. There is no network adapter in this package.

The portable executable interface is contained in this directory. The PadNote repository also documents its integration side in `../../docs/VIDEO_AGENT_CONTRACT.md`. Schemas are in `schemas/`; committed examples are in `tests/fixtures/`.
