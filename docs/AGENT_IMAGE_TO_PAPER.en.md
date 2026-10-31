# Place an Agent image on paper

When a completed Bridge task lists a PNG or JPEG file, its result offers **Place on paper**. Choose a target note, inspect the actual preview and any overlap with existing content, then decide whether to confirm; overlapping placement requires explicit acknowledgement. Existing content is not moved or deleted. Cancelling the preview leaves the note unchanged. After saving, the note can be reopened from the original task.

A drawing request can say: “First check whether the paper’s formula gives enough information. If the domain or other conditions are missing, explain that and draw a verifiable example curve. Return a PNG with axes and key points labelled.”

Before placement, PadNote checks the task artifact identity, source size and SHA-256, verifies the image type, and converts it to the PNG format used by notes. No file, a running or unresolved task, or a download/decode error never counts as a successful placement.

An Agent image is written only after the user confirms placement. Ordinary notes remain on schema 10; a note moves to schema 11 only when an image is confirmed. Typography changes do not downgrade schema 11. Before confirmation, the app warns that older PadNote versions cannot open the schema 11 note. The image and its source receipt are stored in the note document and included with note backups. Connection URLs, access tokens, and temporary paths are not written into the receipt.

Only PNG and JPEG are supported for paper placement. WebP and SVG are not supported for paper placement; PDF remains a document attachment and is not placed as a paper image.
