package com.padnote.android;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real Canvas layout and schema checks using an in-memory note; never writes app note storage. */
@RunWith(AndroidJUnit4.class)
public final class AgentResultAppendInstrumentedTest {
    @Test public void appendPreflightsAtDocumentEndAndCommitsExactlyOnce() throws Exception {
        try (CanvasSession session = CanvasSession.open()) {
            AtomicReference<String> originalFlowSource = new AtomicReference<>();
            AtomicReference<String> originalFlowId = new AtomicReference<>();
            onActivityAndRethrow(session.scenario, activity -> {
                NoteTextBox existing = session.canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "Existing handwritten context", dp(activity, 24), dp(activity, 24));
                assertNotNull(existing);
                originalFlowSource.set(existing.source);
                originalFlowId.set(existing.flowId);
            });

            String taskId = UUID.randomUUID().toString();
            String output = "Result text from an isolated completed task.";
            AgentTaskStore.Task task = new AgentTaskStore.Task(taskId, "fixture-connection", 1L,
                    "fixture-origin", AgentConnectionStore.Kind.HERMES, AgentConnectionStore.Transport.BRIDGE,
                    "fixture-credential", "fixture-bridge", "fixture-instance", "Fixture task", "Fixture",
                    "fixture-note", 10L, "{}", AgentResultProvenance.sha256("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "fixture-remote", AgentTaskStore.Status.COMPLETED, output, "", "", "", "",
                    Collections.emptyList(), 10L, 11L);
            AgentResultProvenance provenance = AgentResultProvenance.create(task, "fixture-note", 10L, output);
            AtomicReference<NoteCanvasView.AgentResultAppendCandidate> candidateRef = new AtomicReference<>();

            onActivityAndRethrow(session.scenario, activity -> {
                JSONObject before = session.canvas.toJsonDocument("fixture-note", "Fixture");
                JSONArray beforeFlows = before.getJSONArray("textFlows");
                assertEquals(1, beforeFlows.length());
                NoteCanvasView.AgentResultAppendCandidate candidate = session.canvas.prepareAgentResultAppend(
                        "fixture-note", "Fixture", output, provenance);
                assertFalse(candidate.alreadyApplied);
                NoteStore.validateDocument(candidate.document);
                assertEquals(9, candidate.document.getInt("schemaVersion"));
                assertEquals(2, candidate.document.getJSONArray("textFlows").length());
                assertEquals("the existing flow remains byte-for-byte the same source",
                        originalFlowSource.get(), candidate.document.getJSONArray("textFlows")
                                .getJSONObject(0).getString("source"));
                assertEquals("preflight does not mutate the live canvas", 1,
                        session.canvas.toJsonDocument("fixture-note", "Fixture")
                                .getJSONArray("textFlows").length());
                candidateRef.set(candidate);
            });

            onActivityAndRethrow(session.scenario, activity -> {
                NoteTextBox last = session.canvas.commitAgentResultAppend(candidateRef.get());
                assertNotNull("the in-memory commit happens only after caller durability", last);
                assertEquals(originalFlowId.get(), session.canvas.toJsonDocument("fixture-note", "Fixture")
                        .getJSONArray("textFlows").getJSONObject(0).getString("id"));
                JSONObject saved = session.canvas.toJsonDocument("fixture-note", "Fixture");
                assertEquals(9, saved.getInt("schemaVersion"));
                assertEquals(2, saved.getJSONArray("textFlows").length());
                JSONObject resultFlow = saved.getJSONArray("textFlows").getJSONObject(1);
                assertEquals(output, resultFlow.getString("source"));
                assertEquals(provenance.applicationId, resultFlow.getJSONObject("agentResult")
                        .getString("applicationId"));
                NoteStore.validateDocument(saved);
                NoteCanvasView.AgentResultAppendCandidate retry = session.canvas.prepareAgentResultAppend(
                        "fixture-note", "Fixture", output,
                        AgentResultProvenance.create(task, "fixture-note", 10L, output));
                assertTrue("same application identity is recognized even when retry timestamp differs",
                        retry.alreadyApplied);
                assertEquals("duplicate result is not appended", 2,
                        retry.document.getJSONArray("textFlows").length());
                JSONObject afterUndoOfLastReceipt = new JSONObject(saved.toString())
                        .put("schemaVersion", 9).put("textFlows", new JSONArray());
                try {
                    session.canvas.loadJsonDocument(afterUndoOfLastReceipt);
                    assertEquals("a valid schema-9 note remains readable after its last receipt flow is removed",
                            8, session.canvas.toJsonDocument("fixture-note", "Fixture").getInt("schemaVersion"));
                } catch (org.json.JSONException invalid) {
                    throw new AssertionError("schema-9 empty receipt note should be accepted", invalid);
                }
            });
        }
    }

    @Test public void pointCandidateRendersAllPagesThenSavesAndReopensAtSameAnchor() throws Exception {
        try (CanvasSession session=CanvasSession.open()) {
            AtomicReference<NoteStore.Entry> noteRef=new AtomicReference<>();
            AtomicReference<NoteCanvasView.AgentResultAppendCandidate> candidateRef=new AtomicReference<>();
            AtomicReference<Exception> failureRef=new AtomicReference<>();
            CountDownLatch ready=new CountDownLatch(1);
            AtomicReference<AgentResultProvenance> provenanceRef=new AtomicReference<>();
            onActivityAndRethrow(session.scenario,activity->{
                NoteStore.Entry note=NoteStore.create(activity,"Placement fixture");noteRef.set(note);
                String output="A point placed Agent answer with compiled preview.";
                AgentTaskStore.Task task=new AgentTaskStore.Task(UUID.randomUUID().toString(),
                        "fixture-connection",1L,"fixture-origin",AgentConnectionStore.Kind.HERMES,
                        AgentConnectionStore.Transport.BRIDGE,"fixture-credential","fixture-bridge",
                        "fixture-instance","Fixture task","Fixture",note.id,11L,"{}",
                        AgentResultProvenance.sha256("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "fixture-remote",AgentTaskStore.Status.COMPLETED,output,"","","","",
                        Collections.emptyList(),11L,12L);
                AgentResultProvenance provenance=AgentResultProvenance.create(task,note.id,11L,output);
                provenanceRef.set(provenance);
                NoteCanvasView.PdfExportSnapshot geometry=session.canvas.createPdfExportSnapshot();
                float pageWidth=geometry.getPageWidth();geometry.close();
                float margin=dp(activity,24);
                AgentResultPlacement placement=AgentResultPlacement.point(0,margin,margin,pageWidth-margin*2);
                NoteCanvasView.AgentResultAppendCandidate candidate=session.canvas.prepareAgentResultAppend(
                        note.id,note.title,output,provenance,placement);
                candidateRef.set(candidate);
                FrameLayout host=new FrameLayout(activity);host.setAlpha(0.01f);host.setClipChildren(false);
                host.setClipToPadding(false);
                ((ViewGroup)activity.findViewById(android.R.id.content)).addView(host,
                        new FrameLayout.LayoutParams(1,1));
                session.canvas.prepareAgentResultPreviewAsync(candidate,host,
                        new NoteCanvasView.AgentPreviewPreparationCallback(){
                            public void onReady(NoteCanvasView.AgentResultAppendCandidate stable,
                                                NoteCanvasView.PdfExportSnapshot snapshot){
                                candidateRef.set(stable);snapshot.close();
                                ((ViewGroup)host.getParent()).removeView(host);ready.countDown();
                            }
                            public void onFailure(Exception error){failureRef.set(error);
                                if(host.getParent() instanceof ViewGroup)((ViewGroup)host.getParent()).removeView(host);
                                ready.countDown();}
                        });
            });
            assertTrue("compiled candidate pages did not stabilize",ready.await(60,TimeUnit.SECONDS));
            if(failureRef.get()!=null)throw new AssertionError("candidate paper preparation failed",failureRef.get());
            assertTrue(candidateRef.get().previewLayoutStable);
            assertEquals(0,candidateRef.get().firstPageIndex);
            assertEquals(AgentResultPlacement.Mode.POINT,candidateRef.get().placement.mode);
            onActivityAndRethrow(session.scenario,activity->{
                NoteCanvasView.AgentResultAppendCandidate candidate=candidateRef.get();
                assertNotNull(candidate);
                NoteTextBox inserted=session.canvas.commitAgentResultAppend(candidate);
                assertNotNull("commit uses the measured point layout",inserted);
                String json=NoteJsonCodec.stringify(session.canvas.toJsonDocument(noteRef.get().id,noteRef.get().title));
                NoteStore.save(activity,noteRef.get().id,noteRef.get().title,json);
                JSONObject reopened=NoteStore.load(activity,noteRef.get().id);
                JSONArray flows=reopened.getJSONArray("textFlows");
                JSONObject result=null;
                for(int i=0;i<flows.length();i++)if(candidate.flowId.equals(flows.getJSONObject(i).optString("id")))result=flows.getJSONObject(i);
                assertNotNull(result);
                assertEquals(candidate.layouts.get(0).pageIndex,result.getInt("anchorPageIndex"));
                assertEquals(candidate.layouts.get(0).x,result.getDouble("anchorXInPage"),0.5d);
                assertEquals(candidate.layouts.get(0).y-
                        (float)(result.getInt("anchorPageIndex")*(session.canvas.toJsonDocument(noteRef.get().id,noteRef.get().title).getDouble("pageHeight")+
                        session.canvas.toJsonDocument(noteRef.get().id,noteRef.get().title).getDouble("pageGap"))),
                        result.getDouble("anchorYInPage"),1d);
                assertEquals(provenanceRef.get().resultSha256,
                        result.getJSONObject("agentResult").getString("resultSha256"));
                session.canvas.loadJsonDocument(reopened);
                assertEquals(result.getString("source"),session.canvas.toJsonDocument(noteRef.get().id,noteRef.get().title)
                        .getJSONArray("textFlows").getJSONObject(flows.length()-1).getString("source"));
                NoteStore.delete(activity,noteRef.get().id);
            });
        }
    }

    @Test public void pointPlacementRejectsTextInkAndImagesWithoutChangingDocument() throws Exception {
        try(CanvasSession session=CanvasSession.open()) {
            onActivityAndRethrow(session.scenario,activity->{
                NoteCanvasView.PdfExportSnapshot geometry=session.canvas.createPdfExportSnapshot();
                float margin=dp(activity,24),width=geometry.getPageWidth()-margin*2;
                geometry.close();
                NoteTextBox old=session.canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "old text collision",margin,margin);
                assertNotNull(old);
                rejectPointCandidateWithoutMutation(activity,session.canvas,margin,margin,width,"existing text");

                JSONObject blank=session.canvas.toJsonDocument("collision-fixture","Fixture");
                blank.put("textFlows",new JSONArray()).put("textBoxes",new JSONArray())
                        .put("strokes",new JSONArray()).put("images",new JSONArray()).put("pageCount",1);
                session.canvas.loadJsonDocument(blank);
                InkStroke stroke=new InkStroke("fixture-ink",android.graphics.Color.BLACK,dp(activity,5),1L);
                stroke.points.add(new InkPoint(margin+4,margin+8,1L,1f));
                stroke.points.add(new InkPoint(margin+40,margin+8,2L,1f));
                blank=session.canvas.toJsonDocument("collision-fixture","Fixture");
                blank.getJSONArray("strokes").put(stroke.toJson());
                session.canvas.loadJsonDocument(blank);
                rejectPointCandidateWithoutMutation(activity,session.canvas,margin,margin,width,"ink");

                blank=session.canvas.toJsonDocument("collision-fixture","Fixture");
                blank.put("strokes",new JSONArray());
                Bitmap bitmap=Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(android.graphics.Color.BLUE);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes));bitmap.recycle();
                String png=Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP);
                NoteImage image=new NoteImage("fixture-image",Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888),
                        png,0,margin+2,margin+2,dp(activity,48),dp(activity,32));
                blank.getJSONArray("images").put(image.toJson());
                session.canvas.loadJsonDocument(blank);
                image.bitmap.recycle();
                rejectPointCandidateWithoutMutation(activity,session.canvas,margin,margin,width,"image");
            });
        }
    }

    @Test public void pointPlacementChecksEveryContinuationPageAndRejectsCollision() throws Exception {
        try(CanvasSession session=CanvasSession.open()) {
            onActivityAndRethrow(session.scenario,activity->{
                JSONObject geometry=session.canvas.toJsonDocument("long-fixture","Fixture");
                float margin=dp(activity,24),pageWidth=(float)geometry.getDouble("pageWidth");
                float stride=(float)(geometry.getDouble("pageHeight")+geometry.getDouble("pageGap"));
                session.canvas.addPage();
                NoteTextBox obstacle=session.canvas.addTextBoxAt(NoteTextBox.Format.MARKDOWN,
                        "page two obstacle",margin,stride+margin);
                assertNotNull(obstacle);
                String before=withoutExportUpdatedAt(session.canvas.toJsonDocument("long-fixture","Fixture"));
                String longOutput=String.join(" ",Collections.nCopies(9000,"answer"));
                AgentTaskStore.Task task=task(UUID.randomUUID().toString(),longOutput);
                AgentResultProvenance provenance=AgentResultProvenance.create(task,"long-fixture",1L,longOutput);
                AgentResultPlacement placement=AgentResultPlacement.point(0,margin,margin,pageWidth-margin*2);
                try {
                    session.canvas.prepareAgentResultAppend("long-fixture","Fixture",longOutput,provenance,placement);
                    fail("long candidate must reject a collision on its continuation page");
                } catch(java.io.IOException expected) {
                    assertTrue(expected.getMessage().contains("第2页"));
                }
                assertEquals("collision rejection is pure apart from export timestamp",before,
                        withoutExportUpdatedAt(session.canvas.toJsonDocument("long-fixture","Fixture")));
            });
        }
    }

    @Test public void pdfPointPlacementAcceptsWhiteRoiRejectsColoredRoiAndFailsClosedWithoutPdf() throws Exception {
        try(CanvasSession session=CanvasSession.open()) {
            AtomicReference<NoteStore.Entry> noteRef=new AtomicReference<>();
            onActivityAndRethrow(session.scenario,activity->{
                NoteStore.Entry note=NoteStore.create(activity,"PDF placement fixture");noteRef.set(note);
                File pdfFile=NoteStore.pdfFile(activity,note.id);
                PdfDocument pdf=new PdfDocument();
                PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(600,800,1).create());
                page.getCanvas().drawColor(android.graphics.Color.WHITE);
                Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(android.graphics.Color.rgb(30,60,180));
                page.getCanvas().drawRect(0,560,600,800,paint);pdf.finishPage(page);
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(pdfFile)){pdf.writeTo(out);out.flush();out.getFD().sync();}
                finally{pdf.close();}
                JSONObject document=NoteStore.load(activity,note.id);
                document.put("schemaVersion",9).put("pageCount",1).put("pdfPageCount",1);
                session.canvas.loadJsonDocument(document);
                NoteCanvasView.PdfExportSnapshot geometry=session.canvas.createPdfExportSnapshot();
                float margin=dp(activity,24),width=dp(activity,220);
                float pageHeight=geometry.getPageHeight(),pageWidth=geometry.getPageWidth();geometry.close();
                AgentTaskStore.Task whiteTask=task(UUID.randomUUID().toString(),"White-region answer");
                AgentResultProvenance whiteReceipt=AgentResultProvenance.create(whiteTask,note.id,10L,whiteTask.output);
                NoteCanvasView.AgentResultAppendCandidate white=session.canvas.prepareAgentResultAppend(
                        note.id,note.title,whiteTask.output,whiteReceipt,
                        AgentResultPlacement.point(0,margin,margin,width));
                assertEquals(0,white.firstPageIndex);
                AgentTaskStore.Task coloredTask=task(UUID.randomUUID().toString(),"Colored-region answer");
                AgentResultProvenance coloredReceipt=AgentResultProvenance.create(coloredTask,note.id,10L,coloredTask.output);
                try {
                    session.canvas.prepareAgentResultAppend(note.id,note.title,coloredTask.output,coloredReceipt,
                            AgentResultPlacement.point(0,margin,pageHeight-dp(activity,220),width));
                    fail("colored PDF pixels inside candidate ROI must reject placement");
                } catch(java.io.IOException expected) { assertTrue(expected.getMessage().contains("PDF内容重叠")); }
                java.lang.reflect.Field background=NoteCanvasView.class.getDeclaredField("pdfBackground");
                background.setAccessible(true);Object saved=background.get(session.canvas);
                background.set(session.canvas,null);
                try {
                    AgentTaskStore.Task missingTask=task(UUID.randomUUID().toString(),"Missing PDF answer");
                    AgentResultProvenance receipt=AgentResultProvenance.create(missingTask,note.id,10L,missingTask.output);
                    try {
                        session.canvas.prepareAgentResultAppend(note.id,note.title,missingTask.output,receipt,
                                AgentResultPlacement.point(0,margin,margin,width));
                        fail("a PDF page without its renderer must fail closed");
                    } catch(java.io.IOException expected) { assertTrue(expected.getMessage().contains("PDF页面资源不可用")); }
                } finally { background.set(session.canvas,saved); }
                NoteStore.delete(activity,note.id);
            });
        }
    }

    private static void rejectPointCandidateWithoutMutation(Activity activity,NoteCanvasView canvas,
            float x,float y,float width,String label) throws Exception {
        String before=withoutExportUpdatedAt(canvas.toJsonDocument("collision-fixture","Fixture"));
        String output="collision probe";
        AgentTaskStore.Task task=task(UUID.randomUUID().toString(),output);
        AgentResultProvenance provenance=AgentResultProvenance.create(task,"collision-fixture",1L,output);
        try {
            canvas.prepareAgentResultAppend("collision-fixture","Fixture",output,provenance,
                    AgentResultPlacement.point(0,x,y,width));
            fail("point candidate must reject "+label+" collision");
        } catch(java.io.IOException expected) { }
        assertEquals("collision check leaves document unchanged apart from export timestamp",before,
                withoutExportUpdatedAt(canvas.toJsonDocument("collision-fixture","Fixture")));
    }

    private static String withoutExportUpdatedAt(JSONObject document) throws Exception {
        JSONObject normalized=new JSONObject(document.toString());
        normalized.remove("updatedAt");
        return NoteJsonCodec.stringify(normalized);
    }

    private static AgentTaskStore.Task task(String taskId,String output) {
        return new AgentTaskStore.Task(taskId,"fixture-connection",1L,"fixture-origin",
                AgentConnectionStore.Kind.HERMES,AgentConnectionStore.Transport.BRIDGE,
                "fixture-credential","fixture-bridge","fixture-instance","Fixture task","Fixture",
                "fixture-note",10L,"{}",AgentResultProvenance.sha256("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "fixture-remote",AgentTaskStore.Status.COMPLETED,output,"","","","",
                Collections.emptyList(),10L,11L);
    }

    private static AgentTaskStore.Task taskWithOutput(AgentTaskStore.Task task,String output) {
        return new AgentTaskStore.Task(task.clientTaskId,task.connectionId,task.connectionRevision,
                task.origin,task.kind,task.transport,task.credentialRef,task.bridgeId,task.instanceId,
                task.title,task.connectionName,task.noteId,task.noteRevision,task.submissionJson,
                task.submissionSha256,task.remoteTaskId,task.status,output,task.error,task.approvalId,
                task.approvalTitle,task.approvalDescription,task.artifacts,task.createdAt,task.updatedAt);
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private interface ActivityWork { void run(MainActivity activity) throws Throwable; }

    private static void onActivityAndRethrow(ActivityScenario<MainActivity> scenario,
                                             ActivityWork work) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try { work.run(activity); }
            catch (Throwable error) { failure.set(error); }
        });
        Throwable error = failure.get();
        if (error == null) return;
        if (error instanceof Error) throw (Error) error;
        if (error instanceof Exception) throw (Exception) error;
        throw new AssertionError("Activity assertion failed", error);
    }

    private static final class CanvasSession implements AutoCloseable {
        final ActivityScenario<MainActivity> scenario;
        final NoteCanvasView canvas;

        private CanvasSession(ActivityScenario<MainActivity> scenario, NoteCanvasView canvas) {
            this.scenario = scenario;
            this.canvas = canvas;
        }

        static CanvasSession open() {
            ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
            AtomicReference<NoteCanvasView> canvasRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                int width = dp(activity, 768), height = dp(activity, 1024);
                FrameLayout root = new FrameLayout(activity);
                NoteCanvasView canvas = new NoteCanvasView(activity);
                root.addView(canvas, new FrameLayout.LayoutParams(width, height));
                activity.setContentView(root, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                canvas.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                canvas.layout(0, 0, width, height);
                canvasRef.set(canvas);
            });
            return new CanvasSession(scenario, canvasRef.get());
        }

        @Override public void close() { scenario.close(); }
    }
}
