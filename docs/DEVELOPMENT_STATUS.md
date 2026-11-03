# Development status · beta.42

## Beta 42 (current)

Beta 42 adds expected-file selection for computer Agent tasks. Completion status and returned attachments are shown separately; when expected files are missing, the text result remains available and the user can explicitly continue in the same context. Long answers can be expanded or collapsed, and PNG/JPEG attachments can be previewed before placement on paper. The connection page checks each computer Agent and offers a manual refresh; pairing by pasted details and QR scanning are separate entry points.

Android beta.42 uses versionCode 74. The accepted application APK was reused by the R3 delivery; the R3 readback and six focused native checks passed, while the 61 JVM tests belong to the earlier accepted R1 run. Windows beta.42 Setup and Portable packages have local package readback. The beta.37-to-beta.41 in-place upgrade was verified in Beta 41. The beta.41-to-beta.42 in-place upgrade passed; pairing and task records were retained. iPad simulator-target compilation succeeded; source is provided without a signed IPA.

The real-handwriting example remains bounded as previously described: the authorized handwritten page and a separate y=x² example appear in one note; the Agent returned SVG, PNG was rendered locally on a Mac, and the image was manually imported through Android's picker, saved, and reopened. The graph is not an answer to the handwriting and was not automatically fetched by PadNote as a task result.



## Beta 40


Beta.40 improves Android long-form Markdown profile 2 pagination and the computer assistant's connection-check guidance. Long pages preserve formatting across word-boundary breaks; visible-content measurement reduces blank space from markup, and settled layout is included in PDF page-fit checks. Existing notes do not switch profiles automatically; the user previews and confirms. The desktop assistant distinguishes a timed-out check from a connected service that returned no HTTP response. Same-profile concurrent checks share one Gateway launch.

The Android beta.40 build contains 436 source files. All 440 JVM tests passed; lint reports zero errors and 172 warnings. Both focused native checks passed: real-renderer pagination/PDF export for long Markdown, and multi-page flow stability through zoom and page recreation. All five synthetic PDF pages were inspected; the four content pages had whole-word breaks, consistent formatting, and no clipping. Settled geometry matched after save and reopen, and all fragments fit the existing PDF height guard. Synthetic test content is used for layout verification.

The Windows Portable package passed isolated ordinary-user startup, identity and ACL, HTTP/TLS, repeat-identity, and normal-shutdown checks. Beta.41 Setup was then used for a native upgrade from beta.37. Setup exited 0, all 243 installed package files were verified, the beta.41 window opened, and the owned local management endpoint returned HTTP 200; TLS identity was independently verified and retained. Pairing state, durable records, source-directory ACL, and TLS identity were retained; the installer left no Python bytecode caches. No Hermes restart or old task resubmission occurred. The uninstall flow was not tested. See `BETA40_TESTING.md` for feature and verification details.


## Beta 41

Beta 41 is a Windows Connection Assistant update. Its accepted source candidate contains 118 files (115 under the desktop assistant and three root licensing/notices files), with 13 existing paths changed against the C40 public baseline and no path additions or removals. The Setup and Portable outputs passed content readback against their package inventory. Desktop status checks require a fresh (60-second) health result and executable task capability; the existing explicit user action performs a recheck after expiration. Installer cleanup is limited to known package-generated Python cache files.

Historical beta.41 record: the beta.37-to-41 upgrade and visible beta.41 window were verified; pairing and task records remained intact. Uninstall behavior was not tested. A graph SVG returned by a real Hermes Agent API was rendered locally to PNG and manually imported into the Android beta.40 demo note. The original handwriting remained on page 1, the separate `y=x²` example was on page 2, and the two-page PDF passed visual review after save and reopen. The graph was not an answer to the handwritten problem; Android did not automatically fetch a cross-device task result. Android beta.40 used versionCode 73, and iPad source was provided without a signed IPA. This is historical beta.41 status, not current beta.42 acceptance.
