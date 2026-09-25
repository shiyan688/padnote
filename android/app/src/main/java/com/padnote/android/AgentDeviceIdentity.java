package com.padnote.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.UUID;

/** Stable non-secret device identity sent during Bridge pairing. */
final class AgentDeviceIdentity {
    private static final String STORE = "padnote-agent-device";
    private static final String ID = "device-id";
    private AgentDeviceIdentity() { }

    static String id(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        String existing = preferences.getString(ID, "");
        if (existing != null && !existing.isEmpty()) return existing;
        String generated = UUID.randomUUID().toString();
        if (!preferences.edit().putString(ID, generated).commit()) {
            throw new IllegalStateException("无法保存设备标识");
        }
        return generated;
    }

    static String name() {
        String model = Build.MODEL == null ? "Android 平板" : Build.MODEL.trim();
        return model.isEmpty() ? "Android 平板" : "PadNote · " + model;
    }
}
