package com.padnote.android;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Durable, revision-bound recovery journal for edits to group-managed notes. */
final class GroupEditorDraftStore {
    static final int MAX_DRAFT_BODY_BYTES = NoteStore.MAX_NOTE_BYTES;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final String DIRECTORY = "group-editor-drafts";
    private static final String SCHEMA = "padnote-group-editor-draft-v2";
    /** In-process tombstones prevent late workers from resurrecting a cleared draft. */
    private static final Map<String, Watermark> SESSION_WATERMARKS = new HashMap<>();

    private static final class Watermark {
        final long sequence;
        final String attemptId;
        final String bodySha256;
        boolean cleared;
        Watermark(Draft draft) {
            sequence = draft.captureSequence;
            attemptId = draft.attemptId;
            bodySha256 = draft.bodySha256;
        }
    }

    static final class Draft {
        final String attemptId;
        final String editorSessionId;
        final long captureSequence;
        final String supersedesAttemptId;
        final String noteId;
        final String lineage;
        final String baseRevision;
        final String baseDigest;
        final long documentRevision;
        final long capturedAt;
        final String bodyJson;
        final String bodySha256;

        Draft(String attemptId, String editorSessionId, long captureSequence,
              String supersedesAttemptId, String noteId, String lineage, String baseRevision,
              String baseDigest, long documentRevision, long capturedAt, String bodyJson,
              String bodySha256) {
            this.attemptId = attemptId;
            this.editorSessionId = editorSessionId;
            this.captureSequence = captureSequence;
            this.supersedesAttemptId = supersedesAttemptId;
            this.noteId = noteId;
            this.lineage = lineage;
            this.baseRevision = baseRevision;
            this.baseDigest = baseDigest;
            this.documentRevision = documentRevision;
            this.capturedAt = capturedAt;
            this.bodyJson = bodyJson;
            this.bodySha256 = bodySha256;
        }

        boolean matchesBase(String expectedNoteId, String expectedLineage,
                            String expectedRevision, String expectedDigest) {
            return noteId.equals(expectedNoteId) && lineage.equals(expectedLineage)
                    && baseRevision.equals(expectedRevision)
                    && baseDigest.equals(expectedDigest);
        }

        /** True only when this full body covers the attempted capture on the same base. */
        boolean covers(Draft attempted) {
            return attempted != null && noteId.equals(attempted.noteId)
                    && editorSessionId.equals(attempted.editorSessionId)
                    && captureSequence >= attempted.captureSequence
                    && documentRevision >= attempted.documentRevision
                    && matchesBase(attempted.noteId, attempted.lineage,
                            attempted.baseRevision, attempted.baseDigest);
        }
    }

    private static final class Header {
        final String attemptId, editorSessionId, supersedesAttemptId;
        final String noteId, lineage, baseRevision, baseDigest, bodySha256;
        final long captureSequence, documentRevision, capturedAt;
        Header(String attemptId, String editorSessionId, long captureSequence,
               String supersedesAttemptId, String noteId, String lineage, String baseRevision,
               String baseDigest, String bodySha256, long documentRevision, long capturedAt) {
            this.attemptId = attemptId;
            this.editorSessionId = editorSessionId;
            this.captureSequence = captureSequence;
            this.supersedesAttemptId = supersedesAttemptId;
            this.noteId = noteId;
            this.lineage = lineage;
            this.baseRevision = baseRevision;
            this.baseDigest = baseDigest;
            this.bodySha256 = bodySha256;
            this.documentRevision = documentRevision;
            this.capturedAt = capturedAt;
        }
    }

    private GroupEditorDraftStore() {}

    static Draft create(String noteId, String lineage, String baseRevision, String baseDigest,
                        long documentRevision, String editorSessionId, long captureSequence,
                        String supersedesAttemptId, String bodyJson) throws Exception {
        BodyFacts facts = bodyFacts(bodyJson);
        Draft draft = new Draft(UUID.randomUUID().toString(), editorSessionId, captureSequence,
                supersedesAttemptId, noteId, lineage, baseRevision, baseDigest, documentRevision,
                System.currentTimeMillis(), bodyJson, facts.sha256);
        validate(draft, noteId, facts);
        return draft;
    }

    static synchronized void write(Context context, Draft draft) throws Exception {
        writeToFilesRoot(canonicalContextFilesRoot(context == null ? null : context.getFilesDir()), draft);
    }

    static synchronized void writeToFilesRoot(File filesRoot, Draft draft) throws Exception {
        validate(draft, draft.noteId);
        File directory = draftDirectory(filesRoot);
        enforceReplacementOrder(filesRoot, draft);
        advanceSessionWatermark(draft);
        File body = bodyFile(directory, draft.noteId, draft.attemptId);
        File header = headerFile(directory, draft.noteId);
        verifyPrivatePath(filesRoot, body);
        verifyPrivatePath(filesRoot, header);

        // The large full body is its own atomic file; the small header is the commit
        // pointer. A crash before the header switch leaves the previous draft readable.
        NoteStore.writeAtomic(body, draft.bodyJson,
                value -> validateBody(value, draft.noteId), NoteStore.NO_WRITE_FAULTS,
                MAX_DRAFT_BODY_BYTES);
        verifyPrivatePath(filesRoot, body);
        String headerJson = encodeHeader(draft);
        NoteStore.writeAtomic(header, headerJson,
                value -> decodeHeader(value, draft.noteId), NoteStore.NO_WRITE_FAULTS,
                MAX_HEADER_BYTES);
        verifyPrivatePath(filesRoot, header);
        cleanupOrphanBodies(filesRoot, directory, draft.noteId, draft.attemptId);
    }

    static synchronized Draft read(Context context, String noteId) throws Exception {
        return readFromFilesRoot(canonicalContextFilesRoot(context == null ? null : context.getFilesDir()), noteId);
    }

    static synchronized Draft readFromFilesRoot(File filesRoot, String noteId) throws Exception {
        File directory = draftDirectory(filesRoot);
        File headerFile = headerFile(directory, noteId);
        verifyPrivatePath(filesRoot, headerFile);
        NoteStore.recoverAtomicFile(headerFile,
                value -> decodeHeader(value, noteId));
        if (!headerFile.exists()) return null;
        String headerValue = readTextBounded(headerFile, MAX_HEADER_BYTES);
        Header header = decodeHeader(headerValue, noteId);
        File bodyFile = bodyFile(directory, noteId, header.attemptId);
        verifyPrivatePath(filesRoot, bodyFile);
        NoteStore.recoverAtomicFile(bodyFile, value -> validateBody(value, noteId));
        String body = readTextBounded(bodyFile, MAX_DRAFT_BODY_BYTES);
        Draft draft = new Draft(header.attemptId, header.editorSessionId,
                header.captureSequence, header.supersedesAttemptId, header.noteId, header.lineage,
                header.baseRevision, header.baseDigest, header.documentRevision,
                header.capturedAt, body, header.bodySha256);
        validate(draft, noteId);
        return draft;
    }

    /** Deletes only the exact draft whose CAS attempt has just committed. */
    static synchronized boolean clearIfMatches(Context context, Draft expected) throws Exception {
        return clearIfMatchesFromFilesRoot(
                canonicalContextFilesRoot(context == null ? null : context.getFilesDir()), expected);
    }

    static synchronized boolean clearIfMatchesFromFilesRoot(File filesRoot, Draft expected)
            throws Exception {
        Draft actual = readFromFilesRoot(filesRoot, expected.noteId);
        if (actual == null) return true;
        if (!sameAttempt(actual, expected)) return false;
        File directory = draftDirectory(filesRoot);
        deleteFamily(filesRoot, headerFile(directory, expected.noteId));
        deleteFamily(filesRoot, bodyFile(directory, expected.noteId, expected.attemptId));
        markSessionAttemptCleared(expected);
        cleanupOrphanBodies(filesRoot, directory, expected.noteId, null);
        return true;
    }

    /**
     * Capture sequence is assigned before work is enqueued. It remains the ordering authority
     * when the CAS and draft executors finish in either order. A new editor session may replace
     * a prior session only after the user explicitly loaded that exact draft at the same base.
     */
    private static void enforceReplacementOrder(File filesRoot, Draft candidate) throws Exception {
        Draft current = readFromFilesRoot(filesRoot, candidate.noteId);
        if (current == null) return;
        if (current.attemptId.equals(candidate.attemptId)) {
            if (!sameAttempt(current, candidate))
                throw new IOException("GROUP_DRAFT_ATTEMPT_CONTENT_MISMATCH");
            return;
        }
        if (current.editorSessionId.equals(candidate.editorSessionId)) {
            if (candidate.captureSequence <= current.captureSequence)
                throw new IOException("GROUP_DRAFT_CAPTURE_ORDER_STALE");
            return;
        }
        if (!current.attemptId.equals(candidate.supersedesAttemptId)
                || !candidate.matchesBase(current.noteId, current.lineage,
                        current.baseRevision, current.baseDigest))
            throw new IOException("GROUP_DRAFT_REPLACEMENT_NOT_ACCEPTED");
    }

    private static void advanceSessionWatermark(Draft candidate) throws IOException {
        String key = candidate.noteId + "|" + candidate.editorSessionId;
        Watermark previous = SESSION_WATERMARKS.get(key);
        if (previous != null && candidate.captureSequence < previous.sequence)
            throw new IOException("GROUP_DRAFT_CAPTURE_ORDER_STALE");
        if (previous != null && candidate.captureSequence == previous.sequence) {
            if (candidate.attemptId.equals(previous.attemptId)
                    && candidate.bodySha256.equals(previous.bodySha256)
                    && !previous.cleared) return;
            throw new IOException("GROUP_DRAFT_CAPTURE_SEQUENCE_REUSED");
        }
        SESSION_WATERMARKS.put(key, new Watermark(candidate));
    }

    private static void markSessionAttemptCleared(Draft expected) {
        String key = expected.noteId + "|" + expected.editorSessionId;
        Watermark current = SESSION_WATERMARKS.get(key);
        if (current != null && current.sequence == expected.captureSequence
                && current.attemptId.equals(expected.attemptId)
                && current.bodySha256.equals(expected.bodySha256)) current.cleared = true;
    }

    private static boolean canonicalUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (Exception invalid) { return false; }
    }

    static void validateForExport(Draft draft) throws Exception {
        if (draft == null) throw new IOException("GROUP_DRAFT_MISSING");
        validate(draft, draft.noteId);
    }

    private static void validate(Draft draft, String expectedNoteId) throws Exception {
        validate(draft, expectedNoteId, bodyFacts(draft == null ? null : draft.bodyJson));
    }

    private static void validate(Draft draft, String expectedNoteId, BodyFacts facts)
            throws Exception {
        if (draft == null || draft.noteId == null || !draft.noteId.equals(expectedNoteId)
                || !draft.noteId.matches("[A-Za-z0-9_-]{1,160}"))
            throw new IOException("GROUP_DRAFT_NOTE_ID_INVALID");
        try {
            if (!UUID.fromString(draft.attemptId).toString().equals(draft.attemptId))
                throw new IllegalArgumentException("noncanonical");
        } catch (Exception invalid) {
            throw new IOException("GROUP_DRAFT_ATTEMPT_INVALID", invalid);
        }
        if (draft.lineage == null || !draft.lineage.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || emptyOrLong(draft.baseRevision, 256) || !hexDigest(draft.baseDigest)
                || draft.documentRevision < 0 || draft.capturedAt <= 0 || facts == null
                || draft.captureSequence <= 0 || !canonicalUuid(draft.editorSessionId)
                || (draft.supersedesAttemptId != null
                        && !canonicalUuid(draft.supersedesAttemptId))
                || facts.byteCount > MAX_DRAFT_BODY_BYTES || !hexDigest(draft.bodySha256)
                || !facts.sha256.equals(draft.bodySha256))
            throw new IOException("GROUP_DRAFT_ENVELOPE_INVALID");
        validateBodyDocument(draft.bodyJson, expectedNoteId);
    }

    private static void validateBody(String body, String expectedNoteId) throws Exception {
        BodyFacts facts = bodyFacts(body);
        if (body == null || facts == null || facts.byteCount > MAX_DRAFT_BODY_BYTES)
            throw new IOException("GROUP_DRAFT_BODY_TOO_LARGE");
        validateBodyDocument(body, expectedNoteId);
    }

    private static void validateBodyDocument(String body, String expectedNoteId) throws Exception {
        JSONObject document = new JSONObject(body);
        NoteStore.validateDocument(document);
        if (!expectedNoteId.equals(document.optString("id", "")))
            throw new IOException("GROUP_DRAFT_BODY_ID_MISMATCH");
    }

    private static String encodeHeader(Draft draft) throws Exception {
        JSONObject value = new JSONObject();
        value.put("schema", SCHEMA);
        value.put("attemptId", draft.attemptId);
        value.put("editorSessionId", draft.editorSessionId);
        value.put("captureSequence", draft.captureSequence);
        value.put("supersedesAttemptId", draft.supersedesAttemptId == null
                ? "" : draft.supersedesAttemptId);
        value.put("noteId", draft.noteId);
        value.put("lineage", draft.lineage);
        value.put("baseRevision", draft.baseRevision);
        value.put("baseDigest", draft.baseDigest);
        value.put("documentRevision", draft.documentRevision);
        value.put("capturedAt", draft.capturedAt);
        value.put("bodySha256", draft.bodySha256);
        value.put("bodyFile", bodyFileName(draft.noteId, draft.attemptId));
        return value.toString();
    }

    private static Header decodeHeader(String encoded, String expectedNoteId) throws Exception {
        if (encoded == null || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_HEADER_BYTES)
            throw new IOException("GROUP_DRAFT_HEADER_TOO_LARGE");
        JSONObject value = new JSONObject(encoded);
        if (value.length() != 13 || !SCHEMA.equals(value.optString("schema", "")))
            throw new IOException("GROUP_DRAFT_SCHEMA_INVALID");
        String supersedes = value.getString("supersedesAttemptId");
        Header header = new Header(value.getString("attemptId"),
                value.getString("editorSessionId"), value.getLong("captureSequence"),
                supersedes.isEmpty() ? null : supersedes, value.getString("noteId"),
                value.getString("lineage"), value.getString("baseRevision"),
                value.getString("baseDigest"), value.getString("bodySha256"),
                value.getLong("documentRevision"), value.getLong("capturedAt"));
        if (header.noteId == null || !header.noteId.equals(expectedNoteId)
                || !header.noteId.matches("[A-Za-z0-9_-]{1,160}"))
            throw new IOException("GROUP_DRAFT_NOTE_ID_INVALID");
        try {
            if (!UUID.fromString(header.attemptId).toString().equals(header.attemptId))
                throw new IllegalArgumentException("noncanonical");
        } catch (Exception invalid) {
            throw new IOException("GROUP_DRAFT_ATTEMPT_INVALID", invalid);
        }
        if (header.lineage == null || !header.lineage.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || emptyOrLong(header.baseRevision, 256) || !hexDigest(header.baseDigest)
                || !hexDigest(header.bodySha256) || header.documentRevision < 0
                || header.captureSequence <= 0 || !canonicalUuid(header.editorSessionId)
                || (header.supersedesAttemptId != null
                        && !canonicalUuid(header.supersedesAttemptId))
                || header.capturedAt <= 0
                || !bodyFileName(header.noteId, header.attemptId)
                        .equals(value.optString("bodyFile", "")))
            throw new IOException("GROUP_DRAFT_HEADER_INVALID");
        return header;
    }

    /**
     * Context.getFilesDir() is the trusted starting point; Android may expose it through a
     * platform alias whose canonical spelling differs. Use the physical spelling as the
     * containment anchor, then keep the independent no-redirect checks for our managed child
     * directory and every draft leaf.
     */
    static File canonicalContextFilesRoot(File contextFilesRoot) throws IOException {
        if (contextFilesRoot == null || !contextFilesRoot.isDirectory())
            throw new IOException("GROUP_DRAFT_CONTEXT_FILES_ROOT_INVALID");
        File canonical;
        try { canonical = contextFilesRoot.getCanonicalFile(); }
        catch (IOException invalid) {
            throw new IOException("GROUP_DRAFT_CONTEXT_FILES_ROOT_INVALID", invalid);
        }
        if (!canonical.isAbsolute() || !canonical.isDirectory())
            throw new IOException("GROUP_DRAFT_CONTEXT_FILES_ROOT_INVALID");
        return canonical;
    }

    private static File draftDirectory(File filesRoot) throws Exception {
        File directory = new File(filesRoot, DIRECTORY);
        if (!directory.exists() && !directory.mkdirs())
            throw new IOException("GROUP_DRAFT_DIRECTORY_CREATE_FAILED");
        File canonicalRoot = filesRoot.getCanonicalFile();
        if (!directory.getCanonicalFile().getParentFile().equals(canonicalRoot)
                || !directory.getCanonicalFile().equals(directory.getAbsoluteFile()))
            throw new IOException("GROUP_DRAFT_DIRECTORY_UNSAFE");
        return directory;
    }

    private static File headerFile(File directory, String noteId) throws Exception {
        validateNoteId(noteId);
        return new File(directory, sha256(noteId.getBytes(StandardCharsets.UTF_8)) + ".json");
    }

    private static File bodyFile(File directory, String noteId, String attemptId) throws Exception {
        validateNoteId(noteId);
        if (attemptId == null || !UUID.fromString(attemptId).toString().equals(attemptId))
            throw new IOException("GROUP_DRAFT_ATTEMPT_INVALID");
        return new File(directory, bodyFileName(noteId, attemptId));
    }

    private static String bodyFileName(String noteId, String attemptId) throws Exception {
        validateNoteId(noteId);
        return sha256(noteId.getBytes(StandardCharsets.UTF_8)) + "." + attemptId + ".body.json";
    }

    private static void validateNoteId(String noteId) throws IOException {
        if (noteId == null || !noteId.matches("[A-Za-z0-9_-]{1,160}"))
            throw new IOException("GROUP_DRAFT_NOTE_ID_INVALID");
    }

    private static void verifyPrivatePath(File filesRoot, File file) throws Exception {
        File directory = file.getParentFile();
        if (directory == null || !directory.getCanonicalFile().equals(directory.getAbsoluteFile())
                || !directory.getCanonicalFile().getParentFile().equals(filesRoot.getCanonicalFile())
                || !file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))
            throw new IOException("GROUP_DRAFT_PATH_UNSAFE");
        verifyNoRedirect(file);
        verifyNoRedirect(new File(directory, file.getName() + ".tmp"));
        verifyNoRedirect(new File(directory, file.getName() + ".bak"));
    }

    private static void verifyNoRedirect(File file) throws Exception {
        if (file.exists() && !file.getCanonicalFile().equals(file.getAbsoluteFile()))
            throw new IOException("GROUP_DRAFT_FILE_REDIRECTED");
    }

    private static void deleteFamily(File filesRoot, File file) throws Exception {
        verifyPrivatePath(filesRoot, file);
        remove(file);
        remove(new File(file.getParentFile(), file.getName() + ".tmp"));
        remove(new File(file.getParentFile(), file.getName() + ".bak"));
    }

    private static void remove(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("GROUP_DRAFT_CLEAR_FAILED");
    }

    private static void cleanupOrphanBodies(File filesRoot, File directory, String noteId,
                                           String keepAttempt) throws Exception {
        String prefix = sha256(noteId.getBytes(StandardCharsets.UTF_8)) + ".";
        String backupAttempt = null;
        File headerBackup = new File(directory,
                headerFile(directory, noteId).getName() + ".bak");
        if (headerBackup.exists()) {
            verifyPrivatePath(filesRoot, headerFile(directory, noteId));
            try {
                Header previous = decodeHeader(readTextBounded(headerBackup, MAX_HEADER_BYTES), noteId);
                backupAttempt = previous.attemptId;
            } catch (Exception invalidBackup) {
                // A malformed backup is not used as draft authority.
            }
        }
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("GROUP_DRAFT_DIRECTORY_READ_FAILED");
        for (File child : children) {
            String name = child.getName();
            if (!name.startsWith(prefix) || !name.endsWith(".body.json")) continue;
            String attempt = name.substring(prefix.length(), name.length() - ".body.json".length());
            try {
                if (!UUID.fromString(attempt).toString().equals(attempt)
                        || attempt.equals(keepAttempt) || attempt.equals(backupAttempt)) continue;
            } catch (Exception malformed) {
                continue;
            }
            deleteFamily(filesRoot, child);
        }
    }

    private static String readTextBounded(File file, int maximumBytes) throws Exception {
        byte[] bytes = readBounded(file, maximumBytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception invalidUtf8) {
            throw new IOException("GROUP_DRAFT_UTF8_INVALID", invalidUtf8);
        }
    }

    private static byte[] readBounded(File file, int maximum) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            for (int count; (count = input.read(buffer)) != -1;) {
                total += count;
                if (total > maximum) throw new IOException("GROUP_DRAFT_TOO_LARGE");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static final class BodyFacts {
        final String sha256;
        final int byteCount;
        BodyFacts(String sha256, int byteCount) { this.sha256 = sha256; this.byteCount = byteCount; }
    }

    /** Hash UTF-8 without allocating another body-sized byte array on the UI thread. */
    private static BodyFacts bodyFacts(String value) throws Exception {
        if (value == null) return null;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer characters = CharBuffer.wrap(value);
        ByteBuffer bytes = ByteBuffer.allocate(8192);
        long total = 0;
        while (true) {
            CoderResult result = encoder.encode(characters, bytes, true);
            bytes.flip();
            total += bytes.remaining();
            digest.update(bytes);
            bytes.clear();
            if (total > Integer.MAX_VALUE) throw new IOException("GROUP_DRAFT_BODY_TOO_LARGE");
            if (result.isError()) result.throwException();
            if (result.isUnderflow()) break;
        }
        while (true) {
            CoderResult result = encoder.flush(bytes);
            bytes.flip();
            total += bytes.remaining();
            digest.update(bytes);
            bytes.clear();
            if (total > Integer.MAX_VALUE) throw new IOException("GROUP_DRAFT_BODY_TOO_LARGE");
            if (result.isError()) result.throwException();
            if (result.isUnderflow()) break;
        }
        byte[] hash = digest.digest();
        StringBuilder encoded = new StringBuilder(64);
        for (byte item : hash)
            encoded.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return new BodyFacts(encoded.toString(), (int) total);
    }

    private static boolean sameAttempt(Draft first, Draft second) {
        return first.attemptId.equals(second.attemptId)
                && first.editorSessionId.equals(second.editorSessionId)
                && first.captureSequence == second.captureSequence
                && java.util.Objects.equals(first.supersedesAttemptId, second.supersedesAttemptId)
                && first.noteId.equals(second.noteId)
                && first.lineage.equals(second.lineage)
                && first.baseRevision.equals(second.baseRevision)
                && first.baseDigest.equals(second.baseDigest)
                && first.documentRevision == second.documentRevision
                && first.capturedAt == second.capturedAt
                && first.bodySha256.equals(second.bodySha256);
    }

    private static boolean emptyOrLong(String value, int maximum) {
        return value == null || value.isEmpty() || value.length() > maximum;
    }

    private static boolean hexDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(64);
        for (byte item : digest)
            result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }
}
