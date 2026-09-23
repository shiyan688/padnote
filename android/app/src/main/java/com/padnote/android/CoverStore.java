package com.padnote.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Notebook covers: one optional PNG per note plus a preset library.
 *
 * <p>Convention over schema: a cover lives next to its note as
 * {@code notes/<noteId>.cover.png}, so {@link NoteStore}'s index format does
 * not change and renames/deletes need no migration. User-imported presets are
 * PNGs under the private {@code covers/} directory; the built-in presets are
 * drawn procedurally (design language: stationery realism, zero binaries).
 */
final class CoverStore {
    /** One selectable preset cover. */
    static final class Preset {
        final String id;
        final String label;
        final Bitmap bitmap;

        Preset(String id, String label, Bitmap bitmap) {
            this.id = id;
            this.label = label;
            this.bitmap = bitmap;
        }
    }

    static final int PRESET_WIDTH = 640;
    static final int PRESET_HEIGHT = 400;
    /** Covers are downscaled to at most this width when assigned/imported. */
    static final int MAX_STORED_WIDTH = 720;

    private static final String COVER_SUFFIX = ".cover.png";

    private CoverStore() {
    }

    /** Where a note's cover image lives; existence means the note has one. */
    static File coverFile(File notesDirectory, String noteId) {
        return new File(notesDirectory, noteId + COVER_SUFFIX);
    }

    private static File coverFile(Context context, String noteId) {
        return coverFile(new File(context.getFilesDir(), NoteStore.notesDirectoryName()),
                noteId);
    }

    static boolean has(Context context, String noteId) {
        return coverFile(context, noteId).isFile();
    }

    /** Writes {@code source} as the note's cover, downscaled, replacing any old one. */
    static void assign(Context context, String noteId, Bitmap source) throws Exception {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("封面位图不可用");
        }
        File file = coverFile(context, noteId);
        File directory = file.getParentFile();
        if (directory != null && !directory.exists()) {
            directory.mkdirs();
        }
        Bitmap scaled = scaleToFit(source, MAX_STORED_WIDTH);
        FileOutputStream stream = new FileOutputStream(file);
        try {
            scaled.compress(Bitmap.CompressFormat.PNG, 90, stream);
        } finally {
            stream.close();
            if (scaled != source) {
                scaled.recycle();
            }
        }
    }

    static void remove(Context context, String noteId) {
        File file = coverFile(context, noteId);
        if (file.isFile()) {
            file.delete();
        }
    }

    /** Decodes a cover sampled near {@code targetWidth} to keep the shelf smooth. */
    static Bitmap load(Context context, String noteId, int targetWidth) {
        File file = coverFile(context, noteId);
        if (!file.isFile()) {
            return null;
        }
        return decodeScaled(file, Math.max(1, targetWidth));
    }

    /** Built-in procedural covers; cheap enough to regenerate per dialog. */
    static List<Preset> builtinPresets() {
        List<Preset> presets = new ArrayList<>();
        presets.add(new Preset("builtin-ruled", "米白横线",
                drawRuled(Color.rgb(250, 248, 242), Color.rgb(206, 214, 222))));
        presets.add(new Preset("builtin-grid", "浅蓝方格",
                drawGrid(Color.rgb(245, 249, 253), Color.rgb(196, 216, 236))));
        presets.add(new Preset("builtin-dots", "墨绿点阵",
                drawDots(Color.rgb(235, 244, 240), Color.rgb(47, 128, 91))));
        presets.add(new Preset("builtin-blue", "书写蓝",
                drawSolid(Color.rgb(40, 94, 168), Color.rgb(232, 240, 250))));
        presets.add(new Preset("builtin-vault", "知识库绿",
                drawSolid(Color.rgb(47, 128, 91), Color.rgb(233, 243, 238))));
        presets.add(new Preset("builtin-ochre", "赭石",
                drawSolid(Color.rgb(164, 106, 42), Color.rgb(250, 242, 230))));
        return presets;
    }

    /** User-imported preset covers, oldest first. */
    static List<Preset> userPresets(Context context) {
        List<Preset> presets = new ArrayList<>();
        File directory = context.getDir("covers", Context.MODE_PRIVATE);
        File[] files = directory.listFiles();
        if (files == null) {
            return presets;
        }
        java.util.Arrays.sort(files);
        for (File file : files) {
            if (!file.getName().startsWith("preset-") || !file.getName().endsWith(".png")) {
                continue;
            }
            Bitmap bitmap = decodeScaled(file, 320);
            if (bitmap != null) {
                presets.add(new Preset("user:" + file.getName(), file.getName(), bitmap));
            }
        }
        return presets;
    }

    /** Copies an imported image into the user preset library. */
    static File addToPresets(Context context, Bitmap source) throws Exception {
        File directory = context.getDir("covers", Context.MODE_PRIVATE);
        File file = new File(directory, String.format(Locale.CHINA,
                "preset-%d.png", System.currentTimeMillis()));
        Bitmap scaled = scaleToFit(source, MAX_STORED_WIDTH);
        FileOutputStream stream = new FileOutputStream(file);
        try {
            scaled.compress(Bitmap.CompressFormat.PNG, 90, stream);
        } finally {
            stream.close();
            if (scaled != source) {
                scaled.recycle();
            }
        }
        return file;
    }

    /** Downsamples a stored image file toward {@code targetWidth}. */
    private static Bitmap decodeScaled(File file, int targetWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, targetWidth);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    static int sampleSize(int sourceWidth, int targetWidth) {
        int sample = 1;
        while (sourceWidth / (sample * 2) >= targetWidth) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap scaleToFit(Bitmap source, int maxWidth) {
        if (source.getWidth() <= maxWidth) {
            return source;
        }
        float ratio = (float) maxWidth / source.getWidth();
        Bitmap scaled = Bitmap.createScaledBitmap(source, maxWidth,
                Math.round(source.getHeight() * ratio), true);
        return scaled == null ? source : scaled;
    }

    private static Bitmap drawRuled(int paperColor, int lineColor) {
        Bitmap bitmap = blank();
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(paperColor);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(lineColor);
        paint.setStrokeWidth(dp(1.5f));
        int step = Math.round(dp(26));
        for (int y = step; y < bitmap.getHeight(); y += step) {
            canvas.drawLine(dp(18), y, bitmap.getWidth() - dp(18), y, paint);
        }
        return bitmap;
    }

    private static Bitmap drawGrid(int paperColor, int lineColor) {
        Bitmap bitmap = blank();
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(paperColor);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(lineColor);
        paint.setStrokeWidth(dp(1));
        int step = Math.round(dp(22));
        for (int x = step; x < bitmap.getWidth(); x += step) {
            canvas.drawLine(x, dp(10), x, bitmap.getHeight() - dp(10), paint);
        }
        for (int y = step; y < bitmap.getHeight(); y += step) {
            canvas.drawLine(dp(10), y, bitmap.getWidth() - dp(10), y, paint);
        }
        return bitmap;
    }

    private static Bitmap drawDots(int paperColor, int dotColor) {
        Bitmap bitmap = blank();
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(paperColor);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(dotColor);
        int step = Math.round(dp(24));
        float radius = dp(2.2f);
        for (int x = step; x < bitmap.getWidth(); x += step) {
            for (int y = step; y < bitmap.getHeight(); y += step) {
                canvas.drawCircle(x, y, radius, paint);
            }
        }
        return bitmap;
    }

    private static Bitmap drawSolid(int baseColor, int bandColor) {
        Bitmap bitmap = blank();
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(baseColor);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(bandColor);
        canvas.drawRoundRect(new RectF(dp(24), dp(140),
                bitmap.getWidth() - dp(24), bitmap.getHeight() - dp(64)), dp(10), dp(10), paint);
        return bitmap;
    }

    private static Bitmap blank() {
        Bitmap bitmap = Bitmap.createBitmap(PRESET_WIDTH, PRESET_HEIGHT,
                Bitmap.Config.ARGB_8888);
        return bitmap;
    }

    private static float dp(float value) {
        return value * android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }
}
