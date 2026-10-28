# PadNote

<img src="assets/brand/PadNote-128.png" width="88" height="88" alt="PadNote mark">

**Write, draw, and ask on a tablet page. Share the context as a PDF or hand it to your own computer agent.**

PadNote brings notes, handwriting, and AI-assisted learning together on one page. Use material such as a video lesson, develop the ideas in your note, then share the page with someone or send it to a paired computer agent. You choose the model and service provider.

[Android beta.22](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.22) · [Download APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.22/PadNote-Android-0.18.0-beta.22-debug.apk) · [Windows installer](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.20/PadNote-Connection-Assistant-0.18.0-beta.20-Setup-x64.exe) · [Windows portable build](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.20/PadNote-Connection-Assistant-0.18.0-beta.20-Portable.zip) · [User guide](docs/BETA22_TESTING.md) · [Windows setup notes](desktop/connection-assistant/windows-setup/README-WINDOWS.md) · [Build from source](#build-from-source) · [Report an issue](https://github.com/shiyan688/padnote/issues)

## beta.22: keep the paper under your fingers

Pan and zoom with two fingers even after selecting text, including when the fingers land on text and blank paper or on different fragments. Adding the second finger cancels an uncommitted text move. Long notes now allow the first page to stay centered when zooming out, avoiding a sudden jump at the top boundary. AI selections continue to include typed text, ink, images, and PDF content. See [selections and gestures](docs/AI_PAPER_SELECTION.md) and the [installation guide](docs/BETA22_TESTING.md).

<details>
<summary>Earlier Android interface screenshots</summary>

These images are from an earlier Android emulator build and show the beta.13 interface.

![beta.13 multilingual interface](docs/images/beta13-01-languages.png)

![beta.13 paper tools](docs/images/beta13-02-tool-icons.png)

![PadNote brand mark](docs/images/beta13-03-new-brand.png)

</details>

## Demo frames

The desktop assistant brings Agent preparation, network choice, pairing, and timestamped checks onto one page. This layout preview uses the current page renderer with synthetic data.

![Desktop assistant checks and pairing, with synthetic demo data](docs/media/2026-10-28/beta19-desktop-readiness.jpg)

These selected frames show the sample paper, task description, and task-preset selector. They were captured from the beta.17 UI as a low-frame-rate sequence of real device screenshots. Note text was typed manually as synthetic sample content; no Agent result is shown. See the [media provenance note](docs/media/2026-10-28/README.md).

![Sample paper](docs/media/2026-10-28/beta18-paper-promo-poster.png)

![Task preset selection](docs/media/2026-10-28/beta18-task-presets-poster.png)

![Paper PDF sharing entry](docs/media/2026-10-28/beta18-share-transition.gif)

[Watch the tutorial (36 sec)](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Android-Tutorial-zh.mp4) · [Watch the overview (35 sec)](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Android-Promo-zh.mp4)

### A real PDF export

This sample page was exported on an Android tablet. Its example text was typed manually; it shows the shareable page output.

![Sample PDF page exported on the tablet](docs/media/2026-10-28/paper-pdf-export.png)

Long notes can be shared in the same page format. These are the first, middle, and final pages of an actual 18-page export from the tablet, using 36 paragraphs of synthetic bilingual test content.

![First, middle, and final pages of an 18-page PDF export](docs/media/2026-10-28/paper-long-text-pdf.jpg)

[Watch the PDF preview (30 sec)](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.21/PadNote-Paper-PDF-Preview-zh.mp4). This video presents actual exported pages; see the [media notes](docs/media/2026-10-28/README.md).

## How it works

Write, draw, edit, and discuss a note with the model you choose. Share a full-page PDF or send note material to a paired Hermes agent that supports note tasks. PadNote is not tied to a specific model provider. The desktop connection assistant helps with pairing and agent connections; it does not include Hermes, a model, or API credentials. Hermes in WSL must expose an API reachable from Windows; automatic WSL discovery is not available. OpenClaw has discovery and setup guidance. Codex note tasks are not connected.

Android is at beta.22; the companion Windows connection assistant remains at beta.20. Tasks are saved on the computer before waiting for a Hermes run ID; uncertain outcomes are not automatically replayed. iPad source is available, but there is no installable signed IPA. See the [development status and next steps](docs/DEVELOPMENT_STATUS.md).

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
