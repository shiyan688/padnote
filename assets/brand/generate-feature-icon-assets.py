#!/usr/bin/env python3
"""Generate Android cached PathParser constants from the shared feature icon JSON."""
from pathlib import Path
import json
root=Path(__file__).resolve().parents[2]
data=json.loads((root/'android/app/src/main/assets/padnote-tool-icons.json').read_text())
lines=['package com.padnote.android;','','import android.graphics.Path;','import androidx.core.graphics.PathParser;','','/** Generated from android/app/src/main/assets/padnote-tool-icons.json. */','final class PadNoteFeatureIconPaths {','    private PadNoteFeatureIconPaths() {}']
for name, paths in data.items():
    if not paths: continue
    lines.append(f'    private static final Path[] {name} = new Path[] {{')
    for path in paths: lines.append(f'        PathParser.createPathFromPathData("{path}"),')
    lines.append('    };')
lines += ['', '    static Path[] forIcon(ToolIconButton.Icon icon) {', '        switch (icon) {']
for name, paths in data.items():
    if paths: lines.append(f'            case {name}: return {name};')
lines += ['            default: return new Path[0];','        }','    }','}']
(root/'android/app/src/main/java/com/padnote/android/PadNoteFeatureIconPaths.java').write_text('\n'.join(lines)+'\n')
