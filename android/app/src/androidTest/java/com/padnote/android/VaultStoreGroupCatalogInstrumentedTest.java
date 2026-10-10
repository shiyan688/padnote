package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.padnote.android.streaming.StreamingGroupStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Vault legacy rows require a verified negative group-marker lookup. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class VaultStoreGroupCatalogInstrumentedTest {
    @Test public void legacyFallbackRequiresCompleteOwnerLookupAndPreservesRejectedSidecars() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path fixture=target.getFilesDir().toPath().resolve("vault-owner-fallback-"+UUID.randomUUID());
        Files.createDirectory(fixture);
        Context context=new FixtureContext(target,fixture.toFile());
        try {
            NoteGroupFacade facade=new NoteGroupFacade(context);
            VaultStore vault=new VaultStore(context);

            NoteStore.Entry legacy=NoteStore.create(context,"Verified legacy owner");
            String legacyFile=vault.write(legacy.id,"Legacy Vault",1,legacy.updatedAt,Collections.singletonList("legacy bytes"));
            File legacyPath=vault.fileForBackup(legacyFile);
            byte[] legacyBefore=Files.readAllBytes(legacyPath.toPath());
            File legacyTemp=new File(legacyPath.getParentFile(),legacyPath.getName()+".tmp");
            Files.move(legacyPath.toPath(),legacyTemp.toPath());
            String recoveredLegacy=vault.read(legacyFile);
            assertTrue("direct read recovers a valid tmp-only legacy family without a preceding list",recoveredLegacy.contains("legacy bytes"));
            assertArrayEquals("recovery promotes the original candidate bytes",legacyBefore,Files.readAllBytes(legacyPath.toPath()));
            assertFalse("recovery retires the candidate after successful promotion",legacyTemp.exists());
            assertEquals("an owner with no group marker remains a visible legacy row",1,
                    rowsFor(vault.list(),legacy.id).size());

            NoteStore.Entry mutationOwner=NoteStore.create(context,"Legacy mutation recovery owner");
            String mutationFile=vault.write(mutationOwner.id,"Mutation Vault",1,mutationOwner.updatedAt,Collections.singletonList("delete after recovery"));
            File mutationPath=vault.fileForBackup(mutationFile);
            File mutationTemp=new File(mutationPath.getParentFile(),mutationPath.getName()+".tmp");
            Files.move(mutationPath.toPath(),mutationTemp.toPath());
            vault.delete(mutationFile);
            assertFalse("mutation owner discovery recovers then deletes the legacy primary",mutationPath.exists());
            assertFalse("mutation owner discovery retires the temporary candidate",mutationTemp.exists());

            NoteStore.Entry grouped=NoteStore.create(context,"Managed owner");
            String staleFile=vault.write(grouped.id,"Managed Vault",1,grouped.updatedAt,Collections.singletonList("group bytes"));
            facade.adoptLegacyNote(grouped.id,(old,staged)->{
                assertTrue(staged.memberSizes().keySet().stream().anyMatch(name->name.startsWith("vault/")&&name.endsWith("/content.bin")));
                return true;
            });
            List<VaultStore.VaultNote> afterAdoption=vault.list();
            List<VaultStore.VaultNote> groupRows=rowsFor(afterAdoption,grouped.id);
            assertEquals("committed group content is listed exactly once",1,groupRows.size());
            assertTrue("managed rows use the virtual group locator",GroupAuthorityBridge.isVirtualVaultName(groupRows.get(0).fileName));
            File stalePath=vault.fileForBackup(staleFile);
            byte[] damagedTarget=new byte[]{0x01,0x02,0x03};
            byte[] pendingCandidate=("---\ntitle: stale copy\nnote-id: "+grouped.id+
                    "\npages: 1\ndigitized-epoch: 1\nsource-modified: 1\n---\n\nobsolete\n").getBytes(StandardCharsets.UTF_8);
            File pendingFile=new File(stalePath.getParentFile(),stalePath.getName()+".tmp");
            Files.write(stalePath.toPath(),damagedTarget);
            Files.write(pendingFile.toPath(),pendingCandidate);
            List<VaultStore.VaultNote> strictBackup=vault.listForBackupStrict();
            assertEquals("verified snapshot supplies exactly one managed Vault row despite a damaged old sidecar",
                    1,rowsFor(strictBackup,grouped.id).size());
            assertTrue("the damaged family is never promoted or rewritten during group-backed backup",
                    java.util.Arrays.equals(damagedTarget,Files.readAllBytes(stalePath.toPath()))
                            &&java.util.Arrays.equals(pendingCandidate,Files.readAllBytes(pendingFile.toPath())));
            try { vault.read(staleFile); fail("managed physical sidecar must not be read as legacy content"); }
            catch(java.io.IOException expected) { assertEquals("GROUP_VAULT_STALE_LEGACY_SIDECAR",expected.getMessage()); }

            StreamingGroupStore.Snapshot current=facade.openGroup(grouped.id);
            facade.retireGroup(grouped.id,current.revision,current.digest);
            List<VaultStore.VaultNote> afterRetire=vault.list();
            assertTrue("retired owner is not downgraded to its stale legacy sidecar",rowsFor(afterRetire,grouped.id).isEmpty());
            try { vault.read(staleFile); fail("retired owner sidecar must stay unreadable"); }
            catch(java.io.IOException expected) { assertEquals("GROUP_VAULT_STALE_LEGACY_SIDECAR",expected.getMessage()); }
            assertEquals("a separate proven legacy owner remains visible",1,rowsFor(afterRetire,legacy.id).size());
            List<VaultStore.VaultNote> strictAfterRetire=vault.listForBackupStrict();
            assertTrue("retired group owner is not reclassified as a strict-backup legacy row",rowsFor(strictAfterRetire,grouped.id).isEmpty());
            assertEquals("strict backup retains the unrelated proven legacy row",1,rowsFor(strictAfterRetire,legacy.id).size());
            assertArrayEquals("retirement does not rewrite the damaged primary sidecar",damagedTarget,Files.readAllBytes(stalePath.toPath()));
            assertArrayEquals("retirement does not rewrite the pending sidecar candidate",pendingCandidate,Files.readAllBytes(pendingFile.toPath()));

            NoteStore.Entry damaged=NoteStore.create(context,"Damaged marker owner");
            String damagedFile=vault.write(damaged.id,"Damaged Vault",1,damaged.updatedAt,Collections.singletonList("must stay byte exact"));
            File damagedPath=vault.fileForBackup(damagedFile);
            byte[] damagedBefore=Files.readAllBytes(damagedPath.toPath());
            File damagedTemp=new File(damagedPath.getParentFile(),damagedPath.getName()+".tmp");
            Files.move(damagedPath.toPath(),damagedTemp.toPath());
            File visible=new File(new File(context.getFilesDir(),"note-groups"),"visible");
            assertTrue(visible.isDirectory());
            File badMarker=new File(visible,damaged.id+".marker");
            Files.write(badMarker.toPath(),(damaged.id+"\ntruncated").getBytes(StandardCharsets.UTF_8));
            assertTrue("the damaged group owner is omitted by the ordinary NoteStore catalog",
                    NoteStore.list(context).stream().noneMatch(entry->entry.id.equals(damaged.id)));
            List<VaultStore.VaultNote> catalogUnavailable=vault.list();
            assertTrue("an unreadable marker catalog suppresses every unverified physical fallback",catalogUnavailable.isEmpty());
            assertTrue("the omitted damaged owner is not shown as a legacy row",rowsFor(catalogUnavailable,damaged.id).isEmpty());
            try { vault.listForBackupStrict(); fail("strict backup listing must surface the unreadable owner catalog"); }
            catch(java.io.IOException expected) { assertEquals("VAULT_OWNER_UNVERIFIED",expected.getMessage()); }
            assertFalse("catalog failure does not promote the unverified temporary sidecar",damagedPath.exists());
            assertArrayEquals("catalog failure preserves the unverified candidate bytes",damagedBefore,Files.readAllBytes(damagedTemp.toPath()));
            try { vault.read(damagedFile); fail("unverified owner must not be read through its temporary sidecar"); }
            catch(java.io.IOException expected) { assertEquals("GROUP_VAULT_STALE_LEGACY_SIDECAR",expected.getMessage()); }
            assertFalse("direct read also leaves an unverified family unpromoted",damagedPath.exists());
            assertArrayEquals(damagedBefore,Files.readAllBytes(damagedTemp.toPath()));
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture)) {
                for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);
            }
            assertFalse("cleanup is confined to this UUID fixture",Files.exists(fixture));
        }
    }

    @Test public void recoveryMutationsWaitForSharedGateAndResume() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixture=target.getFilesDir().toPath().resolve("vault-recovery-lock-"+UUID.randomUUID()).toFile();
        assertTrue(fixture.mkdir());
        Context context=new FixtureContext(target,fixture);
        Thread worker=null;
        try {
            VaultStore vault=new VaultStore(context);
            NoteStore.Entry entry=NoteStore.create(context,"Recovery lock owner");
            File vaultDir=new File(context.getFilesDir(),"vault");assertTrue(vaultDir.isDirectory());
            String fileName="recovery-lock.md";
            File temporary=new File(vaultDir,fileName+".tmp");
            byte[] original=("---\ntitle: Recovery\nnote-id: "+entry.id+"\npages: 1\ndigitized-epoch: 1\nsource-modified: 1\n---\n\nbody\n").getBytes(StandardCharsets.UTF_8);
            Files.write(temporary.toPath(),original);
            CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
            worker=new Thread(()->{try{vault.listForShelf(Collections.singletonList(entry));}catch(Throwable error){failure.set(error);}finally{done.countDown();}},"vault-recovery-gate-probe");
            try(LegacyGroupMutationLock.Lease ignored=LegacyGroupMutationLock.acquire()){
                worker.start();
                assertFalse("Vault recovery must wait for the shared legacy-writer fence",done.await(250,TimeUnit.MILLISECONDS));
                assertTrue("the uncommitted recovery candidate remains untouched while the gate is held",temporary.isFile());
            }
            assertTrue("Vault recovery resumes after the gate is released",done.await(5,TimeUnit.SECONDS));
            worker.join(1000);assertFalse(worker.isAlive());
            if(failure.get()!=null)throw new AssertionError("vault list failed after recovery fence release",failure.get());
            assertTrue("validated candidate is recovered into its target",new File(vaultDir,fileName).isFile());
            assertFalse("recovery candidate is retired only after validation",temporary.exists());
        } finally {
            if(worker!=null&&worker.isAlive()){worker.interrupt();worker.join(1000);}
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture.toPath())) {
                for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);
            }
        }
    }

    private static List<VaultStore.VaultNote> rowsFor(List<VaultStore.VaultNote> rows,String noteId) {
        java.util.ArrayList<VaultStore.VaultNote> found=new java.util.ArrayList<>();
        for(VaultStore.VaultNote row:rows)if(noteId.equals(row.noteId))found.add(row);
        return found;
    }

    private static final class FixtureContext extends ContextWrapper {
        private final File files;
        FixtureContext(Context base,File files){super(base);this.files=files;}
        @Override public File getFilesDir(){return files;}
        @Override public File getDir(String name,int mode){
            if(name==null||!name.matches("[A-Za-z0-9._-]{1,64}"))throw new IllegalArgumentException("fixture_dir_name");
            File dir=new File(files,"app_"+name);
            if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("fixture_dir_create_failed");
            if(!dir.isDirectory()||java.nio.file.Files.isSymbolicLink(dir.toPath()))throw new IllegalStateException("fixture_dir_unsafe");
            return dir;
        }
        @Override public Context getApplicationContext(){return this;}
    }
}
