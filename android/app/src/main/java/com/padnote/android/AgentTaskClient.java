package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Polling task adapters for direct Hermes and PadNote Bridge v1. */
final class AgentTaskClient {
    interface Factory { AgentTaskClient create(); }
    static final class HttpStatusException extends IllegalStateException {
        final int status;
        HttpStatusException(int status, String message) { super(message); this.status=status; }
    }
    static final class Submission {
        final String remoteTaskId;
        final AgentTaskStore.Status status;
        final String conversationId, parentTaskId;
        Submission(String remoteTaskId, AgentTaskStore.Status status, String conversationId,
                   String parentTaskId) {
            this.remoteTaskId = remoteTaskId; this.status = status;
            this.conversationId=conversationId;this.parentTaskId=parentTaskId;
        }
    }

    static final class RemoteStatus {
        final AgentTaskStore.Status status;
        final String output, error, approvalId, approvalTitle, approvalDescription;
        final String conversationId, parentTaskId, followupReason;
        final boolean followupAvailable;
        final List<AgentTaskStore.Artifact> artifacts;
        RemoteStatus(AgentTaskStore.Status status, String output, String error,
                     String approvalId, String approvalTitle, String approvalDescription,
                     List<AgentTaskStore.Artifact> artifacts) {
            this(status,output,error,approvalId,approvalTitle,approvalDescription,artifacts,
                    "","",false,"");
        }
        RemoteStatus(AgentTaskStore.Status status, String output, String error,
                     String approvalId, String approvalTitle, String approvalDescription,
                     List<AgentTaskStore.Artifact> artifacts, String conversationId,
                     String parentTaskId, boolean followupAvailable, String followupReason) {
            this.status=status;this.output=output;this.error=error;this.approvalId=approvalId;
            this.approvalTitle=approvalTitle;this.approvalDescription=approvalDescription;
            this.conversationId=conversationId;this.parentTaskId=parentTaskId;
            this.followupAvailable=followupAvailable;this.followupReason=followupReason;
            this.artifacts=Collections.unmodifiableList(new ArrayList<>(artifacts));
        }
    }

    private static final int MAX_JSON = 2 * 1024 * 1024;
    private final AgentHttpTransport http;
    AgentTaskClient() { this(AgentHttpTransport.production()); }
    AgentTaskClient(AgentHttpTransport http) { this.http=http; }

    Submission submit(AgentTaskStore.Task task, AgentConnectionStore.Config connection)
            throws Exception {
        requireIdentity(task,connection);
        if (connection.kind == AgentConnectionStore.Kind.BUILTIN_VIDEO) {
            if (!VideoTaskClient.isVideoBundle(task) || !connection.capabilities.contains("video_task_submission") ||
                    !connection.capabilities.contains("task_bundle"))
                throw new IllegalStateException("内置视频工作流仅接收已验证的视频任务包");
        } else requireCapability(connection,"run_submission");
        if(connection.kind==AgentConnectionStore.Kind.OPENCLAW)
            throw new IllegalStateException("当前版本尚未实现 OpenClaw 任务适配");
        Map<String,String> headers=new LinkedHashMap<>();
        headers.put("Idempotency-Key",task.clientTaskId);
        AgentHttpTransport.Response response=request("POST",runsRoot(task),connection,
                headers,task.submissionJson.getBytes(StandardCharsets.UTF_8),MAX_JSON);
        if(response.status!=202)throw httpError("提交任务失败",response);
        JSONObject json=new JSONObject(response.utf8());
        String key=task.transport==AgentConnectionStore.Transport.BRIDGE?"task_id":"run_id";
        String remote=required(json,key);
        if(task.transport==AgentConnectionStore.Transport.BRIDGE &&
                !task.instanceId.equals(json.optString("instance_id")))
            throw new IllegalStateException("任务响应来自其他 Agent 实例");
        String conversationId="",parentTaskId="";
        if(task.transport==AgentConnectionStore.Transport.BRIDGE) {
            if(json.has("conversation_id"))conversationId=required(json,"conversation_id");
            if(json.has("parent_task_id"))parentTaskId=json.optString("parent_task_id");
            validateLineage(task,remote,conversationId,parentTaskId);
        }
        return new Submission(remote,mapStatus(required(json,"status")),conversationId,parentTaskId);
    }

    RemoteStatus status(AgentTaskStore.Task task,AgentConnectionStore.Config connection)
            throws Exception {
        requireRemote(task,connection); requireCapability(connection,"run_status");
        AgentHttpTransport.Response response=request("GET",runsRoot(task)+"/"+
                AgentConnectionClient.path(task.remoteTaskId),connection,
                Collections.emptyMap(),null,MAX_JSON);
        if(response.status!=200)throw httpError("读取任务状态失败",response);
        JSONObject json=new JSONObject(response.utf8()); validateRemote(task,json);
        JSONObject approval=json.optJSONObject("approval");
        String approvalId="",approvalTitle="",approvalDescription="";
        if(approval!=null){
            approvalId=task.transport==AgentConnectionStore.Transport.BRIDGE
                    ?approval.optString("approval_id",""):approval.optString("request_id","");
            if(approvalId.length()>256)throw new IllegalStateException("审批请求ID过长");
            approvalTitle=plain(approval.optString("title",approval.optString("tool","需要批准")),160);
            approvalDescription=plain(approval.optString("description",approval.optString("reason",
                    approval.optString("command",""))),1000);
        }
        List<AgentTaskStore.Artifact> artifacts=new ArrayList<>();
        JSONArray values=json.optJSONArray("artifacts");
        if(values!=null&&task.transport==AgentConnectionStore.Transport.BRIDGE){
            for(int i=0;i<values.length();i++){JSONObject a=values.optJSONObject(i);if(a==null)continue;
                String id=a.optString("id",""); long size=a.optLong("size_bytes",-1);
                String sha=a.optString("sha256",""); if(!id.isEmpty()&&size>=0&&size<=100L*1024*1024&&sha.matches("[a-fA-F0-9]{64}"))
                    artifacts.add(new AgentTaskStore.Artifact(id,plain(a.optString("name","artifact"),256),
                            a.optString("media_type","application/octet-stream"),size,sha.toLowerCase()));
            }
        }
        String conversationId="",parentTaskId="",followupReason="电脑助手未提供继续沟通能力信息";
        boolean followupAvailable=false;
        if(task.transport==AgentConnectionStore.Transport.BRIDGE) {
            if(json.has("conversation_id"))conversationId=required(json,"conversation_id");
            if(json.has("parent_task_id"))parentTaskId=json.optString("parent_task_id");
            validateLineage(task,task.remoteTaskId,conversationId,parentTaskId);
            boolean completeFollowupMetadata=json.has("conversation_id")&&json.has("parent_task_id")&&
                    json.has("followup_available")&&json.opt("followup_available") instanceof Boolean&&
                    json.has("followup_reason")&&json.opt("followup_reason") instanceof String;
            if(completeFollowupMetadata) {
                followupAvailable=json.optBoolean("followup_available",false);
                followupReason=plain(json.optString("followup_reason",""),1000);
                if(followupAvailable && (conversationId.isEmpty() || mapStatus(required(json,"status"))!=AgentTaskStore.Status.COMPLETED)) {
                    followupAvailable=false;followupReason="电脑助手返回的追问资格与任务状态不匹配";
                }
            }
        } else followupReason="Hermes 直连暂不支持继续沟通";
        return new RemoteStatus(mapStatus(required(json,"status")),
                output(json.opt("output")),plain(json.optString("error",""),4000),
                approvalId,approvalTitle,approvalDescription,artifacts,conversationId,parentTaskId,
                followupAvailable,followupReason);
    }

    void stop(AgentTaskStore.Task task,AgentConnectionStore.Config connection)throws Exception{
        requireRemote(task,connection);requireCapability(connection,"run_stop");
        AgentHttpTransport.Response r=request("POST",runsRoot(task)+"/"+AgentConnectionClient.path(task.remoteTaskId)+"/stop",
                connection,Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8),MAX_JSON);
        if(r.status<200||r.status>=300)throw httpError("停止任务失败",r);
    }

    void approve(AgentTaskStore.Task task,AgentConnectionStore.Config connection,String decision)throws Exception{
        requireRemote(task,connection);requireCapability(connection,"run_approval_response");
        if(task.approvalId.isEmpty())throw new IllegalStateException("当前任务没有可绑定的审批请求");
        if(!"once".equals(decision)&&!"deny".equals(decision))throw new IllegalArgumentException("审批只支持 once 或 deny");
        JSONObject body=task.transport==AgentConnectionStore.Transport.BRIDGE
                ?new JSONObject().put("approval_id",task.approvalId).put("decision",decision)
                :new JSONObject().put("request_id",task.approvalId).put("choice",decision);
        AgentHttpTransport.Response r=request("POST",runsRoot(task)+"/"+AgentConnectionClient.path(task.remoteTaskId)+"/approval",
                connection,Collections.emptyMap(),body.toString().getBytes(StandardCharsets.UTF_8),MAX_JSON);
        if(r.status<200||r.status>=300)throw httpError("提交审批失败",r);
    }

    URL artifactUrl(AgentTaskStore.Task task,String artifactId)throws Exception{
        if(task.transport!=AgentConnectionStore.Transport.BRIDGE)throw new IllegalStateException("直连任务没有 Bridge 产物接口");
        return new URL(runsRoot(task)+"/"+AgentConnectionClient.path(task.remoteTaskId)+
                "/artifacts/"+AgentConnectionClient.path(artifactId));
    }

    private AgentHttpTransport.Response request(String method,String url,AgentConnectionStore.Config connection,
                                                 Map<String,String> headers,byte[] body,int max)throws Exception{
        AgentHttpTransport.Response response=http.execute(new AgentHttpTransport.Request(method,
                new URL(url),connection.token,headers,body,max,connection.certSha256));AgentConnectionClient.rejectRedirect(response);return response;
    }
    private static String runsRoot(AgentTaskStore.Task task)throws Exception{
        String root=task.origin; if(task.transport==AgentConnectionStore.Transport.BRIDGE)
            return root+"/padnote/v1/agents/"+AgentConnectionClient.path(task.instanceId)+"/runs";
        if(root.endsWith("/v1/capabilities"))root=root.substring(0,root.length()-"/capabilities".length());
        return root.endsWith("/v1")?root+"/runs":root+"/v1/runs";
    }
    private static void requireIdentity(AgentTaskStore.Task task,AgentConnectionStore.Config c){
        if(!task.matches(c))throw new IllegalStateException("任务绑定的连接身份已变更，请新建任务后重新确认");
        if(c.token.isEmpty())throw new IllegalStateException("连接令牌不可用");
    }
    private static void requireRemote(AgentTaskStore.Task task,AgentConnectionStore.Config c){requireIdentity(task,c);if(task.remoteTaskId.isEmpty())throw new IllegalStateException("任务尚未取得远端ID");}
    private static void requireCapability(AgentConnectionStore.Config c,String value){if(!c.capabilities.contains(value))throw new IllegalStateException("Agent 未验证能力："+value);}
    private static void validateRemote(AgentTaskStore.Task task,JSONObject j){String key=task.transport==AgentConnectionStore.Transport.BRIDGE?"task_id":"run_id";if(!task.remoteTaskId.equals(j.optString(key)))throw new IllegalStateException("任务状态ID不匹配");if(task.transport==AgentConnectionStore.Transport.BRIDGE&&!task.instanceId.equals(j.optString("instance_id")))throw new IllegalStateException("任务状态来自其他Agent实例");}
    private static void validateLineage(AgentTaskStore.Task task,String remote,String conversation,String parent) {
        if(task.parentTaskId.isEmpty()) {
            if(!parent.isEmpty())throw new IllegalStateException("任务响应包含不匹配的父任务");
            if(!conversation.isEmpty()&&!remote.equals(conversation))throw new IllegalStateException("任务响应会话ID不匹配");
        } else {
            if(parent.isEmpty()||!task.parentTaskId.equals(parent))throw new IllegalStateException("追问响应父任务ID不匹配");
            if(conversation.isEmpty()||!task.conversationId.equals(conversation))throw new IllegalStateException("追问响应会话ID不匹配");
        }
    }
    private static AgentTaskStore.Status mapStatus(String raw){switch(raw){case"completed":return AgentTaskStore.Status.COMPLETED;case"failed":return AgentTaskStore.Status.FAILED;case"cancelled":return AgentTaskStore.Status.CANCELLED;case"interrupted":return AgentTaskStore.Status.INTERRUPTED;case"waiting_for_approval":return AgentTaskStore.Status.WAITING_FOR_APPROVAL;case"stopping":return AgentTaskStore.Status.STOPPING;case"submitting":return AgentTaskStore.Status.SUBMITTING;case"running":return AgentTaskStore.Status.RUNNING;default:throw new IllegalStateException("Agent响应包含未知任务状态");}}
    private static String output(Object value){if(value==null||value==JSONObject.NULL)return"";return plain(value instanceof String?(String)value:String.valueOf(value),128*1024);}
    private static String plain(String v,int max){if(v==null)return"";String clean=v.replace('\u0000',' ').trim();return clean.length()>max?clean.substring(0,max):clean;}
    private static String required(JSONObject j,String k){String v=j.optString(k,"");if(v.isEmpty())throw new IllegalStateException("Agent响应缺少 "+k);return v;}
    private static IllegalStateException httpError(String prefix,AgentHttpTransport.Response r){String message="";try{JSONObject e=new JSONObject(r.utf8()).optJSONObject("error");if(e!=null)message=plain(e.optString("message",""),500);}catch(Exception ignored){}return new HttpStatusException(r.status,prefix+"（HTTP "+r.status+"）"+(message.isEmpty()?"":"："+message));}
}
