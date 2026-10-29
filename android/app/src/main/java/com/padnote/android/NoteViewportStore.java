package com.padnote.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Durable UI-only viewport state. It deliberately never rewrites note bodies or revisions. */
final class NoteViewportStore {
    private static final String PREFS = "padnote-note-viewports-v1";
    // Canvas permits absolute scale 4f and fittedPageScale bottoms at 0.45f.
    // Keep a small float-rounding margin above their real 8.888... relative limit.
    private static final double MAX_ZOOM = 8.89d;
    private static final double MAX_WORLD = 1_000_000_000d;
    /** One process-wide FIFO prevents a finishing Activity from overwriting its successor. */
    private static volatile Thread writerThread;
    private static final ExecutorService WRITES = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "padnote-viewport-store");
        thread.setDaemon(true);
        writerThread = thread;
        return thread;
    });

    interface WriteCallback { void onComplete(boolean saved); }

    static final class State {
        final double relativeZoom;
        final double centerX;
        final double centerY;

        State(double relativeZoom, double centerX, double centerY) {
            this.relativeZoom = relativeZoom;
            this.centerX = centerX;
            this.centerY = centerY;
        }

        boolean isValid() {
            return Double.isFinite(relativeZoom) && relativeZoom >= 0.05d &&
                    relativeZoom <= MAX_ZOOM && Double.isFinite(centerX) &&
                    Double.isFinite(centerY) && Math.abs(centerX) <= MAX_WORLD &&
                    Math.abs(centerY) <= MAX_WORLD;
        }
    }

    private NoteViewportStore() {}

    static State read(Context context, String noteId) throws JSONException {
        if (!validNoteId(noteId)) return null;
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(noteId), null);
        if (raw == null) return null;
        JSONObject value = new JSONObject(raw);
        if (!noteId.equals(value.optString("noteId", ""))) return null;
        State state = new State(value.optDouble("relativeZoom", Double.NaN),
                value.optDouble("centerX", Double.NaN),
                value.optDouble("centerY", Double.NaN));
        return state.isValid() ? state : null;
    }

    /**
     * Worker-only read that observes every write submitted before this call, including
     * a previous Activity's onPause snapshot. The bounded wait never runs on the UI or
     * the writer thread itself.
     */
    static State readAfterQueuedWrites(Context context, String noteId) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("viewport read barrier must not block the UI thread");
        }
        if (Thread.currentThread() == writerThread) {
            throw new IllegalStateException("viewport read barrier cannot run on its writer");
        }
        Context app = context.getApplicationContext();
        Future<State> read = WRITES.submit(() -> NoteViewportStore.read(app, noteId));
        try {
            return read.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            read.cancel(false);
            throw new java.io.IOException("VIEWPORT_READ_BARRIER_TIMEOUT", timeout);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new java.io.IOException("VIEWPORT_READ_BARRIER_FAILED", cause);
        }
    }

    /** Must run on a worker thread; one preference value is the atomic commit unit. */
    static boolean write(Context context, String noteId, State state) {
        if (!validNoteId(noteId) || state == null || !state.isValid()) return false;
        try {
            JSONObject value = new JSONObject().put("noteId", noteId)
                    .put("relativeZoom", state.relativeZoom)
                    .put("centerX", state.centerX).put("centerY", state.centerY);
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return prefs.edit().putString(key(noteId), value.toString()).commit();
        } catch (JSONException | RuntimeException error) {
            return false;
        }
    }

    static void writeAsync(Context context, String noteId, State state, WriteCallback callback) {
        Context app = context.getApplicationContext();
        WRITES.execute(() -> {
            boolean saved = write(app, noteId, state);
            if (callback != null) callback.onComplete(saved);
        });
    }

    private static boolean validNoteId(String noteId) {
        if (noteId == null || noteId.isEmpty() || noteId.length() > 256) return false;
        for (int i = 0; i < noteId.length(); i++) {
            char c = noteId.charAt(i);
            if (c < 0x20 || (c >= 0x7f && c <= 0x9f)) return false;
        }
        return true;
    }

    private static String key(String noteId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(noteId.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("note-");
            for (byte item : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
