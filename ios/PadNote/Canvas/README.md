# PadNote UIKit canvas

The canvas keeps `InkPoint` and `InkStroke` as ordinary Codable values. It does
not archive a PencilKit drawing, so the same `#AARRGGBB` stroke schema can be
read by Android. Pencil touches use coalesced samples and pressure; a finger
scrolls while `pencilOnly` is enabled. Lasso selection produces a polygon-clipped
white-backed `selectionImage` and supports moving, deleting, and duplicating
strokes.

Known limitations: text flows are rendered from their source with native fonts;
formula/Markdown enhancement remains the responsibility of the existing text
preview/editor layer. The renderer intentionally draws only visible pages in
the live surface; page snapshots and PDF export render one page at a time.
