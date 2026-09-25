# PadNote

**PadNote is an open-source handwriting notebook for Android tablets and iPad** that puts handwriting, sketching, AI reasoning and a growing knowledge base on the same page — and a hand-off point from thinking to doing: select a note, and it becomes a task package for an agent on your computer. Notes stay on your device by default, you bring your own model, and the MIT license allows commercial and closed-source derivatives.

**Write and draw, think together with an AI.**

PadNote is an open-source project for Android tablets and iPad, exploring how handwriting and drawing can be used to communicate with an agent. It starts with note-taking: write down a derivation, circle what's unclear, ask the AI to explain or organize it, then keep what's useful on the original page and carry on from there.

Android · iPad · bring your own model · local notes · MIT

[Who it's for](#who-its-for) · [How it compares](#how-padnote-compares) · [From notes to a finished video](#from-notes-to-a-finished-video-the-path-that-works-today) · [FAQ](#frequently-asked-questions) · [Download Android beta](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.6) · [Build from source](#build-from-source) · [Project direction](#from-notes-to-an-agent-entry-point) · [Report an issue](https://github.com/shiyan688/padnote/issues)

> **The one-line difference: most AI notebooks leave the answer in a chat window. PadNote writes it back onto the page — and you can edit it.**
> Formulas keep their LaTeX source, text is Markdown, diagrams are Mermaid. So the explanation the AI wrote can be modified in place, and you keep going from there.
>
> **One step further: other notebooks stop at recording. PadNote keeps going — what you recorded can be handed to an agent to execute.**
> Select a page, set the audience and the learning goal, export a task package for an agent on your computer. It writes a storyboard, waits for your approval, then renders an explainer video. That path works today — see [From notes to a finished video](#from-notes-to-a-finished-video-the-path-that-works-today).

## Who it's for

**Good fit:**

- People who work with a tablet and a stylus: math, physics, circuits, algorithms, reading notes, meeting notes
- People who want to talk to an AI **on the page**, instead of switching to a separate chat window and copying the conclusion back by hand
- **People who want to turn a note into a finished artifact**: select a page, set the audience and goal, export a task package, and have an agent on your computer produce an explainer video (this path works today)
- People already running — or willing to run — an agent on a computer (**Hermes** is documented): they want the tablet to be the agent's entry point, not just another chat window
- **People who care about AI permission boundaries**: no write access to ink, read-only knowledge base, writes require authorization and can be undone; an agent saying "I'm done" is not treated as success
- People who want to choose their own model and bring their own API key (any OpenAI-compatible endpoint, multiple saved profiles)
- People who don't want their notes uploaded to a cloud vendor (notes live on the device by default; there is no automatic cloud sync)
- People who use both an Android tablet and an iPad (content moves between them as note files)
- People who want to read the code, change it, ship it commercially, or build a closed-source derivative (MIT)
- Students, researchers, engineers, self-learners — **as long as your thinking looks like "handwrite, then ask"**

**Not a good fit:**

- **Anyone who wants a one-tap "AI goes and does the work on my computer"**: hand-off of the task package, execution progress, approval and result return are still manual — the in-app loop is not closed
- Anyone who needs a one-tap App Store or TestFlight install on iPad: today the iPad app must be built from source and signed by you
- Anyone who needs automatic multi-device cloud sync, or live sync between iPad and Android: not available; export and import only
- Anyone who wants the AI to write the whole note automatically without confirming anything: writing into a note requires your authorization, and the AI has no access to your ink
- Teams needing real-time collaborative editing
- People who mostly type and rarely handwrite (this tool is handwriting-first)
- Anyone looking for professional drawing brushes: this is a note-taking app, not a Procreate replacement

## How PadNote compares

The interesting axis isn't the feature count. It's **what form the AI's output takes.**

| | PadNote | GoodNotes 6 | Notability | Apple Notes | AI Canvas |
| --- | --- | --- | --- | --- | --- |
| Platforms | **Android tablets + iPad** | iPad / Mac / iPhone | iPad / Mac / iPhone | Apple platforms | iPad |
| Handwriting engine | Own engine on each platform | Proprietary | Proprietary | System | PencilKit |
| AI | Circle-to-ask, multi-turn follow-up, can write into the note | AI for Math (Solve / Teach Me) | Audio playback tied to ink | System writing tools | Multimodal Q&A |
| **Form of AI output** | **Written back onto the page, LaTeX / Markdown / Mermaid source preserved, still editable** | Chat cards | Not written into the note | Not written into the note | Cards |
| Bring your own model | **Yes — any OpenAI-compatible endpoint, multiple profiles** | No | No | No | Yes (Gemini / ChatGPT / Claude / Groq) |
| Key storage | Android Keystore / iOS Keychain | — | — | — | iOS Keychain |
| Note storage | **On-device by default, no automatic cloud sync** | Local + iCloud | Local + iCloud | iCloud | Local |
| Hand-off to an external agent | **Yes: export a task package for an agent on your computer (Hermes documented), which produces an explainer video** | — | — | — | — |
| Can the AI touch your ink | **No — prohibited at the code level** | — | — | — | — |
| License | **MIT, commercial use and closed-source derivatives allowed** | Proprietary | Proprietary | Proprietary | Custom (non-standard) |
| Price | Free and open source | Free tier; Pro subscription; AI billed separately | Free tier; Plus / Pro subscriptions | Free | Free and open source |

**If you only look at one row:** other tools treat the AI's reply as a **message**. PadNote treats it as **part of your note**. Messages can't be edited and scroll away. Note content has a position, can be changed, and stays.

**"The AI can't touch your ink" is not marketing — it's a structural constraint in the code:**

`android/app/src/main/java/com/padnote/android/NoteTools.java` defines the tool set the model may call:

```
read_page_map       read page structure (bands, text boxes, ink clusters, free regions)
write_text          write text
set_text_flow_style adjust text layout
move_text_flow      move a text block (across pages)
search_vault        search the knowledge base (read-only)
read_vault_note     read a knowledge-base note (read-only)
```

The tool names `move_ink`, `delete_flow`, `delete_text` and `edit_stroke` are **explicitly forbidden from appearing in the code** — `tools/static-check.mjs` fails the build if they do. The source comment reads:

> `v1 deliberately grants no authority over ink; without recognition the model cannot know what a stroke cluster means.`

Vault access is structural in the same way: the storage layer implements `NoteTools.VaultReader`, a read-only view. **The model can search and read the knowledge base. It cannot write to it.**

> Competitor pricing and features above reflect public pages as of 2026-09 and may have changed; check each vendor's own site. The PadNote column can be verified line by line against the repository.

## Think it through on the same page

> **This section answers: what is PadNote's core mechanism, and what can it do.**

Write down part of a derivation, or read a paragraph you don't follow, and you can circle it and ask directly. Before sending, you confirm exactly what the model will see. When the answer comes back you can follow up, or let the AI write the explanation into the note.

What lands on the page is still editable. Formulas keep their LaTeX source, text is Markdown, diagrams are Mermaid. You can change a symbol, add a sentence, or move a whole block; long answers are split into blocks and flow across pages.

As notes accumulate, they can be organized into a Markdown knowledge base — searched by keyword, queried across notes, or exported to other tools.

**Capabilities:**

| Everyday task | What's supported today |
| --- | --- |
| Write and draw | Pressure-sensitive ink, local eraser, lasso, highlight, geometric shapes, images, undo and redo |
| Read and organize | Multi-page notes, page management, PDF import, annotation and export |
| Formulas and diagrams | Editable LaTeX / Markdown, offline rendering of formulas and Mermaid |
| Discuss with AI | Circle-to-ask, multi-turn follow-up, multiple model profiles, optional "visual transcribe → text answer" |
| Keep the result | The AI writes through note tools; you control write permission, and any single write can be undone |
| Build knowledge | Note digitization, local Markdown knowledge base, search and export |
| Hand off to an external agent | Select a note and export a task package (audience / learning goal / duration); the agent on your computer executes it and produces an MP4 explainer video |

## From notes to a finished video: the path that works today

> **This section answers: how a note reaches an agent on your computer, which links are done, and which are still ahead.**

The sections above describe PadNote's internal loop — write, circle, ask, edit, keep writing. This section is about how it sends the output of that loop **outwards**.

Most notebooks stop at "I wrote it down." PadNote takes one more step: **a note can be input for an agent.**

### The flow: from one note to one explainer video

1. **Select a page (or several) on the tablet** and open "create video explainer task"
2. **Fill in three parameters**: who the audience is, what the learning goal is, how long the video should be — these drive how the agent writes the storyboard
3. **Export a `.padnote-video.zip` task package.** It contains the selected note content, the parameters and a `request.json`; it **contains no API key**
4. **Hand the package to an agent on your computer.** The documented host is **Hermes** (there is a dedicated integration doc in the repo); the UI also lists OpenClaw and other agents, but the OpenClaw adapter is not connected yet
5. **The agent writes a storyboard and waits for approval.** This is stage one of the two-stage protocol: the agent produces storyboard descriptions (HTML + PNG), writes `review.json`, and then **stops for you** to `approve` / `revise` / `cancel`
6. **Only after approval does it render**: speech synthesis → MP4, plus an SRT subtitle file, a thumbnail and a QA report, written into `result.json`

Between steps 3 and 4 there is **a manual step today** — you carry the package to the computer yourself. That is the single break in the chain, and it is why "one tap" is not a claim this project can make yet.

### Done / not connected yet

| Link in the chain | Status |
| --- | --- |
| Handwriting, circle-to-ask, AI results written back onto the page | **Works** |
| Select note → set audience / goal / duration → export task package | **Works** |
| Task package contains no API key | **Works** |
| Agent executes end to end: validate → storyboard → approval → TTS → MP4 | **Works** |
| Outputs MP4 + SRT + thumbnail + QA report | **Works** |
| Hermes as the agent host | **Documented** |
| Moving the package from tablet to computer | **Manual**, not automated |
| App submits the task to an agent on its own | Not connected |
| Execution progress display | Not connected |
| In-app approval UI | Not connected |
| Result (the MP4 and friends) flows back into the note | Not connected (by design it lands in a staging area first, and is linked to the note only after you preview and approve) |
| OpenClaw adapter | Not connected |
| End-to-end acceptance against a real Hermes instance | Not done (current tests use offline fake services and a fake gateway) |

The point of that table is that it doesn't lie: **if what you want is one tap, it isn't here yet. If what you want is "my note actually came back as a finished artifact," that path is open.**

### The hardest part of this chain: the trust boundary

The real barrier to letting an agent do the work isn't capability, it's **confidence**. Why should you let an outside program touch your notes?

PadNote's answer lives in `docs/VIDEO_AGENT_CONTRACT.md`, and it is **enforced in code**, not promised in prose:

| Constraint | What it actually says |
| --- | --- |
| **The agent's own report is not trusted** | An agent's natural-language reply **does not count as success**. Stage one reads only `output/review.json`; the final stage reads only `output/result.json` |
| **Inputs are read-only, outputs are isolated** | `request.json` and `input/` are read-only; an implementation may write only to `work/` and `output/` |
| **Input integrity is pinned by hashes** | `manifest.json` records `path` / `media_type` / `size_bytes` / `sha256` per file; `bundle_sha256` is a deterministic tree digest |
| **Failure is failure — no silent downgrade** | A missing required file, a hash mismatch, a schema violation, a path escaping the task directory or a symlink — **all of them fail the run** |
| **Path traversal is blocked** | Paths must be non-empty, non-absolute, free of `.` and `..` segments, backslashes and NUL; the resolved real path must still be inside the task directory |
| **No secrets in the package** | The request may not contain API key, token, secret or authorization fields |
| **Results never edit your note directly** | Remote artifacts land in a staging area first; only after you preview and approve does PadNote link them to the note |
| **A human stays in the loop** | Two-stage protocol: the storyboard must be `approve`d / `revise`d / `cancel`led by a person before rendering is allowed |
| **Rather fail than fake it** | With no usable speech synthesis it reports `tts_not_configured` and stops — it will not ship a silent video |

That last one deserves its own line: **a decision not to do something is worth more than a wrong result.** The rule is verbatim in the contract — *"Never make a silent video or substitute fixture audio."*

Ink is constrained the same way, in code: the tools available to the model are `read_page_map`, `write_text`, `set_text_flow_style`, `move_text_flow`, `search_vault` and `read_vault_note`; names like `move_ink` and `edit_stroke` are explicitly forbidden by `tools/static-check.mjs`.

### On "hand it to an agent to run experiments"

**The documented path is "note → explainer video," not "note → any experiment."** The one frozen vertical task on the agent side is video explanation (the subproject `agent-skills/padnote-video-explainer`, Apache-2.0).

Nothing in the protocol limits it to video — the `request.json` plus six JSON schemas plus `result.json` structure is task-type agnostic, and adding a vertical task means swapping schemas and reference docs. But **only the first one is implemented and documented.** Claiming "let an agent do anything" as a shipped capability would be dishonest, and third parties will check.

### Why this path is worth a look

- It turns a **local-first notebook** into a **task origin** — data stays on the device, yet a person can point an external agent at it
- Its hand-off **is a file, not an API**. The package is a zip: anyone can read it, audit it, change it, reproduce it. No black-box service to trust
- It **leaves approval with the human**: the agent's storyboard must be approved before rendering, and the finished MP4 lands in a staging area first

## From notes to an agent entry point

> **This section answers: what PadNote is aiming at, what works today, and what is still on the roadmap.**

PadNote's core idea is to make the tablet a personal workspace for thinking and learning, connecting recording, understanding, derivation, knowledge accumulation and AI collaboration into one flow.

The longer-term goal is a better agent entry point for tablets. Writing down a task, sketching a diagram, circling the part that needs changing — all of these can express intent; and when the agent returns a result, you can annotate it and keep the conversation going.

Handwritten notes are the starting point of that route. The next step is connecting external agents so ideas on the page can be handed off and the results brought back to the canvas. Agent capability probing and video task-package export exist today; task execution, progress tracking, approval and result return are still in development.

> **Honest note on maturity — split in two, so nothing is misread:**
>
> **Working today:** handwriting, circle-to-ask, AI results written back onto the page and still editable, PDF annotation, the local knowledge base — and **every link of the "note → task package → MP4 explainer video" path** (package export, agent-side execution, human storyboard approval, rendering). Per-link status is in [From notes to a finished video](#from-notes-to-a-finished-video-the-path-that-works-today).
>
> **Not connected yet:** submitting a task from inside the app, execution progress, an in-app approval UI, results flowing back into the note, and the OpenClaw adapter. beta.6 has not been accepted against a real Hermes instance either — current tests use offline fake services and a fake gateway. These **remain on the roadmap**; specifics are in the [agent integration notes](docs/AGENT_INTEGRATION.md).

## Pick your own model, keep your notes local

> **This section answers: which model is used, where notes are stored, and what data leaves the device.**

PadNote lets you enter any OpenAI-compatible HTTPS endpoint, model name and API key, and save multiple profiles. The model service and its cost are your choice; keys are stored in the Android Keystore or the iOS Keychain.

Notes are stored on the device by default. When you use AI, only the selection, note or knowledge-base content you confirmed is sent to the service you chose. Handwriting transcription needs a model; displaying existing formulas and diagrams happens locally.

Android and iPad exchange content through note files. There is no automatic cloud sync.

**What you can verify in this section:**

- Keys are held in the Android `AndroidKeyStore` (`AES/GCM/NoPadding`) and the iOS Keychain, not stored in plain text
- Formulas render with a bundled KaTeX and diagrams with a bundled Mermaid — both offline assets. The rendering WebView sets `setBlockNetworkLoads(true)` and `setAllowUniversalAccessFromFileURLs(false)` and carries a CSP: **rendering never touches the network**
- `AndroidManifest.xml` sets `android:allowBackup="false"`, so notes **stay out of default system backups**
- What gets sent to the model is confirmed by you before it is sent

## Download & install

> **This section answers: how to install on Android and iPad, and which OS versions are required.**

On Android you can download and install the APK directly. Minimum Android 7.0 / API 24:

- [Latest beta: 0.18.0-beta.6](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.6/PadNote-Android-0.18.0-beta.6-debug.apk)
- [Original mainline: 0.17.5](https://github.com/shiyan688/padnote/releases/download/v0.18.0-beta.2/PadNote-Android-0.17.5-debug.apk), for users who want to stay on the original branch.

Open the APK on your tablet and follow the system prompts. This is currently a debug-signed test build. The beta and the mainline build can be installed side by side; notes migrate through export and import. Version details and SHA-256 checksums are on the [release page](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.6).

beta.6 improves on-page text, formula and diagram layout, and fixes text being dropped from Android PDF export. You can import the [layout demo note](docs/fixtures/paper-layout-demo.padnote.json) to see the result without configuring a model; PDF verification steps are in [the export QA doc](docs/PDF_EXPORT_QA.md). The desktop agent settings page now includes an offline connection tutorial.

The iPad source is public. Minimum iPadOS 17. An installable build will come later — for now you run it through Xcode and sign it yourself. There is no IPA, no App Store listing and no TestFlight entry.

> **Realistic expectation for iPad users:** this path needs a Mac, Xcode and your own Apple developer account (a free account works; the signature is valid for 7 days). If you don't want that process, **this isn't usable for you yet** — an App Store / TestFlight build does not exist.

## Build from source

Clone the repository first:

```sh
git clone https://github.com/shiyan688/padnote.git
cd padnote
```

### Android

You need JDK 17, Android SDK Platform 35 and Build Tools 35.0.0. Set `JAVA_HOME` and `ANDROID_SDK_ROOT` (or `ANDROID_HOME`), then run:

```sh
tools/build-android-apk.sh
```

The script uses the repository's Gradle 8.9 wrapper, runs compilation, lint and APK signature verification, and writes the debug build to `dist/`. The first build downloads Gradle and dependencies.

### iPad

Open `ios/PadNote.xcodeproj` in Xcode, select the `PadNote` scheme and an iPad Simulator, then run. For a physical device, pick your Apple Development Team under Signing & Capabilities.

Detailed steps and feature notes are in the [iPad README](ios/README.md).

### What still needs work

- Apple Pencil latency, pressure and palm rejection, plus handwriting feel across different Android tablets, still need more real-device verification.
- Models vary in their handwriting recognition and tool-calling ability; AI answers currently appear all at once rather than streaming.
- Long-document performance, cross-platform layout and file round-tripping still need more real-world feedback. iPad covers are currently stored only locally and don't travel with the note file.
- The complete external agent workflow is not connected yet; see the [agent integration notes](docs/AGENT_INTEGRATION.md).
- Worth separating: **the "note → task package → MP4 explainer video" path already works** (including agent-side execution and human storyboard approval). What is *not* connected is the **in-app** part — automatic submission, progress, approval UI and result return. Don't conflate the two; per-link status is in [From notes to a finished video](#from-notes-to-a-finished-video-the-path-that-works-today).

## Contributing

Start from a problem you actually hit: a stylus that doesn't feel right, a formula that lays out wrong, a model that can't call tools, or a note that breaks when moved between two devices.

When you [open an issue](https://github.com/shiyan688/padnote/issues), include your device and OS, the version you're using, reproduction steps and expected behavior. For model-related problems, also include the model name and a redacted error message. Please don't upload API keys or private notes.

Code map:

| Directory | Contents |
| --- | --- |
| `android/` | Android native client and tests |
| `ios/` | SwiftUI / UIKit iPad client and tests |
| `docs/` | AI tools, PDF and agent interface contracts |
| `agent-skills/` | Video explainer agent subproject |
| `tools/` | Build, check and development helper scripts |
| `entry/` | Early HarmonyOS ArkUI prototype, not synced with the two mainline clients |

For changes touching the note format or AI write permissions, also describe the migration path and verification results.

<details>
<summary>Testing and release verification</summary>

The beta.6 Android build, 59 JVM tests and APK signature verification pass; Lint reports no errors, with 8 pre-existing UI string warnings retained. Native regression tests for paper layout and PDF export compile but have not been run on an Android device. The 20 iPad layout simulator tests and the arm64 unsigned build pass. Test results are not a substitute for real use on a tablet, with a stylus, and against a live model service.

Android unit tests:

```sh
cd android
./gradlew :app:testDebugUnitTest
```

From the repository root you can run `node tools/static-check.mjs` to check files and key constraints, or `bash tools/tests/test-build-android-script.sh` to check the build script.

`tools/emulator/` holds Linux emulator helper scripts and needs a preconfigured SDK, AVD and toolchain; the tests clear the target emulator's beta app data. `tools/e2e/` is for real-model testing and requires an explicit `E2E_API_KEY`, endpoint and model — running it calls the service you choose.

</details>

## Frequently asked questions

### What is PadNote?

PadNote is an open-source handwriting notebook for Android tablets and iPad. It keeps handwritten ink, AI conversation and knowledge accumulation inside one note: circle a half-finished derivation, ask the AI about it, let it write the explanation onto the page, then edit that explanation directly and keep working.

### What's the biggest difference from GoodNotes or Notability?

Two things. **First, what the AI writes is editable note content**: formulas keep their LaTeX source, text is Markdown, diagrams are Mermaid — you can change one symbol and continue the derivation. In those other apps the AI's output is a chat card: readable, not editable. **Second, the model and the storage are your choice**: any OpenAI-compatible endpoint, multiple saved profiles, keys in the Keystore / Keychain, notes not uploaded by default, no lock-in to any cloud service.

### Is it free? Is there a subscription?

Free, MIT licensed. No subscription, no feature gating. What you pay your model provider is a separate matter.

### Do the AI features cost extra?

Yes, but you don't pay PadNote. PadNote ships no model and resells no model credits. You enter the endpoint, model name and API key for an OpenAI-compatible service you choose, and you're billed directly by that provider.

### Are my notes uploaded to the cloud?

Not automatically. Notes are stored on the device by default and there is no automatic cloud sync. When you use AI, only the selection, note or knowledge-base content you confirmed before sending goes to **the service you configured yourself**. Formula and diagram rendering happens locally, and the rendering WebView is explicitly blocked from the network.

### Can the AI see my handwriting? Can it modify my handwriting?

**It can read it within the scope you authorize. It cannot modify it.** When you circle something and ask, the selection you confirmed is transcribed or sent as an image to the model you chose. But the AI has no write access to ink at all — the tools available to the model are `read_page_map`, `write_text`, `set_text_flow_style`, `move_text_flow`, `search_vault` and `read_vault_note`; tool names like `move_ink` and `edit_stroke` are prohibited from appearing in the code. Knowledge-base access is read-only as well.

### Which models are supported?

Any service exposing an OpenAI-compatible HTTPS API. You can save multiple profiles, and you can configure a "visual transcribe → text answer" two-leg route (some models are good at reading handwriting images, others at reasoning — you can configure them separately).

### Can I undo what the AI wrote into the note?

Yes. Each write requires your authorization, and any single write can be undone.

### How do I install it on iPad?

**Building from source is currently the only way**: you need a Mac, Xcode and your own Apple developer account (a free account works, signature valid for 7 days). The repository has `ios/PadNote.xcodeproj` and a detailed [iPad README](ios/README.md). **There is no IPA, App Store listing or TestFlight entry.**

### How do I install it on Android?

Download and install the [APK](https://github.com/shiyan688/padnote/releases/tag/v0.18.0-beta.6). Minimum Android 7.0 / API 24. It's a debug-signed test build; the beta and the mainline build can be installed side by side. You can also [build it yourself](#android).

### Does it sync between Android and iPad?

Not automatically. The two platforms exchange content by exporting and importing note files; there is no built-in cloud sync.

### Does it support math formulas and diagrams?

Yes, and they're **editable**. Formulas keep their LaTeX source, diagrams use Mermaid, and rendering is done offline with the bundled KaTeX and Mermaid. No network needed.

### Can it import and annotate PDFs?

Yes. PDF import, annotation and export are supported.

### Can I get my notes out into other tools?

Yes. Notes can be organized into a Markdown knowledge base (local files), searched by keyword, queried across notes, and exported for use in other tools.

### Can an AI actually do work on my computer?

**Not with one tap — but it can genuinely finish the job.** Here's the split:

- **Working today:** select a note on the tablet → set audience / learning goal / duration → export a `.padnote-video.zip` task package → hand it to an agent on your computer (Hermes is documented) → the agent writes a storyboard, **waits for your approval**, then renders an MP4 explainer video with an SRT subtitle file and a QA report. The package **contains no API key**
- **Not connected:** the app sending the task itself, progress display, in-app approval, results flowing back into the note — those steps are manual right now

So the actual feel today is "export → carry it to the computer → hand it over," not "tap once, come back to the result." Per-link status is in [From notes to a finished video](#from-notes-to-a-finished-video-the-path-that-works-today).

### How do handwritten notes become an explainer video?

Three parameters plus one export:

1. Select the page (or pages) of notes you want explained
2. Fill in three parameters: **who the audience is** (drives vocabulary), **what the learning goal is** (drives emphasis), **how long the video should be** (drives storyboard density)
3. Export the task package and hand it to an agent on your computer

The agent first produces a **storyboard** (HTML + PNG) and stops for your approval — `approve` / `revise` / `cancel`. Only after you approve does it synthesize speech, render the MP4, and emit the SRT file, thumbnail and QA report.

Why stop at the storyboard? Because **editing a storyboard frame is far cheaper than editing a rendered video.** That's also why PadNote's agent contract makes approval a hard stage rather than an option.

### How mature is it? Is it stable?

Honestly: **it's a beta, not a finished product.**

- Android has published beta builds (currently `0.18.0-beta.6`) that are usable day to day; on iPad you build it yourself
- The author reports that beta.6 passes the Android build, 59 JVM tests and APK signature verification, with no lint errors but 8 retained UI string warnings; the native regression tests for paper layout and PDF export **compile but have not yet been run on an Android device**
- 20 iPad layout simulator tests pass
- **Real-device stylus feel** — latency, pressure, palm rejection, especially across Android tablet brands — is still being verified
- AI answers currently appear all at once after generation rather than streaming token by token
- External agent workflow: **the "note → task package → MP4" path already works** (including agent-side execution and human storyboard approval); what is **not connected** is in-app submission, progress display, approval UI and result return — and it has not yet been accepted against a real Hermes instance

### Can I use it commercially? Can I make a closed-source fork?

Yes. The Android and iPad client code written for this project — including the handwriting engine — is MIT licensed, which permits commercial use and closed-source derivatives as long as copyright and license notices are preserved. The video agent subproject remains Apache-2.0. See [the licensing scope](LICENSING.md).

### How can I contribute?

Start from a problem you actually hit: a stylus that feels wrong, a formula that lays out badly, a model whose tools won't call, notes that break when moved between two devices. Include your device and OS, the version, reproduction steps and expected behavior, and [open an issue](https://github.com/shiyan688/padnote/issues).

## License

The Android and iPad client code written for this project, including the handwriting engine, is released under the [MIT license](LICENSE). You're free to use, modify and redistribute it, including commercial use and closed-source derivatives — keep the copyright and license notices.

The video agent subproject remains Apache-2.0, and third-party components follow their own licenses. See [the licensing scope](LICENSING.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
