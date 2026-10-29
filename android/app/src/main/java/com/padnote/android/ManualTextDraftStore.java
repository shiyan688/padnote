package com.padnote.android;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * App-private, bounded checkpoints for an explicitly recoverable manual text edit.
 *
 * <p>A record is not part of the note document and never changes canonical note state. The owner
 * must ask the user whether to resume, defer, or discard it. Checkpoints are synchronous and are
 * intended for lifecycle boundaries such as {@code onPause}, never for every keystroke. A failed
 * checkpoint is reported to the caller; it does not authorize closing or clearing the editor.
 *
 * <p>The persisted JSON has an exact versioned key set. Source text is capped at one million UTF-16
 * code units and four MiB of well-formed UTF-8; the complete JSON record is capped at eight MiB.
 * Larger drafts are rejected before writing. Font and line-height values are stored as canonical
 * finite decimal strings so the strict integer-only JSON codec can reject ambiguous numbers.
 *
 * <p>Writes are compare-and-set against the previous {@link DraftToken}. This prevents a stale
 * lifecycle checkpoint from replacing a newer draft. Clear operations use the same exact token;
 * an invalid record can only be deleted after an explicit user discard and a raw-byte SHA match.
 */
final class ManualTextDraftStore {
    static final int MAX_SOURCE_UTF16_UNITS = 1_000_000;
    static final int MAX_SOURCE_UTF8_BYTES = 4 * 1024 * 1024;
    static final int MAX_RECORD_BYTES = 8 * 1024 * 1024;

    private static final String DIRECTORY = "manual-text-drafts";
    private static final long SCHEMA_VERSION = 1L;
    private static final Object LOCK = new Object();
    private static final String[] RECORD_KEYS = {
            "schemaVersion", "noteId", "flowId", "fragmentId", "source", "format",
            "fontSizeSp", "lineHeight", "newlyInserted", "baseSourceFingerprint",
            "sessionId", "generation"
    };

    enum LoadStatus { NONE, READY, INVALID, IO_ERROR }
    enum SaveStatus { SAVED, INVALID, TOO_LARGE, CONFLICT, IO_ERROR }
    enum ClearStatus { CLEARED, NOT_FOUND, STALE, INVALID, IO_ERROR }

    static final class DraftToken {
        final String sessionId;
        final long generation;

        DraftToken(String sessionId, long generation) {
            this.sessionId = sessionId;
            this.generation = generation;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof DraftToken)) return false;
            DraftToken token = (DraftToken) other;
            return generation == token.generation && sessionId.equals(token.sessionId);
        }

        @Override public int hashCode() {
            return 31 * sessionId.hashCode() + (int) (generation ^ (generation >>> 32));
        }
    }

    static final class Draft {
        final String noteId;
        final String flowId;
        final String fragmentId;
        final String source;
        /** The exact {@link NoteTextBox.Format#name()} value. */
        final String format;
        final float fontSize;
        final float lineHeight;
        final boolean newlyInserted;
        /** SHA-256 of the canonical base source's exact UTF-8 bytes, lowercase hex. */
        final String baseSourceFingerprint;
        /** UUID assigned once for the editor session. */
        final String sessionId;
        /** Strictly increasing checkpoint number within this editor session. */
        final long generation;

        Draft(String noteId, String flowId, String fragmentId, String source, String format,
              float fontSize, float lineHeight, boolean newlyInserted,
              String baseSourceFingerprint, String sessionId, long generation) {
            this.noteId = noteId;
            this.flowId = flowId;
            this.fragmentId = fragmentId;
            this.source = source;
            this.format = format;
            this.fontSize = fontSize;
            this.lineHeight = lineHeight;
            this.newlyInserted = newlyInserted;
            this.baseSourceFingerprint = baseSourceFingerprint;
            this.sessionId = sessionId;
            this.generation = generation;
        }

        DraftToken token() { return new DraftToken(sessionId, generation); }
    }

    static final class LoadResult {
        final LoadStatus status;
        final Draft draft;
        final DraftToken token;
        /** Exact raw record hash, available for explicit invalid-record discard when bounded. */
        final String rawSha256;
        final String errorCode;
        final File observedFile;

        private LoadResult(LoadStatus status, Draft draft, DraftToken token,
                           String rawSha256, String errorCode, File observedFile) {
            this.status = status;
            this.draft = draft;
            this.token = token;
            this.rawSha256 = rawSha256;
            this.errorCode = errorCode;
            this.observedFile = observedFile;
        }
    }

    static final class SaveResult {
        final SaveStatus status;
        final DraftToken token;
        final String errorCode;

        private SaveResult(SaveStatus status, DraftToken token, String errorCode) {
            this.status = status;
            this.token = token;
            this.errorCode = errorCode;
        }

        boolean saved() { return status == SaveStatus.SAVED; }
    }

    private final File filesRoot;
    private final NoteStore.WriteFaultInjector faultInjector;

    ManualTextDraftStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
        Context application = context.getApplicationContext();
        Context owner = application == null ? context : application;
        this.filesRoot = owner.getFilesDir();
        this.faultInjector = NoteStore.NO_WRITE_FAULTS;
    }

    ManualTextDraftStore(File filesRoot) {
        this(filesRoot, NoteStore.NO_WRITE_FAULTS);
    }

    ManualTextDraftStore(File filesRoot, NoteStore.WriteFaultInjector faultInjector) {
        if (filesRoot == null || faultInjector == null) throw new IllegalArgumentException("storage required");
        this.filesRoot = filesRoot;
        this.faultInjector = faultInjector;
    }

    /** SHA-256 over exact, well-formed UTF-8 bytes; no normalization is applied. */
    static String sourceFingerprint(String source) {
        byte[] bytes = utf8(source);
        if (bytes == null) throw new IllegalArgumentException("source Unicode is invalid");
        return hex(sha256(bytes));
    }

    LoadResult load(String noteId) {
        synchronized (LOCK) {
            return loadLocked(noteId);
        }
    }

    /**
     * Atomically checkpoint a draft iff the currently persisted record has exactly the expected
     * token. Pass null only when no previous record exists. A stale save never overwrites newer
     * state. Call synchronously from the lifecycle owner; there are intentionally no background
     * writers that can complete after a newer session starts.
     */
    SaveResult save(Draft draft, DraftToken expectedPrevious) {
        Validation validation = validateDraft(draft);
        if (validation.error != null) {
            return new SaveResult(validation.tooLarge ? SaveStatus.TOO_LARGE : SaveStatus.INVALID,
                    null, validation.error);
        }
        final byte[] encoded;
        try {
            encoded = encode(draft);
        } catch (IOException error) {
            return new SaveResult(SaveStatus.INVALID, null, "record_encode_failed");
        }
        if (encoded.length > MAX_RECORD_BYTES) {
            return new SaveResult(SaveStatus.TOO_LARGE, null, "record_byte_limit");
        }
        synchronized (LOCK) {
            LoadResult current = loadLocked(draft.noteId);
            if (current.status == LoadStatus.INVALID) {
                return new SaveResult(SaveStatus.INVALID, null, "existing_record_invalid");
            }
            if (current.status == LoadStatus.IO_ERROR) {
                return new SaveResult(SaveStatus.IO_ERROR, null, "existing_record_unreadable");
            }
            if (expectedPrevious == null) {
                if (current.status != LoadStatus.NONE) {
                    return new SaveResult(SaveStatus.CONFLICT, current.token,
                            "previous_record_exists");
                }
            } else if (current.status != LoadStatus.READY || current.token == null
                    || !current.token.equals(expectedPrevious)) {
                return new SaveResult(SaveStatus.CONFLICT, current.token,
                        "previous_record_changed");
            }
            if (expectedPrevious != null && expectedPrevious.sessionId.equals(draft.sessionId)
                    && draft.generation <= expectedPrevious.generation) {
                return new SaveResult(SaveStatus.CONFLICT, current.token,
                        "generation_not_increasing");
            }
            try {
                File base = recordFile(draft.noteId, true);
                rejectSidecarSymlinks(base);
                NoteStore.writeAtomic(base, new String(encoded, StandardCharsets.UTF_8),
                        value -> validateStoredRecord(value, draft.noteId), faultInjector,
                        MAX_RECORD_BYTES);
                return new SaveResult(SaveStatus.SAVED, draft.token(), null);
            } catch (Exception error) {
                return new SaveResult(SaveStatus.IO_ERROR, null, "draft_storage_unavailable");
            }
        }
    }

    /**
     * Checkpoint a lifecycle draft with exact-token CAS, but avoid rewriting an already durable
     * identical payload. The generation must still advance; an idempotent result returns the
     * existing disk token without advancing its generation. Equality compares every persisted
     * payload field except generation, never a source hash alone.
     */
    SaveResult checkpointIfChanged(Draft draft, DraftToken expectedPrevious) {
        Validation validation = validateDraft(draft);
        if (validation.error != null) {
            return new SaveResult(validation.tooLarge ? SaveStatus.TOO_LARGE : SaveStatus.INVALID,
                    null, validation.error);
        }
        synchronized (LOCK) {
            LoadResult current = loadLocked(draft.noteId);
            if (current.status == LoadStatus.INVALID) {
                return new SaveResult(SaveStatus.INVALID, null, "existing_record_invalid");
            }
            if (current.status == LoadStatus.IO_ERROR) {
                return new SaveResult(SaveStatus.IO_ERROR, null, "existing_record_unreadable");
            }
            if (expectedPrevious == null) {
                if (current.status != LoadStatus.NONE) {
                    return new SaveResult(SaveStatus.CONFLICT, current.token,
                            "previous_record_exists");
                }
            } else if (current.status != LoadStatus.READY || current.token == null
                    || !current.token.equals(expectedPrevious)) {
                return new SaveResult(SaveStatus.CONFLICT, current.token,
                        "previous_record_changed");
            }
            if (expectedPrevious != null && expectedPrevious.sessionId.equals(draft.sessionId)
                    && draft.generation <= expectedPrevious.generation) {
                return new SaveResult(SaveStatus.CONFLICT, current.token,
                        "generation_not_increasing");
            }
            if (current.status == LoadStatus.READY && samePayloadExceptGeneration(draft, current.draft)) {
                // loadLocked verified the current bytes, and the exact expected token above proves
                // this is the record the caller owns. Return its disk token without encoding or
                // invoking the write transaction.
                return new SaveResult(SaveStatus.SAVED, current.token, null);
            }

            final byte[] encoded;
            try {
                encoded = encode(draft);
            } catch (IOException error) {
                return new SaveResult(SaveStatus.INVALID, null, "record_encode_failed");
            }
            if (encoded.length > MAX_RECORD_BYTES) {
                return new SaveResult(SaveStatus.TOO_LARGE, null, "record_byte_limit");
            }
            try {
                File base = recordFile(draft.noteId, true);
                rejectSidecarSymlinks(base);
                NoteStore.writeAtomic(base, new String(encoded, StandardCharsets.UTF_8),
                        value -> validateStoredRecord(value, draft.noteId), faultInjector,
                        MAX_RECORD_BYTES);
                return new SaveResult(SaveStatus.SAVED, draft.token(), null);
            } catch (Exception error) {
                return new SaveResult(SaveStatus.IO_ERROR, null, "draft_storage_unavailable");
            }
        }
    }

    private static boolean samePayloadExceptGeneration(Draft candidate, Draft stored) {
        return candidate != null && stored != null
                && candidate.noteId.equals(stored.noteId)
                && candidate.flowId.equals(stored.flowId)
                && candidate.fragmentId.equals(stored.fragmentId)
                && candidate.source.equals(stored.source)
                && candidate.format.equals(stored.format)
                && Float.compare(candidate.fontSize, stored.fontSize) == 0
                && Float.compare(candidate.lineHeight, stored.lineHeight) == 0
                && candidate.newlyInserted == stored.newlyInserted
                && candidate.baseSourceFingerprint.equals(stored.baseSourceFingerprint)
                && candidate.sessionId.equals(stored.sessionId);
    }

    /** Clear only the exact valid checkpoint that the UI showed to the user. */
    ClearStatus clearIfMatches(String noteId, DraftToken expected) {
        if (expected == null) return ClearStatus.INVALID;
        synchronized (LOCK) {
            LoadResult current = loadLocked(noteId);
            if (current.status == LoadStatus.NONE) return ClearStatus.NOT_FOUND;
            if (current.status == LoadStatus.INVALID) return ClearStatus.INVALID;
            if (current.status == LoadStatus.IO_ERROR) return ClearStatus.IO_ERROR;
            if (current.token == null || !current.token.equals(expected)) return ClearStatus.STALE;
            try {
                if (!deleteObservedRecordSet(recordFile(noteId, false))) return ClearStatus.STALE;
                return ClearStatus.CLEARED;
            } catch (IOException error) {
                return ClearStatus.IO_ERROR;
            }
        }
    }

    /** Invalid bytes are retained until the user explicitly discards the exact observed record. */
    ClearStatus clearInvalidIfMatches(String noteId, String observedRawSha256) {
        if (!isSha256(observedRawSha256)) return ClearStatus.INVALID;
        synchronized (LOCK) {
            LoadResult current = loadLocked(noteId);
            if (current.status == LoadStatus.NONE) return ClearStatus.NOT_FOUND;
            if (current.status != LoadStatus.INVALID || current.rawSha256 == null) {
                return current.status == LoadStatus.IO_ERROR ? ClearStatus.IO_ERROR : ClearStatus.INVALID;
            }
            if (!observedRawSha256.equals(current.rawSha256)) return ClearStatus.STALE;
            try {
                if (current.observedFile == null
                        || !deleteObservedRecordSet(recordFile(noteId, false))) return ClearStatus.STALE;
                return ClearStatus.CLEARED;
            } catch (IOException error) {
                return ClearStatus.IO_ERROR;
            }
        }
    }

    private LoadResult loadLocked(String noteId) {
        if (!validNoteId(noteId)) return new LoadResult(LoadStatus.INVALID, null, null,
                null, "note_id_invalid", null);
        try {
            File base = recordFile(noteId, false);
            rejectSidecarSymlinks(base);
            File backup = new File(base.getPath() + ".bak");
            File fresh = new File(base.getPath() + ".tmp");
            if (!base.exists() && !backup.exists() && !fresh.exists()) {
                return new LoadResult(LoadStatus.NONE, null, null, null, null, null);
            }
            // A present head is authoritative: never silently hide a damaged current draft by
            // falling back to an older backup. Backup/tmp recovery applies only after an
            // interrupted transaction left the head absent.
            if (base.exists()) return readCandidate(base, noteId);
            File invalidFile = null;
            String invalidHash = null;
            String invalidError = null;
            for (File candidate : new File[]{backup, fresh}) {
                if (!candidate.exists()) continue;
                byte[] raw;
                try (FileInputStream input = new FileInputStream(candidate)) {
                    raw = readBounded(input, MAX_RECORD_BYTES);
                }
                if (raw == null) {
                    if (invalidFile == null) {
                        invalidFile = candidate;
                        invalidError = "record_byte_limit";
                    }
                    continue;
                }
                String rawHash = hex(sha256(raw));
                try {
                    Draft draft = decode(raw, noteId);
                    return new LoadResult(LoadStatus.READY, draft, draft.token(), rawHash,
                            null, candidate);
                } catch (IOException | RuntimeException error) {
                    if (invalidFile == null) {
                        invalidFile = candidate;
                        invalidHash = rawHash;
                        invalidError = "record_validation_failed";
                    }
                }
            }
            return new LoadResult(LoadStatus.INVALID, null, null, invalidHash,
                    invalidError, invalidFile);
        } catch (IOException | RuntimeException error) {
            return new LoadResult(LoadStatus.IO_ERROR, null, null, null,
                    "record_read_failed", null);
        }
    }

    private File recordFile(String noteId, boolean createDirectory) throws IOException {
        if (!validNoteId(noteId)) throw new IOException("NOTE_ID_INVALID");
        if (filesRoot == null) throw new IOException("FILES_DIR_MISSING");
        // The caller-provided files root is trusted. Normalize it once so legitimate aliases
        // (for example /data/data -> /data/user/0) do not make safe children look like symlinks.
        // The draft directory and its records remain untrusted and are checked below.
        File filesCanonical = filesRoot.getCanonicalFile();
        if (!filesCanonical.exists() && createDirectory && !filesCanonical.mkdirs()) {
            throw new IOException("FILES_DIR_CREATE_FAILED");
        }
        if (filesCanonical.exists() && !filesCanonical.isDirectory()) {
            throw new IOException("FILES_DIR_UNSAFE");
        }
        File directory = new File(filesCanonical, DIRECTORY);
        if (createDirectory && !directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("DRAFT_DIR_CREATE_FAILED");
        }
        if (directory.exists() && (!directory.isDirectory()
                || !directory.getCanonicalFile().equals(directory.getAbsoluteFile()))) {
            throw new IOException("DRAFT_DIR_UNSAFE");
        }
        if (!directory.getCanonicalFile().getParentFile().equals(filesCanonical)) {
            throw new IOException("DRAFT_DIR_OUTSIDE_FILES");
        }
        String name = sha256Hex(utf8(noteId)) + ".json";
        File base = new File(directory, name);
        if (!base.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) {
            throw new IOException("DRAFT_PATH_UNSAFE");
        }
        return base;
    }

    private static void rejectSidecarSymlinks(File base) throws IOException {
        for (String suffix : new String[]{"", ".bak", ".tmp"}) {
            File file = new File(base.getPath() + suffix);
            if (file.exists() && !file.getCanonicalFile().equals(file.getAbsoluteFile())) {
                throw new IOException("DRAFT_SYMLINK_REJECTED");
            }
        }
    }

    /** Deletes only the exact artifact set observed under LOCK; a concurrently changed file wins. */
    private static boolean deleteObservedRecordSet(File base) throws IOException {
        rejectSidecarSymlinks(base);
        File[] files = recordFiles(base);
        String[] hashes = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            if (files[i].exists()) {
                hashes[i] = rawFileHash(files[i]);
                if (hashes[i] == null) throw new IOException("DRAFT_RECORD_TOO_LARGE_TO_CLEAR");
            }
        }
        for (int i = 0; i < files.length; i++) {
            if (hashes[i] == null) continue;
            if (!files[i].exists() || !hashes[i].equals(rawFileHash(files[i]))) return false;
        }
        for (int i = 0; i < files.length; i++) {
            if (hashes[i] != null && !files[i].delete()) throw new IOException("DRAFT_DELETE_FAILED");
        }
        return true;
    }

    private static File[] recordFiles(File base) {
        // Delete transaction sidecars first; the authoritative head is always last.
        return new File[]{new File(base.getPath() + ".tmp"),
                new File(base.getPath() + ".bak"), base};
    }

    private static String rawFileHash(File file) throws IOException {
        byte[] raw;
        try (FileInputStream input = new FileInputStream(file)) {
            raw = readBounded(input, MAX_RECORD_BYTES);
        }
        return raw == null ? null : hex(sha256(raw));
    }

    private static LoadResult readCandidate(File candidate, String noteId) throws IOException {
        byte[] raw;
        try (FileInputStream input = new FileInputStream(candidate)) {
            raw = readBounded(input, MAX_RECORD_BYTES);
        }
        if (raw == null) return new LoadResult(LoadStatus.INVALID, null, null,
                null, "record_byte_limit", candidate);
        String rawHash = hex(sha256(raw));
        try {
            Draft draft = decode(raw, noteId);
            return new LoadResult(LoadStatus.READY, draft, draft.token(), rawHash, null, candidate);
        } catch (IOException | RuntimeException error) {
            return new LoadResult(LoadStatus.INVALID, null, null, rawHash,
                    "record_validation_failed", candidate);
        }
    }

    private static void validateStoredRecord(String value, String noteId) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Draft draft = decode(bytes, noteId);
        if (draft == null) throw new IOException("DRAFT_VALIDATION_FAILED");
    }

    private static byte[] encode(Draft draft) throws IOException {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("schemaVersion", new LibraryBackupJson.NumberToken(Long.toString(SCHEMA_VERSION)));
        object.put("noteId", draft.noteId);
        object.put("flowId", draft.flowId);
        object.put("fragmentId", draft.fragmentId);
        object.put("source", draft.source);
        object.put("format", draft.format);
        object.put("fontSizeSp", Float.toString(draft.fontSize));
        object.put("lineHeight", Float.toString(draft.lineHeight));
        object.put("newlyInserted", draft.newlyInserted);
        object.put("baseSourceFingerprint", draft.baseSourceFingerprint);
        object.put("sessionId", draft.sessionId);
        object.put("generation", new LibraryBackupJson.NumberToken(Long.toString(draft.generation)));
        return LibraryBackupJson.encode(object);
    }

    private static Draft decode(byte[] raw, String expectedNoteId) throws IOException {
        Map<String, Object> object = LibraryBackupJson.parseCheckedObject(raw, MAX_RECORD_BYTES);
        LibraryBackupJson.exactKeys(object, "DRAFT_KEYS", RECORD_KEYS);
        if (LibraryBackupJson.integer(object.get("schemaVersion"), SCHEMA_VERSION,
                SCHEMA_VERSION, "DRAFT_SCHEMA") != SCHEMA_VERSION) {
            throw new IOException("DRAFT_SCHEMA");
        }
        String noteId = LibraryBackupJson.string(object.get("noteId"), "DRAFT_NOTE_ID");
        String flowId = LibraryBackupJson.string(object.get("flowId"), "DRAFT_FLOW_ID");
        String fragmentId = LibraryBackupJson.string(object.get("fragmentId"), "DRAFT_FRAGMENT_ID");
        String source = LibraryBackupJson.string(object.get("source"), "DRAFT_SOURCE");
        String format = LibraryBackupJson.string(object.get("format"), "DRAFT_FORMAT");
        String font = LibraryBackupJson.string(object.get("fontSizeSp"), "DRAFT_FONT");
        String leading = LibraryBackupJson.string(object.get("lineHeight"), "DRAFT_LINE_HEIGHT");
        boolean newlyInserted = LibraryBackupJson.bool(object.get("newlyInserted"), "DRAFT_INSERTED");
        String baseFingerprint = LibraryBackupJson.string(object.get("baseSourceFingerprint"), "DRAFT_BASE_HASH");
        String sessionId = LibraryBackupJson.string(object.get("sessionId"), "DRAFT_SESSION");
        long generation = LibraryBackupJson.integer(object.get("generation"), 1L,
                Long.MAX_VALUE, "DRAFT_GENERATION");
        Draft draft = new Draft(noteId, flowId, fragmentId, source, format,
                parseCanonicalFloat(font, 10f, 32f, "DRAFT_FONT"),
                parseCanonicalFloat(leading, TextFlow.MIN_LINE_HEIGHT, TextFlow.MAX_LINE_HEIGHT,
                        "DRAFT_LINE_HEIGHT"),
                newlyInserted, baseFingerprint, sessionId, generation);
        Validation validation = validateDraft(draft);
        if (validation.error != null || !expectedNoteId.equals(noteId)) {
            throw new IOException("DRAFT_RECORD_INVALID");
        }
        return draft;
    }

    private static float parseCanonicalFloat(String value, float min, float max, String error)
            throws IOException {
        final float parsed;
        try { parsed = Float.parseFloat(value); }
        catch (NumberFormatException failure) { throw new IOException(error); }
        if (!Float.isFinite(parsed) || parsed < min || parsed > max
                || !Float.toString(parsed).equals(value)) throw new IOException(error);
        return parsed;
    }

    private static final class Validation {
        final String error;
        final boolean tooLarge;
        Validation(String error, boolean tooLarge) { this.error = error; this.tooLarge = tooLarge; }
    }

    private static Validation validateDraft(Draft draft) {
        if (draft == null || !validNoteId(draft.noteId) || !validId(draft.flowId, false)
                || !validId(draft.fragmentId, true) || !wellFormedUnicode(draft.source)
                || !validFormat(draft.format) || !Float.isFinite(draft.fontSize)
                || draft.fontSize < 10f || draft.fontSize > 32f
                || !Float.isFinite(draft.lineHeight)
                || draft.lineHeight < TextFlow.MIN_LINE_HEIGHT
                || draft.lineHeight > TextFlow.MAX_LINE_HEIGHT
                || !isSha256(draft.baseSourceFingerprint) || !validUuid(draft.sessionId)
                || draft.generation <= 0) {
            return new Validation("draft_fields_invalid", false);
        }
        if (draft.source.length() > MAX_SOURCE_UTF16_UNITS) {
            return new Validation("source_utf16_limit", true);
        }
        byte[] sourceBytes = utf8(draft.source);
        if (sourceBytes == null) return new Validation("source_unicode_invalid", false);
        if (sourceBytes.length > MAX_SOURCE_UTF8_BYTES) {
            return new Validation("source_utf8_limit", true);
        }
        return new Validation(null, false);
    }

    private static boolean validNoteId(String value) {
        return validId(value, false);
    }

    private static boolean validId(String value, boolean emptyAllowed) {
        if (value == null || (!emptyAllowed && value.trim().isEmpty()) || value.length() > 256
                || !wellFormedUnicode(value)) return false;
        byte[] bytes = utf8(value);
        return bytes != null && bytes.length <= 1024;
    }

    private static boolean validFormat(String value) {
        return NoteTextBox.Format.LATEX.name().equals(value)
                || NoteTextBox.Format.MARKDOWN.name().equals(value);
    }

    private static boolean validUuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return false;
        }
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException error) { return false; }
    }

    private static boolean isSha256(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static boolean wellFormedUnicode(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static byte[] utf8(String value) {
        return value == null || !wellFormedUnicode(value)
                ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readBounded(FileInputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > maximumBytes - total) return null;
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    private static String sha256Hex(byte[] bytes) { return hex(sha256(bytes)); }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
