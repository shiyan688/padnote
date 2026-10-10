package com.padnote.android.streaming;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.padnote.android.NativeManualUpdatePlan;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real framework decoders over a single synthetic MANUAL_UPDATE_V2 group. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class FullMediaManualUpdateInstrumentedTest {
    private static final String SOURCE_NOTE="source-note";

    @Test public void allNonemptyComponentsAreVerifiedBeforePreviewAndOnEveryReadPath() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Path owned=TrustedFilesRoot.fromApplicationContext(context).path().resolve("manual-full-media-"+UUID.randomUUID());
        Files.createDirectory(owned);
        try {
            Path root=owned.resolve("store");
            AndroidIdentityOps ops=new AndroidIdentityOps(TrustedFilesRoot.fromApplicationContext(context),root);
            StreamingGroupStore.ProjectionVerifier projection=new StreamingGroupStore.ProjectionVerifier(){
                @Override public void verifyBody(byte[] bytes)throws IOException{if(bytes==null||bytes.length==0)throw new IOException("BODY_EMPTY");}
                @Override public void verifyMetadata(String role,String id,byte[] bytes)throws IOException{if(role==null||id==null||bytes==null)throw new IOException("METADATA_INVALID");}
            };
            StreamingGroupStore.Store store=new StreamingGroupStore.Store(root,ops,projection,
                    NativeManualUpdatePlan.durableVerifier(),phase->{});
            String targetId="note-"+UUID.randomUUID().toString().replace("-",""), targetLineage=UUID.randomUUID().toString();
            String oldRevision=UUID.randomUUID().toString();
            StreamingGroupStore.ImportIdentity seedIdentity=new StreamingGroupStore.ImportIdentity(
                    targetLineage,"original-source-note",targetId,targetLineage,null,null);
            StreamingGroupStore.Snapshot old=store.commit(new StreamingGroupStore.Envelope(seedIdentity,oldRevision,
                    null,null,null,StreamingGroupStore.Action.FIRST_IMPORT,body(targetId,0,null),null,null,
                    Collections.emptyList(),Collections.emptyList()),(current,incoming)->current==null);

            String sourceLineage=UUID.randomUUID().toString(), sourceRevision=UUID.randomUUID().toString();
            byte[] png=png();
            byte[] body=body(SOURCE_NOTE,1,png);
            byte[] pdf=pdf();
            // The asset is packaged in the instrumentation APK, unlike filesDir/store.
            Context fixtureContext=InstrumentationRegistry.getInstrumentation().getContext();
            byte[] video=asset(fixtureContext,"video/roundtrip.mp4");
            byte[] cover=png();
            String videoId=UUID.randomUUID().toString(),vaultId=UUID.randomUUID().toString(),iosVaultId=UUID.randomUUID().toString();
            String videoSha=sha(video), vaultSource=androidVault(SOURCE_NOTE,"Synthetic vault");
            byte[] vaultSourceBytes=vaultSource.getBytes(StandardCharsets.UTF_8);
            NativeManualUpdatePlan.Material videoMaterial=new NativeManualUpdatePlan.Material(
                    NativeManualUpdatePlan.Role.VIDEO,videoId,UUID.randomUUID().toString(),"video/mp4","android",
                    videoMetadata(videoId,SOURCE_NOTE,video.length,videoSha),
                    new padnote.material.StorageAdapter.Association(videoId,sourceLineage,"linked_note",sourceLineage),
                    new NativeManualUpdatePlan.Facts(video.length,videoSha));
            NativeManualUpdatePlan.Material vaultMaterial=new NativeManualUpdatePlan.Material(
                    NativeManualUpdatePlan.Role.VAULT,vaultId,UUID.randomUUID().toString(),"text/markdown","android",
                    vaultSource,new padnote.material.StorageAdapter.Association(vaultId,sourceLineage,"linked_note",sourceLineage),
                    new NativeManualUpdatePlan.Facts(vaultSourceBytes.length,sha(vaultSourceBytes)));
            NativeManualUpdatePlan.Material iosVaultMaterial=new NativeManualUpdatePlan.Material(
                    NativeManualUpdatePlan.Role.VAULT,iosVaultId,UUID.randomUUID().toString(),"text/markdown","ios",
                    iosVaultMetadata(iosVaultId,SOURCE_NOTE,"Synthetic iOS vault",vaultSource),
                    new padnote.material.StorageAdapter.Association(iosVaultId,sourceLineage,"linked_note",sourceLineage),
                    new NativeManualUpdatePlan.Facts(vaultSourceBytes.length,sha(vaultSourceBytes)));
            Path videoPath=write(owned.resolve("input-video.mp4"),video);
            String vaultDestination=vaultSource.replace("note-id: "+SOURCE_NOTE,"note-id: "+targetId);
            Path vaultPath=write(owned.resolve("input-vault.md"),vaultDestination.getBytes(StandardCharsets.UTF_8));
            Path pdfPath=write(owned.resolve("input.pdf"),pdf),coverPath=write(owned.resolve("cover.png"),cover);
            Map<String,String> mapping=new LinkedHashMap<>();
            mapping.put(SOURCE_NOTE,targetId);
            mapping.put(videoId,videoMaterial.destinationId());
            mapping.put(vaultId,vaultMaterial.destinationId());
            mapping.put(iosVaultId,iosVaultMaterial.destinationId());
            NativeManualUpdatePlan plan=plan(old,targetId,targetLineage,sourceLineage,sourceRevision,body,pdf,cover,
                    videoMaterial,Arrays.asList(vaultMaterial,iosVaultMaterial),mapping);
            StreamingGroupStore.ContentInput pdfInput=input(pdfPath,pdf),coverInput=input(coverPath,cover);
            StreamingGroupStore.ContentInput videoInput=input(videoPath,video),vaultInput=input(vaultPath,vaultDestination.getBytes(StandardCharsets.UTF_8));
            StreamingGroupStore.MemberInput videoMember=plan.videoMemberInput(videoId,videoInput);
            StreamingGroupStore.MemberInput vaultMember=plan.vaultMemberInput(vaultId,vaultInput);
            Path iosVaultPath=write(owned.resolve("input-ios-vault.md"),vaultSourceBytes);
            StreamingGroupStore.MemberInput iosVaultMember=plan.vaultMemberInput(iosVaultId,input(iosVaultPath,vaultSourceBytes));
            assertEquals("NativeManualUpdatePlan must emit the lower-case stageList video role","video",videoMember.role);
            assertEquals("NativeManualUpdatePlan must emit the lower-case stageList vault role","vault",vaultMember.role);
            assertEquals("iOS Vault material still uses the same store wire role","vault",iosVaultMember.role);
            StreamingGroupStore.MemberInput wrongRoleCase=new StreamingGroupStore.MemberInput("VIDEO",videoMember.materialId,
                    videoMember.mediaType,videoMember.metadata(),videoMember.content);
            try{
                plan.envelope(UUID.randomUUID().toString(),pdfInput,coverInput,Collections.singletonList(wrongRoleCase),
                        Arrays.asList(vaultMember,iosVaultMember));
                fail("uppercase enum spelling must not pass the lower-case material wire contract");
            }catch(IllegalArgumentException expected){}
            String revision=UUID.randomUUID().toString();
            StreamingGroupStore.Envelope envelope=plan.envelope(revision,pdfInput,coverInput,
                    Collections.singletonList(videoMember),Arrays.asList(vaultMember,iosVaultMember));
            AtomicInteger previewCalls=new AtomicInteger();
            StreamingGroupStore.Snapshot committed=store.commitManualUpdateV2(envelope,(current,incoming)->{
                assertEquals("decoder validation must finish before the preview is shown",1,previewCalls.incrementAndGet());
                exerciseVerifiedLease(incoming,"video/"+videoMaterial.destinationId()+"/content.bin",video);
                return current!=null&&current.digest.equals(old.digest);
            },plan);
            assertEquals(targetId,committed.localId);
            assertEquals("present\n",new String(committed.readSmall("pdf.state",16),StandardCharsets.US_ASCII));
            assertEquals("present\n",new String(committed.readSmall("cover.state",16),StandardCharsets.US_ASCII));
            assertEquals("present\n",new String(committed.readSmall("video.state",16),StandardCharsets.US_ASCII));
            assertEquals("present\n",new String(committed.readSmall("vault.state",16),StandardCharsets.US_ASCII));
            assertEquals(pdf.length,committed.readSmall("pdf.bin",(int)StreamingGroupStore.PDF_MAX).length);
            assertEquals(cover.length,committed.readSmall("cover.bin",(int)StreamingGroupStore.COVER_MAX).length);
            assertEquals(videoSha,committed.memberSha256("video/"+videoMaterial.destinationId()+"/content.bin"));
            String storedVault=new String(committed.readSmall("vault/"+vaultMaterial.destinationId()+"/content.bin",
                    (int)StreamingGroupStore.VAULT_MAX),StandardCharsets.UTF_8);
            assertTrue(storedVault.contains("title: Synthetic vault\n"));
            assertTrue(storedVault.contains("note-id: "+targetId+"\n"));
            String storedIosVault=new String(committed.readSmall("vault/"+iosVaultMaterial.destinationId()+"/content.bin",
                    (int)StreamingGroupStore.VAULT_MAX),StandardCharsets.UTF_8);
            assertTrue(storedIosVault.contains("Synthetic vault"));
            assertEquals(revision,store.read(targetLineage).revision);
            assertEquals(revision,store.readRevision(targetLineage,revision,committed.digest).revision);
            assertEquals(old.revision,store.readRevision(targetLineage,oldRevision,old.digest).revision);
            assertEquals(StreamingGroupStore.Phase.COMMITTED,store.recover(committed.transactionID));

            StreamingGroupStore.Store reopened=new StreamingGroupStore.Store(root,ops,projection,
                    NativeManualUpdatePlan.durableVerifier(),phase->{});
            assertEquals("cold read re-runs the concrete media gate",revision,reopened.read(targetLineage).revision);
            NativeManualUpdatePlan noResourcePlan=NativeManualUpdatePlan.createManualUpdate(
                    new NativeManualUpdatePlan.SelectedTarget(targetId,targetLineage,revision,committed.digest),
                    new NativeManualUpdatePlan.Provenance(NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID,
                            UUID.randomUUID().toString(),SOURCE_NOTE,UUID.randomUUID().toString(),null),
                    body(SOURCE_NOTE,0,null),NativeManualUpdatePlan.Presence.ABSENT,null,
                    NativeManualUpdatePlan.Presence.ABSENT,null,Collections.emptyList(),Collections.emptyList(),
                    Collections.singletonMap(SOURCE_NOTE,targetId));
            String laterRevision=UUID.randomUUID().toString();
            StreamingGroupStore.Snapshot later=reopened.commitManualUpdateV2(
                    noResourcePlan.envelope(laterRevision,null,null,Collections.emptyList(),Collections.emptyList()),
                    (current,incoming)->current!=null&&current.digest.equals(committed.digest),noResourcePlan);
            StreamingGroupStore.Snapshot mediaRestored=reopened.restore(targetLineage,revision,committed.digest,
                    later.revision,later.digest,(current,incoming)->current!=null&&current.digest.equals(later.digest));
            assertNotEquals("history restore publishes a fresh revision after revalidating every old media object",revision,mediaRestored.revision);
            assertEquals("old selected media revision remains immutable",revision,reopened.readRevision(targetLineage,revision,committed.digest).revision);
            assertEquals(videoSha,reopened.read(targetLineage).memberSha256(
                    "video/"+videoMaterial.destinationId()+"/content.bin"));

            // A hash-correct but malformed PDF is rejected by the real renderer before preview/publication.
            StreamingGroupStore.Snapshot beforeBad=reopened.read(targetLineage);
            byte[] malformedPdf="not a PDF".getBytes(StandardCharsets.US_ASCII);
            Path malformedPath=write(owned.resolve("malformed.pdf"),malformedPdf);
            NativeManualUpdatePlan badPlan=plan(beforeBad,targetId,targetLineage,sourceLineage,
                    sourceRevision,body(SOURCE_NOTE,1,png),malformedPdf,cover,videoMaterial,
                    Arrays.asList(vaultMaterial,iosVaultMaterial),mapping);
            StreamingGroupStore.Envelope bad=badPlan.envelope(UUID.randomUUID().toString(),input(malformedPath,malformedPdf),
                    coverInput,Collections.singletonList(badPlan.videoMemberInput(videoId,videoInput)),
                    Arrays.asList(badPlan.vaultMemberInput(vaultId,vaultInput),
                            badPlan.vaultMemberInput(iosVaultId,input(iosVaultPath,vaultSourceBytes))));
            AtomicInteger rejectedPreview=new AtomicInteger();
            try {
                reopened.commitManualUpdateV2(bad,(current,incoming)->{rejectedPreview.incrementAndGet();return true;},badPlan);
                fail("malformed PDF reached visible preview/publication");
            } catch(IOException expected) {
                assertTrue("failure should come from PDF parser/renderer: "+expected,
                        chainContains(expected,"PDF_INVALID")||chainContains(expected,"PDF_PAGE_COUNT_MISMATCH")||chainContains(expected,"PDF_INVALID_OR_UNSUPPORTED"));
            }
            assertEquals(0,rejectedPreview.get());
            assertEquals(beforeBad.digest,reopened.read(targetLineage).digest);

            // A decodable JPEG renamed as cover.png is still invalid: the group
            // contract requires a real PNG, not merely any BitmapFactory image.
            byte[] jpeg=jpeg();Path wrongTypeCoverPath=write(owned.resolve("wrong-type-cover.png"),jpeg);
            NativeManualUpdatePlan wrongTypePlan=plan(beforeBad,targetId,targetLineage,sourceLineage,
                    sourceRevision,body(SOURCE_NOTE,1,png),pdf,jpeg,videoMaterial,
                    Arrays.asList(vaultMaterial,iosVaultMaterial),mapping);
            StreamingGroupStore.ContentInput wrongTypeCover=input(wrongTypeCoverPath,jpeg);
            StreamingGroupStore.Envelope wrongTypeEnvelope=wrongTypePlan.envelope(UUID.randomUUID().toString(),pdfInput,
                    wrongTypeCover,Collections.singletonList(wrongTypePlan.videoMemberInput(videoId,videoInput)),
                    Arrays.asList(wrongTypePlan.vaultMemberInput(vaultId,vaultInput),
                            wrongTypePlan.vaultMemberInput(iosVaultId,input(iosVaultPath,vaultSourceBytes))));
            AtomicInteger wrongTypePreview=new AtomicInteger();
            try {
                reopened.commitManualUpdateV2(wrongTypeEnvelope,(current,incoming)->{wrongTypePreview.incrementAndGet();return true;},wrongTypePlan);
                fail("JPEG cover reached preview as a PNG");
            } catch(IOException expected) {
                assertTrue("wrong PNG type must fail signature validation: "+expected,
                        chainContains(expected,"COVER_PNG_SIGNATURE_INVALID"));
            }
            assertEquals(0,wrongTypePreview.get());
            assertEquals("invalid cover leaves the full previous group visible",beforeBad.digest,reopened.read(targetLineage).digest);
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(owned)){
                for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p);
            }
            assertFalse("only the UUID-owned fixture root is removed",Files.exists(owned));
        }
    }

    @Test public void incomingObjectMutationRejectsOnlyTheIsolatedUpdateAndKeepsOldRevisionVisible() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Path owned=TrustedFilesRoot.fromApplicationContext(context).path().resolve("manual-mutation-negative-"+UUID.randomUUID());
        Files.createDirectory(owned);
        try {
            Path root=owned.resolve("negative-store");
            AndroidIdentityOps ops=new AndroidIdentityOps(TrustedFilesRoot.fromApplicationContext(context),root);
            StreamingGroupStore.ProjectionVerifier projection=new StreamingGroupStore.ProjectionVerifier(){
                @Override public void verifyBody(byte[] bytes)throws IOException{if(bytes==null||bytes.length==0)throw new IOException("BODY_EMPTY");}
                @Override public void verifyMetadata(String role,String id,byte[] bytes)throws IOException{if(role==null||id==null||bytes==null)throw new IOException("METADATA_INVALID");}
            };
            StreamingGroupStore.Store store=new StreamingGroupStore.Store(root,ops,projection,phase->{});
            String targetId="note-"+UUID.randomUUID().toString().replace("-",""),lineage=UUID.randomUUID().toString();
            StreamingGroupStore.ImportIdentity identity=new StreamingGroupStore.ImportIdentity(
                    lineage,"original-source-note",targetId,lineage,null,null);
            String oldRevision=UUID.randomUUID().toString();byte[] oldBody="old-visible-body".getBytes(StandardCharsets.UTF_8);
            StreamingGroupStore.Snapshot old=store.commit(new StreamingGroupStore.Envelope(identity,oldRevision,null,null,null,
                    StreamingGroupStore.Action.FIRST_IMPORT,oldBody,null,null,Collections.emptyList(),Collections.emptyList()),
                    (current,incoming)->current==null);

            String incomingRevision=UUID.randomUUID().toString();byte[] newBody="candidate-body".getBytes(StandardCharsets.UTF_8);
            StreamingGroupStore.Envelope update=new StreamingGroupStore.Envelope(identity,incomingRevision,oldRevision,
                    oldRevision,old.digest,StreamingGroupStore.Action.UPDATE,newBody,null,null,
                    Collections.emptyList(),Collections.emptyList());
            AtomicInteger previews=new AtomicInteger();
            try {
                store.commit(update,(current,incoming)->{
                    assertEquals(old.digest,current.digest);
                    previews.incrementAndGet();
                    String bodyHash=incoming.memberSha256("body.bin");
                    Path stagedObject=root.resolve("objects/sha256").resolve(bodyHash);
                    mutateAndRestoreSameBytes(stagedObject);
                    return true;
                });
                fail("changed immutable candidate object must not publish");
            } catch(IOException expected) {
                assertTrue("refusal must identify immutable object/path state: "+expected,
                        chainContains(expected,"OBJECT_NOT_IMMUTABLE")||chainContains(expected,"IDENTITY_CHANGED")||
                        chainContains(expected,"IMMUTABLE")||chainContains(expected,"OBJECT_CHANGED"));
            }
            assertEquals("the mutation was exercised only after preview",1,previews.get());
            StreamingGroupStore.Snapshot stillVisible=store.read(lineage);
            assertEquals(old.revision,stillVisible.revision);
            assertEquals(old.digest,stillVisible.digest);
            assertArrayEquals(oldBody,stillVisible.readSmall("body.bin",1024));
            assertEquals(old.digest,store.readRevision(lineage,oldRevision,old.digest).digest);
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(owned)){
                for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p);
            }
            assertFalse("only the UUID-owned negative fixture is removed",Files.exists(owned));
        }
    }

    private static NativeManualUpdatePlan plan(StreamingGroupStore.Snapshot base,String targetId,String targetLineage,
            String sourceLineage,String sourceRevision,byte[] body,byte[] pdf,byte[] cover,
            NativeManualUpdatePlan.Material video,List<NativeManualUpdatePlan.Material> vault,Map<String,String> mapping)throws Exception {
        return NativeManualUpdatePlan.createManualUpdate(new NativeManualUpdatePlan.SelectedTarget(
                targetId,targetLineage,base.revision,base.digest),new NativeManualUpdatePlan.Provenance(
                NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID,sourceLineage,SOURCE_NOTE,sourceRevision,null),body,
                NativeManualUpdatePlan.Presence.PRESENT,new NativeManualUpdatePlan.Facts(pdf.length,sha(pdf)),
                NativeManualUpdatePlan.Presence.PRESENT,new NativeManualUpdatePlan.Facts(cover.length,sha(cover)),
                Collections.singletonList(video),vault,mapping);
    }
    private static StreamingGroupStore.ContentInput input(Path path,byte[] bytes){return new StreamingGroupStore.ContentInput(path,bytes.length,sha(bytes),null);}
    private static void exerciseVerifiedLease(StreamingGroupStore.VerifiedGroup group,String member,byte[] expected)throws Exception{
        // dup() shares the open-file-description offset with the descriptor
        // already read to EOF during lease hashing. Each decoder handoff must
        // rewind, and simultaneous leases must keep separate open descriptions.
        StreamingGroupStore.VerifiedContentLease first=group.openVerifiedContentLease(member);
        StreamingGroupStore.VerifiedContentLease second=null;
        try{
            // Keep the first open under cleanup protection if acquiring the
            // second independent lease fails.
            second=group.openVerifiedContentLease(member);
            try(android.os.ParcelFileDescriptor firstPfd=android.os.ParcelFileDescriptor.dup(first.descriptor());
                android.os.ParcelFileDescriptor secondPfd=android.os.ParcelFileDescriptor.dup(second.descriptor());
                java.io.InputStream firstIn=new android.os.ParcelFileDescriptor.AutoCloseInputStream(firstPfd);
                java.io.InputStream secondIn=new android.os.ParcelFileDescriptor.AutoCloseInputStream(secondPfd)){
                assertEquals(expected[0]&255,firstIn.read());assertEquals(expected[0]&255,secondIn.read());
            }
            first.verifyUnchanged();first.verifyUnchanged();
            // Closing one lease must release only its own identity-keyed ancestor
            // witness; the second lease was opened on the same immutable object.
            first.close();
            second.verifyUnchanged();second.verifyUnchanged();
            try(android.os.ParcelFileDescriptor pfd=android.os.ParcelFileDescriptor.dup(second.descriptor());
                java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)){
                assertEquals("remaining concurrent lease still hands off byte zero",expected[0]&255,in.read());
            }
        }finally{
            IOException closeFailure=null;
            try{first.close();}catch(IOException e){closeFailure=e;}
            if(second!=null)try{second.close();}catch(IOException e){if(closeFailure==null)closeFailure=e;else closeFailure.addSuppressed(e);}
            if(closeFailure!=null)throw closeFailure;
        }
    }
    private static void mutateAndRestoreSameBytes(Path path)throws Exception{
        android.system.StructStat stat=android.system.Os.stat(path.toString());
        byte[] original=Files.readAllBytes(path);
        if(original.length==0)throw new IOException("MUTATION_FIXTURE_EMPTY");
        int originalMode=stat.st_mode&07777;
        try{
            android.system.Os.chmod(path.toString(),originalMode|0200);
            try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(path.toFile(),"rw")){
                file.seek(0);file.write(original[0]^1);file.getFD().sync();
                file.seek(0);file.write(original[0]);file.getFD().sync();
            }
        } finally {
            android.system.Os.chmod(path.toString(),originalMode|0200);
            try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(path.toFile(),"rw")){
                file.seek(0);file.write(original);file.setLength(original.length);file.getFD().sync();
            } finally {
                android.system.Os.chmod(path.toString(),originalMode);
            }
        }
        assertArrayEquals("the negative case restores exact object bytes",original,Files.readAllBytes(path));
        android.system.StructStat after=android.system.Os.stat(path.toString());
        assertTrue("same-byte restoration still changes the complete immutable file identity",
                stat.st_ctim.tv_sec!=after.st_ctim.tv_sec||stat.st_ctim.tv_nsec!=after.st_ctim.tv_nsec);
    }
    private static Path write(Path path,byte[] bytes)throws IOException{Files.write(path,bytes);return path;}
    private static byte[] asset(Context c,String path)throws IOException{try(java.io.InputStream in=c.getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
    private static byte[] png()throws IOException{Bitmap b=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);try{b.eraseColor(Color.CYAN);Canvas c=new Canvas(b);Paint p=new Paint();p.setColor(Color.MAGENTA);c.drawRect(1,0,2,2,p);ByteArrayOutputStream o=new ByteArrayOutputStream();if(!b.compress(Bitmap.CompressFormat.PNG,100,o))throw new IOException("PNG_ENCODE");return o.toByteArray();}finally{b.recycle();}}
    private static byte[] jpeg()throws IOException{Bitmap b=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);try{b.eraseColor(Color.CYAN);ByteArrayOutputStream o=new ByteArrayOutputStream();if(!b.compress(Bitmap.CompressFormat.JPEG,90,o))throw new IOException("JPEG_ENCODE");return o.toByteArray();}finally{b.recycle();}}
    private static byte[] pdf()throws IOException{PdfDocument d=new PdfDocument();try{PdfDocument.Page p=d.startPage(new PdfDocument.PageInfo.Builder(180,240,1).create());p.getCanvas().drawColor(Color.WHITE);p.getCanvas().drawRect(20,20,120,120,new Paint());d.finishPage(p);ByteArrayOutputStream o=new ByteArrayOutputStream();d.writeTo(o);return o.toByteArray();}finally{d.close();}}
    private static byte[] body(String id,int pdfPages,byte[] image)throws Exception{String imageValue="null";if(image!=null){JSONObject j=new JSONObject().put("id","synthetic-image").put("png",Base64.encodeToString(image,Base64.NO_WRAP)).put("page",0).put("x",0).put("y",0).put("width",20).put("height",20);imageValue=j.toString();}String json="{\"schemaVersion\":8,\"id\":\""+id+"\",\"title\":\"synthetic\",\"updatedAt\":16,\"pageWidth\":768.0,\"pageHeight\":1086.0,\"canvasWidth\":768.0,\"canvasHeight\":1086.0,\"pageGap\":24.0,\"pageCount\":1,\"pdfPageCount\":"+pdfPages+",\"pageTopologyRevision\":0,\"viewportZoom\":1.0,\"viewportCenterX\":384.0,\"viewportCenterY\":543.0,\"strokes\":[],\"textFlows\":[],\"images\":"+(image==null?"[]":"["+imageValue+"]")+",\"pageStyle\":{\"paper\":\"ruled\",\"ratio\":\"screen\",\"landscape\":false},\"textBoxes\":[]}";return json.getBytes(StandardCharsets.UTF_8);}
    private static String videoMetadata(String id,String note,long size,String hash)throws Exception{return new JSONObject().put("id",id).put("noteId",note).put("sourceRevision",1).put("sourceBundleSha256",repeat("11",32)).put("taskPayloadSha256",JSONObject.NULL).put("connectionRevision",1).put("kind","HERMES").put("transport","local").put("certSha256",JSONObject.NULL).put("taskId",UUID.randomUUID().toString()).put("remoteTaskId","remote-fixture").put("connectionId",UUID.randomUUID().toString()).put("bridgeId",JSONObject.NULL).put("instanceId",JSONObject.NULL).put("artifactId","artifact-fixture").put("name","roundtrip.mp4").put("mediaType","video/mp4").put("sizeBytes",size).put("sha256",hash).put("storedName","video-"+id+".mp4").put("createdAt",16).put("originKind","computer_task").put("sourceState","linked_note").put("sourceNoteId",note).put("sourceRevisionPrecisionMs",1).put("digestKind","source_and_task_payload").put("offlineState","verified_local_copy").toString();}
    private static String androidVault(String note,String title){return "---\ntitle: "+title+"\nnote-id: "+note+"\npages: 1\ndigitized: 100\ndigitized-epoch: 100\nsource-modified: 90\n---\n# "+title+"\nsynthetic content\n";}
    private static String iosVaultMetadata(String id,String sourceNote,String title,String markdown)throws Exception{return new JSONObject()
            .put("id",id).put("title",title).put("markdown",markdown).put("sourceUpdatedAt",1234.5).put("createdAt",16.0)
            .put("archiveOrigin","synthetic").put("archiveSourceNoteID",sourceNote).put("archiveLinkedNoteID",sourceNote)
            .put("archiveSourceState","linked_note").put("restoreTransactionID",JSONObject.NULL).put("restoreGroupID",JSONObject.NULL).toString();}
    private static String repeat(String s,int n){StringBuilder out=new StringBuilder();for(int i=0;i<n;i++)out.append(s);return out.toString();}
    private static String sha(byte[] b){try{byte[] h=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder out=new StringBuilder();for(byte x:h)out.append(String.format(Locale.ROOT,"%02x",x&255));return out.toString();}catch(Exception e){throw new AssertionError(e);}}
    private static boolean chainContains(Throwable e,String text){for(Throwable x=e;x!=null;x=x.getCause())if(String.valueOf(x.getMessage()).contains(text))return true;return false;}
}
