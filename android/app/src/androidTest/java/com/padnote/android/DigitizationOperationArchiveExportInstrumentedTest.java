package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.padnote.android.streaming.StreamingGroupStore;

import org.json.JSONArray;
import org.json.JSONObject;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Exercises the production grouped digitization publish and full-update archive exporter.
 * The retained output contains only synthetic test data for later device-side byte readback.
 */
@RunWith(AndroidJUnit4.class)
public final class DigitizationOperationArchiveExportInstrumentedTest {
    private static final String FIXTURE_AI_TEXT =
            "Synthetic AI text flow: preserve this complete note-body sentence.";

    @Test public void groupedDigitizationOperationHeaderSurvivesRealManualUpdateArchiveExport() throws Exception {
        Assume.assumeTrue("grouped archive export requires API 27", Build.VERSION.SDK_INT >= 27);
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context isolated=isolatedContext(target);
        String runId=UUID.randomUUID().toString();
        File exportDirectory=new File(target.getCacheDir(),"padnote-operation-header-export-"+runId);
        File archive=new File(exportDirectory,"operation-header.padnote");
        boolean retainArchive=false;
        try {
            NoteStore.Entry entry=importOnePagePdf(isolated);
            // PDF import intentionally creates schema 7. Group adoption is limited to
            // current schema 8, so migrate through the same canvas decoder/encoder and
            // durable NoteStore save used by an ordinary editor save before adoption.
            JSONObject migrated=migrateImportedPdfThroughEditor(isolated,entry);
            assertEquals("the explicit editor migration preserves the source PDF page count",
                    1,migrated.getInt("pdfPageCount"));
            assertNonemptyEditorBody(migrated);
            entry=findCurrentEntry(isolated,entry.id);
            assertEquals("the video source token uses the saved schema-8 body revision",
                    migrated.getLong("updatedAt"),entry.updatedAt);
            assertEquals(1,NoteStore.load(isolated,entry.id).getInt("pdfPageCount"));

            Bitmap cover=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);
            try {
                cover.eraseColor(Color.CYAN);
                CoverStore.assign(isolated,entry.id,cover);
            } finally { cover.recycle(); }

            File video=new File(isolated.getFilesDir(),"synthetic-roundtrip.mp4");
            copyAsset(InstrumentationRegistry.getInstrumentation().getContext(),
                    "video/roundtrip.mp4",video);
            String videoSha=sha256(video);
            new VideoAttachmentStore(isolated).attach(entry.id,entry.updatedAt,videoSha,videoSha,
                    "task-"+UUID.randomUUID(),"remote-"+UUID.randomUUID(),
                    "connection-"+UUID.randomUUID(),1,"HERMES","BRIDGE",
                    "bridge-"+UUID.randomUUID(),"instance-"+UUID.randomUUID(),
                    sha256("synthetic certificate".getBytes(StandardCharsets.UTF_8)),
                    "artifact-"+UUID.randomUUID(),"Synthetic roundtrip video","video/mp4",
                    video.length(),videoSha,video);

            new VaultStore(isolated).write(entry.id,"Synthetic source material",1,
                    entry.updatedAt,Collections.singletonList("Synthetic source page."));
            NoteGroupFacade facade=new NoteGroupFacade(isolated);
            StreamingGroupStore.Snapshot adopted=facade.adoptLegacyNote(entry.id,(before,staged)->{
                assertTrue("the source PDF belongs to this adopted group",before.pdfPresent);
                assertTrue("the assigned cover belongs to this adopted group",before.coverPresent);
                assertEquals("the synthetic MP4 belongs to this adopted group",1,before.videoCount);
                assertEquals("the source material belongs to this adopted group",1,before.linkedVaultCount);
                assertTrue(staged.memberSizes().containsKey("pdf.bin"));
                assertTrue(staged.memberSizes().containsKey("cover.bin"));
                assertTrue(staged.memberSizes().keySet().stream().anyMatch(name->name.startsWith("video/")));
                assertTrue(staged.memberSizes().keySet().stream().anyMatch(name->name.startsWith("vault/")));
                return true;
            });
            GroupAuthorityBridge.VaultView priorVault=GroupAuthorityBridge.vaultViews(adopted).get(0);
            byte[] priorVaultBytes=priorVault.markdown.clone();

            DigitizationStore.Target operation=new VaultStore(isolated)
                    .captureDigitizationTarget(entry.id,null);
            assertEquals(DigitizationStore.TargetKind.GROUP,operation.kind);
            assertEquals(DigitizationStore.TargetOperation.ADD,operation.operation);
            assertNotEquals("new operation receives a distinct material identity",priorVault.materialId,operation.materialId);
            assertEquals(adopted.revision,operation.groupRevision);
            assertEquals(adopted.digest,operation.groupDigest);
            assertCanonicalUuid(operation.operationId);

            String operationTitle=migrated.optString("title","Synthetic operation result");
            int digitizationPageCount=migrated.getInt("pageCount");
            assertEquals("the editor-created appendix is included in the note snapshot",2,
                    digitizationPageCount);
            String operationPage="Synthetic operation page from the durable checkpoint.";
            List<String> operationPages=new ArrayList<>();
            for(int pageIndex=0;pageIndex<digitizationPageCount;pageIndex++)
                operationPages.add(pageIndex==0?operationPage:
                        "Synthetic operation page "+(pageIndex+1)+" from the durable checkpoint.");
            DigitizationStore.Source digitizationSource=DigitizationStore.Source.from(migrated,
                    sha256(NoteStore.pdfFile(isolated,entry.id)),digitizationPageCount,
                    "archive-export-test","https://example.test/v1/chat/completions",
                    "synthetic-model");
            DigitizationStore checkpoints=new DigitizationStore(isolated);
            DigitizationStore.Snapshot created=checkpoints.create(digitizationSource,
                    operationTitle,migrated.getLong("updatedAt"),operation);
            assertEquals("new local checkpoint ID is derived from the captured target UUID",
                    "digitize-"+operation.operationId,created.runId);
            DigitizationStore.Snapshot writing=checkpoints.startAttempt(created);
            for(int pageIndex=0;pageIndex<digitizationPageCount;pageIndex++) {
                writing=checkpoints.markPageStarted(writing,pageIndex);
                writing=checkpoints.savePage(writing,pageIndex,operationPages.get(pageIndex));
            }
            DigitizationStore.Snapshot ready=writing;
            assertEquals("every note-snapshot page is durably checkpointed",
                    DigitizationStore.State.READY,ready.state);
            DigitizationStore.Snapshot cold=new DigitizationStore(isolated).load(created.runId);
            assertTrue("cold read retains the captured frozen target",operation.sameIntent(cold.target));
            assertEquals("cold read preserves every checkpoint page",operationPages,cold.pages);
            assertEquals(digitizationPageCount,cold.totalPages);
            assertEquals("cold read preserves the exact local checkpoint identity",
                    "digitize-"+cold.target.operationId,cold.runId);

            long publishStartedAt=System.currentTimeMillis();
            new VaultStore(isolated).publishDigitization(cold.target,cold.title,cold.totalPages,
                    cold.sourceUpdatedAt,cold.pages);
            long publishCompletedAt=System.currentTimeMillis();
            StreamingGroupStore.Snapshot committed=facade.openGroup(entry.id);
            JSONObject committedBody=NotePrecisionJsonParser.parseObject(new String(
                    committed.readSmall("body.bin",(int)LibraryBackupManifest.MAX_NOTE_BYTES),
                    StandardCharsets.UTF_8));
            long migratedUpdatedAt=migrated.getLong("updatedAt");
            long committedUpdatedAt=committedBody.getLong("updatedAt");
            long minimumPublishedAt=Math.max(publishStartedAt,migratedUpdatedAt+1);
            long maximumPublishedAt=Math.max(publishCompletedAt,migratedUpdatedAt+1);
            assertTrue("the Vault publish creates a newer body timestamp within its operation window",
                    committedUpdatedAt>=minimumPublishedAt&&committedUpdatedAt<=maximumPublishedAt);
            assertEquals("the grouped publish changes only the body revision timestamp",
                    NoteJsonCodec.stringifyWithout(migrated,"updatedAt"),
                    NoteJsonCodec.stringifyWithout(committedBody,"updatedAt"));
            JSONObject reopenedCurrentBody=NoteStore.load(isolated,entry.id);
            assertEquals("ordinary NoteStore read returns the exact committed group body",
                    NoteJsonCodec.stringify(committedBody),
                    NoteJsonCodec.stringify(reopenedCurrentBody));
            NoteStore.Entry publishedEntry=findCurrentEntry(isolated,entry.id);
            assertEquals("the shelf timestamp matches the committed body revision",
                    committedUpdatedAt,publishedEntry.updatedAt);
            GroupAuthorityBridge.VaultView committedVault=
                    GroupAuthorityBridge.vaultView(committed,operation.materialId);
            GroupAuthorityBridge.VaultView committedPriorVault=
                    GroupAuthorityBridge.vaultView(committed,priorVault.materialId);
            assertArrayEquals("publishing a new operation retains prior source material bytes",
                    priorVaultBytes,committedPriorVault.markdown);
            String operationHeader="digitization-operation-id: "+cold.target.operationId+"\n";
            String committedMarkdown=new String(committedVault.markdown,StandardCharsets.UTF_8);
            assertEquals("the operation header is emitted once",1,countOccurrences(committedMarkdown,operationHeader));
            assertEquals("published header UUID is the cold-read local checkpoint UUID",
                    cold.runId.substring("digitize-".length()),operationHeaderId(committedMarkdown));

            String retriedName=new VaultStore(isolated).publishDigitization(cold.target,cold.title,
                    cold.totalPages,cold.sourceUpdatedAt,cold.pages);
            assertEquals("exact checkpoint retry returns the same material",committedVault.fileName,retriedName);
            StreamingGroupStore.Snapshot afterExactRetry=facade.openGroup(entry.id);
            assertEquals("an exact retry does not create another group revision",
                    committed.revision,afterExactRetry.revision);
            assertEquals("an exact retry leaves the group digest unchanged",committed.digest,
                    afterExactRetry.digest);
            assertEquals("an exact retry does not duplicate the Vault row",2,
                    GroupAuthorityBridge.vaultViews(afterExactRetry).size());
            assertArrayEquals("an exact retry leaves the winning member bytes unchanged",
                    committedVault.markdown,
                    GroupAuthorityBridge.vaultView(afterExactRetry,cold.target.materialId).markdown);

            try {
                List<String> conflictingPages=new ArrayList<>(cold.pages);
                conflictingPages.set(0,"Conflicting bytes for the same checkpoint operation.");
                assertEquals("the stale-byte retry preserves the captured page count",
                        cold.totalPages,conflictingPages.size());
                new VaultStore(isolated).publishDigitization(cold.target,cold.title,cold.totalPages,
                        cold.sourceUpdatedAt,conflictingPages);
                fail("same operation UUID with changed bytes must be rejected as stale");
            } catch(java.io.IOException expected) {
                assertEquals("DIGITIZATION_TARGET_STALE",expected.getMessage());
            }
            StreamingGroupStore.Snapshot afterConflict=facade.openGroup(entry.id);
            assertEquals("changed-byte retry cannot replace the winning revision",
                    committed.revision,afterConflict.revision);
            assertEquals("changed-byte retry cannot change the winning digest",committed.digest,
                    afterConflict.digest);
            assertEquals("changed-byte retry cannot create a second Vault row",2,
                    GroupAuthorityBridge.vaultViews(afterConflict).size());
            assertArrayEquals("changed-byte retry leaves the winning member bytes intact",
                    committedVault.markdown,
                    GroupAuthorityBridge.vaultView(afterConflict,cold.target.materialId).markdown);

            assertTrue("the export leaf is fresh and app-private",exportDirectory.mkdir());
            Os.chmod(exportDirectory.getAbsolutePath(),0700);
            String cacheRoot=target.getCacheDir().getCanonicalPath()+File.separator;
            assertTrue(exportDirectory.getCanonicalPath().startsWith(cacheRoot));
            try(LibraryBackupSnapshot snapshot=LibraryBackupSnapshot.createForManualUpdate(
                    isolated,Collections.singleton(entry.id),null,null)) {
                assertEquals("manual update export uses strict current archive format",
                        LibraryBackupManifest.FORMAT_VERSION,snapshot.manifest.formatVersion);
                assertEquals(1,snapshot.manifest.notes.size());
                assertEquals(1,snapshot.manifest.updateProfiles.size());
                assertEquals("manual update snapshot includes both existing and newly published Vault rows",2,snapshot.manifest.vaultEntries.size());
                assertEquals(1,snapshot.manifest.videoAttachments.size());
                snapshot.write(archive,null,null);
            }
            assertTrue("the real exporter created a nonempty archive",archive.isFile());
            File lock=new File(exportDirectory,".padnote-publish-"
                    +sha256(archive.getName().getBytes(StandardCharsets.UTF_8))+".lock");
            File[] retained=exportDirectory.listFiles();
            assertNotNull(retained);
            assertEquals("only the archive and its deterministic publication lock may remain",2,retained.length);
            assertTrue("the exact synthetic archive remains",archive.isFile());
            assertTrue("the only sidecar is the destination-bound publication lock",lock.isFile());
            for(File child:retained)assertTrue("no arbitrary file is allowed in the export leaf",
                    child.equals(archive)||child.equals(lock));
            android.system.StructStat lockStat=Os.lstat(lock.getAbsolutePath());
            assertTrue("publication lock is a regular no-follow file",OsConstants.S_ISREG(lockStat.st_mode));
            assertEquals("publication lock belongs to this app uid",Os.getuid(),lockStat.st_uid);
            assertEquals("publication lock uses private permissions",0600,(int)(lockStat.st_mode&0777));
            assertEquals("publication lock has one link",1L,lockStat.st_nlink);
            assertEquals("publication lock is the expected empty lock leaf",0L,lockStat.st_size);
            String archiveSha=sha256(archive);
            long archiveBytes=archive.length();

            File validationParent=new File(isolated.getFilesDir(),"export-validation");
            assertTrue(validationParent.mkdir());
            LibraryBackupManifest exported;
            try(LibraryBackupArchive.StagedArchive staged=
                        LibraryBackupArchive.stage(archive,validationParent,null,null)) {
                exported=staged.manifest();
                assertEquals("the archive resource/profile contract is independently parsed",
                        LibraryBackupManifest.FORMAT_VERSION,exported.formatVersion);
                exported.validate();
                assertEquals(1,exported.notes.size());
                assertEquals(1,exported.updateProfiles.size());
                assertEquals("the archive retains both linked Vault materials",2,exported.vaultEntries.size());
                assertEquals(1,exported.videoAttachments.size());

                LibraryBackupManifest.Note note=exported.notes.get(0);
                byte[] archivedBodyBytes=readBounded(staged.resourceFile(note.noteResourceId),
                        LibraryBackupManifest.MAX_NOTE_BYTES);
                JSONObject archivedBody=new JSONObject(new String(archivedBodyBytes,
                        StandardCharsets.UTF_8));
                assertNonemptyEditorBody(archivedBody);
                assertEquals("the complete current schema-8 group body survives export without field loss",
                        NoteJsonCodec.stringify(committedBody),NoteJsonCodec.stringify(archivedBody));
                assertEquals("export preserves every authored and schema field from the pre-publish body",
                        NoteJsonCodec.stringifyWithout(migrated,"updatedAt"),
                        NoteJsonCodec.stringifyWithout(archivedBody,"updatedAt"));
                assertTrue("the archive carries the current committed body revision",
                        archivedBody.getLong("updatedAt")>=minimumPublishedAt
                                &&archivedBody.getLong("updatedAt")<=maximumPublishedAt);
                assertNotNull("the exported note retains its PDF",note.pdfResourceId);
                assertEquals("pdf_original",resource(exported,note.pdfResourceId).role);
                assertNotNull("the exported note retains its assigned cover",note.coverResourceId);
                assertEquals("assigned_cover_png",resource(exported,note.coverResourceId).role);
                assertEquals("both Vault rows remain linked to the exported note",2,note.vaultEntryIds.size());
                assertEquals(1,note.videoAttachmentIds.size());
                ArchiveUpdateProfile profile=exported.updateProfiles.get(0);
                assertEquals(note.itemId,profile.noteItemId);
                assertEquals(entry.id,profile.sourceNoteId);
                assertEquals(ArchiveUpdateProfile.SCHEMA_VERSION,profile.schemaVersion);
                assertEquals(resource(exported,note.noteResourceId).sha256,profile.bodySha256);

                boolean priorVaultPreserved=false,operationVaultPreserved=false;
                for(LibraryBackupManifest.VaultEntry vault:exported.vaultEntries) {
                    assertEquals(note.itemId,vault.noteItemId);
                    assertNotNull("the exported update profile retains source Vault bytes",
                            vault.sourceStorageResourceId);
                    LibraryBackupManifest.Resource storage=resource(exported,vault.sourceStorageResourceId);
                    assertEquals("vault_storage_markdown",storage.role);
                    assertEquals("text/markdown",storage.mediaType);
                    assertTrue("the source resource size is bounded",storage.byteLength<=LibraryBackupManifest.MAX_VAULT_BYTES);
                    byte[] exportedMarkdown=readBounded(staged.resourceFile(storage.resourceId),
                            LibraryBackupManifest.MAX_VAULT_BYTES);
                    if(java.security.MessageDigest.isEqual(priorVaultBytes,exportedMarkdown))priorVaultPreserved=true;
                    if(java.security.MessageDigest.isEqual(committedVault.markdown,exportedMarkdown)) {
                        operationVaultPreserved=true;
                        String archivedMarkdown=new String(exportedMarkdown,StandardCharsets.UTF_8);
                        assertEquals("the canonical operation header remains unchanged in the archive",1,
                                countOccurrences(archivedMarkdown,operationHeader));
                    }
                }
                assertTrue("archive keeps the pre-existing source material byte-exact",priorVaultPreserved);
                assertTrue("archive keeps the new operation bytes and exact header",operationVaultPreserved);

                LibraryBackupManifest.VideoAttachment videoRow=exported.videoAttachments.get(0);
                assertEquals(note.itemId,videoRow.noteItemId);
                assertEquals("video/mp4",videoRow.mediaType);
                assertEquals("video_attachment_mp4",resource(exported,videoRow.resourceId).role);
                assertTrue("the archive ZIP itself passed production member CRC/SHA verification",
                        staged.archiveSha256().equals(archiveSha));
            }
            assertEquals("validation did not rewrite the retained export",archiveBytes,archive.length());
            assertEquals(archiveSha,sha256(archive));
            retainArchive=true;
            System.out.println("PADNOTE_SYNTHETIC_OPERATION_ARCHIVE "
                    +"relative_path=cache/"+exportDirectory.getName()+"/"+archive.getName()
                    +" bytes="+archiveBytes+" sha256="+archiveSha
                    +" profile_schema=2 pdf=1 cover=1 video=1 vault_entries=2 operation_header=1");
        } finally {
            deleteOwnedTree(isolated.getFilesDir());
            if(!retainArchive)deleteOwnedTree(exportDirectory);
        }
    }

    private static NoteStore.Entry importOnePagePdf(Context context) throws Exception {
        PdfDocument document=new PdfDocument();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try {
            PdfDocument.Page page=document.startPage(
                    new PdfDocument.PageInfo.Builder(240,320,1).create());
            page.getCanvas().drawColor(Color.WHITE);
            document.finishPage(page);
            document.writeTo(bytes);
        } finally { document.close(); }
        return PdfNoteIO.importFile(context,new ByteArrayInputStream(bytes.toByteArray()),
                "Synthetic archive source");
    }

    private static NoteStore.Entry findCurrentEntry(Context context,String noteId)throws Exception {
        for(NoteStore.Entry candidate:NoteStore.list(context))
            if(noteId.equals(candidate.id))return candidate;
        throw new java.io.IOException("MIGRATED_FIXTURE_ENTRY_MISSING");
    }

    private static JSONObject migrateImportedPdfThroughEditor(Context context,
                                                               NoteStore.Entry entry) throws Exception {
        JSONObject imported=NoteStore.load(context,entry.id);
        assertEquals("PDF import starts at its documented legacy schema",7,
                imported.getInt("schemaVersion"));
        InkStroke fixtureStroke=new InkStroke("export-fixture-ink",Color.BLUE,2.5d,
                Long.valueOf(1730000000000L),false);
        fixtureStroke.points.add(new InkPoint(48.25d,72.5d,Long.valueOf(1730000000001L),0.65d));
        fixtureStroke.points.add(new InkPoint(93.75d,118.125d,Long.valueOf(1730000000002L),0.9d));
        JSONArray seededStrokes=new JSONArray();
        seededStrokes.put(fixtureStroke.toJson());
        imported.put("strokes",seededStrokes);
        JSONObject[] encoded=new JSONObject[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            NoteCanvasView canvas=new NoteCanvasView(context);
            try {
                canvas.layout(0,0,800,1000);
                canvas.loadJsonDocument(imported);
                NoteToolContext tools=canvas.createToolContext(null);
                PlacementResolver.Placement placement=tools.resolvePlacement(
                        new JSONObject().put("page",2).put("widthDp",420));
                assertEquals("PDF-safe model placement resolves to the blank appendix page",1,
                        placement.pageIndex);
                JSONObject flowReport=tools.createTextFlow(
                        FIXTURE_AI_TEXT,NoteTextBox.Format.MARKDOWN,placement);
                if(flowReport==null)throw new AssertionError("canvas did not create the note text flow");
                canvas.addPage();
                if(!canvas.deletePage(2))throw new AssertionError("canvas did not complete fixture page edit");
                encoded[0]=canvas.toJsonDocument(entry.id,entry.title);
            } catch(Exception error) {
                throw new AssertionError("explicit PDF fixture migration failed",error);
            } finally { canvas.onDetachedFromWindow(); }
        });
        assertNotNull("the current editor encoder returned a document",encoded[0]);
        assertEquals(8,encoded[0].getInt("schemaVersion"));
        assertEquals(1,encoded[0].getInt("pdfPageCount"));
        assertNonemptyEditorBody(encoded[0]);
        NoteStore.save(context,entry.id,entry.title,NoteJsonCodec.stringify(encoded[0]));
        JSONObject reopened=NoteStore.load(context,entry.id);
        assertEquals("the persisted body is schema 8 before adoption",8,
                reopened.getInt("schemaVersion"));
        assertEquals("the imported PDF sidecar remains linked",1,
                reopened.getInt("pdfPageCount"));
        assertNonemptyEditorBody(reopened);
        assertEquals("saving changes only the expected updatedAt timestamp",
                NoteJsonCodec.stringifyWithout(encoded[0],"updatedAt"),
                NoteJsonCodec.stringifyWithout(reopened,"updatedAt"));
        return reopened;
    }

    private static void assertNonemptyEditorBody(JSONObject document)throws Exception {
        assertEquals(8,document.getInt("schemaVersion"));
        JSONArray strokes=document.getJSONArray("strokes");
        assertTrue("the exported note contains at least one real ink stroke",strokes.length()>=1);
        JSONObject stroke=strokes.getJSONObject(0);
        assertTrue("fixture stroke has multiple persisted points",stroke.getJSONArray("points").length()>=2);
        JSONArray flows=document.getJSONArray("textFlows");
        assertTrue("the exported note contains a note-body AI/text flow",flows.length()>=1);
        boolean foundFixtureFlow=false;
        for(int index=0;index<flows.length();index++) {
            JSONObject flow=flows.getJSONObject(index);
            if(FIXTURE_AI_TEXT.equals(flow.optString("source")))foundFixtureFlow=true;
        }
        assertTrue("the nonempty text flow retains its source text",foundFixtureFlow);
        assertTrue("page geometry is present",document.getDouble("pageWidth")>0d
                &&document.getDouble("pageHeight")>0d);
        assertTrue("page style remains a structured object",document.getJSONObject("pageStyle").length()>0);
        assertTrue("authored page edit serial is retained",document.getLong("authorPageEditSerial")>0);
        assertTrue("page topology serial is retained",document.getLong("authorPageTopologySerial")>0);
        assertTrue("independent viewport scale is present and valid",
                document.has("viewportScale")&&document.getDouble("viewportScale")>0d);
        assertTrue("viewport zoom remains separately present and valid",
                document.has("viewportZoom")&&document.getDouble("viewportZoom")>0d);
        assertNotEquals("absolute viewport scale is not conflated with relative zoom",
                Double.doubleToLongBits(document.getDouble("viewportScale")),
                Double.doubleToLongBits(document.getDouble("viewportZoom")));
    }

    private static void copyAsset(Context context,String name,File destination)throws Exception {
        try(InputStream input=context.getAssets().open(name);
            FileOutputStream output=new FileOutputStream(destination,false)) {
            byte[] buffer=new byte[16384];
            for(int count;(count=input.read(buffer))!=-1;)output.write(buffer,0,count);
            output.flush();output.getFD().sync();
        }
    }

    private static String sha256(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(FileInputStream input=new FileInputStream(file)) {
            byte[] buffer=new byte[16384];
            for(int count;(count=input.read(buffer))!=-1;)digest.update(buffer,0,count);
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] bytes)throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&0xff));
        return out.toString();
    }

    private static void assertCanonicalUuid(String value) {
        assertNotNull(value);
        try { assertEquals(value,UUID.fromString(value).toString()); }
        catch(IllegalArgumentException invalid) { fail("operation ID is not a canonical UUID"); }
    }

    private static int countOccurrences(String value,String needle) {
        int count=0,offset=0;
        while((offset=value.indexOf(needle,offset))>=0){count++;offset+=needle.length();}
        return count;
    }

    private static String operationHeaderId(String markdown) {
        String prefix="digitization-operation-id: ";
        int start=markdown.indexOf(prefix);
        assertTrue("operation header exists",start>=0);
        start+=prefix.length();
        int end=markdown.indexOf('\n',start);
        if(end<0)end=markdown.length();
        String id=markdown.substring(start,end).trim();
        assertEquals("operation header contains one canonical UUID",36,id.length());
        assertCanonicalUuid(id);
        assertEquals("operation header occurs once",1,countOccurrences(markdown,prefix));
        return id;
    }

    private static LibraryBackupManifest.Resource resource(LibraryBackupManifest manifest,String id) {
        for(LibraryBackupManifest.Resource value:manifest.resources)if(value.resourceId.equals(id))return value;
        throw new AssertionError("archive resource missing");
    }

    private static byte[] readBounded(File file,long limit)throws Exception {
        if(!file.isFile()||file.length()<0||file.length()>limit)throw new java.io.IOException("EXPORT_MEMBER_INVALID");
        ByteArrayOutputStream output=new ByteArrayOutputStream((int)file.length());
        try(FileInputStream input=new FileInputStream(file)) {
            byte[] buffer=new byte[8192];long total=0;
            for(int count;(count=input.read(buffer))!=-1;) {
                total+=count;if(total>limit)throw new java.io.IOException("EXPORT_MEMBER_LIMIT");
                output.write(buffer,0,count);
            }
        }
        return output.toByteArray();
    }

    private static Context isolatedContext(Context base)throws Exception {
        File directory=new File(base.getCacheDir(),"digitization-operation-export-source-"+UUID.randomUUID());
        if(!directory.mkdir())throw new java.io.IOException("FIXTURE_ROOT_CREATE_FAILED");
        return new ContextWrapper(base) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return directory;}
        };
    }

    private static void deleteOwnedTree(File file) {
        if(file==null||!file.exists())return;
        File[] children=file.listFiles();
        if(children!=null)for(File child:children)deleteOwnedTree(child);
        if(!file.delete())throw new AssertionError("owned synthetic fixture cleanup failed");
    }

}
