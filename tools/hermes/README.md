# PadNote Hermes server integration

This Linux test host uses Hermes 0.21.0 with the official BigModel API and the
existing DashScope TTS adapter. It does not run model weights locally.

## Layout

- `sample-*.md`: authored teaching notes at three audience levels.
- `prepare-sample.mjs`: creates the percentages task once; existing inputs are not overwritten.
- `run.mjs`: reads the ignored `api/glm.json` and referenced key at launch.
- `stage1-prompt.md`: bounded storyboard-generation request.
- `.runtime/source`, `.runtime/venv`, `.runtime/profile`, `.runtime/uv-cache`,
  `.runtime/tasks`: ignored installation, configuration, sessions and task output.

Node 22.22 and rendering dependencies are reused from the video Skill's existing
local environment. The launcher currently targets this Linux x64 workspace;
it is not a Windows installer or a publicly reachable PadNote server.

## Run on this host

```bash
node tools/hermes/run.mjs --version
node tools/hermes/run.mjs chat --provider custom --model glm-5.3-flash \
  --toolsets terminal,skills --max-turns 20 --run-budget 420 --oneshot \
  --query-file tools/hermes/stage1-prompt.md
```

The profile config selects the custom endpoint and exposes `agent-skills` through
`skills.external_dirs`. The launcher passes `BIGMODEL_API_KEY`: current Hermes
gates environment credentials by host, so a generic `OPENAI_API_KEY` is not used
for `open.bigmodel.cn`. Never print environment values or include keys in prompts.
Runtime lazy dependency installs are disabled. Existing command approval checks
remain enabled; do not use `--yolo` to work around a blocked command.

Stage 1 stops at validated `output/review.json`. Stage 2 needs approval of its
exact revision, then Hermes invokes `audio:prepare`, `render` and
`validate:result` in the Skill directory. The Qwen adapter handles synthesis;
there is no need to configure a second native Hermes TTS provider.

The single-task CLI path is separate from Android task transport. Installing this
host does not implement a PadNote network bridge or expose any inbound port.
