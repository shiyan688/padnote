package com.padnote.android;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Storage for the knowledge base: one Markdown file per digitized handwritten
 * note, under the app-private {@code vault/} directory.
 *
 * <p>Files are plain Markdown with a small YAML front-matter block, so the
 * whole directory stays Obsidian-compatible — copying it out loses nothing.
 * The front-matter carries the source note id and the timestamp of the ink it
 * was generated from, which is what makes the "已过期" staleness badge possible
 * without any separate index database.
 *
 * <p>Implements {@link NoteTools.VaultReader} so the AI's knowledge-base tools
 * see exactly {@link #list()} and {@link #read(String)} — and no write path.
 */
final class VaultStore implements NoteTools.VaultReader {
    private static final Object GLOBAL_LOCK = new Object();

    static final class VaultNote {
        final String fileName;
        final String noteId;
        final String title;
        final int pageCount;
        final long digitizedAt;
        final long sourceModifiedAt;

        VaultNote(String fileName, String noteId, String title, int pageCount,
                  long digitizedAt, long sourceModifiedAt) {
            this.fileName = fileName;
            this.noteId = noteId;
            this.title = title;
            this.pageCount = pageCount;
            this.digitizedAt = digitizedAt;
            this.sourceModifiedAt = sourceModifiedAt;
        }
    }

    private final File vaultDir;
    private final NoteStore.WriteFaultInjector writeFaults;

    VaultStore(Context context) {
        this(new File(context.getFilesDir(), "vault"), NoteStore.NO_WRITE_FAULTS);
    }

    VaultStore(File vaultDir) {
        this(vaultDir, NoteStore.NO_WRITE_FAULTS);
    }

    VaultStore(File vaultDir, NoteStore.WriteFaultInjector writeFaults) {
        this.vaultDir = vaultDir;
        this.writeFaults = writeFaults == null ? NoteStore.NO_WRITE_FAULTS : writeFaults;
        if (!vaultDir.exists() && !vaultDir.mkdirs()) {
            // list()/write() surface the failure; constructor stays tolerant.
        }
    }

    public List<VaultNote> list() {
        synchronized (GLOBAL_LOCK) {
            return listUnlocked();
        }
    }

    private List<VaultNote> listUnlocked() {
        List<VaultNote> notes = new ArrayList<>();
        recoverVaultFiles();
        File[] files = vaultDir.listFiles((dir, name) -> name.endsWith(".md"));
        if (files == null) {
            return notes;
        }
        for (File file : files) {
            try {
                File checked = checkedVaultFile(file.getName());
                String head = readFileHead(checked, 2048);
                if (!head.startsWith("---")) {
                    continue;
                }
                String noteId = frontValue(head, "note-id");
                if (!noteId.matches("[A-Za-z0-9_-]{1,160}")) continue;
                notes.add(new VaultNote(file.getName(), noteId,
                        frontValue(head, "title"),
                        parseInt(frontValue(head, "pages"), 0),
                        parseLong(frontValue(head, "digitized-epoch"), 0L),
                        parseLong(frontValue(head, "source-modified"), 0L)));
            } catch (Exception corrupted) {
                // A damaged file must not break listing the rest of the vault.
            }
        }
        notes.sort((a, b) -> Long.compare(b.digitizedAt, a.digitizedAt));
        return notes;
    }

    public String read(String fileName) throws Exception {
        return readBounded(fileName, 16 * 1024 * 1024);
    }

    /** Replaces an explicitly edited Markdown source without changing its filename. */
    void replaceRaw(String fileName, String markdown) throws Exception {
        synchronized (GLOBAL_LOCK) {
            File target = checkedVaultFile(fileName);
            NoteStore.recoverAtomicFile(target, VaultStore::validateAnyMarkdown);
            if (!target.isFile()) throw new IllegalArgumentException("格式笔记不存在");
            String existing = readBoundedUnlocked(fileName, 16 * 1024 * 1024);
            String expectedNoteId = frontValue(
                    existing.substring(0, Math.min(existing.length(), 4096)), "note-id");
            validateMarkdown(markdown, expectedNoteId);
            NoteStore.writeAtomic(target, markdown,
                    value -> validateMarkdown(value, expectedNoteId),
                    writeFaults, 16 * 1024 * 1024);
        }
    }

    @Override
    public String readBounded(String fileName, int maximumBytes) throws Exception {
        synchronized (GLOBAL_LOCK) {
            return readBoundedUnlocked(fileName, maximumBytes);
        }
    }

    private String readBoundedUnlocked(String fileName, int maximumBytes) throws Exception {
        File file = checkedVaultFile(fileName);
        NoteStore.recoverAtomicFile(file, VaultStore::validateAnyMarkdown);
        long size = file.length();
        if (maximumBytes < 0 || size > maximumBytes || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("格式笔记超出单项材料上限");
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) size];
            int read = 0;
            while (read < buffer.length) {
                int chunk = input.read(buffer, read, buffer.length - read);
                if (chunk < 0) {
                    break;
                }
                read += chunk;
            }
            if (input.read() != -1) {
                throw new IllegalArgumentException("格式笔记在读取时变大，请重新选择");
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    /** Writes one digitized note atomically, then retires older files for this source id. */
    String write(String noteId, String title, int pageCount, long sourceModifiedAt,
                 List<String> pageSections) throws Exception {
        synchronized (GLOBAL_LOCK) {
            return writeUnlocked(noteId, title, pageCount, sourceModifiedAt, pageSections);
        }
    }

    private String writeUnlocked(String noteId, String title, int pageCount,
                                 long sourceModifiedAt, List<String> pageSections)
            throws Exception {
        if (noteId == null || !noteId.matches("[A-Za-z0-9_-]{1,160}")) {
            throw new IllegalArgumentException("noteId is required");
        }
        String safeTitle = sanitizeTitle(title);
        List<VaultNote> beforeCommit = list();
        long now = System.currentTimeMillis();
        StringBuilder markdown = new StringBuilder();
        markdown.append("---\n")
                .append("title: ").append(oneLine(safeTitle)).append('\n')
                .append("note-id: ").append(oneLine(noteId)).append('\n')
                .append("pages: ").append(Math.max(0, pageCount)).append('\n')
                .append("digitized: ").append(
                        new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(now)))
                .append('\n')
                .append("digitized-epoch: ").append(now).append('\n')
                .append("source-modified: ").append(Math.max(0L, sourceModifiedAt)).append('\n')
                .append("---\n\n")
                .append("# ").append(oneLine(safeTitle)).append("\n\n");
        for (int index = 0; index < pageSections.size(); index++) {
            String section = pageSections.get(index);
            if (section == null || section.trim().isEmpty()) {
                continue;
            }
            markdown.append("## 第 ").append(index + 1).append(" 页\n\n")
                    .append(section.trim()).append("\n\n");
        }

        String fileName = "note-" + stableNoteSuffix(noteId) + ".md";
        File target = new File(vaultDir, fileName);
        NoteStore.StoredFileValidator validator = value -> validateMarkdown(value, noteId);
        NoteStore.writeAtomic(target, markdown.toString(), validator, writeFaults,
                16 * 1024 * 1024);
        validateMarkdown(readBounded(fileName, 16 * 1024 * 1024), noteId);

        // The replacement is now durable and readable. Only now may an older
        // title-based or renamed file for this same source be removed.
        for (VaultNote existing : beforeCommit) {
            if (existing.noteId.equals(noteId) && !existing.fileName.equals(fileName)) {
                deleteOldFamily(existing.fileName);
            }
        }
        return fileName;
    }

    void delete(String fileName) {
        synchronized (GLOBAL_LOCK) {
            try { deleteFamily(fileName, false); }
            catch (Exception ignored) { /* Legacy void API: an undeleted file remains visible. */ }
        }
    }

    boolean hasNoteForSource(String noteId) {
        for (VaultNote note : list()) {
            if (note.noteId.equals(noteId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Embeds an existing text flow into the digitized document. Markdown flows
     * are already in the target format and pass through verbatim; LaTeX flows
     * are wrapped in display math so they compile like any other formula.
     */
    static String embedTextFlow(TextFlow flow) {
        String source = flow.source == null ? "" : flow.source.trim();
        if (source.isEmpty()) {
            return "";
        }
        if (flow.format == NoteTextBox.Format.MARKDOWN) {
            return source;
        }
        String bare = CompiledTextWebView.normalizeLatexSource(source);
        if (bare.isEmpty()) {
            return "";
        }
        return "\\[\n" + bare + "\n\\]";
    }

    static String sanitizeTitle(String title) {
        String cleaned = (title == null ? "" : title).trim()
                .replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            cleaned = "未命名笔记";
        }
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60).trim();
        }
        return cleaned;
    }

    private static String stableNoteSuffix(String noteId) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(noteId.getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < digest.length; index++) {
            value.append(String.format(Locale.ROOT, "%02x", digest[index] & 255));
        }
        return value.toString();
    }

    private static void validateMarkdown(String value, String expectedNoteId) {
        if (value == null || !value.startsWith("---\n")) {
            throw new IllegalArgumentException("格式笔记缺少元数据");
        }
        String noteId = frontValue(value.substring(0, Math.min(value.length(), 4096)), "note-id");
        if (!expectedNoteId.equals(noteId)) {
            throw new IllegalArgumentException("格式笔记来源 ID 不一致");
        }
    }

    private static void validateAnyMarkdown(String value) {
        if (value == null || !value.startsWith("---\n")) {
            throw new IllegalArgumentException("格式笔记缺少元数据");
        }
        String noteId = frontValue(value.substring(0, Math.min(value.length(), 4096)), "note-id");
        if (!noteId.matches("[A-Za-z0-9_-]{1,160}")) {
            throw new IllegalArgumentException("格式笔记来源 ID 无效");
        }
    }

    private void recoverVaultFiles() {
        File[] files = vaultDir.listFiles();
        if (files == null) return;
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (File file : files) {
            String name = file.getName();
            int marker = name.indexOf(".md");
            if (marker < 0) continue;
            names.add(name.substring(0, marker + 3));
        }
        for (String name : names) {
            try { NoteStore.recoverAtomicFile(new File(vaultDir, name), VaultStore::validateAnyMarkdown); }
            catch (Exception ignored) { /* Original candidates remain for recovery/export. */ }
        }
    }

    private void deleteOldFamily(String fileName) throws Exception {
        deleteFamily(fileName, true);
    }

    private void deleteFamily(String fileName, boolean requireDeletion) throws Exception {
        checkedVaultFile(fileName);
        for (String suffix : new String[]{"", ".tmp", ".bak"}) {
            File file = checkedVaultFile(fileName + suffix);
            if (file.exists() && !file.delete() && requireDeletion) {
                throw new java.io.IOException("新格式笔记已保存，但无法清理旧来源文件");
            }
        }
    }

    private File checkedVaultFile(String fileName) throws Exception {
        if (fileName == null || fileName.isEmpty()
                || fileName.getBytes(StandardCharsets.UTF_8).length > 240
                || fileName.equals(".") || fileName.equals("..") || fileName.contains("..")
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || fileName.indexOf('\0') >= 0 || !fileName.endsWith(".md")
                        && !fileName.endsWith(".md.tmp") && !fileName.endsWith(".md.bak")) {
            throw new IllegalArgumentException("知识库文件名无效");
        }
        File root = vaultDir.getCanonicalFile();
        File candidate = new File(root, fileName);
        File canonical = candidate.getCanonicalFile();
        if (!root.equals(canonical.getParentFile())
                || !candidate.getAbsoluteFile().equals(canonical)) {
            throw new IllegalArgumentException("知识库文件路径无效");
        }
        return candidate;
    }

    private static String oneLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String readFileHead(File file, int limit) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[Math.min(limit, (int) file.length())];
            int read = 0;
            while (read < buffer.length) {
                int chunk = input.read(buffer, read, buffer.length - read);
                if (chunk < 0) {
                    break;
                }
                read += chunk;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    /** Front-matter value for {@code key}, or "" — also used by the vault tools. */
    static String frontValue(String head, String key) {
        boolean inFrontMatter = false;
        for (String line : head.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.equals("---")) {
                if (inFrontMatter) {
                    break;
                }
                inFrontMatter = true;
                continue;
            }
            if (!inFrontMatter) {
                continue;
            }
            int separator = trimmed.indexOf(':');
            if (separator > 0 && key.equals(trimmed.substring(0, separator).trim())) {
                return trimmed.substring(separator + 1).trim();
            }
        }
        return "";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception malformed) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception malformed) {
            return fallback;
        }
    }
}
