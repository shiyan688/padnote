# PadNote

<img src="assets/brand/PadNote-128.png" width="88" height="88" alt="PadNote mark">

**Put your thinking on the page. Work through it with AI. Hand the next task to your computer.**

PadNote is an open-source AI notebook for tablets. Read a document, write out a derivation, draw the connections, and discuss them on the same page. Share your work as a PDF, or send the note and its context to a paired computer Agent on your network to continue the task. You choose the model and service.

[![A learning note in 30 seconds](docs/media/2026-10-28/paper-learning-tour-preview.png)](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.24/PadNote-Learning-Tour-20261028.mp4)

[Watch the learning-note tour (30 sec)](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.24/PadNote-Learning-Tour-20261028.mp4) · [Download the app and computer assistant](https://github.com/shiyan688/padnote/releases) · [中文](README.md)

The demos use synthetic learning content and real-device screenshots to show the page and sharing experience. They do not record AI or computer-Agent execution. [Media provenance](docs/media/2026-10-28/README.md).

| Step | What you do in PadNote | What you get |
| --- | --- | --- |
| 1. Learn and organize | Read, handwrite, draw, and discuss with your chosen model on the tablet page | Notes and ideas stay together on the page |
| 2. Share the page | Export the current note as a PDF, then share it through the system share sheet | Recipients can read the page layout |
| 3. Hand work to a computer Agent | Send page material to a paired Hermes Agent on the same network that supports note tasks | The computer receives the page material and creates a task; track progress in the computer task list and view returned content in the task details |

[Downloads and installers (GitHub Releases)](https://github.com/shiyan688/padnote/releases) · [PDF sharing guide](docs/BETA25_TESTING.md) · [Text editing and drafts](docs/BETA26_TESTING.md) · [Removing and restoring notes](docs/BETA27_TESTING.md) · [Interface and workflow guide](docs/BETA28_TESTING.md) · [Selections and gestures](docs/AI_PAPER_SELECTION.md) · [Windows connection assistant notes](desktop/connection-assistant/windows-setup/README-WINDOWS.md) · [Build from source](#build-from-source) · [Report an issue](https://github.com/shiyan688/padnote/issues)

## Demo frames

Paper tools are grouped by writing, color, history, and page actions, with AI, tasks, and sharing kept visible. The computer assistant puts Agent checks, QR pairing, and device approval on one page, and prompts for a new check when results expire. The image below shows the management UI with synthetic data. [Media notes](docs/media/2026-10-29/README.md).

![Desktop assistant checks and pairing, with synthetic demo data](docs/media/2026-10-29/beta28-desktop-ui.png)

<details>
<summary>Windows launcher and diagnostic controls</summary>

This window was rendered by the new launcher on Windows 11 with synthetic service and Agent state. No real Agent was contacted. [Media notes](docs/media/2026-10-29/README.md).

<img src="docs/media/2026-10-29/beta28-windows-launcher.png" alt="Windows connection assistant with synthetic state" width="760">

</details>

These real-device frames show a synthetic sample page, task description, and task-preset selector. See the [capture and frame-rate notes](docs/media/2026-10-28/README.md).

![Sample paper](docs/media/2026-10-28/beta18-paper-promo-poster.png)

![Task preset selection](docs/media/2026-10-28/beta18-task-presets-poster.png)

![Paper PDF sharing entry](docs/media/2026-10-28/beta18-share-transition.gif)

[Watch the tutorial (36 sec)](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Android-Tutorial-zh.mp4) · [Watch the overview (35 sec)](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.18/PadNote-Android-Promo-zh.mp4)

### Exporting the page as PDF

Shared notes should have predictable page sizes. A4-ratio notes export as A4, screen-ratio notes keep their shape, and notes with a PDF background use the first source page's displayed dimensions for every exported page. Content is scaled proportionally, so text and diagrams are not stretched to fill the paper. See the [page-size and sharing guide](docs/BETA25_TESTING.md).

This sample page was exported on an Android tablet. Its example text was typed manually. The PDF preserves the page content for sharing and reading.

![Sample PDF page exported on the tablet](docs/media/2026-10-28/paper-pdf-export.png)

Long notes can also be shared in page form. These are the first, middle, and final pages of an actual 18-page export from the tablet, using 36 paragraphs of synthetic bilingual test content.

![First, middle, and final pages of an 18-page PDF export](docs/media/2026-10-28/paper-long-text-pdf.jpg)

[Watch the PDF preview (30 sec)](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.21/PadNote-Paper-PDF-Preview-zh.mp4). This video presents actual exported pages; see the [media notes](docs/media/2026-10-28/README.md).

### A worked learning example

This five-page Bayesian inspection example brings prose, formulas, handwriting, and a decision-flow diagram together. The prose was written by a person, the blue strokes are synthetic, and the diagram was drawn by hand. The 30-second tour at the top uses these real-device screenshots. See the [media notes](docs/media/2026-10-28/README.md) for capture and crop details.


<p align="center"><img src="docs/media/2026-10-28/paper-learning-app.png" alt="PadNote app view of a synthetic learning example" width="820"></p>

<table>
  <tr>
    <td width="50%"><img src="docs/media/2026-10-28/paper-learning-formula.png" alt="Bayesian formula in the learning example" width="100%"></td>
    <td width="50%"><img src="docs/media/2026-10-28/paper-learning-diagram.png" alt="Inspection decision-flow diagram in the learning example" width="100%"></td>
  </tr>
</table>

<details>
<summary>Earlier Android interface screenshots</summary>

These images are from an earlier Android emulator build and show the beta.13 interface.

![beta.13 multilingual interface](docs/images/beta13-01-languages.png)

![beta.13 paper tools](docs/images/beta13-02-tool-icons.png)

![PadNote brand mark](docs/images/beta13-03-new-brand.png)

</details>

## Platforms and connections

- **Android tablets:** Installable APKs are available through GitHub Releases.
- **Windows:** The connection assistant is a separately distributed companion for pairing and Agent connections. It does not include Hermes, a model, or API credentials.
- **iPad:** Source is available; there is no installable signed IPA.

Computer tasks currently require a paired Hermes Agent that supports PadNote note tasks. Tasks are saved on the computer before PadNote waits for a Hermes run ID; uncertain outcomes are not automatically replayed. You configure the model provider. OpenClaw has discovery and setup guidance, but that does not mean PadNote note tasks are integrated with OpenClaw. Codex note tasks are not connected. Hermes in WSL must expose an API reachable from Windows; automatic WSL Agent discovery is not available.

See the [development status and next steps](docs/DEVELOPMENT_STATUS.md) for more boundaries and project status.

## Downloads and verification

Get the Android APK, Windows connection assistant, full source, and license files from [GitHub Releases](https://github.com/shiyan688/padnote/releases). Verify downloads with `shasum -a 256 <file>` on macOS/Linux or `Get-FileHash <file> -Algorithm SHA256` on Windows using `SHA256SUMS.txt`.

## Build from source

Install JDK 17, Android SDK Platform 35, and Build Tools 35.0.0, then run:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

See the [Windows installer source and build requirements](desktop/connection-assistant/windows-setup/README-WINDOWS.md).

## License and feedback

Original Android/iPad client code is MIT licensed; the video Agent subproject is Apache-2.0. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and [`LICENSING.md`](LICENSING.md) for third-party licenses. For feedback, include the version, device, steps, and visible message. Do not include API keys, pairing tokens, passwords, or private notes.
