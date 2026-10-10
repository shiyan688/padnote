package com.padnote.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.padnote.android.streaming.StreamingGroupStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/** Real bookshelf/editor/save path for an already committed complete group. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class GroupEditorSaveInstrumentedTest {
    @Test public void pausingEditorKeepsConflictedLatestCanvasAsRevisionBoundDraft()
            throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String title="R46b pause recovery "+UUID.randomUUID();
        NoteStore.Entry entry=NoteStore.create(context,title);
        ActivityScenario<MainActivity> scenario=null;
        try {
            NoteGroupFacade facade=new NoteGroupFacade(context);
            StreamingGroupStore.Snapshot base=facade.adoptLegacyNote(entry.id,(old,staged)->true);
            byte[] initial=base.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX);
            int initialStrokes=new JSONObject(new String(initial,StandardCharsets.UTF_8))
                    .getJSONArray("strokes").length();

            scenario=ActivityScenario.launch(MainActivity.class);
            clickNoteWhenVisible(title);
            awaitEditorReady(scenario,entry.id,base.revision,base.digest);

            // A concurrent writer wins while this editor still holds the captured base.
            // The stale editor must keep its new body as a draft, not overwrite the winner.
            JSONObject concurrentBody=new JSONObject(new String(initial,StandardCharsets.UTF_8));
            concurrentBody.put("title","R46b concurrent winner");
            StreamingGroupStore.Snapshot winner=facade.saveEditorRevision(entry.id,
                    base.revision,base.digest,"R46b concurrent winner",
                    NoteJsonCodec.stringify(concurrentBody));
            AtomicReference<JSONObject> captured=new AtomicReference<>();
            scenario.onActivity(activity->{
                dispatchStylusStroke(activity.canvasForTest());
                try { captured.set(activity.canvasForTest().toJsonDocument(entry.id,title)); }
                catch(Exception error) { throw new AssertionError(error); }
            });
            assertNotNull("the live canvas was captured before lifecycle pause",captured.get());
            assertEquals(initialStrokes+1,captured.get().getJSONArray("strokes").length());

            // ActivityScenario delivers onPause/onStop through Android's lifecycle. The
            // save then conflicts with the winner above, so only the durable draft can keep
            // this captured body available for explicit recovery.
            scenario.moveToState(Lifecycle.State.CREATED);
            GroupEditorDraftStore.Draft draft=null;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline){
                draft=GroupEditorDraftStore.read(context,entry.id);
                if(draft!=null)break;
                Thread.sleep(100);
            }
            assertNotNull("onPause must durably retain the full body before stale CAS failure",draft);
            JSONObject retainedBody=NotePrecisionJsonParser.parseObject(draft.bodyJson);
            assertEquals(captured.get().getJSONArray("strokes").toString(),
                    retainedBody.getJSONArray("strokes").toString());
            assertEquals(captured.get().getLong("authorPageEditSerial"),
                    retainedBody.getLong("authorPageEditSerial"));
            assertEquals(captured.get().getInt("pageCount"),retainedBody.getInt("pageCount"));
            assertTrue("recovery remains bound to the exact base opened by this editor",
                    draft.matchesBase(entry.id,base.lineage,base.revision,base.digest));
            StreamingGroupStore.Snapshot stillWinner=facade.openGroup(entry.id);
            assertEquals(winner.revision,stillWinner.revision);
            assertEquals(winner.digest,stillWinner.digest);
            assertEquals("R46b concurrent winner",new JSONObject(new String(
                    stillWinner.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX),
                    StandardCharsets.UTF_8)).getString("title"));
        } finally {
            if(scenario!=null)scenario.close();
        }
    }

    @Test public void editorSavePublishesWholeCasRevisionAndKeepsPreviousRevision() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String noteTitle="R15 group editor "+UUID.randomUUID();
        NoteStore.Entry entry=NoteStore.create(context,noteTitle);
        File sourceVideo=new File(context.getCacheDir(),"r15-video-"+UUID.randomUUID()+".mp4");
        ActivityScenario<MainActivity> scenario=null;
        try {
            JSONObject body=NoteStore.load(context,entry.id);
            body.put("pageCount",2).put("pdfPageCount",1);
            NoteStore.save(context,entry.id,noteTitle,NoteJsonCodec.stringify(body));
            File pdf=NoteStore.pdfFile(context,entry.id);writePdf(pdf);
            Bitmap cover=Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888);cover.eraseColor(Color.CYAN);
            CoverStore.assign(context,entry.id,cover);cover.recycle();
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("video/roundtrip.mp4");
                FileOutputStream out=new FileOutputStream(sourceVideo)) {
                byte[] buffer=new byte[16384];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);
                out.flush();out.getFD().sync();
            }
            byte[] video=Files.readAllBytes(sourceVideo.toPath());String videoSha=sha(video);
            new VideoAttachmentStore(context).attach(entry.id,entry.updatedAt,videoSha,videoSha,
                    "task-"+UUID.randomUUID(),"remote-"+UUID.randomUUID(),"connection-"+UUID.randomUUID(),1,
                    "HERMES","BRIDGE","bridge-"+UUID.randomUUID(),"instance-"+UUID.randomUUID(),
                    sha("r15 synthetic certificate".getBytes(StandardCharsets.UTF_8)),"artifact-"+UUID.randomUUID(),
                    "R15 preserved video","video/mp4",video.length,videoSha,sourceVideo);
            new VaultStore(context).write(entry.id,"R15 linked Vault",2,entry.updatedAt,
                    Collections.singletonList("R15 Vault content must survive editor save."));

            NoteGroupFacade facade=new NoteGroupFacade(context);
            StreamingGroupStore.Snapshot base=facade.adoptLegacyNote(entry.id,(old,staged)->true);
            byte[] originalBody=base.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX);
            byte[] originalPdf=readMember(base,"pdf.bin");
            byte[] originalCover=readMember(base,"cover.bin");
            String videoMember=onlyContent(base,"video/");
            String vaultMember=onlyContent(base,"vault/");
            byte[] originalVideo=readMember(base,videoMember);
            byte[] originalVault=readMember(base,vaultMember);
            byte[] originalVideoMetadata=readMember(base,videoMember.replace("content.bin","metadata.bin"));
            byte[] originalVaultMetadata=readMember(base,vaultMember.replace("content.bin","metadata.bin"));
            assertArrayEquals(video,originalVideo);

            scenario=ActivityScenario.launch(MainActivity.class);
            clickNoteWhenVisible(noteTitle);
            awaitEditorReady(scenario,entry.id,base.revision,base.digest);
            scenario.onActivity(activity->{
                assertEquals("opening the actual shelf card captures the exact immutable base revision",base.revision,activity.groupRevisionForTest());
                assertEquals("opening the actual shelf card captures the exact immutable base digest",base.digest,activity.groupDigestForTest());
            });
            JSONObject baseBody=new JSONObject(new String(originalBody,StandardCharsets.UTF_8));
            int oldStrokeCount=baseBody.getJSONArray("strokes").length();
            java.util.concurrent.atomic.AtomicReference<JSONObject> editedCanvas=new java.util.concurrent.atomic.AtomicReference<>();
            scenario.onActivity(activity->{
                NoteCanvasView canvas=activity.canvasForTest();
                assertTrue("real editor canvas is laid out",canvas.getWidth()>0&&canvas.getHeight()>0);
                dispatchStylusStroke(canvas);
                try{editedCanvas.set(canvas.toJsonDocument(entry.id,noteTitle));}
                catch(Exception error){throw new AssertionError(error);}
            });
            JSONObject changed=editedCanvas.get();
            assertNotNull("real stylus input produced a canvas snapshot",changed);
            assertTrue("the canvas serializer emits its persisted viewport scale",changed.has("viewportScale"));
            String serializedCanvas=NoteJsonCodec.stringify(changed);
            TypedNoteTimeProjection.Projection sourceProjection=
                    TypedNoteTimeProjection.nativeAndroid(serializedCanvas);
            assertEquals("typed projection accepts the exact live canvas JSON",
                    sha(serializedCanvas.getBytes(StandardCharsets.UTF_8)),sourceProjection.rawSha256);
            JSONArray oldStrokes=changed.getJSONArray("strokes");
            assertEquals("a real stylus stroke adds one document stroke",oldStrokeCount+1,oldStrokes.length());
            onView(withContentDescription("立即保存")).perform(scrollTo(),click());

            StreamingGroupStore.Snapshot saved=awaitNewRevision(facade,entry.id,base.revision);
            awaitSavedStatus();
            JSONObject savedBody=new JSONObject(new String(saved.readSmall("body.bin",
                    (int)StreamingGroupStore.BODY_MAX),StandardCharsets.UTF_8));
            assertEquals(noteTitle,savedBody.getString("title"));
            assertEquals(oldStrokeCount+1,savedBody.getJSONArray("strokes").length());
            assertEquals("editor page-edit counter survives the group save",
                    changed.getLong("authorPageEditSerial"),savedBody.getLong("authorPageEditSerial"));
            assertEquals("editor page-topology counter survives the group save",
                    changed.getLong("authorPageTopologySerial"),savedBody.getLong("authorPageTopologySerial"));
            assertEquals("persisted viewport scale survives the exact group-body save",
                    Double.doubleToRawLongBits(changed.getDouble("viewportScale")),
                    Double.doubleToRawLongBits(savedBody.getDouble("viewportScale")));
            for(int i=0;i<oldStrokeCount;i++)assertEquals("existing stroke "+i,
                    changed.getJSONArray("strokes").getJSONObject(i).toString(),
                    savedBody.getJSONArray("strokes").getJSONObject(i).toString());
            assertEquals(2,savedBody.getInt("pageCount"));
            assertEquals(1,savedBody.getInt("pdfPageCount"));
            assertArrayEquals("PDF bytes are carried into the saved revision",originalPdf,readMember(saved,"pdf.bin"));
            assertArrayEquals("cover bytes are carried into the saved revision",originalCover,readMember(saved,"cover.bin"));
            assertArrayEquals("video bytes are carried into the saved revision",originalVideo,
                    readMember(saved,onlyContent(saved,"video/")));
            assertArrayEquals("video descriptor identity is unchanged",originalVideoMetadata,
                    readMember(saved,onlyContent(saved,"video/").replace("content.bin","metadata.bin")));
            assertArrayEquals("linked Vault storage Markdown is carried byte-exact",originalVault,
                    readMember(saved,onlyContent(saved,"vault/")));
            assertArrayEquals("Vault descriptor identity is unchanged",originalVaultMetadata,
                    readMember(saved,onlyContent(saved,"vault/").replace("content.bin","metadata.bin")));

            StreamingGroupStore.Snapshot retained=facade.openGroupRevision(entry.id,base.revision,base.digest);
            assertNotNull("the editor's base revision remains immutable and readable",retained);
            assertArrayEquals(originalBody,readMember(retained,"body.bin"));
            assertArrayEquals(originalPdf,readMember(retained,"pdf.bin"));
            assertArrayEquals(originalCover,readMember(retained,"cover.bin"));
            assertArrayEquals(originalVideo,readMember(retained,onlyContent(retained,"video/")));
            assertArrayEquals(originalVault,readMember(retained,onlyContent(retained,"vault/")));

            JSONObject stale=new JSONObject(savedBody.toString()).put("title","must not replace winner");
            try{facade.saveEditorRevision(entry.id,base.revision,base.digest,"stale editor",stale.toString());
                fail("an editor with an old group revision must not overwrite the winner");}
            catch(java.io.IOException expected){assertEquals("BASE_CAS_CONFLICT",expected.getMessage());}
            StreamingGroupStore.Snapshot stillWinner=facade.openGroup(entry.id);
            assertEquals(saved.revision,stillWinner.revision);assertEquals(saved.digest,stillWinner.digest);
            assertEquals(noteTitle,NoteStore.load(context,entry.id).getString("title"));

            onView(withContentDescription("返回书架")).perform(click());
            clickNoteWhenVisible(noteTitle);
            awaitEditorReady(scenario,entry.id,saved.revision,saved.digest);
            java.util.concurrent.atomic.AtomicReference<JSONObject> reopenedEditor=new java.util.concurrent.atomic.AtomicReference<>();
            scenario.onActivity(activity->{
                try{reopenedEditor.set(activity.canvasForTest().toJsonDocument(entry.id,noteTitle));}
                catch(Exception failure){throw new AssertionError(failure);}
                assertEquals("reopened editor uses the committed group revision",saved.revision,activity.groupRevisionForTest());
                assertEquals("reopened editor uses the committed group digest",saved.digest,activity.groupDigestForTest());
            });
            assertNotNull("reopened editor body is readable",reopenedEditor.get());
            assertEquals("reopened editor shows the saved strokes",savedBody.getJSONArray("strokes").length(),
                    reopenedEditor.get().getJSONArray("strokes").length());
            assertEquals("reopened editor shows the saved page count",2,reopenedEditor.get().getInt("pageCount"));
            assertEquals("reopened editor restores the saved page-edit counter",
                    savedBody.getLong("authorPageEditSerial"),reopenedEditor.get().getLong("authorPageEditSerial"));
            assertEquals("reopened editor restores the saved page-topology counter",
                    savedBody.getLong("authorPageTopologySerial"),reopenedEditor.get().getLong("authorPageTopologySerial"));
        } finally {
            if(scenario!=null)scenario.close();
            if(sourceVideo.exists())assertTrue("synthetic source video cleanup",sourceVideo.delete());
        }
    }

    private static void clickNoteWhenVisible(String title)throws Exception {
        String description="打开笔记 "+title+"。长按可重命名或更换封面。";
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        Throwable last=null;
        while(System.nanoTime()<deadline){
            try{onView(withContentDescription(description)).perform(scrollTo(),clickWithGeometry());return;}
            catch(RuntimeException|AssertionError unavailable){last=unavailable;Thread.sleep(120);}
        }
        throw new AssertionError("Bookshelf did not expose the synthetic note card",last);
    }
    private static ViewAction clickWithGeometry() {
        ViewAction actualClick=click();
        return new ViewAction() {
            @Override public org.hamcrest.Matcher<View> getConstraints() { return actualClick.getConstraints(); }
            @Override public String getDescription() { return "record shelf card geometry and perform real click"; }
            @Override public void perform(UiController controller,View view) {
                emitShelfCardGeometry("beforeClick",view);
                try { actualClick.perform(controller,view); }
                finally { emitShelfCardGeometry("afterClick",view); }
            }
        };
    }
    private static void emitShelfCardGeometry(String phase,View target) {
        try {
            JSONObject record=new JSONObject();record.put("phase",phase);
            JSONArray ancestors=new JSONArray();View current=target;
            for(int depth=0;current!=null&&depth<7;depth++){
                JSONObject item=new JSONObject();item.put("depth",depth);
                item.put("class",current.getClass().getName());item.put("attached",current.isAttachedToWindow());
                item.put("shown",current.isShown());item.put("width",current.getWidth());item.put("height",current.getHeight());
                item.put("left",current.getLeft());item.put("top",current.getTop());
                item.put("right",current.getRight());item.put("bottom",current.getBottom());
                item.put("clipBounds",current.getClipBounds()==null?JSONObject.NULL:current.getClipBounds().toShortString());
                int[] screen=new int[2];current.getLocationOnScreen(screen);
                item.put("screenX",screen[0]);item.put("screenY",screen[1]);
                Rect visible=new Rect();boolean hasVisible=current.getGlobalVisibleRect(visible);
                item.put("hasGlobalVisibleRect",hasVisible);
                if(hasVisible){JSONArray bounds=new JSONArray();bounds.put(visible.left).put(visible.top).put(visible.right).put(visible.bottom);item.put("globalVisibleRect",bounds);}
                if(current instanceof android.view.ViewGroup){
                    android.view.ViewGroup group=(android.view.ViewGroup)current;
                    item.put("clipChildren",group.getClipChildren());item.put("clipToPadding",group.getClipToPadding());
                }
                ancestors.put(item);
                android.view.ViewParent parent=current.getParent();current=parent instanceof View?(View)parent:null;
            }
            record.put("ancestors",ancestors);
            System.out.println("R29_EDITOR_CLICK_GEOMETRY "+record);
        } catch(Exception diagnosticFailure) {
            System.out.println("R29_EDITOR_CLICK_GEOMETRY {\"captureFailed\":true}");
        }
    }
    /** Sends the same stylus down/move/up sequence the editor receives from a pen. */
    private static void dispatchStylusStroke(NoteCanvasView canvas) {
        long downTime=android.os.SystemClock.uptimeMillis();
        float x=canvas.getWidth()*0.5f;
        float y=canvas.getHeight()*0.5f;
        dispatchStylusEvent(canvas,downTime,downTime,MotionEvent.ACTION_DOWN,x,y);
        dispatchStylusEvent(canvas,downTime,downTime+24,MotionEvent.ACTION_MOVE,x+18f,y+20f);
        dispatchStylusEvent(canvas,downTime,downTime+48,MotionEvent.ACTION_UP,x+36f,y+42f);
    }
    private static void dispatchStylusEvent(NoteCanvasView canvas,long downTime,long eventTime,
            int action,float x,float y) {
        MotionEvent.PointerProperties properties=new MotionEvent.PointerProperties();
        properties.id=0;properties.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords coordinates=new MotionEvent.PointerCoords();
        coordinates.x=x;coordinates.y=y;coordinates.pressure=0.7f;coordinates.size=0.08f;
        MotionEvent event=MotionEvent.obtain(downTime,eventTime,action,1,
                new MotionEvent.PointerProperties[]{properties},new MotionEvent.PointerCoords[]{coordinates},
                0,MotionEvent.BUTTON_STYLUS_PRIMARY,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0);
        try{assertTrue("stylus input is handled by the real editor canvas",canvas.dispatchTouchEvent(event));}
        finally{event.recycle();}
    }
    private static void awaitEditorReady(ActivityScenario<MainActivity> scenario,String syntheticId,
            String expectedRevision,String expectedDigest)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){
            java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
            scenario.onActivity(activity->ready.set(activity.canvasForTest()!=null&&activity.canvasForTest().isEnabled()));
            if(ready.get())return;Thread.sleep(100);
        }
        java.util.concurrent.atomic.AtomicReference<String> state=new java.util.concurrent.atomic.AtomicReference<>("{\"captureFailed\":true}");
        scenario.onActivity(activity->state.set(activity.editorOpenStateForTest(
                syntheticId,expectedRevision,expectedDigest)));
        String message="Group-backed editor did not finish opening; state="+state.get();
        System.out.println("R29_EDITOR_OPEN_FAILURE "+state.get());
        throw new AssertionError(message);
    }
    private static void awaitSavedStatus()throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);Throwable last=null;
        while(System.nanoTime()<deadline){
            try{onView(withText("已保存到本机 · 可重新打开")).check(matches(isDisplayed()));return;}
            catch(RuntimeException|AssertionError unavailable){last=unavailable;Thread.sleep(100);}
        }
        throw new AssertionError("real editor save did not finish successfully",last);
    }
    private static StreamingGroupStore.Snapshot awaitNewRevision(NoteGroupFacade facade,String id,String oldRevision)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);StreamingGroupStore.Snapshot last=null;
        while(System.nanoTime()<deadline){last=facade.openGroup(id);if(last!=null&&!oldRevision.equals(last.revision))return last;Thread.sleep(100);}
        throw new AssertionError("Existing save action did not publish a new group revision; last="+(last==null?"missing":last.revision));
    }
    private static byte[] readMember(StreamingGroupStore.Snapshot group,String member)throws Exception {
        Long size=group.memberSizes().get(member);assertNotNull("member exists: "+member,size);
        assertTrue("member fits bounded test read: "+member,size<=Integer.MAX_VALUE);
        return group.readSmall(member,Math.max(1,size.intValue()));
    }
    private static String onlyContent(StreamingGroupStore.Snapshot group,String prefix)throws Exception {
        String found=null;for(String member:group.memberSizes().keySet())if(member.startsWith(prefix)&&member.endsWith("/content.bin")){
            assertNull("one content object under "+prefix,found);found=member;}
        assertNotNull("content exists under "+prefix,found);return found;
    }
    private static void writePdf(File file)throws Exception {
        PdfDocument doc=new PdfDocument();PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(160,160,1).create());
        page.getCanvas().drawColor(Color.WHITE);Paint paint=new Paint();paint.setColor(Color.BLACK);page.getCanvas().drawText("R15 group save",10,40,paint);
        doc.finishPage(page);try(FileOutputStream out=new FileOutputStream(file)){doc.writeTo(out);out.flush();out.getFD().sync();}finally{doc.close();}
    }
    private static String sha(byte[] bytes)throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();
        for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
}
