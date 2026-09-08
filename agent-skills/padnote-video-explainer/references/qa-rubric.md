# QA rubric

A final result may be `completed` only after all checks below pass:

1. Request input manifest, file sizes, SHA-256 values, and bundle tree hash match.
2. Lesson IR Schema and semantic references pass; all visuals are supported.
3. Review artifacts exist, are offline, have matching metadata, include every scene, and predate `review.json`.
4. One non-silent PCM WAV exists per scene; manifest durations equal parsed container durations.
5. One 1080×1920 keyframe exists per scene and the storyboard safe-area/overflow gate passed before video rendering.
6. Scene frame boundaries and total composition frames derive from cumulative audio milliseconds at 30 fps.
7. MP4 dimensions, frame rate, and counted frames match `render.manifest.json`.
8. The last SRT cue ends at the exact cumulative source-audio end time.
9. Video, captions, thumbnail, render manifest, and QA report have matching MIME, byte count, and SHA-256.
10. `result.json` is atomically written after every declared artifact and then passes `validate:result`.

Any failure stops publication. Do not write or preserve a new `completed` result for a failed run.

Engineering validation is not a teaching-quality certificate. Before presenting a storyboard, inspect whether the chosen visuals explain the central relationship rather than repeat narration; check the reasoning, units, prerequisites and an answerable transfer question. State renderer limitations explicitly. Before presenting a video, inspect representative moving segments and listen to narration when playback is available. If only frames or audio metadata were checked, disclose that limitation; never report human-viewer comprehension as tested without viewer feedback.
