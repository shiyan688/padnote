package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.padnote.android.streaming.StreamingGroupStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic-only Activity coverage; no live Agent, endpoint, credential or task output is used. */
@RunWith(AndroidJUnit4.class)
public final class AgentImageToPaperInstrumentedTest {
    @Test public void cancelPreviewLeavesPaperUntouchedThenConfirmedImageReopensOnce() throws Exception {
        try(Fixture fixture=Fixture.create();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            GroupProof group=GroupProof.seed(fixture);
            openTargetNote(fixture,scenario);
            scenario.onActivity(a->a.showCachedAgentImageTaskForTest(fixture.task.clientTaskId,
                    fixture.tasks,fixture.connection,fixture.source()));
            openPlacementPreview(fixture,scenario);
            assertInitialImageFit(scenario);
            onView(withText(R.string.core_cancel)).perform(click());
            await(()->scenarioIdle(scenario)&&NoteStore.load(fixture.context,fixture.note.id)
                    .getInt("schemaVersion")==10,"cancel did not release the synthetic placement transaction");
            assertEquals("cancel must not add the image",0,NoteStore.load(fixture.context,fixture.note.id)
                    .getJSONArray("images").length());

            scenario.onActivity(a->a.showCachedAgentImageTaskForTest(fixture.task.clientTaskId,
                    fixture.tasks,fixture.connection,fixture.source()));
            openPlacementPreview(fixture,scenario);
            onView(withText(R.string.agent_image_confirm)).check(matches(isEnabled())).perform(click());
            await(()->{
                JSONObject note=NoteStore.load(fixture.context,fixture.note.id);
                return scenarioIdle(scenario)&&note.optInt("schemaVersion")==11
                        &&countApplication(note,fixture.applicationId)==1;
            },"confirmed synthetic image was not durably stored");
            JSONObject saved=NoteStore.load(fixture.context,fixture.note.id);
            JSONObject image=findApplication(saved,fixture.applicationId);
            assertNotNull(image);assertTrue(image.getString("id").equals("agent-image-"+fixture.applicationId));
            assertEquals(fixture.sha,image.getJSONObject("agentImage").getString("artifactSha256"));
            NoteStore.validateDocument(saved);
            group.assertPreserved();
            JSONObject malformedNumeric=new JSONObject(saved.toString());
            JSONObject malformedReceipt=findApplication(malformedNumeric,fixture.applicationId).getJSONObject("agentImage");
            malformedReceipt.put("connectionId",12345);
            assertThrows("Android org.json must not coerce numeric receipt strings",Exception.class,
                    ()->NoteStore.validateDocument(malformedNumeric));
            JSONObject malformedBoolean=new JSONObject(saved.toString());
            malformedReceipt=findApplication(malformedBoolean,fixture.applicationId).getJSONObject("agentImage");
            malformedReceipt.put("connectionId",true);
            assertThrows("Android org.json must not coerce Boolean receipt strings",Exception.class,
                    ()->NoteStore.validateDocument(malformedBoolean));

            onView(withContentDescription(R.string.core_back_shelf)).perform(click());
            clickNoteWhenVisible(fixture.context,fixture.note.title);
            await(()->{
                final boolean[] loaded={false};scenario.onActivity(a->{
                    try{loaded[0]=a.canvasForTest().toJsonDocument(fixture.note.id,fixture.note.title)
                            .getInt("schemaVersion")==11;}catch(Exception error){throw new AssertionError("reopened document read failed",error);}
                });return loaded[0];
            },"reopened paper did not load schema 11");
            scenario.onActivity(a->a.showCachedAgentImageTaskForTest(fixture.task.clientTaskId,
                    fixture.tasks,fixture.connection,fixture.source()));
            onView(withText(fixture.context.getString(R.string.agent_image_place_on_paper,"graph.png"))).perform(scrollTo(),click());
            await(()->scenarioIdle(scenario),"idempotency lookup did not finish");
            onView(withText(R.string.agent_image_choose_target)).check(doesNotExist());
            assertEquals("duplicate task action must not add a second image",1,
                    countApplication(NoteStore.load(fixture.context,fixture.note.id),fixture.applicationId));
            captureProductionPaperPdf(scenario,fixture.context,fixture.note.id,fixture.note.title);
        }
    }

    @Test public void changedConnectionCannotCommitReadyPreview() throws Exception {
        try(Fixture fixture=Fixture.create();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            openTargetNote(fixture,scenario);
            scenario.onActivity(a->a.showCachedAgentImageTaskForTest(fixture.task.clientTaskId,fixture.tasks,fixture.connection,fixture.source()));
            openPlacementPreview(fixture,scenario);
            AgentConnectionStore.Config revoked=new AgentConnectionStore.Config(fixture.connection.id,fixture.connection.name,
                    fixture.connection.kind,fixture.connection.endpoint,fixture.connection.token,fixture.connection.credentialRef,
                    fixture.connection.transport,fixture.connection.bridgeId,fixture.connection.instanceId,fixture.connection.certSha256,
                    fixture.connection.revision+1,fixture.connection.verifiedAt,fixture.connection.capabilities);
            scenario.onActivity(a->a.replaceAgentImageConnectionForTest(revoked));
            onView(withText(R.string.agent_image_confirm)).perform(click());
            JSONObject unchanged=NoteStore.load(fixture.context,fixture.note.id);
            assertEquals(10,unchanged.getInt("schemaVersion"));assertEquals(0,unchanged.getJSONArray("images").length());
            onView(withText(R.string.core_cancel)).perform(click());
            await(()->scenarioIdle(scenario),"stale connection preview did not close cleanly");
            assertEquals(10,NoteStore.load(fixture.context,fixture.note.id).getInt("schemaVersion"));
        }
    }

    @Test public void manualImageCallbackIdentityFenceRejectsLateWrongNoteAndDirtyRevision() {
        assertTrue(MainActivity.imageImportTargetMatches("note-a","note-a",4,4,7,7,9,9,true,true,false));
        assertFalse(MainActivity.imageImportTargetMatches("note-a","note-b",4,4,7,7,9,9,true,true,false));
        assertFalse(MainActivity.imageImportTargetMatches("note-a","note-a",4,5,7,7,9,9,true,true,false));
        assertFalse(MainActivity.imageImportTargetMatches("note-a","note-a",4,4,7,8,9,9,true,true,false));
        assertFalse(MainActivity.imageImportTargetMatches("note-a","note-a",4,4,7,7,9,10,true,true,false));
        assertFalse(MainActivity.imageImportTargetMatches("note-a","note-a",4,4,7,7,9,9,true,true,true));
        assertEquals(12d,MainActivity.clampAgentImageOrigin(0d,100d,612d,12d),0.0001d);
        assertEquals(500d,MainActivity.clampAgentImageOrigin(612d,100d,612d,12d),0.0001d);
    }

    @Test public void taskStatusAndFullArtifactDescriptorArePartOfPlacementFence() throws Exception {
        try(Fixture fixture=Fixture.create()){
            AgentTaskStore.Task t=fixture.task;
            AgentTaskStore.Task running=copyTask(t,AgentTaskStore.Status.RUNNING,t.artifacts);
            assertFalse(MainActivity.sameAgentImageTask(t,running,fixture.artifact));
            AgentTaskStore.Artifact changed=new AgentTaskStore.Artifact(fixture.artifact.id,
                    fixture.artifact.name+"-changed",fixture.artifact.mediaType,fixture.artifact.sizeBytes,fixture.artifact.sha256);
            AgentTaskStore.Task changedDescriptor=copyTask(t,AgentTaskStore.Status.COMPLETED,Collections.singletonList(changed));
            assertFalse(MainActivity.sameAgentImageTask(t,changedDescriptor,fixture.artifact));
            assertTrue(MainActivity.sameAgentImageTask(t,t,fixture.artifact));
        }
    }

    private static AgentTaskStore.Task copyTask(AgentTaskStore.Task t,AgentTaskStore.Status status,
            List<AgentTaskStore.Artifact> artifacts){
        return new AgentTaskStore.Task(t.clientTaskId,t.connectionId,t.connectionRevision,t.origin,t.kind,t.transport,
                t.credentialRef,t.bridgeId,t.instanceId,t.title,t.connectionName,t.noteId,t.noteRevision,
                t.submissionJson,t.submissionSha256,t.remoteTaskId,status,t.output,t.error,t.approvalId,
                t.approvalTitle,t.approvalDescription,artifacts,t.createdAt,t.updatedAt);
    }

    @Test public void cancelledImageStreamClosesAndDeletesOnlyItsPartialFile() throws Exception {
        try(Fixture fixture=Fixture.create()){
            java.util.concurrent.CountDownLatch reading=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch closed=new java.util.concurrent.CountDownLatch(1);
            AgentArtifactDownloader.Source source=(url,token,pin)->new AgentArtifactDownloader.OpenResponse(200,url,url,
                    fixture.png.length,new java.io.InputStream(){
                @Override public int read()throws java.io.IOException{reading.countDown();try{closed.await();}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}throw new java.io.IOException("closed");}
                @Override public int read(byte[] b,int off,int len)throws java.io.IOException{return read();}
                @Override public void close(){closed.countDown();}
            },null);
            File stage=new File(fixture.context.getCacheDir(),"C39QA-cancel-"+UUID.randomUUID());assertTrue(stage.mkdir());
            AgentArtifactDownloader.Cancellation cancellation=new AgentArtifactDownloader.Cancellation();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker=new Thread(()->{try{new AgentArtifactDownloader(source).downloadImage(fixture.task,fixture.connection,
                    fixture.artifact,stage,cancellation);failure.set(new AssertionError("cancelled download returned a file"));}
                catch(Throwable expected){failure.set(expected);}},"c39-synthetic-image-cancel");worker.start();
            assertTrue("download reached its synthetic blocking stream",reading.await(5,TimeUnit.SECONDS));
            cancellation.cancel();worker.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse("cancelled downloader terminated",worker.isAlive());assertNotNull(failure.get());
            File[] leftovers=stage.listFiles();assertNotNull(leftovers);assertEquals("owned partial file is removed",0,leftovers.length);
            assertTrue(stage.delete());
        }
    }

    @Test public void boundedNormalizerOrientsJpegAndRejectsMalformedPngMimeAndBounds() throws Exception {
        byte[] jpeg=jpegWithOrientation(3,2,ExifInterface.ORIENTATION_ROTATE_90);
        try(Fixture fixture=Fixture.createFor("image/jpeg",jpeg)){
            File input=writeArtifact(fixture,jpeg);
            try{
                NoteImage image=NoteImage.fromAgentArtifact(input,fixture.task,fixture.connection,
                        fixture.artifact,fixture.note.id,fixture.note.updatedAt);
                assertEquals("orientation is applied before storage",2,image.bitmap.getWidth());
                assertEquals("orientation is applied before storage",3,image.bitmap.getHeight());
                assertEquals("image/png",pngMime(image.png));
                assertEquals("image/jpeg",image.agentImage.mediaType);
                image.bitmap.recycle();
            }finally{input.delete();}
        }
        byte[] png=testPng();
        try(Fixture fixture=Fixture.createFor("image/png",Arrays.copyOf(png,png.length-5))){
            assertNormalizerRejects(fixture,Arrays.copyOf(png,png.length-5),"truncated PNG");
        }
        byte[] animated=withApngControlChunk(png);
        try(Fixture fixture=Fixture.createFor("image/png",animated)){
            assertNormalizerRejects(fixture,animated,"APNG control chunk");
        }
        try(Fixture fixture=Fixture.createFor("image/jpeg",png)){
            assertNormalizerRejects(fixture,png,"declared JPEG with PNG bytes");
        }
        byte[] oversized=jpegWithOrientation(16385,1,ExifInterface.ORIENTATION_NORMAL);
        try(Fixture fixture=Fixture.createFor("image/jpeg",oversized)){
            assertNormalizerRejects(fixture,oversized,"source width above 16384 pixels");
        }
    }

    private static void assertNormalizerRejects(Fixture fixture,byte[] bytes,String label)throws Exception{
        File input=writeArtifact(fixture,bytes);
        try{NoteImage.fromAgentArtifact(input,fixture.task,fixture.connection,fixture.artifact,
                fixture.note.id,fixture.note.updatedAt);fail("must reject "+label);}
        catch(IllegalArgumentException|java.io.IOException expected){ }
        finally{input.delete();}
    }

    private static File writeArtifact(Fixture fixture,byte[] bytes)throws Exception{
        File file=File.createTempFile("c39-agent-image-", ".source",fixture.context.getCacheDir());
        try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.getFD().sync();}
        return file;
    }

    private static byte[] jpegWithOrientation(int width,int height,int orientation)throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.MAGENTA);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,bytes));bitmap.recycle();
        File file=File.createTempFile("c39-exif-", ".jpg",InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes.toByteArray());}
        ExifInterface exif=new ExifInterface(file);exif.setAttribute(ExifInterface.TAG_ORIENTATION,Integer.toString(orientation));exif.saveAttributes();
        byte[] result;try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream copy=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>=0)if(n>0)copy.write(buffer,0,n);result=copy.toByteArray();}
        assertTrue(file.delete());return result;
    }

    private static byte[] testPng()throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.rgb(232,30,50));
        ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));bitmap.recycle();return out.toByteArray();
    }

    private static byte[] withApngControlChunk(byte[] png)throws Exception{
        byte[] type="acTL".getBytes(StandardCharsets.US_ASCII),data={0,0,0,1,0,0,0,0};
        ByteArrayOutputStream chunk=new ByteArrayOutputStream();java.io.DataOutputStream d=new java.io.DataOutputStream(chunk);
        d.writeInt(data.length);d.write(type);d.write(data);java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(type);crc.update(data);d.writeInt((int)crc.getValue());
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(png,0,33);out.write(chunk.toByteArray());out.write(png,33,png.length-33);return out.toByteArray();
    }

    private static String pngMime(String base64){
        byte[] bytes=android.util.Base64.decode(base64,android.util.Base64.DEFAULT);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);return o.outMimeType;
    }

    /** Uses the same immutable paper snapshot and PDF exporter as the app; files remain pullable by the QA runner. */
    private static void captureProductionPaperPdf(ActivityScenario<MainActivity> scenario,Context context,
            String noteId,String title)throws Exception{
        JSONObject document=NoteStore.load(context,noteId);AtomicReference<NoteCanvasView.PdfExportSnapshot> snapshotRef=new AtomicReference<>();
        AtomicReference<FrameLayout> hostRef=new AtomicReference<>();
        scenario.onActivity(activity->{
            FrameLayout root=new FrameLayout(activity);NoteCanvasView canvas=new NoteCanvasView(activity);
            FrameLayout host=new FrameLayout(activity);host.setVisibility(View.VISIBLE);host.setAlpha(0.01f);host.setClipChildren(false);host.setClipToPadding(false);
            root.addView(canvas,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(host,new FrameLayout.LayoutParams(1,1));activity.setContentView(root);
            int width=Math.max(1,(int)(768*activity.getResources().getDisplayMetrics().density));
            int height=Math.max(1,(int)(1024*activity.getResources().getDisplayMetrics().density));
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            root.layout(0,0,width,height);
            try{canvas.loadJsonDocument(document);snapshotRef.set(canvas.createPdfExportSnapshot());}
            catch(Exception error){throw new AssertionError("production paper snapshot preparation failed",error);}
            hostRef.set(host);
        });
        NoteCanvasView.PdfExportSnapshot snapshot=snapshotRef.get();assertNotNull(snapshot);
        try{
            File qaRoot=new File(context.getCacheDir(),"C39QA-agent-image");if(!qaRoot.exists())assertTrue(qaRoot.mkdir());
            File run=new File(qaRoot,"paper-"+UUID.randomUUID());assertTrue("QA output directory is exclusive",run.mkdir());
            File pdf=new File(run,"confirmed-note.pdf");ByteArrayOutputStream output=new ByteArrayOutputStream();
            PdfNoteIO.exportFlattenedPdf(snapshot,hostRef.get(),output,new Handler(Looper.getMainLooper()),null);
            assertTrue(output.size()>100);assertEquals("%PDF",new String(output.toByteArray(),0,4,StandardCharsets.US_ASCII));
            try(FileOutputStream out=new FileOutputStream(pdf)){out.write(output.toByteArray());out.getFD().sync();}
            try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY);
                PdfRenderer renderer=new PdfRenderer(descriptor)){
                assertEquals(snapshot.getPageCount(),renderer.getPageCount());
                try(PdfRenderer.Page page=renderer.openPage(renderer.getPageCount()-1)){
                    Bitmap bitmap=Bitmap.createBitmap(page.getWidth(),page.getHeight(),Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    int red=0;for(int y=bitmap.getHeight()/3;y<bitmap.getHeight()*2/3;y+=4)
                        for(int x=bitmap.getWidth()/3;x<bitmap.getWidth()*2/3;x+=4){int c=bitmap.getPixel(x,y);
                            if(Color.red(c)>150&&Color.red(c)>Color.green(c)*1.35&&Color.red(c)>Color.blue(c)*1.2)red++;}
                    assertTrue("normalized synthetic image is visible in the production PDF page",red>0);
                    File png=new File(run,"confirmed-last-page.png");try(FileOutputStream out=new FileOutputStream(png)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.getFD().sync();}finally{bitmap.recycle();}
                }
            }
            JSONObject inventory=new JSONObject().put("schema","padnote.c39-agent-image-paper-evidence.v1")
                    .put("syntheticOnly",true).put("noteId",noteId).put("pageCount",snapshot.getPageCount())
                    .put("pdf",fileRecord(pdf)).put("lastPage",fileRecord(new File(run,"confirmed-last-page.png")));
            try(FileOutputStream out=new FileOutputStream(new File(run,"inventory.json"))){out.write(inventory.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        }finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(snapshot::close);}
    }

    private static JSONObject fileRecord(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;
        try(FileInputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))>=0)if(n>0){size+=n;digest.update(b,0,n);}}
        StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return new JSONObject().put("name",file.getName()).put("bytes",size).put("sha256",sha.toString());
    }

    @Test public void newNoteDraftIsNotWrittenUntilPaperPreviewConfirmation() throws Exception {
        try(Fixture fixture=Fixture.create();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->a.showCachedAgentImageTaskForTest(fixture.task.clientTaskId,
                    fixture.tasks,fixture.connection,fixture.source()));
            openNewNotePlacementPreview(fixture,scenario);
            final String[] draft={""};scenario.onActivity(a->draft[0]=a.agentImageTargetForTest());
            assertFalse(draft[0].isEmpty());
            final boolean[] detached={false};final float[][] draftSize={new float[0]};
            scenario.onActivity(a->{detached[0]=a.agentImageUsesDetachedDraftForTest();
                draftSize[0]=a.agentImageDraftPageSizeForTest();});
            assertTrue("shelf placement must use an unpersisted detached canvas",detached[0]);
            assertArrayEquals("shelf draft uses canonical portrait paper geometry",new float[]{768f,1086f},draftSize[0],0f);
            assertNull("shelf new-note preview must not create a library entry",findEntry(fixture.context,draft[0]));
            onView(withText(R.string.core_cancel)).perform(click());
            await(()->scenarioIdle(scenario),"cancelled shelf new-note preview did not release the draft");
            assertNull("cancelled shelf draft must remain absent",findEntry(fixture.context,draft[0]));

            scenario.onActivity(a->a.showCachedAgentImageTaskForTest(fixture.task.clientTaskId,
                    fixture.tasks,fixture.connection,fixture.source()));
            openNewNotePlacementPreview(fixture,scenario);
            final String[] confirmedDraft={""};scenario.onActivity(a->confirmedDraft[0]=a.agentImageTargetForTest());
            assertFalse(confirmedDraft[0].isEmpty());
            assertNull("new note remains absent before confirm",findEntry(fixture.context,confirmedDraft[0]));
            onView(withText(R.string.agent_image_confirm)).check(matches(isEnabled())).perform(click());
            await(()->findEntry(fixture.context,confirmedDraft[0])!=null&&scenarioIdle(scenario)
                            &&editorReady(scenario,confirmedDraft[0]),
                    "confirmed shelf image draft did not create and open its stable note");
            assertEquals("confirmed draft creates exactly one shelf entry",1,countEntries(fixture.context,confirmedDraft[0]));
            JSONObject created=NoteStore.load(fixture.context,confirmedDraft[0]);
            assertEquals(11,created.getInt("schemaVersion"));
            assertEquals(1,countApplication(created,fixture.applicationId));
            assertEquals(0,NoteStore.load(fixture.context,fixture.note.id).getJSONArray("images").length());
        }
    }

    private static void openPlacementPreview(Fixture f,ActivityScenario<MainActivity> scenario)throws Exception{
        awaitTaskPlacementAction(f,scenario);
        onView(withText(f.context.getString(R.string.agent_image_place_on_paper,"graph.png"))).perform(scrollTo(),click());
        awaitTargetChooser(f,scenario);
        onView(withText(f.context.getString(R.string.agent_image_current_note,f.note.title))).perform(click());
        awaitPlacementPreviewReady();
    }

    private static void openNewNotePlacementPreview(Fixture f,ActivityScenario<MainActivity> scenario)throws Exception{
        awaitTaskPlacementAction(f,scenario);
        onView(withText(f.context.getString(R.string.agent_image_place_on_paper,"graph.png"))).perform(scrollTo(),click());
        awaitTargetChooser(f,scenario);
        onView(withText(R.string.agent_image_new_note)).perform(click());
        awaitPlacementPreviewReady();
    }

    private static void awaitTaskPlacementAction(Fixture f,ActivityScenario<MainActivity> scenario)throws Exception{
        AtomicReference<Throwable> lastActionFailure=new AtomicReference<>();
        await(()->{
            try{onView(withText(f.context.getString(R.string.agent_image_place_on_paper,"graph.png")))
                    .check(matches(withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE)));return true;}
            catch(RuntimeException|AssertionError pending){lastActionFailure.set(pending);return false;}
        },"synthetic task detail did not expose its image placement action",
                ()->{final String[] state={"unavailable"};scenario.onActivity(a->state[0]=a.agentImageLookupDiagnosticsForTest());return state[0];},
                lastActionFailure);
    }

    private static void awaitTargetChooser(Fixture f,ActivityScenario<MainActivity> scenario)throws Exception{
        AtomicReference<Throwable> lastChooserFailure=new AtomicReference<>();
        await(()->{
            try{onView(withText(R.string.agent_image_choose_target)).check(matches(isDisplayed()));return true;}
            catch(RuntimeException|AssertionError pending){lastChooserFailure.set(pending);return false;}
        },"styled Agent image target chooser did not appear after the asynchronous duplicate lookup",
                ()->{final String[] state={"unavailable"};scenario.onActivity(a->state[0]=a.agentImageLookupDiagnosticsForTest());return state[0];},
                lastChooserFailure);
    }

    private static void awaitPlacementPreviewReady()throws Exception{
        await(()->{
            try{onView(withText(R.string.agent_image_preview_title)).check(matches(isDisplayed()));return true;}
            catch(RuntimeException|AssertionError pending){return false;}
        },"real Activity paper preview did not open");
        await(()->{
            try{onView(withText(R.string.agent_image_confirm)).check(matches(isEnabled()));return true;}
            catch(RuntimeException|AssertionError rendering){return false;}
        },"production paper renderer did not prepare the candidate");
    }

    private static void assertInitialImageFit(ActivityScenario<MainActivity> scenario){
        final double[][] observed={new double[0]};
        scenario.onActivity(activity->observed[0]=activity.agentImagePlacementGeometryForTest());
        double[] g=observed[0];
        assertEquals("preview geometry should expose image and page bounds",9,g.length);
        double margin=g[8],pageWidth=g[4],pageHeight=g[5];
        assertTrue(g[2]>0d&&g[3]>0d&&g[6]>0d&&g[7]>0d);
        assertTrue(g[0]>=margin&&g[1]>=margin);
        assertTrue(g[0]+g[2]<=pageWidth-margin+1e-6d);
        assertTrue(g[1]+g[3]<=pageHeight-margin+1e-6d);
        assertTrue(g[2]<=(pageWidth-2d*margin)*AgentImagePlacementGeometry.DEFAULT_PAGE_FRACTION+1e-6d);
        assertTrue(g[3]<=(pageHeight-2d*margin)*AgentImagePlacementGeometry.DEFAULT_PAGE_FRACTION+1e-6d);
        assertEquals(g[6]/g[7],g[2]/g[3],1e-6d);
        assertEquals(pageWidth/2d,g[0]+g[2]/2d,1e-6d);
        assertEquals(pageHeight/2d,g[1]+g[3]/2d,1e-6d);
    }

    private static void openTargetNote(Fixture f,ActivityScenario<MainActivity> scenario)throws Exception{
        clickNoteWhenVisible(f.context,f.note.title);
        await(()->{
            final boolean[] ready={false};scenario.onActivity(a->ready[0]=a.editorReadyForImageTest(f.note.id));return ready[0];
        },"fixture target note did not restore");
    }

    private static boolean scenarioIdle(ActivityScenario<MainActivity> scenario){
        final boolean[] idle={false};scenario.onActivity(a->idle[0]=a.agentImagePlacementIdleForTest());return idle[0];
    }
    private static NoteStore.Entry findEntry(Context context,String id)throws Exception{
        for(NoteStore.Entry entry:NoteStore.list(context))if(id.equals(entry.id))return entry;return null;
    }
    private static int countEntries(Context context,String id)throws Exception{
        int count=0;for(NoteStore.Entry entry:NoteStore.list(context))if(id.equals(entry.id))count++;return count;
    }
    private static boolean editorReady(ActivityScenario<MainActivity> scenario,String noteId){
        final boolean[] ready={false};scenario.onActivity(a->ready[0]=a.editorReadyForImageTest(noteId));return ready[0];
    }
    private static int countApplication(JSONObject note,String app)throws Exception{
        JSONArray images=note.optJSONArray("images");int count=0;if(images!=null)for(int i=0;i<images.length();i++){
            JSONObject r=images.getJSONObject(i).optJSONObject("agentImage");if(r!=null&&app.equals(r.optString("applicationId")))count++;}
        return count;
    }
    private static JSONObject findApplication(JSONObject note,String app)throws Exception{
        JSONArray images=note.optJSONArray("images");if(images!=null)for(int i=0;i<images.length();i++){
            JSONObject image=images.getJSONObject(i),receipt=image.optJSONObject("agentImage");
            if(receipt!=null&&app.equals(receipt.optString("applicationId")))return image;}
        return null;
    }
    private static void clickNoteWhenVisible(Context context,String title)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            try{onView(withText(title)).perform(click());return;}catch(RuntimeException|AssertionError pending){Thread.sleep(50L);}}
        fail("fixture note did not become visible on the bookshelf");
    }
    @FunctionalInterface private interface AwaitCondition{boolean get()throws Exception;}
    @FunctionalInterface private interface DiagnosticSupplier{String get()throws Exception;}
    private static void await(AwaitCondition condition,String failure)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline){InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            try{if(condition.get())return;}catch(Exception error){throw new AssertionError(failure,error);}Thread.sleep(50L);}
        fail(failure);
    }
    private static void await(AwaitCondition condition,String failure,DiagnosticSupplier diagnostics,
            AtomicReference<Throwable> last)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline){InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            try{if(condition.get())return;}catch(Exception error){throw new AssertionError(failure,error);}Thread.sleep(50L);}
        String state;try{state=diagnostics.get();}catch(Exception error){state="diagnostic-read-failed:"+error.getClass().getSimpleName();}
        AssertionError timeout=new AssertionError(failure+"; lookupState="+state);
        Throwable lastFailure=last==null?null:last.get();if(lastFailure!=null)timeout.initCause(lastFailure);
        throw timeout;
    }

    private static final class GroupProof{
        final Fixture fixture;final String materialId;final byte[] markdown;
        // Snapshot is an immutable manifest/path value; it owns no descriptor and has no close API.
        final StreamingGroupStore.Snapshot base;
        private GroupProof(Fixture f,String id,byte[] bytes,StreamingGroupStore.Snapshot snapshot){fixture=f;materialId=id;markdown=bytes;base=snapshot;}
        static GroupProof seed(Fixture fixture)throws Exception{
            if(android.os.Build.VERSION.SDK_INT<27)return new GroupProof(fixture,null,null,null);
            new VaultStore(fixture.context).write(fixture.note.id,"Keep before image insertion",1,123L,
                    Collections.singletonList("synthetic linked material"));
            StreamingGroupStore.Snapshot before=new NoteGroupFacade(fixture.context)
                    .adoptLegacyNote(fixture.note.id,(old,staged)->true);
            List<GroupAuthorityBridge.VaultView> views=GroupAuthorityBridge.vaultViews(before);
            assertEquals(1,views.size());GroupAuthorityBridge.VaultView view=views.get(0);
            return new GroupProof(fixture,view.materialId,view.markdown,before);
        }
        void assertPreserved()throws Exception{
            if(base==null)return;
            StreamingGroupStore.Snapshot after=new NoteGroupFacade(fixture.context).openGroup(fixture.note.id);
            assertNotEquals(base.revision,after.revision);
            assertEquals(1,GroupAuthorityBridge.vaultViews(after).size());
            assertArrayEquals(markdown,GroupAuthorityBridge.vaultView(after,materialId).markdown);
        }
    }

    private static final class Fixture implements AutoCloseable{
        final Context context;final NoteStore.Entry note;final AgentTaskStore tasks;
        final AgentTaskStore.Task task;final AgentTaskStore.Artifact artifact;
        final AgentConnectionStore.Config connection;final byte[] png;final String sha,applicationId;final File directory;
        private Fixture(Context c,NoteStore.Entry n,AgentTaskStore ts,AgentTaskStore.Task t,
                AgentTaskStore.Artifact a,AgentConnectionStore.Config conn,byte[] bytes,String digest,String app,File dir){
            context=c;note=n;tasks=ts;task=t;artifact=a;connection=conn;png=bytes;sha=digest;applicationId=app;directory=dir;
        }
        static Fixture create()throws Exception{
            return createFor("image/png",testPng());
        }
        static Fixture createFor(String mediaType,byte[] png)throws Exception{
            Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
            if((context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)==0)throw new SecurityException("debug build required");
            String suffix=UUID.randomUUID().toString();NoteStore.Entry note=NoteStore.create(context,"Synthetic image paper "+suffix);
            String sha=hex(MessageDigest.getInstance("SHA-256").digest(png));
            AgentTaskStore.Artifact artifact=new AgentTaskStore.Artifact("artifact-"+suffix,"graph."+("image/jpeg".equals(mediaType)?"jpg":"png"),mediaType,png.length,sha);
            AgentConnectionStore.Config connection=new AgentConnectionStore.Config("connection-"+suffix,"Synthetic fixture",
                    AgentConnectionStore.Kind.HERMES,"https://fixture.invalid","synthetic-token","fixture-credential",
                    AgentConnectionStore.Transport.BRIDGE,"fixture-bridge","fixture-instance","",1L,1L,
                    Arrays.asList("artifacts"));
            File directory=new File(context.getCacheDir(),"agent-image-fixture-"+suffix);AgentTaskStore tasks=new AgentTaskStore(directory);
            AgentTaskStore.Task task=tasks.create(connection,"Synthetic image task","Synthetic-only test",note.id,note.updatedAt,null);
            if(!tasks.applySubmission(task.clientTaskId,connection.id,connection.revision,"fixture-remote",AgentTaskStore.Status.RUNNING))
                throw new IllegalStateException("FIXTURE_SUBMISSION_FAILED");
            AgentTaskClient.RemoteStatus completed=new AgentTaskClient.RemoteStatus(AgentTaskStore.Status.COMPLETED,
                    "Synthetic image fixture task","","","","",Collections.singletonList(artifact));
            if(!tasks.applyStatus(task.clientTaskId,connection.id,connection.revision,"fixture-remote",completed))
                throw new IllegalStateException("FIXTURE_COMPLETION_FAILED");
            task=tasks.get(task.clientTaskId);
            String app=AgentImageReceipt.calculateApplicationId(task.clientTaskId,task.connectionId,task.instanceId,
                    "hermes","bridge",artifact.id,artifact.name,artifact.mediaType,artifact.sizeBytes,sha);
            return new Fixture(context,note,tasks,task,artifact,connection,png,sha,app,directory);
        }
        AgentArtifactDownloader.Source source(){return (url,token,pin)->new AgentArtifactDownloader.OpenResponse(200,url,url,png.length,
                new ByteArrayInputStream(png),null);}
        @Override public void close()throws Exception{
            try{
                if(android.os.Build.VERSION.SDK_INT>=27){
                    StreamingGroupStore.Snapshot current=new NoteGroupFacade(context).openGroup(note.id);
                    if(current!=null)NoteStore.delete(context,note.id,current.revision,current.digest);
                    else NoteStore.delete(context,note.id);
                }else NoteStore.delete(context,note.id);
            }finally{deleteTree(directory);}
        }
        private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
        private static void deleteTree(File f){if(f==null||!f.exists())return;File[] children=f.listFiles();if(children!=null)for(File c:children)deleteTree(c);if(!f.delete()&&f.exists())throw new IllegalStateException("FIXTURE_CLEANUP_FAILED");}
    }
}
