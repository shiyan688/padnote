package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.app.UiAutomation;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.Espresso;
import androidx.test.espresso.action.ViewActions;
import androidx.test.espresso.assertion.ViewAssertions;
import androidx.test.espresso.matcher.RootMatchers;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the actual nested task-detail and video-review dialogs on device. */
@RunWith(AndroidJUnit4.class)
public final class VideoTaskDialogsTest {
    @Test public void detailEntryRendersBoundReviewAndExplicitApprovalDoesNotRenderVideo() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        AgentConnectionStore connections=new AgentConnectionStore(context);AgentConnectionStore.Config saved=null;
        ActivityScenario<MainActivity> scenario=null;ExecutorService worker=Executors.newSingleThreadExecutor();
        File taskDir=new File(context.getCacheDir(),"video-ui-tasks-"+System.nanoTime());
        final java.util.List<AgentHttpTransport.Request> calls=Collections.synchronizedList(new java.util.ArrayList<>());
        final Map<String,String> operationActions=Collections.synchronizedMap(new HashMap<>());
        final Map<String,String> operationIds=Collections.synchronizedMap(new HashMap<>());
        final AtomicInteger operationCounter=new AtomicInteger();
        final AtomicReference<String> state=new AtomicReference<>("initialize");
        final VideoOperationStore[] videoStore={null};
        byte[] png=pngFixture();
        String pngSha=sha(png),reviewSha=repeat('b',64),irSha=repeat('c',64),previewId=previewId(reviewSha,irSha);
        AgentTaskStore.Task[] videoTask={null};
        try{
            connections.clear();saved=connections.addBridge("Video UI fixture",AgentConnectionStore.Kind.HERMES,
                    "https://bridge.test","bridge-ui","instance-ui","synthetic-token");
            assertTrue(connections.applyProbeSuccess(saved.id,saved.revision,new AgentConnectionClient.ProbeResult(
                    "verified","bridge-ui","instance-ui",Arrays.asList("task_bundle","run_status","video_operations"))));
            AgentConnectionStore.Config verified=connections.get(saved.id);
            ByteArrayOutputStream zip=new ByteArrayOutputStream();VideoTaskBundleIO.write(zip,"note-ui",1,"视频标题","材料","初学者","理解输入",60,"voice",1f);
            byte[] bundle=zip.toByteArray();String clientId="padnote-cccccccccccccccccccccccccccccccc";
            AgentTaskStore taskStore=new AgentTaskStore(taskDir);
            AgentTaskStore.Task pending=taskStore.create(verified,"视频UI测试","视频说明","note-ui",1,bundle);
            assertTrue(taskStore.applySubmission(pending.clientTaskId,verified.id,verified.revision,"remote-video",AgentTaskStore.Status.COMPLETED));
            AgentTaskStore.Task created=taskStore.get(pending.clientTaskId);
            videoTask[0]=created;
            String workerId=workerId(bundle);Map<String,String> operationKeys=Collections.synchronizedMap(new HashMap<>());
            AgentHttpTransport transport=request->{calls.add(request);String path=request.url.getPath();
                if(path.endsWith("/video/diagnostics"))return response(request,200,diagnostics());
                if(path.endsWith("/video/review")){
                    if(state.get().equals("initialize"))return response(request,409,new JSONObject().put("error","review_unavailable"));
                    return response(request,200,review(workerId,state.get().equals("approve")?"approved":"awaiting_storyboard_review",previewId,png,pngSha,reviewSha,irSha));
                }
                if(path.endsWith("/video/previews/"+previewId))return new AgentHttpTransport.Response(200,request.url,request.url,png,
                        Map.of("Content-Type",Collections.singletonList("image/png"),"Content-Length",Collections.singletonList(String.valueOf(png.length))));
                if(path.endsWith("/video/operations")){
                    JSONObject sent=new JSONObject(new String(request.body,StandardCharsets.UTF_8));String action=sent.getString("action");String key=request.headers.get("Idempotency-Key");
                    assertEquals(key,sent.optString("client_operation_id",key));operationKeys.put(key,action);state.set(action);
                    String id=String.format(java.util.Locale.ROOT,"18ed9804-fbdb-4a3a-96f9-%012d",operationCounter.incrementAndGet());operationIds.put(id,key);
                    return response(request,202,operation(id,key,action,"queued",null));
                }
                if(path.contains("/video/operations/")){
                    String id=path.substring(path.lastIndexOf('/')+1);String key=operationIds.get(id);String action=operationKeys.get(key);
                    if(key==null||action==null)throw new AssertionError("unknown operation id");
                    if(action.equals("initialize"))return response(request,200,operation(id,key,"initialize","succeeded",inspection("initialized","idle",1,0,"","","")));
                    if(action.equals("storyboard"))return response(request,200,operation(id,key,"storyboard","succeeded",inspection("awaiting_storyboard_review","awaiting_approval",3,1,reviewSha,irSha,"")));
                    return response(request,200,operation(id,key,"approve","succeeded",inspection("approved","approval_pending",4,1,reviewSha,irSha,"approval-ui")));
                }
                throw new AssertionError("unexpected video URL: "+path);
            };
            final AgentTaskDialogs[] dialogs={null};
            scenario=ActivityScenario.launch(MainActivity.class);
            awaitWindowFocus(scenario);
            scenario.onActivity(activity->{dialogs[0]=new AgentTaskDialogs(activity,connections,worker,null,taskStore,
                    (a,c,t,w)->new VideoTaskDialogs(a,c,t,w,videoStore[0]=new VideoOperationStore(a),new VideoTaskClient(transport)));dialogs[0].showDetail(created.clientTaskId);});
            Espresso.onView(ViewMatchers.withText("视频分镜")).inRoot(RootMatchers.isDialog()).perform(ViewActions.click());
            awaitEnabled("初始化视频任务");
            Espresso.onView(ViewMatchers.withText("初始化视频任务")).inRoot(RootMatchers.isDialog()).perform(ViewActions.click());
            awaitEnabled("生成分镜");
            Espresso.onView(ViewMatchers.withText("生成分镜")).inRoot(RootMatchers.isDialog()).perform(ViewActions.scrollTo(),ViewActions.click());
            Espresso.onView(ViewMatchers.withText("发送材料并生成分镜")).inRoot(RootMatchers.isDialog()).perform(ViewActions.click());
            try{waitForText("这是解说文字");}catch(AssertionError failure){logFailureEvidence(context,scenario,videoStore[0],created,calls,failure);throw failure;}
            awaitEnabled("批准第1版分镜");
            Espresso.onView(ViewMatchers.withText("批准第1版分镜")).inRoot(RootMatchers.isDialog()).perform(ViewActions.scrollTo(),ViewActions.click());
            waitUntil(()->state.get().equals("approve")&&calls.stream().filter(x->x.method.equals("POST")&&x.url.getPath().endsWith("/video/operations")).count()>=3);
            awaitApprovalCommitted(videoStore[0],created);
            JSONObject storyboardBody=null,approvalBody=null;for(AgentHttpTransport.Request request:calls)if(request.method.equals("POST")&&request.url.getPath().endsWith("/video/operations")){
                JSONObject sent=new JSONObject(new String(request.body,StandardCharsets.UTF_8));if("storyboard".equals(sent.getString("action")))storyboardBody=sent.getJSONObject("parameters");if("approve".equals(sent.getString("action")))approvalBody=sent.getJSONObject("parameters");}
            assertNotNull(storyboardBody);assertEquals(1,storyboardBody.getInt("revision"));assertEquals(1,storyboardBody.getInt("event_cursor"));
            assertNotNull(approvalBody);assertEquals(1,approvalBody.getInt("revision"));assertEquals(3,approvalBody.getInt("event_cursor"));
            assertEquals(reviewSha,approvalBody.getString("review_sha256"));assertEquals(irSha,approvalBody.getString("lesson_ir_sha256"));
            Espresso.onView(ViewMatchers.withText("批准当前分镜")).inRoot(RootMatchers.isDialog()).check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.GONE)));
            for(AgentHttpTransport.Request request:calls)assertTrue(request.url.getHost().equals("bridge.test"));
        }finally{
            worker.shutdownNow();if(scenario!=null)scenario.close();if(saved!=null)connections.delete(saved.id);deleteTree(taskDir);
        }
    }
    private interface Check{boolean ok();}
    private static void waitUntil(Check check)throws Exception{long end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){if(check.ok())return;Thread.sleep(50);}assertTrue("video UI action did not finish",check.ok());}
    private static void awaitApprovalCommitted(VideoOperationStore store,AgentTaskStore.Task task)throws Exception{waitUntil(()->{try{VideoOperationStore.Operation op=store.latest(task);JSONObject review=store.review(task);if(op==null||!"approve".equals(op.action)||!"succeeded".equals(op.status)||review==null||!"approved".equals(review.getString("status"))||review.getInt("event_cursor")!=4)return false;Espresso.onView(ViewMatchers.withText("批准当前分镜")).inRoot(RootMatchers.isDialog()).check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.GONE)));return true;}catch(Exception ignored){return false;}});}
    private static void awaitEnabled(String text)throws Exception{waitUntil(()->{try{Espresso.onView(ViewMatchers.withText(text)).inRoot(RootMatchers.isDialog()).check(ViewAssertions.matches(ViewMatchers.isEnabled()));return true;}catch(Exception ignored){return false;}});}
    private static void waitForText(String text)throws Exception{waitUntil(()->{try{Espresso.onView(ViewMatchers.withText(org.hamcrest.Matchers.containsString(text))).inRoot(RootMatchers.isDialog()).perform(ViewActions.scrollTo()).check(ViewAssertions.matches(ViewMatchers.isDisplayed()));return true;}catch(Exception ignored){return false;}});}
    private static void awaitWindowFocus(ActivityScenario<MainActivity> scenario){long end=android.os.SystemClock.uptimeMillis()+5000L;while(android.os.SystemClock.uptimeMillis()<end){AtomicReference<Boolean> focused=new AtomicReference<>(false);scenario.onActivity(a->focused.set(a.getWindow()!=null&&a.getWindow().getDecorView().hasWindowFocus()));if(focused.get())return;android.os.SystemClock.sleep(50L);}fail("MainActivity never received window focus before opening video dialogs");}
    private static void logFailureEvidence(Context context,ActivityScenario<MainActivity> scenario,VideoOperationStore store,AgentTaskStore.Task task,java.util.List<AgentHttpTransport.Request> calls,AssertionError failure){
        StringBuilder evidence=new StringBuilder("video review UI wait failed: ").append(failure).append('\n');
        try{if(store!=null)for(VideoOperationStore.Operation op:store.operations(task))evidence.append("operation=").append(op.action).append('/').append(op.status).append(" error=").append(op.error).append('\n');}catch(Exception e){evidence.append("operation_store=").append(e.getClass().getSimpleName()).append('\n');}
        synchronized(calls){for(AgentHttpTransport.Request request:calls)evidence.append("http=").append(request.method).append(' ').append(request.url.getPath()).append('\n');}
        try{UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();for(AccessibilityWindowInfo window:automation.getWindows()){
                evidence.append("window type=").append(window.getType()).append(" focused=").append(window.isFocused()).append(" title=").append(window.getTitle()).append('\n');
                appendVisibleTree(window.getRoot(),evidence,0,new int[]{0});}
            Bitmap screenshot=automation.takeScreenshot();if(screenshot!=null){File file=new File(context.getCacheDir(),"video-task-ui-failure.png");try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}evidence.append("screenshot=").append(file.getAbsolutePath()).append('\n');screenshot.recycle();}
        }catch(Exception e){evidence.append("accessibility_capture=").append(e.getClass().getSimpleName()).append('\n');}
        android.util.Log.e("VideoTaskDialogsTest",evidence.toString());
    }
    private static void appendVisibleTree(AccessibilityNodeInfo node,StringBuilder out,int depth,int[] count){if(node==null||depth>20||count[0]++>250||out.length()>16000)return;CharSequence text=node.getText(),description=node.getContentDescription();if((text!=null&&!text.toString().trim().isEmpty())||(description!=null&&!description.toString().trim().isEmpty())){out.append("  ");for(int i=0;i<Math.min(depth,20);i++)out.append(' ');out.append("view text=").append(text).append(" desc=").append(description).append(" shown=").append(node.isVisibleToUser()).append('\n');}for(int i=0;i<node.getChildCount();i++)appendVisibleTree(node.getChild(i),out,depth+1,count);}
    private static JSONObject diagnostics()throws Exception{JSONObject checks=new JSONObject();for(String k:new String[]{"worker_modules","storyboard_browser","render_browser","ffmpeg","ffprobe","tts"})checks.put(k,new JSONObject().put("status","available").put("reason","fixture"));return new JSONObject().put("schema_version","1.0").put("runtime_verified",false).put("video_ready",false).put("checks",checks);}
    private static JSONObject review(String worker,String status,String previewId,byte[] png,String sha,String reviewSha,String irSha)throws Exception{return new JSONObject().put("object","padnote.video.review").put("protocol_version",1).put("task_id","remote-video").put("worker_task_id",worker).put("status",status).put("event_cursor","approved".equals(status)?4:3).put("revision",1).put("review_sha256",reviewSha).put("lesson_ir_sha256",irSha).put("episode",new JSONObject().put("title","视频标题").put("audience","初学者").put("learning_goal","理解输入").put("language","zh-CN")).put("scenes",new JSONArray().put(new JSONObject().put("id","scene-1").put("learning_objective","学习输入").put("narration","这是解说文字").put("screen_text",new JSONArray().put("输入示例")).put("visual_kind","title").put("preview",new JSONObject().put("id",previewId).put("media_type","image/png").put("size_bytes",png.length).put("sha256",sha).put("width",1).put("height",1))));}
    private static JSONObject inspection(String status,String phase,int cursor,int revision,String review,String ir,String approval)throws Exception{JSONObject j=new JSONObject().put("protocol_version",1).put("task_id","remote-video").put("status",status).put("phase",phase).put("event_cursor",cursor);if(revision>0)j.put("revision",revision).put("review_sha256",review).put("lesson_ir_sha256",ir);if(!approval.isEmpty())j.put("approval",new JSONObject().put("approval_id",approval).put("revision",revision).put("review_sha256",review).put("lesson_ir_sha256",ir).put("granted_at","2026-09-27T00:00:00.000Z"));return j;}
    private static JSONObject operation(String id,String key,String action,String status,JSONObject result)throws Exception{return new JSONObject().put("object","padnote.video.operation").put("protocol_version",1).put("operation_id",id).put("task_id","remote-video").put("client_operation_id",key).put("action",action).put("status",status).put("created_at",1).put("updated_at",1).put("result",result==null?JSONObject.NULL:result).put("error",JSONObject.NULL);}
    private static AgentHttpTransport.Response response(AgentHttpTransport.Request request,int status,JSONObject body){return new AgentHttpTransport.Response(status,request.url,request.url,body.toString().getBytes(StandardCharsets.UTF_8),Collections.emptyMap());}
    private static String workerId(byte[] zip)throws Exception{java.util.zip.ZipInputStream in=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip));java.util.zip.ZipEntry e;while((e=in.getNextEntry())!=null)if("request.json".equals(e.getName())){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))>=0)out.write(b,0,n);return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)).getString("task_id");}throw new AssertionError();}
    private static String sha(byte[] b)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte x:d)s.append(String.format("%02x",x&255));return s.toString();}
    private static byte[] pngFixture()throws Exception{Bitmap bitmap=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);try{ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));return out.toByteArray();}finally{bitmap.recycle();}}
    private static String previewId(String reviewSha,String irSha)throws Exception{return sha(("{\"lesson_ir_sha256\":\""+irSha+"\",\"revision\":1,\"review_sha256\":\""+reviewSha+"\",\"scene_id\":\"scene-1\"}").getBytes(StandardCharsets.UTF_8));}
    private static String repeat(char c,int n){char[] a=new char[n];Arrays.fill(a,c);return new String(a);}
    private static void deleteTree(File f){if(f==null||!f.exists())return;File[] children=f.listFiles();if(children!=null)for(File c:children)deleteTree(c);f.delete();}
}
