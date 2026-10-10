package com.padnote.android.streaming;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertTrue;

/** Read-only metadata probe for path behavior inside the actual application process. */
@RunWith(AndroidJUnit4.class)
public final class AndroidAppPathDiagnosticInstrumentedTest {
    private static final String TAG = "STREAMING_PATH_DIAG_R1";
    private static final int MAX_OUTPUT_BYTES = 4096;

    @Test
    public void reportContextRootsAndAncestorIdentity() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        List<File> roots = new ArrayList<>();
        roots.add(context.getDataDir());
        roots.add(context.getFilesDir());
        roots.add(context.getCacheDir());

        JSONObject output = new JSONObject();
        output.put("schema", 1);
        JSONArray rootRows = new JSONArray();
        Set<String> components = new LinkedHashSet<>();
        for (File root : roots) {
            String absolute = root.getAbsoluteFile().toPath().normalize().toString();
            JSONObject row = new JSONObject();
            row.put("absolute", absolute);
            try {
                row.put("canonical", root.getCanonicalPath());
                row.put("canonical_status", "ok");
            } catch (Exception ignored) {
                row.put("canonical_status", "error");
            }
            rootRows.put(row);
            addComponents(absolute, components);
        }
        output.put("roots", rootRows);

        JSONArray componentRows = new JSONArray();
        for (String path : components) componentRows.put(inspect(path));
        output.put("components", componentRows);

        String line = output.toString();
        int byteCount = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue("diagnostic output exceeded fixed cap", byteCount <= MAX_OUTPUT_BYTES);
        android.util.Log.i(TAG, line);
        System.out.println(TAG + " " + line);
    }

    private static void addComponents(String absolute, Set<String> out) {
        File current = new File(absolute);
        List<String> reversed = new ArrayList<>();
        while (current != null) {
            reversed.add(current.getAbsolutePath());
            current = current.getParentFile();
        }
        for (int i = reversed.size() - 1; i >= 0; i--) out.add(reversed.get(i));
    }

    private static JSONObject inspect(String path) throws Exception {
        JSONObject row = new JSONObject();
        row.put("path", path);
        StructStat pathStat;
        try {
            pathStat = Os.lstat(path);
        } catch (ErrnoException error) {
            row.put("lstat_errno", error.errno);
            return row;
        }
        row.put("mode", String.format(java.util.Locale.ROOT, "%06o", pathStat.st_mode));
        row.put("type", typeOf(pathStat.st_mode));
        row.put("is_directory", OsConstants.S_ISDIR(pathStat.st_mode));
        row.put("is_symlink", OsConstants.S_ISLNK(pathStat.st_mode));
        row.put("path_dev", pathStat.st_dev);
        row.put("path_ino", pathStat.st_ino);
        if (!OsConstants.S_ISDIR(pathStat.st_mode)) return row;

        FileDescriptorHolder fd = null;
        try {
            fd = openDirectoryNoFollow(path);
            StructStat fdStat = Os.fstat(fd.value);
            row.put("fd_mode", String.format(java.util.Locale.ROOT, "%06o", fdStat.st_mode));
            row.put("fd_type", typeOf(fdStat.st_mode));
            row.put("fd_is_directory", OsConstants.S_ISDIR(fdStat.st_mode));
            row.put("fd_dev", fdStat.st_dev);
            row.put("fd_ino", fdStat.st_ino);
            row.put("same_dev_ino", pathStat.st_dev == fdStat.st_dev && pathStat.st_ino == fdStat.st_ino);
        } catch (ErrnoException error) {
            row.put("open_fstat_errno", error.errno);
        } finally {
            if (fd != null) {
                try { Os.close(fd.value); }
                catch (ErrnoException error) { row.put("close_errno", error.errno); }
            }
        }
        return row;
    }

    private static FileDescriptorHolder openDirectoryNoFollow(String path) throws ErrnoException {
        return new FileDescriptorHolder(Os.open(path,
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW,
                0));
    }

    private static String typeOf(int mode) {
        if (OsConstants.S_ISDIR(mode)) return "directory";
        if (OsConstants.S_ISLNK(mode)) return "symlink";
        if (OsConstants.S_ISREG(mode)) return "regular";
        return "other";
    }

    private static final class FileDescriptorHolder {
        final java.io.FileDescriptor value;
        FileDescriptorHolder(java.io.FileDescriptor value) { this.value = value; }
    }
}
