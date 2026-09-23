package com.padnote.android;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class InkStroke {
    final String id;
    final int color;
    final float baseWidth;
    final long createdAt;
    final boolean highlighter;
    final List<InkPoint> points = new ArrayList<>();

    InkStroke(String id, int color, float baseWidth, long createdAt) {
        this(id, color, baseWidth, createdAt, false);
    }

    InkStroke(String id, int color, float baseWidth, long createdAt, boolean highlighter) {
        this.id = id;
        this.color = color;
        this.baseWidth = baseWidth;
        this.createdAt = createdAt;
        this.highlighter = highlighter;
    }

    InkStroke copy() {
        InkStroke copy = new InkStroke(id, color, baseWidth, createdAt, highlighter);
        for (InkPoint point : points) {
            copy.points.add(point.copy());
        }
        return copy;
    }

    InkStroke copyWithId(String newId) {
        InkStroke copy = new InkStroke(newId, color, baseWidth, System.currentTimeMillis(), highlighter);
        for (InkPoint point : points) {
            copy.points.add(point.copy());
        }
        return copy;
    }

    void translate(float dx, float dy) {
        for (InkPoint point : points) {
            point.translate(dx, dy);
        }
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("color", String.format("#%08X", color));
        json.put("baseWidth", baseWidth);
        json.put("createdAt", createdAt);
        if (highlighter) json.put("highlighter", true);
        JSONArray pointArray = new JSONArray();
        for (InkPoint point : points) {
            pointArray.put(point.toJson());
        }
        json.put("points", pointArray);
        return json;
    }

    static InkStroke fromJson(JSONObject json) throws JSONException {
        InkStroke stroke = new InkStroke(
                json.getString("id"),
                Color.parseColor(json.getString("color")),
                (float) json.getDouble("baseWidth"),
                json.getLong("createdAt"),
                json.optBoolean("highlighter", false)
        );
        JSONArray pointArray = json.getJSONArray("points");
        for (int index = 0; index < pointArray.length(); index++) {
            stroke.points.add(InkPoint.fromJson(pointArray.getJSONObject(index)));
        }
        return stroke;
    }
}
