# Logo generation record

Created 2026-10-15 with the built-in `image_gen.imagegen` tool. The first code-drawn P concept was rejected by the user. The following prompts produced the current raster masters; these files are not SVG source.

## `padnote-icon-source.png`

Use case: logo-brand. Asset type: production app icon for PadNote, a tablet workspace where a human and an AI learn, sketch and write together on the same sheet. Design an exceptional, original, memorable mark with the restraint and optical craft of a high-end independent productivity app. One confident symbol: two generous interleaving ink strokes that suggest an open sheet and a conversation continuing across it; a subtle pen gesture, coherent silhouette, beautiful negative space. Make it feel thoughtful, tactile, calm and immediately recognizable at 32 pixels. Avoid literal document-with-letter-P, avoid a generic notebook/pencil illustration, AI stars, robots, brains, chat bubbles, tiny details and clipart. Palette: deep blue ink, warm ivory and a single clear periwinkle-blue accent, balanced warm/cool. Refined flat graphic, smooth curves, crisp edges, no bevels, no 3D, no gradients or drop shadows. Center the mark within roughly the central 60 percent of the square so launcher masks have generous breathing room. Full-bleed solid warm ivory square canvas extending to every edge, no outer rounded-square frame, no text, no wordmark, no mockup, no grid, no watermarks. Deliver one finished square app icon, not a presentation sheet.

`transparent_background: false`; no reference image.

## `padnote-mark-source.png`

Use case: background-extraction. This is an exact production brand asset extraction. Remove only the ivory background and make it genuinely transparent. Preserve the two navy and blue interleaving ink strokes pixel-faithfully: same proportions, curves, position, colors and smooth edges, no redesign, no added elements, no outline, no shadow. Preserve the square canvas dimensions and centered mark with existing breathing room. Transparent RGBA output.

`transparent_background: true`; reference: `padnote-icon-source.png`.

## `padnote-mark-clean.png` — current production master

Use case: background-extraction. Extract the exact two-stroke PadNote logo from this reference into a clean production transparent PNG. Keep both strokes' existing geometry, aspect ratio, relative position, dark navy and blue colors. The only content in the image is these two strokes. Outside the strokes is perfectly transparent alpha zero, including the central opening and all margins. Absolutely no residual ivory haze, no faint paper texture outside the mark, no ghost shapes or nearly transparent stray pixels in the margins. The interiors of the navy and blue shapes are solid opaque, with smooth antialiasing confined to the actual shape boundaries. Do not redraw or stylize, no additional marks, no shadows. Preserve the square canvas and original placement. This is a clean logo cutout for a launcher foreground.

`transparent_background: true`; reference: `padnote-icon-source.png`.

The model produced a subtly textured mark. Alpha extraction is model-generated and is visually similar to, rather than guaranteed pixel-identical with, the opaque source. The clean transparent master is the single production source for Android, iOS, Windows and the bookshelf, so those exports have identical shape proportions. The first opaque and transparent images remain design records. Mechanical exports resize proportionally and add the platform background or file container; they do not redraw the mark. The source contains a few alpha=1 margin pixels, recorded during export review; it is not claimed to be a mathematically perfect vector mask.
