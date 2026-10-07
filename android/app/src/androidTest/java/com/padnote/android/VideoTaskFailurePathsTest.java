package com.padnote.android;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
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
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Failure-path UI tests for durable video submissions and identity revocation. */
@RunWith(AndroidJUnit4.class)
public final class VideoTaskFailurePathsTest {
    @Test public void lostSubmissionResponseReopensAndRetriesTheExactSameKeyAndBody() throws Exception {
        Fixture f=new Fixture(ServerMode.LOSE_FIRST_RESPONSE);
        try {
            f.open();awaitEnabled("初始化视频任务");click("初始化视频任务");awaitEnabled("用同一幂等键重试提交");
            VideoOperationStore.Operation uncertain=f.videoStore[0].latest(f.task);
            assertEquals("submission_uncertain",uncertain.status);assertTrue(uncertain.operationId.isEmpty());
            assertEquals(1,f.server.postCount);assertEquals(1,f.server.acceptedExecutions);

            closeVideoDialog();f.openVideoAgain();awaitEnabled("用同一幂等键重试提交");
            assertEquals("submission_uncertain",f.videoStore[0].latest(f.task).status);
            assertEquals("reopening must not auto-submit",1,f.server.postCount);
            click("用同一幂等键重试提交");
            waitUntil(()->{try{VideoOperationStore.Operation op=f.videoStore[0].latest(f.task);return "succeeded".equals(op.status)&&!op.operationId.isEmpty();}catch(Exception ignored){return false;}});
            assertEquals(2,f.server.postCount);assertEquals(1,f.server.acceptedExecutions);
            assertEquals(f.server.firstKey,f.server.retriedKey);assertEquals(f.server.firstBody,f.server.retriedBody);
        } finally { f.close(); }
    }

    @Test public void remoteUnknownSurvivesRefreshAndReopenWithoutAnyNewPostOrRetryEntry() throws Exception {
        Fixture f=new Fixture(ServerMode.REMOTE_UNKNOWN);
        try {
            f.open();awaitEnabled("初始化视频任务");click("初始化视频任务");
            awaitText("执行结果尚未确认。可点“核对电脑端结果”读取电脑端保存记录；不会重跑。");
            assertEquals("unknown",f.videoStore[0].latest(f.task).status);
            assertGone("用同一幂等键重试提交");assertEquals(1,f.server.postCount);

            click("刷新状态与审阅");awaitText("执行结果尚未确认。可点“核对电脑端结果”读取电脑端保存记录；不会重跑。");
            closeVideoDialog();f.openVideoAgain();awaitText("执行结果尚未确认。可点“核对电脑端结果”读取电脑端保存记录；不会重跑。");
            assertGone("用同一幂等键重试提交");assertEquals("unknown must never be replayed",1,f.server.postCount);
        } finally { f.close(); }
    }

    @Test public void explicitReconciliationCanConfirmSuccessWithoutAnotherActionPost() throws Exception {
        Fixture f=new Fixture(ServerMode.RECONCILE_SUCCESS);
        try{
            f.open();awaitEnabled("初始化视频任务");click("初始化视频任务");awaitEnabled("核对电脑端结果");
            assertEquals("normal status refresh must not reconcile",0,f.server.reconcileCount);
            assertTrue("lose video action capability but preserve bound identity",
                    f.connections.applyProbeSuccess(f.connection.id,f.connection.revision,
                            new AgentConnectionClient.ProbeResult("verified","bridge-failure","instance-failure",Arrays.asList("task_bundle","run_status"))));
            click("刷新状态与审阅");awaitEnabled("核对电脑端结果");assertEquals(0,f.server.reconcileCount);
            click("核对电脑端结果");
            waitUntil(()->{try{return "succeeded".equals(f.videoStore[0].latest(f.task).status);}catch(Exception ignored){return false;}});
            assertEquals(1,f.server.reconcileCount);assertEquals(1,f.server.postCount);assertEquals(1,f.server.acceptedExecutions);
            assertEquals("succeeded",f.videoStore[0].latest(f.task).status);assertGone("核对电脑端结果");
        }finally{f.close();}
    }

    @Test public void normalGetMayObserveAnAlreadyConfirmedSuccessWithoutRunningReconcile() throws Exception {
        Fixture f=new Fixture(ServerMode.REMOTE_UNKNOWN);
        try{
            f.open();awaitEnabled("初始化视频任务");click("初始化视频任务");awaitEnabled("核对电脑端结果");
            f.server.confirmedOnGet=true;click("刷新状态与审阅");
            waitUntil(()->{try{return "succeeded".equals(f.videoStore[0].latest(f.task).status);}catch(Exception ignored){return false;}});
            assertEquals("GET must only observe the persisted status",0,f.server.reconcileCount);
            assertEquals(1,f.server.postCount);assertEquals(1,f.server.acceptedExecutions);awaitGone("核对电脑端结果");
        }finally{f.close();}
    }

    @Test public void reconciliationUnknownOrHttpFailureKeepsUnknownAndNeverReplays() throws Exception {
        for(ServerMode mode:new ServerMode[]{ServerMode.RECONCILE_UNKNOWN,ServerMode.RECONCILE_UNAVAILABLE}){
            Fixture f=new Fixture(mode);
            try{
                f.open();awaitEnabled("初始化视频任务");click("初始化视频任务");awaitEnabled("核对电脑端结果");
                click("核对电脑端结果");awaitEnabled("核对电脑端结果");
                assertEquals("unknown",f.videoStore[0].latest(f.task).status);
                assertEquals(1,f.server.reconcileCount);assertEquals(1,f.server.postCount);assertEquals(1,f.server.acceptedExecutions);
                assertGone("用同一幂等键重试提交");
            }finally{f.close();}
        }
    }

    @Test public void revocationDuringReconciliationRejectsTheLateResponse() throws Exception {
        Fixture f=new Fixture(ServerMode.RECONCILE_BLOCK_SUCCESS);
        try{
            f.open();awaitEnabled("初始化视频任务");click("初始化视频任务");awaitEnabled("核对电脑端结果");
            click("核对电脑端结果");assertTrue("reconcile request should be in flight",f.server.reconcileEntered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(f.connections.delete(f.connection.id));f.server.releaseReconcile.countDown();
            awaitText("连接已撤销或身份已变化");
            assertEquals("late response must not rewrite local unknown", "unknown",f.videoStore[0].latest(f.task).status);
            assertEquals(1,f.server.reconcileCount);assertEquals(1,f.server.postCount);assertEquals(1,f.server.acceptedExecutions);
        }finally{f.server.releaseReconcile.countDown();f.close();}
    }

    @Test public void dismissBeforeQueuedReconcileWorkerStartsSendsNoRequest() throws Exception {
        Fixture f=new Fixture(ServerMode.RECONCILE_SUCCESS);
        java.util.concurrent.CountDownLatch blockerStarted=new java.util.concurrent.CountDownLatch(1),releaseWorker=new java.util.concurrent.CountDownLatch(1);
        try{
            f.open();awaitEnabled("初始化视频任务");click("初始化视频任务");awaitEnabled("核对电脑端结果");
            f.worker.execute(()->{blockerStarted.countDown();try{releaseWorker.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
            assertTrue(blockerStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
            click("核对电脑端结果");closeVideoDialog();releaseWorker.countDown();
            java.util.concurrent.CountDownLatch workerIdle=new java.util.concurrent.CountDownLatch(1);f.worker.execute(workerIdle::countDown);
            assertTrue("worker should drain after the dialog closes",workerIdle.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,f.server.reconcileCount);assertEquals(1,f.server.postCount);assertEquals(1,f.server.acceptedExecutions);
            assertEquals("unknown",f.videoStore[0].latest(f.task).status);
        }finally{releaseWorker.countDown();f.close();}
    }

    @Test public void revokedConnectionAfterDisplayedReviewBlocksApprovalBeforePost() throws Exception {
        Fixture f=new Fixture(ServerMode.SUCCESS);
        try {
            f.open();awaitEnabled("初始化视频任务");click("初始化视频任务");awaitEnabled("生成分镜");
            startStoryboard();awaitText("这是解说文字");awaitEnabled("批准第1版分镜");
            assertEquals(2,f.server.postCount);
            assertTrue("fixture must revoke the exact bound connection",f.connections.delete(f.connection.id));

            click("批准第1版分镜");awaitText("已撤销或身份已变化");
            awaitText("这不会恢复旧任务绑定");awaitText("这是解说文字");awaitDisabled("检查电脑视频依赖");awaitDisabled("刷新状态与审阅");awaitDisabled("批准第1版分镜");
            assertEquals("no approval POST may escape after revocation",2,f.server.postCount);
            for(VideoOperationStore.Operation op:f.videoStore[0].operations(f.task))assertNotEquals("approve",op.action);
            assertEquals(2,f.server.acceptedExecutions);
            closeVideoDialog();f.openVideoAgain();awaitText("这是解说文字");awaitText("图片未缓存");
            awaitDisabled("初始化视频任务");awaitDisabled("批准当前分镜");assertEquals(2,f.server.postCount);
        } finally { f.close(); }
    }

    @Test public void queuedOperationCanBeCancelledButRunningOperationNeverShowsCancel() throws Exception {
        Fixture queued=new Fixture(ServerMode.QUEUED);
        try {
            queued.open();awaitEnabled("初始化视频任务");click("初始化视频任务");awaitEnabled("取消排队中的操作");
            assertEquals("queued",queued.videoStore[0].latest(queued.task).status);
            click("取消排队中的操作");
            waitUntil(()->{try{return "cancelled".equals(queued.videoStore[0].latest(queued.task).status);}catch(Exception ignored){return false;}});
            assertEquals(1,queued.server.cancelCount);assertEquals(1,queued.server.postCount);assertEquals(1,queued.server.acceptedExecutions);
            awaitText("已取消");assertGone("取消排队中的操作");
        } finally { queued.close(); }

        Fixture running=new Fixture(ServerMode.RUNNING);
        try {
            running.open();awaitEnabled("初始化视频任务");click("初始化视频任务");
            waitUntil(()->{try{return "running".equals(running.videoStore[0].latest(running.task).status);}catch(Exception ignored){return false;}});
            awaitText("执行中");assertGone("取消排队中的操作");assertEquals(0,running.server.cancelCount);assertEquals(1,running.server.postCount);
            assertEquals("running",running.videoStore[0].latest(running.task).status);
        } finally { running.close(); }
    }

    @Test public void queuedCancelLostResponseCanBeRecoveredByColdReopenGet() throws Exception {
        Fixture f=new Fixture(ServerMode.QUEUED);
        try{
            f.open();awaitEnabled("初始化视频任务");click("初始化视频任务");awaitEnabled("取消排队中的操作");
            f.server.loseQueuedCancelResponse=true;click("取消排队中的操作");
            assertTrue("server accepted cancellation before losing its response",f.server.queuedCancelEntered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            closeVideoDialog();f.server.releaseQueuedCancel.countDown();
            java.util.concurrent.CountDownLatch workerIdle=new java.util.concurrent.CountDownLatch(1);f.worker.execute(workerIdle::countDown);
            assertTrue("cancel request should finish before cold reopen",workerIdle.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("queued",f.videoStore[0].latest(f.task).status);
            f.openVideoAgain();awaitText("已取消");
            assertEquals("GET recovers accepted cancellation without replay", "cancelled",f.videoStore[0].latest(f.task).status);
            assertEquals(1,f.server.cancelCount);assertEquals(1,f.server.postCount);assertEquals(1,f.server.acceptedExecutions);
        }finally{f.server.releaseQueuedCancel.countDown();f.close();}
    }

    @Test public void runningStoryboardStopRequiresExplicitRequestAndReceiptReconciliation() throws Exception {
        Fixture f=new Fixture(ServerMode.RUNNING_STORYBOARD);
        try{
            f.open();awaitEnabled("初始化视频任务");click("初始化视频任务");awaitEnabled("生成分镜");
            startStoryboard();awaitEnabled("请求停止生成分镜");
            assertEquals(2,f.server.postCount);assertEquals("running",f.videoStore[0].latest(f.task).status);
            f.server.loseCancelBeforeAccept=true;click("请求停止生成分镜");
            waitUntil(()->{try{VideoOperationStore.Operation op=f.videoStore[0].latest(f.task);return op.cancelControl!=null&&"pending".equals(op.cancelControl.optString("status"));}catch(Exception ignored){return false;}});
            assertEquals(1,f.server.cancelRunningCount);assertEquals(2,f.server.postCount);assertEquals(2,f.server.acceptedExecutions);
            awaitEnabled("再次请求停止");
            closeVideoDialog();f.openVideoAgain();awaitEnabled("再次请求停止");
            assertEquals("reopen must not auto-POST",1,f.server.cancelRunningCount);
            click("再次请求停止");
            waitUntil(()->{try{VideoOperationStore.Operation op=f.videoStore[0].latest(f.task);return "unknown".equals(op.status)&&op.cancelControl!=null&&"unconfirmed".equals(op.cancelControl.optString("status"));}catch(Exception ignored){return false;}});
            assertEquals(2,f.server.cancelRunningCount);assertEquals(2,f.server.postCount);assertEquals(2,f.server.acceptedExecutions);
            awaitEnabled("查看停止状态");click("查看停止状态");
            waitUntil(()->{try{VideoOperationStore.Operation op=f.videoStore[0].latest(f.task);return "unknown".equals(op.status)&&op.cancelControl!=null&&"unconfirmed".equals(op.cancelControl.optString("status"));}catch(Exception ignored){return false;}});
            awaitEnabled("核对电脑端停止或操作结果");click("核对电脑端停止或操作结果");
            waitUntil(()->{try{return "cancelled".equals(f.videoStore[0].latest(f.task).status);}catch(Exception ignored){return false;}});
            assertEquals("cancelled",f.videoStore[0].latest(f.task).status);
            assertEquals("verified_cancelled",f.videoStore[0].latest(f.task).cancelControl.getString("status"));
            assertEquals("stop must not create or replay a new action",2,f.server.postCount);
            assertEquals(2,f.server.cancelRunningCount);assertEquals(1,f.server.reconcileCount);assertEquals(2,f.server.acceptedExecutions);
            awaitText("电脑端已核实分镜停止");
            awaitGone("请求停止生成分镜");
            awaitGone("生成分镜");
            awaitGone("批准当前分镜");
            awaitText("请返回笔记新建视频任务继续");
            closeVideoDialog();f.openVideoAgain();awaitText("请返回笔记新建视频任务继续");
            assertGone("生成分镜");
            assertGone("批准当前分镜");
            assertEquals("reopen must retain the terminal safety gate",2,f.server.postCount);
        }finally{f.close();}
    }

    @Test public void dismissBeforeQueuedRunningStopRetryStartsSendsNoPost() throws Exception {
        Fixture f=new Fixture(ServerMode.RUNNING_STORYBOARD);
        java.util.concurrent.CountDownLatch blockerStarted=new java.util.concurrent.CountDownLatch(1),releaseWorker=new java.util.concurrent.CountDownLatch(1);
        try{
            f.open();awaitEnabled("初始化视频任务");click("初始化视频任务");awaitEnabled("生成分镜");
            startStoryboard();awaitEnabled("请求停止生成分镜");
            f.server.loseCancelBeforeAccept=true;click("请求停止生成分镜");
            waitUntil(()->{try{VideoOperationStore.Operation op=f.videoStore[0].latest(f.task);return op.cancelControl!=null&&"pending".equals(op.cancelControl.optString("status"));}catch(Exception ignored){return false;}});
            awaitEnabled("再次请求停止");assertEquals(1,f.server.cancelRunningCount);

            f.worker.execute(()->{blockerStarted.countDown();try{releaseWorker.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
            assertTrue("worker blocker should start",blockerStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
            click("再次请求停止");closeVideoDialog();releaseWorker.countDown();
            java.util.concurrent.CountDownLatch workerIdle=new java.util.concurrent.CountDownLatch(1);f.worker.execute(workerIdle::countDown);
            assertTrue("queued retry worker should drain after dismissal",workerIdle.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("dismissal before queued retry starts must suppress POST",1,f.server.cancelRunningCount);
            assertEquals("stop retry must not create/replay an action",2,f.server.postCount);
            VideoOperationStore.Operation saved=f.videoStore[0].latest(f.task);
            assertEquals("running",saved.status);assertEquals("pending",saved.cancelControl.getString("status"));
        }finally{releaseWorker.countDown();f.close();}
    }

    @Test public void cachedReviewCannotBeApprovedWhenFreshReviewCheckFailsEvenIfPreviewRemainsAvailable() throws Exception {
        Fixture f=new Fixture(ServerMode.SUCCESS);
        try {
            f.open();awaitEnabled("初始化视频任务");click("初始化视频任务");awaitEnabled("生成分镜");
            startStoryboard();awaitEnabled("批准第1版分镜");
            assertEquals(1,f.server.previewCount);int posts=f.server.postCount;
            f.server.failNextReview=true;click("刷新状态与审阅");
            awaitText("本地缓存审阅，未取得电脑端最新版本，不能批准");assertGone("批准当前分镜");
            assertEquals(posts,f.server.postCount);assertEquals("stale review must not trigger a preview fetch",1,f.server.previewCount);
        } finally { f.close(); }
    }

    private enum ServerMode { LOSE_FIRST_RESPONSE, REMOTE_UNKNOWN, RECONCILE_SUCCESS, RECONCILE_UNKNOWN,
        RECONCILE_UNAVAILABLE, RECONCILE_BLOCK_SUCCESS, SUCCESS, QUEUED, RUNNING, RUNNING_STORYBOARD }

    private static final class Fixture {
        final android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final AgentConnectionStore connections=new AgentConnectionStore(context);
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final File taskDirectory=new File(context.getCacheDir(),"video-failure-task-"+System.nanoTime());
        final java.util.List<AgentHttpTransport.Request> requests=Collections.synchronizedList(new java.util.ArrayList<>());
        final Server server;final AgentTaskStore taskStore;final AgentTaskStore.Task task;
        final AgentConnectionStore.Config connection;final VideoOperationStore[] videoStore={null};
        ActivityScenario<MainActivity> scenario;
        Fixture(ServerMode mode)throws Exception {
            connections.clear();AgentConnectionStore.Config added=connections.addBridge("Failure-path Bridge",AgentConnectionStore.Kind.HERMES,
                    "https://bridge.test","bridge-failure","instance-failure","synthetic-token");
            assertTrue(connections.applyProbeSuccess(added.id,added.revision,new AgentConnectionClient.ProbeResult(
                    "verified","bridge-failure","instance-failure",Arrays.asList("task_bundle","run_status","video_operations"))));
            connection=connections.get(added.id);
            ByteArrayOutputStream zip=new ByteArrayOutputStream();VideoTaskBundleIO.write(zip,"note-video-failure",1,"视频故障路径","合成材料","初学者","理解输入",60,"voice",1f);
            taskStore=new AgentTaskStore(taskDirectory);AgentTaskStore.Task local=taskStore.create(connection,"视频故障路径","合成任务","note-video-failure",1,zip.toByteArray());
            assertTrue(taskStore.applySubmission(local.clientTaskId,connection.id,connection.revision,"remote-failure",AgentTaskStore.Status.COMPLETED));
            task=taskStore.get(local.clientTaskId);server=new Server(mode,task);
        }
        void open(){
            scenario=ActivityScenario.launch(MainActivity.class);awaitWindowFocus(scenario);
            scenario.onActivity(activity->new AgentTaskDialogs(activity,connections,worker,null,taskStore,
                    (a,c,t,w)->new VideoTaskDialogs(a,c,t,w,videoStore[0]=new VideoOperationStore(a),new VideoTaskClient(request->{requests.add(request);return server.execute(request);})))
                    .showDetail(task.clientTaskId));
            click("视频分镜");
        }
        void openVideoAgain(){click("视频分镜");}
        void close(){
            if(scenario!=null){try{closeVideoDialog();}catch(Exception ignored){}scenario.close();}worker.shutdownNow();connections.delete(connection.id);deleteTree(taskDirectory);
        }
    }

    /** Idempotent server fixture records acceptance before simulating a lost response. */
    private static final class Server {
        final ServerMode mode;final AgentTaskStore.Task task;final Map<String,Accepted> byKey=new HashMap<>();final Map<String,Accepted> byOperationId=new HashMap<>();
        final byte[] png;final String pngSha,reviewSha=repeat('b',64),irSha=repeat('c',64),previewId;
        volatile int postCount,acceptedExecutions,cancelCount,cancelRunningCount,cancelStatusGetCount,previewCount,reconcileCount;volatile boolean failNextReview,confirmedOnGet,stopRequested,cancelVerified,loseCancelBeforeAccept,loseQueuedCancelResponse,queuedCancelAccepted;private int nextId;private long stopRequestedAt,stopUpdatedAt;volatile String firstKey="",retriedKey="",firstBody="",retriedBody="";private String lastAction="";
        final java.util.concurrent.CountDownLatch reconcileEntered=new java.util.concurrent.CountDownLatch(1),releaseReconcile=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch queuedCancelEntered=new java.util.concurrent.CountDownLatch(1),releaseQueuedCancel=new java.util.concurrent.CountDownLatch(1);
        Server(ServerMode mode,AgentTaskStore.Task task)throws Exception{this.mode=mode;this.task=task;png=pngFixture();pngSha=sha(png);previewId=previewId(reviewSha,irSha);}
        synchronized AgentHttpTransport.Response execute(AgentHttpTransport.Request request)throws Exception{
            String path=request.url.getPath();String agent="/padnote/v1/agents/instance-failure",run=agent+"/runs/remote-failure",operations=run+"/video/operations";
            assertEquals("bridge.test",request.url.getHost());assertEquals("https",request.url.getProtocol());assertEquals("synthetic-token",request.bearer);
            if(path.equals(agent+"/video/diagnostics")){assertEquals("GET",request.method);return response(request,200,diagnostics());}
            if(path.equals(run+"/video/review")){assertEquals("GET",request.method);if(failNextReview){failNextReview=false;return response(request,503,new JSONObject().put("error","unavailable"));}
                if(!"storyboard".equals(lastAction)&&!"approve".equals(lastAction))return response(request,409,new JSONObject().put("error","review_unavailable"));
                return response(request,200,review(workerId(task),"approve".equals(lastAction)?"approved":"awaiting_storyboard_review",png,pngSha));
            }
            if(path.equals(run+"/video/previews/"+previewId)){assertEquals("GET",request.method);previewCount++;return new AgentHttpTransport.Response(200,request.url,request.url,png,
                    Map.of("Content-Type",Collections.singletonList("image/png"),"Content-Length",Collections.singletonList(String.valueOf(png.length))));
            }
            if("POST".equals(request.method)&&path.matches(java.util.regex.Pattern.quote(operations)+"/[0-9a-f-]{36}/cancel")){
                cancelCount++;String id=path.substring(operations.length()+1,path.length()-"/cancel".length());Accepted accepted=byOperationId.get(id);
                assertNotNull("cancel must address the accepted operation",accepted);assertEquals(0,new JSONObject(new String(request.body,StandardCharsets.UTF_8)).length());
                if(mode==ServerMode.RUNNING)return response(request,409,new JSONObject().put("error","operation_running"));
                if(mode==ServerMode.QUEUED){queuedCancelAccepted=true;if(loseQueuedCancelResponse){loseQueuedCancelResponse=false;queuedCancelEntered.countDown();assertTrue(releaseQueuedCancel.await(5,java.util.concurrent.TimeUnit.SECONDS));throw new IOException("simulated accepted cancel response loss");}}
                return response(request,200,operation(accepted,"cancelled",null,"cancelled"));
            }
            if(path.matches(java.util.regex.Pattern.quote(operations)+"/[0-9a-f-]{36}/cancel-running")){
                String id=path.substring(operations.length()+1,path.length()-"/cancel-running".length());Accepted accepted=byOperationId.get(id);assertNotNull(accepted);
                if("POST".equals(request.method)){cancelRunningCount++;assertEquals("{}",new String(request.body,StandardCharsets.UTF_8));if(loseCancelBeforeAccept){loseCancelBeforeAccept=false;throw new IOException("simulated request loss before Bridge acceptance");}stopRequested=true;if(stopRequestedAt==0)stopRequestedAt=System.currentTimeMillis()/1000L;stopUpdatedAt=Math.max(stopRequestedAt,Math.max(stopUpdatedAt,System.currentTimeMillis()/1000L));return response(request,200,cancelRequest(accepted,"requested",stopRequestedAt,stopUpdatedAt));}
                if("GET".equals(request.method)){cancelStatusGetCount++;if(!stopRequested)return response(request,409,new JSONObject().put("error","cancel_request_missing"));stopUpdatedAt=Math.max(stopUpdatedAt+1,System.currentTimeMillis()/1000L);return response(request,200,cancelRequest(accepted,"unconfirmed",stopRequestedAt,stopUpdatedAt));}
            }
            if("POST".equals(request.method)&&path.matches(java.util.regex.Pattern.quote(operations)+"/[0-9a-f-]{36}/reconcile")){
                reconcileCount++;String id=path.substring(operations.length()+1,path.length()-"/reconcile".length());Accepted accepted=byOperationId.get(id);
                assertNotNull("reconcile must use the original operation id",accepted);assertEquals("{}",new String(request.body,StandardCharsets.UTF_8));
                if(mode==ServerMode.RECONCILE_BLOCK_SUCCESS){reconcileEntered.countDown();assertTrue(releaseReconcile.await(5,java.util.concurrent.TimeUnit.SECONDS));}
                if(mode==ServerMode.RECONCILE_UNAVAILABLE)return response(request,503,new JSONObject().put("error","unavailable"));
                if(mode==ServerMode.RUNNING_STORYBOARD&&stopRequested){cancelVerified=true;return response(request,200,operation(accepted,"cancelled",null,"cancelled"));}
                if(mode==ServerMode.RECONCILE_UNKNOWN)return response(request,200,operation(accepted,"unknown",null,"worker_unknown"));
                return response(request,200,operation(accepted,"succeeded",inspection(accepted),null));
            }
            if("POST".equals(request.method)&&path.equals(operations))return submit(request);
            if("GET".equals(request.method)&&path.startsWith(operations+"/")){
                String id=path.substring(path.lastIndexOf('/')+1);Accepted accepted=byOperationId.get(id);if(accepted==null)throw new AssertionError("unknown operation ID");
                if((mode==ServerMode.REMOTE_UNKNOWN||mode==ServerMode.RECONCILE_SUCCESS||mode==ServerMode.RECONCILE_UNKNOWN||mode==ServerMode.RECONCILE_UNAVAILABLE||mode==ServerMode.RECONCILE_BLOCK_SUCCESS)&&!confirmedOnGet)return response(request,200,operation(accepted,"unknown",null,"worker_interrupted"));
                if(mode==ServerMode.QUEUED)return queuedCancelAccepted?response(request,200,operation(accepted,"cancelled",null,"cancelled")):response(request,200,operation(accepted,"queued",null,null));
                if(mode==ServerMode.RUNNING)return response(request,200,operation(accepted,"running",null,null));
                if(mode==ServerMode.RUNNING_STORYBOARD){if("initialize".equals(accepted.action))return response(request,200,operation(accepted,"succeeded",inspection(accepted),null));if(cancelVerified)return response(request,200,operation(accepted,"cancelled",null,"cancelled"));if(stopRequested)return response(request,200,operation(accepted,"unknown",null,"worker_interrupted"));return response(request,200,operation(accepted,"running",null,null));}
                return response(request,200,operation(accepted,"succeeded",inspection(accepted),null));
            }
            throw new AssertionError("unexpected video route "+request.method+" "+path);
        }
        private AgentHttpTransport.Response submit(AgentHttpTransport.Request request)throws Exception{
            postCount++;JSONObject body=new JSONObject(new String(request.body,StandardCharsets.UTF_8));String key=request.headers.get("Idempotency-Key");
            String action=body.getString("action"),canonical=body.toString();assertNotNull(key);assertTrue(key.matches("[0-9a-f-]{36}"));JSONObject params=body.getJSONObject("parameters");
            if("initialize".equals(action))assertEquals(0,params.length());
            else if("storyboard".equals(action)){assertEquals(2,params.length());assertEquals(1,params.getInt("revision"));assertEquals(1,params.getInt("event_cursor"));}
            else throw new AssertionError("unexpected operation action: "+action);
            Accepted accepted=byKey.get(key);
            if(accepted==null){accepted=new Accepted(key,action,canonical,body.getJSONObject("parameters"),operationId());byKey.put(key,accepted);byOperationId.put(accepted.operationId,accepted);acceptedExecutions++;lastAction=action;
                firstKey=key;firstBody=canonical;
                if(mode==ServerMode.LOSE_FIRST_RESPONSE&&postCount==1)throw new IOException("simulated connection loss after durable acceptance");
            }else{assertEquals("retry must reuse exact action",accepted.action,action);assertEquals("retry must reuse exact parameters/body",accepted.body,canonical);retriedKey=key;retriedBody=canonical;}
            if(mode==ServerMode.RUNNING_STORYBOARD&&"initialize".equals(action))return response(request,202,operation(accepted,"succeeded",inspection(accepted),null));
            return response(request,202,operation(accepted,"queued",null,null));
        }
        private JSONObject inspection(Accepted accepted)throws Exception{
            if("initialize".equals(accepted.action))return new JSONObject().put("protocol_version",1).put("task_id",task.remoteTaskId).put("status","initialized").put("phase","idle").put("event_cursor",1);
            if("storyboard".equals(accepted.action))return new JSONObject().put("protocol_version",1).put("task_id",task.remoteTaskId).put("status","awaiting_storyboard_review").put("phase","awaiting_approval").put("event_cursor",3).put("revision",1).put("review_sha256",reviewSha).put("lesson_ir_sha256",irSha);
            return new JSONObject().put("protocol_version",1).put("task_id",task.remoteTaskId).put("status","approved").put("phase","approval_pending").put("event_cursor",4).put("revision",1).put("review_sha256",reviewSha).put("lesson_ir_sha256",irSha)
                    .put("approval",new JSONObject().put("approval_id","approval-failure-test").put("revision",1).put("review_sha256",reviewSha).put("lesson_ir_sha256",irSha).put("granted_at","2026-09-27T00:00:00.000Z"));
        }
        private String operationId(){nextId++;return String.format(java.util.Locale.ROOT,"2a1c4042-92d7-4f67-a387-%012d",nextId);}
    }
    private static final class Accepted {final String key,action,body,operationId;final JSONObject parameters;Accepted(String key,String action,String body,JSONObject parameters,String id){this.key=key;this.action=action;this.body=body;this.parameters=parameters;this.operationId=id;}}

    private static JSONObject diagnostics()throws Exception{JSONObject checks=new JSONObject();for(String key:new String[]{"worker_modules","storyboard_browser","render_browser","ffmpeg","ffprobe","tts"})checks.put(key,new JSONObject().put("status","available").put("reason","fixture"));return new JSONObject().put("schema_version","1.0").put("runtime_verified",false).put("video_ready",false).put("checks",checks);}
    private static JSONObject review(String worker,String status,byte[] png,String pngSha)throws Exception{String reviewSha=repeat('b',64),irSha=repeat('c',64);JSONObject scene=new JSONObject().put("id","scene-1").put("learning_objective","理解输入").put("narration","这是解说文字").put("screen_text",new JSONArray().put("输入示例")).put("visual_kind","title");JSONObject review=new JSONObject().put("object","padnote.video.review").put("protocol_version",1).put("task_id","remote-failure").put("worker_task_id",worker).put("status",status).put("event_cursor","approved".equals(status)?4:3).put("revision",1).put("review_sha256",reviewSha).put("lesson_ir_sha256",irSha).put("episode",new JSONObject().put("title","视频标题").put("audience","初学者").put("learning_goal","理解输入").put("language","zh-CN"));String preview=previewId(reviewSha,irSha);scene.put("preview",new JSONObject().put("id",preview).put("media_type","image/png").put("size_bytes",png.length).put("sha256",pngSha).put("width",1).put("height",1));review.put("scenes",new JSONArray().put(scene));return review;}
    private static JSONObject operation(Accepted accepted,String status,JSONObject result,String error)throws Exception{return new JSONObject().put("object","padnote.video.operation").put("protocol_version",1).put("operation_id",accepted.operationId).put("task_id","remote-failure").put("client_operation_id",accepted.key).put("action",accepted.action).put("status",status).put("created_at",1).put("updated_at",1).put("result",result==null?JSONObject.NULL:result).put("error",error==null?JSONObject.NULL:error);}
    private static JSONObject cancelRequest(Accepted accepted,String status,long requestedAt,long updatedAt)throws Exception{return new JSONObject().put("object","padnote.video.cancel_request").put("protocol_version",1).put("operation_id",accepted.operationId).put("task_id","remote-failure").put("status",status).put("requested_at",requestedAt).put("updated_at",updatedAt);}
    private static AgentHttpTransport.Response response(AgentHttpTransport.Request request,int status,JSONObject json){return new AgentHttpTransport.Response(status,request.url,request.url,json.toString().getBytes(StandardCharsets.UTF_8),Collections.emptyMap());}
    private static String workerId(AgentTaskStore.Task task)throws Exception{JSONObject submission=new JSONObject(task.submissionJson);byte[] zip=Base64Compat.decode(submission.getString("bundle_base64"));java.util.zip.ZipInputStream in=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip));java.util.zip.ZipEntry entry;while((entry=in.getNextEntry())!=null)if("request.json".equals(entry.getName())){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))>=0)out.write(buffer,0,n);return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)).getString("task_id");}throw new AssertionError("request.json missing");}
    private static final class Base64Compat {static byte[] decode(String text){return android.util.Base64.decode(text,android.util.Base64.NO_WRAP);}}
    private static byte[] pngFixture()throws Exception{Bitmap bitmap=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);try{ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));return out.toByteArray();}finally{bitmap.recycle();}}
    private static String previewId(String reviewSha,String irSha)throws Exception{return sha(("{\"lesson_ir_sha256\":\""+irSha+"\",\"revision\":1,\"review_sha256\":\""+reviewSha+"\",\"scene_id\":\"scene-1\"}").getBytes(StandardCharsets.UTF_8));}
    private static String sha(byte[] bytes)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder();for(byte value:digest)result.append(String.format("%02x",value&255));return result.toString();}
    private static String repeat(char c,int count){char[] chars=new char[count];Arrays.fill(chars,c);return new String(chars);}
    private static void click(String text){Espresso.onView(ViewMatchers.withText(text)).inRoot(RootMatchers.isDialog()).perform(ViewActions.scrollTo(),ViewActions.click());}
    private static void startStoryboard(){click("生成分镜");click("发送材料并生成分镜");}
    private static void awaitEnabled(String text)throws Exception{waitUntil(()->{try{Espresso.onView(ViewMatchers.withText(text)).inRoot(RootMatchers.isDialog()).perform(ViewActions.scrollTo()).check(ViewAssertions.matches(ViewMatchers.isDisplayed())).check(ViewAssertions.matches(ViewMatchers.isEnabled()));return true;}catch(Exception ignored){return false;}});}
    private static void awaitGone(String text)throws Exception{waitUntil(()->{try{Espresso.onView(ViewMatchers.withText(text)).inRoot(RootMatchers.isDialog()).check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.GONE)));return true;}catch(Exception ignored){return false;}});}
    private static void awaitDisabled(String text)throws Exception{waitUntil(()->{try{Espresso.onView(ViewMatchers.withText(text)).inRoot(RootMatchers.isDialog()).perform(ViewActions.scrollTo()).check(ViewAssertions.matches(ViewMatchers.isDisplayed())).check(ViewAssertions.matches(ViewMatchers.isNotEnabled()));return true;}catch(Exception ignored){return false;}});}
    private static void awaitText(String text)throws Exception{waitUntil(()->{try{Espresso.onView(ViewMatchers.withText(org.hamcrest.Matchers.containsString(text))).inRoot(RootMatchers.isDialog()).perform(ViewActions.scrollTo()).check(ViewAssertions.matches(ViewMatchers.isDisplayed()));return true;}catch(Exception ignored){return false;}});}
    private static void assertGone(String text){Espresso.onView(ViewMatchers.withText(text)).inRoot(RootMatchers.isDialog()).check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.GONE)));}
    private static void closeVideoDialog(){Espresso.onView(ViewMatchers.withText("关闭")).inRoot(RootMatchers.isDialog()).perform(ViewActions.click());}
    private interface Check{boolean ok();}
    private static void waitUntil(Check check)throws Exception{long end=System.currentTimeMillis()+10000L;while(System.currentTimeMillis()<end){if(check.ok())return;Thread.sleep(50L);}assertTrue("video failure-path condition did not settle",check.ok());}
    private static void awaitWindowFocus(ActivityScenario<MainActivity> scenario){long end=android.os.SystemClock.uptimeMillis()+5000L;while(android.os.SystemClock.uptimeMillis()<end){AtomicReference<Boolean> focused=new AtomicReference<>(false);scenario.onActivity(a->focused.set(a.getWindow()!=null&&a.getWindow().getDecorView().hasWindowFocus()));if(focused.get())return;android.os.SystemClock.sleep(50L);}fail("MainActivity did not receive window focus");}
    private static void deleteTree(File file){if(file==null||!file.exists())return;File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);file.delete();}
}
