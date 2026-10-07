package com.padnote.android;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class VideoTaskProtocolTest {
    @Test public void diagnosticsUsesAgentRouteAndFalseVideoReadinessDoesNotMeanBroken() throws Exception {
        AgentTaskStore.Task task=videoTask();AgentConnectionStore.Config connection=config();
        AtomicReference<AgentHttpTransport.Request> seen=new AtomicReference<>();
        AgentHttpTransport client=request->{
            seen.set(request);
            assertEquals("GET",request.method);
            assertEquals("https://bridge.test/padnote/v1/agents/instance-a/video/diagnostics",request.url.toExternalForm());
            assertEquals("Bearer-token",request.bearer);
            return response(request.url,200,diagnostics().toString());
        };
        JSONObject result=new VideoTaskClient(client).diagnostics(task,connection);
        assertFalse(result.getBoolean("runtime_verified"));assertFalse(result.getBoolean("video_ready"));
        assertEquals("",seen.get().certSha256);
    }

    @Test public void hermesAndBuiltinVideoBridgeIdentityPreservesTheirPinRules() throws Exception {
        String pin=repeat('a',64);
        assertDiagnosticsAccepted(videoTask(AgentConnectionStore.Kind.HERMES),config(AgentConnectionStore.Kind.HERMES,pin),pin);
        assertDiagnosticsAccepted(videoTask(AgentConnectionStore.Kind.BUILTIN_VIDEO),config(AgentConnectionStore.Kind.BUILTIN_VIDEO,pin),pin);
        assertIdentityRejected(videoTask(AgentConnectionStore.Kind.BUILTIN_VIDEO),config(AgentConnectionStore.Kind.BUILTIN_VIDEO,""));
        assertIdentityRejected(videoTask(AgentConnectionStore.Kind.HERMES),config(AgentConnectionStore.Kind.HERMES,"bad-pin"));
        assertIdentityRejected(videoTask(AgentConnectionStore.Kind.HERMES),config(AgentConnectionStore.Kind.OPENCLAW,""));
    }

    private static void assertDiagnosticsAccepted(AgentTaskStore.Task task,AgentConnectionStore.Config connection,
                                                  String expectedPin)throws Exception{
        AtomicReference<AgentHttpTransport.Request> seen=new AtomicReference<>();
        JSONObject response=new VideoTaskClient(request->{seen.set(request);return response(request.url,200,diagnostics().toString());})
                .diagnostics(task,connection);
        assertFalse(response.getBoolean("runtime_verified"));
        assertEquals(expectedPin,seen.get().certSha256);
    }

    private static void assertIdentityRejected(AgentTaskStore.Task task,AgentConnectionStore.Config connection)throws Exception{
        AtomicReference<Boolean> called=new AtomicReference<>(false);
        try{
            new VideoTaskClient(request->{called.set(true);return response(request.url,200,diagnostics().toString());})
                    .diagnostics(task,connection);
            fail("invalid Bridge video identity must be rejected");
        }catch(IllegalStateException expected){}
        assertFalse("invalid identity must be rejected before network I/O",called.get());
    }

    @Test public void initializeSubmissionPersistsAndSendsSameIdempotencyKeyAndStrictBinding() throws Exception {
        AgentTaskStore.Task task=videoTask();AgentConnectionStore.Config connection=config();
        VideoOperationStore.Operation operation=new VideoOperationStore.Operation("initialize","key-uuid","key-uuid","",
                "prepared","",new JSONObject(),null,false,10,10);
        AtomicReference<AgentHttpTransport.Request> requestSeen=new AtomicReference<>();
        AgentHttpTransport client=request->{requestSeen.set(request);return response(request.url,202,
                new JSONObject().put("object","padnote.video.operation").put("protocol_version",1)
                        .put("operation_id","18ed9804-fbdb-4a3a-96f9-05d56e4b4e52").put("task_id","remote-a")
                        .put("client_operation_id","key-uuid").put("action","initialize").put("status","queued")
                        .put("created_at",10).put("updated_at",10).put("result",JSONObject.NULL).put("error",JSONObject.NULL).toString());};
        JSONObject result=new VideoTaskClient(client).submit(task,connection,operation);
        assertEquals("queued",result.getString("status"));
        assertEquals("key-uuid",requestSeen.get().headers.get("Idempotency-Key"));
        JSONObject body=new JSONObject(new String(requestSeen.get().body,StandardCharsets.UTF_8));
        assertEquals("initialize",body.getString("action"));assertEquals(0,body.getJSONObject("parameters").length());
    }

    @Test public void produceSubmitsApprovedCursorAndStrictBooleanConsentAndBindsReceipt() throws Exception {
        AgentTaskStore.Task task=videoTask();AgentConnectionStore.Config connection=config();
        String key="2c2c2c2c-2c2c-42c2-82c2-2c2c2c2c2c2c";
        String operationId="18ed9804-fbdb-4a3a-96f9-05d56e4b4e52";
        String reviewHash=repeat('b',64),lessonHash=repeat('c',64);
        JSONObject parameters=new JSONObject().put("revision",1).put("event_cursor",4)
                .put("review_sha256",reviewHash).put("lesson_ir_sha256",lessonHash).put("allow_cloud_tts",true);
        VideoOperationStore.Operation operation=new VideoOperationStore.Operation("produce",key,key,"",
                "prepared","",parameters,null,false,10,10);
        JSONObject receipt=new JSONObject().put("operation_id",operationId)
                .put("attempt_id","33333333-3333-4333-8333-333333333333").put("action","produce")
                .put("payload_digest",repeat('d',64)).put("source_snapshot_digest",repeat('e',64))
                .put("request_sha256",repeat('f',64)).put("input_event_cursor",4).put("result_event_cursor",10)
                .put("revision",1).put("review_sha256",reviewHash).put("lesson_ir_sha256",lessonHash)
                .put("allow_cloud_tts",true);
        JSONObject resultBody=new JSONObject().put("protocol_version",1).put("task_id","remote-a")
                .put("status","completed").put("phase","completed").put("event_cursor",10).put("revision",1)
                .put("review_sha256",reviewHash).put("lesson_ir_sha256",lessonHash).put("receipt",receipt);
        AtomicReference<AgentHttpTransport.Request> seen=new AtomicReference<>();
        AgentHttpTransport valid=request->{seen.set(request);return response(request.url,202,operation(operation,"succeeded",resultBody,null).put("operation_id",operationId).toString());};
        JSONObject succeeded=new VideoTaskClient(valid).submit(task,connection,operation);
        assertEquals("succeeded",succeeded.getString("status"));
        JSONObject posted=new JSONObject(new String(seen.get().body,StandardCharsets.UTF_8));
        assertEquals("produce",posted.getString("action"));
        assertEquals(1,posted.getJSONObject("parameters").getInt("revision"));
        assertEquals(4,posted.getJSONObject("parameters").getInt("event_cursor"));
        assertEquals(reviewHash,posted.getJSONObject("parameters").getString("review_sha256"));
        assertEquals(lessonHash,posted.getJSONObject("parameters").getString("lesson_ir_sha256"));
        assertTrue(posted.getJSONObject("parameters").get("allow_cloud_tts") instanceof Boolean);
        assertTrue(posted.getJSONObject("parameters").getBoolean("allow_cloud_tts"));

        JSONObject malformedReceipt=new JSONObject(receipt.toString()).put("input_event_cursor",5);
        JSONObject malformedResult=new JSONObject(resultBody.toString()).put("receipt",malformedReceipt);
        AgentHttpTransport wrong=request->response(request.url,202,operation(operation,"succeeded",malformedResult,null).put("operation_id",operationId).toString());
        try{new VideoTaskClient(wrong).submit(task,connection,operation);fail("a receipt with a different approval cursor must not succeed");}
        catch(IllegalStateException expected){}
        malformedReceipt=new JSONObject(receipt.toString()).put("allow_cloud_tts","true");
        JSONObject wrongConsent=new JSONObject(resultBody.toString()).put("receipt",malformedReceipt);
        AgentHttpTransport stringBoolean=request->response(request.url,202,operation(operation,"succeeded",wrongConsent,null).put("operation_id",operationId).toString());
        try{new VideoTaskClient(stringBoolean).submit(task,connection,operation);fail("a string must not satisfy cloud TTS consent");}
        catch(IllegalStateException expected){}
    }

    @Test public void explicitReconcilePostsEmptyBodyToExactOperationAndValidatesResponse() throws Exception {
        AgentTaskStore.Task task=videoTask();AgentConnectionStore.Config connection=config();
        VideoOperationStore.Operation unknown=new VideoOperationStore.Operation("storyboard",
                "2c2c2c2c-2c2c-42c2-82c2-2c2c2c2c2c2c","2c2c2c2c-2c2c-42c2-82c2-2c2c2c2c2c2c",
                "18ed9804-fbdb-4a3a-96f9-05d56e4b4e52","unknown","worker_unknown",
                new JSONObject().put("revision",1).put("event_cursor",1),null,false,10,11);
        AtomicReference<AgentHttpTransport.Request> seen=new AtomicReference<>();
        AgentHttpTransport transport=request->{seen.set(request);return response(request.url,200,
                operation(unknown,"succeeded",inspection("awaiting_storyboard_review","awaiting_approval",3,1,
                        repeat('b',64),repeat('c',64),""),null).toString());};
        JSONObject confirmed=new VideoTaskClient(transport).reconcile(task,connection,unknown);
        assertEquals("succeeded",confirmed.getString("status"));
        assertEquals("POST",seen.get().method);
        assertEquals("/padnote/v1/agents/instance-a/runs/remote-a/video/operations/18ed9804-fbdb-4a3a-96f9-05d56e4b4e52/reconcile",seen.get().url.getPath());
        assertEquals("{}",new String(seen.get().body,StandardCharsets.UTF_8));
        assertFalse(seen.get().headers.containsKey("Idempotency-Key"));
        AgentHttpTransport notUnknown=request->response(request.url,200,
                operation(unknown,"queued",null,null).toString());
        try{new VideoTaskClient(notUnknown).reconcile(task,connection,unknown);fail("reconcile must not return a replayable state");}
        catch(IllegalStateException expected){}
    }

    @Test public void runningStoryboardStopUsesDedicatedStrictPostAndReadOnlyGet() throws Exception {
        AgentTaskStore.Task task=videoTask();AgentConnectionStore.Config connection=config();
        VideoOperationStore.Operation running=runningStoryboard();AtomicReference<AgentHttpTransport.Request> seen=new AtomicReference<>();
        AgentHttpTransport transport=request->{seen.set(request);return response(request.url,200,cancelRequest(running,"requested").toString());};
        VideoTaskClient client=new VideoTaskClient(transport);
        JSONObject posted=client.cancelRunning(task,connection,running);
        assertEquals("requested",posted.getString("status"));assertEquals("POST",seen.get().method);
        assertEquals("/padnote/v1/agents/instance-a/runs/remote-a/video/operations/18ed9804-fbdb-4a3a-96f9-05d56e4b4e52/cancel-running",seen.get().url.getPath());
        assertEquals("{}",new String(seen.get().body,StandardCharsets.UTF_8));
        JSONObject read=client.cancelRequest(task,connection,running);
        assertEquals("requested",read.getString("status"));assertEquals("GET",seen.get().method);assertEquals(0,seen.get().body.length);
    }

    @Test public void runningStopRejectsWrongIdentityAndUnknownControlFields() throws Exception {
        AgentTaskStore.Task task=videoTask();AgentConnectionStore.Config connection=config();
        VideoOperationStore.Operation running=runningStoryboard();
        AgentHttpTransport wrong=request->response(request.url,200,cancelRequest(running,"requested").put("operation_id","aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString());
        try{new VideoTaskClient(wrong).cancelRunning(task,connection,running);fail("mismatched operation must fail");}
        catch(IllegalStateException expected){}
        AgentHttpTransport extra=request->response(request.url,200,cancelRequest(running,"requested").put("attempt_id","secret").toString());
        try{new VideoTaskClient(extra).cancelRequest(task,connection,running);fail("unknown fields must fail");}
        catch(IllegalStateException expected){}
    }

    @Test public void reconciliationAcceptsOnlyVerifiedCancelledOperationProjection() throws Exception {
        AgentTaskStore.Task task=videoTask();AgentConnectionStore.Config connection=config();
        VideoOperationStore.Operation unknown=new VideoOperationStore.Operation("storyboard",
                "2c2c2c2c-2c2c-42c2-82f9-2c2c2c2c2c2c","2c2c2c2c-2c2c-42c2-82f9-2c2c2c2c2c2c",
                "18ed9804-fbdb-4a3a-96f9-05d56e4b4e52","unknown","worker_unknown",
                new JSONObject().put("revision",1).put("event_cursor",1),null,false,10,11,
                new JSONObject().put("status","unconfirmed").put("requested_at",10).put("updated_at",11));
        AgentHttpTransport valid=request->response(request.url,200,operation(unknown,"cancelled",null,"cancelled").toString());
        assertEquals("cancelled",new VideoTaskClient(valid).reconcile(task,connection,unknown).getString("status"));
        AgentHttpTransport malformed=request->response(request.url,200,operation(unknown,"cancelled",null,"worker_unknown").toString());
        try{new VideoTaskClient(malformed).reconcile(task,connection,unknown);fail("unverified cancellation code must fail");}
        catch(IllegalStateException expected){}
    }

    @Test public void rejectsRedirectWrongOperationIdentityAndWrongBundleWorker() throws Exception {
        AgentTaskStore.Task task=videoTask();AgentConnectionStore.Config connection=config();
        VideoOperationStore.Operation operation=new VideoOperationStore.Operation("initialize","key-uuid","key-uuid","",
                "prepared","",new JSONObject(),null,false,10,10);
        AgentHttpTransport wrong=request->response(request.url,202,
                new JSONObject().put("object","padnote.video.operation").put("protocol_version",1)
                        .put("operation_id","18ed9804-fbdb-4a3a-96f9-05d56e4b4e52").put("task_id","remote-a")
                        .put("client_operation_id","other-key").put("action","initialize").put("status","queued")
                        .put("created_at",10).put("updated_at",10).put("result",JSONObject.NULL).put("error",JSONObject.NULL).toString());
        try{new VideoTaskClient(wrong).submit(task,connection,operation);fail();}catch(IllegalStateException expected){}
        AgentHttpTransport redirected=request->new AgentHttpTransport.Response(202,request.url,
                new URL("https://other.test/steal"),new byte[0],Collections.emptyMap());
        try{new VideoTaskClient(redirected).submit(task,connection,operation);fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("重定向"));}
        JSONObject review=review(task,"not-the-bundle-task");
        try{VideoTaskClient.validateReview(review,task);fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("审阅版本"));}
    }

    @Test public void previewMustBeListedBoundPngWithMatchingDigestAndDimensions() throws Exception {
        AgentTaskStore.Task task=videoTask();AgentConnectionStore.Config connection=config();
        byte[] png=headerPng(2,3);String digest=sha(png);String id=repeat('a',64);
        JSONObject review=review(task,workerId(task),id,png.length,digest,2,3);
        JSONObject metadata=review.getJSONArray("scenes").getJSONObject(0).getJSONObject("preview");
        id=VideoTaskClient.previewId(review,review.getJSONArray("scenes").getJSONObject(0));metadata.put("id",id);
        AtomicReference<AgentHttpTransport.Request> seen=new AtomicReference<>();
        Map<String,java.util.List<String>> headers=new HashMap<>();headers.put("Content-Type",Collections.singletonList("image/png"));headers.put("Content-Length",Collections.singletonList(String.valueOf(png.length)));
        AgentHttpTransport transport=request->{seen.set(request);return new AgentHttpTransport.Response(200,request.url,request.url,png,headers);};
        assertArrayEquals(png,new VideoTaskClient(transport).preview(task,connection,review,metadata));
        assertTrue(seen.get().url.getPath().endsWith("/video/previews/"+id));
        byte[] corrupted=png.clone();corrupted[0]=0;
        AgentHttpTransport bad=request->new AgentHttpTransport.Response(200,request.url,request.url,corrupted,headers);
        try{new VideoTaskClient(bad).preview(task,connection,review,metadata);fail();}catch(IllegalStateException expected){}
    }

    private static JSONObject diagnostics()throws Exception{
        JSONObject checks=new JSONObject();for(String key:new String[]{"worker_modules","storyboard_browser","render_browser","ffmpeg","ffprobe","tts"})checks.put(key,new JSONObject().put("status","available").put("reason","present"));
        return new JSONObject().put("schema_version","1.0").put("runtime_verified",false).put("video_ready",false).put("checks",checks);
    }
    private static JSONObject inspection(String status,String phase,int cursor,int revision,
                                         String review,String lessonIr,String approval)throws Exception{
        JSONObject value=new JSONObject().put("protocol_version",1).put("task_id","remote-a")
                .put("status",status).put("phase",phase).put("event_cursor",cursor);
        if(revision>0)value.put("revision",revision).put("review_sha256",review)
                .put("lesson_ir_sha256",lessonIr);
        if(!approval.isEmpty())value.put("approval",new JSONObject().put("approval_id",approval)
                .put("revision",revision).put("review_sha256",review).put("lesson_ir_sha256",lessonIr)
                .put("granted_at","2026-09-27T00:00:00.000Z"));
        return value;
    }
    private static JSONObject review(AgentTaskStore.Task task,String worker)throws Exception{return review(task,worker,repeat('a',64),33,sha(headerPng(1,1)),1,1);}
    private static JSONObject review(AgentTaskStore.Task task,String worker,String id,int size,String sha,int width,int height)throws Exception{
        JSONObject preview=new JSONObject().put("id",id).put("media_type","image/png").put("size_bytes",size).put("sha256",sha).put("width",width).put("height",height);
        JSONObject scene=new JSONObject().put("id","scene-1").put("learning_objective","理解输入").put("narration","这是解说文字").put("screen_text",new JSONArray().put("例子")).put("visual_kind","title").put("preview",preview);
        JSONObject review=new JSONObject().put("object","padnote.video.review").put("protocol_version",1).put("task_id","remote-a")
                .put("worker_task_id",worker).put("status","awaiting_storyboard_review").put("event_cursor",3).put("revision",1)
                .put("review_sha256",repeat('b',64)).put("lesson_ir_sha256",repeat('c',64))
                .put("episode",new JSONObject().put("title","视频标题").put("audience","初学者").put("learning_goal","理解输入").put("language","zh-CN"))
                .put("scenes",new JSONArray().put(scene));
        preview.put("id",VideoTaskClient.previewId(review,scene));return review;
    }
    private static AgentTaskStore.Task videoTask()throws Exception{return videoTask(AgentConnectionStore.Kind.HERMES);}
    private static AgentTaskStore.Task videoTask(AgentConnectionStore.Kind kind)throws Exception{
        ByteArrayOutputStream zip=new ByteArrayOutputStream();VideoTaskBundleIO.write(zip,"note-a",1,"标题","材料","初学者","理解输入",90,"voice",1f);
        byte[] bytes=zip.toByteArray();String clientId="padnote-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        JSONObject submission=new JSONObject().put("client_task_id",clientId).put("input","video").put("bundle_base64",java.util.Base64.getEncoder().encodeToString(bytes)).put("bundle_sha256",sha(bytes));
        String text=submission.toString();return new AgentTaskStore.Task(clientId,"connection-a",7,"https://bridge.test",kind,
                AgentConnectionStore.Transport.BRIDGE,"credential-a","bridge-a","instance-a","视频任务","Bridge","note-a",1,text,
                AgentTaskStore.sha256(text.getBytes(StandardCharsets.UTF_8)),"remote-a",AgentTaskStore.Status.RUNNING,"","","","","",Collections.emptyList(),1,1);
    }
    private static String workerId(AgentTaskStore.Task task)throws Exception{
        java.util.zip.ZipInputStream in=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(new JSONObject(task.submissionJson).getString("bundle_base64"))));
        java.util.zip.ZipEntry e;while((e=in.getNextEntry())!=null)if("request.json".equals(e.getName())){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))>=0)out.write(b,0,n);return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)).getString("task_id");}throw new AssertionError();
    }
    private static AgentConnectionStore.Config config(){return config(AgentConnectionStore.Kind.HERMES,"");}
    private static AgentConnectionStore.Config config(AgentConnectionStore.Kind kind,String certSha256){return new AgentConnectionStore.Config("connection-a","Bridge",kind,"https://bridge.test","Bearer-token","credential-a",AgentConnectionStore.Transport.BRIDGE,"bridge-a","instance-a",certSha256,7,1,Arrays.asList("video_operations","video_production"));}
    private static VideoOperationStore.Operation runningStoryboard()throws Exception{return new VideoOperationStore.Operation("storyboard","2c2c2c2c-2c2c-42c2-82f9-2c2c2c2c2c2c","2c2c2c2c-2c2c-42c2-82f9-2c2c2c2c2c2c","18ed9804-fbdb-4a3a-96f9-05d56e4b4e52","running","",new JSONObject().put("revision",1).put("event_cursor",1),null,false,10,11);}
    private static JSONObject cancelRequest(VideoOperationStore.Operation op,String status)throws Exception{return new JSONObject().put("object","padnote.video.cancel_request").put("protocol_version",1).put("operation_id",op.operationId).put("task_id","remote-a").put("status",status).put("requested_at",10).put("updated_at",11);}
    private static byte[] headerPng(int width,int height){byte[] b=new byte[33];byte[] sig={(byte)137,80,78,71,13,10,26,10};System.arraycopy(sig,0,b,0,8);b[11]=13;b[12]=73;b[13]=72;b[14]=68;b[15]=82;b[19]=(byte)width;b[23]=(byte)height;b[24]=8;b[25]=6;return b;}
    private static AgentHttpTransport.Response response(URL url,int status,String json){return new AgentHttpTransport.Response(status,url,url,json.getBytes(StandardCharsets.UTF_8),Collections.emptyMap());}
    private static JSONObject operation(VideoOperationStore.Operation op,String status,JSONObject result,String error)throws Exception{return new JSONObject().put("object","padnote.video.operation").put("protocol_version",1).put("operation_id",op.operationId).put("task_id","remote-a").put("client_operation_id",op.clientOperationId).put("action",op.action).put("status",status).put("created_at",10).put("updated_at",11).put("result",result==null?JSONObject.NULL:result).put("error",error==null?JSONObject.NULL:error);}
    private static String sha(byte[] bytes)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format("%02x",b&255));return s.toString();}
    private static String repeat(char c,int count){char[] v=new char[count];Arrays.fill(v,c);return new String(v);}
}
