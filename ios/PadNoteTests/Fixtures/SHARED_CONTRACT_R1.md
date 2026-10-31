# C38 shared Markdown profile and note schema contract R1

This is the single cross-platform contract for Android, iPad, archive tooling, and shared fixtures. Keep the JSON shape and profile semantics identical on both platforms.

## Serialized data boundary

- `schemaVersion` is a JSON numeric integer value from 1 through 10; booleans, strings, and non-integral numbers are invalid. Canonical output uses an integer. Numerically integral forms such as `10.0` or `1e1` have the same value and are accepted; do not add raw-token lexical restrictions.
- Note schema 10 allows `markdownProfileVersion` on a text flow only when that flow has `format: "markdown"`.
- Missing `markdownProfileVersion` means effective profile 1. Readers MUST NOT backfill the missing key when opening, rendering, caching, or re-saving a legacy flow.
- The only serialized non-default value is JSON numeric value 2. Accept numeric forms such as `2`, `2.0`, and `2e0` because they denote the same integral value. Reject booleans, strings, `null`, every non-integral/other number (including 2.5), and explicit numeric value 1 (including `1.0`). A profile field on a non-Markdown flow is invalid. Implementations MUST NOT add raw-token lexical restrictions.
- Schema 1–9 MUST reject the presence of `markdownProfileVersion`, even if its value is 2. This includes typed projections, backup manifests/payloads, imports and restore paths.
- Profile 1 is the C37 parser/layout behavior. Profile 2 is the C38 corrected Markdown behavior. A renderer must never silently render an effective profile 2 as profile 1.
- Profile 2 is per-flow; it does not alter source bytes, page anchors, geometry, style, source identity, or Agent/task provenance/receipts.

## Creation, upgrade and switching

- A newly created empty note uses schema 10. A newly created Markdown flow uses profile 2.
- A new AI/Agent flow appended to a schema 1–9 note stays profile 1 and does not silently promote the note. If creating a genuinely new note, the new note and Markdown flow use schema 10/profile 2.
- Existing notes remain in their current schema/profile until the user sees a real, full-paper before/after preview and explicitly accepts migration. Commit is guarded by the existing note revision/library generation CAS. Cancel, render failure, or stale CAS leaves serialized note bytes unchanged.
- Migration changes only the selected Markdown flow's optional profile and the note schema to 10 when needed. Preserve paper dimensions, anchors, all other source/material, and provenance. The effective compiled layout may require additional pages; derive the candidate `pageCount` from the complete preview and commit it atomically with the profile/schema. Do not reject otherwise valid content merely because the selected profile needs more pages. Switching back never automatically shrinks an existing page count or removes later user content; this is not historical rollback. Preserve all other flows and their original key presence and exact `source`. This profile-only migration is allowed even when no text bytes change.
- The existing old/new typography switch always renders current content with the selected style. A full preview may derive and atomically commit an increased `pageCount` when the selected profile requires additional pages. Preserve paper dimensions, anchors, other source/material, and provenance. Switching back does not automatically shrink an existing page count or remove later user content. Switching back to profile 1 is not a historical rollback and MUST NOT restore old source or prior flow contents. Do not add a `typographyUndo` receipt, history envelope, or archive format v3.
- A per-flow rendering cache key, compiled-layout identity, measurement result, and asynchronous callback ownership MUST include the effective profile (missing = 1). Late results from another profile may not replace the selected profile's layout.

## Parser profile semantics

- Profile 1 remains byte-for-byte compatible in output semantics with C37; do not run v2 transformations on it.
- Profile 2 tokenizes inline content before applying emphasis. Strong/emphasis scopes span inline math nodes; when strong encloses math, the formula glyphs themselves render strong/bold along with surrounding prose. Inline code is opaque to Markdown delimiters. Escaped delimiters remain literal; unmatched delimiters remain literal source.
- Standalone `---`, `***`, or `___` lines with optional surrounding whitespace become semantic thematic-break blocks (rendered as a visible separator with explicit measured height/margins). The same text inside fenced code is code. Table alignment rows remain table syntax.
- Existing link destinations/labels, diagrams, lists, tables, headings, quotes, fenced code and math semantics remain supported. Rule-block measurement and rendered height must agree so pagination does not drift.

## Archive, typed projection and compatibility

- Preserve the existing archive envelope version. Its note resource already carries the full note JSON; accept declared note schema 10 and preserve `markdownProfileVersion` exactly. Do not normalize absent key to explicit 1.
- Exact typed projections include `markdownProfileVersion` only when present and only for schema-10 Markdown flows. Their canonical digest must change when the profile changes and remain unchanged when absence is preserved.
- Existing archive envelope versions and schema 1–9 resources round-trip without adding profile keys. Schema 10/profile 2 resources round-trip byte-exactly across Android/iOS. Do not remove the field on export, backup, restore, copy-only conflict handling, or typed update.
- Older readers that stop at schema 9 must fail closed on schema-10 declarations or profile fields. They must not strip the field, rewrite as schema 9, or otherwise silently downgrade.

## Cross-platform golden fixture

`source/tools/fixtures/TYPOGRAPHY_CASES_R1.json` contains the exact Markdown source strings and semantic expectations for parser profile 2, plus regression cases for profile 1 and non-typographic constructs. Both platform test suites should consume this file (read-only/copy into their own test inputs as needed), not recreate independent expected behavior. Keep fixture updates reviewed as shared API changes.
