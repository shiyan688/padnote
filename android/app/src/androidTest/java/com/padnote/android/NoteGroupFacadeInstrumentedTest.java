package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.padnote.android.streaming.StreamingGroupStore;
import com.padnote.android.streaming.LegacyFileCapture;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real NoteStore/material stores -> staged full group -> marker adoption; test-only isolated files root. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class NoteGroupFacadeInstrumentedTest {
    @Test public void shelfListingUsesOneFreshLinearCatalogAndFailsClosedOnCorruptGroupMarker() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path fixture=target.getFilesDir().toPath().resolve("note-group-shelf-reader-"+UUID.randomUUID());
        Files.createDirectory(fixture);FixtureContext context=new FixtureContext(target,fixture.toFile());
        try {
            NoteStore.Entry first=NoteStore.create(context,"Shelf reader first");
            NoteStore.Entry second=NoteStore.create(context,"Shelf reader second");
            NoteGroupFacade facade=new NoteGroupFacade(context);
            StreamingGroupStore.Snapshot firstGroup=facade.adoptLegacyNote(first.id,(old,staged)->true);
            StreamingGroupStore.Snapshot secondGroup=facade.adoptLegacyNote(second.id,(old,staged)->true);

            List<NoteStore.Entry> visible=NoteStore.list(context);
            assertEquals("all healthy grouped rows remain visible",2,visible.size());
            JSONObject firstBodyMetadata=new JSONObject(new String(firstGroup.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
            JSONObject secondBodyMetadata=new JSONObject(new String(secondGroup.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
            java.util.Optional<NoteStore.Entry> visibleFirstResult=visible.stream().filter(row->first.id.equals(row.id)).findFirst();
            java.util.Optional<NoteStore.Entry> visibleSecondResult=visible.stream().filter(row->second.id.equals(row.id)).findFirst();
            assertTrue(visibleFirstResult.isPresent());assertTrue(visibleSecondResult.isPresent());
            NoteStore.Entry visibleFirst=visibleFirstResult.get(),visibleSecond=visibleSecondResult.get();
            assertEquals(firstBodyMetadata.optString("title"),visibleFirst.title);assertEquals(firstBodyMetadata.optLong("updatedAt"),visibleFirst.updatedAt);
            assertEquals(firstBodyMetadata.getJSONArray("strokes").length(),visibleFirst.strokeCount);assertEquals(firstBodyMetadata.optInt("pageCount",1),visibleFirst.pageCount);
            assertEquals(secondBodyMetadata.optString("title"),visibleSecond.title);assertEquals(secondBodyMetadata.optLong("updatedAt"),visibleSecond.updatedAt);
            assertEquals(secondBodyMetadata.getJSONArray("strokes").length(),visibleSecond.strokeCount);assertEquals(secondBodyMetadata.optInt("pageCount",1),visibleSecond.pageCount);
            JSONObject healthy=new JSONObject(NoteStore.listDiagnosticForTest(context));
            assertEquals("one invocation-local facade/store serves reconciliation and projection",1,healthy.getInt("group_reader_instances"));
            assertEquals("the same invocation-local catalog serves reconciliation",1,healthy.getInt("reconcile_reader_instances"));
            assertEquals("each indexed row consumes the catalog result",2,healthy.getInt("group_lookups"));
            assertEquals(2,healthy.getInt("total"));assertEquals(2,healthy.getInt("visited"));assertEquals(0,healthy.getInt("rejected"));
            JSONObject healthyProfile=healthy.getJSONObject("profile");
            assertEquals("catalog-profile-v1",healthyProfile.getString("profile_schema"));
            assertTrue("completed list has no running profile stage",healthyProfile.getString("active_stage").isEmpty());
            assertEquals(0,healthyProfile.getLong("active_stage_elapsed_ns"));
            assertEquals(2,healthyProfile.getInt("note_files_seen"));
            assertEquals(2,healthyProfile.getInt("note_files_eligible"));
            assertEquals("one complete catalog lookup is shared by both list phases",1,healthyProfile.getInt("lookups_started"));
            assertEquals("two bounded inventories detect changes during group verification",2,healthyProfile.getInt("marker_scans"));
            assertEquals(healthyProfile.getInt("lookups_started"),healthyProfile.getInt("lookups_completed"));
            assertEquals("two markers are visited in each inventory, independent of requested row count",4,healthyProfile.getLong("marker_entries_examined"));
            assertTrue(healthyProfile.getLong("marker_bytes_read")>0);
            assertEquals("each requested current marker is rechecked while verifying its group",2,healthyProfile.getLong("marker_record_reads"));
            assertTrue(healthyProfile.getLong("marker_entry_scan_ns")>0);
            assertEquals("every current group is verified exactly once for this list",2,healthyProfile.getLong("group_verifications"));
            long expectedFullHashes=uniqueMemberHashCount(firstGroup)+uniqueMemberHashCount(secondGroup);
            long expectedWitnessRechecks=memberCount(firstGroup)+memberCount(secondGroup)-expectedFullHashes;
            assertEquals("each distinct object is freshly hashed once per group, even when another group shares it",
                    expectedFullHashes,healthyProfile.getLong("object_full_hashes"));
            assertTrue(healthyProfile.getLong("object_full_hash_bytes")>0);
            assertEquals("only duplicate members inside one already-verified group may use a witness",
                    expectedWitnessRechecks,healthyProfile.getLong("object_witness_rechecks"));
            assertTrue(healthyProfile.getLong("reader_constructor_ns")>0);
            assertTrue(healthyProfile.getLong("reconciliation_ns")>0);
            assertTrue(healthyProfile.getLong("projection_ns")>0);
            Bundle profileStatus=new Bundle();profileStatus.putString("padnote_catalog_profile",healthyProfile.toString());
            profileStatus.putString("padnote_catalog_elapsed_ms",Long.toString(healthy.getLong("elapsed_ms")));
            InstrumentationRegistry.getInstrumentation().sendStatus(2,profileStatus);
            long terminalElapsed=healthy.getLong("elapsed_ms");
            JSONObject repeated=new JSONObject(NoteStore.listDiagnosticForTest(context));
            assertEquals("terminal list elapsed does not grow after completion",terminalElapsed,repeated.getLong("elapsed_ms"));
            JSONObject repeatedProfile=repeated.getJSONObject("profile");
            assertEquals(healthyProfile.getLong("marker_entries_examined"),repeatedProfile.getLong("marker_entries_examined"));
            assertEquals(healthyProfile.getLong("marker_entry_scan_ns"),repeatedProfile.getLong("marker_entry_scan_ns"));
            assertEquals(healthyProfile.getLong("object_full_hash_bytes"),repeatedProfile.getLong("object_full_hash_bytes"));
            assertEquals(healthyProfile.getLong("object_witness_rechecks"),repeatedProfile.getLong("object_witness_rechecks"));
            try(LegacyGroupMutationLock.Lease ignored=LegacyGroupMutationLock.acquire()) {
                synchronized(NoteStore.class) {
                    GroupAuthorityBridge.CatalogReader scoped=GroupAuthorityBridge.catalogReader(context);
                    scoped.prepareVisibleCatalog(Collections.singleton("unrelated-local-id"));
                    try { scoped.shelfMetadataIfManaged(first.id);fail("a present but unrequested marker cannot appear absent"); }
                    catch(java.io.IOException notRequested) { assertEquals("GROUP_CATALOG_ID_NOT_REQUESTED",notRequested.getMessage()); }
                }
            }
            try(LegacyGroupMutationLock.Lease ignored=LegacyGroupMutationLock.acquire()) {
                synchronized(NoteStore.class) {
                    NoteGroupFacade scopedFacade=new NoteGroupFacade(context);
                    java.util.Set<String> oversized=new java.util.HashSet<>();char[] suffix=new char[4090];Arrays.fill(suffix,'x');
                    for(int i=0;i<1100;i++)oversized.add("r"+i+new String(suffix));
                    try { scopedFacade.readVisibleCatalog(oversized,snapshot->{throw new java.io.IOException("PROJECTOR_MUST_NOT_RUN");});
                        fail("oversized aggregate requests must fail explicitly before producing a partial shelf"); }
                    catch(java.io.IOException budget){assertEquals("VISIBLE_CATALOG_REQUEST_BUDGET",budget.getMessage());}
                }
            }

            File duplicateMarker=new File(new File(new File(context.getFilesDir(),"note-groups"),"visible"),"duplicate-lineage.marker");
            try {
                Files.write(duplicateMarker.toPath(),("duplicate-lineage\n"+first.id+"\n"+firstGroup.revision+"\n"+firstGroup.digest+"\n").getBytes(StandardCharsets.UTF_8));
                List<NoteStore.Entry> duplicateRejected=NoteStore.list(context);
                assertFalse("ambiguous duplicate local identity cannot select either marker or fall back to legacy",duplicateRejected.stream().anyMatch(entry->first.id.equals(entry.id)));
                assertTrue("an unrelated unambiguous group remains visible",duplicateRejected.stream().anyMatch(entry->second.id.equals(entry.id)));
                JSONObject duplicateDiagnostic=new JSONObject(NoteStore.listDiagnosticForTest(context));
                assertEquals("DUPLICATE_LOCAL_ID",duplicateDiagnostic.getString("error_code"));
                JSONObject duplicateProfile=duplicateDiagnostic.getJSONObject("profile");
                assertEquals("each unambiguous current group is verified once",1,duplicateProfile.getLong("group_verifications"));
                assertEquals("the duplicate marker is covered by two linear inventory passes",6,duplicateProfile.getLong("marker_entries_examined"));
            } finally { Files.deleteIfExists(duplicateMarker.toPath()); }

            byte[] firstBody=firstGroup.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX);
            Path object=new File(new File(new File(context.getFilesDir(),"note-groups"),"objects/sha256"),sha(firstBody)).toPath();
            byte[] originalObject=Files.readAllBytes(object);byte[] alteredObject=originalObject.clone();alteredObject[0]^=1;
            GroupAuthorityBridge.CatalogReader freshReader=GroupAuthorityBridge.catalogReader(context);
            assertNotNull("first fresh lookup verifies the complete group",freshReader.bodyIfManagedFresh(first.id));
            StructStat objectStat=Os.lstat(object.toString());int originalMode=(int)(objectStat.st_mode&07777);
            try {
                Os.chmod(object.toString(),originalMode|OsConstants.S_IWUSR);
                Files.write(object,alteredObject);
                try {
                    freshReader.bodyIfManagedFresh(first.id);
                    fail("a later lookup through the reused reader must rehash a changed member");
                } catch(java.io.IOException changedBetweenLookups) {
                    assertEquals("OBJECT_HASH_MISMATCH",changedBetweenLookups.getMessage());
                }
                List<NoteStore.Entry> afterObjectMutation=NoteStore.list(context);
                assertFalse("a member hash change hides only its group; no legacy fallback is shown",
                        afterObjectMutation.stream().anyMatch(entry->first.id.equals(entry.id)));
                assertTrue("the unaffected group remains available",afterObjectMutation.stream().anyMatch(entry->second.id.equals(entry.id)));
                JSONObject integrity=new JSONObject(NoteStore.listDiagnosticForTest(context));
                assertEquals(1,integrity.getInt("group_reader_instances"));
                assertEquals(2,integrity.getInt("group_lookups"));assertEquals(1,integrity.getInt("rejected"));
                assertEquals("OBJECT_HASH_MISMATCH",integrity.getString("error_code"));
                assertTrue(integrity.getJSONObject("profile").getLong("object_full_hash_bytes")>0);
            } finally { Files.write(object,originalObject);Os.chmod(object.toString(),originalMode); }

            File marker=new File(new File(new File(context.getFilesDir(),"note-groups"),"visible"),firstGroup.lineage+".marker");
            byte[] previous=Files.readAllBytes(marker.toPath());
            try {
                Files.write(marker.toPath(),new byte[]{0x42,0x41,0x44});
                List<NoteStore.Entry> rejected=NoteStore.list(context);
                assertTrue("a corrupt marker never falls back to the retained legacy note",rejected.isEmpty());
                JSONObject diagnostic=new JSONObject(NoteStore.listDiagnosticForTest(context));
                assertEquals(1,diagnostic.getInt("group_reader_instances"));
                assertEquals("both indexed rows consume the failed catalog result",2,diagnostic.getInt("group_lookups"));
                assertEquals(2,diagnostic.getInt("rejected"));
                assertEquals("VISIBLE_MARKER_INVALID",diagnostic.getString("error_code"));
                JSONObject corruptProfile=diagnostic.getJSONObject("profile");
                assertTrue("corrupt global marker scans are counted without file details",corruptProfile.getLong("marker_scan_failures")>0);
                assertTrue(corruptProfile.getLong("marker_entries_examined")>0);
            } finally { Files.write(marker.toPath(),previous); }
            assertNotNull(secondGroup);
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path); }
        }
    }

    @Test public void shelfCatalogAllowsLegacyOnlyAfterCompleteMarkerInventoryProvesAbsence() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path fixture=target.getFilesDir().toPath().resolve("note-group-shelf-legacy-inventory-"+UUID.randomUUID());
        Files.createDirectory(fixture);FixtureContext context=new FixtureContext(target,fixture.toFile());
        try {
            NoteStore.Entry legacy=NoteStore.create(context,"Legacy without group marker");
            assertTrue("an absent marker in a complete inventory preserves a valid legacy note",
                    NoteStore.list(context).stream().anyMatch(entry->legacy.id.equals(entry.id)));
            File visible=new File(new File(context.getFilesDir(),"note-groups"),"visible");
            assertTrue("the catalog creates its private visible directory",visible.isDirectory());
            File unknown=new File(visible,"unexpected-child");
            try {
                Files.write(unknown.toPath(),new byte[]{1});
                assertFalse("an unparseable catalog child makes absence unprovable and cannot fall through to legacy",
                        NoteStore.list(context).stream().anyMatch(entry->legacy.id.equals(entry.id)));
                JSONObject diagnostic=new JSONObject(NoteStore.listDiagnosticForTest(context));
                assertEquals("VISIBLE_UNKNOWN_CHILD",diagnostic.getString("error_code"));
            } finally { Files.deleteIfExists(unknown.toPath()); }
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path); }
        }
    }

    @Test public void interleavedListDiagnosticAttemptsKeepLocalProfilesAndPublishedSnapshotPaired() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path firstRoot=target.getFilesDir().toPath().resolve("note-group-profile-attempt-a-"+UUID.randomUUID());
        Path secondRoot=target.getFilesDir().toPath().resolve("note-group-profile-attempt-b-"+UUID.randomUUID());
        Files.createDirectory(firstRoot);Files.createDirectory(secondRoot);
        FixtureContext firstContext=new FixtureContext(target,firstRoot.toFile());
        FixtureContext secondContext=new FixtureContext(target,secondRoot.toFile());
        try {
            long started=android.os.SystemClock.elapsedRealtime();
            NoteStore.CatalogProfile first=NoteStore.beginListDiagnostic(firstContext,started);
            NoteStore.CatalogProfile second=NoteStore.beginListDiagnostic(secondContext,started);
            assertNotNull("debug instrumentation creates a local per-list profile",first);
            assertNotNull(second);
            assertNotSame("interleaved list attempts never share a profile",first,second);
            assertTrue("attempts are distinct",first.attempt<second.attempt);
            JSONObject published=new JSONObject(NoteStore.listDiagnosticForTest(firstContext));
            assertEquals("latest snapshot belongs to second attempt",second.attempt,published.getLong("attempt"));
            assertEquals("snapshot carries its own matching profile",second.attempt,published.getJSONObject("profile").getLong("attempt"));
            assertNotEquals("first task's local profile cannot be substituted by latest global state",
                    first.attempt,published.getJSONObject("profile").getLong("attempt"));
            NoteStore.list(secondContext);
            JSONObject completed=new JSONObject(NoteStore.listDiagnosticForTest(secondContext));
            assertEquals("actual list publishes its own completed attempt",completed.getLong("attempt"),
                    completed.getJSONObject("profile").getLong("attempt"));
            assertEquals("actual list remains terminal", "COMPLETE",completed.getString("stage"));
        } finally {
            for(Path fixture:new Path[]{firstRoot,secondRoot})
                try(java.util.stream.Stream<Path> paths=Files.walk(fixture)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path); }
        }
    }

    /** Profiles the real target-context shelf read without creating or deleting fixture data. */
    @Test public void targetContextShelfProfileEmitsOnlyFixedCountersAndDurations() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        NoteStore.list(context);
        JSONObject diagnostic=new JSONObject(NoteStore.listDiagnosticForTest(context));
        JSONObject profile=diagnostic.getJSONObject("profile");
        assertEquals("catalog-profile-v1",profile.getString("profile_schema"));
        assertTrue("target-context list completed its measured stage",profile.getString("active_stage").isEmpty());
        String[] numeric={"active_stage_elapsed_ns","legacy_gate_wait_ns","note_store_monitor_wait_ns","load_index_ns","migration_ns",
                "reconciliation_ns","projection_ns","sort_ns","note_files_seen","note_files_eligible",
                "reader_construction_attempts","reader_constructors","reader_construction_failures","reader_constructor_ns","lookups_started","lookups_completed","lookups_failed","lookup_elapsed_ns",
                "marker_scans","marker_scan_failures","marker_entries_examined","marker_bytes_read","marker_entry_scan_ns","marker_record_reads","marker_record_bytes",
                "group_verifications","group_verification_failures","group_verification_ns","object_full_hashes",
                "object_full_hash_bytes","object_full_hash_ns","object_witness_rechecks","object_witness_recheck_ns"};
        for(String key:numeric)assertTrue("nonnegative fixed profile field: "+key,profile.getLong(key)>=0);
        assertFalse(profile.has("id"));assertFalse(profile.has("title"));assertFalse(profile.has("path"));
        Bundle profileStatus=new Bundle();
        profileStatus.putString("padnote_catalog_profile",profile.toString());
        profileStatus.putString("padnote_catalog_elapsed_ms",Long.toString(diagnostic.getLong("elapsed_ms")));
        InstrumentationRegistry.getInstrumentation().sendStatus(2,profileStatus);
    }

    @Test public void adoptsFullLegacyNoteOnlyAfterPreviewAndKeepsOriginalFilesByteExact() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path fixture=target.getFilesDir().toPath().resolve("note-group-facade-"+UUID.randomUUID());
        Files.createDirectory(fixture);
        FixtureContext context=new FixtureContext(target,fixture.toFile());
        try {
            try {
                LegacyFileCapture.read(context,new File(target.getCacheDir(),"outside-"+UUID.randomUUID()+".bin"),1024);
                fail("legacy capture must reject a file outside the app files root");
            } catch(java.io.IOException outside) {
                assertEquals("PATH_OUTSIDE_TRUSTED_FILES_ROOT",outside.getMessage());
            }
            File externalTarget=new File(target.getCacheDir(),"same-name-"+UUID.randomUUID()+".bin");
            Files.write(externalTarget.toPath(),new byte[]{1,2,3});
            File externalLink=new File(target.getCacheDir(),"outside-link-"+UUID.randomUUID()+".bin");
            Files.createSymbolicLink(externalLink.toPath(),externalTarget.toPath());
            try {
                LegacyFileCapture.read(context,externalLink,1024);
                fail("an external symlink must not enter the trusted root through canonicalization");
            } catch(java.io.IOException outside) {
                assertEquals("PATH_OUTSIDE_TRUSTED_FILES_ROOT",outside.getMessage());
            } finally { Files.deleteIfExists(externalLink.toPath());Files.deleteIfExists(externalTarget.toPath()); }
            NoteStore.Entry entry=NoteStore.create(context,"Facade legacy note");
            JSONObject body=NoteStore.load(context,entry.id); body.put("pdfPageCount",1);
            byte[] image=png(); JSONArray images=body.optJSONArray("images");if(images==null)images=new JSONArray();
            images.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("png",Base64.encodeToString(image,Base64.NO_WRAP))
                    .put("page",0).put("x",1.25).put("y",2.5).put("width",12.0).put("height",8.0)); body.put("images",images);
            NoteStore.save(context,entry.id,"Facade legacy note",NoteJsonCodec.stringify(body));
            File pdf=NoteStore.pdfFile(context,entry.id);writePdf(pdf);
            Bitmap cover=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);cover.eraseColor(Color.CYAN);CoverStore.assign(context,entry.id,cover);cover.recycle();

            Context fixtureAssets=InstrumentationRegistry.getInstrumentation().getContext();
            File sourceVideo=new File(context.getFilesDir(),"synthetic-input.mp4");
            try(InputStream in=fixtureAssets.getAssets().open("video/roundtrip.mp4");FileOutputStream out=new FileOutputStream(sourceVideo)){byte[] b=new byte[16384];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);}
            byte[] videoBytes=Files.readAllBytes(sourceVideo.toPath());String videoSha=sha(videoBytes);
            VideoAttachmentStore videos=new VideoAttachmentStore(context);
            VideoAttachmentStore.Attachment attachment=videos.attach(entry.id,entry.updatedAt,videoSha,
                    "task-"+UUID.randomUUID(),"artifact-"+UUID.randomUUID(),"Synthetic video","video/mp4",
                    videoBytes.length,videoSha,sourceVideo);
            VaultStore vault=new VaultStore(context);vault.write(entry.id,"Facade linked Vault",1,entry.updatedAt,Collections.singletonList("Synthetic linked material"));
            File legacyBody=NoteStore.noteFileForBackup(context,entry.id), legacyCover=CoverStore.coverFile(new File(context.getFilesDir(),NoteStore.notesDirectoryName()),entry.id);
            VaultStore.VaultNote vaultNote=vault.listForBackupStrict().stream().filter(v->entry.id.equals(v.noteId)).findFirst().orElseThrow();
            File legacyVault=vault.fileForBackup(vaultNote.fileName), legacyVideo=videos.openVerified(attachment);
            byte[] vaultBefore=Files.readAllBytes(legacyVault.toPath());
            assertTrue("canonical Vault file is a descendant of the canonical Context files root",
                    legacyVault.getCanonicalFile().toPath().startsWith(context.getFilesDir().getCanonicalFile().toPath()));
            assertArrayEquals("both Vault's canonical spelling and the NoteStore lexical spelling must pass the identity-checked capture root",
                    vaultBefore,LegacyFileCapture.read(context,legacyVault,(int)StreamingGroupStore.VAULT_MAX).bytes);
            byte[] bodyBefore=Files.readAllBytes(legacyBody.toPath()),pdfBefore=Files.readAllBytes(pdf.toPath()),coverBefore=Files.readAllBytes(legacyCover.toPath()),videoBefore=Files.readAllBytes(legacyVideo.toPath());

            NoteGroupFacade facade=new NoteGroupFacade(context);
            NoteGroupFacade.CapturedLegacyNote captured=facade.inspectLegacy(entry.id);
            assertEquals(entry.id,captured.localId);assertTrue(captured.pdfPresent);assertTrue(captured.coverPresent);
            assertEquals(1,captured.videoCount);assertEquals(1,captured.linkedVaultCount);
            try {
                facade.adoptLegacyNote(entry.id,(old,staged)->{
                    assertEquals(captured.fingerprint,old.fingerprint);
                    assertTrue(staged.memberSizes().containsKey("pdf.bin"));assertTrue(staged.memberSizes().containsKey("cover.bin"));
                    assertEquals(1,staged.memberSizes().keySet().stream().filter(k->k.startsWith("video/")&&k.endsWith("/content.bin")).count());
                    assertEquals(1,staged.memberSizes().keySet().stream().filter(k->k.startsWith("vault/")&&k.endsWith("/content.bin")).count());
                    return false;
                }); fail("cancelled preview must not publish a marker");
            } catch(java.io.IOException cancelled){assertTrue(cancelled.getMessage().contains("PREVIEW_CANCELLED"));}
            assertNull("legacy note remains the only visible form after cancel",facade.openGroup(entry.id));
            assertArrayEquals(bodyBefore,Files.readAllBytes(legacyBody.toPath()));assertArrayEquals(pdfBefore,Files.readAllBytes(pdf.toPath()));

            StreamingGroupStore.Snapshot committed=facade.adoptLegacyNote(entry.id,(old,staged)->{
                assertEquals(captured.fingerprint,old.fingerprint);
                assertEquals("present\n",new String(staged.readSmall("video.state",16),StandardCharsets.US_ASCII));
                assertEquals("present\n",new String(staged.readSmall("vault.state",16),StandardCharsets.US_ASCII));
                return true;
            });
            assertEquals(entry.id,committed.localId);
            StreamingGroupStore.Snapshot cold=new NoteGroupFacade(context).openGroup(entry.id);
            assertNotNull("a fresh store must cold-read the committed full group",cold);
            assertEquals(committed.localId,cold.localId);assertEquals(committed.lineage,cold.lineage);
            assertEquals(committed.revision,cold.revision);assertEquals(committed.digest,cold.digest);
            assertEquals(committed.memberSizes(),cold.memberSizes());
            for(String member:committed.memberSizes().keySet())assertEquals("cold-read member digest: "+member,committed.memberSha256(member),cold.memberSha256(member));
            assertNotNull("a fresh store must cold-read the committed full group",new NoteGroupFacade(context).openGroup(entry.id));
            StreamingGroupStore.Snapshot stillValid=facade.openGroup(entry.id);
            assertNotNull("cold verification by another Store must preserve the live immutable witness",stillValid);
            assertEquals(committed.revision,stillValid.revision);assertEquals(committed.digest,stillValid.digest);
            assertArrayEquals(bodyBefore,cold.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX));
            assertArrayEquals(pdfBefore,cold.readSmall("pdf.bin",(int)StreamingGroupStore.PDF_MAX));
            assertArrayEquals(coverBefore,cold.readSmall("cover.bin",(int)StreamingGroupStore.COVER_MAX));
            assertArrayEquals("typed source provenance survives cold read",committed.readSmall("source-provenance.bin",1<<20),cold.readSmall("source-provenance.bin",1<<20));
            String videoId=attachment.id;
            assertEquals(videoSha,committed.memberSha256("video/"+videoId+"/content.bin"));
            assertArrayEquals(videoBefore,cold.readSmall("video/"+videoId+"/content.bin",(int)StreamingGroupStore.VIDEO_MAX));
            String vaultDestination=padnote.material.StorageAdapter.androidVaultMaterialId(vaultNote.fileName);
            assertArrayEquals(vaultBefore,cold.readSmall("vault/"+vaultDestination+"/content.bin",(int)StreamingGroupStore.VAULT_MAX));
            assertArrayEquals("legacy storage remains unchanged until all callers are routed",bodyBefore,Files.readAllBytes(legacyBody.toPath()));
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p); }
            assertFalse("cleanup is confined to this UUID fixture",Files.exists(fixture));
        }
    }

    @Test public void adoptsOrdinaryCreatedNoPdfNoteAndColdReadsCompleteSnapshot() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path fixture=target.getFilesDir().toPath().resolve("note-group-facade-no-pdf-"+UUID.randomUUID());
        Files.createDirectory(fixture);FixtureContext context=new FixtureContext(target,fixture.toFile());
        try {
            NoteStore.Entry entry=NoteStore.create(context,"Ordinary no-PDF note");
            JSONObject created=NoteStore.load(context,entry.id);
            assertEquals(8,created.getInt("schemaVersion"));
            assertFalse("ordinary schema 8 NoteStore.create omits PDF metadata",created.has("pdfPageCount"));
            File legacyBody=NoteStore.noteFileForBackup(context,entry.id);
            byte[] originalBody=Files.readAllBytes(legacyBody.toPath());
            NoteGroupFacade facade=new NoteGroupFacade(context);
            NoteGroupFacade.CapturedLegacyNote inspected=facade.inspectLegacy(entry.id);
            assertEquals(entry.id,inspected.localId);
            assertFalse("missing count means no PDF",inspected.pdfPresent);
            assertEquals(0,inspected.pageCount);
            assertFalse("ordinary no-PDF creation has no PDF file",Files.exists(NoteStore.pdfFile(context,entry.id).toPath()));

            StreamingGroupStore.Snapshot committed=facade.adoptLegacyNote(entry.id,(old,staged)->{
                assertEquals(inspected.fingerprint,old.fingerprint);
                assertArrayEquals("preview stages the unchanged no-PDF body",originalBody,
                        staged.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX));
                assertEquals("absent\n",new String(staged.readSmall("pdf.state",16),StandardCharsets.US_ASCII));
                return true;
            });
            NoteGroupFacade coldFacade=new NoteGroupFacade(context);
            StreamingGroupStore.Snapshot cold=coldFacade.openGroup(entry.id);
            assertNotNull("fresh facade cold-reads no-PDF adoption",cold);
            assertEquals(entry.id,committed.localId);assertEquals(committed.localId,cold.localId);
            assertEquals(committed.lineage,cold.lineage);assertEquals(committed.revision,cold.revision);
            assertEquals(committed.digest,cold.digest);assertEquals(committed.memberSizes(),cold.memberSizes());
            assertFalse("cold group contains no fabricated PDF member",cold.memberSizes().containsKey("pdf.bin"));
            for(String member:committed.memberSizes().keySet())
                assertEquals("cold-read member digest: "+member,committed.memberSha256(member),cold.memberSha256(member));
            assertArrayEquals("cold body remains byte-exact",originalBody,
                    cold.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX));
            JSONObject coldBody=new JSONObject(new String(cold.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
            assertEquals(entry.id,coldBody.getString("id"));
            assertFalse("cold legacy body still omits pdfPageCount",coldBody.has("pdfPageCount"));
            assertEquals("absent\n",new String(cold.readSmall("pdf.state",16),StandardCharsets.US_ASCII));
            byte[] committedProvenance=committed.readSmall("source-provenance.bin",1<<20);
            byte[] coldProvenance=cold.readSmall("source-provenance.bin",1<<20);
            assertArrayEquals("typed source provenance survives fresh cold read",committedProvenance,coldProvenance);
            assertEquals(committed.memberSha256("source-provenance.bin"),cold.memberSha256("source-provenance.bin"));
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p); }
            assertFalse("cleanup is confined to this UUID fixture",Files.exists(fixture));
        }
    }

    @Test public void sourceEditedInsideConfirmationIsRejectedBeforeMarkerAndKeepsNewLegacyBody() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path fixture=target.getFilesDir().toPath().resolve("note-group-facade-confirm-edit-"+UUID.randomUUID());
        Files.createDirectory(fixture);
        FixtureContext context=new FixtureContext(target,fixture.toFile());
        try {
            NoteStore.Entry entry=NoteStore.create(context,"Before confirmation");
            assertFalse("ordinary NoteStore.create schema 8 has no PDF metadata",NoteStore.load(context,entry.id).has("pdfPageCount"));
            File legacyBody=NoteStore.noteFileForBackup(context,entry.id);
            byte[] oldBody=Files.readAllBytes(legacyBody.toPath());
            VaultStore vault=new VaultStore(context);
            String vaultName=vault.write(entry.id,"Linked before confirmation",1,entry.updatedAt,Collections.singletonList("old linked vault"));
            File legacyVault=vault.fileForBackup(vaultName);
            byte[] oldVault=Files.readAllBytes(legacyVault.toPath());
            NoteGroupFacade facade=new NoteGroupFacade(context);
            NoteGroupFacade.CapturedLegacyNote captured=facade.inspectLegacy(entry.id);
            try {
                facade.adoptLegacyNote(entry.id,(old,staged)->{
                    assertEquals(captured.fingerprint,old.fingerprint);
                    CountDownLatch started=new CountDownLatch(1),finished=new CountDownLatch(1);
                    AtomicReference<Throwable> writerFailure=new AtomicReference<>();
                    Thread ordinaryWriter=new Thread(()->{
                        started.countDown();
                        try {
                            JSONObject edited=NoteStore.load(context,entry.id);
                            edited.put("title","Saved during confirmation");
                            NoteStore.save(context,entry.id,"Saved during confirmation",NoteJsonCodec.stringify(edited));
                            String markdown=new String(oldVault,StandardCharsets.UTF_8).replace("old linked vault","new linked vault");
                            vault.replaceRaw(vaultName,markdown);
                        } catch(Throwable failure) { writerFailure.set(failure); }
                        finally { finished.countDown(); }
                    },"legacy-group-concurrent-writer");
                    ordinaryWriter.start();
                    assertTrue("real ordinary NoteStore/VaultStore writers start during preview",started.await(5,TimeUnit.SECONDS));
                    assertTrue("preview remains open without holding the publication writer fence",finished.await(10,TimeUnit.SECONDS));
                    ordinaryWriter.join(1000);
                    if(writerFailure.get()!=null) throw new AssertionError("ordinary writer failed inside preview",writerFailure.get());
                    assertEquals("Saved during confirmation",NoteStore.load(context,entry.id).getString("title"));
                    assertTrue("linked VaultStore mutation also completed during preview",new String(Files.readAllBytes(legacyVault.toPath()),StandardCharsets.UTF_8).contains("new linked vault"));
                    return true;
                });
                fail("source changed after preview must be rejected before marker publication");
            } catch(java.io.IOException changed) {
                assertEquals("LEGACY_SOURCE_CHANGED_BEFORE_PUBLICATION",changed.getMessage());
            }
            assertNull("failed adoption must leave the legacy note unmarked",facade.openGroup(entry.id));
            JSONObject current=NoteStore.load(context,entry.id);
            assertEquals("Saved during confirmation",current.getString("title"));
            byte[] currentBytes=Files.readAllBytes(legacyBody.toPath());
            assertFalse("stale staged body must not overwrite the user's newer legacy save",Arrays.equals(oldBody,currentBytes));
            JSONObject persisted=new JSONObject(new String(currentBytes,StandardCharsets.UTF_8));
            assertEquals("the current body file retains the user's accepted edit",current.getString("title"),persisted.getString("title"));
            assertTrue("the concurrently edited linked Vault remains intact",new String(Files.readAllBytes(legacyVault.toPath()),StandardCharsets.UTF_8).contains("new linked vault"));
            assertFalse("legacy Vault bytes changed and were not overwritten by a stale staged group",Arrays.equals(oldVault,Files.readAllBytes(legacyVault.toPath())));
            assertNotEquals("the live legacy source fingerprint must reflect the newer save",captured.fingerprint,facade.inspectLegacy(entry.id).fingerprint);
            String transaction=onlyTransaction(context);
            NoteGroupFacade reopened=new NoteGroupFacade(context);
            assertEquals("a source-guard refusal is terminal for replay",StreamingGroupStore.Phase.LEGACY_SOURCE_RECHECK_REQUIRED,reopened.recoverTransaction(transaction));
            assertNull("explicit recovery cannot publish the stale snapshot",reopened.openGroup(entry.id));
            assertEquals("new legacy save survives rejected replay","Saved during confirmation",NoteStore.load(context,entry.id).getString("title"));
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p); }
            assertFalse("cleanup is confined to this UUID fixture",Files.exists(fixture));
        }
    }

    @Test public void ordinaryBodyCoverVideoAndVaultWritersWaitBehindPublicationFence() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path fixture=target.getFilesDir().toPath().resolve("note-group-facade-writer-fence-"+UUID.randomUUID());
        Files.createDirectory(fixture);FixtureContext context=new FixtureContext(target,fixture.toFile());
        Bitmap cover=null;
        List<MutationAttempt> attempts=new ArrayList<>();
        try {
            NoteStore.Entry entry=NoteStore.create(context,"Writer fence note");
            File bodyFile=NoteStore.noteFileForBackup(context,entry.id);
            byte[] beforeBody=Files.readAllBytes(bodyFile.toPath());
            VaultStore vault=new VaultStore(context);
            String vaultName=vault.write(entry.id,"Fence Vault",1,entry.updatedAt,Collections.singletonList("old"));
            File vaultFile=vault.fileForBackup(vaultName);
            byte[] beforeVault=Files.readAllBytes(vaultFile.toPath());
            VideoAttachmentStore videos=new VideoAttachmentStore(context);
            Path stagedVideo=fixture.resolve("source.mp4");
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("video/roundtrip.mp4");
                FileOutputStream out=new FileOutputStream(stagedVideo.toFile())) { byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n); }
            byte[] videoBytes=Files.readAllBytes(stagedVideo);
            String videoSha=sha(videoBytes);
            cover=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);cover.eraseColor(Color.YELLOW);
            final Bitmap coverImage=cover;
            JSONObject edited=NoteStore.load(context,entry.id);edited.put("title","writer completed");
            attempts.add(new MutationAttempt("body-save",()->NoteStore.save(context,entry.id,
                    "writer completed",NoteJsonCodec.stringify(edited))));
            attempts.add(new MutationAttempt("cover-assign",()->CoverStore.assign(context,entry.id,coverImage)));
            attempts.add(new MutationAttempt("vault-replace",()->vault.replaceRaw(vaultName,
                    new String(beforeVault,StandardCharsets.UTF_8).replace("old\n","new\n"))));
            attempts.add(new MutationAttempt("video-attach",()->videos.attach(entry.id,entry.updatedAt,videoSha,
                    "fence-task-"+UUID.randomUUID(),"fence-artifact-"+UUID.randomUUID(),"Fence video",
                    "video/mp4",videoBytes.length,videoSha,stagedVideo.toFile())));
            CountDownLatch startGate=new CountDownLatch(1);
            for(MutationAttempt attempt:attempts)attempt.start(startGate);
            try(LegacyGroupMutationLock.Lease held=LegacyGroupMutationLock.acquire()) {
                for(MutationAttempt attempt:attempts)attempt.thread.start();
                startGate.countDown();
                for(MutationAttempt attempt:attempts)assertTrue(attempt.name+" worker reached its API",attempt.started.await(5,TimeUnit.SECONDS));
                for(MutationAttempt attempt:attempts)assertFalse(attempt.name+" is independently blocked by the publication fence",
                        attempt.finished.await(300,TimeUnit.MILLISECONDS));
                assertArrayEquals("body is unchanged while publication fence is held",beforeBody,Files.readAllBytes(bodyFile.toPath()));
                assertFalse("cover is not partially installed",CoverStore.has(context,entry.id));
                assertArrayEquals("Vault is unchanged while publication fence is held",beforeVault,Files.readAllBytes(vaultFile.toPath()));
                assertTrue("video index remains unchanged while publication fence is held",videos.listForNote(entry.id).isEmpty());
            }
            for(MutationAttempt attempt:attempts){
                assertTrue(attempt.name+" resumes after the fence releases",attempt.finished.await(10,TimeUnit.SECONDS));
                attempt.thread.join(1000);
                if(attempt.failure.get()!=null)throw new AssertionError(attempt.name+" failed after fence release",attempt.failure.get());
            }
            cover.recycle();
            assertEquals("writer completed",NoteStore.load(context,entry.id).getString("title"));
            assertTrue("cover write completed",CoverStore.has(context,entry.id));
            assertTrue("Vault write completed",new String(Files.readAllBytes(vaultFile.toPath()),StandardCharsets.UTF_8).contains("new"));
            assertEquals("video attachment write completed",1,videos.listForNote(entry.id).size());
        } finally {
            for(MutationAttempt attempt:attempts)if(attempt.thread.isAlive())attempt.thread.interrupt();
            for(MutationAttempt attempt:attempts)if(attempt.thread.isAlive())attempt.thread.join(5000);
            boolean allWritersStopped=true;
            for(MutationAttempt attempt:attempts)allWritersStopped&=!attempt.thread.isAlive();
            if(!allWritersStopped){
                StringBuilder liveWriters=new StringBuilder();
                for(MutationAttempt attempt:attempts)if(attempt.thread.isAlive()){
                    if(liveWriters.length()>0)liveWriters.append(", ");
                    liveWriters.append(attempt.name);
                }
                fail("owned writers remain live; retaining their bitmap and UUID fixture: "+liveWriters);
            }
            if(cover!=null&&!cover.isRecycled())cover.recycle();
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p); }
            assertFalse("cleanup is confined to this UUID fixture",Files.exists(fixture));
        }
    }

    private interface Mutation { void run() throws Exception; }
    private static final class MutationAttempt {
        final String name; final Mutation mutation; final CountDownLatch started=new CountDownLatch(1),finished=new CountDownLatch(1);
        final AtomicReference<Throwable> failure=new AtomicReference<>(); final Thread thread; volatile CountDownLatch gate;
        MutationAttempt(String name,Mutation mutation){this.name=name;this.mutation=mutation;thread=new Thread(()->{
            try{CountDownLatch current=gate;if(current==null)throw new IllegalStateException("writer gate missing");current.await();started.countDown();mutation.run();}
            catch(Throwable error){failure.set(error);started.countDown();}finally{finished.countDown();}
        },"legacy-writer-"+name);}
        void start(CountDownLatch gate){this.gate=gate;thread.setUncaughtExceptionHandler((t,e)->failure.set(e));}
    }

    @Test public void interruptedApprovedAdoptionCannotPublishAfterLegacySourceChanges() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path fixture=target.getFilesDir().toPath().resolve("note-group-facade-interrupted-"+UUID.randomUUID());
        Files.createDirectory(fixture);FixtureContext context=new FixtureContext(target,fixture.toFile());
        try {
            NoteStore.Entry entry=NoteStore.create(context,"Before interrupted approval");
            assertFalse("ordinary NoteStore.create schema 8 has no PDF metadata",NoteStore.load(context,entry.id).has("pdfPageCount"));
            NoteGroupFacade interrupted=new NoteGroupFacade(context,phase->{
                if(phase==StreamingGroupStore.Phase.PREVIEW_APPROVED)throw new java.io.IOException("SIMULATED_PROCESS_INTERRUPTION");
            });
            try {
                interrupted.adoptLegacyNote(entry.id,(old,staged)->true);
                fail("observer must interrupt after the durable approval record");
            } catch(java.io.IOException expected) { assertEquals("SIMULATED_PROCESS_INTERRUPTION",expected.getMessage()); }
            String transaction=onlyTransaction(context);
            assertNull("an interrupted pre-publication transaction has no visible marker",new NoteGroupFacade(context).openGroup(entry.id));
            JSONObject edited=NoteStore.load(context,entry.id);edited.put("title","Saved after interruption");
            NoteStore.save(context,entry.id,"Saved after interruption",NoteJsonCodec.stringify(edited));
            NoteGroupFacade reopened=new NoteGroupFacade(context);
            assertEquals("recovery must not replay a preview against changed legacy files",StreamingGroupStore.Phase.LEGACY_SOURCE_RECHECK_REQUIRED,reopened.recoverTransaction(transaction));
            assertEquals(StreamingGroupStore.Phase.LEGACY_SOURCE_RECHECK_REQUIRED,reopened.recoverTransaction(transaction));
            assertNull("no stale marker appears after explicit recovery",reopened.openGroup(entry.id));
            assertEquals("the accepted legacy edit remains readable","Saved after interruption",NoteStore.load(context,entry.id).getString("title"));
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p); }
            assertFalse("cleanup is confined to this UUID fixture",Files.exists(fixture));
        }
    }

    private static String onlyTransaction(Context context)throws Exception {
        File dir=new File(new File(context.getFilesDir(),"note-groups"),"transactions");
        File[] children=dir.listFiles(File::isDirectory);assertNotNull(children);assertEquals("fixture has exactly one owned transaction",1,children.length);
        String id=children[0].getName();assertEquals(id,UUID.fromString(id).toString());return id;
    }

    private static long uniqueMemberHashCount(StreamingGroupStore.Snapshot snapshot)throws Exception {
        java.util.Set<String> hashes=new java.util.HashSet<>();
        for(String member:snapshot.memberSizes().keySet())hashes.add(snapshot.memberSha256(member));
        return hashes.size();
    }
    private static long memberCount(StreamingGroupStore.Snapshot snapshot)throws Exception{return snapshot.memberSizes().size();}
    private static byte[] png() throws Exception {Bitmap b=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);b.eraseColor(Color.MAGENTA);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));b.recycle();return out.toByteArray();}
    private static void writePdf(File file)throws Exception {PdfDocument doc=new PdfDocument();PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(144,144,1).create());page.getCanvas().drawColor(Color.WHITE);Paint p=new Paint();p.setColor(Color.BLACK);page.getCanvas().drawText("legacy group",10,40,p);doc.finishPage(page);try(FileOutputStream out=new FileOutputStream(file)){doc.writeTo(out);}finally{doc.close();}}
    private static String sha(byte[] b)throws Exception {byte[] h=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte x:h)s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return s.toString();}
    private static final class FixtureContext extends ContextWrapper {
        private final File files;FixtureContext(Context base,File files){super(base);this.files=files;}
        @Override public File getFilesDir(){return files;}
        @Override public File getDir(String name,int mode) {
            if(name==null||!name.matches("[A-Za-z0-9._-]{1,64}"))throw new IllegalArgumentException("fixture_dir_name");
            File dir=new File(files,"app_"+name);
            if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("fixture_dir_create_failed");
            if(!dir.isDirectory()||java.nio.file.Files.isSymbolicLink(dir.toPath()))throw new IllegalStateException("fixture_dir_unsafe");
            return dir;
        }
        @Override public Context getApplicationContext(){return this;}
    }
}
