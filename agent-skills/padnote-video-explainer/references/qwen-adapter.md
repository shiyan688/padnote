# Optional Qwen adapter

This guide applies only when the user or invoking Agent explicitly selects the bundled DashScope/Qwen speech adapter, or the separate dedicated Qwen host integration. It is not the general video workflow, a universal provider picker, or a requirement for other Agent TTS and adapter paths.

## Bundled CLI adapter

The included `scripts/dashscope-tts.mjs` is a Node.js 22.22+ adapter for DashScope non-realtime TTS. Configure `PADNOTE_TTS_COMMAND` to its absolute path. Its required settings are:

- `DASHSCOPE_API_KEY`
- `DASHSCOPE_BASE_URL`
- `DASHSCOPE_TTS_MODEL`
- `DASHSCOPE_TTS_VOICE`

Optional settings are `DASHSCOPE_TTS_LANGUAGE`, `DASHSCOPE_TTS_INSTRUCTIONS`, and `DASHSCOPE_TTS_OPTIMIZE_INSTRUCTIONS`. The adapter sends the selected scene narration, voice, and language to the DashScope multimodal-generation endpoint over HTTPS, downloads the returned audio over HTTPS, and writes the returned WAV. It does not map the request's abstract voice profile or speed to DashScope controls, and it does not add silence between scenes. Backend WAV validation remains required.

To select these names for the adapter, configure `PADNOTE_TTS_ENV_NAMES` with the comma-separated names it needs. An optional private dotenv file may be named by `PADNOTE_TTS_ENV_FILE`; it is read as data and only selected names are used. Do not commit or print the file or its values. Do not place credentials in task inputs or request JSON. The worker does not inherit unrelated host secrets.

For compatibility, if no generic name list is configured, the existing bundled Qwen path retains its fixed DashScope name set and legacy dotenv handling. A different provider must use its own explicitly selected names; it does not inherit DashScope settings.

The adapter makes a paid external request. Follow the user's existing sharing and payment authorization before sending narration. If the request outcome may be uncertain, do not invoke it again automatically; inspect the original task and provider state first.

## Dedicated Qwen host integration

The desktop connection assistant may also expose a separately enabled, fixed Qwen-only video integration. Its current development guide describes a user-owned key held by the operating system's credential storage, with a session-only warning if it cannot be stored, and only a validated China-region configuration. Those limits belong to that host integration; check its current setup page before use. The host protocol, configuration, and provider-specific capability checks do not add a general in-product model/TTS picker and do not constrain a user-selected Agent, Hermes TTS, another adapter, or compatible supplied audio. Do not treat its Qwen configuration as a prerequisite for the portable Skill.

That host sends a selected note-text snapshot for storyboard generation. Storyboard approval is a review record, not permission to start speech generation; any paid follow-on call still follows the user's existing authorization. The generic Skill contract does not prescribe this host's protocol or imply that it has been verified on a real installation.
