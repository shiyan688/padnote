package com.padnote.android;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
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

    VaultStore(Context context) {
        vaultDir = new File(context.getFilesDir(), "vault");
        if (!vaultDir.exists() && !vaultDir.mkdirs()) {
            // list()/write() surface the failure; constructor stays tolerant.
        }
    }

    public List<VaultNote> list() {
        List<VaultNote> notes = new ArrayList<>();
        File[] files = vaultDir.listFiles((dir, name) -> name.endsWith(".md"));
        if (files == null) {
            return notes;
        }
        for (File file : files) {
            try {
                String head = readFileHead(file, 2048);
                if (!head.startsWith("---")) {
                    continue;
                }
                notes.add(new VaultNote(file.getName(),
                        frontValue(head, "note-id"),
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
        if (!fileName.matches("[A-Za-z0-9_\\-\u4e00-\u9fff.]+") || fileName.contains("..")) {
            throw new IllegalArgumentException("Invalid vault file name");
        }
        File file = new File(vaultDir, fileName);
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
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

    /** Writes one digitized note atomically, replacing any older file for the same note id. */
    void write(String noteId, String title, int pageCount, long sourceModifiedAt,
               List<String> pageSections) throws Exception {
        if (noteId == null || noteId.trim().isEmpty()) {
            throw new IllegalArgumentException("noteId is required");
        }
        String safeTitle = sanitizeTitle(title);
        // The title is the filename, so a renamed note must leave no orphan under
        // its previous name; matching by note-id in the front-matter finds it.
        for (VaultNote existing : list()) {
            if (existing.noteId.equals(noteId) && !existing.fileName.equals(safeTitle + ".md")) {
                new File(vaultDir, existing.fileName).delete();
            }
        }
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

        File target = new File(vaultDir, safeTitle + ".md");
        File temporary = new File(vaultDir, safeTitle + ".md.tmp");
        byte[] bytes = markdown.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IllegalStateException("无法写入知识库文件");
        }
    }

    void delete(String fileName) {
        if (fileName == null || fileName.contains("..")) {
            return;
        }
        new File(vaultDir, fileName).delete();
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
