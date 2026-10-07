package com.padnote.android;

import org.json.JSONException;
import org.json.JSONObject;

final class InkPoint {
    /** Canonical persisted values. x/y/pressure below are render/input projections. */
    double x64;
    double y64;
    final Number timestampValue;
    final double pressure64;

    float x;
    float y;
    final long timestamp;
    final float pressure;

    InkPoint(double x, double y, Number timestamp, double pressure) {
        this.x64 = x;
        this.y64 = y;
        this.timestampValue = PersistedGeometry.persistedNumber(timestamp, "timestamp");
        this.pressure64 = pressure;
        this.x = PersistedGeometry.renderFloat(x);
        this.y = PersistedGeometry.renderFloat(y);
        this.timestamp = this.timestampValue.longValue();
        this.pressure = PersistedGeometry.renderFloat(pressure);
    }

    InkPoint(float x, float y, long timestamp, float pressure) {
        this((double) x, (double) y, Long.valueOf(timestamp), (double) pressure);
    }

    InkPoint copy() {
        return new InkPoint(x64, y64, timestampValue, pressure64);
    }

    void translate(float dx, float dy) {
        translate((double) dx, (double) dy);
    }

    void translate(double dx, double dy) {
        double nextX64 = dx == 0d ? x64 : x64 + dx;
        double nextY64 = dy == 0d ? y64 : y64 + dy;
        float nextX = PersistedGeometry.renderFloat(nextX64);
        float nextY = PersistedGeometry.renderFloat(nextY64);
        x64 = nextX64;
        y64 = nextY64;
        x = nextX;
        y = nextY;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("x", x64);
        json.put("y", y64);
        json.put("timestamp", timestampValue);
        json.put("pressure", pressure64);
        return json;
    }

    static InkPoint fromJson(JSONObject json) throws JSONException {
        Object rawTimestamp = json.get("timestamp");
        Number timestamp = PersistedGeometry.persistedNumber(rawTimestamp, "timestamp");
        return new InkPoint(json.getDouble("x"), json.getDouble("y"), timestamp,
                json.optDouble("pressure", 0.5));
    }
}
