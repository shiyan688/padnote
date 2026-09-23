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
    static final int MAX_EDGE = 1600;
    static final int MAX_DOCUMENT_PIXELS = 12 * 1024 * 1024;
    static final int MAX_DOCUMENT_ENCODED = 20 * 1024 * 1024;
    final String id;
    final Bitmap bitmap;
    final String png;
    int page;
    float x, y, width, height;

    NoteImage(String id, Bitmap bitmap, String png, int page,
              float x, float y, float width, float height) {
        this.id = id; this.bitmap = bitmap; this.png = png; this.page = page;
        this.x = x; this.y = y; this.width = width; this.height = height;
    }

    NoteImage copy(boolean newId) {
        return new NoteImage(newId ? UUID.randomUUID().toString() : id,
                bitmap, png, page, x, y, width, height);
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
                .put("x", x).put("y", y).put("width", width).put("height", height);
    }

    static NoteImage fromJson(JSONObject json) throws JSONException {
        String png = json.getString("png");
        if (png.length() > MAX_DOCUMENT_ENCODED) throw new JSONException("图片数据过大");
        byte[] bytes;
        try { bytes = Base64.decode(png, Base64.DEFAULT); }
        catch (IllegalArgumentException error) { throw new JSONException("图片编码无效"); }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_EDGE
                || bounds.outHeight > MAX_EDGE) throw new JSONException("图片尺寸无效");
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) throw new JSONException("图片无法解码");
        return new NoteImage(json.getString("id"), bitmap, png, json.getInt("page"),
                finite(json, "x"), finite(json, "y"), finite(json, "width"), finite(json, "height"));
    }

    private static float finite(JSONObject json, String key) throws JSONException {
        float value = (float) json.getDouble(key);
        if (!Float.isFinite(value) || value < 0) throw new JSONException("图片位置或尺寸无效");
        return value;
    }
}
