# Beta 37 testing and release limits

This page records beta.37 behavior and verification scope.

## What changed

### Android paper viewport

When adding an Agent text result to paper, the paper preview supports zooming and panning, with fit-page and fit-width controls. These are preview-view settings, separate from note content; no claim is made that viewport settings persist across app restarts. Page elements keep logical page coordinates, and taps/place actions are converted through the current viewport transform. A zoomed preview therefore still saves the selected result at its logical paper location, rather than storing screen pixels as note coordinates.

Validation: source closure 417 files; 426 JVM tests and 10 native tests passed; lint reported 0 errors; four ABIs and the existing signer were verified. Native scenarios include zoom/pan, a placement action at the transformed point, save, and reopen. The emulator was shut down after testing. No user's physical app was installed, upgraded, or cleared by these tests.

### iPad paper viewport

The iPad source includes matching viewport handling and logical page placement. Validation comprises 330 unit tests and three simulator UI scenarios, evidenced across R6 and R7 runs: R6 covered zoom/pan followed by placement, save, and reopen; R7 covered the remaining append/reopen/deduplication and stale-save checks. This is combined evidence across those runs, not one batch. The final source closure is 149 files. No signed IPA is included.

### Windows Connection Assistant

The assistant distinguishes an HTTP response deadline after a connection is established (`unresponsive`) from failure to connect and from a service that responds as a different API. It does not treat a listening port or a TCP connection as `ready`; readiness still requires a timely, valid Hermes identity/capability response. For a timed-out POST whose receipt may be unknown, the existing uncertainty protection remains: no automatic retry or resend. The interface asks the user to check service status/logs and test again after recovery; PadNote does not restart the service.

The 56 focused Python tests passed on the frozen 118-file desktop source closure (Python 3.9.6; 19.188 seconds; exit 0). Beta.37 Setup and Portable hashes were checked; the Portable archive's 243 payload files match its inventory and source mapping, and Setup hash and PE version resources match beta.37. In a separate isolated Portable acceptance, four checks passed: non-elevated account; fresh identity and ACL; service startup with HTTP 200, TLS identity check and clean exit; repeat-start identity stability. The Setup payload was not independently extracted, and the Setup installer and GUI launcher were not tested. The isolated check used a fresh identity; it did not upgrade the existing helper or call real Hermes, and it does not establish a real Agent task.

## Suggested checks

1. On an Android test build, open a synthetic or disposable computer-task result, choose “add note”, then use the paper preview zoom/pan and fit-page/fit-width controls. Place the result at a visible point and verify the saved logical page position remains stable; preview view settings are not claimed to persist across restarts.
2. On an iPad simulator, use the same viewport placement path; see the R6/R7 evidence partition above.
3. In the Windows assistant, compare a refused/failed connection with a local endpoint that accepts TCP but delays HTTP, and with a responsive endpoint that is not Hermes. Confirm the states remain distinct and only a valid Hermes response meets the readiness contract. A POST timeout is unknown and must not be resubmitted automatically.

## Not included or not established

- Live Hermes end-to-end plotting and an Agent-generated function-plot artifact are outside the validation recorded here.
