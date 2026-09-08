# Visual grammar

## quantity_change

Use for two to four positive quantities changing over time on a fixed zero-based scale. Data: `unit` and `states`, each with `label` (up to 32 characters) and positive numeric `value`. The HTML storyboard shows all states with proportional bars; video continuously changes a single bar's length using the same maximum throughout. This is useful for successive percentage changes; do not use it for negative values or a distribution. State beats currently divide scene audio equally, not by word alignment: keep narration clauses in the same order and similar length, and disclose timing limitations when relevant.

## title

Use for the lesson question or a section reset. Data: `title` and optional `subtitle`.

## formula_steps

Use for a sequence of LaTeX expressions. Data: ordered `steps`. Revideo renders each expression through its MathJax-backed `Latex` node; raw source is never the visual output.

## process

Use for a linear procedure or causal sequence. Data: ordered textual `steps`. The renderer numbers and reveals them in order.

## concept_map

Use for non-linear relationships or a cycle. Data: nodes with stable IDs and labeled or unlabeled directed edges. The renderer chooses positions; the IR never carries coordinates.

## comparison

Use for one explicit two-sided distinction. Data: `left` and `right`, each with a title and short items.

## annotated_source

Use when the source image itself must remain visible. Data: a safe `input/assets/` path and annotation strings. The worker copies the declared asset into the local render workspace and never fetches it from the network.

Unknown visual types fail validation and rendering. There is no plain-text downgrade.
