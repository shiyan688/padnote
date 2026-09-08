# Hermes execution

Use this path only when Hermes Agent is the host. Hermes owns the model and TTS configuration; PadNote and this Skill never receive provider credentials.

## One-time host setup

Expose the parent Skill directory in the active Hermes profile:

```yaml
skills:
  external_dirs:
    - /absolute/path/to/padnote/agent-skills
```

Select a TTS provider with `hermes tools`. Edge TTS is the zero-subscription test option. The video worker still requires Linux x64 and Node.js 22.22 or newer.

## Stage 1

Invoke `/padnote-video-explainer` with the absolute task root and request Stage 1 only. Stop after `output/review.json` validates. Do not call TTS before the user approves the exact storyboard revision.

## Stage 2

If the host has configured `PADNOTE_TTS_COMMAND`, use that provider adapter instead of Hermes' native TTS tool:

```bash
npm run audio:prepare -- <task-root>
PADNOTE_AGENT_BACKEND=hermes npm run render -- <task-root> --approval approve --revision <n>
npm run validate:result -- <task-root>
```

The host can supply private environment-file paths to the adapter. Do not read, copy, or print those files or environment values in Agent messages. This is the configured DashScope path for the PadNote server; Hermes orchestrates the commands while the adapter performs synthesis.

Otherwise, when native TTS is configured:

For every scene in `output/lesson.ir.json`, call Hermes' `text_to_speech` tool once:

- `text`: the scene's exact `narration` value.
- `output_path`: the absolute `<task-root>/work/audio/<scene-id>.wav` path.

Do not use voice-mode playback or a default audio-cache path. After every scene WAV exists, run:

```bash
npm run audio:index -- <task-root>
PADNOTE_AGENT_BACKEND=hermes npm run render -- <task-root> --approval approve --revision <n>
npm run validate:result -- <task-root>
```

`audio:index` requires a non-silent 16-bit PCM WAV for every scene and derives duration, byte count, and SHA-256 from the closed files. If the selected Hermes provider does not produce that format, stop and report the validation error; do not relabel compressed audio as WAV.

The Hermes API server, PadNote network adapter, and task transport are outside this Skill. They exchange the standard task root defined by the schemas and portable references in this directory.
