# Provider-neutral TTS contract

The user or invoking Agent selects the speech tool. The Skill accepts either real, compatible per-scene audio supplied by the Agent/user or audio written by an explicitly selected TTS adapter. It does not choose a vendor or silently register a paid service.

## Agent-supplied audio

Read narration only from the exact approved snapshot recorded in `work/task-state.json` at `approval.lesson_ir_snapshot_path`. Write one `work/audio/<scene-id>.wav` for every scene in that frozen IR, using that scene's exact narration, then run:

```bash
npm run audio:index -- <task-root>
```

`audio:index` validates and binds scene audio to the approved IR. Do not synthesize from the mutable `output/lesson.ir.json` after approval. Previously supplied audio is acceptable only when it matches the approved scene/narration mapping and passes the same backend checks.

## External adapter

Set `PADNOTE_TTS_COMMAND` to an absolute executable path. The worker invokes it once per scene, without a shell or positional arguments. It receives these scene values:

- `PADNOTE_TTS_SCENE_ID`
- `PADNOTE_TTS_TEXT` — exact narration from the approved IR snapshot
- `PADNOTE_TTS_LANGUAGE`
- `PADNOTE_TTS_VOICE_PROFILE`
- `PADNOTE_TTS_SPEED`
- `PADNOTE_TTS_OUTPUT` — absolute destination under `<task-root>/work/audio/`

The adapter must exit successfully only after writing a non-silent 16-bit PCM WAV to the output path. The worker parses the WAV, derives duration, hashes the closed file, and binds it to the approved scene in `work/audio-manifest.json`.

To pass provider-specific configuration, set `PADNOTE_TTS_ENV_NAMES` to a comma-separated list of only the selected adapter's required variable names. The list must contain 1–8 unique uppercase names matching `[A-Z][A-Z0-9_]{0,63}`. Empty entries, duplicates, wildcards, runtime-control names, and broad `PADNOTE_*` inheritance are rejected. `PATH` and `HOME` are not provider settings.

Optionally set `PADNOTE_TTS_ENV_FILE` to a private dotenv file. The parent reads it as data (regular file, no symlink, maximum 64 KiB), selects only names in `PADNOTE_TTS_ENV_NAMES`, and lets explicitly set host values override the file. The file path itself is not passed to the adapter. The child receives a small runtime environment, scene fields, and only selected provider values; it does not inherit the host environment wholesale. Do not put credentials in task inputs, repository files, or logs.

If `PADNOTE_TTS_COMMAND` is absent or the selected tool cannot produce the required format, stop. The worker reports:

```json
{"code":"tts_not_configured","message":"没有配置可用的语音合成能力"}
```

Fixture audio requires both `--fixture-audio` for generation and `--allow-fixture-audio` for rendering. It is synthetic test data only, never a real TTS fallback or successful narration.

The dedicated bundled Qwen adapter has additional compatibility behavior and fixed provider fields; read [the optional Qwen adapter guide](qwen-adapter.md) only when that adapter is selected. It is not a requirement for other Agent or adapter paths.
