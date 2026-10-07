package com.padnote.android;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** API 24-compatible primitives for Android's Linux file operations. */
final class AndroidFileCompat {
    /** Linux UAPI O_CLOEXEC (02000000), present in Android's API 24 Bionic kernel headers. */
    static final int O_CLOEXEC = 0x80000;

    private AndroidFileCompat() { }

    /** Lexically normalizes an absolute path without resolving symlinks. */
    static String normalizeAbsolute(File file) throws IOException {
        if (file == null) throw new IOException("PATH_INVALID");
        String raw = file.getAbsolutePath();
        if (raw == null || !raw.startsWith("/")) throw new IOException("PATH_INVALID");
        List<String> parts = new ArrayList<>();
        for (String part : raw.split("/+")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) return "/";
        StringBuilder out = new StringBuilder();
        for (String part : parts) out.append('/').append(part);
        return out.toString();
    }

    /** Returns lexical path components below root; rejects root itself and prefix siblings. */
    static List<String> descendantComponents(File root, File target) throws IOException {
        String rootPath = normalizeAbsolute(root);
        String targetPath = normalizeAbsolute(target);
        if (rootPath.equals(targetPath)) throw new IOException("PATH_NOT_DESCENDANT");
        String prefix = "/".equals(rootPath) ? "/" : rootPath + "/";
        if (!targetPath.startsWith(prefix)) throw new IOException("PATH_OUTSIDE_ROOT");
        String relative = targetPath.substring(prefix.length());
        if (relative.isEmpty()) throw new IOException("PATH_NOT_DESCENDANT");
        String[] components = relative.split("/");
        List<String> result = new ArrayList<>(components.length);
        for (String component : components) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                throw new IOException("PATH_INVALID");
            }
            result.add(component);
        }
        return Collections.unmodifiableList(result);
    }
}
