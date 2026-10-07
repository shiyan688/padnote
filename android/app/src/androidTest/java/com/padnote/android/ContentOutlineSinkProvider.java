package com.padnote.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.SystemClock;

/** Test APK only: pipe sink that proves the writer closes and delivers exact bytes to EOF. */
public final class ContentOutlineSinkProvider extends ContentProvider {
    private static final String AUTHORITY = "com.padnote.android.beta.test.contentoutline";
    private static final String CALL_BEGIN = "begin";
    private static final String CALL_STATUS = "status";
    private static final String CALL_CLEANUP = "cleanup";
    private static final int MAX_CAPTURE_BYTES = 16 * 1024 * 1024;
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "outline-test-pipe-reader"); thread.setDaemon(true); return thread;
    });
    private static final Object STATE_LOCK = new Object();
    private static String activeSession;
    private static SinkState activeState;

    static final class Capture {
        final boolean complete;
        final byte[] bytes;
        final String failure;
        final int eofCount, openCount;
        Capture(boolean complete, byte[] bytes, String failure, int eofCount, int openCount) {
            this.complete = complete; this.bytes = bytes == null ? null : bytes.clone();
            this.failure = failure; this.eofCount = eofCount; this.openCount = openCount;
        }
    }
    private static final class SinkState {
        boolean complete;
        byte[] bytes;
        String fileName;
        String failure;
        int eofCount, openCount;
    }

    static String uriString(Context context, String sessionId) {
        if (!"com.padnote.android.beta.test".equals(context.getPackageName()))
            throw new IllegalStateException("unexpected instrumentation package");
        requireUuid(sessionId);
        return "content://" + AUTHORITY + "/" + sessionId + "/outline.md";
    }

    static Uri captureUri(Context context, String sessionId) {
        if (!"com.padnote.android.beta.test".equals(context.getPackageName()))
            throw new IllegalStateException("unexpected instrumentation package");
        requireUuid(sessionId);
        return Uri.parse("content://" + AUTHORITY + "/" + sessionId + "/capture.bin");
    }

    static void beginSession(Context context, String sessionId) {
        requireUuid(sessionId);
        Bundle result = context.getContentResolver().call(Uri.parse("content://" + AUTHORITY),
                CALL_BEGIN, sessionId, null);
        if (result == null || !result.getBoolean("ok"))
            throw new IllegalStateException("test sink session could not begin");
    }

    static Capture awaitCapture(Context context, String sessionId, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        Capture last = new Capture(false, null, null, 0, 0);
        while (SystemClock.uptimeMillis() < deadline) {
            Bundle result = context.getContentResolver().call(Uri.parse("content://" + AUTHORITY),
                    CALL_STATUS, sessionId, null);
            if (result == null || !result.getBoolean("ok")) return last;
            byte[] bytes = result.getByteArray("bytes");
            if (bytes != null && bytes.length > MAX_CAPTURE_BYTES)
                throw new IllegalStateException("test sink response exceeded its bound");
            last = new Capture(result.getBoolean("complete"), bytes,
                    result.getString("failure"), result.getInt("eof_count"),
                    result.getInt("open_count"));
            if (last.complete) {
                String fileName = result.getString("file_name");
                if (last.bytes == null && fileName != null) {
                    try (InputStream input = context.getContentResolver()
                            .openInputStream(captureUri(context, sessionId))) {
                        if (input == null) throw new IOException("capture content stream unavailable");
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        byte[] buffer = new byte[16 * 1024];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            if (count == 0) continue;
                            if (output.size() + count > MAX_CAPTURE_BYTES)
                                throw new IOException("test sink response exceeded its bound");
                            output.write(buffer, 0, count);
                        }
                        last = new Capture(true, output.toByteArray(),
                                last.failure, last.eofCount, last.openCount);
                    } catch (IOException error) {
                        throw new IllegalStateException("test sink capture stream unavailable", error);
                    }
                }
                return last;
            }
            SystemClock.sleep(50);
        }
        return last;
    }

    static void cleanupSession(Context context, String sessionId) {
        if (sessionId == null) return;
        requireUuid(sessionId);
        context.getContentResolver().call(Uri.parse("content://" + AUTHORITY),
                CALL_CLEANUP, sessionId, null);
    }

    private static String requireUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid test sink session", invalid);
        }
    }

    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        synchronized (STATE_LOCK) {
            if (CALL_BEGIN.equals(method)) {
                String id = requireUuid(arg);
                if (activeState != null && !activeState.complete) {
                    result.putBoolean("ok", false); return result;
                }
                activeSession = id;
                activeState = new SinkState();
                result.putBoolean("ok", true); return result;
            }
            if (arg == null || !arg.equals(activeSession) || activeState == null) {
                result.putBoolean("ok", false); return result;
            }
            if (CALL_CLEANUP.equals(method)) {
                if (activeState.fileName != null) {
                    File captured = new File(getContext().getCacheDir(), activeState.fileName);
                    if (captured.isFile()) captured.delete();
                }
                activeSession = null; activeState = null;
                result.putBoolean("ok", true); return result;
            }
            if (CALL_STATUS.equals(method)) {
                SinkState state = activeState;
                result.putBoolean("ok", true);
                result.putBoolean("complete", state.complete);
                result.putInt("open_count", state.openCount);
                result.putInt("eof_count", state.eofCount);
                if (state.failure != null) result.putString("failure", state.failure);
                if (state.bytes != null && state.bytes.length <= 256 * 1024)
                    result.putByteArray("bytes", state.bytes.clone());
                if (state.fileName != null) result.putString("file_name", state.fileName);
                return result;
            }
        }
        result.putBoolean("ok", false);
        return result;
    }
    @Override public String getType(Uri uri) {
        return "export.pdf".equals(uri.getLastPathSegment()) ? "application/pdf" : "text/markdown";
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String name = uri.getLastPathSegment() == null ? "outline.md" : uri.getLastPathSegment();
        return new android.database.MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE}, 1) {{ addRow(new Object[]{name, null}); }};
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        java.util.List<String> segments = uri.getPathSegments();
        if ("r".equals(mode) && segments.size() == 2 && "capture.bin".equals(segments.get(1))) {
            final File captured;
            synchronized (STATE_LOCK) {
                String expectedFile = "sink-" + segments.get(0) + ".bin";
                if (activeSession == null || !activeSession.equals(segments.get(0))
                        || activeState == null || !activeState.complete
                        || !expectedFile.equals(activeState.fileName)) {
                    throw new FileNotFoundException("capture is not complete for this session");
                }
                captured = new File(getContext().getCacheDir(), expectedFile);
                if (!captured.isFile() || captured.length() > MAX_CAPTURE_BYTES)
                    throw new FileNotFoundException("completed capture file unavailable");
            }
            return ParcelFileDescriptor.open(captured, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (!"w".equals(mode) || segments.size() != 2
                || !("outline.md".equals(segments.get(1)) || "export.pdf".equals(segments.get(1)))) {
            throw new FileNotFoundException("test sink only accepts outline.md or export.pdf write");
        }
        final SinkState state;
        synchronized (STATE_LOCK) {
            if (activeSession == null || !activeSession.equals(segments.get(0)) || activeState == null) {
                throw new FileNotFoundException("unknown test sink session");
            }
            state = activeState;
            if (++state.openCount != 1) throw new FileNotFoundException("duplicate test sink open");
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            READER.execute(() -> {
                String fileName = "sink-" + segments.get(0) + ".bin";
                File captured = new File(getContext().getCacheDir(), fileName);
                long total = 0;
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                     FileOutputStream output = new FileOutputStream(captured, false)) {
                    byte[] buffer = new byte[16 * 1024]; int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count == 0) continue;
                        total += count;
                        if (total > MAX_CAPTURE_BYTES)
                            throw new IOException("OUTPUT_TOO_LARGE");
                        output.write(buffer, 0, count);
                    }
                    output.flush(); output.getFD().sync();
                } catch (Exception failure) {
                    if (captured.exists()) captured.delete();
                    synchronized (STATE_LOCK) {
                        state.failure = failure.getClass().getSimpleName();
                        state.fileName = null;
                    }
                } finally {
                    synchronized (STATE_LOCK) {
                        if (state.failure == null && captured.isFile()) {
                            state.fileName = fileName;
                            state.eofCount++;
                        } else if (state.failure == null) {
                            state.failure = "CaptureFileMissing";
                        }
                        state.complete = true;
                    }
                }
            });
            return pipe[1];
        } catch (IOException failure) {
            synchronized (STATE_LOCK) {
                state.failure = failure.getClass().getSimpleName(); state.complete = true;
            }
            FileNotFoundException wrapped = new FileNotFoundException("test pipe unavailable");
            wrapped.initCause(failure); throw wrapped;
        }
    }
}
