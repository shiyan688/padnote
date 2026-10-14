# PadNote

**Write, draw, and ask on the same page, then share it or hand it to a computer agent.**

PadNote is an open-source tablet notebook for handwriting, drawing, and AI-assisted learning. On Android, work in the same note, export a complete-page PDF, or send the current note material to a paired Hermes computer assistant. You choose the model and service provider.

[Android beta.12](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.12) · [Download APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.12/PadNote-Android-0.18.0-beta.12-debug.apk) · [Windows installer](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.11/PadNote-Connection-Assistant-0.18.0-beta.11-Setup-x64.exe) · [Test guide](docs/BETA12_TESTING.md) · [Build from source](#build-from-source) · [Report an issue](https://github.com/shiyan688/padnote/issues)

Screenshots from an Android 35 emulator. The page and question are synthetic examples; the question was not sent.

![Paper and AI card](docs/images/beta12-01-paper-ai.png)

![Input and send controls with the keyboard open](docs/images/beta12-02-keyboard.png)

![Guidance when no Agent is connected](docs/images/beta12-03-agent-guide.png)

## beta.12

This release gives the Android paper toolbar, AI card, and forms a consistent set of action buttons and icons. The AI input area grows to two or three lines with longer text; the input and send controls remain available with the keyboard open, and the AI card stays visible after rotation. When no computer Agent is connected, choose **Connection settings** to open setup. The Windows x64 connection assistant installer is reused unchanged from beta.11.

The Android APK supports Android 7.0 / API 24 and later. Package name: `com.padnote.android.beta`. It uses the same signing certificate as beta.11 and can update an existing installation. Back up important notes before installing. See the [test guide](docs/BETA12_TESTING.md) for scope and results.

## Scope and limitations

Write, edit, and zoom in a note; continue an AI conversation; export a full note as PDF; or send the current note PDF and readable context to a paired Hermes agent that supports note tasks. You or the selected agent choose models, speech, and video tools; PadNote does not require a particular provider. OpenClaw provides discovery/setup guidance only; Codex note tasks are not connected. iPad source is available, but there is no installable signed IPA.

The desktop assistant does not include Hermes, models, API keys, or video runtimes. WSL Hermes must expose an API reachable from Windows; automatic WSL discovery is not implemented. External services may incur costs. Review the task and included material before sending.

## Downloads and verification

The Release includes the Android APK, Windows x64 installer, source ZIP, test guide, license files, and `SHA256SUMS.txt`. Beta.11 demo videos are not reposted. The Windows installer was checked on Windows 11 x64 through SSH Session 0 for install, start/stop, service readiness, same-version reinstall, and uninstall. This does not verify ordinary desktop mouse use, Wi-Fi pairing, or a live Hermes task. The installer is unsigned.

Verify downloads with `shasum -a 256 <file>` on macOS/Linux or `Get-FileHash <file> -Algorithm SHA256` on Windows.

## Build from source

Install JDK 17, Android SDK Platform 35, and Build Tools 35.0.0, then run:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

See [`desktop/connection-assistant/windows-setup/README-WINDOWS.md`](desktop/connection-assistant/windows-setup/README-WINDOWS.md) for Windows installer source and build requirements.

## License and feedback

Original Android/iPad code is MIT licensed; the video Agent subproject is Apache-2.0. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and [`LICENSING.md`](LICENSING.md). For feedback, include version, device, steps, and observed behavior. Do not upload API keys, pairing tokens, passwords, or private notes.
