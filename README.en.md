# PadNote

**Write on an Android tablet, select a difficult passage, and keep an AI explanation alongside your notes.**

Create a handwritten note or annotate an imported PDF. Writing does not require a model or a computer agent. AI uses your configured provider, which may charge for requests.

[Download Android APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.13/PadNote-Android-0.18.0-beta.13-debug.apk) · [Quick start (Chinese)](docs/QUICK_START.md) · [FAQ (Chinese)](docs/FAQ.md) · [中文](README.md) · [Report an issue](https://github.com/shiyan688/padnote/issues)

## Finish your first learning note

1. **Write or import:** create a note and write a few lines, or use Import note to open a PDF.
2. **Select:** lasso a small area, tap AI, and check the selection preview.
3. **Configure:** open Settings → New profile. Start with the direct multimodal route: supply a profile name, HTTPS API address, image-capable model, and your own API key. Presets are editable suggestions; check availability and pricing with your provider.
4. **Explain and keep:** choose Allow note edits before Explain to permit an answer on the page, or Only card to read it first. Confirm the material and destination, check the answer, and save. AI writes for PDF notes go on blank annotation pages.

This path does not require the Windows connection assistant, Hermes, or video tools.

![Android paper tools](docs/images/beta13-02-tool-icons.png)

Screenshot from the beta.13 Android emulator. Displayed text is local UI content, not evidence of a real model response tested in this revision.

## Availability

| Component | Current status |
| --- | --- |
| Android | Downloadable beta.13 debug-signed APK; Android 7.0 / API 24 or newer; a test release |
| iPad | Source available; build and sign with Xcode yourself; no signed, installable IPA |
| AI | Bring an image-capable OpenAI-compatible service; no offline AI or included model credits |
| Computer agent | Optional advanced workflow with a configured and paired supported Hermes target |
| Data | Notes stay on the device by default; model calls send the confirmed material; no automatic cloud sync |

The published download remains [v0.18.0-beta.13](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.13). First-use shortcuts, direct first-profile setup, and the scrollable profile editor in this source revision have not been packaged or released.

The beta.13 report records Android API 35 emulator UI checks and 329 JVM tests. It does not report a physical tablet or real model/agent requests in that round. See the [release test report](docs/BETA13_TESTING.md); those checks do not establish stylus quality or compatibility with every provider. The interface supports seven languages; translations still need native-speaker review.

## Explore later

- Share the paper as a standard PDF, or manually transfer editable note packages.
- Convert notes into Markdown and explicitly select additional material for AI.
- Use Share / Agent with a paired Hermes target supporting paper context. OpenClaw currently provides discovery/setup guidance; Codex paper tasks are not connected. See the [core workflow](docs/CORE_PRODUCT_WORKFLOW.md).

The optional [Windows beta.11 connection assistant](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.11/PadNote-Connection-Assistant-0.18.0-beta.11-Setup-x64.exe) includes no Hermes installation, models, API keys, or video runtime. Its installer is unsigned. WSL Hermes must expose an API reachable from Windows; automatic WSL discovery is not implemented.

## Verify downloads and build

Back up important notes before updating. Compare downloads with the Release `SHA256SUMS.txt` using `Get-FileHash <file> -Algorithm SHA256` on Windows or `shasum -a 256 <file>` on macOS/Linux.

Android requires JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and the configured NDK 29.0.14206865:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

See [verification for this source revision](docs/FIRST_USE_VALIDATION.md) and [Windows build requirements](desktop/connection-assistant/windows-setup/README-WINDOWS.md).

## License and feedback

Original Android/iPad client code is MIT licensed; the video Agent subproject is Apache-2.0. See [licensing scope](LICENSING.md) and [third-party notices](THIRD_PARTY_NOTICES.md). Include version, device, steps, and observed behavior in reports. Do not upload API keys, pairing tokens, or private notes.
