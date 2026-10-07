# Pinned Revideo renderer cleanup patch

`revideo-renderer-0.11.0.json` applies a narrowly scoped cleanup fix to the
locked `@revideo/renderer` 0.11.0 CommonJS renderer. Its installer accepts only
the pinned upstream source SHA-256 or the pinned patched SHA-256; it rejects
other versions and bytes. `npm ci` applies it through the Skill `postinstall`,
and the Windows builder runs the installer again as an idempotent verification.

The copied renderer implementation remains covered by the included
`revideo-renderer-LICENSE.txt`, including the upstream MIT copyright notice.

The Skill calls the renderer with one worker. Cleanup is made idempotent for
that render lifecycle; the upstream API's multi-worker mode is not used by the
product path. The patch preserves the upstream `--single-process` default on
non-Windows platforms and leaves Windows Chromium in its multi-process default.
