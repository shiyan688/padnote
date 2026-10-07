# PadNote

**Think with AI, through handwriting and drawing.**

A calculation, an unfamiliar passage, or an unfinished sketch can start a conversation. PadNote brings handwriting, reading, and AI discussion onto the same page: select a question, ask a follow-up, keep the useful answer, and continue writing.

Our long-term goal is a better tablet interface for Agents. Express an idea in words and drawings, review the result, annotate it, and continue the conversation. Handwritten notes are the starting point.

Android · iPad · Your choice of model · Local notes · MIT

[Downloads](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.8) · [Desktop connection assistant](desktop/connection-assistant/README.md) · [Report an issue](https://github.com/shiyan688/padnote/issues) · [中文](README.md)

## Keep the answer on the page

- Select handwriting or other content, confirm what will be sent, and ask a question. Allow AI to write into the note when useful.
- Edit the result: text keeps Markdown source, formulas keep LaTeX, and diagrams use Mermaid. Move the content and keep annotating it.
- Read long answers across pages. Zoom preserves the text layout. Android PDF export includes page text, handwriting, and annotations.
- Keep notes locally, with multiple pages, PDF import, images, selection, highlighting, undo/redo, and a local Markdown knowledge library.

Each user supplies their own model endpoint, model name, and API key, and pays their own provider. AI requests send the confirmed content to that service. There is no automatic cloud sync; existing formulas and diagrams can render locally.

## Send a note to your own computer

The source preview includes a desktop connection assistant. On the same Wi-Fi, generate a pairing code on the computer, scan or paste it on the tablet, then approve the device on the computer. Save multiple connections with separate permissions for each Agent and device. No PadNote server is required.

- **Existing Hermes:** use your computer's installation and model configuration to send text or note task bundles, check progress, handle approvals, and retrieve files. See the [Windows / WSL2 guide](docs/HERMES_CONNECTION.md).
- **Built-in video workflow:** send a text snapshot to your computer. Your own Qwen API key generates a storyboard; after reviewing and approving it, separately authorize speech generation. The computer renders and returns video, subtitles, and a cover. “Built-in” describes the workflow supplied by the assistant, not a Qwen model running on the tablet.

The assistant retains the Agent's long-term credentials. The tablet receives revocable device credentials. Transfers check paths, sizes, and hashes; the Agent's computer tools still use its own permissions and approval settings. OpenClaw currently has detection and setup guidance only. Codex is not connected yet.

These flows are still being validated. Real Hermes, native Windows distribution, WSL2 networking, and physical tablets have not completed acceptance testing. Video inputs currently focus on text snapshots; full handwriting and original-image bundles remain work in progress. Read the notes for the specific Release you download.

## Install or build

Android requires Android 7.0 / API 24 or newer. Download the [PadNote Android beta 8 APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.8/PadNote-Android-0.18.0-beta.8-debug.apk) or read the [beta 8 release notes](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.8). The beta package and the original 0.17.5 mainline use different package names and can coexist. Export and import notes to move between them.

Beta.8 uses a debug signing certificate. Back up important notes before installation and check the SHA-256 on the release page. Attachment migration depends on the export format; automatic cloud sync is not provided. The beta.8 APK in the Release is the immutable build published for that version. Current `main` source includes a later two-file Android local-storage failure fix that is not in the beta.8 APK. Reviewing `main` source does not verify the APK you installed.

For Android, install JDK 17 or newer, Android SDK Platform 35, and Build Tools 34/35. Set `JAVA_HOME` and `ANDROID_SDK_ROOT`, then run:

```sh
git clone https://github.com/shiyan688/padnote.git
cd padnote
tools/build-android-apk.sh
```

iPad requires iPadOS 17 or newer. Current `main` source includes the complete native iPad client. Open `ios/PadNote.xcodeproj` in Xcode and run the PadNote scheme on a simulator; a physical device requires your own Apple Development Team. There is no installable IPA, App Store, or TestFlight release. See the [iPad build guide](ios/README.md) for validation scope and limits. The iPad source update does not change the beta.8 Android APK or establish complete feature parity between platforms.

## Contribute

Include your device, OS, app version, steps, and expected result in an Issue. For model-related failures, add the model name and a redacted error. Do not upload API keys or private notes.

| Directory | Contents |
| --- | --- |
| `android/` | Android app and tests |
| `ios/` | iPad app and tests |
| `desktop/connection-assistant/` | Local desktop assistant |
| `agent-skills/` | Video workflow and contracts |
| `docs/` | Connection, AI permissions, and file-format documentation |
| `tools/` | Build and validation tools |
| `entry/` | Early HarmonyOS prototype |

Run Android unit tests with `cd android && ./gradlew :app:testDebugUnitTest`. Emulator tests do not replace physical stylus, file-picker, or real Agent testing.

## License

The original Android and iPad code uses [MIT](LICENSE), including permission for commercial use and closed-source derivatives. Keep the copyright and license notices. The video Agent subproject remains Apache-2.0. Third-party components retain their own licenses; see [license scope](LICENSING.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
