package com.padnote.android;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.padnote.android.streaming.NativeNoReplace;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Durable, permanent commit markers for physical deletion of legacy notes.
 *
 * <p>Only a verified canonical {@code <noteId>.committed} file is a logical commit. Temporary
 * files, even complete and synced ones, are never a commit signal. Callers must not clean any
 * note material for NOT_COMMITTED or UNCERTAIN. Markers intentionally remain after cleanup to
 * stop an old index backup from bringing the deleted id back.
 */
final class LegacyDeleteJournal {
    // Bionic's fcntl.h defines O_CLOEXEC as 02000000 on all supported Android ABIs.
    // OsConstants.O_CLOEXEC is annotated API 27+, so use the stable kernel flag
    // value directly to keep atomic close-on-exec behavior on minSdk 24 as well.
    private static final int O_CLOEXEC_COMPAT = 02000000;
    static final int MAX_MARKER_BYTES = 16 * 1024;
    static final int MAX_MARKERS = 20_000;
    static final long MAX_INVENTORY_BYTES = 64L * 1024L * 1024L;
    private static final String DIRECTORY = "legacy-note-deletes";
    private static final String HEADER = "PADNOTE_LEGACY_DELETE_V1";

    enum CommitStatus { NOT_COMMITTED, COMMITTED, UNCERTAIN }
    enum Stage { TEMP_SYNCED, CANONICAL_PROMOTED, DIRECTORY_SYNCED }

    interface FaultInjector { void after(Stage stage) throws Exception; }
    interface TemporaryWriter {
        void writeNewSynced(File temporary, byte[] contents) throws IOException;
        void deleteOwnedTemp(File temporary, Identity expected, FileIdentityReader identities)
                throws IOException;
    }
    interface DirectorySync { void sync(File directory) throws IOException; }
    interface Publisher { void publishNoReplace(File temporary, File canonical) throws IOException; }
    /** Explicit package-private storage injection seam for isolated host tests only. */
    interface StorageEnvironment {
        FileIdentityReader fileIdentityReader();
        DirectorySync directorySync();
    }
    interface FileIdentityReader {
        int currentUid();
        Identity lstat(File file) throws IOException;
        byte[] readRegularNoFollow(File file, int maximumBytes) throws IOException;
        String sha256RegularNoFollow(File file) throws IOException;
    }

    static final class Identity {
        final boolean exists, regular, directory, symlink;
        final int uid;
        final long linkCount;
        final long device, inode, size, modifiedSeconds;
        final String canonicalPath;

        Identity(boolean exists, boolean regular, boolean directory, boolean symlink,
                 int uid, long linkCount, long device, long inode, long size,
                 long modifiedSeconds, String canonicalPath) {
            this.exists=exists; this.regular=regular; this.directory=directory;
            this.symlink=symlink; this.uid=uid; this.linkCount=linkCount;
            this.device=device; this.inode=inode; this.size=size;
            this.modifiedSeconds=modifiedSeconds; this.canonicalPath=canonicalPath;
        }

        boolean sameObject(Identity other) {
            return other != null && exists == other.exists && regular == other.regular
                    && directory == other.directory && symlink == other.symlink
                    && uid == other.uid && linkCount == other.linkCount
                    && device == other.device && inode == other.inode && size == other.size
                    && modifiedSeconds == other.modifiedSeconds
                    && equal(canonicalPath, other.canonicalPath);
        }
    }

    static final class Record {
        final String noteId, operationId, canonicalNoteSha256;
        final long createdAt;
        final boolean hasCanonicalNoteIdentity;
        final String filesRootPathSha256;
        final long filesRootDevice, filesRootInode;
        final int noteUid;
        final long noteLinkCount;
        final long noteDevice, noteInode, noteSize, noteModifiedSeconds;

        Record(String noteId, String operationId, String canonicalNoteSha256, long createdAt) {
            this(noteId, operationId, canonicalNoteSha256, createdAt, null, null, null);
        }

        Record(String noteId, String operationId, String canonicalNoteSha256, long createdAt,
               String canonicalFilesRoot, Identity filesRootIdentity,
               Identity canonicalNoteIdentity) {
            this.noteId=noteId; this.operationId=operationId;
            this.canonicalNoteSha256=canonicalNoteSha256; this.createdAt=createdAt;
            this.hasCanonicalNoteIdentity=filesRootIdentity!=null&&canonicalNoteIdentity!=null;
            this.filesRootPathSha256=hasCanonicalNoteIdentity?sha256Text(canonicalFilesRoot):"-";
            this.filesRootDevice=hasCanonicalNoteIdentity?filesRootIdentity.device:0;
            this.filesRootInode=hasCanonicalNoteIdentity?filesRootIdentity.inode:0;
            this.noteUid=hasCanonicalNoteIdentity?canonicalNoteIdentity.uid:0;
            this.noteLinkCount=hasCanonicalNoteIdentity?canonicalNoteIdentity.linkCount:0;
            this.noteDevice=hasCanonicalNoteIdentity?canonicalNoteIdentity.device:0;
            this.noteInode=hasCanonicalNoteIdentity?canonicalNoteIdentity.inode:0;
            this.noteSize=hasCanonicalNoteIdentity?canonicalNoteIdentity.size:0;
            this.noteModifiedSeconds=hasCanonicalNoteIdentity?canonicalNoteIdentity.modifiedSeconds:0;
        }

        private Record(String noteId, String operationId, String canonicalNoteSha256, long createdAt,
                       boolean hasIdentity, String rootPathSha, long rootDevice, long rootInode,
                       int noteUid, long noteLinkCount, long noteDevice, long noteInode,
                       long noteSize, long noteModifiedSeconds) {
            this.noteId=noteId; this.operationId=operationId;
            this.canonicalNoteSha256=canonicalNoteSha256; this.createdAt=createdAt;
            this.hasCanonicalNoteIdentity=hasIdentity; this.filesRootPathSha256=rootPathSha;
            this.filesRootDevice=rootDevice; this.filesRootInode=rootInode; this.noteUid=noteUid;
            this.noteLinkCount=noteLinkCount; this.noteDevice=noteDevice; this.noteInode=noteInode;
            this.noteSize=noteSize; this.noteModifiedSeconds=noteModifiedSeconds;
        }
    }

    static final class CommitResult {
        final CommitStatus status;
        final Record record;
        final String errorCode;
        private CommitResult(CommitStatus status, Record record, String errorCode) {
            this.status=status; this.record=record; this.errorCode=errorCode;
        }
    }

    private LegacyDeleteJournal() { }

    static CommitResult createCommitted(File filesRoot, Record requested, FaultInjector faults,
                                        FileIdentityReader identities, Publisher publisher,
                                        DirectorySync directorySync) {
        return createCommitted(filesRoot, requested, faults, identities, publisher,
                directorySync, androidTemporaryWriter());
    }

    static CommitResult createCommitted(File filesRoot, Record requested, FaultInjector faults,
                                        FileIdentityReader identities, Publisher publisher,
                                        DirectorySync directorySync, TemporaryWriter writer) {
        if (identities == null || publisher == null || directorySync == null
                || writer == null || !validRecord(requested)) return result(CommitStatus.NOT_COMMITTED, requested,
                "DELETE_MARKER_INVALID_INPUT");
        File directory = new File(filesRoot, DIRECTORY);
        File temporary = null;
        Identity temporaryIdentity = null;
        boolean promoted = false;
        boolean publicationAttempted = false;
        try {
            ensureDirectory(filesRoot, directory, identities, directorySync);
            File canonical = markerFile(directory, requested.noteId);
            Identity existing = identities.lstat(canonical);
            if (existing.exists) {
                Record old = readCanonical(filesRoot, requested.noteId, identities, directorySync);
                if (sameRecord(old, requested))
                    return result(CommitStatus.COMMITTED, old, null);
                return result(CommitStatus.NOT_COMMITTED, requested, "DELETE_MARKER_CONFLICT");
            }

            temporary = stagingFile(directory, requested);
            byte[] encoded = encode(requested);
            writer.writeNewSynced(temporary, encoded);
            temporaryIdentity = requireRegularOwned(temporary, identities, filesRoot, false);
            if (faults != null) faults.after(Stage.TEMP_SYNCED);

            // Atomic renameat2(RENAME_NOREPLACE) publishes the synced marker as one inode.
            publicationAttempted = true;
            publisher.publishNoReplace(temporary, canonical);
            promoted = true;
            if (faults != null) faults.after(Stage.CANONICAL_PROMOTED);
            directorySync.sync(directory);
            if (faults != null) faults.after(Stage.DIRECTORY_SYNCED);

            Record observed = readCanonical(filesRoot, requested.noteId, identities,
                    directorySync);
            if (!sameRecord(observed, requested))
                return result(CommitStatus.UNCERTAIN, requested, "DELETE_MARKER_READBACK_MISMATCH");
            return result(CommitStatus.COMMITTED, observed, null);
        } catch (Exception failure) {
            boolean canonicalVisible = false;
            if (!promoted && publicationAttempted) try {
                canonicalVisible = identities.lstat(markerFile(directory,
                        requested.noteId)).exists;
            } catch (Exception ignored) { canonicalVisible = true; }
            promoted = promoted || canonicalVisible;
            if (!promoted) {
                deleteTemporaryIfSame(temporary, temporaryIdentity, identities, writer);
                return result(CommitStatus.NOT_COMMITTED, requested, code(failure,
                        "DELETE_MARKER_WRITE_FAILED"));
            }
            // The canonical file became visible, but durability or verification did not complete.
            // Do not clean any assets. A later call must verify and fsync the canonical marker.
            return result(CommitStatus.UNCERTAIN, requested, code(failure,
                    "DELETE_MARKER_DURABILITY_UNCERTAIN"));
        }
    }

    static Record readCommitted(File filesRoot, String noteId, FileIdentityReader identities,
                                DirectorySync directorySync) throws IOException {
        if (!validNoteId(noteId) || identities == null || directorySync == null)
            throw new IOException("DELETE_MARKER_INVALID_INPUT");
        File directory = new File(filesRoot, DIRECTORY);
        if (!verifyReadDirectory(filesRoot, directory, identities, directorySync)) return null;
        File canonical = markerFile(directory, noteId);
        Identity before = identities.lstat(canonical);
        if (!before.exists) return null;
        Record record = readCanonical(filesRoot, noteId, identities, directorySync);
        if (!noteId.equals(record.noteId)) throw new IOException("DELETE_MARKER_ID_MISMATCH");
        return record;
    }

    static List<Record> readAllCommitted(File filesRoot, FileIdentityReader identities,
                                        DirectorySync directorySync) throws IOException {
        File directory = new File(filesRoot, DIRECTORY);
        if (!verifyReadDirectory(filesRoot, directory, identities, directorySync))
            return Collections.emptyList();
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("DELETE_MARKER_DIRECTORY_UNREADABLE");
        if (children.length > MAX_MARKERS + 4096)
            throw new IOException("DELETE_MARKER_INVENTORY_TOO_LARGE");
        List<Record> result = new ArrayList<>();
        long totalBytes = 0;
        for (File child : children) {
            String name = child.getName();
            if (!name.endsWith(".committed")) continue;
            String noteId = name.substring(0, name.length() - ".committed".length());
            if (!validNoteId(noteId)) throw new IOException("DELETE_MARKER_NAME_INVALID");
            if (result.size() >= MAX_MARKERS) throw new IOException("DELETE_MARKER_INVENTORY_TOO_LARGE");
            Identity observed = identities.lstat(child);
            totalBytes += observed.size;
            if (totalBytes > MAX_INVENTORY_BYTES)
                throw new IOException("DELETE_MARKER_INVENTORY_TOO_LARGE");
            Record record = readCanonical(filesRoot, noteId, identities, directorySync);
            if (record == null) throw new IOException("DELETE_MARKER_DISAPPEARED");
            result.add(record);
        }
        return Collections.unmodifiableList(result);
    }

    static File canonicalFile(File filesRoot, String noteId) {
        if (!validNoteId(noteId)) throw new IllegalArgumentException("invalid note id");
        return markerFile(new File(filesRoot, DIRECTORY), noteId);
    }

    static boolean matchesCapturedCanonicalNote(File filesRoot, Record record, String noteId,
                                                FileIdentityReader identities) throws IOException {
        if (record == null || !record.hasCanonicalNoteIdentity || !noteId.equals(record.noteId))
            return false;
        File root = filesRoot.getCanonicalFile();
        Identity rootState = identities.lstat(root);
        File note = new File(new File(root, "notes"), noteId + ".json");
        Identity state = identities.lstat(note);
        if (!rootState.directory || rootState.symlink || rootState.uid != identities.currentUid()
                || rootState.device != record.filesRootDevice || rootState.inode != record.filesRootInode
                || !sha256Text(root.getPath()).equals(record.filesRootPathSha256)
                || !state.exists || !state.regular || state.symlink || state.uid != record.noteUid
                || state.linkCount != record.noteLinkCount || state.linkCount != 1
                || state.device != record.noteDevice || state.inode != record.noteInode
                || state.size != record.noteSize || state.modifiedSeconds != record.noteModifiedSeconds
                || !note.getCanonicalPath().equals(state.canonicalPath)) return false;
        String digest = identities.sha256RegularNoFollow(note);
        Identity after = identities.lstat(note);
        return state.sameObject(after) && digest.equals(record.canonicalNoteSha256);
    }

    static Record captureCanonicalNote(File filesRoot, String noteId, String operationId,
                                        long createdAt, FileIdentityReader identities)
            throws IOException {
        if (!validNoteId(noteId) || operationId == null
                || !operationId.matches("[a-f0-9-]{36}") || createdAt <= 0)
            throw new IOException("DELETE_CAPTURE_INVALID_INPUT");
        File root = filesRoot.getCanonicalFile();
        Identity rootState = identities.lstat(root);
        File note = new File(new File(root, "notes"), noteId + ".json");
        Identity before = identities.lstat(note);
        if (!before.exists || !before.regular || before.symlink
                || before.uid != identities.currentUid() || before.linkCount != 1
                || before.size < 0 || !note.getCanonicalPath().equals(before.canonicalPath))
            throw new IOException("DELETE_NOTE_UNSAFE");
        String digest = identities.sha256RegularNoFollow(note);
        Identity after = identities.lstat(note);
        if (!before.sameObject(after) || !rootState.directory || rootState.symlink
                || rootState.uid != identities.currentUid())
            throw new IOException("DELETE_NOTE_CHANGED_DURING_CAPTURE");
        return new Record(noteId, operationId, digest, createdAt, root.getPath(), rootState, before);
    }

    static FileIdentityReader androidIdentityReader() {
        return new FileIdentityReader() {
            @Override public int currentUid() { return android.os.Process.myUid(); }
            @Override public Identity lstat(File file) throws IOException {
                try {
                    StructStat state = Os.lstat(file.getAbsolutePath());
                    return fromStat(file, state);
                } catch (ErrnoException missing) {
                    if (missing.errno == OsConstants.ENOENT) return absent(file);
                    throw new IOException("DELETE_MARKER_STAT_FAILED", missing);
                }
            }
            @Override public byte[] readRegularNoFollow(File file, int maximumBytes)
                    throws IOException {
                FileDescriptorHolder holder = null;
                try {
                    Identity namedBefore = fromStat(file, Os.lstat(file.getAbsolutePath()));
                    if (!namedBefore.regular || namedBefore.symlink || namedBefore.linkCount != 1
                            || namedBefore.uid != currentUid())
                        throw new IOException("DELETE_MARKER_PATH_UNSAFE");
                    holder = FileDescriptorHolder.open(file);
                    Identity before = fromStat(file, Os.fstat(holder.descriptor));
                    if (!before.regular || before.symlink || before.linkCount != 1
                            || before.uid != currentUid() || !namedBefore.sameObject(before))
                        throw new IOException("DELETE_MARKER_FD_UNSAFE");
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int total = 0;
                    int count;
                    while ((count = holder.input.read(buffer)) != -1) {
                        if (count == 0) continue;
                        total += count;
                        if (total > maximumBytes) throw new IOException("DELETE_MARKER_TOO_LARGE");
                        output.write(buffer, 0, count);
                    }
                    Identity after = fromStat(file, Os.fstat(holder.descriptor));
                    Identity namedAfter = fromStat(file, Os.lstat(file.getAbsolutePath()));
                    if (!before.sameObject(after) || !after.sameObject(namedAfter))
                        throw new IOException("DELETE_MARKER_FD_CHANGED");
                    return output.toByteArray();
                } catch (ErrnoException error) {
                    throw new IOException("DELETE_MARKER_READ_FAILED", error);
                } finally {
                    if (holder != null) holder.close();
                }
            }

            @Override public String sha256RegularNoFollow(File file) throws IOException {
                FileDescriptorHolder holder = null;
                try {
                    Identity namedBefore = fromStat(file, Os.lstat(file.getAbsolutePath()));
                    if (!namedBefore.regular || namedBefore.symlink || namedBefore.linkCount != 1
                            || namedBefore.uid != currentUid())
                        throw new IOException("DELETE_FILE_PATH_UNSAFE");
                    holder = FileDescriptorHolder.open(file);
                    Identity before = fromStat(file, Os.fstat(holder.descriptor));
                    if (!before.regular || before.symlink || before.linkCount != 1
                            || before.uid != currentUid() || !namedBefore.sameObject(before))
                        throw new IOException("DELETE_FILE_FD_UNSAFE");
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = holder.input.read(buffer)) != -1) {
                        if (count > 0) digest.update(buffer, 0, count);
                    }
                    Identity after = fromStat(file, Os.fstat(holder.descriptor));
                    Identity namedAfter = fromStat(file, Os.lstat(file.getAbsolutePath()));
                    if (!before.sameObject(after) || !after.sameObject(namedAfter))
                        throw new IOException("DELETE_FILE_FD_CHANGED");
                    return hex(digest.digest());
                } catch (ErrnoException error) {
                    throw new IOException("DELETE_FILE_READ_FAILED", error);
                } catch (NoSuchAlgorithmException error) {
                    throw new IOException("DELETE_FILE_HASH_UNAVAILABLE", error);
                } finally {
                    if (holder != null) holder.close();
                }
            }
        };
    }

    static Publisher androidPublisher() {
        return (temporary, canonical) -> {
            int errno = NativeNoReplace.renameNoReplace(
                    temporary.getParentFile().getAbsolutePath(), temporary.getName(),
                    canonical.getParentFile().getAbsolutePath(), canonical.getName());
            if (errno != 0) throw new IOException("DELETE_MARKER_PUBLISH_FAILED_" + errno);
        };
    }

    static DirectorySync androidDirectorySync() {
        return directory -> {
            java.io.FileDescriptor descriptor = null;
            try {
                Identity namedBefore = fromStat(directory, Os.lstat(directory.getAbsolutePath()));
                if (!namedBefore.directory || namedBefore.symlink
                        || namedBefore.uid != android.os.Process.myUid())
                    throw new IOException("DELETE_MARKER_DIRECTORY_UNSAFE");
                descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY
                        | OsConstants.O_NOFOLLOW | O_CLOEXEC_COMPAT, 0);
                Identity opened = fromStat(directory, Os.fstat(descriptor));
                if (!opened.directory || opened.symlink || opened.uid != android.os.Process.myUid()
                        || !namedBefore.sameObject(opened))
                    throw new IOException("DELETE_MARKER_DIRECTORY_FD_MISMATCH");
                Os.fsync(descriptor);
                Identity afterFd = fromStat(directory, Os.fstat(descriptor));
                Identity afterPath = fromStat(directory, Os.lstat(directory.getAbsolutePath()));
                if (!opened.sameObject(afterFd) || !afterFd.sameObject(afterPath))
                    throw new IOException("DELETE_MARKER_DIRECTORY_CHANGED");
            } catch (ErrnoException error) {
                throw new IOException("DELETE_MARKER_DIRECTORY_SYNC_FAILED", error);
            } finally {
                if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException ignored) { }
            }
        };
    }

    private static Record readCanonical(File filesRoot, String noteId,
                                        FileIdentityReader identities,
                                        DirectorySync directorySync) throws IOException {
        File directory = new File(filesRoot, DIRECTORY);
        File canonical = markerFile(directory, noteId);
        Identity before = requireRegularOwned(canonical, identities, filesRoot, true);
        if (before.linkCount != 1 || before.size <= 0 || before.size > MAX_MARKER_BYTES)
            throw new IOException("DELETE_MARKER_UNSAFE");
        byte[] bytes = identities.readRegularNoFollow(canonical, MAX_MARKER_BYTES);
        Identity after = requireRegularOwned(canonical, identities, filesRoot, true);
        if (!before.sameObject(after) || bytes.length != before.size)
            throw new IOException("DELETE_MARKER_CHANGED");
        Record record = decode(bytes);
        if (!noteId.equals(record.noteId)) throw new IOException("DELETE_MARKER_ID_MISMATCH");
        if (record.hasCanonicalNoteIdentity) {
            Identity root = identities.lstat(filesRoot.getCanonicalFile());
            if (!root.directory || root.symlink || root.uid != identities.currentUid()
                    || root.device != record.filesRootDevice || root.inode != record.filesRootInode
                    || !sha256Text(filesRoot.getCanonicalFile().getPath())
                    .equals(record.filesRootPathSha256))
                throw new IOException("DELETE_MARKER_ROOT_IDENTITY_MISMATCH");
        }
        return record;
    }

    private static Identity requireRegularOwned(File file, FileIdentityReader identities,
                                                File filesRoot, boolean marker)
            throws IOException {
        Identity identity = identities.lstat(file);
        if (!identity.exists || identity.symlink || !identity.regular
                || identity.uid != identities.currentUid() || !file.getCanonicalPath()
                .equals(identity.canonicalPath)) throw new IOException("DELETE_MARKER_UNSAFE");
        File canonicalRoot = filesRoot.getCanonicalFile();
        File canonicalParent = file.getParentFile().getCanonicalFile();
        if (!canonicalParent.equals(new File(canonicalRoot, DIRECTORY).getCanonicalFile()))
            throw new IOException("DELETE_MARKER_PATH_UNSAFE");
        if (marker && identity.linkCount != 1) throw new IOException("DELETE_MARKER_LINK_UNSAFE");
        return identity;
    }

    private static void ensureDirectory(File filesRoot, File directory,
                                        FileIdentityReader identities,
                                        DirectorySync directorySync) throws IOException {
        if (filesRoot == null || identities == null) throw new IOException("DELETE_MARKER_ROOT_INVALID");
        File canonicalRoot = filesRoot.getCanonicalFile();
        Identity rootIdentity = identities.lstat(canonicalRoot);
        if (!rootIdentity.exists || !rootIdentity.directory || rootIdentity.symlink
                || rootIdentity.uid != identities.currentUid()
                || !canonicalRoot.getPath().equals(rootIdentity.canonicalPath))
            throw new IOException("DELETE_MARKER_ROOT_UNSAFE");
        if (!directory.exists() && !directory.mkdirs()) {
            Identity afterFailedCreate = identities.lstat(directory);
            if (!afterFailedCreate.exists) throw new IOException("DELETE_MARKER_DIRECTORY_CREATE_FAILED");
        }
        Identity state = identities.lstat(directory);
        if (!state.exists || !state.directory || state.symlink || state.uid != identities.currentUid()
                || !directory.getCanonicalPath().equals(state.canonicalPath))
            throw new IOException("DELETE_MARKER_DIRECTORY_UNSAFE");
        // Do not cache durability from coarse directory metadata; another process can
        // change entries without a reliable mtime/size witness.
        directorySync.sync(canonicalRoot);
        directorySync.sync(directory);
    }

    private static boolean verifyReadDirectory(File filesRoot, File directory,
                                               FileIdentityReader identities,
                                               DirectorySync directorySync) throws IOException {
        if (filesRoot == null || identities == null || directorySync == null)
            throw new IOException("DELETE_MARKER_ROOT_INVALID");
        File canonicalRoot = filesRoot.getCanonicalFile();
        Identity root = identities.lstat(canonicalRoot);
        if (!root.exists || !root.directory || root.symlink || root.uid != identities.currentUid()
                || !canonicalRoot.getPath().equals(root.canonicalPath))
            throw new IOException("DELETE_MARKER_ROOT_UNSAFE");
        Identity child = identities.lstat(directory);
        if (!child.exists) return false;
        if (!child.directory || child.symlink || child.uid != identities.currentUid()
                || !directory.getCanonicalPath().equals(child.canonicalPath))
            throw new IOException("DELETE_MARKER_DIRECTORY_UNSAFE");
        directorySync.sync(canonicalRoot);
        directorySync.sync(directory);
        return true;
    }

    private static File stagingFile(File directory, Record record) {
        return new File(directory, record.noteId + ".tmp-" + record.operationId);
    }

    private static void deleteTemporaryIfSame(File file, Identity expected,
                                              FileIdentityReader identities,
                                              TemporaryWriter writer) {
        if (file == null || expected == null) return;
        try {
            Identity current = identities.lstat(file);
            if (expected.sameObject(current) && current.regular && current.linkCount == 1)
                writer.deleteOwnedTemp(file, expected, identities);
        } catch (Exception ignored) { }
    }

    private static void writeNewTemporary(File file, byte[] encoded) throws IOException {
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(file.getAbsolutePath(), OsConstants.O_WRONLY
                    | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW
                    | O_CLOEXEC_COMPAT, 0600);
            try (FileOutputStream output = new FileOutputStream(descriptor)) {
                output.write(encoded);
                output.flush();
                output.getFD().sync();
            }
        } catch (ErrnoException error) {
            throw new IOException("DELETE_MARKER_TEMP_CREATE_FAILED", error);
        }
    }

    static TemporaryWriter androidTemporaryWriter() {
        return new TemporaryWriter() {
            @Override public void writeNewSynced(File temporary, byte[] contents)
                    throws IOException { writeNewTemporary(temporary, contents); }
            @Override public void deleteOwnedTemp(File temporary, Identity expected,
                                                  FileIdentityReader identities)
                    throws IOException {
                Identity current = identities.lstat(temporary);
                if (!expected.sameObject(current) || !current.regular || current.linkCount != 1)
                    throw new IOException("DELETE_MARKER_TEMP_CHANGED");
                try { Os.remove(temporary.getAbsolutePath()); }
                catch (ErrnoException error) { throw new IOException("DELETE_MARKER_TEMP_REMOVE_FAILED",error); }
            }
        };
    }

    private static byte[] encode(Record record) throws IOException {
        String value = HEADER + "\n" + record.noteId + "\n" + record.operationId + "\n"
                + record.canonicalNoteSha256 + "\n" + record.createdAt + "\n"
                + (record.hasCanonicalNoteIdentity ? "1" : "0") + "\n"
                + (record.hasCanonicalNoteIdentity ? record.filesRootPathSha256 : "-") + "\n"
                + record.filesRootDevice + "\n" + record.filesRootInode + "\n"
                + record.noteUid + "\n" + record.noteLinkCount + "\n"
                + record.noteDevice + "\n" + record.noteInode + "\n" + record.noteSize + "\n"
                + record.noteModifiedSeconds + "\n";
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > MAX_MARKER_BYTES) throw new IOException("DELETE_MARKER_TOO_LARGE");
        return bytes;
    }

    private static Record decode(byte[] bytes) throws IOException {
        String value = new String(bytes, StandardCharsets.US_ASCII);
        String[] lines = value.split("\n", -1);
        if (lines.length != 16 || !HEADER.equals(lines[0]) || !lines[15].isEmpty())
            throw new IOException("DELETE_MARKER_INVALID");
        try {
            long created = Long.parseLong(lines[4]);
            boolean hasIdentity;
            if ("1".equals(lines[5])) hasIdentity = true;
            else if ("0".equals(lines[5])) hasIdentity = false;
            else throw new IOException("DELETE_MARKER_INVALID");
            Record record = new Record(lines[1], lines[2], lines[3], created, hasIdentity,
                    lines[6], Long.parseLong(lines[7]), Long.parseLong(lines[8]),
                    Integer.parseInt(lines[9]), Long.parseLong(lines[10]),
                    Long.parseLong(lines[11]), Long.parseLong(lines[12]),
                    Long.parseLong(lines[13]), Long.parseLong(lines[14]));
            if (!validRecord(record)) throw new IOException("DELETE_MARKER_INVALID");
            return record;
        } catch (NumberFormatException error) {
            throw new IOException("DELETE_MARKER_INVALID", error);
        }
    }

    private static boolean validRecord(Record record) {
        return record != null && validNoteId(record.noteId)
                && record.operationId != null && record.operationId.matches("[a-f0-9-]{36}")
                && isHexSha256(record.canonicalNoteSha256) && record.createdAt > 0
                && (!record.hasCanonicalNoteIdentity || (isHexSha256(record.filesRootPathSha256)
                && record.filesRootDevice >= 0 && record.filesRootInode > 0 && record.noteUid >= 0
                && record.noteLinkCount == 1 && record.noteDevice >= 0 && record.noteInode > 0
                && record.noteSize >= 0 && record.noteModifiedSeconds >= 0))
                && (record.hasCanonicalNoteIdentity || ("-".equals(record.filesRootPathSha256)
                && record.filesRootDevice == 0 && record.filesRootInode == 0
                && record.noteUid == 0 && record.noteLinkCount == 0 && record.noteDevice == 0
                && record.noteInode == 0 && record.noteSize == 0
                && record.noteModifiedSeconds == 0));
    }

    private static boolean validNoteId(String noteId) {
        return noteId != null && noteId.matches("[A-Za-z0-9_-]{1,160}");
    }

    private static boolean isHexSha256(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static boolean sameRecord(Record a, Record b) {
        return a != null && b != null && a.noteId.equals(b.noteId)
                && a.operationId.equals(b.operationId)
                && a.canonicalNoteSha256.equals(b.canonicalNoteSha256)
                && a.createdAt == b.createdAt
                && a.hasCanonicalNoteIdentity == b.hasCanonicalNoteIdentity
                && equal(a.filesRootPathSha256,b.filesRootPathSha256)
                && a.filesRootDevice==b.filesRootDevice && a.filesRootInode==b.filesRootInode
                && a.noteUid==b.noteUid && a.noteLinkCount==b.noteLinkCount
                && a.noteDevice==b.noteDevice && a.noteInode==b.noteInode
                && a.noteSize==b.noteSize && a.noteModifiedSeconds==b.noteModifiedSeconds;
    }

    private static File markerFile(File directory, String noteId) {
        return new File(directory, noteId + ".committed");
    }

    private static Identity absent(File file) {
        return new Identity(false, false, false, false, -1, 0, -1, -1, 0, 0,
                file.getAbsolutePath());
    }

    private static Identity fromStat(File file, StructStat state) throws IOException {
        if (state == null) throw new IOException("DELETE_STAT_UNAVAILABLE");
        int type = state.st_mode & OsConstants.S_IFMT;
        return new Identity(true, type == OsConstants.S_IFREG, type == OsConstants.S_IFDIR,
                type == OsConstants.S_IFLNK, state.st_uid, state.st_nlink, state.st_dev,
                state.st_ino, state.st_size, state.st_mtime, file.getCanonicalPath());
    }

    private static CommitResult result(CommitStatus status, Record record, String error) {
        return new CommitResult(status, record, error);
    }

    private static String code(Exception error, String fallback) {
        String message = error.getMessage();
        return message != null && message.matches("[A-Z0-9_]{1,80}") ? message : fallback;
    }

    private static boolean equal(Object a, Object b) { return a == b || (a != null && a.equals(b)); }

    private static String sha256Text(String value) {
        try { return hex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private static String sha256(byte[] value) throws IOException {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("DELETE_MARKER_HASH_UNAVAILABLE", unavailable);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT,
                "%02x", value & 0xff));
        return result.toString();
    }

    private static final class FileDescriptorHolder {
        final java.io.FileDescriptor descriptor;
        final FileInputStream input;
        private FileDescriptorHolder(java.io.FileDescriptor descriptor, FileInputStream input) {
            this.descriptor=descriptor; this.input=input;
        }
        static FileDescriptorHolder open(File file) throws ErrnoException {
            java.io.FileDescriptor descriptor = Os.open(file.getAbsolutePath(), OsConstants.O_RDONLY
                    | OsConstants.O_NOFOLLOW | O_CLOEXEC_COMPAT | OsConstants.O_NONBLOCK, 0);
            return new FileDescriptorHolder(descriptor, new FileInputStream(descriptor));
        }
        void close() { try { input.close(); } catch (IOException ignored) { } }
    }
}
