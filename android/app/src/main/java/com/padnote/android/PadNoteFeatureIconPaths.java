package com.padnote.android;

import android.graphics.Path;
import androidx.core.graphics.PathParser;

/** Generated from android/app/src/main/assets/padnote-tool-icons.json. */
final class PadNoteFeatureIconPaths {
    private PadNoteFeatureIconPaths() {}
    private static final Path[] PEN = new Path[] {
        PathParser.createPathFromPathData("M5 20L7 15L17 5L20 8L10 18Z"),
        PathParser.createPathFromPathData("M7 15L10 18"),
        PathParser.createPathFromPathData("M14 8L17 11"),
    };
    private static final Path[] HIGHLIGHTER = new Path[] {
        PathParser.createPathFromPathData("M4 16L16 4L20 8L8 20H4Z"),
        PathParser.createPathFromPathData("M8 16L12 20"),
        PathParser.createPathFromPathData("M4 22H20"),
    };
    private static final Path[] SHAPE = new Path[] {
        PathParser.createPathFromPathData("M2 4H11V14H2Z"),
        PathParser.createPathFromPathData("M17 4C19.5 4 21.5 6 21.5 9C21.5 12 19.5 14 17 14C14.5 14 12.5 12 12.5 9C12.5 6 14.5 4 17 4Z"),
    };
    private static final Path[] ERASER = new Path[] {
        PathParser.createPathFromPathData("M5 15L14 5L21 11L12 21H7L4 18Z"),
        PathParser.createPathFromPathData("M9 11L16 17"),
        PathParser.createPathFromPathData("M12 21H21"),
    };
    private static final Path[] LASSO = new Path[] {
        PathParser.createPathFromPathData("M12 3C17 3 21 6 21 11C21 16 17 18 12 17C7 19 3 16 3 11C3 6 7 3 12 3Z"),
        PathParser.createPathFromPathData("M16 16C20 19 17 22 14 20"),
    };
    private static final Path[] AI = new Path[] {
        PathParser.createPathFromPathData("M7 3H14L18 7V21H7Z"),
        PathParser.createPathFromPathData("M14 3V7H18"),
        PathParser.createPathFromPathData("M9 11C11 9 13 13 16 11"),
        PathParser.createPathFromPathData("M16 15C13 13 11 17 9 15"),
    };
    private static final Path[] UNDO = new Path[] {
        PathParser.createPathFromPathData("M10 6L5 11L10 16"),
        PathParser.createPathFromPathData("M5 11H14C18 11 20 14 19 19"),
    };
    private static final Path[] REDO = new Path[] {
        PathParser.createPathFromPathData("M14 6L19 11L14 16"),
        PathParser.createPathFromPathData("M19 11H10C6 11 4 14 5 19"),
    };
    private static final Path[] DUPLICATE = new Path[] {
        PathParser.createPathFromPathData("M4 3H15V16H4Z"),
        PathParser.createPathFromPathData("M9 8H20V21H9Z"),
    };
    private static final Path[] DELETE = new Path[] {
        PathParser.createPathFromPathData("M5 7H19L18 21H6Z"),
        PathParser.createPathFromPathData("M3 4H21"),
        PathParser.createPathFromPathData("M9 4V2H15V4"),
        PathParser.createPathFromPathData("M10 10V18"),
        PathParser.createPathFromPathData("M14 10V18"),
    };
    private static final Path[] CANCEL = new Path[] {
        PathParser.createPathFromPathData("M5 5L19 19"),
        PathParser.createPathFromPathData("M19 5L5 19"),
    };
    private static final Path[] CLEAR = new Path[] {
        PathParser.createPathFromPathData("M12 3V14"),
        PathParser.createPathFromPathData("M7 13H17L20 21H4Z"),
    };
    private static final Path[] SAVE = new Path[] {
        PathParser.createPathFromPathData("M4 3H18L21 6V21H3V3Z"),
        PathParser.createPathFromPathData("M7 3V9H16V3"),
        PathParser.createPathFromPathData("M7 13H17V21H7Z"),
    };
    private static final Path[] EXPORT = new Path[] {
        PathParser.createPathFromPathData("M4 14V20H20V14"),
        PathParser.createPathFromPathData("M12 17V3"),
        PathParser.createPathFromPathData("M7 8L12 3L17 8"),
    };
    private static final Path[] BOOKSHELF = new Path[] {
        PathParser.createPathFromPathData("M3 4H8V19H3Z"),
        PathParser.createPathFromPathData("M10 6H15V19H10Z"),
        PathParser.createPathFromPathData("M17 3H21V19H17Z"),
        PathParser.createPathFromPathData("M2 21H22"),
    };
    private static final Path[] PAGE_ADD = new Path[] {
        PathParser.createPathFromPathData("M5 3H15L19 7V19H5Z"),
        PathParser.createPathFromPathData("M15 3V7H19"),
        PathParser.createPathFromPathData("M9 11H15"),
        PathParser.createPathFromPathData("M12 8V14"),
    };
    private static final Path[] PAGE_MENU = new Path[] {
        PathParser.createPathFromPathData("M5 3H15L19 7V21H5Z"),
        PathParser.createPathFromPathData("M15 3V7H19"),
        PathParser.createPathFromPathData("M8 11H16"),
        PathParser.createPathFromPathData("M8 14H16"),
        PathParser.createPathFromPathData("M8 17H16"),
    };
    private static final Path[] IMAGE = new Path[] {
        PathParser.createPathFromPathData("M3 5H21V19H3Z"),
        PathParser.createPathFromPathData("M6 16L10 11L13 14L16 10L20 16"),
        PathParser.createPathFromPathData("M8 9C8 8 9 7 10 7C11 7 12 8 12 9C12 10 11 11 10 11C9 11 8 10 8 9Z"),
    };
    private static final Path[] TEXT_BOX = new Path[] {
        PathParser.createPathFromPathData("M4 4H20V20H4Z"),
        PathParser.createPathFromPathData("M8 8H16"),
        PathParser.createPathFromPathData("M12 8V17"),
    };
    private static final Path[] PEN_ONLY = new Path[] {
        PathParser.createPathFromPathData("M5 18L7 13L17 3L21 7L11 17Z"),
        PathParser.createPathFromPathData("M15 17L18 20L22 14"),
    };

    static Path[] forIcon(ToolIconButton.Icon icon) {
        switch (icon) {
            case PEN: return PEN;
            case HIGHLIGHTER: return HIGHLIGHTER;
            case SHAPE: return SHAPE;
            case ERASER: return ERASER;
            case LASSO: return LASSO;
            case AI: return AI;
            case UNDO: return UNDO;
            case REDO: return REDO;
            case DUPLICATE: return DUPLICATE;
            case DELETE: return DELETE;
            case CANCEL: return CANCEL;
            case CLEAR: return CLEAR;
            case SAVE: return SAVE;
            case EXPORT: return EXPORT;
            case BOOKSHELF: return BOOKSHELF;
            case PAGE_ADD: return PAGE_ADD;
            case PAGE_MENU: return PAGE_MENU;
            case IMAGE: return IMAGE;
            case TEXT_BOX: return TEXT_BOX;
            case PEN_ONLY: return PEN_ONLY;
            default: return new Path[0];
        }
    }
}
