# PadNote brand assets

`imagegen-v2/padnote-mark-clean.png` is the current transparent raster master used for the Android, iOS, Windows, and bookshelf marks. The PNG and ICO files in this directory are generated exports; the feature-icon set has its own generator and remains separate from the app mark.

Run `python3 assets/brand/generate-brand-assets.py` from the source root to regenerate the platform exports. The iOS copy/validation helper is `ios/scripts/generate-icon.swift`; it reads `assets/brand/PadNote-1024.png` and writes the iOS AppIcon asset. The tool-icon generator is `assets/brand/render-tool-icons.py`.

The `imagegen-v2/PROMPTS.md` file records the current logo-generation and extraction prompts. The two historical extraction sources remain as prompt-reference records; the clean master is the only export source. These are raster assets; no vector artwork is claimed.
