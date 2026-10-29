package com.padnote.android;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Tests the real on-disk delete journal using a fresh temporary files root. */
public final class LegacyDeleteJournalTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void identitylessRecordRoundTripsWithCanonicalStrictRepresentation() throws Exception {
        File filesRoot=temporary.newFolder("identityless-roundtrip");
        LegacyDeleteJournal.Record legacy=new LegacyDeleteJournal.Record("note-identityless",
                UUID.randomUUID().toString(),"b".repeat(64),System.currentTimeMillis());
        LegacyDeleteJournal.CommitResult committed=create(filesRoot,legacy,stage->{},
                new NioIdentityReader(filesRoot),realDirectorySync(null));
        assertEquals("errorCode="+committed.errorCode,
                LegacyDeleteJournal.CommitStatus.COMMITTED,committed.status);
        LegacyDeleteJournal.Record reopened=LegacyDeleteJournal.readCommitted(filesRoot,
                legacy.noteId,new NioIdentityReader(filesRoot),realDirectorySync(null));
        assertRecordEquals(legacy,reopened);
        assertFalse(reopened.hasCanonicalNoteIdentity);
        assertEquals("-",reopened.filesRootPathSha256);
    }

    @Test public void onlyCanonicalMarkerCommitsAndReopensAcrossJournalInstances() throws Exception {
        File filesRoot = temporary.newFolder("files");
        LegacyDeleteJournal.FileIdentityReader identities = new NioIdentityReader(filesRoot);
        LegacyDeleteJournal.DirectorySync sync = realDirectorySync(null);
        File dir = new File(filesRoot, "legacy-note-deletes");
        assertTrue(dir.mkdir());
        File tempOnly = new File(dir, "note-temp.tmp-00000000-0000-4000-8000-000000000000");
        File backupOnly = new File(dir, "note-backup.bak");
        Files.write(tempOnly.toPath(), new byte[] {1, 2, 3});
        Files.write(backupOnly.toPath(), new byte[] {4, 5, 6});
        assertNull(LegacyDeleteJournal.readCommitted(filesRoot, "note-temp", identities, sync));
        assertNull(LegacyDeleteJournal.readCommitted(filesRoot, "note-backup", identities, sync));
        assertTrue(tempOnly.isFile());
        assertTrue(backupOnly.isFile());

        LegacyDeleteJournal.Record record = record(filesRoot, "note-committed");
        LegacyDeleteJournal.CommitResult committed = create(filesRoot, record,
                stage -> { }, identities, sync);
        assertEquals("errorCode=" + committed.errorCode,
                LegacyDeleteJournal.CommitStatus.COMMITTED, committed.status);
        LegacyDeleteJournal.Record reopened = LegacyDeleteJournal.readCommitted(
                filesRoot, record.noteId, new NioIdentityReader(filesRoot), sync);
        assertRecordEquals(record, reopened);
    }

    @Test public void prePromotionFailureLeavesNoCommitMarkerAndTemporaryBytesAreNotAuthority()
            throws Exception {
        File filesRoot = temporary.newFolder("precommit");
        LegacyDeleteJournal.FileIdentityReader identities = new NioIdentityReader(filesRoot);
        LegacyDeleteJournal.Record record = record(filesRoot, "note-precommit");
        LegacyDeleteJournal.CommitResult result = create(filesRoot, record, stage -> {
            if (stage == LegacyDeleteJournal.Stage.TEMP_SYNCED) throw new IOException("INJECTED");
        }, identities, realDirectorySync(null));
        assertEquals("errorCode="+result.errorCode,
                LegacyDeleteJournal.CommitStatus.NOT_COMMITTED, result.status);
        assertNull(LegacyDeleteJournal.readCommitted(filesRoot, record.noteId,
                new NioIdentityReader(filesRoot), realDirectorySync(null)));
        File family = new File(filesRoot, "legacy-note-deletes");
        assertEquals("pre-promotion failure cleans only its owned temporary marker", 0,
                family.list((dir, name) -> name.startsWith(record.noteId + ".tmp-" )).length);
    }

    @Test public void promotionWithoutDurabilityConfirmationIsUncertainButCanonicalCanBeReverified()
            throws Exception {
        File filesRoot = temporary.newFolder("uncertain");
        LegacyDeleteJournal.FileIdentityReader identities = new NioIdentityReader(filesRoot);
        LegacyDeleteJournal.Record record = record(filesRoot, "note-uncertain");
        LegacyDeleteJournal.CommitResult result = create(filesRoot, record, stage -> {
            if (stage == LegacyDeleteJournal.Stage.CANONICAL_PROMOTED) throw new IOException("INJECTED");
        }, identities, realDirectorySync(null));
        assertEquals("errorCode="+result.errorCode,
                LegacyDeleteJournal.CommitStatus.UNCERTAIN, result.status);
        assertTrue("once canonical promotion happened, caller must not clean note material",
                LegacyDeleteJournal.canonicalFile(filesRoot, record.noteId).isFile());
        LegacyDeleteJournal.Record reopened = LegacyDeleteJournal.readCommitted(filesRoot,
                record.noteId, new NioIdentityReader(filesRoot), realDirectorySync(null));
        assertRecordEquals(record, reopened);
    }

    @Test public void directorySyncFailureAfterPublicationIsUncertainAndCanBeReverified() throws Exception {
        File filesRoot=temporary.newFolder("dirsync-failure");
        LegacyDeleteJournal.FileIdentityReader identities=new NioIdentityReader(filesRoot);
        LegacyDeleteJournal.Record record=record(filesRoot,"note-dirsync-failure");
        java.util.concurrent.atomic.AtomicInteger childSyncs=new java.util.concurrent.atomic.AtomicInteger();
        LegacyDeleteJournal.DirectorySync failCommitSync=directory->{
            if("legacy-note-deletes".equals(directory.getName())&&childSyncs.incrementAndGet()==2)
                throw new IOException("INJECTED_DIRECTORY_FSYNC");
            forceDirectory(directory);
        };
        LegacyDeleteJournal.CommitResult result=create(filesRoot,record,stage->{},identities,failCommitSync);
        assertEquals("errorCode="+result.errorCode,
                LegacyDeleteJournal.CommitStatus.UNCERTAIN,result.status);
        assertTrue(LegacyDeleteJournal.canonicalFile(filesRoot,record.noteId).isFile());
        assertRecordEquals(record,LegacyDeleteJournal.readCommitted(filesRoot,record.noteId,
                new NioIdentityReader(filesRoot),realDirectorySync(null)));
    }

    @Test public void publisherFailureAfterCanonicalPromotionIsUncertainNotNotCommitted() throws Exception {
        File filesRoot = temporary.newFolder("publisher-partial");
        LegacyDeleteJournal.FileIdentityReader identities = new NioIdentityReader(filesRoot);
        LegacyDeleteJournal.Record record = record(filesRoot,"note-publisher-partial");
        LegacyDeleteJournal.CommitResult result = LegacyDeleteJournal.createCommitted(filesRoot,
                record, stage -> { }, identities, (temporary, canonical) -> {
                    Files.move(temporary.toPath(),canonical.toPath(),StandardCopyOption.ATOMIC_MOVE);
                    throw new IOException("INJECTED_AFTER_RENAME");
                }, realDirectorySync(null), nioTemporaryWriter());
        assertEquals("canonical publication began, so cleanup must remain withheld",
                LegacyDeleteJournal.CommitStatus.UNCERTAIN, result.status);
        assertTrue(LegacyDeleteJournal.canonicalFile(filesRoot, record.noteId).isFile());
        assertRecordEquals(record, LegacyDeleteJournal.readCommitted(filesRoot, record.noteId,
                new NioIdentityReader(filesRoot), realDirectorySync(null)));
    }

    @Test public void markerDirectoryCreationMustSyncItsParentBeforeCanonicalCommit() throws Exception {
        File filesRoot = temporary.newFolder("parent-sync");
        LegacyDeleteJournal.FileIdentityReader identities = new NioIdentityReader(filesRoot);
        LegacyDeleteJournal.Record record = record(filesRoot,"note-parent-sync");
        java.util.List<String> synced = new java.util.ArrayList<>();
        LegacyDeleteJournal.DirectorySync sync = directory -> {
            assertTrue(directory.isDirectory());
            synced.add(directory.getCanonicalPath());
            forceDirectory(directory);
        };
        LegacyDeleteJournal.CommitResult result = create(filesRoot, record, stage -> { }, identities, sync);
        assertEquals("errorCode=" + result.errorCode,
                LegacyDeleteJournal.CommitStatus.COMMITTED, result.status);
        assertTrue("durably creating the journal directory requires syncing its parent",
                synced.contains(filesRoot.getCanonicalPath()));
        assertTrue(synced.contains(new File(filesRoot, "legacy-note-deletes").getCanonicalPath()));
    }

    @Test public void inventoryRechecksExternalMarkerAndSameLengthTamperingDespiteRestoredMtime() throws Exception {
        File filesRoot=temporary.newFolder("inventory-cache-target");
        LegacyDeleteJournal.FileIdentityReader identities=new NioIdentityReader(filesRoot);
        LegacyDeleteJournal.DirectorySync sync=realDirectorySync(null);
        File directory=new File(filesRoot,"legacy-note-deletes");
        assertTrue(directory.mkdir());
        LegacyDeleteJournal.Record record=record(filesRoot,"note-reappearing-marker");
        LegacyDeleteJournal.CommitResult committed=create(filesRoot,record,stage->{},identities,sync);
        assertEquals("errorCode="+committed.errorCode,
                LegacyDeleteJournal.CommitStatus.COMMITTED,committed.status);
        File targetMarker=LegacyDeleteJournal.canonicalFile(filesRoot,record.noteId);
        assertEquals(1,LegacyDeleteJournal.readAllCommitted(filesRoot,identities,sync).size());

        // Hide and restore the real marker within the same bound filesRoot. Keep the entry
        // count, filename length, directory size and mtime stable so a coarse cache would miss it.
        String markerName=targetMarker.getName();
        int stagingPrefixLength=markerName.length()-".stage".length();
        assertTrue(stagingPrefixLength>0);
        File staging=new File(directory,"x".repeat(stagingPrefixLength)+".stage");
        assertEquals(markerName.length(),staging.getName().length());
        FileTime originalDirectoryTime=Files.getLastModifiedTime(directory.toPath(),LinkOption.NOFOLLOW_LINKS);
        long originalDirectorySize=((Number)Files.getAttribute(directory.toPath(),"unix:size",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        Files.move(targetMarker.toPath(),staging.toPath(),StandardCopyOption.ATOMIC_MOVE);
        assertTrue(LegacyDeleteJournal.readAllCommitted(filesRoot,identities,sync).isEmpty());
        Files.move(staging.toPath(),targetMarker.toPath(),StandardCopyOption.ATOMIC_MOVE);
        Files.setLastModifiedTime(directory.toPath(),originalDirectoryTime);
        long restoredDirectorySize=((Number)Files.getAttribute(directory.toPath(),"unix:size",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        assertEquals("fixture keeps directory fingerprint fields unchanged",originalDirectorySize,
                restoredDirectorySize);
        assertEquals("a marker reappearing with restored coarse metadata must be rediscovered",1,
                LegacyDeleteJournal.readAllCommitted(filesRoot,new NioIdentityReader(filesRoot),sync).size());

        FileTime markerTime=Files.getLastModifiedTime(targetMarker.toPath(),LinkOption.NOFOLLOW_LINKS);
        byte[] valid=Files.readAllBytes(targetMarker.toPath());
        valid[0]=(byte)(valid[0]^1);
        Files.write(targetMarker.toPath(),valid,StandardOpenOption.TRUNCATE_EXISTING);
        Files.setLastModifiedTime(targetMarker.toPath(),markerTime);
        try {
            LegacyDeleteJournal.readAllCommitted(filesRoot,new NioIdentityReader(filesRoot),sync);
            fail("same-length changed canonical marker must be re-read and rejected");
        } catch(IOException expected) {
            assertTrue(expected.getMessage().startsWith("DELETE_MARKER_"));
        }
        assertArrayEquals("tampered bytes remain available for diagnosis",valid,
                Files.readAllBytes(targetMarker.toPath()));
    }

    @Test public void malformedOrSymlinkCanonicalMarkerFailsClosedAndRemainsUntouched()
            throws Exception {
        File filesRoot = temporary.newFolder("bad-marker");
        LegacyDeleteJournal.FileIdentityReader identities = new NioIdentityReader(filesRoot);
        LegacyDeleteJournal.DirectorySync sync = realDirectorySync(null);
        File marker = LegacyDeleteJournal.canonicalFile(filesRoot, "note-malformed");
        assertTrue(marker.getParentFile().mkdir());
        byte[] invalid = "not a delete marker".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(marker.toPath(), invalid);
        try {
            LegacyDeleteJournal.readCommitted(filesRoot, "note-malformed", identities, sync);
            fail("malformed canonical marker must fail closed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().startsWith("DELETE_MARKER_"));
        }
        assertArrayEquals(invalid, Files.readAllBytes(marker.toPath()));

        File linkedRoot = temporary.newFolder("symlink-root");
        LegacyDeleteJournal.FileIdentityReader linkedIdentity = new NioIdentityReader(linkedRoot);
        LegacyDeleteJournal.DirectorySync linkedSync = realDirectorySync(null);
        File linked = LegacyDeleteJournal.canonicalFile(linkedRoot, "note-linked");
        assertTrue(linked.getParentFile().mkdir());
        File target = new File(temporary.newFolder("foreign"), "target");
        byte[] sentinel = {9, 8, 7};
        Files.write(target.toPath(), sentinel);
        Files.createSymbolicLink(linked.toPath(), target.toPath());
        try {
            LegacyDeleteJournal.readCommitted(linkedRoot, "note-linked", linkedIdentity, linkedSync);
            fail("symlink marker must fail closed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().startsWith("DELETE_MARKER_"));
        }
        assertArrayEquals(sentinel, Files.readAllBytes(target.toPath()));
        assertTrue(Files.isSymbolicLink(linked.toPath()));
    }

    private static LegacyDeleteJournal.CommitResult create(File filesRoot,
            LegacyDeleteJournal.Record record, LegacyDeleteJournal.FaultInjector faults,
            LegacyDeleteJournal.FileIdentityReader identities,
            LegacyDeleteJournal.DirectorySync sync) {
        return LegacyDeleteJournal.createCommitted(filesRoot, record, faults, identities,
                (temporary, canonical) -> Files.move(temporary.toPath(),canonical.toPath(),
                        StandardCopyOption.ATOMIC_MOVE), sync, nioTemporaryWriter());
    }

    static LegacyDeleteJournal.DirectorySync testDirectorySync() {
        return realDirectorySync(null);
    }

    private static LegacyDeleteJournal.DirectorySync realDirectorySync(java.util.List<String> observed) {
        return directory -> {
            assertTrue("sync target is a real directory",directory.isDirectory());
            if(observed!=null) observed.add(directory.getCanonicalPath());
            forceDirectory(directory);
        };
    }

    private static void forceDirectory(File directory) throws IOException {
        try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static LegacyDeleteJournal.TemporaryWriter nioTemporaryWriter() {
        return new LegacyDeleteJournal.TemporaryWriter() {
            @Override public void writeNewSynced(File temporary,byte[] contents)throws IOException {
                Set<PosixFilePermission> ownerOnly=EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE);
                Files.createFile(temporary.toPath(),PosixFilePermissions.asFileAttribute(ownerOnly));
                try(FileChannel channel=FileChannel.open(temporary.toPath(),StandardOpenOption.WRITE)) {
                    ByteBuffer buffer=ByteBuffer.wrap(contents);
                    while(buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
            }
            @Override public void deleteOwnedTemp(File temporary,LegacyDeleteJournal.Identity expected,
                    LegacyDeleteJournal.FileIdentityReader identities)throws IOException {
                LegacyDeleteJournal.Identity current=identities.lstat(temporary);
                if(expected!=null&&expected.sameObject(current)&&current.regular&&!current.symlink
                        &&current.linkCount==1) Files.delete(temporary.toPath());
            }
        };
    }

    private static LegacyDeleteJournal.Record record(File filesRoot,String noteId) throws Exception {
        File notes=new File(filesRoot,"notes");
        if(!notes.isDirectory()) assertTrue(notes.mkdir());
        Files.write(new File(notes,noteId+".json").toPath(),
                ("{\"id\":\""+noteId+"\",\"body\":\"synthetic\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return LegacyDeleteJournal.captureCanonicalNote(filesRoot,noteId,
                UUID.randomUUID().toString(),System.currentTimeMillis(),
                new NioIdentityReader(filesRoot));
    }

    private static void assertRecordEquals(LegacyDeleteJournal.Record expected,
                                           LegacyDeleteJournal.Record actual) {
        assertNotNull(actual);
        assertEquals(expected.noteId, actual.noteId);
        assertEquals(expected.operationId, actual.operationId);
        assertEquals(expected.canonicalNoteSha256, actual.canonicalNoteSha256);
        assertEquals(expected.createdAt, actual.createdAt);
        assertEquals(expected.hasCanonicalNoteIdentity,actual.hasCanonicalNoteIdentity);
        assertEquals(expected.filesRootPathSha256,actual.filesRootPathSha256);
        assertEquals(expected.filesRootDevice,actual.filesRootDevice);
        assertEquals(expected.filesRootInode,actual.filesRootInode);
        assertEquals(expected.noteUid,actual.noteUid);
        assertEquals(expected.noteLinkCount,actual.noteLinkCount);
        assertEquals(expected.noteDevice,actual.noteDevice);
        assertEquals(expected.noteInode,actual.noteInode);
        assertEquals(expected.noteSize,actual.noteSize);
        assertEquals(expected.noteModifiedSeconds,actual.noteModifiedSeconds);
    }

    /** Real no-follow filesystem identity for host JVM tests; production keeps Android Os.lstat. */
    static final class NioIdentityReader implements LegacyDeleteJournal.FileIdentityReader {
        private final int uid;
        NioIdentityReader(File filesRoot) throws IOException {
            this.uid = ((Number) Files.getAttribute(filesRoot.toPath(), "unix:uid",
                    LinkOption.NOFOLLOW_LINKS)).intValue();
        }
        @Override public int currentUid() { return uid; }
        @Override public LegacyDeleteJournal.Identity lstat(File file) throws IOException {
            try {
                BasicFileAttributes attrs = Files.readAttributes(file.toPath(),
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                int actualUid = ((Number) Files.getAttribute(file.toPath(), "unix:uid",
                        LinkOption.NOFOLLOW_LINKS)).intValue();
                int links = ((Number) Files.getAttribute(file.toPath(), "unix:nlink",
                        LinkOption.NOFOLLOW_LINKS)).intValue();
                long dev = ((Number) Files.getAttribute(file.toPath(), "unix:dev",
                        LinkOption.NOFOLLOW_LINKS)).longValue();
                long ino = ((Number) Files.getAttribute(file.toPath(), "unix:ino",
                        LinkOption.NOFOLLOW_LINKS)).longValue();
                return new LegacyDeleteJournal.Identity(true, attrs.isRegularFile(),
                        attrs.isDirectory(), attrs.isSymbolicLink(), actualUid, links,
                        dev, ino, attrs.size(), attrs.lastModifiedTime().toMillis() / 1000L,
                        file.getCanonicalPath());
            } catch (NoSuchFileException missing) {
                return new LegacyDeleteJournal.Identity(false, false, false, false,
                        -1, 0, -1, -1, 0, 0, file.getAbsolutePath());
            }
        }
        @Override public String sha256RegularNoFollow(File file) throws IOException {
            LegacyDeleteJournal.Identity before=lstat(file);
            if(!before.exists||before.symlink||!before.regular)throw new IOException("DELETE_MARKER_UNSAFE");
            byte[] bytes=readRegularNoFollow(file,64*1024*1024);
            if(!before.sameObject(lstat(file)))throw new IOException("DELETE_MARKER_CHANGED");
            try {
                byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder hex=new StringBuilder(64);
                for(byte value:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&0xff));
                return hex.toString();
            } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        @Override public byte[] readRegularNoFollow(File file, int maximumBytes) throws IOException {
            LegacyDeleteJournal.Identity before = lstat(file);
            if (!before.exists || before.symlink || !before.regular || before.size > maximumBytes)
                throw new IOException("DELETE_MARKER_UNSAFE");
            Set<OpenOption> options = new HashSet<>();
            options.add(StandardOpenOption.READ);
            options.add(LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = Files.newByteChannel(file.toPath(), options);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                ByteBuffer buffer = ByteBuffer.allocate(4096);
                while (channel.read(buffer) >= 0) {
                    buffer.flip();
                    if (bytes.size() + buffer.remaining() > maximumBytes)
                        throw new IOException("DELETE_MARKER_TOO_LARGE");
                    while (buffer.hasRemaining()) bytes.write(buffer.get());
                    buffer.clear();
                }
                return bytes.toByteArray();
            }
        }
    }
}
