package com.padnote.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Durable, per-page checkpoints for whole-note digitization. */
final class DigitizationStore {
    enum State { PENDING, READY, COMPLETED, CANCELLED, FAILED }

    enum WriteStage { TEMP_SYNCED, OLD_VERSION_BACKED_UP, NEW_VERSION_PROMOTED }

    interface WriteFaultInjector { void after(WriteStage stage) throws Exception; }

    static final WriteFaultInjector NO_WRITE_FAULTS = stage -> { };
    static final int MAX_RUN_BYTES = 8 * 1024 * 1024;
    static final int MAX_PAGE_BYTES = 256 * 1024;
    static final int MAX_PDF_BYTES = 200 * 1024 * 1024;
    private static final Object GLOBAL_LOCK = new Object();

    static final class Source {
        final String noteId;
        final String sourceFingerprint;
        final String pdfSha256;
        final int totalPages;
        final String profileId;
        final String endpointDisplay;
        final String model;
        final String recipientIdentitySha256;

        private Source(String noteId, String sourceFingerprint, String pdfSha256,
                       int totalPages, String profileId, String endpointDisplay,
                       String model, String recipientIdentitySha256) {
            this.noteId = noteId;
            this.sourceFingerprint = sourceFingerprint;
            this.pdfSha256 = pdfSha256;
            this.totalPages = totalPages;
            this.profileId = profileId;
            this.endpointDisplay = endpointDisplay;
            this.model = model;
            this.recipientIdentitySha256 = recipientIdentitySha256;
        }

        static Source from(JSONObject document, String pdfSha256, int totalPages,
                           String profileId, String endpoint, String model) throws Exception {
            if (document == null) throw new IllegalArgumentException("缺少笔记快照");
            NoteStore.validateDocument(document);
            String noteId = requireIdentifier(document.optString("id"), "笔记 ID");
            if (totalPages < 1 || totalPages > 500 ||
                    document.optInt("pageCount", 1) != totalPages) {
                throw new IllegalArgumentException("数字化页数与笔记快照不一致");
            }
            String pdf = pdfSha256 == null ? "" : pdfSha256.trim().toLowerCase(Locale.ROOT);
            if (!pdf.isEmpty() && !pdf.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("PDF 指纹无效");
            }
            boolean hasPdf = document.optInt("pdfPageCount", 0) > 0;
            if (hasPdf != !pdf.isEmpty()) {
                throw new IllegalArgumentException("PDF 指纹与笔记原文类型不一致");
            }
            String cleanProfile = requireText(profileId, "模型档案 ID", 160);
            String cleanModel = requireText(model, "模型名称", 240);
            String fullEndpoint = requireText(endpoint, "模型端点", 2048);
            URI uri = new URI(fullEndpoint);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("模型端点无效");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException("模型端点不能包含用户名或密码");
            }
            String display = uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                    + uri.getHost().toLowerCase(Locale.ROOT)
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                    + (uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    ? "/" : uri.getRawPath());
            return new Source(noteId, canonicalSourceFingerprint(document), pdf, totalPages,
                    cleanProfile, display, cleanModel,
                    sha256(cleanProfile + "\0" + fullEndpoint + "\0" + cleanModel));
        }
    }

    static final class Snapshot {
        final String runId;
        final String noteId;
        final String title;
        final int totalPages;
        final List<String> pages;
        final State state;
        final long revision;
        final int attempt;
        /** Page whose provider outcome may be unknown; -1 means no unresolved request. */
        final int inFlightPage;
        final long sourceUpdatedAt;
        final String sourceFingerprint;
        final String pdfSha256;
        final String profileId;
        final String endpointDisplay;
        final String model;
        final String recipientIdentitySha256;
        final long createdAt;
        final long updatedAt;
        final String error;
        final String vaultFileName;

        Snapshot(String runId, String noteId, String title, int totalPages,
                 List<String> pages, State state, long revision, int attempt, int inFlightPage,
                 long sourceUpdatedAt, String sourceFingerprint, String pdfSha256,
                 String profileId, String endpointDisplay, String model,
                 String recipientIdentitySha256, long createdAt, long updatedAt,
                 String error, String vaultFileName) {
            this.runId = runId;
            this.noteId = noteId;
            this.title = title;
            this.totalPages = totalPages;
            this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
            this.state = state;
            this.revision = revision;
            this.attempt = attempt;
            this.inFlightPage = inFlightPage;
            this.sourceUpdatedAt = sourceUpdatedAt;
            this.sourceFingerprint = sourceFingerprint;
            this.pdfSha256 = pdfSha256;
            this.profileId = profileId;
            this.endpointDisplay = endpointDisplay;
            this.model = model;
            this.recipientIdentitySha256 = recipientIdentitySha256;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.error = error;
            this.vaultFileName = vaultFileName;
        }

        boolean matches(Source source) {
            return source != null && noteId.equals(source.noteId)
                    && totalPages == source.totalPages
                    && sourceFingerprint.equals(source.sourceFingerprint)
                    && pdfSha256.equals(source.pdfSha256)
                    && recipientIdentitySha256.equals(source.recipientIdentitySha256);
        }

        int completedCount() {
            int count = 0;
            for (String page : pages) if (page != null) count++;
            return count;
        }

        int nextPendingPage() {
            for (int index = 0; index < pages.size(); index++) {
                if (pages.get(index) == null) return index;
            }
            return -1;
        }

        String partialMarkdown() {
            StringBuilder value = new StringBuilder("# ").append(title).append("\n\n")
                    .append("> 数字化草稿：已完成 ").append(completedCount()).append("/")
                    .append(totalPages).append(" 页");
            if (state == State.READY) value.append("；页面已完成，尚未存入知识库");
            value.append("\n\n");
            for (int index = 0; index < pages.size(); index++) {
                String page = pages.get(index);
                value.append("## 第 ").append(index + 1).append(" 页\n\n")
                        .append(page == null && index == inFlightPage
                                ? "【本页请求结果未知；重试可能再次计费】"
                                : page == null ? "【待数字化】" : page)
                        .append("\n\n");
            }
            return value.toString().trim();
        }
    }

    /** Exclusive execution ownership for one batch. Callers must hold it until the worker exits. */
    static final class Lease implements AutoCloseable {
        private final RandomAccessFile owner;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private Lease(RandomAccessFile owner, FileChannel channel, FileLock lock) {
            this.owner = owner; this.channel = channel; this.lock = lock;
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            try { lock.release(); } catch (Exception ignored) { }
            try { channel.close(); } catch (Exception ignored) { }
            try { owner.close(); } catch (Exception ignored) { }
        }
    }

    static final class CorruptEntry {
        final String fileName;
        final long updatedAt;
        final String message;

        CorruptEntry(String fileName, long updatedAt, String message) {
            this.fileName = fileName;
            this.updatedAt = updatedAt;
            this.message = message;
        }
    }

    private final File directory;
    private final WriteFaultInjector faults;

    DigitizationStore(Context context) {
        this(new File(context.getFilesDir(), "digitization-checkpoints"), NO_WRITE_FAULTS);
    }

    DigitizationStore(File directory) {
        this(directory, NO_WRITE_FAULTS);
    }

    DigitizationStore(File directory, WriteFaultInjector faults) {
        this.directory = directory;
        this.faults = faults == null ? NO_WRITE_FAULTS : faults;
    }

    synchronized Snapshot create(Source source, String title, long sourceUpdatedAt)
            throws Exception {
        synchronized (GLOBAL_LOCK) {
            if (source == null) throw new IllegalArgumentException("缺少数字化来源");
            long now = System.currentTimeMillis();
            List<String> pages = new ArrayList<>();
            for (int index = 0; index < source.totalPages; index++) pages.add(null);
            Snapshot snapshot = new Snapshot("digitize-" + UUID.randomUUID().toString(),
                    source.noteId, safeTitle(title), source.totalPages, pages, State.PENDING,
                    1, 0, -1, Math.max(0, sourceUpdatedAt), source.sourceFingerprint,
                    source.pdfSha256, source.profileId, source.endpointDisplay, source.model,
                    source.recipientIdentitySha256, now, now, "", "");
            persist(snapshot);
            return snapshot;
        }
    }

    synchronized Snapshot load(String runId) throws Exception {
        synchronized (GLOBAL_LOCK) {
            validateRunId(runId);
            File file = runFile(runId);
            recover(file);
            if (!file.isFile()) throw new IllegalStateException("数字化检查点不存在");
            return requireRunId(readBounded(file, MAX_RUN_BYTES), runId);
        }
    }

    synchronized List<Snapshot> listForNote(String noteId) {
        synchronized (GLOBAL_LOCK) {
            try {
                requireIdentifier(noteId, "笔记 ID");
            } catch (Exception invalid) {
                return Collections.emptyList();
            }
            List<Snapshot> result = new ArrayList<>();
            for (File file : runTargets()) {
                try {
                    Snapshot snapshot = load(runIdFromFile(file));
                    if (snapshot.noteId.equals(noteId)) result.add(snapshot);
                } catch (Exception ignored) {
                    // listCorrupt() exposes the retained entry to the UI.
                }
            }
            result.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
            return result;
        }
    }

    synchronized Snapshot findLatest(String noteId) {
        synchronized (GLOBAL_LOCK) {
            List<Snapshot> values = listForNote(noteId);
            return values.isEmpty() ? null : values.get(0);
        }
    }

    synchronized List<CorruptEntry> listCorrupt() {
        synchronized (GLOBAL_LOCK) {
            List<CorruptEntry> result = new ArrayList<>();
            for (File file : runTargets()) {
                try {
                    load(runIdFromFile(file));
                } catch (Exception error) {
                    result.add(new CorruptEntry(file.getName(), file.lastModified(),
                            safeError(error)));
                }
            }
            File[] retained = directory.listFiles((ignored, name) -> name.contains(".corrupt-"));
            if (retained != null) for (File file : retained) {
                result.add(new CorruptEntry(file.getName(), file.lastModified(),
                        "已保留损坏检查点供恢复"));
            }
            result.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
            return result;
        }
    }

    synchronized Snapshot startAttempt(Snapshot snapshot) throws Exception {
        synchronized (GLOBAL_LOCK) {
            Snapshot current = requireCurrent(snapshot);
            if (current.state == State.COMPLETED) {
                throw new IllegalStateException("已发布的数字化任务不能继续执行");
            }
            State state = current.completedCount() == current.totalPages
                    ? State.READY : State.PENDING;
            return update(current, state, current.pages, current.attempt + 1,
                    current.inFlightPage, "",
                    current.vaultFileName);
        }
    }

    synchronized Snapshot markPageStarted(Snapshot snapshot, int pageIndex) throws Exception {
        synchronized (GLOBAL_LOCK) {
            Snapshot current = requireCurrent(snapshot);
            if (current.state != State.PENDING
                    || pageIndex < 0 || pageIndex >= current.totalPages
                    || current.pages.get(pageIndex) != null) {
                throw new IllegalStateException("当前数字化任务不能开始该页");
            }
            if (current.inFlightPage >= 0 && current.inFlightPage != pageIndex) {
                throw new IllegalStateException("另一页仍有未确认的模型请求");
            }
            return update(current, State.PENDING, current.pages, current.attempt,
                    pageIndex, current.error, current.vaultFileName);
        }
    }

    synchronized Snapshot savePage(Snapshot snapshot, int pageIndex, String text)
            throws Exception {
        synchronized (GLOBAL_LOCK) {
            Snapshot current = requireCurrent(snapshot);
            if (current.state != State.PENDING) {
                throw new IllegalStateException("当前数字化任务不接受页面结果：" + current.state);
            }
            if (pageIndex < 0 || pageIndex >= current.totalPages) {
                throw new IllegalArgumentException("数字化页码越界");
            }
            String cleaned = text == null ? "" : text.trim();
            if (cleaned.isEmpty()) throw new IllegalArgumentException("空白页结果不能作为已完成检查点");
            if (!withinBytes(cleaned, MAX_PAGE_BYTES)) {
                throw new IllegalArgumentException("单页数字化结果超过 256 KB");
            }
            List<String> pages = new ArrayList<>(current.pages);
            String previous = pages.get(pageIndex);
            if (previous != null && !previous.equals(cleaned)) {
                throw new IllegalStateException("该页已有不同结果，不能混入同一检查点");
            }
            pages.set(pageIndex, cleaned);
            State state = pages.contains(null) ? State.PENDING : State.READY;
            int inFlight = current.inFlightPage == pageIndex ? -1 : current.inFlightPage;
            return update(current, state, pages, current.attempt, inFlight, "",
                    current.vaultFileName);
        }
    }

    synchronized Snapshot cancel(Snapshot snapshot) throws Exception {
        synchronized (GLOBAL_LOCK) {
            Snapshot current = requireCurrent(snapshot);
            if (current.state == State.COMPLETED) return current;
            return update(current, State.CANCELLED, current.pages, current.attempt,
                    current.inFlightPage, "", "");
        }
    }

    synchronized Snapshot fail(Snapshot snapshot, String message) throws Exception {
        synchronized (GLOBAL_LOCK) {
            Snapshot current = requireCurrent(snapshot);
            if (current.state == State.COMPLETED) return current;
            State next = current.completedCount() == current.totalPages
                    ? State.READY : State.FAILED;
            return update(current, next, current.pages, current.attempt,
                    current.inFlightPage,
                    safeMessage(message), current.vaultFileName);
        }
    }

    synchronized Snapshot markPublished(Snapshot snapshot) throws Exception {
        return markPublished(snapshot, snapshot.vaultFileName);
    }

    synchronized Snapshot markPublished(Snapshot snapshot, String vaultFileName)
            throws Exception {
        synchronized (GLOBAL_LOCK) {
            Snapshot current = requireCurrent(snapshot);
            if (current.state == State.COMPLETED) return current;
            if (current.completedCount() != current.totalPages) {
                throw new IllegalStateException("页面尚未全部完成，不能发布");
            }
            String fileName = vaultFileName == null ? "" : vaultFileName.trim();
            if (fileName.isEmpty() || !safeFileName(fileName)) {
                throw new IllegalArgumentException("知识库文件名无效");
            }
            return update(current, State.COMPLETED, current.pages, current.attempt, -1,
                    "", fileName);
        }
    }

    private Snapshot requireCurrent(Snapshot supplied) throws Exception {
        if (supplied == null) throw new IllegalArgumentException("缺少数字化检查点");
        Snapshot current = load(supplied.runId);
        if (current.revision != supplied.revision) {
            throw new IllegalStateException("数字化检查点已被其他操作更新，请刷新后重试");
        }
        if (!current.noteId.equals(supplied.noteId)
                || !current.sourceFingerprint.equals(supplied.sourceFingerprint)
                || !current.recipientIdentitySha256.equals(supplied.recipientIdentitySha256)) {
            throw new IllegalStateException("数字化检查点身份不一致");
        }
        return current;
    }

    private Snapshot update(Snapshot current, State state, List<String> pages,
                            int attempt, int inFlightPage, String error,
                            String vaultFileName) throws Exception {
        Snapshot next = new Snapshot(current.runId, current.noteId, current.title,
                current.totalPages, pages, state, current.revision + 1, attempt,
                inFlightPage, current.sourceUpdatedAt, current.sourceFingerprint, current.pdfSha256,
                current.profileId, current.endpointDisplay, current.model,
                current.recipientIdentitySha256, current.createdAt, System.currentTimeMillis(),
                error, vaultFileName);
        persist(next);
        return next;
    }

    private void persist(Snapshot snapshot) throws Exception {
        ensureDirectory();
        JSONObject value = encode(snapshot);
        String serialized = value.toString();
        validateStored(serialized);
        writeAtomic(runFile(snapshot.runId), serialized);
    }

    private JSONObject encode(Snapshot snapshot) throws Exception {
        JSONArray pages = new JSONArray();
        for (String page : snapshot.pages) pages.put(page == null ? JSONObject.NULL : page);
        return new JSONObject().put("schemaVersion", 1).put("runId", snapshot.runId)
                .put("noteId", snapshot.noteId).put("title", snapshot.title)
                .put("state", snapshot.state.name()).put("revision", snapshot.revision)
                .put("attempt", snapshot.attempt).put("createdAt", snapshot.createdAt)
                .put("inFlightPage", snapshot.inFlightPage)
                .put("updatedAt", snapshot.updatedAt).put("sourceUpdatedAt", snapshot.sourceUpdatedAt)
                .put("sourceFingerprint", snapshot.sourceFingerprint)
                .put("pdfSha256", snapshot.pdfSha256).put("totalPages", snapshot.totalPages)
                .put("profileId", snapshot.profileId).put("endpoint", snapshot.endpointDisplay)
                .put("model", snapshot.model)
                .put("recipientIdentitySha256", snapshot.recipientIdentitySha256)
                .put("pages", pages).put("error", snapshot.error)
                .put("vaultFileName", snapshot.vaultFileName);
    }

    private static Snapshot decodeStatic(String value) throws Exception {
        JSONObject json = new JSONObject(value);
        validateJson(json);
        JSONArray pageValues = json.getJSONArray("pages");
        List<String> pages = new ArrayList<>();
        for (int index = 0; index < pageValues.length(); index++) {
            pages.add(pageValues.isNull(index) ? null : pageValues.getString(index));
        }
        return new Snapshot(json.getString("runId"), json.getString("noteId"),
                json.getString("title"), json.getInt("totalPages"), pages,
                State.valueOf(json.getString("state")), json.getLong("revision"),
                json.getInt("attempt"), json.optInt("inFlightPage", -1),
                json.getLong("sourceUpdatedAt"),
                json.getString("sourceFingerprint"), json.getString("pdfSha256"),
                json.getString("profileId"), json.getString("endpoint"),
                json.getString("model"), json.getString("recipientIdentitySha256"),
                json.getLong("createdAt"), json.getLong("updatedAt"),
                json.optString("error"), json.optString("vaultFileName"));
    }

    private static void validateStored(String value) throws Exception {
        if (!withinBytes(value, MAX_RUN_BYTES)) {
            throw new IllegalArgumentException("数字化检查点超过 8 MB");
        }
        decodeStatic(value);
    }

    private static void validateJson(JSONObject json) throws Exception {
        if (json.optInt("schemaVersion", 0) != 1) throw new IllegalArgumentException("检查点版本无效");
        validateRunId(json.getString("runId"));
        requireIdentifier(json.getString("noteId"), "笔记 ID");
        requireText(json.getString("title"), "标题", 200);
        int total = json.getInt("totalPages");
        JSONArray pages = json.getJSONArray("pages");
        if (total < 1 || total > 500 || pages.length() != total) {
            throw new IllegalArgumentException("检查点页数无效");
        }
        State state = State.valueOf(json.getString("state"));
        if (json.getLong("revision") < 1 || json.getInt("attempt") < 0) {
            throw new IllegalArgumentException("检查点修订无效");
        }
        int inFlightPage = json.optInt("inFlightPage", -1);
        if (inFlightPage < -1 || inFlightPage >= total
                || inFlightPage >= 0 && !pages.isNull(inFlightPage)) {
            throw new IllegalArgumentException("检查点未确认页无效");
        }
        requireHash(json.getString("sourceFingerprint"), "来源指纹");
        String pdf = json.getString("pdfSha256");
        if (!pdf.isEmpty()) requireHash(pdf, "PDF 指纹");
        requireHash(json.getString("recipientIdentitySha256"), "接收者指纹");
        requireText(json.getString("profileId"), "模型档案 ID", 160);
        requireText(json.getString("endpoint"), "模型端点", 2048);
        requireText(json.getString("model"), "模型名称", 240);
        int complete = 0;
        for (int index = 0; index < pages.length(); index++) {
            if (pages.isNull(index)) continue;
            String page = pages.getString(index);
            if (page.trim().isEmpty() || !withinBytes(page, MAX_PAGE_BYTES)) {
                throw new IllegalArgumentException("页面检查点内容无效");
            }
            complete++;
        }
        if ((state == State.READY || state == State.COMPLETED) && complete != total) {
            throw new IllegalArgumentException("就绪检查点缺少页面");
        }
        if (state == State.COMPLETED && json.optString("vaultFileName").isEmpty()) {
            throw new IllegalArgumentException("已发布检查点缺少知识库文件");
        }
        if ((state == State.READY || state == State.COMPLETED) && inFlightPage != -1) {
            throw new IllegalArgumentException("已完成检查点仍有未确认请求");
        }
    }

    Lease acquireLease(String runId) throws Exception {
        validateRunId(runId);
        synchronized (GLOBAL_LOCK) { ensureDirectory(); }
        File file = new File(directory, ".lease-" + runId + ".lock");
        RandomAccessFile owner = new RandomAccessFile(file, "rw");
        FileChannel channel = owner.getChannel();
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) return new Lease(owner, channel, lock);
        } catch (OverlappingFileLockException busy) {
            // Another Store instance in this process owns this batch.
        } catch (Exception error) {
            try { channel.close(); } catch (Exception ignored) { }
            try { owner.close(); } catch (Exception ignored) { }
            throw error;
        }
        try { channel.close(); } catch (Exception ignored) { }
        try { owner.close(); } catch (Exception ignored) { }
        return null;
    }

    static String canonicalSourceFingerprint(JSONObject document) throws Exception {
        if (document == null) throw new IllegalArgumentException("缺少笔记快照");
        return sha256(canonical(document));
    }

    static String sha256File(File file, long maximumBytes) throws Exception {
        if (file == null || !file.isFile() || isSymlink(file)) {
            throw new IllegalArgumentException("来源文件无效");
        }
        if (maximumBytes < 0 || file.length() > maximumBytes) {
            throw new IllegalArgumentException("来源文件超过大小限制");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maximumBytes) throw new IllegalArgumentException("来源文件超过大小限制");
                digest.update(buffer, 0, read);
            }
        }
        if (file.length() != total) throw new IOException("来源文件在读取时发生变化");
        return hex(digest.digest());
    }

    private static String canonical(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                String key = iterator.next();
                if ("updatedAt".equals(key) || key.startsWith("viewport")) continue;
                keys.add(key);
            }
            Collections.sort(keys);
            StringBuilder output = new StringBuilder("{");
            for (int index = 0; index < keys.size(); index++) {
                if (index > 0) output.append(',');
                String key = keys.get(index);
                output.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));
            }
            return output.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder output = new StringBuilder("[");
            for (int index = 0; index < array.length(); index++) {
                if (index > 0) output.append(',');
                output.append(canonical(array.get(index)));
            }
            return output.append(']').toString();
        }
        if (value instanceof Number) return JSONObject.numberToString((Number) value);
        if (value instanceof Boolean) return value.toString();
        return JSONObject.quote(String.valueOf(value));
    }

    private void writeAtomic(File target, String value) throws Exception {
        recover(target);
        File temporary = new File(directory, target.getName() + ".tmp");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        requireRunId(readBounded(temporary, MAX_RUN_BYTES), runIdFromFile(target));
        faults.after(WriteStage.TEMP_SYNCED);
        File backup = new File(directory, target.getName() + ".bak");
        if (backup.exists() && !backup.delete()) throw new IOException("无法轮换检查点备份");
        boolean hadTarget = target.isFile();
        if (hadTarget && !target.renameTo(backup)) throw new IOException("无法备份旧检查点");
        faults.after(WriteStage.OLD_VERSION_BACKED_UP);
        if (!temporary.renameTo(target)) {
            if (hadTarget) backup.renameTo(target);
            throw new IOException("无法提交数字化检查点");
        }
        faults.after(WriteStage.NEW_VERSION_PROMOTED);
        requireRunId(readBounded(target, MAX_RUN_BYTES), runIdFromFile(target));
    }

    private void recover(File target) throws Exception {
        ensureDirectory();
        File temporary = new File(directory, target.getName() + ".tmp");
        File backup = new File(directory, target.getName() + ".bak");
        String expectedRunId = runIdFromFile(target);
        boolean targetValid = valid(target, expectedRunId);
        boolean backupValid = valid(backup, expectedRunId);
        boolean temporaryValid = valid(temporary, expectedRunId);
        if (targetValid) {
            if (temporary.exists()) {
                if (temporaryValid) deleteRequired(temporary); else quarantine(temporary);
            }
            if (backup.exists() && !backupValid) quarantine(backup);
            return;
        }
        if (backupValid) {
            if (target.exists()) quarantine(target);
            if (!backup.renameTo(target)) throw new IOException("无法恢复检查点备份");
            if (temporary.exists()) {
                if (temporaryValid) deleteRequired(temporary); else quarantine(temporary);
            }
            return;
        }
        if (temporaryValid) {
            if (target.exists()) quarantine(target);
            if (backup.exists()) quarantine(backup);
            if (!temporary.renameTo(target)) throw new IOException("无法恢复临时检查点");
            return;
        }
        if (target.exists() || backup.exists() || temporary.exists()) {
            throw new IOException("检查点损坏；原文件已保留供恢复");
        }
    }

    private boolean valid(File file, String expectedRunId) {
        if (!file.isFile() || file.length() > MAX_RUN_BYTES) return false;
        try {
            requireRunId(readBounded(file, MAX_RUN_BYTES), expectedRunId);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private File[] runTargets() {
        if (!directory.isDirectory()) return new File[0];
        File[] values = directory.listFiles();
        if (values == null) return new File[0];
        LinkedHashMap<String, File> targets = new LinkedHashMap<>();
        for (File value : values) {
            String name = value.getName();
            int marker = name.indexOf(".json");
            if (marker < 0) continue;
            String runId = name.substring(0, marker);
            try { validateRunId(runId); }
            catch (Exception ignored) { continue; }
            targets.put(runId, runFile(runId));
        }
        return targets.values().toArray(new File[0]);
    }

    private File runFile(String runId) { return new File(directory, runId + ".json"); }

    private void ensureDirectory() throws IOException {
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建数字化检查点目录");
        if (!directory.isDirectory() || isSymlink(directory)) {
            throw new IOException("数字化检查点目录无效");
        }
    }

    private static String runIdFromFile(File file) {
        String name = file.getName();
        return name.substring(0, name.length() - 5);
    }

    private static void validateRunId(String runId) {
        if (runId == null || !runId.matches("digitize-[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("数字化任务 ID 无效");
        }
    }

    private static String requireIdentifier(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,160}")) {
            throw new IllegalArgumentException(label + " 无效");
        }
        return value;
    }

    private static String requireText(String value, String label, int maximum) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > maximum || clean.matches("(?s).*\\p{Cntrl}.*")) {
            throw new IllegalArgumentException(label + " 无效");
        }
        return clean;
    }

    private static String safeTitle(String title) {
        String clean = title == null ? "" : title.trim().replace('\n', ' ').replace('\r', ' ');
        if (clean.isEmpty()) clean = "未命名笔记";
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }

    private static String safeMessage(String value) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > 500 ? clean.substring(0, 500) : clean;
    }

    private static String safeError(Exception error) {
        return safeMessage(error == null ? "检查点无法读取" : error.getMessage());
    }

    private static boolean safeFileName(String value) {
        return value.matches("[A-Za-z0-9_\\-\u4e00-\u9fff.]+") && !value.contains("..");
    }

    private static void requireHash(String value, String label) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(label + "无效");
        }
    }

    private static boolean withinBytes(String value, int maximum) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length <= maximum;
    }

    private static String sha256(String value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format("%02x", item & 255));
        return value.toString();
    }

    private static String readBounded(File file, int maximum) throws Exception {
        if (!file.isFile() || isSymlink(file) || file.length() > maximum) {
            throw new IOException("检查点文件无效或超过大小限制");
        }
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maximum) throw new IOException("检查点文件超过大小限制");
                output.write(buffer, 0, read);
            }
            if (file.length() != total) throw new IOException("检查点文件在读取时发生变化");
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static Snapshot requireRunId(String value, String expectedRunId) throws Exception {
        Snapshot snapshot = decodeStatic(value);
        if (!expectedRunId.equals(snapshot.runId)) {
            throw new IllegalArgumentException("检查点文件名与任务 ID 不一致");
        }
        return snapshot;
    }

    private static boolean isSymlink(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent == null) return false;
        File normalizedLeaf = new File(parent.getCanonicalFile(), file.getName()).getAbsoluteFile();
        return !normalizedLeaf.equals(file.getCanonicalFile());
    }

    private static void quarantine(File file) throws IOException {
        long suffix = System.currentTimeMillis();
        File destination;
        do {
            destination = new File(file.getParentFile(), file.getName() + ".corrupt-" + suffix++);
        } while (destination.exists());
        if (!file.renameTo(destination)) throw new IOException("无法保留损坏检查点");
    }

    private static void deleteRequired(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("无法清理检查点临时文件");
    }
}
