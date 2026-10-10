package com.padnote.android.streaming;

import android.content.Context;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.Path;

/** Capability for the exact private files root returned by an application Context. */
@android.annotation.TargetApi(27)
final class TrustedFilesRoot {
    private final Path path;
    private final Path contextPath;
    private final long device;
    private final long inode;
    private final int uid;
    private final int mode;

    private TrustedFilesRoot(Path path, Path contextPath, StructStat witness) throws IOException {
        this.path = path;
        this.contextPath = contextPath;
        requireDirectoryForApp(witness, Process.myUid(), "TRUSTED_FILES_ROOT_NOT_PRIVATE_DIRECTORY");
        device = witness.st_dev;
        inode = witness.st_ino;
        uid = witness.st_uid;
        mode = witness.st_mode;
    }

    static TrustedFilesRoot fromApplicationContext(Context context) throws IOException {
        if (context == null) throw new IllegalArgumentException("APPLICATION_CONTEXT_REQUIRED");
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        File filesDir = application.getFilesDir();
        if (filesDir == null) throw new IOException("CONTEXT_FILES_DIR_MISSING");
        // Context paths can use an Android alias such as /data/user/0 while a
        // checked store path returned by File.getCanonicalFile() uses its
        // physical spelling (/data/data). Bind the capability to that same
        // physical directory, and prove the original Context path names the
        // identical private directory before using it as the containment root.
        Path requested = filesDir.getAbsoluteFile().toPath().normalize();
        Path path;
        try { path = filesDir.getCanonicalFile().toPath().normalize(); }
        catch (IOException error) { throw new IOException("TRUSTED_FILES_ROOT_CANONICALIZE", error); }
        if (!path.isAbsolute() || !requested.isAbsolute()) throw new IOException("TRUSTED_FILES_ROOT_MUST_BE_ABSOLUTE");
        StructStat requestedStat = lstat(requested);
        StructStat canonicalStat = lstat(path);
        requireDirectoryForApp(requestedStat, Process.myUid(), "TRUSTED_FILES_ROOT_NOT_PRIVATE_DIRECTORY");
        requireDirectoryForApp(canonicalStat, Process.myUid(), "TRUSTED_FILES_ROOT_NOT_PRIVATE_DIRECTORY");
        if (requestedStat.st_dev != canonicalStat.st_dev || requestedStat.st_ino != canonicalStat.st_ino
                || requestedStat.st_uid != canonicalStat.st_uid || requestedStat.st_mode != canonicalStat.st_mode) {
            throw new IOException("TRUSTED_FILES_ROOT_ALIAS_IDENTITY_MISMATCH");
        }
        TrustedFilesRoot result = new TrustedFilesRoot(path, requested, canonicalStat);
        result.verifyAgainst(canonicalStat);
        result.openAndVerify();
        return result;
    }

    Path path() { return path; }
    long device() { return device; }
    long inode() { return inode; }
    int appUid() { return uid; }
    int mode() { return mode; }

    Path requireInScope(Path candidate) throws IOException {
        if (candidate == null) throw new IOException("TRUSTED_FILES_PATH_REQUIRED");
        Path absolute = candidate.toAbsolutePath().normalize();
        if (absolute.startsWith(path)) return absolute;
        // NoteStore may retain the exact lexical spelling returned by the
        // Context, while VaultStore canonicalizes its directory. Translate
        // only that previously verified Context alias to the canonical root.
        // Do not canonicalize arbitrary candidates: a symlink outside this
        // root must never become an accepted in-root path by following it.
        if (absolute.startsWith(contextPath)) {
            Path translated = path.resolve(contextPath.relativize(absolute)).normalize();
            if (!translated.startsWith(path)) throw new IOException("PATH_OUTSIDE_TRUSTED_FILES_ROOT");
            return translated;
        }
        throw new IOException("PATH_OUTSIDE_TRUSTED_FILES_ROOT");
    }

    void verify() throws IOException {
        StructStat current = lstat(path);
        verifyAgainst(current);
        openAndVerify();
        StructStat after = lstat(path);
        verifyAgainst(after);
    }

    private void openAndVerify() throws IOException {
        FileDescriptor fd;
        try {
            fd = Os.open(path.toString(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        } catch (ErrnoException error) {
            throw errno("TRUSTED_FILES_ROOT_OPEN", error);
        }
        IOException failure = null;
        try {
            StructStat opened;
            try { opened = Os.fstat(fd); }
            catch (ErrnoException error) { throw errno("TRUSTED_FILES_ROOT_FSTAT", error); }
            verifyAgainst(opened);
        } catch (IOException error) {
            failure = error;
            throw error;
        } finally {
            try { Os.close(fd); }
            catch (ErrnoException closeError) {
                if (failure != null) failure.addSuppressed(errno("TRUSTED_FILES_ROOT_CLOSE", closeError));
                else throw errno("TRUSTED_FILES_ROOT_CLOSE", closeError);
            }
        }
    }

    private void verifyAgainst(StructStat stat) throws IOException {
        requireDirectoryForApp(stat, uid, "TRUSTED_FILES_ROOT_IDENTITY_CHANGED");
        if (stat.st_dev != device || stat.st_ino != inode || stat.st_uid != uid || stat.st_mode != mode) {
            throw new IOException("TRUSTED_FILES_ROOT_IDENTITY_CHANGED");
        }
    }

    private static StructStat lstat(Path path) throws IOException {
        try { return Os.lstat(path.toString()); }
        catch (ErrnoException error) { throw errno("TRUSTED_FILES_ROOT_LSTAT", error); }
    }

    private static void requireDirectoryForApp(StructStat stat, int appUid, String code) throws IOException {
        if (!OsConstants.S_ISDIR(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode) || stat.st_uid != appUid) {
            throw new IOException(code);
        }
    }

    private static IOException errno(String operation, ErrnoException error) {
        return new IOException(operation + "_ERRNO_" + error.errno);
    }
}
