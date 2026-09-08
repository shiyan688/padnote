# Lesson IR v1

Lesson IR is a semantic teaching plan, not renderer source. It contains source identity, episode intent, auditable claims, scenes, and a fixed 9:16 render profile. It must pass `schemas/lesson-ir.schema.json` and `scripts/validate-ir.ts`.

Each scene has one learning objective, claim references, narration, concise screen text, one visual, an animation intent with a teaching explanation, and an estimated planning duration. Final timing never uses that estimate; it uses parsed WAV duration.

Allowed visuals are `title`, `formula_steps`, `concept_map`, `process`, `comparison`, `annotated_source`, and `quantity_change`. CSS, HTML, component code, FFmpeg commands, absolute layout coordinates, base64 payloads, and untracked external assets are forbidden.

Every claim has source references or `verification: "uncertain"`. When external research is disabled, claims cannot be marked `external-source`. Scene claim IDs, concept-map edges, and annotated asset paths are validated before any preview or render.

The schemas in this Skill are the executable contract. `SKILL.md`, this file, `tts-contract.md`, and `qa-rubric.md` provide the portable task-root, review, audio, render, and result rules. The PadNote repository's `docs/VIDEO_AGENT_CONTRACT.md` is an integration document, not a runtime dependency.
