package com.padnote.android;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.UUID;

/** Page-relative image geometry; immutable pixels are shared by undo snapshots. */
final class NoteImage {
    static final class StoredImageInfo {
        final int width;
        final int height;
        final int encodedLength;

        StoredImageInfo(int width, int height, int encodedLength) {
            this.width = width;
            this.height = height;
            this.encodedLength = encodedLength;
        }
    }
    static final int MAX_EDGE = 1600;
    static final int MAX_DOCUMENT_PIXELS = 12 * 1024 * 1024;
    static final int MAX_DOCUMENT_ENCODED = 20 * 1024 * 1024;
    final String id;
    final Bitmap bitmap;
    final String png;
    int page;
    /** Canonical serialized binary64 geometry; float fields below are render projections. */
    double x64, y64, width64, height64;
    float x, y, width, height;

    NoteImage(String id, Bitmap bitmap, String png, int page,
              double x, double y, double width, double height) {
        this.id = id; this.bitmap = bitmap; this.png = png; this.page = page;
        setGeometry(x, y, width, height);
    }

    void setGeometry(double x, double y, double width, double height) {
        double checkedX = finiteNonnegative(x, "x");
        double checkedY = finiteNonnegative(y, "y");
        double checkedWidth = finiteNonnegative(width, "width");
        double checkedHeight = finiteNonnegative(height, "height");
        float renderX = PersistedGeometry.renderFloat(checkedX);
        float renderY = PersistedGeometry.renderFloat(checkedY);
        float renderWidth = PersistedGeometry.renderFloat(checkedWidth);
        float renderHeight = PersistedGeometry.renderFloat(checkedHeight);
        this.x64 = checkedX;
        this.y64 = checkedY;
        this.width64 = checkedWidth;
        this.height64 = checkedHeight;
        this.x = renderX;
        this.y = renderY;
        this.width = renderWidth;
        this.height = renderHeight;
    }

    private static double finiteNonnegative(double value, String key) {
        if (!Double.isFinite(value) || value < 0d) throw new IllegalArgumentException("image " + key + " invalid");
        return value;
    }

    NoteImage copy(boolean newId) {
        return new NoteImage(newId ? UUID.randomUUID().toString() : id,
                bitmap, png, page, x64, y64, width64, height64);
    }

    static NoteImage read(ContentResolver resolver, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("无法读取图片");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_EDGE) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap;
        try (InputStream input = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IllegalArgumentException("图片格式不受支持");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalArgumentException("无法编码图片");
        return new NoteImage(UUID.randomUUID().toString(), bitmap,
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP), 0, 0, 0, 0, 0);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("png", png).put("page", page)
                .put("x", x64).put("y", y64).put("width", width64).put("height", height64);
    }

    static NoteImage fromJson(JSONObject json) throws JSONException {
        String png = json.getString("png");
        byte[] bytes = decodeAndValidate(png);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) throw new JSONException("图片无法解码");
        return new NoteImage(json.getString("id"), bitmap, png, json.getInt("page"),
                finite(json, "x"), finite(json, "y"), finite(json, "width"), finite(json, "height"));
    }

    /** Validates encoded storage without allocating the full pixel bitmap. */
    static StoredImageInfo inspectJson(JSONObject json) throws JSONException {
        String png = json.getString("png");
        byte[] bytes = decodeAndValidate(png);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        return new StoredImageInfo(bounds.outWidth, bounds.outHeight, png.length());
    }

    private static byte[] decodeAndValidate(String png) throws JSONException {
        if (png.length() > MAX_DOCUMENT_ENCODED) throw new JSONException("图片数据过大");
        byte[] bytes;
        try { bytes = Base64.decode(png, Base64.DEFAULT); }
        catch (IllegalArgumentException error) { throw new JSONException("图片编码无效"); }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_EDGE
                || bounds.outHeight > MAX_EDGE) throw new JSONException("图片尺寸无效");
        return bytes;
    }

    private static double finite(JSONObject json, String key) throws JSONException {
        double value = json.getDouble(key);
        if (!Double.isFinite(value) || value < 0) throw new JSONException("图片位置或尺寸无效");
        return value;
    }
}
