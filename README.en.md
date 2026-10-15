# PadNote

<img src="assets/brand/PadNote-128.png" width="88" height="88" alt="PadNote mark">

**Write, draw, and ask on the same page, then share it or hand it to a computer agent.**

PadNote is a tablet notebook for writing, drawing, and AI-assisted learning. Work in one note, then share the page or hand it to a paired computer agent. You choose the model and service provider.

[Android beta.13](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.13) · [Download APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.13/PadNote-Android-0.18.0-beta.13-debug.apk) · [Windows installer](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.11/PadNote-Connection-Assistant-0.18.0-beta.11-Setup-x64.exe) · [Test guide](docs/BETA13_TESTING.md) · [Build from source](#build-from-source) · [Report an issue](https://github.com/shiyan688/padnote/issues)

Screenshots from an Android emulator; displayed text is local UI content.

![多语言界面](docs/images/beta13-01-languages.png)

![纸面工具图标](docs/images/beta13-02-tool-icons.png)

![新品牌标志](docs/images/beta13-03-new-brand.png)

## beta.13 update

The Android interface adds Simplified Chinese, English, Japanese, Korean, Spanish, French, and German, with 21 updated feature icons and the new brand mark. The default language follows the system; you can also change it from the bookshelf. Translations have not yet received a line-by-line native-speaker review. Some raw machine or agent errors may remain in their original language, and model responses are not translated with the interface.

The Windows connection assistant continues to use the beta.11 installer, so the Windows app still shows its older icon. This release updates the shared icon source. The iPad update contains icon source assets only; there is no signed, installable IPA. See the [test guide](docs/BETA13_TESTING.md) for the Android target and verified checks.

## Scope and limitations

Write, edit, and zoom in a note; continue an AI conversation; export a full-page PDF; or send note material to a paired Hermes agent that supports note tasks. You or the selected agent choose models and services; PadNote does not require a particular provider. OpenClaw currently provides discovery and setup guidance. Codex note tasks are not connected. iPad source is available, but there is no installable signed IPA.

The desktop assistant does not include Hermes, models, API keys, or video runtimes. WSL Hermes must expose an API reachable from Windows; automatic WSL discovery is not implemented. External services may incur costs. Review the task and included material before sending.

## Downloads and verification

The Release includes the Android APK, the unchanged beta.11 Windows x64 installer, source ZIP, test guide, license files, and `SHA256SUMS.txt`. See the test guide for the beta.11 installer’s verification scope. This release does not claim a new Windows UI or icon. The installer is unsigned.

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
