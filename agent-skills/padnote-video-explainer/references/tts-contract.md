# Provider-neutral TTS contract

The Skill does not embed a vendor SDK or API key. If the Agent cannot write valid per-scene WAV files itself, configure one local executable:

```bash
export PADNOTE_TTS_COMMAND=/absolute/path/to/tts-adapter
```

The worker invokes that executable once per scene with no shell and no positional arguments. The adapter reads:

- `PADNOTE_TTS_SCENE_ID`
- `PADNOTE_TTS_TEXT`
- `PADNOTE_TTS_LANGUAGE`
- `PADNOTE_TTS_VOICE_PROFILE`
- `PADNOTE_TTS_SPEED`
- `PADNOTE_TTS_OUTPUT` — absolute destination under `<task-root>/work/audio/`

The adapter must exit zero only after writing a non-silent 16-bit PCM WAV to `PADNOTE_TTS_OUTPUT`. Provider keys remain in that adapter's own environment variables. The worker parses the WAV container, derives the real duration, hashes the closed file, and writes `work/audio-manifest.json`.

The child does not inherit the host environment. It receives a small runtime
allowlist, the six scene variables above, and only the exact provider settings
used by the bundled DashScope adapter: `PADNOTE_TTS_ENV_FILE` and its documented
`DASHSCOPE_*` names. The bundled adapter parses an environment file as data,
copies only those exact DashScope fields into a private configuration object,
and never sources the file or merges it into `process.env`; unrelated provider
keys in a shared file remain unavailable. A different provider must add and review its exact variable
names in `scripts/tts-environment.ts`; broad prefix rules such as all
`PADNOTE_*` variables are not accepted.

Without `PADNOTE_TTS_COMMAND`, `npm run audio:prepare` exits non-zero and emits:

```json
{"code":"tts_not_configured","message":"没有配置可用的语音合成能力"}
```

Fixture audio requires both `--fixture-audio` at generation and `--allow-fixture-audio` at rendering. It is synthetic, test-only, and not a TTS fallback.
