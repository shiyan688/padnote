# PadNote

**Think on the page, then share it with a computer agent.**

PadNote is an open-source tablet notebook for handwriting, drawing, and AI-assisted learning. On Android, work in the same note, export a complete-page PDF, or send the current note's PDF and readable context to a paired Hermes assistant.

[Android beta.11](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.11) · [Download APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.11/PadNote-Android-0.18.0-beta.11-debug.apk) · [Windows installer](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.11/PadNote-Connection-Assistant-0.18.0-beta.11-Setup-x64.exe) · [Test guide](docs/BETA11_TESTING.md) · [Build from source](#build-from-source) · [Report an issue](https://github.com/shiyan688/padnote/issues)

## beta.11

Android model, agent, export, task preset, and video-style menus now use a consistent paper-like selection style with clearer selected states, wrapped labels, and touch targets. Windows x64 has a per-user setup installer that preserves existing user data.

The APK supports Android 7.0 / API 24 and later, package `com.padnote.android.beta`, and uses the same debug signing certificate as beta.10, so it can update an existing installation. Back up important notes first. Windows setup was tested on Windows 11 x64 through SSH Session 0 for installation, bundled imports, native window button start/stop, readiness status, service, same-version reinstall, and uninstall. This does not verify interactive desktop mouse use, Wi-Fi pairing, or a live Hermes task. The installer is unsigned.

## Scope and limitations

Write, edit, and zoom in a note; continue an AI conversation; export a full note as PDF; or send the current note PDF and readable context to a paired Hermes agent that supports note tasks. You or the selected agent choose models, speech, and video tools; Qwen is optional. OpenClaw provides discovery/setup guidance only; Codex note tasks are not connected. iPad source is available, but there is no installable signed IPA.

The desktop assistant does not include Hermes, models, API keys, or video runtimes. WSL Hermes must expose an API reachable from Windows; automatic WSL discovery is not implemented. External services may incur costs. Review the task and included material before sending.

## Downloads and verification

The Release includes the Android APK, Windows x64 installer, two demonstration videos, source ZIP, test guide, license files, and `SHA256SUMS.txt`. Verify each download with `shasum -a 256 <file>` on macOS/Linux or `Get-FileHash <file> -Algorithm SHA256` on Windows.

## Build from source

For Android, install JDK 17, Android SDK Platform 35, and Build Tools 35.0.0, then run:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

See [`desktop/connection-assistant/windows-setup/README-WINDOWS.md`](desktop/connection-assistant/windows-setup/README-WINDOWS.md) for Windows setup source and build prerequisites. See [`docs/BETA11_TESTING.md`](docs/BETA11_TESTING.md) for user testing steps.

## License and feedback

Original Android/iPad code is MIT licensed; the video-agent subproject is Apache-2.0. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and [`LICENSING.md`](LICENSING.md). For feedback, include version, device, steps, and observed behavior. Do not upload API keys, pairing tokens, passwords, or private notes.
