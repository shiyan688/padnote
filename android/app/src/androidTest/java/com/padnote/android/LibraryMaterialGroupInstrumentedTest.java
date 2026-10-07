package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Real Android filesystem/media verification for the isolated material-only prototype. */
@RunWith(AndroidJUnit4.class)
public final class LibraryMaterialGroupInstrumentedTest {
    private static final String FIXTURE_SHA256 = "82d7bb7794e5c56d4b3ec55a498000ba6bb3e365795c467616ea553ada518abe";
    private static final String LINKED_VIDEO = "a-11111111111111111111111111111111";
    private static final String DELETED_VIDEO = "a-22222222222222222222222222222222";
    private static final String LINKED_VAULT = "v-11111111111111111111111111111111";
    private static final String DELETED_VAULT = "v-22222222222222222222222222222222";
    private static final String PRESET = "c-11111111111111111111111111111111";

    @Test public void realArchivePdfPngMp4AndFullProofSetBecomeVisibleOnlyAfterCommit() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(FIXTURE_SHA256, sha256(read(f.archiveInput)));
            LibraryBackupManifest manifest = f.archive.manifest();
            assertEquals(2, manifest.notes.size());
            assertEquals(2, manifest.vaultEntries.size());
            assertEquals(2, manifest.videoAttachments.size());
            assertEquals(1, manifest.coverPresets.size());

            LibraryBackupManifest.Resource pdf = resourceByRole(manifest, "pdf_original");
            JSONObject parsedPdf = PdfNoteIO.pdfDocument(f.archive.resourceFile(pdf.resourceId), "native fixture");
            assertEquals(1, parsedPdf.getInt("pdfPageCount"));

            RestoredLibraryMaterialStore store = new RestoredLibraryMaterialStore(f.materialRoot);
            LibraryBackupArchive.AndroidSafeFiles safe = new LibraryBackupArchive.AndroidSafeFiles();
            LibraryMaterialGroupJournal journal = new LibraryMaterialGroupJournal(f.journalRoot, safe, store);

            String videoNote = UUID.randomUUID().toString();
            assertCommittedMaterial(f, journal, store, LINKED_VIDEO,
                    RestoredLibraryMaterialStore.Kind.VIDEO, videoNote);
            String vaultNote = UUID.randomUUID().toString();
            assertCommittedMaterial(f, journal, store, LINKED_VAULT,
                    RestoredLibraryMaterialStore.Kind.VAULT, vaultNote);
            assertCommittedMaterial(f, journal, store, PRESET,
                    RestoredLibraryMaterialStore.Kind.COVER_PRESET, null);

            // The checks above are material-only groups. They do not claim note/PDF/assigned-cover
            // atomic visibility in a restored note group.
            assertTrue(safe.canonicalPrivateAndroidDirectory(f.root).isDirectory());
            logPrivateMode("candidate-root", f.root);
            logPrivateMode("material-root", f.materialRoot);
        }
    }

    @Test public void emptyAndPartialGroupsRollbackWithoutExposingOrRemovingFixtureBytes() throws Exception {
        try (Fixture f = new Fixture()) {
            RestoredLibraryMaterialStore store = new RestoredLibraryMaterialStore(f.materialRoot);
            LibraryBackupArchive.AndroidSafeFiles safe = new LibraryBackupArchive.AndroidSafeFiles();
            LibraryMaterialGroupJournal journal = new LibraryMaterialGroupJournal(f.journalRoot, safe, store);
            byte[] untouchedArchive = read(f.archiveInput);

            List<LibraryMaterialGroupJournal.MemberRequest> requests = new ArrayList<>();
            requests.add(new LibraryMaterialGroupJournal.MemberRequest(DELETED_VIDEO,
                    RestoredLibraryMaterialStore.Kind.VIDEO, null));
            requests.add(new LibraryMaterialGroupJournal.MemberRequest(DELETED_VAULT,
                    RestoredLibraryMaterialStore.Kind.VAULT, null));
            String tx = UUID.randomUUID().toString();
            LibraryMaterialGroupJournal.GroupPlan partial = journal.ensurePendingGroup(
                    tx, "native-partial-" + tx.replace("-", ""), requests);
            LibraryMaterialGroupJournal.Member first = member(partial, DELETED_VIDEO);
            try (LibraryMaterialGroupJournal.GroupLease lease = journal.acquire(partial)) {
                RestoredLibraryMaterialStore.Prepared prepared = store.prepareVideo(tx, partial.groupId,
                        first.localId, null, DELETED_VIDEO, f.archive);
                RestoredLibraryMaterialStore.CommitProof proof = prepared.commitProof();
                lease.registerProof(proof);
                store.promote(prepared);
                assertTrue(store.listDetailed(lease.commitGate()).records.isEmpty());
                lease.markRollbackInProgress();
                store.rollback(proof, lease.commitGate(), lease.pendingRollbackGate());
                lease.markRolledBack();
                assertFalse(lease.commitGate().isCommitted(proof));
            }
            assertArrayEquals(untouchedArchive, read(f.archiveInput));

            String emptyTx = UUID.randomUUID().toString();
            LibraryMaterialGroupJournal.GroupPlan emptyProof = journal.ensurePendingGroup(
                    emptyTx, "native-empty-" + emptyTx.replace("-", ""), Collections.singletonList(
                            new LibraryMaterialGroupJournal.MemberRequest(DELETED_VIDEO,
                                    RestoredLibraryMaterialStore.Kind.VIDEO, null)));
            try (LibraryMaterialGroupJournal.GroupLease lease = journal.acquire(emptyProof)) {
                lease.markRollbackInProgress();
                lease.markRolledBack();
                assertFalse(new File(lease.directory, "committed.json").exists());
            }
        }
    }

    @Test public void unknownCommitTempAndTornReservationAreRefusedAndPreserved() throws Exception {
        try (Fixture f = new Fixture()) {
            RestoredLibraryMaterialStore store = new RestoredLibraryMaterialStore(f.materialRoot);
            LibraryBackupArchive.AndroidSafeFiles safe = new LibraryBackupArchive.AndroidSafeFiles();
            LibraryMaterialGroupJournal journal = new LibraryMaterialGroupJournal(f.journalRoot, safe, store);

            String tx = UUID.randomUUID().toString();
            LibraryMaterialGroupJournal.GroupPlan plan = oneVideoGroup(journal, tx, DELETED_VIDEO, null);
            LibraryMaterialGroupJournal.Member member = plan.members.get(0);
            File groupDir = groupDir(f.journalRoot, plan);
            File unknownTemp = new File(groupDir, ".commit-" + UUID.randomUUID() + ".tmp");
            byte[] unknownBytes = "unknown-native-temp".getBytes(StandardCharsets.UTF_8);
            writeSynced(unknownTemp, unknownBytes);
            try (LibraryMaterialGroupJournal.GroupLease lease = journal.acquire(plan)) {
                RestoredLibraryMaterialStore.Prepared prepared = store.prepareVideo(tx, plan.groupId,
                        member.localId, null, DELETED_VIDEO, f.archive);
                lease.registerProof(prepared.commitProof());
                store.promote(prepared);
                assertIOExceptionCode("MATERIAL_GROUP_COMMIT_RECOVERY_NEEDED", lease::commit);
                assertArrayEquals(unknownBytes, read(unknownTemp));
                assertTrue(store.listDetailed(lease.commitGate()).records.isEmpty());
            }

            String tornTx = UUID.randomUUID().toString();
            LibraryMaterialGroupJournal.GroupPlan torn = oneVideoGroup(journal, tornTx, DELETED_VIDEO, null);
            LibraryMaterialGroupJournal.Member tornMember = torn.members.get(0);
            File tornDir = groupDir(f.journalRoot, torn);
            File reservation = new File(tornDir, "committed.json");
            byte[] tornBytes = "{}".getBytes(StandardCharsets.UTF_8);
            writeSynced(reservation, tornBytes);
            try (LibraryMaterialGroupJournal.GroupLease lease = journal.acquire(torn)) {
                RestoredLibraryMaterialStore.Prepared prepared = store.prepareVideo(tornTx, torn.groupId,
                        tornMember.localId, null, DELETED_VIDEO, f.archive);
                lease.registerProof(prepared.commitProof());
                store.promote(prepared);
                assertIOExceptionCode("MATERIAL_GROUP_COMMIT_MISMATCH", lease::commit);
                assertArrayEquals(tornBytes, read(reservation));
            }
        }
    }

    @Test public void completeRenamedCommitMarkerIsResyncedAfterNativeDirectorySyncFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            RestoredLibraryMaterialStore store = new RestoredLibraryMaterialStore(f.materialRoot);
            NativeFaultFiles safe = new NativeFaultFiles();
            LibraryMaterialGroupJournal journal = new LibraryMaterialGroupJournal(f.journalRoot, safe, store);
            String tx = UUID.randomUUID().toString();
            LibraryMaterialGroupJournal.GroupPlan plan = oneVideoGroup(journal, tx, DELETED_VIDEO, null);
            LibraryMaterialGroupJournal.Member member = plan.members.get(0);
            File groupDir = groupDir(f.journalRoot, plan);
            try (LibraryMaterialGroupJournal.GroupLease lease = journal.acquire(plan)) {
                RestoredLibraryMaterialStore.Prepared prepared = store.prepareVideo(tx, plan.groupId,
                        member.localId, null, DELETED_VIDEO, f.archive);
                RestoredLibraryMaterialStore.CommitProof proof = prepared.commitProof();
                lease.registerProof(proof);
                store.promote(prepared);
                safe.failOnceAfterFinalMarkerRename(groupDir);
                assertIOExceptionCode("NATIVE_TEST_FINAL_COMMIT_DIRSYNC", lease::commit);
                assertTrue(safe.failureWasInjected());
                File committed = new File(groupDir, "committed.json");
                assertTrue(committed.isFile());
                assertTrue(new String(read(committed), StandardCharsets.UTF_8).contains("\"proofs\""));
                assertTrue(lease.commitGate().isCommitted(proof));
                lease.commit();
                assertTrue(lease.commitGate().isCommitted(proof));
                assertEquals(1, store.listDetailed(lease.commitGate()).records.size());
            }
        }
    }

    @Test public void androidSafeFilesRejectsParentLeafAndDanglingSymlinks() throws Exception {
        try (Fixture f = new Fixture()) {
            LibraryBackupArchive.AndroidSafeFiles safe = new LibraryBackupArchive.AndroidSafeFiles();
            File regular = new File(f.root, "safe-regular.bin");
            writeSynced(regular, new byte[]{1, 2, 3});

            File leafLink = new File(f.root, "leaf-link.bin");
            Os.symlink(regular.getAbsolutePath(), leafLink.getAbsolutePath());
            assertIOExceptionCode("FILE_UNSAFE", () -> safe.inspect(leafLink, false));

            File dangling = new File(f.root, "dangling-link.bin");
            Os.symlink(new File(f.root, "absent-target.bin").getAbsolutePath(), dangling.getAbsolutePath());
            assertFalse("lstat distinguishes dangling link from ENOENT", safe.isAbsentNoFollow(dangling));
            assertIOExceptionCode("FILE_UNSAFE", () -> safe.openRead(dangling));

            File realDirectory = new File(f.root, "real-parent");
            mkdirPrivate(realDirectory);
            File parentLink = new File(f.root, "linked-parent");
            Os.symlink(realDirectory.getAbsolutePath(), parentLink.getAbsolutePath());
            assertIOExceptionCode("PATH_UNSAFE", () -> safe.validatePrivateDirectory(new File(parentLink, "child")));
            assertTrue(realDirectory.isDirectory());
        }
    }

    private static void assertCommittedMaterial(Fixture f, LibraryMaterialGroupJournal journal,
            RestoredLibraryMaterialStore store, String sourceItemId,
            RestoredLibraryMaterialStore.Kind kind, String currentNoteId) throws Exception {
        String tx = UUID.randomUUID().toString();
        String sourceGroup = "native-item-" + sourceItemId.substring(2);
        LibraryMaterialGroupJournal.GroupPlan plan = journal.ensurePendingGroup(tx, sourceGroup,
                Collections.singletonList(new LibraryMaterialGroupJournal.MemberRequest(sourceItemId, kind, currentNoteId)));
        LibraryMaterialGroupJournal.Member member = plan.members.get(0);
        try (LibraryMaterialGroupJournal.GroupLease lease = journal.acquire(plan)) {
            RestoredLibraryMaterialStore.Prepared prepared;
            switch (kind) {
                case VIDEO:
                    prepared = store.prepareVideo(tx, plan.groupId, member.localId, currentNoteId, sourceItemId, f.archive);
                    break;
                case VAULT:
                    prepared = store.prepareVault(tx, plan.groupId, member.localId, currentNoteId, sourceItemId, f.archive);
                    break;
                case COVER_PRESET:
                    prepared = store.preparePreset(tx, plan.groupId, member.localId, sourceItemId, f.archive);
                    break;
                default: throw new AssertionError("unexpected kind");
            }
            RestoredLibraryMaterialStore.CommitProof proof = prepared.commitProof();
            assertFalse(lease.commitGate().isCommitted(proof));
            lease.registerProof(proof);
            store.promote(prepared);
            assertTrue("pending rows must remain hidden", store.listDetailed(lease.commitGate()).records.isEmpty());
            lease.commit();
            assertTrue(lease.commitGate().isCommitted(proof));
            RestoredLibraryMaterialStore.ListResult visible = store.listDetailed(lease.commitGate());
            assertEquals(1, visible.records.size());
            assertEquals(proof.localId, visible.records.get(0).localId);
            assertEquals(proof.payloadSha256, visible.records.get(0).sha256);
            if (kind == RestoredLibraryMaterialStore.Kind.VAULT) {
                byte[] vault = store.readVault(member.localId, lease.commitGate());
                assertTrue(new String(vault, StandardCharsets.UTF_8).contains("\"markdown\""));
            }
            File copied = new File(f.exportRoot, "copy-" + member.localId + extension(kind));
            store.copyForShare(member.localId, copied, lease.commitGate());
            assertEquals(proof.payloadSha256, sha256(read(copied)));
            assertTrue(copied.length() > 0);
        }
        RestoredLibraryMaterialStore reopenedStore = new RestoredLibraryMaterialStore(f.materialRoot);
        try (LibraryMaterialGroupJournal.GroupLease reopened = new LibraryMaterialGroupJournal(
                f.journalRoot, new LibraryBackupArchive.AndroidSafeFiles(), reopenedStore).acquire(tx, plan.groupId)) {
            RestoredLibraryMaterialStore.ListResult rows = reopenedStore.listDetailed(reopened.commitGate());
            assertEquals(1, rows.records.size());
            assertEquals(kind, rows.records.get(0).kind);
            assertEquals(currentNoteId, rows.records.get(0).currentLocalNoteId);
        }
    }

    private static LibraryMaterialGroupJournal.GroupPlan oneVideoGroup(LibraryMaterialGroupJournal journal,
            String tx, String item, String currentNote) throws Exception {
        String sourceKey = "native-one-" + UUID.randomUUID().toString().replace("-", "");
        return journal.ensurePendingGroup(tx, sourceKey, Collections.singletonList(
                new LibraryMaterialGroupJournal.MemberRequest(item, RestoredLibraryMaterialStore.Kind.VIDEO, currentNote)));
    }

    private static LibraryMaterialGroupJournal.Member member(LibraryMaterialGroupJournal.GroupPlan plan, String item) {
        for (LibraryMaterialGroupJournal.Member member : plan.members) if (item.equals(member.sourceItemId)) return member;
        throw new AssertionError("planned member missing");
    }

    private static File groupDir(File root, LibraryMaterialGroupJournal.GroupPlan plan) {
        return new File(new File(new File(root, plan.transactionId), "groups"), plan.groupId);
    }

    private static LibraryBackupManifest.Resource resourceByRole(LibraryBackupManifest manifest, String role) {
        for (LibraryBackupManifest.Resource resource : manifest.resources) if (role.equals(resource.role)) return resource;
        throw new AssertionError("resource role missing: " + role);
    }

    private static String extension(RestoredLibraryMaterialStore.Kind kind) {
        if (kind == RestoredLibraryMaterialStore.Kind.VIDEO) return ".mp4";
        if (kind == RestoredLibraryMaterialStore.Kind.COVER_PRESET) return ".png";
        return ".json";
    }

    private static void assertIOExceptionCode(String code, CheckedAction action) throws Exception {
        try { action.run(); fail("expected " + code); }
        catch (IOException expected) { assertEquals(code, expected.getMessage()); }
    }

    private static void mkdirPrivate(File directory) throws Exception {
        assertTrue("exclusive private directory " + directory.getName(), directory.mkdir());
        Os.chmod(directory.getAbsolutePath(), 0700);
        new LibraryBackupArchive.AndroidSafeFiles().canonicalPrivateAndroidDirectory(directory);
    }

    private static void writeSynced(File destination, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(destination)) {
            out.write(bytes); out.flush(); out.getFD().sync();
        }
        Os.chmod(destination.getAbsolutePath(), 0600);
    }

    private static byte[] read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) >= 0) if (count > 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    private static void logPrivateMode(String label, File path) throws Exception {
        StructStat s = Os.lstat(path.getAbsolutePath());
        System.out.println("MATERIAL_ANDROID_MODE label=" + label + " mode=0x" +
                Integer.toHexString(s.st_mode) + " uid=" + s.st_uid + " gid=" + s.st_gid +
                " dev=" + s.st_dev + " dir=" + OsConstants.S_ISDIR(s.st_mode) +
                " symlink=" + OsConstants.S_ISLNK(s.st_mode));
    }

    private interface CheckedAction { void run() throws Exception; }

    private static final class NativeFaultFiles implements LibraryBackupArchive.SafeFiles {
        private final LibraryBackupArchive.AndroidSafeFiles delegate = new LibraryBackupArchive.AndroidSafeFiles();
        private File finalCommitDirectory;
        private boolean armed, injected;
        void failOnceAfterFinalMarkerRename(File directory) { finalCommitDirectory = directory; armed = true; }
        boolean failureWasInjected() { return injected; }
        @Override public LibraryBackupArchive.FileIdentity inspect(File p, boolean d) throws IOException { return delegate.inspect(p, d); }
        @Override public void validatePrivateDirectory(File p) throws IOException { delegate.validatePrivateDirectory(p); }
        @Override public LibraryBackupArchive.Seekable openRead(File p) throws IOException { return delegate.openRead(p); }
        @Override public PublishLock lockPublish(File p) throws IOException { return delegate.lockPublish(p); }
        @Override public LibraryBackupArchive.FileIdentity inspectOwnedPair(File p) throws IOException { return delegate.inspectOwnedPair(p); }
        @Override public LibraryBackupArchive.Seekable openOwnedPair(File p) throws IOException { return delegate.openOwnedPair(p); }
        @Override public void linkNoReplace(File a, File b) throws IOException { delegate.linkNoReplace(a, b); }
        @Override public boolean isAbsentNoFollow(File p) throws IOException { return delegate.isAbsentNoFollow(p); }
        @Override public FileOutputStream createExclusive(File p) throws IOException { return delegate.createExclusive(p); }
        @Override public void mkdirExclusive(File p) throws IOException { delegate.mkdirExclusive(p); }
        @Override public void syncDirectory(File p) throws IOException {
            if (armed && !injected && finalCommitDirectory.equals(p)) {
                File marker = new File(p, "committed.json");
                if (marker.isFile()) {
                    try {
                        String bytes = new String(read(marker), StandardCharsets.UTF_8);
                        if (bytes.contains("\"proofs\"")) {
                            injected = true; armed = false;
                            throw new IOException("NATIVE_TEST_FINAL_COMMIT_DIRSYNC");
                        }
                    } catch (IOException failure) {
                        if ("NATIVE_TEST_FINAL_COMMIT_DIRSYNC".equals(failure.getMessage())) throw failure;
                        throw failure;
                    } catch (Exception failure) { throw new IOException("NATIVE_TEST_PROBE_FAILED"); }
                }
            }
            delegate.syncDirectory(p);
        }
        @Override public void syncExistingFile(File p, LibraryBackupArchive.FileIdentity id) throws IOException { delegate.syncExistingFile(p, id); }
        @Override public void deleteOwnedTree(File p) throws IOException { delegate.deleteOwnedTree(p); }
        @Override public void replaceOwnedMarker(File a, File b, byte[] marker, LibraryBackupArchive.FileIdentity id) throws IOException { delegate.replaceOwnedMarker(a, b, marker, id); }
        @Override public void unlink(File p) throws IOException { delegate.unlink(p); }
        @Override public boolean matchesFile(File p, LibraryBackupArchive.FileIdentity id) throws IOException { return delegate.matchesFile(p, id); }
    }

    private static final class Fixture implements AutoCloseable {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File root, archiveInput, stageParent, journalRoot, materialRoot, exportRoot;
        final LibraryBackupArchive.StagedArchive archive;
        private boolean closed;
        Fixture() throws Exception {
            File files = context.getFilesDir().getCanonicalFile();
            root = new File(files, "library-material-native-r2-" + UUID.randomUUID());
            LibraryBackupArchive.StagedArchive staged = null;
            try {
                mkdirPrivate(root);
                stageParent = new File(root, "stage-parent"); mkdirPrivate(stageParent);
                journalRoot = new File(root, "journal"); mkdirPrivate(journalRoot);
                materialRoot = new File(root, "materials"); mkdirPrivate(materialRoot);
                exportRoot = new File(root, "exports"); mkdirPrivate(exportRoot);
                archiveInput = new File(root, "fixture.zip");
                try (java.io.InputStream in = InstrumentationRegistry.getInstrumentation().getContext()
                        .getAssets().open("library-backup-r1/valid-library.zip");
                     FileOutputStream out = new FileOutputStream(archiveInput)) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = in.read(buffer)) >= 0) if (count > 0) out.write(buffer, 0, count);
                    out.flush(); out.getFD().sync();
                }
                Os.chmod(archiveInput.getAbsolutePath(), 0600);
                staged = LibraryBackupArchive.stage(archiveInput, stageParent, null, null);
            } catch (Exception failure) {
                if (staged != null) try { staged.close(); } catch (Exception ignored) { }
                if (root.exists()) try { new LibraryBackupArchive.AndroidSafeFiles().deleteOwnedTree(root); }
                catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            }
            archive = staged;
        }
        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            if (!root.getName().matches("library-material-native-r2-[0-9a-f-]{36}"))
                throw new IOException("NATIVE_TEST_CLEANUP_SCOPE_INVALID");
            try { archive.close(); }
            finally { new LibraryBackupArchive.AndroidSafeFiles().deleteOwnedTree(root); }
        }
    }
}
