# PadNote

<img src="assets/brand/PadNote-128.png" width="88" height="88" alt="PadNote mark">

**Write, draw, and ask on a tablet page. Share the context as a PDF or hand it to your own computer agent.**

PadNote brings notes, handwriting, and AI-assisted learning together on one page. Use material such as a video lesson, develop the ideas in your note, then share the page with someone or send it to a paired computer agent. You choose the model and service provider.

[Android beta.18](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.18) · [Download APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Android-0.18.0-beta.18-debug.apk) · [Windows installer](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Connection-Assistant-0.18.0-beta.18-Setup-x64.exe) · [Windows portable build](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Connection-Assistant-0.18.0-beta.18-Portable.zip) · [Android beta.18 guide](docs/BETA18_TESTING.md) · [Windows setup notes](desktop/connection-assistant/windows-setup/README-WINDOWS.md) · [Build from source](#build-from-source) · [Report an issue](https://github.com/shiyan688/padnote/issues)

## Android beta.18

Updates PDF task status messages, read-only background refresh, task entry points, and recipient labels. Return to the note and check the task later. An uncertain result is not automatically submitted again; copy diagnostics to continue troubleshooting. See the [beta.18 guide](docs/BETA18_TESTING.md) for installation and test steps.

## Historical interface screenshots

These images are from an earlier Android emulator build and show the beta.13 interface, not the current beta.18 UI.

![beta.13 multilingual interface](docs/images/beta13-01-languages.png)

![beta.13 paper tools](docs/images/beta13-02-tool-icons.png)

![PadNote brand mark](docs/images/beta13-03-new-brand.png)

## Demo frames

These selected frames show the sample paper, task description, and task-preset selector. They were captured from the beta.17 UI as a low-frame-rate sequence of real device screenshots. Note text was typed manually as synthetic sample content; no Agent result is shown. See the [media provenance note](docs/media/2026-10-28/README.md).

![Sample paper](docs/media/2026-10-28/beta18-paper-promo-poster.png)

![Task preset selection](docs/media/2026-10-28/beta18-task-presets-poster.png)

![Paper PDF sharing entry](docs/media/2026-10-28/beta18-share-transition.gif)

[Watch the tutorial (36 sec)](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Android-Tutorial-zh.mp4) · [Watch the overview (35 sec)](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Android-Promo-zh.mp4)

## How it works

Write, draw, edit, and discuss a note with the model you choose. Share a full-page PDF or send note material to a paired Hermes agent that supports note tasks. PadNote is not tied to a specific model provider. The desktop connection assistant helps with pairing and agent connections; it does not include Hermes, a model, or API credentials. Hermes in WSL must expose an API reachable from Windows; automatic WSL discovery is not available. OpenClaw has discovery and setup guidance. Codex note tasks are not connected.

Android and the Windows connection assistant are both at beta.18. Windows Hermes run creation uses a bounded background wait; other requests retain a 10-second limit, and uncertain outcomes are not automatically replayed. iPad source resources are available, but there is no installable signed IPA.

## Downloads and verification

The Release includes the Android APK, Windows installer and portable build, full source, and license files. Verify downloads with `shasum -a 256 <file>` on macOS/Linux or `Get-FileHash <file> -Algorithm SHA256` on Windows using `SHA256SUMS.txt`.

## Build from source

Install JDK 17, Android SDK Platform 35, and Build Tools 35.0.0, then run:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

See [`desktop/connection-assistant/windows-setup/README-WINDOWS.md`](desktop/connection-assistant/windows-setup/README-WINDOWS.md) for Windows installer source and build requirements.

## License and feedback

Original Android/iPad client code is MIT licensed; the video Agent subproject is Apache-2.0. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and [`LICENSING.md`](LICENSING.md) for third-party licenses. For feedback, include the version, device, steps, and visible message. Do not include API keys, pairing tokens, passwords, or private notes.
