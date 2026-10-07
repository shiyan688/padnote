package com.padnote.android;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;

public class LibraryBackupArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void acceptsOnlyTheMeasuredSystemUserZeroAliasShape() {
        int symlink = 0120777, data0771 = 0040771, rootTarget0751 = 0040751;
        assertTrue(LibraryBackupArchive.AndroidSafeFiles.permitsSystemUserZeroAlias(
                "/data/user/0", symlink, 0, "/data/data", rootTarget0751, 0, 0,
                data0771, 1000, 1000, 0040751, 0));
        assertTrue(LibraryBackupArchive.AndroidSafeFiles.permitsSystemUserZeroAlias(
                "/data/user/0", symlink, 0, "/data/data", data0771, 1000, 1000,
                data0771, 1000, 1000, 0040751, 0));
        assertFalse(LibraryBackupArchive.AndroidSafeFiles.permitsSystemUserZeroAlias(
                "/data/user/1", symlink, 0, "/data/data", rootTarget0751, 0, 0,
                data0771, 1000, 1000, 0040751, 0));
        assertFalse(LibraryBackupArchive.AndroidSafeFiles.permitsSystemUserZeroAlias(
                "/data/user/0", symlink, 10207, "/data/data", rootTarget0751, 0, 0,
                data0771, 1000, 1000, 0040751, 0));
        assertFalse(LibraryBackupArchive.AndroidSafeFiles.permitsSystemUserZeroAlias(
                "/data/user/0", symlink, 0, "/tmp/attacker", rootTarget0751, 0, 0,
                data0771, 1000, 1000, 0040751, 0));
        assertFalse(LibraryBackupArchive.AndroidSafeFiles.permitsSystemUserZeroAlias(
                "/data/user/0", symlink, 0, "/data/data", 0040773, 0, 0,
                data0771, 1000, 1000, 0040751, 0));
        assertFalse(LibraryBackupArchive.AndroidSafeFiles.permitsSystemUserZeroAlias(
                "/data/user/0", symlink, 0, "/data/data", data0771, 10207, 20207,
                data0771, 1000, 1000, 0040751, 0)); // app-owned target is not trusted
        assertFalse(LibraryBackupArchive.AndroidSafeFiles.permitsSystemUserZeroAlias(
                "/data/user/0", symlink, 0, "/data/data", rootTarget0751, 0, 0,
                0040773, 1000, 1000, 0040751, 0)); // world-write is never trusted
        assertFalse(LibraryBackupArchive.AndroidSafeFiles.permitsSystemUserZeroAlias(
                "/data/user/0", symlink, 0, "/data/data", rootTarget0751, 0, 0,
                data0771, 1000, 2000, 0040751, 0)); // data root group must be system
        assertFalse(LibraryBackupArchive.AndroidSafeFiles.permitsSystemUserZeroAlias(
                "/data/user/0", symlink, 0, "/data/data", rootTarget0751, 0, 0,
                data0771, 1000, 1000, 0040771, 0)); // user root group-write is not allowed
    }

    @Test public void canonicalPrivateDirectoryPreservesOrdinaryUserZeroPath() throws Exception {
        assertEquals("/data/user/0/com.padnote.android.beta/cache",
                LibraryBackupArchive.AndroidSafeFiles.expectedCanonicalPrivatePath(
                        "/data/user/0/com.padnote.android.beta/cache", false));
        assertEquals("/data/data/com.padnote.android.beta/cache",
                LibraryBackupArchive.AndroidSafeFiles.expectedCanonicalPrivatePath(
                        "/data/user/0/com.padnote.android.beta/cache", true));
        try {
            LibraryBackupArchive.AndroidSafeFiles.expectedCanonicalPrivatePath("/tmp/user-cache", true);
            fail("unrelated path accepted as system alias");
        } catch (IOException expected) { assertEquals("PATH_UNSAFE", expected.getMessage()); }
    }

    @Test public void stagesSharedApplicationFixtureAndCleansOnlyOwnedDirectory() throws Exception {
        File archive = fixture("application-valid/archive/valid-library.zip");
        LibraryBackupArchive.StagedArchive staged = LibraryBackupArchive.stage(archive, temp.getRoot(), null, null, new JvmFiles());
        File directory = staged.directory();
        assertTrue(directory.isDirectory());
        assertEquals(2, staged.manifest().notes.size());
        assertEquals(2, staged.manifest().videoAttachments.size());
        assertEquals(9, staged.manifest().resources.size());
        for (LibraryBackupManifest.Resource resource : staged.manifest().resources) {
            File file = staged.resourceFile(resource.resourceId);
            assertEquals(resource.byteLength, file.length());
            assertEquals(resource.sha256, sha(file));
        }
        staged.close();
        assertFalse(directory.exists());
    }

    @Test public void writeAndRestagePreservesManifestAndResourceBytes() throws Exception {
        File original = fixture("application-valid/archive/valid-library.zip");
        JvmFiles files = new JvmFiles();
        LibraryBackupArchive.StagedArchive staged = LibraryBackupArchive.stage(original, temp.getRoot(), null, null, files);
        File output = new File(temp.getRoot(), "roundtrip.zip");
        Map<String, File> resources = new HashMap<>(staged.resourceFiles());
        LibraryBackupArchive.ArchiveResult result = LibraryBackupArchive.write(staged.manifest(), resources, output, null, null, files);
        assertEquals(output.length(), result.byteLength);
        assertEquals(sha(output), result.sha256);
        LibraryBackupArchive.StagedArchive reread = LibraryBackupArchive.stage(output, temp.getRoot(), null, null, files);
        assertEquals(staged.manifest().resources.size(), reread.manifest().resources.size());
        for (LibraryBackupManifest.Resource resource : staged.manifest().resources) {
            assertEquals(sha(staged.resourceFile(resource.resourceId)), sha(reread.resourceFile(resource.resourceId)));
        }
        staged.close(); reread.close();
    }

    @Test public void rejectsCrcDamageTrailingBytesAndDuplicateJsonKeys() throws Exception {
        File fixture = fixture("application-valid/archive/valid-library.zip");
        byte[] bytes = Files.readAllBytes(fixture.toPath());
        byte[] damaged = bytes.clone();
        damaged[damaged.length / 2] ^= 1;
        File bad = new File(temp.getRoot(), "damaged.zip"); Files.write(bad.toPath(), damaged);
        expectRejected(bad);
        File trailing = new File(temp.getRoot(), "trailing.zip");
        byte[] extra = new byte[bytes.length + 1]; System.arraycopy(bytes, 0, extra, 0, bytes.length); extra[extra.length - 1] = 7;
        Files.write(trailing.toPath(), extra);
        expectRejected(trailing);
        try { LibraryBackupJson.parseCheckedObject("{\"x\":1,\"x\":2}".getBytes("UTF-8"), 32); fail("duplicate key accepted"); }
        catch (IOException expected) { assertEquals("JSON_DUPLICATE_KEY", expected.getMessage()); }
        try { LibraryBackupJson.parseCheckedObject("{\"x\":{\"a\":1,\"\\u0061\":2}}".getBytes("UTF-8"), 64); fail("escaped duplicate accepted"); }
        catch (IOException expected) { assertEquals("JSON_DUPLICATE_KEY", expected.getMessage()); }
    }

    @Test public void rejectsZipPrefixAndNoOverwriteDestination() throws Exception {
        File original = fixture("canonical/valid-library.zip");
        byte[] bytes = Files.readAllBytes(original.toPath());
        File prefixed = new File(temp.getRoot(), "prefix.zip");
        try (FileOutputStream out = new FileOutputStream(prefixed)) { out.write(new byte[] {1, 2, 3}); out.write(bytes); }
        expectRejected(prefixed);

        LibraryBackupArchive.StagedArchive staged = LibraryBackupArchive.stage(original, temp.getRoot(), null, null, new JvmFiles());
        File destination = new File(temp.getRoot(), "exists.zip"); Files.write(destination.toPath(), new byte[] {9});
        try { LibraryBackupArchive.write(staged.manifest(), staged.resourceFiles(), destination, null, null, new JvmFiles()); fail("overwrote destination"); }
        catch (IOException expected) { assertEquals("DESTINATION_EXISTS", expected.getMessage()); }
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(destination.toPath()));
        staged.close();
    }

    @Test public void transferMarkerIsDurableAndCloseLeavesTransferredStage() throws Exception {
        File archive = fixture("canonical/valid-library.zip");
        LibraryBackupArchive.StagedArchive staged = LibraryBackupArchive.stage(archive, temp.getRoot(), null, null, new JvmFiles());
        File directory = staged.directory();
        String markerSha = staged.transferOwnership(UUID.randomUUID().toString());
        assertEquals(64, markerSha.length());
        assertTrue(new File(directory, ".padnote-stage-transfer.json").isFile());
        staged.close();
        assertTrue(directory.isDirectory());
    }

    @Test public void originalTransferOwnerCanExplicitlyCleanupWithoutReopen() throws Exception {
        LibraryBackupArchive.StagedArchive staged = LibraryBackupArchive.stage(
                fixture("canonical/valid-library.zip"), temp.getRoot(), null, null, new JvmFiles());
        File directory = staged.directory();
        staged.transferOwnership(UUID.randomUUID().toString());
        staged.cleanupTransferred();
        assertFalse(directory.exists());
    }

    @Test public void transferredStageCanBeReopenedAndOnlyExplicitOwnerCleanupDeletesIt() throws Exception {
        LibraryBackupArchive.StagedArchive original = LibraryBackupArchive.stage(
                fixture("application-valid/archive/valid-library.zip"), temp.getRoot(), null, null, new JvmFiles());
        File directory = original.directory();
        String tx = UUID.randomUUID().toString();
        String archiveSha = original.archiveSha256();
        String markerSha = original.transferOwnership(tx);
        original.close();
        assertTrue(directory.isDirectory());

        LibraryBackupArchive.StagedArchive resumed = LibraryBackupArchive.openTransferred(
                directory, tx, archiveSha, markerSha, new JvmFiles());
        assertEquals(9, resumed.resourceFiles().size());
        resumed.close();
        assertTrue("normal lifecycle close must retain journal-owned data", directory.isDirectory());
        try {
            LibraryBackupArchive.openTransferred(directory, UUID.randomUUID().toString(), archiveSha,
                    markerSha, new JvmFiles());
            fail("foreign transaction accepted");
        } catch (IOException expected) { assertEquals("STAGE_TRANSFER_BINDING_MISMATCH", expected.getMessage()); }
        LibraryBackupArchive.StagedArchive owner = LibraryBackupArchive.openTransferred(
                directory, tx, archiveSha, markerSha, new JvmFiles());
        owner.cleanupTransferred();
        assertFalse(directory.exists());
    }

    @Test public void transferredStageRejectsWrongMarkerOrChangedResourceWithoutDeletingIt() throws Exception {
        LibraryBackupArchive.StagedArchive original = LibraryBackupArchive.stage(
                fixture("application-valid/archive/valid-library.zip"), temp.getRoot(), null, null, new JvmFiles());
        File directory = original.directory();
        String tx = UUID.randomUUID().toString();
        String archiveSha = original.archiveSha256();
        String markerSha = original.transferOwnership(tx);
        original.close();
        try {
            LibraryBackupArchive.openTransferred(directory, tx, archiveSha, "0".repeat(64), new JvmFiles());
            fail("wrong transfer marker accepted");
        } catch (IOException expected) { assertEquals("STAGE_TRANSFER_BINDING_MISMATCH", expected.getMessage()); }
        File resource = directory.listFiles((parent, name) -> name.endsWith(".bin"))[0];
        Files.write(resource.toPath(), new byte[] {1});
        try {
            LibraryBackupArchive.openTransferred(directory, tx, archiveSha, markerSha, new JvmFiles());
            fail("changed staged resource accepted");
        } catch (IOException expected) { assertEquals("STAGE_RESOURCE_LENGTH", expected.getMessage()); }
        assertTrue("failed validation must not remove the journal's stage", directory.isDirectory());
    }

    @Test public void historicalSourceIdentityDoesNotBreakRestoredAssociations() throws Exception {
        LibraryBackupManifest source = LibraryBackupManifest.parse(Files.readAllBytes(
                fixture("application-valid/archive/valid-manifest.json").toPath()));
        LibraryBackupManifest.VaultEntry v = source.vaultEntries.get(0);
        LibraryBackupManifest.VaultEntry historicalVault = new LibraryBackupManifest.VaultEntry(v.itemId,
                v.noteItemId, v.sourceState, "older-source-id", v.sourceRevisionMs, v.createdAtMs, v.resourceId);
        ArrayList<LibraryBackupManifest.VaultEntry> vaults = new ArrayList<>(source.vaultEntries);
        vaults.set(0, historicalVault);
        LibraryBackupManifest copy = new LibraryBackupManifest(source.createdAtMs, source.producer, source.scope,
                source.notes, vaults, source.videoAttachments, source.coverPresets, source.resources);
        assertEquals("older-source-id", copy.vaultEntries.get(0).sourceNoteId);

        LibraryBackupManifest.VideoAttachment a = source.videoAttachments.get(0);
        LibraryBackupManifest.VideoAttachment restored = new LibraryBackupManifest.VideoAttachment(a.itemId,
                a.noteItemId, a.sourceState, "restored_archive", "older-video-source-id", a.sourceRevisionMs,
                a.sourceRevisionPrecisionMs, a.sourceBundleSha256, a.taskPayloadSha256, a.digestKind,
                a.offlineState, a.taskId, a.remoteTaskId, a.connectionProvenance, a.artifactId, a.displayName,
                a.mediaType, a.byteLength, a.sha256, a.createdAtMs, a.resourceId);
        ArrayList<LibraryBackupManifest.VideoAttachment> videos = new ArrayList<>(source.videoAttachments);
        videos.set(0, restored);
        LibraryBackupManifest restoredCopy = new LibraryBackupManifest(source.createdAtMs, source.producer,
                source.scope, source.notes, source.vaultEntries, videos, source.coverPresets, source.resources);
        assertEquals("older-video-source-id", restoredCopy.videoAttachments.get(0).sourceNoteId);

        LibraryBackupManifest.VideoAttachment invalidComputerTask = new LibraryBackupManifest.VideoAttachment(a.itemId,
                a.noteItemId, a.sourceState, "computer_task", "older-video-source-id", a.sourceRevisionMs,
                a.sourceRevisionPrecisionMs, a.sourceBundleSha256, a.taskPayloadSha256, a.digestKind,
                a.offlineState, a.taskId, a.remoteTaskId, a.connectionProvenance, a.artifactId, a.displayName,
                a.mediaType, a.byteLength, a.sha256, a.createdAtMs, a.resourceId);
        try {
            new LibraryBackupManifest(source.createdAtMs, source.producer, source.scope, source.notes,
                    source.vaultEntries, java.util.Arrays.asList(invalidComputerTask, source.videoAttachments.get(1)),
                    source.coverPresets, source.resources);
            fail("linked computer task source mismatch accepted");
        } catch (IOException expected) { assertEquals("SOURCE_NOTE_ID_MISMATCH", expected.getMessage()); }
    }

    @Test public void deflateDataDescriptorFixtureIsAccepted() throws Exception {
        LibraryBackupArchive.StagedArchive staged = LibraryBackupArchive.stage(
                fixture("application-valid/archive/valid-library.zip"), temp.getRoot(), null, null, new JvmFiles());
        staged.close();
        // Writer uses descriptor records; this assertion also guards the reader/writer wire contract.
        File source = fixture("canonical/valid-library.zip");
        LibraryBackupArchive.StagedArchive canonical = LibraryBackupArchive.stage(source, temp.getRoot(), null, null, new JvmFiles());
        File output = new File(temp.getRoot(), "deflate.zip");
        LibraryBackupArchive.write(canonical.manifest(), canonical.resourceFiles(), output, null, null, new JvmFiles());
        LibraryBackupArchive.StagedArchive reread = LibraryBackupArchive.stage(output, temp.getRoot(), null, null, new JvmFiles());
        assertEquals(canonical.manifest().resources.size(), reread.manifest().resources.size());
        canonical.close(); reread.close();
    }

    @Test public void acceptsDeflateDescriptorWithoutSignature() throws Exception {
        File source = fixture("application-valid/archive/valid-library.zip");
        LibraryBackupArchive.StagedArchive staged = LibraryBackupArchive.stage(source, temp.getRoot(), null, null, new JvmFiles());
        File signed = new File(temp.getRoot(), "signed-descriptor.zip");
        LibraryBackupArchive.write(staged.manifest(), staged.resourceFiles(), signed, null, null, new JvmFiles());
        File unsigned = new File(temp.getRoot(), "unsigned-descriptor.zip");
        Files.write(unsigned.toPath(), removeDescriptorSignatures(Files.readAllBytes(signed.toPath())));
        LibraryBackupArchive.StagedArchive reread = LibraryBackupArchive.stage(unsigned, temp.getRoot(), null, null, new JvmFiles());
        assertEquals(staged.manifest().resources.size(), reread.manifest().resources.size());
        staged.close(); reread.close();
    }

    @Test public void acceptsStoredZipWithoutDataDescriptors() throws Exception {
        File source = fixture("canonical/valid-library.zip");
        File output = new File(temp.getRoot(), "stored-no-descriptor.zip");
        try (java.util.zip.ZipFile in = new java.util.zip.ZipFile(source);
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output))) {
            java.util.Enumeration<? extends ZipEntry> entries = in.entries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                ZipEntry old = entries.nextElement();
                byte[] bytes;
                try (java.io.InputStream stream = in.getInputStream(old); ByteArrayOutputStream copy = new ByteArrayOutputStream()) {
                    int n; while ((n = stream.read(buffer)) >= 0) copy.write(buffer, 0, n); bytes = copy.toByteArray();
                }
                CRC32 crc = new CRC32(); crc.update(bytes);
                ZipEntry entry = new ZipEntry(old.getName()); entry.setMethod(ZipEntry.STORED);
                entry.setSize(bytes.length); entry.setCompressedSize(bytes.length); entry.setCrc(crc.getValue());
                out.putNextEntry(entry); out.write(bytes); out.closeEntry();
            }
        }
        LibraryBackupArchive.StagedArchive staged = LibraryBackupArchive.stage(output, temp.getRoot(), null, null, new JvmFiles());
        assertEquals(9, staged.manifest().resources.size()); staged.close();
    }

    @Test public void failedFinalizationRemovesOnlyTheNewArchiveLinks() throws Exception {
        File source = fixture("canonical/valid-library.zip");
        JvmFiles files = new JvmFiles();
        LibraryBackupArchive.StagedArchive staged = LibraryBackupArchive.stage(source, temp.getRoot(), null, null, files);
        for (boolean failSync : new boolean[] { false, true }) {
            File destination = new File(temp.getRoot(), "fail-finalize-" + failSync + ".zip");
            FailingFinalizeFiles failing = new FailingFinalizeFiles(failSync);
            try { LibraryBackupArchive.write(staged.manifest(), staged.resourceFiles(), destination, null, null, failing); fail("finalization failure hidden"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("INJECTED")); }
            assertFalse(destination.exists());
            File[] leftovers = temp.getRoot().listFiles((dir, name) -> name.endsWith(".partial"));
            assertEquals(0, leftovers == null ? 0 : leftovers.length);
        }
        staged.close();
    }

    @Test public void markerReplacementRefusesChangedReservationAndPreservesIt() throws Exception {
        LibraryBackupArchive.StagedArchive staged=LibraryBackupArchive.stage(fixture("canonical/valid-library.zip"),temp.getRoot(),null,null,new JvmFiles());
        File destination=new File(temp.getRoot(),"changed-marker.zip");
        JvmFiles tampering=new JvmFiles(){
            @Override public void replaceOwnedMarker(File from,File to,byte[] marker,LibraryBackupArchive.FileIdentity markerIdentity)throws IOException {
                try{Files.write(to.toPath(),new byte[]{7,8,9});}catch(IOException e){throw new IOException("INJECTED_TAMPER");}
                super.replaceOwnedMarker(from,to,marker,markerIdentity);
            }
        };
        try{LibraryBackupArchive.write(staged.manifest(),staged.resourceFiles(),destination,null,null,tampering);fail("changed reservation replaced");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("PUBLISH_MARKER_CHANGED"));}
        assertArrayEquals(new byte[]{7,8,9},Files.readAllBytes(destination.toPath()));
        staged.close();
    }

    @Test public void directorySyncFailureAfterAtomicRenamePreservesOnlyVerifiedArchive() throws Exception {
        LibraryBackupArchive.StagedArchive staged=LibraryBackupArchive.stage(fixture("canonical/valid-library.zip"),temp.getRoot(),null,null,new JvmFiles());
        File destination=new File(temp.getRoot(),"directory-sync-uncertain.zip");
        JvmFiles failing=new JvmFiles(){int syncs;@Override public void syncDirectory(File path)throws IOException {if(++syncs==2)throw new IOException("INJECTED_FINAL_DIRECTORY_SYNC");}};
        try{LibraryBackupArchive.write(staged.manifest(),staged.resourceFiles(),destination,null,null,failing);fail("sync failure hidden");}
        catch(IOException expected){assertEquals("INJECTED_FINAL_DIRECTORY_SYNC",expected.getMessage());}
        assertTrue(destination.isFile());
        LibraryBackupArchive.StagedArchive recovered=LibraryBackupArchive.stage(destination,temp.getRoot(),null,null,new JvmFiles());
        assertEquals(staged.manifest().resources.size(),recovered.manifest().resources.size());
        recovered.close();staged.close();
    }

    @Test public void cancellationAfterPartialArchiveWriteCleansOnlyOwnedTemporary() throws Exception {
        LibraryBackupArchive.StagedArchive staged=LibraryBackupArchive.stage(fixture("canonical/valid-library.zip"),temp.getRoot(),null,null,new JvmFiles());
        final int[] checks={0};LibraryBackupArchive.Cancellation cancellation=()->++checks[0]>1;
        File destination=new File(temp.getRoot(),"cancelled.zip");
        try{LibraryBackupArchive.write(staged.manifest(),staged.resourceFiles(),destination,cancellation,null,new JvmFiles());fail("cancel ignored");}
        catch(IOException expected){assertEquals("CANCELLED",expected.getMessage());}
        assertFalse(destination.exists());
        File[] partials=temp.getRoot().listFiles((dir,name)->name.endsWith(".partial"));
        assertEquals(0,partials==null?0:partials.length);staged.close();
    }

    private void expectRejected(File archive) throws Exception {
        try { LibraryBackupArchive.stage(archive, temp.getRoot(), null, null, new JvmFiles()); fail("archive accepted: " + archive.getName()); }
        catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }
    private static File fixture(String relative) {
        File result = new File("../../docs/fixtures/library-backup-r1", relative);
        if (!result.isFile()) result = new File("docs/fixtures/library-backup-r1", relative);
        return result;
    }
    private static String sha(File file) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256"); byte[] b=new byte[65536];
        try(FileInputStream in=new FileInputStream(file)){int n;while((n=in.read(b))>=0)d.update(b,0,n);}
        StringBuilder out=new StringBuilder();for(byte x:d.digest())out.append(String.format("%02x",x&255));return out.toString();
    }
    private static byte[] removeDescriptorSignatures(byte[] zip) throws IOException {
        int eocd=-1;for(int i=zip.length-22;i>=Math.max(0,zip.length-65557);i--)if(u32(zip,i)==0x06054b50L&&i+22+u16(zip,i+20)==zip.length){eocd=i;break;}
        if(eocd<0)throw new IOException("TEST_EOCD");int count=u16(zip,eocd+10),central=(int)u32(zip,eocd+16),centralSize=(int)u32(zip,eocd+12);
        java.util.List<Integer> offsets=new ArrayList<>();java.util.List<Long> compressedSizes=new ArrayList<>();int p=central;for(int i=0;i<count;i++){if(u32(zip,p)!=0x02014b50L)throw new IOException("TEST_CENTRAL");offsets.add((int)u32(zip,p+42));compressedSizes.add(u32(zip,p+20));p+=46+u16(zip,p+28)+u16(zip,p+30)+u16(zip,p+32);}
        java.util.List<Integer> descriptorLocations=new ArrayList<>();for(int i=0;i<offsets.size();i++){int offset=offsets.get(i),nl=u16(zip,offset+26),el=u16(zip,offset+28);int pos=(int)(offset+30L+nl+el+compressedSizes.get(i));if(u32(zip,pos)!=0x08074b50L)throw new IOException("TEST_DESCRIPTOR");descriptorLocations.add(pos);}
        ByteArrayOutputStream out=new ByteArrayOutputStream(zip.length-count*4);int previous=0;for(int location:descriptorLocations){out.write(zip,previous,location-previous);previous=location+4;}out.write(zip,previous,zip.length-previous);byte[] result=out.toByteArray();
        int total=count*4,newCentral=central-total,newEocd=eocd-total;int cursor=newCentral;
        for(int i=0;i<count;i++){put32(result,cursor+42,offsets.get(i)-i*4);cursor+=46+u16(result,cursor+28)+u16(result,cursor+30)+u16(result,cursor+32);}
        put32(result,newEocd+16,newCentral);put32(result,newEocd+12,centralSize);return result;
    }
    private static int u16(byte[] b,int p){return(b[p]&255)|((b[p+1]&255)<<8);}
    private static long u32(byte[] b,int p){return((long)b[p]&255)|(((long)b[p+1]&255)<<8)|(((long)b[p+2]&255)<<16)|(((long)b[p+3]&255)<<24);}
    private static void put32(byte[] b,int p,long v){for(int i=0;i<4;i++)b[p+i]=(byte)(v>>>(8*i));}

    private static final class FailingFinalizeFiles extends JvmFiles {
        final boolean failSync; boolean failed;
        FailingFinalizeFiles(boolean failSync){this.failSync=failSync;}
        @Override public void syncDirectory(File path)throws IOException { if(failSync&&!failed){failed=true;throw new IOException("INJECTED_SYNC");} }
        @Override public void replaceOwnedMarker(File from,File to,byte[] marker,LibraryBackupArchive.FileIdentity markerIdentity)throws IOException { if(!failSync&&!failed){failed=true;throw new IOException("INJECTED_RENAME");}super.replaceOwnedMarker(from,to,marker,markerIdentity); }
    }

    /** Test-only ordinary-file backend. It does not claim to exercise Android Os/no-follow semantics. */
    private static class JvmFiles implements LibraryBackupArchive.SafeFiles {
        private static final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.locks.ReentrantLock> LOCKS=new java.util.concurrent.ConcurrentHashMap<>();
        public LibraryBackupArchive.FileIdentity inspect(File path, boolean directory) throws IOException {
            if (Files.isSymbolicLink(path.toPath())) throw new IOException("FILE_UNSAFE");
            BasicFileAttributes a=Files.readAttributes(path.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(directory!=a.isDirectory() || (!directory&&!a.isRegularFile()))throw new IOException("FILE_UNSAFE");
            return identity(path,a,directory);
        }
        public void validatePrivateDirectory(File path)throws IOException{inspect(path,true);}
        public LibraryBackupArchive.SafeFiles.PublishLock lockPublish(File destination)throws IOException {
            java.util.concurrent.locks.ReentrantLock lock=LOCKS.computeIfAbsent(destination.getAbsolutePath(),key->new java.util.concurrent.locks.ReentrantLock());
            lock.lock();return lock::unlock;
        }
        public void replaceOwnedMarker(File from,File to,byte[] marker,LibraryBackupArchive.FileIdentity markerIdentity)throws IOException {
            LibraryBackupArchive.FileIdentity found=inspect(to,false);
            if(!found.equals(markerIdentity)||found.size!=marker.length||!Arrays.equals(Files.readAllBytes(to.toPath()),marker))throw new IOException("PUBLISH_MARKER_CHANGED");
            try{Files.move(from.toPath(),to.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            catch(java.nio.file.AtomicMoveNotSupportedException e){throw new IOException("ATOMIC_RENAME_UNAVAILABLE");}
        }
        public LibraryBackupArchive.FileIdentity inspectOwnedPair(File path)throws IOException{return inspect(path,false);}
        public LibraryBackupArchive.Seekable openOwnedPair(File path)throws IOException{return openRead(path);}
        public LibraryBackupArchive.Seekable openRead(File path)throws IOException {
            LibraryBackupArchive.FileIdentity before=inspect(path,false); RandomAccessFile raf=new RandomAccessFile(path,"r");
            LibraryBackupArchive.FileIdentity opened=identity(path,Files.readAttributes(path.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS),false);
            if(!before.equals(opened)){raf.close();throw new IOException("FILE_CHANGED");}
            return new LibraryBackupArchive.Seekable(){
                public long size()throws IOException{return raf.length();}
                public void readFully(long offset,byte[] target,int targetOffset,int length)throws IOException{raf.seek(offset);raf.readFully(target,targetOffset,length);}
                public LibraryBackupArchive.FileIdentity openedIdentity()throws IOException{return identity(path,Files.readAttributes(path.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS),false);}
                public LibraryBackupArchive.FileIdentity currentPathIdentity()throws IOException{return inspect(path,false);}
                public void close()throws IOException{raf.close();}
            };
        }
        public FileOutputStream createExclusive(File path)throws IOException{if(!path.createNewFile())throw new IOException("FILE_CREATE_FAILED");return new FileOutputStream(path,false);}
        public void mkdirExclusive(File path)throws IOException{if(!path.mkdir())throw new IOException("DIRECTORY_CREATE_FAILED");}
        public void syncDirectory(File path)throws IOException{}
        public void deleteOwnedTree(File path)throws IOException{if(!path.exists())return;File[] c=path.listFiles();if(c!=null)for(File f:c)deleteOwnedTree(f);if(!path.delete())throw new IOException("STAGE_CLEANUP_FAILED");}
        public void linkNoReplace(File from,File to)throws IOException{try{Files.createLink(to.toPath(),from.toPath());}catch(java.nio.file.FileAlreadyExistsException e){throw new IOException("DESTINATION_CREATE_FAILED");}}
        public void unlink(File path)throws IOException{Files.deleteIfExists(path.toPath());}
        public boolean matchesFile(File path,LibraryBackupArchive.FileIdentity expected)throws IOException{return expected.inode==identity(path,Files.readAttributes(path.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS),false).inode;}
        private static LibraryBackupArchive.FileIdentity identity(File f,BasicFileAttributes a,boolean dir){
            Object key=a.fileKey();long ino=key==null?f.getAbsolutePath().hashCode():key.hashCode();
            return new LibraryBackupArchive.FileIdentity(1,ino,a.size(),a.lastModifiedTime().toMillis()*1_000_000L,a.lastModifiedTime().toMillis()*1_000_000L,1,dir);
        }
    }
}
