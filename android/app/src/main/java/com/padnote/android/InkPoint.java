package com.padnote.android;

import org.json.JSONException;
import org.json.JSONObject;

final class InkPoint {
    float x;
    float y;
    final long timestamp;
    final float pressure;

    InkPoint(float x, float y, long timestamp, float pressure) {
        this.x = x;
        this.y = y;
        this.timestamp = timestamp;
        this.pressure = pressure;
    }

    InkPoint copy() {
        return new InkPoint(x, y, timestamp, pressure);
    }

    void translate(float dx, float dy) {
        x += dx;
        y += dy;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("x", x);
        json.put("y", y);
        json.put("timestamp", timestamp);
        json.put("pressure", pressure);
        return json;
    }

    static InkPoint fromJson(JSONObject json) throws JSONException {
        return new InkPoint(
                (float) json.getDouble("x"),
                (float) json.getDouble("y"),
                json.getLong("timestamp"),
                (float) json.optDouble("pressure", 0.5)
        );
    }
}
