# PadNote

**Think on the page, then share it with a computer agent.**

PadNote is an open-source tablet notebook for handwriting, drawing, and AI-assisted learning. On Android, you can work with handwriting and AI in the same note, share the complete page as a PDF, or send the current note's frozen PDF and readable context to a paired Hermes assistant on your local network.

[Android beta.10](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.10) · [Beta.10 test guide](docs/BETA10_TESTING.md) · [Build from source](#build-from-source) · [Report an issue](https://github.com/shiyan688/padnote/issues)

## Learn on the page

Write, draw, zoom, and continue an AI conversation in the current note. Useful answers can stay editable on the page. Share the complete note as a PDF with its pages, background, handwriting, and readable text.

You can also hand the current note to a paired computer agent. Before sending, review the target, task, and included material: the note PDF, readable text, and conversation available from that same note. PadNote does not treat a PDF as recognized text or include other notes or a knowledge base.

| Area | Current scope |
| --- | --- |
| Note taking | Android multi-page notes, handwriting and editing, zoom, PDF import and export |
| AI learning | Continue a conversation in the current note using a service you configure |
| Sharing | Export the complete note as a PDF through Android's share sheet |
| Computer agent | Send the current note and readable context to a paired Hermes assistant that supports note tasks |
| Task presets | Continue work, make a shareable handout, find learning gaps and make exercises, or request a shareable explainer video |

You or your selected agent choose the model, speech synthesis, and video tools. PadNote does not require Qwen or tie video tasks to one provider. Generic files can be saved to the device and shared from a file manager; in-app play, save, and share actions are available for video files.

## Android beta.10

The beta.10 debug APK supports Android 7.0 / API 24 and later. It uses the beta.9 signing certificate and can update an existing beta.9 installation. Back up important notes before installing.

- [Download the Android APK](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.10/PadNote-Android-0.18.0-beta.10-debug.apk)
- [Download the test kit](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.10/PadNote-beta10-test-kit.zip)
- [Release assets and checksums](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.10)

This is a debug test build, not a Play Store release. Real Windows/WSL, tablet, and Hermes task testing is still needed.

## Computer assistant

The assistant runs on your computer and connects PadNote to a Hermes installation you already use. It does not include Hermes, models, credentials, or video runtimes. See the [test guide](docs/BETA10_TESTING.md) for Windows/WSL networking requirements.

OpenClaw currently provides discovery and setup guidance only; its note-task workflow is not connected. Codex is not connected to note tasks. iPad source code is available, but there is no installable signed IPA, TestFlight, or App Store build.

## Build from source

Clone the repository:

```sh
git clone https://github.com/shiyan688/padnote.git
cd padnote
```

### Android

Install JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and NDK 29.0.14206865. Set `JAVA_HOME` and `ANDROID_SDK_ROOT` (or `ANDROID_HOME`), then run:

```sh
tools/build-android-apk.sh
```

The repository includes the Gradle Wrapper. The first build downloads Gradle and dependencies.

### iPad

Open `ios/PadNote.xcodeproj` in Xcode, select the `PadNote` scheme and an iPad Simulator, then run. For a physical device, select your Apple Development Team under Signing & Capabilities. There is currently no installable signed IPA.

See the [iPad README](ios/README.md) for details.

## License

The Android and iPad applications use the [MIT License](LICENSE). The video-agent subproject remains under Apache-2.0; third-party components retain their own licenses. See [licensing details](LICENSING.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
