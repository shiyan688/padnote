package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.padnote.android.streaming.LegacyFileCapture;
import com.padnote.android.streaming.StreamingGroupStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Whole-group video and Vault membership mutations; ordinary UI callbacks are a later route phase. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class NoteGroupFacadeMediaMutationInstrumentedTest {
    @Test public void legacyVideoCaptureCopyStreamsAndRejectsChangedInputs() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path fixture=target.getFilesDir().toPath().resolve("legacy-copy-"+UUID.randomUUID());
        Files.createDirectory(fixture);
        try {
            File source=fixture.resolve("source.mp4").toFile();
            writePatterned(source,12L*1024*1024);
            long max=StreamingGroupStore.VIDEO_MAX;
            LegacyFileCapture.Result expected=LegacyFileCapture.inspect(target,source,max);
            assertNull("inspect hashes the large source without retaining its bytes",expected.bytes);
            File copied=fixture.resolve("copied.mp4").toFile();
            LegacyFileCapture.Result result=LegacyFileCapture.copyTo(target,expected,copied,max);
            assertNull("the copied result remains streaming-backed",result.bytes);
            assertEquals(expected.size,result.size);assertEquals(expected.sha256,result.sha256);
            assertEquals(expected.size,Files.size(copied.toPath()));
            assertEquals(expected.sha256,shaFile(copied.toPath()));

            File existing=fixture.resolve("existing.mp4").toFile();byte[] sentinel={7,6,5,4};
            Files.write(existing.toPath(),sentinel);
            try { LegacyFileCapture.copyTo(target,expected,existing,max);fail("pre-existing target is never replaced"); }
            catch(java.io.IOException rejected){assertEquals("LEGACY_CAPTURE_COPY_DESTINATION_EXISTS",rejected.getMessage());}
            assertArrayEquals("pre-existing destination bytes remain exact",sentinel,Files.readAllBytes(existing.toPath()));
            File protectedFile=fixture.resolve("protected.mp4").toFile();byte[] protectedBytes={3,1,4,1,5};
            Files.write(protectedFile.toPath(),protectedBytes);
            File linkedDestination=fixture.resolve("linked-destination.mp4").toFile();
            Files.createSymbolicLink(linkedDestination.toPath(),protectedFile.toPath());
            try { LegacyFileCapture.copyTo(target,expected,linkedDestination,max);fail("symlink target is never followed or replaced"); }
            catch(java.io.IOException rejected){assertEquals("LEGACY_CAPTURE_COPY_DESTINATION_EXISTS",rejected.getMessage());}
            assertArrayEquals("symlink destination target remains exact",protectedBytes,Files.readAllBytes(protectedFile.toPath()));

            LegacyFileCapture.Result staleHash=LegacyFileCapture.inspect(target,source,max);
            try(java.io.RandomAccessFile changed=new java.io.RandomAccessFile(source,"rw")) {
                changed.seek(0);changed.write(0x51);changed.getFD().sync();
            }
            File failedCopy=fixture.resolve("failed-hash.mp4").toFile();
            try { LegacyFileCapture.copyTo(target,staleHash,failedCopy,max);fail("same-size source mutation must fail its captured SHA"); }
            catch(java.io.IOException rejected){assertEquals("LEGACY_CAPTURE_COPY_HASH_CHANGED",rejected.getMessage());}
            assertFalse("failed owned output is cleaned",Files.exists(failedCopy.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS));

            LegacyFileCapture.Result staleSize=LegacyFileCapture.inspect(target,source,max);
            try(FileOutputStream append=new FileOutputStream(source,true)){append.write(0x22);append.getFD().sync();}
            File failedSize=fixture.resolve("failed-size.mp4").toFile();
            try { LegacyFileCapture.copyTo(target,staleSize,failedSize,max);fail("source size change must be rejected"); }
            catch(java.io.IOException rejected){assertEquals("LEGACY_CAPTURE_COPY_SOURCE_INVALID",rejected.getMessage());}
            assertFalse("size mismatch creates no destination",Files.exists(failedSize.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS));

            File symlink=fixture.resolve("source-link.mp4").toFile();Files.createSymbolicLink(symlink.toPath(),source.toPath());
            try { LegacyFileCapture.inspect(target,symlink,max);fail("symlink inputs are rejected"); }
            catch(java.io.IOException rejected){assertEquals("LEGACY_CAPTURE_SIZE_OR_IDENTITY",rejected.getMessage());}
        } finally { deleteFixture(fixture); }
    }

    @Test public void legacyVideoCaptureRejectsHardlinkWhenFilesystemAllowsCreation() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path fixture=target.getFilesDir().toPath().resolve("legacy-hardlink-"+UUID.randomUUID());
        Files.createDirectory(fixture);
        try {
            File source=fixture.resolve("source.mp4").toFile();
            Files.write(source.toPath(),new byte[]{9,8,7,6});
            File hardlink=fixture.resolve("source-hardlink.mp4").toFile();
            try {
                android.system.Os.link(source.getAbsolutePath(),hardlink.getAbsolutePath());
            } catch(android.system.ErrnoException denied) {
                if(denied.errno==android.system.OsConstants.EACCES||denied.errno==android.system.OsConstants.EPERM) {
                    org.junit.Assume.assumeTrue("runtime policy does not permit creating an in-app hardlink; boundary was not exercised",false);
                }
                throw denied;
            }
            try {
                LegacyFileCapture.inspect(target,hardlink,1024);
                fail("a successfully created hardlinked input must be rejected");
            } catch(java.io.IOException rejected) {
                assertEquals("LEGACY_CAPTURE_SIZE_OR_IDENTITY",rejected.getMessage());
            }
        } finally { deleteFixture(fixture); }
    }

    @Test public void videoAndVaultMutationsUseCapturedCasAndRetainEveryWinningHistory() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path fixture=target.getFilesDir().toPath().resolve("media-mutation-"+UUID.randomUUID());
        Files.createDirectory(fixture);
        Context context=new FixtureContext(target,fixture.toFile());
        try {
            String title="R58 media mutation "+UUID.randomUUID();
            NoteStore.Entry entry=NoteStore.create(context,title);
            JSONObject body=NotePrecisionJsonParser.parseObject(NoteStore.load(context,entry.id).toString());
            body.put("pageCount",2).put("pdfPageCount",1)
                    .put("viewportCenterX",-0.0d).put("viewportCenterY",9007199254740993L)
                    .put("pageGap",768.00001d);
            NoteStore.save(context,entry.id,title,NoteJsonCodec.stringify(body));
            File pdf=NoteStore.pdfFile(context,entry.id);writePdf(pdf);
            Bitmap cover=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);
            cover.eraseColor(Color.CYAN);CoverStore.assign(context,entry.id,cover);cover.recycle();

            File initialVideo=new File(context.getFilesDir(),"initial.mp4");
            copyAsset("video/roundtrip.mp4",initialVideo);
            byte[] initialVideoBytes=Files.readAllBytes(initialVideo.toPath());
            String initialVideoSha=sha(initialVideoBytes);
            VideoAttachmentStore videos=new VideoAttachmentStore(context);
            VideoAttachmentStore.Attachment initialAttachment=videos.attach(entry.id,entry.updatedAt,
                    initialVideoSha,initialVideoSha,"task-"+UUID.randomUUID(),"remote-"+UUID.randomUUID(),
                    "connection-"+UUID.randomUUID(),1,"HERMES","BRIDGE","bridge-"+UUID.randomUUID(),
                    "instance-"+UUID.randomUUID(),sha("certificate".getBytes(StandardCharsets.UTF_8)),
                    "artifact-"+UUID.randomUUID(),"Initial video","video/mp4",initialVideoBytes.length,
                    initialVideoSha,initialVideo);
            VaultStore legacyVault=new VaultStore(context);
            String initialVaultFile=legacyVault.write(entry.id,"Initial Vault",2,entry.updatedAt,
                    java.util.Collections.singletonList("Original linked Vault bytes."));

            NoteGroupFacade facade=new NoteGroupFacade(context);
            StreamingGroupStore.Snapshot base0=facade.adoptLegacyNote(entry.id,(old,staged)->true);
            byte[] baseBody=member(base0,"body.bin"),basePdf=member(base0,"pdf.bin"),baseCover=member(base0,"cover.bin");
            assertPrecisionBody(base0);
            String initialVideoContent="video/"+initialAttachment.id+"/content.bin";
            String initialVideoMetadata=initialVideoContent.replace("content.bin","metadata.bin");
            String initialVaultId=onlyVaultId(base0);
            String initialVaultContent="vault/"+initialVaultId+"/content.bin";
            String initialVaultMetadata=initialVaultContent.replace("content.bin","metadata.bin");
            byte[] baseInitialVideo=member(base0,initialVideoContent),baseInitialVideoMetadata=member(base0,initialVideoMetadata);
            byte[] baseInitialVault=member(base0,initialVaultContent),baseInitialVaultMetadata=member(base0,initialVaultMetadata);
            Map<String,byte[]> base0Members=members(base0);

            // Add one new video. The source is captured under the fixture's trusted files root;
            // metadata is separately tied to this local note and material UUID.
            File addedVideo=new File(context.getFilesDir(),"added.mp4");copyAsset("video/roundtrip.mp4",addedVideo);
            LegacyFileCapture.Result addedVideoCapture=LegacyFileCapture.inspect(context,addedVideo,
                    StreamingGroupStore.VIDEO_MAX);
            VideoAttachmentStore.Attachment addedAttachment=videoMetadata(entry.id,addedVideoCapture);
            VideoAttachmentStore.Attachment foreignOwnerAttachment=videoMetadata(
                    "00000000-0000-4000-8000-000000000099",addedVideoCapture);
            try {
                facade.attachVideoRevision(entry.id,base0.revision,base0.digest,foreignOwnerAttachment,addedVideoCapture);
                fail("video metadata owned by a different local note must be rejected");
            } catch(java.io.IOException invalid) {
                assertEquals("GROUP_VIDEO_INPUT_IDENTITY_INVALID",invalid.getMessage());
            }
            assertCurrentUnchanged(facade,entry.id,base0);
            StreamingGroupStore.Snapshot base1=facade.attachVideoRevision(entry.id,base0.revision,base0.digest,
                    addedAttachment,addedVideoCapture);
            String addedVideoContent="video/"+addedAttachment.id+"/content.bin";
            String addedVideoMetadata=addedVideoContent.replace("content.bin","metadata.bin");
            String addedVideoMediaType=addedVideoContent.replace("content.bin","media-type.txt");
            assertTrue(base1.memberSizes().containsKey(addedVideoContent));
            assertTrue(base1.memberSizes().containsKey(addedVideoMetadata));
            assertArrayEquals("new video adds the canonical media-type member",
                    (addedAttachment.mediaType+"\n").getBytes(StandardCharsets.UTF_8),member(base1,addedVideoMediaType));
            assertEquals("one complete video material adds content, media type, and metadata",
                    base0Members.size()+3,base1.memberSizes().size());
            assertArrayEquals(basePdf,member(base1,"pdf.bin"));assertArrayEquals(baseCover,member(base1,"cover.bin"));
            assertArrayEquals(baseInitialVideo,member(base1,initialVideoContent));
            assertArrayEquals(baseInitialVideoMetadata,member(base1,initialVideoMetadata));
            assertArrayEquals(baseInitialVault,member(base1,initialVaultContent));
            assertArrayEquals(baseInitialVaultMetadata,member(base1,initialVaultMetadata));
            assertBodyContentUnchanged(baseBody,member(base1,"body.bin"));
            assertPrecisionBody(base1);
            assertPreviousRevision(facade,entry.id,base0,base0Members);
            assertArrayEquals(addedVideoCaptureBytes(addedVideoCapture),member(base1,addedVideoContent));
            padnote.material.StorageAdapter.Projection addedVideoProjection=padnote.material.StorageAdapter.videoJson(
                    addedAttachment.json().toString(),"android",
                    new padnote.material.StorageAdapter.Association(addedAttachment.id,base0.lineage,
                            "linked_note",base0.lineage),addedVideoCapture.size,addedVideoCapture.sha256);
            assertArrayEquals("new video metadata binds the exact linked owner and bytes",
                    addedVideoProjection.descriptor(),member(base1,addedVideoMetadata));

            // A callback holding base0 cannot remove a member after the add has won.
            try {
                facade.removeVideoRevision(entry.id,base0.revision,base0.digest,initialAttachment.id);
                fail("a stale media callback must not rebind to the new revision");
            } catch(java.io.IOException stale) { assertEquals("BASE_CAS_CONFLICT",stale.getMessage()); }
            assertCurrentUnchanged(facade,entry.id,base1);

            // Remove only the original video, retaining the newly added attachment.
            StreamingGroupStore.Snapshot base2=facade.removeVideoRevision(entry.id,base1.revision,
                    base1.digest,initialAttachment.id);
            assertFalse(base2.memberSizes().containsKey(initialVideoContent));
            assertFalse(base2.memberSizes().containsKey(initialVideoContent.replace("content.bin","media-type.txt")));
            assertFalse(base2.memberSizes().containsKey(initialVideoMetadata));
            assertEquals("removing one video removes its complete three-member material",
                    base1.memberSizes().size()-3,base2.memberSizes().size());
            assertArrayEquals(addedVideoCaptureBytes(addedVideoCapture),member(base2,addedVideoContent));
            assertArrayEquals(member(base1,"pdf.bin"),member(base2,"pdf.bin"));
            assertArrayEquals(member(base1,"cover.bin"),member(base2,"cover.bin"));
            assertArrayEquals(member(base1,initialVaultContent),member(base2,initialVaultContent));
            assertArrayEquals(member(base1,initialVaultMetadata),member(base2,initialVaultMetadata));
            assertBodyContentUnchanged(member(base1,"body.bin"),member(base2,"body.bin"));
            assertPrecisionBody(base2);
            assertPreviousRevision(facade,entry.id,base1,members(base1));

            // Publishing a malformed or foreign-owner Markdown document changes no marker.
            String newVaultId=UUID.randomUUID().toString();
            byte[] foreignMarkdown=vaultMarkdown("note-foreign-owner","Wrong owner",3,
                    System.currentTimeMillis(),System.currentTimeMillis(),"must not publish");
            try {
                facade.publishVaultRevision(entry.id,base2.revision,base2.digest,newVaultId,foreignMarkdown);
                fail("a Vault member owned by a different local note must be rejected");
            } catch(java.io.IOException invalid) { assertEquals("GROUP_VAULT_OWNER_MISMATCH",invalid.getMessage()); }
            assertCurrentUnchanged(facade,entry.id,base2);

            byte[] newVaultBytes=vaultMarkdown(entry.id,"Published Vault",3,System.currentTimeMillis(),
                    System.currentTimeMillis(),"Published bytes are linked to this note.");
            StreamingGroupStore.Snapshot base3=facade.publishVaultRevision(entry.id,base2.revision,
                    base2.digest,newVaultId,newVaultBytes);
            String newVaultContent="vault/"+newVaultId+"/content.bin";
            String newVaultMetadata=newVaultContent.replace("content.bin","metadata.bin");
            String newVaultMediaType=newVaultContent.replace("content.bin","media-type.txt");
            assertArrayEquals(newVaultBytes,member(base3,newVaultContent));
            assertArrayEquals("new Vault adds the canonical media-type member",
                    "text/markdown\n".getBytes(StandardCharsets.UTF_8),member(base3,newVaultMediaType));
            padnote.material.StorageAdapter.Projection publishedVaultProjection=padnote.material.StorageAdapter.androidVaultMarkdown(
                    newVaultBytes,new padnote.material.StorageAdapter.Association(newVaultId,base2.lineage,
                            "linked_note",base2.lineage));
            assertArrayEquals("published Vault descriptor binds exact markdown and owner",
                    publishedVaultProjection.descriptor(),member(base3,newVaultMetadata));
            assertArrayEquals("the pre-existing Vault descriptor remains byte-exact",baseInitialVaultMetadata,
                    member(base3,initialVaultMetadata));
            assertArrayEquals("the pre-existing Vault content remains byte-exact",baseInitialVault,
                    member(base3,initialVaultContent));
            assertEquals("the original and new Vault members both remain",2,contentMembers(base3,"vault/"));
            assertEquals("one complete Vault material adds content, media type, and metadata",
                    base2.memberSizes().size()+3,base3.memberSizes().size());
            assertArrayEquals(member(base2,"pdf.bin"),member(base3,"pdf.bin"));
            assertArrayEquals(member(base2,"cover.bin"),member(base3,"cover.bin"));
            assertArrayEquals(member(base2,addedVideoContent),member(base3,addedVideoContent));
            assertArrayEquals(member(base2,initialVaultContent),member(base3,initialVaultContent));
            assertBodyContentUnchanged(member(base2,"body.bin"),member(base3,"body.bin"));
            assertPrecisionBody(base3);
            assertPreviousRevision(facade,entry.id,base2,members(base2));

            // Replace the existing Vault UUID; only its content/descriptor may change.
            byte[] replacement=vaultMarkdown(entry.id,"Replaced Vault",4,System.currentTimeMillis(),
                    System.currentTimeMillis(),"Replacement keeps the same material identity.");
            StreamingGroupStore.Snapshot base4=facade.replaceVaultRevision(entry.id,base3.revision,
                    base3.digest,initialVaultId,replacement);
            assertArrayEquals(replacement,member(base4,initialVaultContent));
            padnote.material.StorageAdapter.Projection replacementProjection=padnote.material.StorageAdapter.androidVaultMarkdown(
                    replacement,new padnote.material.StorageAdapter.Association(initialVaultId,base3.lineage,
                            "linked_note",base3.lineage));
            assertArrayEquals("replacement descriptor commits the new exact markdown",
                    replacementProjection.descriptor(),member(base4,initialVaultMetadata));
            assertTrue("replacement keeps its stable Vault member UUID",base4.memberSizes().containsKey(initialVaultMetadata));
            assertEquals("replacing a Vault preserves the complete member set",base3.memberSizes().size(),base4.memberSizes().size());
            assertArrayEquals(member(base3,newVaultMediaType),member(base4,newVaultMediaType));
            assertArrayEquals(member(base3,"pdf.bin"),member(base4,"pdf.bin"));
            assertArrayEquals(member(base3,"cover.bin"),member(base4,"cover.bin"));
            assertArrayEquals(member(base3,addedVideoContent),member(base4,addedVideoContent));
            assertArrayEquals(member(base3,addedVideoContent.replace("content.bin","metadata.bin")),
                    member(base4,addedVideoContent.replace("content.bin","metadata.bin")));
            assertArrayEquals(member(base3,newVaultContent),member(base4,newVaultContent));
            assertBodyContentUnchanged(member(base3,"body.bin"),member(base4,"body.bin"));
            assertPrecisionBody(base4);
            assertPreviousRevision(facade,entry.id,base3,members(base3));

            // Delete one Vault UUID while preserving the other and all unrelated material.
            StreamingGroupStore.Snapshot base5=facade.deleteVaultRevision(entry.id,base4.revision,
                    base4.digest,newVaultId);
            assertFalse(base5.memberSizes().containsKey(newVaultContent));
            assertFalse(base5.memberSizes().containsKey(newVaultMediaType));
            assertFalse(base5.memberSizes().containsKey(newVaultContent.replace("content.bin","metadata.bin")));
            assertEquals("deleting one Vault removes its complete three-member material",
                    base4.memberSizes().size()-3,base5.memberSizes().size());
            assertArrayEquals(replacement,member(base5,initialVaultContent));
            assertArrayEquals(member(base4,"pdf.bin"),member(base5,"pdf.bin"));
            assertArrayEquals(member(base4,"cover.bin"),member(base5,"cover.bin"));
            assertArrayEquals(member(base4,addedVideoContent),member(base5,addedVideoContent));
            assertBodyContentUnchanged(member(base4,"body.bin"),member(base5,"body.bin"));
            assertPrecisionBody(base5);
            assertPreviousRevision(facade,entry.id,base4,members(base4));

            try {
                facade.deleteVaultRevision(entry.id,base3.revision,base3.digest,initialVaultId);
                fail("a stale Vault delete cannot overwrite the winning replacement");
            } catch(java.io.IOException stale) { assertEquals("BASE_CAS_CONFLICT",stale.getMessage()); }
            assertCurrentUnchanged(facade,entry.id,base5);

            StreamingGroupStore.Snapshot retired=facade.retireGroup(entry.id,base5.revision,base5.digest);
            try {
                facade.deleteVaultRevision(entry.id,retired.revision,retired.digest,initialVaultId);
                fail("retired groups reject media mutation");
            } catch(java.io.IOException unavailable) { assertEquals("GROUP_NOTE_RETIRED",unavailable.getMessage()); }
            assertRetiredUnchanged(facade,entry.id,retired);

            // Damaged current marker cannot fall back to a sidecar or publish a mutation.
            File marker=new File(new File(new File(context.getFilesDir(),"note-groups"),"visible"),retired.lineage+".marker");
            byte[] goodMarker=Files.readAllBytes(marker.toPath());
            try {
                Files.write(marker.toPath(),new byte[]{1,2,3});
                try {
                    facade.deleteVaultRevision(entry.id,retired.revision,retired.digest,initialVaultId);
                    fail("a damaged group marker must fail closed");
                } catch(java.io.IOException damaged) {
                    assertNotEquals("a damaged marker is not treated as a missing/legacy note",
                            "UPDATE_TARGET_REQUIRES_GROUP_ADOPTION",damaged.getMessage());
                }
            } finally { Files.write(marker.toPath(),goodMarker); }
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(fixture)) {
                for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))
                    Files.deleteIfExists(path);
            }
            assertFalse("cleanup is confined to the UUID fixture",Files.exists(fixture));
        }
    }

    private static VideoAttachmentStore.Attachment videoMetadata(String noteId,
            LegacyFileCapture.Result content)throws Exception {
        String id=UUID.randomUUID().toString(),sha=content.sha256;
        return new VideoAttachmentStore.Attachment(id,noteId,System.currentTimeMillis(),sha,sha,1,
                "HERMES","BRIDGE",sha,"task-"+UUID.randomUUID(),"remote-"+UUID.randomUUID(),
                "connection-"+UUID.randomUUID(),"bridge-"+UUID.randomUUID(),"instance-"+UUID.randomUUID(),
                "artifact-"+UUID.randomUUID(),"Added video","video/mp4",content.size,sha,
                "video-"+id+".mp4",System.currentTimeMillis(),"computer_task","linked_note",noteId,1,
                "source_and_task_payload","verified_local_copy");
    }

    private static byte[] vaultMarkdown(String noteId,String title,int pages,long created,long sourceModified,
            String paragraph) {
        String safe=VaultStore.sanitizeTitle(title);
        String markdown="---\n"+
                "title: "+safe+"\n"+
                "note-id: "+noteId+"\n"+
                "pages: "+pages+"\n"+
                "digitized: "+new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(new Date(created))+"\n"+
                "digitized-epoch: "+created+"\n"+
                "source-modified: "+sourceModified+"\n---\n\n# "+safe+"\n\n## 第 1 页\n\n"+paragraph+"\n";
        return markdown.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] addedVideoCaptureBytes(LegacyFileCapture.Result result)throws Exception {
        return Files.readAllBytes(result.path);
    }
    private static int contentMembers(StreamingGroupStore.Snapshot snapshot,String prefix)
            throws java.io.IOException {
        int count=0;for(String member:snapshot.memberSizes().keySet())
            if(member.startsWith(prefix)&&member.endsWith("/content.bin"))count++;
        return count;
    }
    private static String onlyVaultId(StreamingGroupStore.Snapshot snapshot)
            throws java.io.IOException {
        String found=null;
        for(String member:snapshot.memberSizes().keySet())if(member.matches("vault/[0-9a-f-]{36}/content.bin")){
            if(found!=null)throw new AssertionError("fixture must have one Vault member");
            found=member.substring("vault/".length(),member.length()-"/content.bin".length());
        }
        if(found==null)throw new AssertionError("fixture Vault member missing");return found;
    }
    private static byte[] member(StreamingGroupStore.Snapshot snapshot,String name)throws Exception {
        Long size=snapshot.memberSizes().get(name);assertNotNull("member present: "+name,size);
        assertTrue("test member remains within a small fixture bound",size<=Integer.MAX_VALUE);
        return snapshot.readSmall(name,size.intValue());
    }
    private static Map<String,byte[]> members(StreamingGroupStore.Snapshot snapshot)throws Exception {
        Map<String,byte[]> out=new LinkedHashMap<>();
        for(String name:snapshot.memberSizes().keySet())out.put(name,member(snapshot,name));
        return out;
    }
    private static void assertPreviousRevision(NoteGroupFacade facade,String noteId,
            StreamingGroupStore.Snapshot previous,Map<String,byte[]> expected)throws Exception {
        StreamingGroupStore.Snapshot retained=facade.openGroupRevision(noteId,previous.revision,previous.digest);
        assertEquals(expected.keySet(),retained.memberSizes().keySet());
        for(Map.Entry<String,byte[]> entry:expected.entrySet())
            assertArrayEquals("immutable history member "+entry.getKey(),entry.getValue(),member(retained,entry.getKey()));
    }
    private static void assertCurrentUnchanged(NoteGroupFacade facade,String noteId,
            StreamingGroupStore.Snapshot expected)throws Exception {
        assertSnapshotUnchanged(expected,facade.openGroup(noteId));
    }
    private static void assertRetiredUnchanged(NoteGroupFacade facade,String noteId,
            StreamingGroupStore.Snapshot expected)throws Exception {
        assertSnapshotUnchanged(expected,facade.openGroupIncludingRetired(noteId));
    }
    private static void assertSnapshotUnchanged(StreamingGroupStore.Snapshot expected,
            StreamingGroupStore.Snapshot actual)throws Exception {
        assertEquals(expected.revision,actual.revision);assertEquals(expected.digest,actual.digest);
        assertEquals(expected.memberSizes().keySet(),actual.memberSizes().keySet());
        for(String member:expected.memberSizes().keySet())
            assertArrayEquals(member,member(expected,member),member(actual,member));
    }
    private static void assertBodyContentUnchanged(byte[] before,byte[] after)throws Exception {
        JSONObject oldBody=NotePrecisionJsonParser.parseObject(new String(before,StandardCharsets.UTF_8));
        JSONObject newBody=NotePrecisionJsonParser.parseObject(new String(after,StandardCharsets.UTF_8));
        long oldUpdatedAt=oldBody.optLong("updatedAt",0),newUpdatedAt=newBody.optLong("updatedAt",0);
        oldBody.remove("updatedAt");newBody.remove("updatedAt");
        assertEquals("media-only revision preserves the complete precision-parsed body apart from updatedAt",
                canonicalJson(oldBody),canonicalJson(newBody));
        assertTrue("media publication advances the document update time",newUpdatedAt>=oldUpdatedAt);
    }
    private static String canonicalJson(Object value)throws Exception {
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value,sorted=new JSONObject();
            java.util.List<String> keys=new java.util.ArrayList<>();
            java.util.Iterator<String> iterator=object.keys();while(iterator.hasNext())keys.add(iterator.next());
            java.util.Collections.sort(keys);
            for(String key:keys)sorted.put(key,canonicalValue(object.get(key)));
            return NoteJsonCodec.stringify(sorted);
        }
        return NoteJsonCodec.stringify(new JSONObject().put("value",canonicalValue(value)));
    }
    private static Object canonicalValue(Object value)throws Exception {
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value,sorted=new JSONObject();
            java.util.List<String> keys=new java.util.ArrayList<>();
            java.util.Iterator<String> iterator=object.keys();while(iterator.hasNext())keys.add(iterator.next());
            java.util.Collections.sort(keys);
            for(String key:keys)sorted.put(key,canonicalValue(object.get(key)));
            return sorted;
        }
        if(value instanceof org.json.JSONArray) {
            org.json.JSONArray array=(org.json.JSONArray)value,sorted=new org.json.JSONArray();
            for(int i=0;i<array.length();i++)sorted.put(canonicalValue(array.get(i)));
            return sorted;
        }
        return value;
    }
    private static String jsonValue(Object value)throws Exception {
        return NoteJsonCodec.stringify(new JSONObject().put("value",value));
    }
    private static void assertPrecisionBody(StreamingGroupStore.Snapshot snapshot)throws Exception {
        String raw=new String(member(snapshot,"body.bin"),StandardCharsets.UTF_8);
        assertTrue("media revision retains the signed zero token",raw.contains("\"viewportCenterX\":-0.0"));
        assertTrue("media revision retains the full integer token",raw.contains("\"viewportCenterY\":9007199254740993"));
        assertTrue("media revision retains the precise fractional token",raw.contains("\"pageGap\":768.00001"));
        JSONObject parsed=NotePrecisionJsonParser.parseObject(raw);
        assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits(parsed.getDouble("viewportCenterX")));
        assertEquals(9007199254740993L,parsed.getLong("viewportCenterY"));
        assertEquals(Double.doubleToRawLongBits(768.00001d),Double.doubleToRawLongBits(parsed.getDouble("pageGap")));
    }
    private static void writePatterned(File file,long bytes)throws Exception {
        byte[] buffer=new byte[64*1024];for(int i=0;i<buffer.length;i++)buffer[i]=(byte)(i*31+7);
        try(FileOutputStream output=new FileOutputStream(file)) {
            long remaining=bytes;while(remaining>0){int count=(int)Math.min(buffer.length,remaining);output.write(buffer,0,count);remaining-=count;}
            output.flush();output.getFD().sync();
        }
    }
    private static String shaFile(Path path)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[64*1024];
        try(InputStream input=Files.newInputStream(path)){for(int n;(n=input.read(buffer))!=-1;)if(n>0)digest.update(buffer,0,n);}
        StringBuilder out=new StringBuilder();for(byte value:digest.digest())out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();
    }
    private static void deleteFixture(Path fixture)throws Exception {
        if(!Files.exists(fixture,java.nio.file.LinkOption.NOFOLLOW_LINKS))return;
        try(java.util.stream.Stream<Path> paths=Files.walk(fixture)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception error){throw new RuntimeException(error);}});
        }
    }
    private static void copyAsset(String asset,File target)throws Exception {
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(asset);
            FileOutputStream output=new FileOutputStream(target)) {
            byte[] buffer=new byte[16384];for(int n;(n=input.read(buffer))!=-1;)output.write(buffer,0,n);
            output.flush();output.getFD().sync();
        }
    }
    private static void writePdf(File file)throws Exception {
        PdfDocument doc=new PdfDocument();PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(144,144,1).create());
        page.getCanvas().drawColor(Color.WHITE);Paint paint=new Paint();paint.setColor(Color.BLACK);
        page.getCanvas().drawText("media mutation fixture",10,40,paint);doc.finishPage(page);
        try(FileOutputStream output=new FileOutputStream(file)){doc.writeTo(output);output.flush();output.getFD().sync();}
        finally{doc.close();}
    }
    private static String sha(byte[] bytes)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();
        for(byte value:digest)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();
    }
    private static final class FixtureContext extends ContextWrapper {
        private final File files;
        FixtureContext(Context base,File files){super(base);this.files=files;}
        @Override public File getFilesDir(){return files;}
        @Override public File getDir(String name,int mode){
            if(name==null||!name.matches("[A-Za-z0-9._-]{1,64}"))throw new IllegalArgumentException("fixture_dir_name");
            File dir=new File(files,"app_"+name);if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("fixture_dir_create_failed");
            if(!dir.isDirectory()||Files.isSymbolicLink(dir.toPath()))throw new IllegalStateException("fixture_dir_unsafe");
            return dir;
        }
        @Override public Context getApplicationContext(){return this;}
    }
}
