package com.padnote.android;

import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Strict r1 archive codec. It validates bytes and stages resources; it does not import them into NoteStore. */
public final class LibraryBackupArchive {
    public static final long MAX_ARCHIVE_BYTES = LibraryBackupManifest.MAX_ARCHIVE_BYTES;
    public static final long MAX_EXPANDED_BYTES = LibraryBackupManifest.MAX_TOTAL_RESOURCE_BYTES;
    public static final int MAX_ENTRY_COUNT = LibraryBackupManifest.MAX_RESOURCES + 1;
    private static final int CHUNK = 64 * 1024;
    private static final int EOCD_MAX = 65_557;
    private static final String OWNER_FILE = ".padnote-stage-owner.json";
    private static final String TRANSFER_FILE = ".padnote-stage-transfer.json";
    private static final String MANIFEST_FILE = ".padnote-stage-manifest.json";

    private LibraryBackupArchive() { }

    public interface Cancellation { boolean isCancelled(); }
    public interface Progress { void onProgress(long completedBytes, long totalBytes); }

    public static final class ArchiveResult {
        public final String sha256;
        public final long byteLength;
        ArchiveResult(String sha256, long byteLength) { this.sha256 = sha256; this.byteLength = byteLength; }
    }

    /** A stage owns its private directory until transferOwnership succeeds. */
    public static final class StagedArchive implements Closeable {
        private final File directory;
        private final LibraryBackupManifest manifest;
        private final String archiveSha256;
        private final Map<String, File> resources;
        private final SafeFiles files;
        private boolean transferred;
        private boolean closed;
        private String transactionId;
        private String transferMarkerSha256;

        private StagedArchive(File directory, LibraryBackupManifest manifest, String archiveSha256,
                Map<String, File> resources, SafeFiles files) {
            this.directory = directory;
            this.manifest = manifest;
            this.archiveSha256 = archiveSha256;
            this.resources = Collections.unmodifiableMap(new HashMap<>(resources));
            this.files = files;
        }
        public LibraryBackupManifest manifest() { return manifest; }
        public File resourceFile(String resourceId) throws IOException {
            File result = resources.get(resourceId);
            if (result == null || closed) throw new IOException("RESOURCE_NOT_STAGED");
            return result;
        }
        public Map<String, File> resourceFiles() { return resources; }
        public String archiveSha256() { return archiveSha256; }
        public File directory() { return directory; }

        /**
         * Durable handoff after the caller has committed its own journal. The marker is exclusive and
         * includes only transaction id and validated content digests. On failure this object keeps ownership.
         */
        public synchronized String transferOwnership(String transactionId) throws IOException {
            if (closed || transferred || !isCanonicalUuid(transactionId))
                throw new IOException("STAGE_TRANSFER_INVALID");
            Map<String, Object> marker = new HashMap<>();
            marker.put("schema_version", 1);
            marker.put("transaction_id", transactionId.toLowerCase(java.util.Locale.ROOT));
            marker.put("archive_sha256", archiveSha256);
            marker.put("resource_manifest_sha256", sha256(manifest.toJsonBytes()));
            byte[] bytes = LibraryBackupJson.encode(marker);
            File markerFile = new File(directory, TRANSFER_FILE);
            try (FileOutputStream out = files.createExclusive(markerFile)) {
                out.write(bytes);
                out.getFD().sync();
            }
            files.syncDirectory(directory);
            transferred = true;
            this.transactionId = transactionId.toLowerCase(java.util.Locale.ROOT);
            transferMarkerSha256 = sha256(bytes);
            return transferMarkerSha256;
        }

        /**
         * Deletes a transferred stage only after rechecking its journal binding and every staged
         * resource. Ordinary close deliberately retains transferred stages for transaction resume.
         */
        public synchronized void cleanupTransferred() throws IOException {
            if (closed || !transferred || transactionId == null || transferMarkerSha256 == null)
                throw new IOException("STAGE_CLEANUP_NOT_OWNER");
            verifyTransferredStage(directory, transactionId, archiveSha256, transferMarkerSha256, files);
            files.deleteOwnedTree(directory);
            closed = true;
        }

        /** Compatibility convenience for a non-transactional caller; explicit handoff is preferred. */
        public synchronized void retain() { transferred = true; }

        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            if (!transferred) files.deleteOwnedTree(directory);
        }
    }

    /** Injectable boundary for JVM tests. Android callers use the default handle-based implementation. */
    interface SafeFiles {
        interface PublishLock extends Closeable {}
        FileIdentity inspect(File path, boolean directory) throws IOException;
        void validatePrivateDirectory(File path) throws IOException;
        Seekable openRead(File path) throws IOException;
        /** One cross-process lock for a destination path; the caller holds it over check/reserve/rename. */
        default PublishLock lockPublish(File destination) throws IOException { throw new IOException("PUBLISH_LOCK_UNAVAILABLE"); }
        /** Recognizes only durable empty sibling lock files created by lockPublish. */
        default boolean isPersistentPublishLockArtifact(File path)throws IOException {
            if(path==null||!path.getName().matches("\\.padnote-publish-[0-9a-f]{64}\\.lock"))return false;
            FileIdentity id=inspect(path,false);return id.links==1&&id.size==0;
        }
        /** Legacy material-store surface; removed when Stage B changes its journal protocol. */
        FileIdentity inspectOwnedPair(File path) throws IOException;
        Seekable openOwnedPair(File path) throws IOException;
        void linkNoReplace(File from, File to) throws IOException;
        /** No-follow missing check used to distinguish safe promotion crash states. */
        default boolean isAbsentNoFollow(File path) throws IOException {
            try { inspect(path, false); return false; }
            catch (IOException failure) { if (!path.exists()) return true; throw failure; }
        }
        FileOutputStream createExclusive(File path) throws IOException;
        void mkdirExclusive(File path) throws IOException;
        void syncDirectory(File path) throws IOException;
        /** Re-fsync an already validated regular file after an interrupted write/sync boundary. */
        default void syncExistingFile(File path, FileIdentity expected) throws IOException { throw new IOException("FILE_SYNC_UNAVAILABLE"); }
        void deleteOwnedTree(File path) throws IOException;
        /** Atomically replace only the exact exclusive marker created for this publication. */
        default void replaceOwnedMarker(File from, File to, byte[] marker, FileIdentity markerIdentity) throws IOException { throw new IOException("PUBLISH_REPLACE_UNAVAILABLE"); }
        /** Atomically moves a fully synced file to a currently absent destination. */
        default void moveNoReplace(File from, File to, byte[] expectedBytes) throws IOException { throw new IOException("PUBLISH_MOVE_UNAVAILABLE"); }
        void unlink(File path) throws IOException;
        boolean matchesFile(File path, FileIdentity expected) throws IOException;
    }

    static final class FileIdentity {
        final long device, inode, size, mtimeNanos, ctimeNanos, links;
        final boolean directory;
        FileIdentity(long device, long inode, long size, long mtimeNanos, long ctimeNanos, long links, boolean directory) {
            this.device=device;this.inode=inode;this.size=size;this.mtimeNanos=mtimeNanos;this.ctimeNanos=ctimeNanos;this.links=links;this.directory=directory;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof FileIdentity)) return false;
            FileIdentity x=(FileIdentity)other;
            return device==x.device&&inode==x.inode&&size==x.size&&mtimeNanos==x.mtimeNanos&&ctimeNanos==x.ctimeNanos&&links==x.links&&directory==x.directory;
        }
        @Override public int hashCode() { return (int)(device^inode^size^mtimeNanos^ctimeNanos^links); }
    }

    interface Seekable extends Closeable {
        long size() throws IOException;
        void readFully(long offset, byte[] target, int targetOffset, int length) throws IOException;
        FileIdentity openedIdentity() throws IOException;
        FileIdentity currentPathIdentity() throws IOException;
    }

    private static final SafeFiles SYSTEM_FILES = new AndroidSafeFiles();

    private static FileIdentity createReservationMarker(SafeFiles files,File destination,byte[] marker)throws IOException {
        FileIdentity created=null;
        try {
            try(FileOutputStream out=files.createExclusive(destination)) {
                created=files.inspect(destination,false);
                out.write(marker);out.flush();out.getFD().sync();
            }
            FileIdentity written=verifyReservationMarker(files,destination,marker,null);
            files.syncDirectory(destination.getAbsoluteFile().getParentFile());
            return written;
        } catch(IOException failure) {
            if(created!=null)try{if(files.matchesFile(destination,created))files.unlink(destination);}catch(IOException ignored){}
            throw failure;
        }
    }

    private static FileIdentity verifyReservationMarker(SafeFiles files,File path,byte[] expected,
            FileIdentity expectedIdentity)throws IOException {
        FileIdentity before=files.inspect(path,false);
        if(before.links!=1||before.directory||before.size!=expected.length||
                (expectedIdentity!=null&&!expectedIdentity.equals(before)))throw new IOException("PUBLISH_MARKER_CHANGED");
        byte[] actual=new byte[expected.length];
        try(Seekable input=files.openRead(path)) {
            if(!before.equals(input.openedIdentity())||input.size()!=expected.length)throw new IOException("PUBLISH_MARKER_CHANGED");
            input.readFully(0,actual,0,actual.length);
            if(!before.equals(input.openedIdentity())||!before.equals(input.currentPathIdentity()))throw new IOException("PUBLISH_MARKER_CHANGED");
        }
        if(!java.util.Arrays.equals(actual,expected))throw new IOException("PUBLISH_MARKER_CHANGED");
        return before;
    }

    private static void verifyFile(SafeFiles files,File path,long expectedSize,String expectedSha,long expectedLinks)throws IOException {
        FileIdentity before=files.inspect(path,false);
        if(before.directory||before.links!=expectedLinks||before.size!=expectedSize)throw new IOException("PUBLISH_FILE_CHANGED");
        MessageDigest sha=digest();byte[] buffer=new byte[65536];long offset=0;
        try(Seekable input=files.openRead(path)) {
            if(!before.equals(input.openedIdentity())||input.size()!=expectedSize)throw new IOException("PUBLISH_FILE_CHANGED");
            while(offset<expectedSize){int count=(int)Math.min(buffer.length,expectedSize-offset);input.readFully(offset,buffer,0,count);sha.update(buffer,0,count);offset+=count;}
            if(!before.equals(input.openedIdentity())||!before.equals(input.currentPathIdentity()))throw new IOException("PUBLISH_FILE_CHANGED");
        }
        if(!hex(sha.digest()).equals(expectedSha))throw new IOException("PUBLISH_HASH_MISMATCH");
    }

    public static StagedArchive stage(File archive, File privateStagingParent, Cancellation cancellation,
            Progress progress) throws IOException {
        return stage(archive, privateStagingParent, cancellation, progress, SYSTEM_FILES);
    }

    static StagedArchive stage(File archive, File privateStagingParent, Cancellation cancellation,
            Progress progress, SafeFiles files) throws IOException {
        checkCancelled(cancellation);
        FileIdentity archiveBefore = files.inspect(archive, false);
        if (archiveBefore.size < 22 || archiveBefore.size > MAX_ARCHIVE_BYTES) throw new IOException("ARCHIVE_SIZE_LIMIT");
        files.validatePrivateDirectory(privateStagingParent);
        File stage = new File(privateStagingParent, "library-backup-" + UUID.randomUUID().toString());
        files.mkdirExclusive(stage);
        boolean keep = false;
        try {
            files.validatePrivateDirectory(stage);
            String archiveHash;
            Map<String, Entry> entries;
            LibraryBackupManifest manifest;
            Map<String, File> staged = new HashMap<>();
            try (Seekable input = files.openRead(archive)) {
                if (!archiveBefore.equals(input.openedIdentity()) || input.size() != archiveBefore.size) throw new IOException("ARCHIVE_CHANGED");
                archiveHash = hashSeekable(input, cancellation);
                entries = scan(input);
                Entry manifestEntry = entries.get("manifest.json");
                if (manifestEntry == null || manifestEntry.expanded > LibraryBackupManifest.MAX_MANIFEST_BYTES)
                    throw new IOException("MANIFEST_MISSING");
                byte[] manifestBytes = readMember(input, manifestEntry, null, LibraryBackupManifest.MAX_MANIFEST_BYTES, cancellation, null);
                manifest = LibraryBackupManifest.parse(manifestBytes);
                Set<String> expected = new HashSet<>();
                expected.add("manifest.json");
                for (LibraryBackupManifest.Resource resource : manifest.resources) expected.add(resource.member);
                if (!entries.keySet().equals(expected)) throw new IOException("ARCHIVE_MEMBER_SET_MISMATCH");
                long expandedTotal = 0;
                for (LibraryBackupManifest.Resource resource : manifest.resources) {
                    if (resource.byteLength > MAX_EXPANDED_BYTES - expandedTotal) throw new IOException("EXPANDED_SIZE_LIMIT");
                    expandedTotal += resource.byteLength;
                }
                long done = 0;
                for (LibraryBackupManifest.Resource resource : manifest.resources) {
                    checkCancelled(cancellation);
                    Entry entry = entries.get(resource.member);
                    if (entry == null || entry.expanded != resource.byteLength) throw new IOException("RESOURCE_LENGTH_MISMATCH");
                    File target = new File(stage, resource.resourceId + ".bin");
                    try (FileOutputStream output = files.createExclusive(target)) {
                        DigestOutput digest = new DigestOutput(output);
                        readMember(input, entry, digest, limit(resource.role), cancellation, digest);
                        output.getFD().sync();
                        if (digest.count != resource.byteLength || !digest.shaHex().equals(resource.sha256)) throw new IOException("RESOURCE_HASH_MISMATCH");
                    }
                    staged.put(resource.resourceId, target);
                    done += resource.byteLength;
                    if (progress != null) progress.onProgress(done, expandedTotal);
                }
                validatePayloadMetadata(manifest, staged, files);
                if (!archiveBefore.equals(input.openedIdentity()) || !archiveBefore.equals(input.currentPathIdentity()) || !archiveHash.equals(hashSeekable(input, cancellation))) throw new IOException("ARCHIVE_CHANGED");
            }
            Map<String, Object> owner = new HashMap<>();
            owner.put("schema_version", 1); owner.put("archive_sha256", archiveHash);
            owner.put("resource_manifest_sha256", sha256(manifest.toJsonBytes()));
            try (FileOutputStream out = files.createExclusive(new File(stage, MANIFEST_FILE))) {
                out.write(manifest.toJsonBytes()); out.getFD().sync();
            }
            try (FileOutputStream out = files.createExclusive(new File(stage, OWNER_FILE))) {
                out.write(LibraryBackupJson.encode(owner)); out.getFD().sync();
            }
            files.syncDirectory(stage);
            keep = true;
            return new StagedArchive(stage, manifest, archiveHash, staged, files);
        } finally {
            if (!keep) files.deleteOwnedTree(stage);
        }
    }

    /** Reopens a journal-owned stage after restart without taking it into a UI/listing lifecycle. */
    public static StagedArchive openTransferred(File stageDirectory, String transactionId,
            String archiveSha256, String transferMarkerSha256) throws IOException {
        return openTransferred(stageDirectory, transactionId, archiveSha256, transferMarkerSha256, SYSTEM_FILES);
    }

    static StagedArchive openTransferred(File stageDirectory, String transactionId, String archiveSha256,
            String transferMarkerSha256, SafeFiles files) throws IOException {
        if (stageDirectory == null || !isCanonicalUuid(transactionId) || !isSha256(archiveSha256) ||
                !isSha256(transferMarkerSha256)) throw new IOException("STAGE_TRANSFER_INVALID");
        String tx = transactionId.toLowerCase(java.util.Locale.ROOT);
        files.validatePrivateDirectory(stageDirectory);
        FileIdentity directoryBefore = files.inspect(stageDirectory, true);
        byte[] manifestBytes = readBounded(files, new File(stageDirectory, MANIFEST_FILE), LibraryBackupManifest.MAX_MANIFEST_BYTES);
        byte[] ownerBytes = readBounded(files, new File(stageDirectory, OWNER_FILE), 4096);
        byte[] transferBytes = readBounded(files, new File(stageDirectory, TRANSFER_FILE), 4096);
        LibraryBackupManifest manifest = LibraryBackupManifest.parse(manifestBytes);
        Map<String, Object> owner = LibraryBackupJson.parseCheckedObject(ownerBytes, 4096);
        LibraryBackupJson.exactKeys(owner, "STAGE_OWNER_FIELDS", "schema_version", "archive_sha256", "resource_manifest_sha256");
        Map<String, Object> transfer = LibraryBackupJson.parseCheckedObject(transferBytes, 4096);
        LibraryBackupJson.exactKeys(transfer, "STAGE_TRANSFER_FIELDS", "schema_version", "transaction_id", "archive_sha256", "resource_manifest_sha256");
        String manifestSha = sha256(manifest.toJsonBytes());
        if (LibraryBackupJson.integer(owner.get("schema_version"), 1, 1, "STAGE_OWNER_SCHEMA") != 1 ||
                !archiveSha256.equals(LibraryBackupJson.string(owner.get("archive_sha256"), "STAGE_OWNER_ARCHIVE")) ||
                !manifestSha.equals(LibraryBackupJson.string(owner.get("resource_manifest_sha256"), "STAGE_OWNER_MANIFEST")) ||
                LibraryBackupJson.integer(transfer.get("schema_version"), 1, 1, "STAGE_TRANSFER_SCHEMA") != 1 ||
                !tx.equals(LibraryBackupJson.string(transfer.get("transaction_id"), "STAGE_TRANSFER_TRANSACTION")) ||
                !archiveSha256.equals(LibraryBackupJson.string(transfer.get("archive_sha256"), "STAGE_TRANSFER_ARCHIVE")) ||
                !manifestSha.equals(LibraryBackupJson.string(transfer.get("resource_manifest_sha256"), "STAGE_TRANSFER_MANIFEST")) ||
                !sha256(transferBytes).equals(transferMarkerSha256)) throw new IOException("STAGE_TRANSFER_BINDING_MISMATCH");

        Set<String> expected = new HashSet<>();
        expected.add(OWNER_FILE); expected.add(TRANSFER_FILE); expected.add(MANIFEST_FILE);
        Map<String, File> resources = new HashMap<>();
        for (LibraryBackupManifest.Resource resource : manifest.resources) {
            String name = resource.resourceId + ".bin";
            if (!expected.add(name)) throw new IOException("STAGE_RESOURCE_SET");
            File path = new File(stageDirectory, name);
            FileIdentity before = files.inspect(path, false);
            if (before.size != resource.byteLength) throw new IOException("STAGE_RESOURCE_LENGTH");
            try (Seekable input = files.openRead(path)) {
                if (!before.equals(input.openedIdentity()) || input.size() != resource.byteLength ||
                        !hashSeekable(input, null).equals(resource.sha256) ||
                        !before.equals(input.openedIdentity()) || !before.equals(input.currentPathIdentity()))
                    throw new IOException("STAGE_RESOURCE_CHANGED");
            }
            resources.put(resource.resourceId, path);
        }
        validatePayloadMetadata(manifest, resources, files);
        String[] children = stageDirectory.list();
        if (children == null || children.length != expected.size()) throw new IOException("STAGE_RESOURCE_SET");
        Set<String> actual = new HashSet<>(Arrays.asList(children));
        if (actual.size() != children.length || !actual.equals(expected) ||
                !directoryBefore.equals(files.inspect(stageDirectory, true))) throw new IOException("STAGE_RESOURCE_SET");
        StagedArchive result = new StagedArchive(stageDirectory, manifest, archiveSha256, resources, files);
        result.transferred = true;
        result.transactionId = tx;
        result.transferMarkerSha256 = transferMarkerSha256;
        return result;
    }

    private static void verifyTransferredStage(File directory, String transactionId, String archiveSha256,
            String markerSha256, SafeFiles files) throws IOException {
        StagedArchive verified = openTransferred(directory, transactionId, archiveSha256, markerSha256, files);
        // The temporary verification handle must not assume transaction ownership or clean up.
        verified.closed = true;
    }

    public static ArchiveResult write(LibraryBackupManifest manifest, Map<String, File> resourceFiles,
            File destination, Cancellation cancellation, Progress progress) throws IOException {
        return write(manifest, resourceFiles, destination, cancellation, progress, SYSTEM_FILES);
    }

    static ArchiveResult write(LibraryBackupManifest manifest, Map<String, File> resourceFiles,
            File destination, Cancellation cancellation, Progress progress, SafeFiles files) throws IOException {
        manifest.validate();
        if (resourceFiles == null || resourceFiles.size() != manifest.resources.size()) throw new IOException("RESOURCE_FILE_SET");
        for (LibraryBackupManifest.Resource resource : manifest.resources) if (!resourceFiles.containsKey(resource.resourceId)) throw new IOException("RESOURCE_FILE_SET");
        File parent = destination.getAbsoluteFile().getParentFile();
        files.validatePrivateDirectory(parent);
        if (existsNoFollow(destination, files)) throw new IOException("DESTINATION_EXISTS");
        File temp = new File(parent, ".padnote-backup-" + UUID.randomUUID() + ".partial");
        byte[] manifestBytes = manifest.toJsonBytes();
        if (manifestBytes.length > LibraryBackupManifest.MAX_MANIFEST_BYTES) throw new IOException("MANIFEST_SIZE_LIMIT");
        long expected = manifestBytes.length;
        for (LibraryBackupManifest.Resource resource : manifest.resources) expected += resource.byteLength;
        if (expected > MAX_ARCHIVE_BYTES) throw new IOException("ARCHIVE_SIZE_LIMIT");
        MessageDigest archiveDigest = digest();
        boolean installed = false;
        FileIdentity tempIdentity = null;
        FileIdentity tempOwnershipIdentity = null;
        FileIdentity markerIdentity = null;
        byte[] markerBytes = null;
        SafeFiles.PublishLock publishLock = null;
        try {
            try (FileOutputStream raw = files.createExclusive(temp)) {
                tempOwnershipIdentity = files.inspect(temp, false);
                CountingDigestOutput output = new CountingDigestOutput(raw, archiveDigest, MAX_ARCHIVE_BYTES);
                ZipWriter zip = new ZipWriter(output);
                zip.add("manifest.json", manifestBytes, null, null, cancellation);
                long completed = manifestBytes.length;
                for (LibraryBackupManifest.Resource resource : manifest.resources) {
                    checkCancelled(cancellation);
                    File source = resourceFiles.get(resource.resourceId);
                    FileIdentity before = files.inspect(source, false);
                    if (before.size != resource.byteLength) throw new IOException("SOURCE_LENGTH_MISMATCH");
                    try (Seekable input = files.openRead(source)) {
                        if (!before.equals(input.openedIdentity())) throw new IOException("SOURCE_CHANGED");
                        zip.add(resource.member, null, input, resource, cancellation);
                        if (!before.equals(input.openedIdentity()) || !before.equals(input.currentPathIdentity())) throw new IOException("SOURCE_CHANGED");
                    }
                    completed += resource.byteLength;
                    if (progress != null) progress.onProgress(completed, expected);
                }
                zip.finish();
                raw.getFD().sync();
                if (output.count > MAX_ARCHIVE_BYTES) throw new IOException("ARCHIVE_SIZE_LIMIT");
            }
            tempIdentity = files.inspect(temp, false);
            String finalSha=hex(archiveDigest.digest());
            publishLock=files.lockPublish(destination);
            files.validatePrivateDirectory(parent);
            if(existsNoFollow(destination,files))throw new IOException("DESTINATION_EXISTS");
            markerBytes=reservationMarker("archive",UUID.randomUUID().toString(),destination.getName(),
                    outputCount(tempIdentity),finalSha,null,null,null,null,null);
            markerIdentity=createReservationMarker(files,destination,markerBytes);
            files.replaceOwnedMarker(temp,destination,markerBytes,markerIdentity);
            FileIdentity finalId=files.inspect(destination,false);
            if(finalId.size!=tempIdentity.size||finalId.links!=1)throw new IOException("DESTINATION_CHANGED");
            verifyFile(files,destination,finalId.size,finalSha,1);
            files.syncDirectory(parent);
            installed = true;
            return new ArchiveResult(finalSha, tempIdentity.size);
        } finally {
            if (!installed) {
                if(markerBytes!=null&&markerIdentity!=null) {
                    try { verifyReservationMarker(files,destination,markerBytes,markerIdentity);files.unlink(destination);files.syncDirectory(parent); }
                    catch(IOException ignored) { }
                }
                if(tempOwnershipIdentity!=null)try{if(files.matchesFile(temp,tempOwnershipIdentity))files.unlink(temp);}catch(IOException ignored){}
            }
            if(publishLock!=null)try{publishLock.close();}catch(IOException closeFailure){if(installed)throw closeFailure;}
        }
    }

    private static long outputCount(FileIdentity identity) { return identity.size; }

    private static byte[] reservationMarker(String purpose,String token,String destination,long size,String sha256,
            String transactionId,String groupId,String localId,String bindingSha256,String currentLocalNoteId)throws IOException {
        Map<String,Object> marker=new java.util.TreeMap<>();
        marker.put("schema_version",1);marker.put("purpose",purpose);marker.put("token",token);marker.put("destination",destination);
        marker.put("expected_size",size);marker.put("sha256",sha256);marker.put("transaction_id",transactionId);
        marker.put("group_id",groupId);marker.put("local_id",localId);marker.put("binding_sha256",bindingSha256);
        marker.put("current_local_note_id",currentLocalNoteId);
        return LibraryBackupJson.encode(marker);
    }

    private static boolean existsNoFollow(File path, SafeFiles files) {
        try { return !files.isAbsentNoFollow(path); }
        catch (IOException unsafeOrUnknown) { return true; }
    }

    private static void checkCancelled(Cancellation cancellation) throws IOException {
        if (cancellation != null && cancellation.isCancelled()) throw new IOException("CANCELLED");
    }

    private static long limit(String role) throws IOException {
        switch (role) {
            case "note_document": return LibraryBackupManifest.MAX_NOTE_BYTES;
            case "pdf_original": return LibraryBackupManifest.MAX_PDF_BYTES;
            case "assigned_cover_png": case "user_cover_preset_png": return LibraryBackupManifest.MAX_PNG_BYTES;
            case "vault_entry_json": return LibraryBackupManifest.MAX_VAULT_BYTES;
            case "video_attachment_mp4": return LibraryBackupManifest.MAX_VIDEO_BYTES;
            default: throw new IOException("RESOURCE_ROLE_INVALID");
        }
    }

    private static void validatePayloadMetadata(LibraryBackupManifest manifest, Map<String, File> staged,
            SafeFiles files) throws IOException {
        for (LibraryBackupManifest.Note note : manifest.notes) {
            LibraryBackupManifest.Resource resource = manifest.resourceById(note.noteResourceId);
            Map<String, Object> document = LibraryBackupJson.parseCheckedObject(readBounded(files,
                    staged.get(note.noteResourceId), resource.byteLength), (int) LibraryBackupManifest.MAX_NOTE_BYTES);
            String sourceId = LibraryBackupJson.string(document.get("id"), "NOTE_PAYLOAD_ID");
            long schema = LibraryBackupJson.integer(document.get("schemaVersion"), 1, 8, "NOTE_PAYLOAD_SCHEMA");
            Object updatedAt = document.get("updatedAt");
            if (!(updatedAt instanceof LibraryBackupJson.NumberToken)) throw new IOException("NOTE_PAYLOAD_REVISION");
            double rawRevision;
            try { rawRevision = Double.parseDouble(((LibraryBackupJson.NumberToken) updatedAt).value); }
            catch (NumberFormatException invalid) { throw new IOException("NOTE_PAYLOAD_REVISION"); }
            if (!Double.isFinite(rawRevision) || rawRevision < 0 || rawRevision >= 9.223372036854776E18d ||
                    !sourceId.equals(note.sourceNoteId) || schema != note.noteSchemaVersion || (long) Math.floor(rawRevision) != note.sourceRevisionMs)
                throw new IOException("NOTE_PAYLOAD_DESCRIPTOR_MISMATCH");
        }
        for (LibraryBackupManifest.VaultEntry vault : manifest.vaultEntries) {
            LibraryBackupManifest.Resource resource = manifest.resourceById(vault.resourceId);
            Map<String, Object> payload = LibraryBackupJson.parseCheckedObject(readBounded(files,
                    staged.get(vault.resourceId), resource.byteLength), (int) LibraryBackupManifest.MAX_VAULT_BYTES);
            LibraryBackupJson.exactKeys(payload, "VAULT_PAYLOAD_FIELDS", "schema_version", "title", "markdown",
                    "source_note_id", "source_revision_ms", "created_at_ms");
            if (LibraryBackupJson.integer(payload.get("schema_version"), 1, 1, "VAULT_PAYLOAD_SCHEMA") != 1 ||
                    LibraryBackupJson.string(payload.get("title"), "VAULT_PAYLOAD_TITLE").isEmpty() ||
                    !(payload.get("markdown") instanceof String) ||
                    !vault.sourceNoteId.equals(LibraryBackupJson.string(payload.get("source_note_id"), "VAULT_PAYLOAD_SOURCE")) ||
                    vault.sourceRevisionMs != LibraryBackupJson.integer(payload.get("source_revision_ms"), 0, Long.MAX_VALUE, "VAULT_PAYLOAD_REVISION") ||
                    vault.createdAtMs != LibraryBackupJson.integer(payload.get("created_at_ms"), 0, Long.MAX_VALUE, "VAULT_PAYLOAD_CREATED"))
                throw new IOException("VAULT_PAYLOAD_DESCRIPTOR_MISMATCH");
        }
    }

    private static byte[] readBounded(SafeFiles files, File file, long maxBytes) throws IOException {
        if (file == null) throw new IOException("RESOURCE_NOT_STAGED");
        FileIdentity before = files.inspect(file, false);
        if (before.size > maxBytes || before.size > Integer.MAX_VALUE) throw new IOException("PAYLOAD_SIZE_LIMIT");
        byte[] result = new byte[(int) before.size];
        try (Seekable in = files.openRead(file)) {
            if (!before.equals(in.openedIdentity()) || in.size() != before.size) throw new IOException("PAYLOAD_CHANGED");
            in.readFully(0, result, 0, result.length);
            if (!before.equals(in.openedIdentity()) || !before.equals(in.currentPathIdentity())) throw new IOException("PAYLOAD_CHANGED");
        }
        return result;
    }

    private static Map<String, Entry> scan(Seekable input) throws IOException {
        long size = input.size();
        int tailLength = (int) Math.min(size, EOCD_MAX);
        byte[] tail = new byte[tailLength]; input.readFully(size - tailLength, tail, 0, tailLength);
        int eocd = -1;
        for (int i = tail.length - 22; i >= 0; i--) if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.length) { eocd = i; break; }
        if (eocd < 0 || u16(tail, eocd + 4) != 0 || u16(tail, eocd + 6) != 0 || u16(tail, eocd + 8) != u16(tail, eocd + 10)) throw new IOException("ZIP_EOCD_INVALID");
        int count = u16(tail, eocd + 10); long centralSize = u32(tail, eocd + 12), centralOffset = u32(tail, eocd + 16);
        if (count == 0xffff || centralSize == 0xffffffffL || centralOffset == 0xffffffffL || count < 1 || count > MAX_ENTRY_COUNT || centralSize > LibraryBackupManifest.MAX_MANIFEST_BYTES) throw new IOException("ZIP64_OR_COUNT_LIMIT");
        long absoluteEocd = size - tailLength + eocd;
        if (centralOffset + centralSize != absoluteEocd) throw new IOException("ZIP_CENTRAL_RANGE");
        byte[] central = new byte[(int) centralSize]; input.readFully(centralOffset, central, 0, central.length);
        Map<String, Entry> entries = new HashMap<>(); Set<String> folded = new HashSet<>();
        int cursor = 0;
        for (int n = 0; n < count; n++) {
            if (cursor + 46 > central.length || u32(central, cursor) != 0x02014b50L) throw new IOException("ZIP_CENTRAL_RECORD");
            int madeBy = u16(central, cursor + 4), flags = u16(central, cursor + 8), method = u16(central, cursor + 10);
            long crc=u32(central,cursor+16), compressed=u32(central,cursor+20), expanded=u32(central,cursor+24), offset=u32(central,cursor+42);
            int nameLen=u16(central,cursor+28), extraLen=u16(central,cursor+30), commentLen=u16(central,cursor+32), end=cursor+46+nameLen+extraLen+commentLen;
            if (end > central.length || nameLen == 0 || (flags & ~0x080e) != 0 || (flags & 1) != 0 || !(method == 0 || method == 8)) throw new IOException("ZIP_FLAGS_OR_METHOD");
            String name = strictUtf8(central, cursor + 46, nameLen);
            byte[] extra = slice(central, cursor + 46 + nameLen, extraLen);
            if (!safeMember(name) || hasZip64(extra) || !folded.add(name.toLowerCase(java.util.Locale.ROOT))) throw new IOException("ZIP_MEMBER_NAME");
            long ext=u32(central,cursor+38); int host=(madeBy >>> 8) & 255; int unixMode=(int)(ext >>> 16) & 0xffff;
            if ((ext & 0x10) != 0 || (host == 3 && unixMode != 0 && (unixMode & 0xf000) != 0x8000)) throw new IOException("ZIP_SPECIAL_FILE");
            Entry entry=new Entry(name,flags,method,crc,compressed,expanded,offset,extra);
            if (entries.put(name, entry) != null) throw new IOException("ZIP_DUPLICATE");
            cursor=end;
        }
        if (cursor != central.length) throw new IOException("ZIP_CENTRAL_TRAILING");
        List<Entry> ordered=new ArrayList<>(entries.values()); ordered.sort(Comparator.comparingLong(e -> e.offset));
        long expected=0;
        for (Entry e:ordered) { if (e.offset != expected) throw new IOException("ZIP_GAP_OR_PREFIX"); expected=localEnd(input,e,size); }
        if (expected != centralOffset) throw new IOException("ZIP_DATA_TRAILING");
        return entries;
    }

    private static long localEnd(Seekable input, Entry e, long archiveSize) throws IOException {
        byte[] h=readAt(input,e.offset,30);
        if (u32(h,0)!=0x04034b50L || u16(h,6)!=e.flags || u16(h,8)!=e.method) throw new IOException("ZIP_LOCAL_HEADER");
        int nameLength=u16(h,26),extraLength=u16(h,28); byte[] name=readAt(input,e.offset+30,nameLength), extra=readAt(input,e.offset+30+nameLength,extraLength);
        if (!e.name.equals(strictUtf8(name,0,name.length)) || hasZip64(extra)) throw new IOException("ZIP_LOCAL_NAME");
        if ((e.flags & 8)==0 && (u32(h,14)!=e.crc || u32(h,18)!=e.compressed || u32(h,22)!=e.expanded)) throw new IOException("ZIP_LOCAL_SIZE");
        if ((e.flags & 8)!=0 && (u32(h,14)!=0 && u32(h,14)!=e.crc || u32(h,18)!=0 && u32(h,18)!=e.compressed || u32(h,22)!=0 && u32(h,22)!=e.expanded)) throw new IOException("ZIP_LOCAL_PLACEHOLDER");
        long dataEnd=e.offset+30L+nameLength+extraLength+e.compressed;
        if (dataEnd > archiveSize) throw new IOException("ZIP_MEMBER_RANGE");
        if ((e.flags & 8)==0) return dataEnd;
        byte[] first=readAt(input,dataEnd,4); long marker=u32(first,0);
        if (marker==0x08074b50L) {
            byte[] signed=readAt(input,dataEnd+4,12);
            if (u32(signed,0)==e.crc && u32(signed,4)==e.compressed && u32(signed,8)==e.expanded) return dataEnd+16;
        }
        byte[] unsigned=readAt(input,dataEnd,12);
        if (u32(unsigned,0)!=e.crc || u32(unsigned,4)!=e.compressed || u32(unsigned,8)!=e.expanded) throw new IOException("ZIP_DESCRIPTOR");
        return dataEnd+12;
    }

    private static byte[] readMember(Seekable input, Entry e, DigestOutput output, long max, Cancellation cancellation, DigestOutput alsoDigest) throws IOException {
        long dataOffset=e.offset+30L+u16(readAt(input,e.offset,30),26)+u16(readAt(input,e.offset,30),28);
        if (e.expanded > max) throw new IOException("RESOURCE_SIZE_LIMIT");
        CRC32 crc=new CRC32(); MessageDigest sha=digest(); long total=0;
        ByteArrayOutputStream capture=output==null?new ByteArrayOutputStream((int)e.expanded):null;
        byte[] compressedBuffer=new byte[CHUNK], outputBuffer=new byte[CHUNK];
        Inflater inflater=e.method==8?new Inflater(true):null;
        try {
            long left=e.compressed;
            while (left>0) {
                checkCancelled(cancellation);
                int n=(int)Math.min(left,compressedBuffer.length); input.readFully(dataOffset,compressedBuffer,0,n); dataOffset+=n; left-=n;
                if (inflater==null) {
                    total=consume(compressedBuffer,n,total,max,crc,sha,output,alsoDigest,capture);
                } else {
                    inflater.setInput(compressedBuffer,0,n);
                    while (!inflater.needsInput()) {
                        int produced;
                        try { produced=inflater.inflate(outputBuffer); } catch (DataFormatException bad) { throw new IOException("DEFLATE_INVALID"); }
                        if (produced>0) total=consume(outputBuffer,produced,total,max,crc,sha,output,alsoDigest,capture);
                        if (inflater.finished()) {
                            if (inflater.getRemaining()!=0 || left!=0) throw new IOException("DEFLATE_TRAILING");
                            break;
                        }
                        if (produced==0 && inflater.needsDictionary()) throw new IOException("DEFLATE_DICTIONARY");
                        if (produced==0 && !inflater.needsInput()) throw new IOException("DEFLATE_STALLED");
                    }
                    if (inflater.finished() && left!=0) throw new IOException("DEFLATE_TRAILING");
                }
            }
            if (inflater!=null && !inflater.finished()) throw new IOException("DEFLATE_TRUNCATED");
        } finally { if (inflater!=null) inflater.end(); }
        if (total!=e.expanded || crc.getValue()!=e.crc) throw new IOException("ZIP_CRC_OR_SIZE");
        String contentSha=hex(sha.digest());
        if (alsoDigest!=null) alsoDigest.digestResult=contentSha;
        return capture==null?null:capture.toByteArray();
    }

    private static long consume(byte[] bytes,int n,long total,long max,CRC32 crc,MessageDigest sha,DigestOutput output,DigestOutput also,ByteArrayOutputStream capture) throws IOException {
        if (total > max-n) throw new IOException("RESOURCE_SIZE_LIMIT");
        crc.update(bytes,0,n); sha.update(bytes,0,n); if (output!=null) output.write(bytes,0,n); else if(capture!=null) capture.write(bytes,0,n); if (also!=null && also!=output) also.write(bytes,0,n);
        return total+n;
    }

    private static String hashSeekable(Seekable input, Cancellation cancellation) throws IOException {
        MessageDigest digest=digest(); byte[] b=new byte[CHUNK]; long offset=0;
        while (offset<input.size()) { checkCancelled(cancellation); int n=(int)Math.min(b.length,input.size()-offset); input.readFully(offset,b,0,n); digest.update(b,0,n); offset+=n; }
        return hex(digest.digest());
    }

    private static boolean safeMember(String name) {
        if ("manifest.json".equals(name)) return true;
        return name.matches("payload/r-[0-9a-f]{32}\\.bin") && !name.contains("\\") && !name.contains("..") && !name.startsWith("/");
    }
    private static boolean hasZip64(byte[] extra) throws IOException {
        int pos=0; while (pos<extra.length) { if (pos+4>extra.length) throw new IOException("ZIP_EXTRA_INVALID"); int id=u16(extra,pos),len=u16(extra,pos+2); pos+=4; if (pos+len>extra.length) throw new IOException("ZIP_EXTRA_INVALID"); if (id==1) return true; pos+=len; }
        return false;
    }
    private static String strictUtf8(byte[] bytes,int offset,int length) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,offset,length)).toString(); }
        catch (CharacterCodingException e) { throw new IOException("ZIP_UTF8_INVALID"); }
    }
    private static byte[] slice(byte[] data,int offset,int length) { byte[] result=new byte[length]; System.arraycopy(data,offset,result,0,length); return result; }
    private static byte[] readAt(Seekable input,long offset,int size) throws IOException { byte[] result=new byte[size]; input.readFully(offset,result,0,size); return result; }
    private static int u16(byte[] b,int p) { return (b[p]&255)|((b[p+1]&255)<<8); }
    private static long u32(byte[] b,int p) { return ((long)b[p]&255)|(((long)b[p+1]&255)<<8)|(((long)b[p+2]&255)<<16)|(((long)b[p+3]&255)<<24); }
    private static MessageDigest digest() throws IOException { try { return MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException e) { throw new IOException("SHA256_UNAVAILABLE"); } }
    private static String sha256(byte[] b) throws IOException { MessageDigest d=digest(); return hex(d.digest(b)); }
    private static boolean isCanonicalUuid(String value) { if(value==null)return false;try{return UUID.fromString(value).toString().equals(value.toLowerCase(java.util.Locale.ROOT))&&value.length()==36;}catch(IllegalArgumentException e){return false;} }
    private static boolean isSha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String hex(byte[] b) { StringBuilder s=new StringBuilder(b.length*2); for(byte x:b)s.append(String.format(java.util.Locale.ROOT,"%02x",x&255)); return s.toString(); }

    private static final class Entry {
        final String name; final int flags,method; final long crc,compressed,expanded,offset; final byte[] extra;
        Entry(String name,int flags,int method,long crc,long compressed,long expanded,long offset,byte[] extra){this.name=name;this.flags=flags;this.method=method;this.crc=crc;this.compressed=compressed;this.expanded=expanded;this.offset=offset;this.extra=extra;}
    }
    private static final class DigestOutput extends java.io.OutputStream {
        final java.io.OutputStream out; final MessageDigest digest; long count; String digestResult;
        DigestOutput(java.io.OutputStream out) throws IOException { this.out=out; this.digest=LibraryBackupArchive.digest(); }
        @Override public void write(int b)throws IOException{out.write(b);digest.update((byte)b);count++;}
        @Override public void write(byte[] b,int off,int len)throws IOException{out.write(b,off,len);digest.update(b,off,len);count+=len;}
        String shaHex(){return digestResult!=null?digestResult:hex(digest.digest());}
    }
    private static final class CountingDigestOutput extends java.io.OutputStream {
        final java.io.OutputStream out; final MessageDigest digest; final long max; long count;
        CountingDigestOutput(java.io.OutputStream out,MessageDigest digest,long max){this.out=out;this.digest=digest;this.max=max;}
        @Override public void write(int b)throws IOException{if(count>=max)throw new IOException("ARCHIVE_SIZE_LIMIT");out.write(b);digest.update((byte)b);count++;}
        @Override public void write(byte[] b,int off,int len)throws IOException{if(len<0||count>max-len)throw new IOException("ARCHIVE_SIZE_LIMIT");out.write(b,off,len);digest.update(b,off,len);count+=len;}
    }
    private static final class ZipWriter {
        final CountingDigestOutput out; final List<Written> records=new ArrayList<>();
        ZipWriter(CountingDigestOutput out){this.out=out;}
        void add(String name,byte[] bytes,Seekable source,LibraryBackupManifest.Resource descriptor,Cancellation cancel)throws IOException {
            byte[] nameBytes=name.getBytes(StandardCharsets.UTF_8); if (nameBytes.length>65535||!safeMember(name)) throw new IOException("ZIP_MEMBER_NAME");
            long offset=out.count; write32(0x04034b50L);write16(20);write16(0x0808);write16(8);write16(0);write16(0);write32(0);write32(0);write32(0);write16(nameBytes.length);write16(0);out.write(nameBytes);
            CRC32 crc=new CRC32(); MessageDigest sha=digest(); long plain=0,compressed=0; Deflater deflater=new Deflater(Deflater.DEFAULT_COMPRESSION,true); byte[] inbuf=new byte[CHUNK],outbuf=new byte[CHUNK];
            try {
                if (source==null) { plain=bytes.length; crc.update(bytes); sha.update(bytes); deflater.setInput(bytes); deflater.finish(); while(!deflater.finished()){int n=deflater.deflate(outbuf);out.write(outbuf,0,n);compressed+=n;} }
                else { long pos=0; while(pos<source.size()){checkCancelled(cancel);int n=(int)Math.min(inbuf.length,source.size()-pos);source.readFully(pos,inbuf,0,n);pos+=n;plain+=n;if(plain>descriptor.byteLength)throw new IOException("SOURCE_CHANGED");crc.update(inbuf,0,n);sha.update(inbuf,0,n);deflater.setInput(inbuf,0,n);while(!deflater.needsInput()){int made=deflater.deflate(outbuf);if(made>0){out.write(outbuf,0,made);compressed+=made;}} } deflater.finish();while(!deflater.finished()){int n=deflater.deflate(outbuf);out.write(outbuf,0,n);compressed+=n;} }
            } finally { deflater.end(); }
            if (source!=null && (plain!=descriptor.byteLength || !hex(sha.digest()).equals(descriptor.sha256))) throw new IOException("SOURCE_HASH_MISMATCH");
            if (source==null) sha.digest();
            if (plain>0xffffffffL||compressed>0xffffffffL) throw new IOException("ZIP64_UNSUPPORTED");
            write32(0x08074b50L);write32(crc.getValue());write32(compressed);write32(plain);
            records.add(new Written(nameBytes,0x0808,8,crc.getValue(),compressed,plain,offset));
        }
        void finish() throws IOException {
            long start=out.count;
            for(Written r:records){write32(0x02014b50L);write16((3<<8)|20);write16(20);write16(r.flags);write16(r.method);write16(0);write16(0);write32(r.crc);write32(r.compressed);write32(r.expanded);write16(r.name.length);write16(0);write16(0);write16(0);write16(0);write32(0);write32(r.offset);out.write(r.name);}
            long central=out.count-start;if(records.size()>65534||central>0xffffffffL||start>0xffffffffL)throw new IOException("ZIP64_UNSUPPORTED");
            write32(0x06054b50L);write16(0);write16(0);write16(records.size());write16(records.size());write32(central);write32(start);write16(0);
        }
        void write16(long v)throws IOException{out.write((int)v);out.write((int)(v>>>8));}
        void write32(long v)throws IOException{write16(v);write16(v>>>16);}
        static final class Written{final byte[] name;final int flags,method;final long crc,compressed,expanded,offset;Written(byte[] n,int f,int m,long c,long z,long e,long o){name=n;flags=f;method=m;crc=c;compressed=z;expanded=e;offset=o;}}
    }

    static final class AndroidSafeFiles implements SafeFiles {
        @Override public FileIdentity inspect(File path, boolean directory) throws IOException { try { checkAncestors(path.getAbsoluteFile().getParentFile()); android.system.StructStat s=Os.lstat(path.getAbsolutePath()); validateStat(s,directory); return identity(s,directory); } catch(android.system.ErrnoException e){throw new IOException("FILE_UNAVAILABLE");} }
        @Override public void validatePrivateDirectory(File path) throws IOException {
            try {
                checkAncestors(path.getAbsoluteFile().getParentFile());
                android.system.StructStat s=Os.lstat(path.getAbsolutePath());
                validateStat(s,true);
                if (s.st_uid != Os.getuid() || (s.st_mode & 0022) != 0) throw new IOException("PRIVATE_DIRECTORY_REQUIRED");
            } catch (android.system.ErrnoException e) { throw new IOException("PRIVATE_DIRECTORY_REQUIRED"); }
        }
        @Override public boolean isAbsentNoFollow(File path)throws IOException {
            try { checkAncestors(path.getAbsoluteFile().getParentFile());Os.lstat(path.getAbsolutePath());return false; }
            catch(android.system.ErrnoException e){if(e.errno==OsConstants.ENOENT)return true;throw new IOException("FILE_UNAVAILABLE");}
        }
        @Override public Seekable openRead(File path) throws IOException {
            java.io.FileDescriptor fd = null;
            try {
                checkAncestors(path.getAbsoluteFile().getParentFile());
                android.system.StructStat before=Os.lstat(path.getAbsolutePath()); validateStat(before,false);
                fd=Os.open(path.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);
                android.system.StructStat opened=Os.fstat(fd); validateStat(opened,false);
                if(!same(before,opened)) throw new IOException("FILE_CHANGED");
                ChannelSource source = new ChannelSource(fd,identity(opened,false),path);
                fd = null;
                return source;
            } catch(android.system.ErrnoException e){throw new IOException("FILE_UNAVAILABLE");}
            finally { if(fd!=null) try{Os.close(fd);}catch(android.system.ErrnoException ignored){} }
        }
        @Override public FileOutputStream createExclusive(File path) throws IOException { try { checkAncestors(path.getAbsoluteFile().getParentFile()); java.io.FileDescriptor fd=Os.open(path.getAbsolutePath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600); return new FileOutputStream(fd); } catch(android.system.ErrnoException e){throw new IOException("FILE_CREATE_FAILED");} }
        @Override public void syncExistingFile(File path,FileIdentity expected)throws IOException {
            java.io.FileDescriptor fd=null;
            try {
                checkAncestors(path.getAbsoluteFile().getParentFile());
                android.system.StructStat before=Os.lstat(path.getAbsolutePath());validateStat(before,false);
                FileIdentity beforeId=identity(before,false);
                if(!beforeId.equals(expected)||beforeId.links!=1)throw new IOException("FILE_CHANGED");
                fd=Os.open(path.getAbsolutePath(),OsConstants.O_WRONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);
                android.system.StructStat opened=Os.fstat(fd);validateStat(opened,false);
                if(!beforeId.equals(identity(opened,false)))throw new IOException("FILE_CHANGED");
                Os.fsync(fd);
                android.system.StructStat after=Os.fstat(fd);validateStat(after,false);
                android.system.StructStat pathAfter=Os.lstat(path.getAbsolutePath());validateStat(pathAfter,false);
                FileIdentity afterId=identity(after,false);
                if(!beforeId.equals(afterId)||!afterId.equals(identity(pathAfter,false)))throw new IOException("FILE_CHANGED");
            } catch(android.system.ErrnoException e){throw new IOException("FILE_SYNC_FAILED");}
            finally {if(fd!=null)try{Os.close(fd);}catch(android.system.ErrnoException ignored){}}
        }
        @Override public void mkdirExclusive(File path)throws IOException { try { checkAncestors(path.getAbsoluteFile().getParentFile()); Os.mkdir(path.getAbsolutePath(),0700); } catch(android.system.ErrnoException e){throw new IOException("DIRECTORY_CREATE_FAILED");} }
        @Override public void syncDirectory(File path)throws IOException { try { java.io.FileDescriptor fd=Os.open(path.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0); try{android.system.StructStat s=Os.fstat(fd);if(OsConstants.S_ISLNK(s.st_mode)||!OsConstants.S_ISDIR(s.st_mode))throw new IOException("DIRECTORY_UNSAFE");Os.fsync(fd);}finally{Os.close(fd);} }catch(android.system.ErrnoException e){throw new IOException("DIRECTORY_SYNC_FAILED");} }
        @Override public void deleteOwnedTree(File path)throws IOException {
            android.system.StructStat root;
            try { root=Os.lstat(path.getAbsolutePath()); } catch(android.system.ErrnoException e) { return; }
            if (OsConstants.S_ISLNK(root.st_mode) || !OsConstants.S_ISDIR(root.st_mode)) throw new IOException("STAGE_CLEANUP_UNSAFE");
            File[] children=path.listFiles(); if(children==null)throw new IOException("STAGE_CLEANUP_FAILED");
            for(File child:children){
                try { android.system.StructStat s=Os.lstat(child.getAbsolutePath());
                    if(OsConstants.S_ISDIR(s.st_mode) && !OsConstants.S_ISLNK(s.st_mode)) deleteOwnedTree(child);
                    else Os.remove(child.getAbsolutePath());
                } catch(android.system.ErrnoException e){throw new IOException("STAGE_CLEANUP_FAILED");}
            }
            try{Os.remove(path.getAbsolutePath());}catch(android.system.ErrnoException e){throw new IOException("STAGE_CLEANUP_FAILED");}
        }
        @Override public PublishLock lockPublish(File destination)throws IOException {
            File parent=destination.getAbsoluteFile().getParentFile();validatePrivateDirectory(parent);
            String lockName=".padnote-publish-"+hex(digest().digest(destination.getName().getBytes(StandardCharsets.UTF_8)))+".lock";
            File lockFile=new File(parent,lockName);FileDescriptor fd=null;FileOutputStream stream=null;FileChannel channel=null;java.nio.channels.FileLock lock=null;
            try {
                fd=Os.open(lockFile.getAbsolutePath(),OsConstants.O_RDWR|OsConstants.O_CREAT|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);
                android.system.StructStat opened=Os.fstat(fd),pathStat=Os.lstat(lockFile.getAbsolutePath());
                if(!OsConstants.S_ISREG(opened.st_mode)||OsConstants.S_ISLNK(opened.st_mode)||opened.st_nlink!=1||opened.st_uid!=Os.getuid()||
                        (opened.st_mode&0077)!=0||!same(opened,pathStat))throw new IOException("PUBLISH_LOCK_UNSAFE");
                stream=new FileOutputStream(fd);fd=null;channel=stream.getChannel();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(lock==null&&System.nanoTime()<deadline){try{lock=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException busy){}if(lock==null)try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("PUBLISH_LOCK_INTERRUPTED");}}
                if(lock==null)throw new IOException("PUBLISH_LOCK_BUSY");
                final FileOutputStream held=stream;final java.nio.channels.FileLock heldLock=lock;
                return ()->{try{heldLock.release();}finally{held.close();}};
            } catch(android.system.ErrnoException e) {
                if(lock!=null)try{lock.release();}catch(IOException ignored){}if(stream!=null)try{stream.close();}catch(IOException ignored){}else if(fd!=null)try{Os.close(fd);}catch(android.system.ErrnoException ignored){}
                throw new IOException("PUBLISH_LOCK_FAILED");
            } catch(IOException failure) {
                if(lock!=null)try{lock.release();}catch(IOException ignored){}if(stream!=null)try{stream.close();}catch(IOException ignored){}else if(fd!=null)try{Os.close(fd);}catch(android.system.ErrnoException ignored){}
                throw failure;
            }
        }
        @Override public boolean isPersistentPublishLockArtifact(File path)throws IOException {
            if(path==null||!path.getName().matches("\\.padnote-publish-[0-9a-f]{64}\\.lock"))return false;
            FileDescriptor fd=null;
            try{
                checkAncestors(path.getAbsoluteFile().getParentFile());android.system.StructStat pathBefore=Os.lstat(path.getAbsolutePath());
                if(!validPersistentPublishLockStat(pathBefore))return false;
                fd=Os.open(path.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);
                android.system.StructStat openedBefore=Os.fstat(fd);if(!validPersistentPublishLockStat(openedBefore)||!same(pathBefore,openedBefore))return false;
                android.system.StructStat openedAfter=Os.fstat(fd);checkAncestors(path.getAbsoluteFile().getParentFile());
                android.system.StructStat pathAfter=Os.lstat(path.getAbsolutePath());
                return validPersistentPublishLockStat(openedAfter)&&validPersistentPublishLockStat(pathAfter)&&
                        same(openedBefore,openedAfter)&&same(pathBefore,pathAfter)&&same(openedAfter,pathAfter);
            }catch(android.system.ErrnoException e){throw new IOException("PUBLISH_LOCK_UNAVAILABLE");}
            finally{if(fd!=null)try{Os.close(fd);}catch(android.system.ErrnoException ignored){}}
        }
        private static boolean validPersistentPublishLockStat(android.system.StructStat s){return OsConstants.S_ISREG(s.st_mode)&&!OsConstants.S_ISLNK(s.st_mode)&&s.st_nlink==1&&s.st_uid==Os.getuid()&&(s.st_mode&0777)==0600&&s.st_size==0;}
        @Override public void replaceOwnedMarker(File from,File to,byte[] marker,FileIdentity markerIdentity)throws IOException {
            checkAncestors(to.getAbsoluteFile().getParentFile());checkAncestors(from.getAbsoluteFile().getParentFile());
            verifyReservationMarker(this,to,marker,markerIdentity);
            FileIdentity source=inspect(from,false);if(source.links!=1||source.directory)throw new IOException("PUBLISH_SOURCE_UNSAFE");
            try {
                android.system.StructStat sourceParent=Os.stat(from.getAbsoluteFile().getParent());
                android.system.StructStat targetParent=Os.stat(to.getAbsoluteFile().getParent());
                if(sourceParent.st_dev!=targetParent.st_dev)throw new IOException("PUBLISH_CROSS_DEVICE");
                verifyReservationMarker(this,to,marker,markerIdentity);
                Os.rename(from.getAbsolutePath(),to.getAbsolutePath());
            } catch(android.system.ErrnoException e){throw new IOException("PUBLISH_RENAME_FAILED");}
        }
        @Override public void moveNoReplace(File from,File to,byte[] expectedBytes)throws IOException {
            checkAncestors(from.getAbsoluteFile().getParentFile());checkAncestors(to.getAbsoluteFile().getParentFile());
            FileIdentity source=inspect(from,false);if(source.links!=1||source.directory||source.size!=expectedBytes.length)throw new IOException("PUBLISH_SOURCE_UNSAFE");
            if(!isAbsentNoFollow(to))throw new IOException("DESTINATION_EXISTS");
            try {
                android.system.StructStat sourceParent=Os.stat(from.getAbsoluteFile().getParent()),targetParent=Os.stat(to.getAbsoluteFile().getParent());
                if(sourceParent.st_dev!=targetParent.st_dev)throw new IOException("PUBLISH_CROSS_DEVICE");
                // link(2) creates the destination atomically with no replacement. Remove the
                // staging name only after verifying the complete two-link state.
                linkNoReplace(from,to);
                FileIdentity installed=inspect(to,false),staged=inspect(from,false);
                if(installed.links!=2||staged.links!=2||installed.device!=source.device||installed.inode!=source.inode||staged.device!=source.device||staged.inode!=source.inode||installed.size!=expectedBytes.length)throw new IOException("PUBLISH_DESTINATION_CHANGED");
                byte[] actual=new byte[expectedBytes.length];try(Seekable input=openRead(to)){if(!installed.equals(input.openedIdentity()))throw new IOException("PUBLISH_DESTINATION_CHANGED");input.readFully(0,actual,0,actual.length);if(!installed.equals(input.currentPathIdentity()))throw new IOException("PUBLISH_DESTINATION_CHANGED");}
                if(!java.util.Arrays.equals(actual,expectedBytes))throw new IOException("PUBLISH_DESTINATION_CHANGED");
                unlink(from);FileIdentity finalIdentity=inspect(to,false);
                if(finalIdentity.links!=1||finalIdentity.device!=source.device||finalIdentity.inode!=source.inode||finalIdentity.size!=expectedBytes.length)throw new IOException("PUBLISH_DESTINATION_CHANGED");
            }catch(android.system.ErrnoException e){throw new IOException("PUBLISH_RENAME_FAILED");}
        }
        @Override public FileIdentity inspectOwnedPair(File path)throws IOException {try{checkAncestors(path.getAbsoluteFile().getParentFile());android.system.StructStat s=Os.lstat(path.getAbsolutePath());if(!OsConstants.S_ISREG(s.st_mode)||OsConstants.S_ISLNK(s.st_mode)||s.st_nlink!=2)throw new IOException("FILE_UNSAFE");return identity(s,false);}catch(android.system.ErrnoException e){throw new IOException("FILE_UNAVAILABLE");}}
        @Override public Seekable openOwnedPair(File path)throws IOException {FileDescriptor fd=null;try{checkAncestors(path.getAbsoluteFile().getParentFile());android.system.StructStat before=Os.lstat(path.getAbsolutePath());if(!OsConstants.S_ISREG(before.st_mode)||OsConstants.S_ISLNK(before.st_mode)||before.st_nlink!=2)throw new IOException("FILE_UNSAFE");fd=Os.open(path.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);android.system.StructStat opened=Os.fstat(fd);if(!same(before,opened))throw new IOException("FILE_CHANGED");ChannelSource input=new ChannelSource(fd,identity(opened,false),path);fd=null;return input;}catch(android.system.ErrnoException e){throw new IOException("FILE_UNAVAILABLE");}finally{if(fd!=null)try{Os.close(fd);}catch(android.system.ErrnoException ignored){}}}
        @Override public void linkNoReplace(File from,File to)throws IOException { try{Os.link(from.getAbsolutePath(),to.getAbsolutePath());}catch(android.system.ErrnoException e){throw new IOException("DESTINATION_CREATE_FAILED errno="+e.errno+" detail="+e.getMessage(),e);} }
        @Override public void unlink(File path)throws IOException { try{Os.remove(path.getAbsolutePath());}catch(android.system.ErrnoException e){if(path.exists())throw new IOException("FILE_UNLINK_FAILED");} }
        @Override public boolean matchesFile(File path, FileIdentity expected)throws IOException {
            try { checkAncestors(path.getAbsoluteFile().getParentFile()); android.system.StructStat s=Os.lstat(path.getAbsolutePath());
                return OsConstants.S_ISREG(s.st_mode) && !OsConstants.S_ISLNK(s.st_mode) && s.st_dev==expected.device && s.st_ino==expected.inode;
            } catch(android.system.ErrnoException e) { return false; }
        }
        static void checkAncestors(File path)throws IOException {
            if(path==null)throw new IOException("PATH_INVALID");
            List<File> parts=new ArrayList<>();for(File p=path.getAbsoluteFile();p!=null;p=p.getParentFile())parts.add(p);Collections.reverse(parts);
            for(File p:parts) {
                if(USER_ZERO_ALIAS.equals(p.getAbsolutePath())) {
                    try {
                        android.system.StructStat alias=Os.lstat(USER_ZERO_ALIAS);
                        if(OsConstants.S_ISLNK(alias.st_mode)) { requireTrustedSystemUserZeroAlias(); continue; }
                        // Some Android layouts expose user zero as an ordinary directory.
                        // Preserve the original no-follow directory policy in that case.
                    } catch(android.system.ErrnoException e) { throw new IOException("PATH_UNAVAILABLE"); }
                }
                try { android.system.StructStat s=Os.lstat(p.getAbsolutePath());
                    if(!OsConstants.S_ISDIR(s.st_mode)||OsConstants.S_ISLNK(s.st_mode))throw new IOException("PATH_UNSAFE");
                    File parent=p.getParentFile();String parentPath=parent==null?null:parent.getAbsolutePath();
                    if("/data/user/0".equals(parentPath)||"/data/data".equals(parentPath)) {
                        if(s.st_uid!=Os.getuid()||(s.st_mode&0022)!=0)throw new IOException("PATH_UNSAFE");
                    }
                } catch(android.system.ErrnoException e) { throw new IOException("PATH_UNAVAILABLE"); }
            }
        }
        /**
         * Validates a caller supplied private directory while permitting only the measured
         * Android /data/user/0 -> /data/data platform alias.  No arbitrary canonicalization
         * is used to make an otherwise unsafe path acceptable.
         */
        static File canonicalPrivateAndroidDirectory(File path)throws IOException {
            if(path==null||!path.isAbsolute())throw new IOException("PATH_INVALID");
            String raw=path.getPath();
            if(!raw.startsWith("/"))throw new IOException("PATH_INVALID");
            String[] components=raw.substring(1).split("/",-1);
            for(String component:components) if(component.isEmpty()||".".equals(component)||"..".equals(component)) throw new IOException("PATH_INVALID");
            checkAncestors(path);
            try {
                boolean systemAlias=false;
                if(raw.equals(USER_ZERO_ALIAS)||raw.startsWith(USER_ZERO_ALIAS+"/")) {
                    android.system.StructStat prefix=Os.lstat(USER_ZERO_ALIAS);
                    systemAlias=OsConstants.S_ISLNK(prefix.st_mode);
                    if(systemAlias) requireTrustedSystemUserZeroAlias();
                }
                android.system.StructStat before=Os.lstat(raw);
                if(!OsConstants.S_ISDIR(before.st_mode)||OsConstants.S_ISLNK(before.st_mode)||before.st_uid!=Os.getuid()||(before.st_mode&0022)!=0)
                    throw new IOException("PRIVATE_DIRECTORY_REQUIRED");
                String expected=expectedCanonicalPrivatePath(raw,systemAlias);
                File canonical=path.getCanonicalFile();
                if(!expected.equals(canonical.getPath()))throw new IOException("PATH_UNSAFE");
                checkAncestors(path);
                android.system.StructStat after=Os.lstat(raw);
                if(!same(before,after))throw new IOException("PATH_UNSAFE");
                return canonical;
            } catch(android.system.ErrnoException e) { throw new IOException("PATH_UNAVAILABLE"); }
        }
        static String expectedCanonicalPrivatePath(String raw,boolean systemAlias)throws IOException {
            if(raw==null||!raw.startsWith("/"))throw new IOException("PATH_INVALID");
            if(!systemAlias)return raw;
            if(!raw.equals(USER_ZERO_ALIAS)&&!raw.startsWith(USER_ZERO_ALIAS+"/"))throw new IOException("PATH_UNSAFE");
            return LEGACY_APP_DATA_ROOT+raw.substring(USER_ZERO_ALIAS.length());
        }
        private static final int ANDROID_SYSTEM_UID=1000;
        private static final int ANDROID_ROOT_UID=0;
        private static final String USER_ZERO_ALIAS="/data/user/0";
        private static final String LEGACY_APP_DATA_ROOT="/data/data";
        static boolean permitsSystemUserZeroAlias(String path,int linkMode,int linkUid,String canonicalTarget,
                int targetMode,int targetUid,int targetGid,int dataRootMode,int dataRootUid,int dataRootGid,
                int userRootMode,int userRootUid) {
            int typeMask=0170000,linkType=0120000,directoryType=0040000;
            boolean trustedDataRoot=(dataRootMode&typeMask)==directoryType&&dataRootUid==ANDROID_SYSTEM_UID&&
                    dataRootGid==ANDROID_SYSTEM_UID&&(dataRootMode&07777)==0771;
            boolean trustedTarget=(targetMode&typeMask)==directoryType&&
                    ((targetUid==ANDROID_ROOT_UID&&targetGid==ANDROID_ROOT_UID&&(targetMode&0022)==0)||
                     (targetUid==ANDROID_SYSTEM_UID&&targetGid==ANDROID_SYSTEM_UID&&(targetMode&0002)==0));
            return USER_ZERO_ALIAS.equals(path)&&(linkMode&typeMask)==linkType&&linkUid==ANDROID_ROOT_UID&&
                    LEGACY_APP_DATA_ROOT.equals(canonicalTarget)&&trustedTarget&&trustedDataRoot&&
                    (userRootMode&typeMask)==directoryType&&userRootUid==ANDROID_ROOT_UID&&
                    ((userRootMode&07777)==0751);
        }
        private static void requireTrustedSystemUserZeroAlias()throws IOException {
            try {
                android.system.StructStat linkBefore=Os.lstat(USER_ZERO_ALIAS);
                if(!OsConstants.S_ISLNK(linkBefore.st_mode)||linkBefore.st_uid!=ANDROID_ROOT_UID)throw new IOException("PATH_UNSAFE");
                File alias=new File(USER_ZERO_ALIAS),target=new File(LEGACY_APP_DATA_ROOT);
                String linkTarget=Os.readlink(USER_ZERO_ALIAS);
                if(!LEGACY_APP_DATA_ROOT.equals(linkTarget)||!LEGACY_APP_DATA_ROOT.equals(alias.getCanonicalPath()))throw new IOException("PATH_UNSAFE");
                android.system.StructStat targetStat=Os.lstat(LEGACY_APP_DATA_ROOT);
                android.system.StructStat dataStat=Os.lstat("/data");
                android.system.StructStat userStat=Os.lstat("/data/user");
                if(!permitsSystemUserZeroAlias(USER_ZERO_ALIAS,linkBefore.st_mode,linkBefore.st_uid,alias.getCanonicalPath(),
                        targetStat.st_mode,targetStat.st_uid,targetStat.st_gid,dataStat.st_mode,dataStat.st_uid,dataStat.st_gid,
                        userStat.st_mode,userStat.st_uid)||
                        !OsConstants.S_ISDIR(targetStat.st_mode)||OsConstants.S_ISLNK(targetStat.st_mode)||
                        !OsConstants.S_ISDIR(dataStat.st_mode)||OsConstants.S_ISLNK(dataStat.st_mode)||
                        !OsConstants.S_ISDIR(userStat.st_mode)||OsConstants.S_ISLNK(userStat.st_mode))
                    throw new IOException("PATH_UNSAFE");
                android.system.StructStat linkAfter=Os.lstat(USER_ZERO_ALIAS);
                android.system.StructStat targetAfter=Os.lstat(LEGACY_APP_DATA_ROOT);
                android.system.StructStat dataAfter=Os.lstat("/data");
                android.system.StructStat userAfter=Os.lstat("/data/user");
                if(!same(linkBefore,linkAfter)||!same(targetStat,targetAfter)||!same(dataStat,dataAfter)||!same(userStat,userAfter)||
                        !LEGACY_APP_DATA_ROOT.equals(Os.readlink(USER_ZERO_ALIAS)))throw new IOException("PATH_UNSAFE");
            } catch(android.system.ErrnoException e) { throw new IOException("PATH_UNAVAILABLE"); }
        }
        private static void validateStat(android.system.StructStat s,boolean directory)throws IOException {
            if (OsConstants.S_ISLNK(s.st_mode)) throw new IOException("FILE_UNSAFE");
            if (directory) { if (!OsConstants.S_ISDIR(s.st_mode)) throw new IOException("FILE_UNSAFE"); }
            else if (!OsConstants.S_ISREG(s.st_mode) || s.st_nlink != 1) throw new IOException("FILE_UNSAFE");
        }
        private static FileIdentity identity(android.system.StructStat s,boolean directory){return new FileIdentity(s.st_dev,s.st_ino,s.st_size,s.st_mtime*1_000_000_000L,s.st_ctime*1_000_000_000L,s.st_nlink,directory);}
        private static boolean same(android.system.StructStat a,android.system.StructStat b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size&&a.st_mtime==b.st_mtime&&a.st_ctime==b.st_ctime&&a.st_nlink==b.st_nlink&&a.st_mode==b.st_mode;}
        private static final class ChannelSource implements Seekable {
            final FileInputStream stream; final FileChannel channel; final FileIdentity opened; final File path;
            ChannelSource(java.io.FileDescriptor fd,FileIdentity opened,File path){this.stream=new FileInputStream(fd);this.channel=stream.getChannel();this.opened=opened;this.path=path;}
            public long size()throws IOException{return channel.size();}
            public void readFully(long offset,byte[] target,int targetOffset,int length)throws IOException{ByteBuffer b=ByteBuffer.wrap(target,targetOffset,length);long p=offset;while(b.hasRemaining()){int n=channel.read(b,p);if(n<0)throw new IOException("FILE_TRUNCATED");if(n==0)continue;p+=n;}}
            public FileIdentity openedIdentity()throws IOException{try{return identity(Os.fstat(stream.getFD()),false);}catch(android.system.ErrnoException e){throw new IOException("FILE_STAT_FAILED");}}
            public FileIdentity currentPathIdentity()throws IOException{return opened.links==2?new AndroidSafeFiles().inspectOwnedPair(path):new AndroidSafeFiles().inspect(path,false);}
            public void close()throws IOException{stream.close();}
        }
    }
}
