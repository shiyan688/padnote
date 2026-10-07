package com.padnote.android;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Video-only durable queue state. It deliberately does not mutate AgentTaskStore run status. */
final class VideoOperationStore {
    static final class Operation {
        final String action, key, clientOperationId, operationId, status, error;
        final JSONObject parameters, result;
        final JSONObject cancelControl;
        final boolean submissionUncertain;
        final long createdAt, updatedAt;
        Operation(String action, String key, String clientOperationId, String operationId,
                  String status, String error, JSONObject parameters, JSONObject result,
                  boolean submissionUncertain, long createdAt, long updatedAt) {
            this(action,key,clientOperationId,operationId,status,error,parameters,result,
                    submissionUncertain,createdAt,updatedAt,null);
        }
        Operation(String action, String key, String clientOperationId, String operationId,
                  String status, String error, JSONObject parameters, JSONObject result,
                  boolean submissionUncertain, long createdAt, long updatedAt,JSONObject cancelControl) {
            this.action=action;this.key=key;this.clientOperationId=clientOperationId;
            this.operationId=operationId;this.status=status;this.error=error;
            this.parameters=copy(parameters);this.result=copy(result);
            this.cancelControl=copy(cancelControl);
            this.submissionUncertain=submissionUncertain;this.createdAt=createdAt;this.updatedAt=updatedAt;
        }
        JSONObject json() throws Exception { return new JSONObject().put("action",action).put("key",key)
                .put("client_operation_id",clientOperationId).put("operation_id",operationId)
                .put("status",status).put("error",error).put("parameters",parameters)
                .put("result",result==null?JSONObject.NULL:result)
                .put("submission_uncertain",submissionUncertain).put("created_at",createdAt)
                .put("updated_at",updatedAt).put("cancel_control",cancelControl==null?JSONObject.NULL:cancelControl); }
    }

    private static final int MAX_FILE=1024*1024, MAX_OPERATIONS=128;
    private static final Object LOCK=new Object();
    private final File directory;
    VideoOperationStore(Context context) { this(new File(context.getFilesDir(),"video-operations")); }
    VideoOperationStore(File directory) {
        this.directory=directory;
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("无法创建视频操作记录目录");
    }

    List<Operation> operations(AgentTaskStore.Task task) throws Exception { synchronized(LOCK) {
        JSONObject root=read(task);
        JSONArray array=root.optJSONArray("operations");
        List<Operation> result=new ArrayList<>();
        if(array!=null)for(int i=0;i<array.length();i++)result.add(parse(array.getJSONObject(i)));
        return Collections.unmodifiableList(result);}}
    JSONObject review(AgentTaskStore.Task task) throws Exception { synchronized(LOCK) { JSONObject saved=copy(read(task).optJSONObject("review"));if(saved!=null)VideoTaskClient.validateReview(saved,task);return saved; } }
    void saveReview(AgentTaskStore.Task task, JSONObject review) throws Exception { synchronized(LOCK) {
        VideoTaskClient.validateReview(review,task);
        JSONObject root=readOrCreate(task);root.put("review",copy(review));write(task,root);
    }}
    Operation find(AgentTaskStore.Task task,String operationId) throws Exception { synchronized(LOCK) {
        for(Operation operation:operations(task))if(operation.clientOperationId.equals(operationId))return operation;
        return null;
    }}
    Operation active(AgentTaskStore.Task task) throws Exception { synchronized(LOCK) {
        List<Operation> all=operations(task);for(int i=all.size()-1;i>=0;i--){Operation o=all.get(i);
            if(!terminal(o.status))return o;}return null;
    }}
    Operation latest(AgentTaskStore.Task task) throws Exception { synchronized(LOCK) {List<Operation> all=operations(task);return all.isEmpty()?null:all.get(all.size()-1);} }
    boolean hasVerifiedStoryboardStop(AgentTaskStore.Task task)throws Exception{synchronized(LOCK){
        for(Operation operation:operations(task))if("storyboard".equals(operation.action)&&"cancelled".equals(operation.status)&&
                operation.cancelControl!=null&&"verified_cancelled".equals(operation.cancelControl.optString("status")))return true;
        return false;
    }}
    Operation reserve(AgentTaskStore.Task task,String action,JSONObject parameters) throws Exception {
        return reserve(task,action,parameters,null);
    }
    Operation reserve(AgentTaskStore.Task task,String action,JSONObject parameters,JSONObject expectedReview) throws Exception { synchronized(LOCK) {
        JSONObject root=readOrCreate(task);JSONArray array=root.getJSONArray("operations");
        if(("storyboard".equals(action)||"approve".equals(action))&&hasVerifiedStoryboardStopIn(array))
            throw new IllegalStateException("此任务的分镜已由电脑端核实停止；请返回笔记新建视频任务");
        if("approve".equals(action)){
            JSONObject current=root.optJSONObject("review");
            if(expectedReview==null||current==null||!current.toString().equals(expectedReview.toString()))
                throw new IllegalStateException("审阅版本已变化，请刷新后重新查看");
            try{VideoTaskClient.validateReview(current,task);}catch(Exception invalid){throw new IllegalStateException("当前审阅版本无效",invalid);}
            JSONObject approval=parameters;
            if(!"awaiting_storyboard_review".equals(current.optString("status"))||approval.length()!=4||
                    approval.optLong("revision",-1)!=current.optLong("revision",-2)||
                    approval.optLong("event_cursor",-1)!=current.optLong("event_cursor",-2)||
                    !approval.optString("review_sha256").equals(current.optString("review_sha256"))||
                    !approval.optString("lesson_ir_sha256").equals(current.optString("lesson_ir_sha256")))
                throw new IllegalStateException("批准参数与当前审阅版本不匹配");
        }else if("produce".equals(action)){
            JSONObject current=root.optJSONObject("review");Operation approved=null;
            for(int i=array.length()-1;i>=0;i--){Operation candidate=parse(array.getJSONObject(i));if("approve".equals(candidate.action)){approved=candidate;break;}}
            JSONObject p=parameters,ar=approved==null?null:approved.result;
            if(expectedReview!=null||current==null||!"approved".equals(current.optString("status"))||approved==null||!"succeeded".equals(approved.status)||ar==null||
                    p.length()!=5||!(p.opt("allow_cloud_tts") instanceof Boolean)||!p.getBoolean("allow_cloud_tts")||p.optLong("revision",-1)!=ar.optLong("revision",-2)||p.optLong("event_cursor",-1)!=ar.optLong("event_cursor",-2)||
                    !p.optString("review_sha256").equals(ar.optString("review_sha256"))||!p.optString("lesson_ir_sha256").equals(ar.optString("lesson_ir_sha256"))||
                    p.optLong("revision",-1)!=current.optLong("revision",-2)||p.optLong("event_cursor",-1)!=current.optLong("event_cursor",-2)||
                    !p.optString("review_sha256").equals(current.optString("review_sha256"))||!p.optString("lesson_ir_sha256").equals(current.optString("lesson_ir_sha256")))
                throw new IllegalStateException("视频生成参数与已批准版本不匹配");
        }else if(expectedReview!=null)throw new IllegalArgumentException("仅批准操作可绑定审阅版本");
        for(int i=array.length()-1;i>=0;i--){Operation old=parse(array.getJSONObject(i));
            if(old.action.equals(action)&&old.parameters.toString().equals(parameters.toString())){
                if(!terminal(old.status)||"succeeded".equals(old.status))return old;
                // A user explicitly pressing the same action after a known failed/cancelled result is a new attempt.
            }}
        for(int i=0;i<array.length();i++)if(!terminal(array.getJSONObject(i).getString("status")))
            throw new IllegalStateException("已有视频操作尚未确认，请先刷新");
        if(array.length()>=MAX_OPERATIONS)throw new IllegalStateException("视频操作历史已满，请联系电脑端维护");
        long now=System.currentTimeMillis();String key=UUID.randomUUID().toString();Operation created=new Operation(action,key,
                key,"","prepared","",parameters,null,false,now,now);
        array.put(created.json());write(task,root);return created;}}
    private static boolean hasVerifiedStoryboardStopIn(JSONArray array)throws Exception{
        for(int i=0;i<array.length();i++){Operation operation=parse(array.getJSONObject(i));
            if("storyboard".equals(operation.action)&&"cancelled".equals(operation.status)&&operation.cancelControl!=null&&
                    "verified_cancelled".equals(operation.cancelControl.optString("status")))return true;}
        return false;
    }
    Operation update(AgentTaskStore.Task task,String clientOperationId,
                                  String operationId,String status,String error,JSONObject result,
                                  boolean submissionUncertain) throws Exception { synchronized(LOCK) {
        return updateLocked(task,clientOperationId,operationId,status,error,result,submissionUncertain,null);
    }}
    /** Applies a response only if the durable record is still the exact snapshot used for its request. */
    Operation updateIfCurrent(AgentTaskStore.Task task,Operation expected,
                              String operationId,String status,String error,JSONObject result,
                              boolean submissionUncertain) throws Exception { synchronized(LOCK) {
        if(expected==null)return null;
        return updateLocked(task,expected.clientOperationId,operationId,status,error,result,
                submissionUncertain,expected);
    }}
    Operation updateReconciledIfCurrent(AgentTaskStore.Task task,Operation expected,String operationId,
                                        String status,String error,JSONObject result)throws Exception{
        if(!"cancelled".equals(status))return updateIfCurrent(task,expected,operationId,status,error,result,false);
        synchronized(LOCK){
            if(expected==null||!"unknown".equals(expected.status)||expected.cancelControl==null||!("storyboard".equals(expected.action)||"produce".equals(expected.action))||!"cancelled".equals(error)||result!=null)return null;
            JSONObject root=read(task);JSONArray array=root.getJSONArray("operations");
            for(int i=0;i<array.length();i++){
                JSONObject item=array.getJSONObject(i);if(!expected.clientOperationId.equals(item.optString("client_operation_id")))continue;
                Operation old=parse(item);if(!sameSnapshot(old,expected)||!old.operationId.equals(operationId))return null;
                JSONObject control=verifiedControlFromReceipt(old);
                Operation changed=withCancelControl(old,control,"cancelled","cancelled",null,System.currentTimeMillis());
                array.put(i,changed.json());write(task,root);return changed;
            }
            return null;
        }
    }
    /** Persists stop intent before any mutating request; repeated taps reuse this record. */
    Operation prepareRunningCancel(AgentTaskStore.Task task,Operation expected)throws Exception{synchronized(LOCK){
        if(expected==null||!("storyboard".equals(expected.action)||"produce".equals(expected.action))||
                !("running".equals(expected.status)||"produce".equals(expected.action)&&"unknown".equals(expected.status))||expected.operationId.isEmpty())
            throw new IllegalStateException("当前操作不支持运行中停止请求");
        JSONObject root=read(task);JSONArray array=root.getJSONArray("operations");
        for(int i=0;i<array.length();i++){
            JSONObject item=array.getJSONObject(i);if(!expected.clientOperationId.equals(item.optString("client_operation_id")))continue;
            Operation old=parse(item);if(!sameSnapshot(old,expected))return null;
            if(old.cancelControl!=null){String savedStatus=old.cancelControl.optString("status");
                return "pending".equals(savedStatus)||"unconfirmed".equals(savedStatus)?old:null;}
            long now=Math.max(System.currentTimeMillis(),old.updatedAt);
            JSONObject control=new JSONObject().put("status","pending").put("intent_at_ms",now);
            Operation changed=withCancelControl(old,control,old.status,old.error,old.result,now);
            array.put(i,changed.json());write(task,root);return changed;
        }
        return null;
    }}
    Operation clearUnsentRunningCancel(AgentTaskStore.Task task,Operation expected)throws Exception{synchronized(LOCK){
        if(expected==null||expected.cancelControl==null||!"pending".equals(expected.cancelControl.optString("status")))return null;
        JSONObject root=read(task);JSONArray array=root.getJSONArray("operations");
        for(int i=0;i<array.length();i++){
            JSONObject item=array.getJSONObject(i);if(!expected.clientOperationId.equals(item.optString("client_operation_id")))continue;
            Operation old=parse(item);if(!sameSnapshot(old,expected))return null;
            Operation changed=new Operation(old.action,old.key,old.clientOperationId,old.operationId,old.status,old.error,
                    old.parameters,old.result,old.submissionUncertain,old.createdAt,
                    Math.max(System.currentTimeMillis(),old.updatedAt),null);
            array.put(i,changed.json());write(task,root);return changed;
        }
        return null;
    }}
    /** Applies authenticated cancel-running or reconcile control state with full operation snapshot CAS. */
    Operation applyCancelControl(AgentTaskStore.Task task,Operation expected,JSONObject remote)throws Exception{synchronized(LOCK){
        if(expected==null||expected.cancelControl==null||remote==null)return null;
        JSONObject root=read(task);JSONArray array=root.getJSONArray("operations");
        for(int i=0;i<array.length();i++){
            JSONObject item=array.getJSONObject(i);if(!expected.clientOperationId.equals(item.optString("client_operation_id")))continue;
            Operation old=parse(item);if(!sameSnapshot(old,expected))return null;
            String status=remote.getString("status");long requested=remote.getLong("requested_at"),updated=remote.getLong("updated_at");
            if(!java.util.Arrays.asList("requested","unconfirmed","verified_cancelled","too_late").contains(status)||requested<0||updated<requested)
                throw new IllegalStateException("停止状态无效");
            String previousControl=old.cancelControl.optString("status");
            if(old.cancelControl.has("requested_at")&&
                    (requested!=old.cancelControl.getLong("requested_at")||updated<old.cancelControl.getLong("updated_at")))return null;
            if(("verified_cancelled".equals(previousControl)||"too_late".equals(previousControl))&&!previousControl.equals(status))return null;
            if("unconfirmed".equals(previousControl)&&"requested".equals(status))return null;
            if(("requested".equals(status)||"unconfirmed".equals(status))&&!("running".equals(old.status)||"unknown".equals(old.status)))return null;
            if("verified_cancelled".equals(status)&&!("running".equals(old.status)||"unknown".equals(old.status)||"cancelled".equals(old.status)))return null;
            if("too_late".equals(status)&&!("running".equals(old.status)||"unknown".equals(old.status)||"succeeded".equals(old.status)||"failed".equals(old.status)))return null;
            String nextOperationStatus=old.status,error=old.error;JSONObject result=old.result;
            if("verified_cancelled".equals(status)){
                if("cancelled".equals(old.status)&&"verified_cancelled".equals(previousControl))return old;
                if(!"storyboard".equals(old.action)||!("running".equals(old.status)||"unknown".equals(old.status)))
                    throw new IllegalStateException("当前操作不能确认停止");
                nextOperationStatus="cancelled";error="cancelled";result=null;
            }
            JSONObject control=new JSONObject().put("status",status).put("intent_at_ms",old.cancelControl.getLong("intent_at_ms"))
                    .put("requested_at",requested).put("updated_at",updated);
            Operation changed=withCancelControl(old,control,nextOperationStatus,error,result,Math.max(System.currentTimeMillis(),old.updatedAt));
            if(old.status.equals(changed.status)&&old.error.equals(changed.error)&&sameJson(old.cancelControl,changed.cancelControl))return old;
            array.put(i,changed.json());write(task,root);return changed;
        }
        return null;
    }}
    private static Operation withCancelControl(Operation old,JSONObject control,String status,String error,JSONObject result,long updatedAt){
        return new Operation(old.action,old.key,old.clientOperationId,old.operationId,status,error,old.parameters,result,
                old.submissionUncertain,old.createdAt,Math.max(updatedAt,old.updatedAt),control);
    }
    private static JSONObject verifiedControlFromReceipt(Operation old)throws Exception{
        JSONObject control=new JSONObject().put("status","verified_cancelled").put("intent_at_ms",old.cancelControl.getLong("intent_at_ms"));
        if(old.cancelControl.has("requested_at"))control.put("requested_at",old.cancelControl.getLong("requested_at"))
                .put("updated_at",old.cancelControl.getLong("updated_at"));
        else control.put("confirmed_at_ms",Math.max(System.currentTimeMillis(),old.cancelControl.getLong("intent_at_ms")));
        return control;
    }
    private Operation updateLocked(AgentTaskStore.Task task,String clientOperationId,
                                   String operationId,String status,String error,JSONObject result,
                                   boolean submissionUncertain,Operation expected) throws Exception {
        JSONObject root=read(task);JSONArray array=root.getJSONArray("operations");
        for(int i=0;i<array.length();i++){
            JSONObject oldJson=array.getJSONObject(i);if(!clientOperationId.equals(oldJson.optString("client_operation_id")))continue;
            Operation old=parse(oldJson);
            if(expected!=null&&!sameSnapshot(old,expected))return null;
            if(!allowedTransition(old,status,value(operationId),value(error),result,submissionUncertain))
                throw new IllegalStateException("视频操作状态不能回退或覆盖");
            if(old.status.equals(status)&&old.operationId.equals(value(operationId))
                    &&old.error.equals(value(error))&&sameJson(old.result,result)
                    &&old.submissionUncertain==submissionUncertain)return old;
            JSONObject cancelControl=old.cancelControl;
            if("cancelled".equals(status)&&cancelControl!=null&&"cancelled".equals(value(error))){
                cancelControl=verifiedControlFromReceipt(old);
            }else if(cancelControl!=null&&!("pending".equals(cancelControl.optString("status")))&&("succeeded".equals(status)||"failed".equals(status))){
                cancelControl=new JSONObject().put("status","too_late")
                        .put("intent_at_ms",old.cancelControl.getLong("intent_at_ms"))
                        .put("requested_at",old.cancelControl.getLong("requested_at"))
                        .put("updated_at",Math.max(System.currentTimeMillis()/1000L,old.cancelControl.getLong("updated_at")));
            }
            Operation changed=new Operation(old.action,old.key,clientOperationId,
                    value(operationId),status,value(error),old.parameters,result,
                    submissionUncertain,old.createdAt,Math.max(System.currentTimeMillis(),old.updatedAt),cancelControl);
            array.put(i,changed.json());write(task,root);return changed;
        }
        throw new IllegalStateException("视频操作记录不存在");
    }
    private static boolean allowedTransition(Operation old,String status,String operationId,
                                             String error,JSONObject result,boolean submissionUncertain){
        if(!java.util.Arrays.asList("prepared","submission_uncertain","queued","running","succeeded","failed","unknown","cancelled").contains(status))return false;
        if(!old.operationId.isEmpty()&&!old.operationId.equals(operationId))return false;
        if(terminal(old.status))return old.status.equals(status)&&old.operationId.equals(operationId)
                &&old.error.equals(error)&&sameJson(old.result,result)&&old.submissionUncertain==submissionUncertain;
        if("unknown".equals(old.status))return ("unknown".equals(status)||"succeeded".equals(status)||
                ("cancelled".equals(status)&&old.cancelControl!=null&&"cancelled".equals(error)&&result==null))
                &&!operationId.isEmpty()&&!submissionUncertain;
        if("running".equals(old.status))return "running".equals(status)||"succeeded".equals(status)||
                ("cancelled".equals(status)&&old.cancelControl!=null&&"cancelled".equals(error)&&result==null)
                ||"failed".equals(status)||"unknown".equals(status);
        if("queued".equals(old.status))return java.util.Arrays.asList("queued","running","succeeded","failed","unknown","cancelled").contains(status);
        if("submission_uncertain".equals(old.status)||"prepared".equals(old.status)){
            if("submission_uncertain".equals(status))return operationId.isEmpty()&&submissionUncertain&&error.isEmpty()&&result==null;
            return java.util.Arrays.asList("queued","running","succeeded","failed","unknown","cancelled").contains(status)
                    &&!operationId.isEmpty()&&!submissionUncertain;
        }
        return false;
    }
    private static boolean sameSnapshot(Operation a,Operation b){
        return a.action.equals(b.action)&&a.key.equals(b.key)&&a.clientOperationId.equals(b.clientOperationId)
                &&a.operationId.equals(b.operationId)&&a.status.equals(b.status)&&a.error.equals(b.error)
                &&a.parameters.toString().equals(b.parameters.toString())&&sameJson(a.result,b.result)
                &&a.submissionUncertain==b.submissionUncertain&&a.createdAt==b.createdAt&&a.updatedAt==b.updatedAt
                &&sameJson(a.cancelControl,b.cancelControl);
    }
    private static boolean sameJson(JSONObject a,JSONObject b){return a==null?b==null:b!=null&&a.toString().equals(b.toString());}
    void bind(AgentTaskStore.Task task) throws Exception { synchronized(LOCK) {
        JSONObject root=readOrCreate(task);
        if(!task.clientTaskId.equals(root.optString("client_task_id"))||
                !task.connectionId.equals(root.optString("connection_id"))||
                task.connectionRevision!=root.optLong("connection_revision")||
                !task.remoteTaskId.equals(root.optString("remote_task_id"))||
                !task.origin.equals(root.optString("origin"))||!task.instanceId.equals(root.optString("instance_id"))||
                !task.credentialRef.equals(root.optString("credential_ref")))
            throw new IllegalStateException("视频任务连接身份已变化");}}

    private JSONObject readOrCreate(AgentTaskStore.Task task) throws Exception {
        File file=file(task);if(file.exists()||new File(file.getPath()+".bak").exists()){
            JSONObject root=read(task);bindExisting(task,root);return root;}
        return new JSONObject().put("schema_version",1).put("client_task_id",task.clientTaskId)
                .put("connection_id",task.connectionId).put("connection_revision",task.connectionRevision)
                .put("origin",task.origin).put("credential_ref",task.credentialRef).put("kind",task.kind.name())
                .put("transport",task.transport.name()).put("bridge_id",task.bridgeId)
                .put("instance_id",task.instanceId).put("remote_task_id",task.remoteTaskId)
                .put("operations",new JSONArray()).put("review",JSONObject.NULL);
    }
    private JSONObject read(AgentTaskStore.Task task) throws Exception {
        File file=file(task);if(!file.exists()&&!new File(file.getPath()+".bak").exists()){
            return readOrCreate(task);
        }
        byte[] bytes;try(InputStream input=new AtomicFile(file).openRead()){
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))>=0){if(out.size()+count>MAX_FILE)throw new IllegalStateException("视频操作记录过大");out.write(buffer,0,count);}bytes=out.toByteArray();}
        if(bytes.length==0)throw new IllegalStateException("视频操作记录损坏");
        JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));bindExisting(task,root);
        String[] rootKeys={"schema_version","client_task_id","connection_id","connection_revision","origin","credential_ref","kind","transport","bridge_id","instance_id","remote_task_id","operations","review"};
        boolean complete=true;for(String key:rootKeys)complete&=root.has(key);
        if(root.length()!=13||!complete||!(root.opt("schema_version") instanceof Integer)||root.optInt("schema_version")!=1||root.optJSONArray("operations")==null||!root.has("review")||(!root.isNull("review")&&!(root.opt("review") instanceof JSONObject)))
            throw new IllegalStateException("视频操作记录版本无效");
        JSONArray array=root.getJSONArray("operations");if(array.length()>MAX_OPERATIONS)throw new IllegalStateException("视频操作记录过多");
        for(int i=0;i<array.length();i++){Operation operation=parse(array.getJSONObject(i));if(operation.result!=null){VideoTaskClient.validateInspection(operation.result,task);validateResultBinding(operation);}}
        if(!root.isNull("review"))VideoTaskClient.validateReview(root.getJSONObject("review"),task);
        return root;
    }
    private static void bindExisting(AgentTaskStore.Task task,JSONObject root) {
        if(!task.clientTaskId.equals(root.optString("client_task_id"))||
                !task.connectionId.equals(root.optString("connection_id"))||task.connectionRevision!=root.optLong("connection_revision")||
                !task.remoteTaskId.equals(root.optString("remote_task_id"))||!task.origin.equals(root.optString("origin"))||
                !task.instanceId.equals(root.optString("instance_id"))||!task.credentialRef.equals(root.optString("credential_ref"))||
                !task.kind.name().equals(root.optString("kind"))||!task.transport.name().equals(root.optString("transport"))||!task.bridgeId.equals(root.optString("bridge_id")))
            throw new IllegalStateException("视频操作记录绑定无效");
    }
    private void write(AgentTaskStore.Task task,JSONObject root)throws Exception {
        byte[] bytes=root.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_FILE)throw new IllegalStateException("视频操作记录过大");
        AtomicFile atomic=new AtomicFile(file(task));FileOutputStream out=null;
        try{out=atomic.startWrite();out.write(bytes);out.flush();out.getFD().sync();atomic.finishWrite(out);}
        catch(Exception error){if(out!=null)atomic.failWrite(out);throw error;}
    }
    private File file(AgentTaskStore.Task task) { return new File(directory,task.clientTaskId+".json"); }
    private static Operation parse(JSONObject j)throws Exception {
        String action=j.getString("action"),status=j.getString("status");
        if(!("initialize".equals(action)||"storyboard".equals(action)||"approve".equals(action)||"produce".equals(action))||
                !("prepared".equals(status)||"submission_uncertain".equals(status)||"queued".equals(status)||
                        "running".equals(status)||"succeeded".equals(status)||"failed".equals(status)||"unknown".equals(status)||"cancelled".equals(status)))
            throw new IllegalStateException("视频操作记录状态无效");
        String[] operationKeys={"action","key","client_operation_id","operation_id","status","error","parameters","result","submission_uncertain","created_at","updated_at","cancel_control"};
        if((j.length()!=11&&j.length()!=12)||(j.length()==12&&!j.has("cancel_control")))throw new IllegalStateException("视频操作记录结构无效");
        for(java.util.Iterator<String> keys=j.keys();keys.hasNext();)if(!java.util.Arrays.asList(operationKeys).contains(keys.next()))throw new IllegalStateException("视频操作记录结构无效");
        for(int i=0;i<11;i++)if(!j.has(operationKeys[i]))throw new IllegalStateException("视频操作记录结构无效");
        String key=j.getString("key"), client=j.getString("client_operation_id"),operationId=j.getString("operation_id");
        if(!key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")||!key.equals(client))throw new IllegalStateException("视频幂等键无效");
        boolean uncertain=j.getBoolean("submission_uncertain");
        if(("prepared".equals(status)&&(!operationId.isEmpty()||uncertain))||("submission_uncertain".equals(status)&&(!operationId.isEmpty()||!uncertain))||(!"prepared".equals(status)&&!"submission_uncertain".equals(status)&&(!operationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")||uncertain)))throw new IllegalStateException("视频操作记录绑定无效");
        Object result=j.opt("result");
        JSONObject parameters=j.getJSONObject("parameters");
        if("initialize".equals(action)&&parameters.length()!=0)throw new IllegalStateException("视频初始化参数损坏");
        if("storyboard".equals(action)){if(parameters.length()!=2||!safePositive(parameters,"revision")||!safePositive(parameters,"event_cursor"))throw new IllegalStateException("分镜参数损坏");}
        if("approve".equals(action)){if(parameters.length()!=4||!safePositive(parameters,"revision")||!safePositive(parameters,"event_cursor")||!digest(parameters.optString("review_sha256"))||!digest(parameters.optString("lesson_ir_sha256")))throw new IllegalStateException("批准参数损坏");}
        if("produce".equals(action)){if(parameters.length()!=5||!safePositive(parameters,"revision")||!safePositive(parameters,"event_cursor")||!digest(parameters.optString("review_sha256"))||!digest(parameters.optString("lesson_ir_sha256"))||!Boolean.TRUE.equals(parameters.opt("allow_cloud_tts")))throw new IllegalStateException("视频生成参数损坏");}
        long created=number(j,"created_at"),updated=number(j,"updated_at");if(created<0||updated<created)throw new IllegalStateException("视频操作时间损坏");
        String error=j.getString("error");if(!error.isEmpty()&&!java.util.Arrays.asList("worker_failed","worker_unknown","worker_interrupted","worker_result_invalid","worker_timeout","authorization_revoked","instance_retired","run_unavailable","source_changed","binding_changed","cancelled").contains(error))throw new IllegalStateException("视频操作错误码损坏");
        boolean resultObject=result instanceof JSONObject;
        if(("succeeded".equals(status))!=resultObject||(("failed".equals(status)||"unknown".equals(status)||"cancelled".equals(status))&&resultObject)||(("failed".equals(status)||"unknown".equals(status)||"cancelled".equals(status))&&error.isEmpty())||(("queued".equals(status)||"running".equals(status)||"succeeded".equals(status))&&!error.isEmpty())||(("prepared".equals(status)||"submission_uncertain".equals(status))&&(!error.isEmpty()||resultObject)))throw new IllegalStateException("视频操作结果状态损坏");
        JSONObject cancelControl=j.isNull("cancel_control")?null:j.optJSONObject("cancel_control");
        if(j.has("cancel_control")&&!j.isNull("cancel_control")){
            if(cancelControl==null||!java.util.Arrays.asList("pending","requested","unconfirmed","verified_cancelled","too_late").contains(cancelControl.optString("status"))||!("storyboard".equals(action)||"produce".equals(action))||operationId.isEmpty())throw new IllegalStateException("停止请求记录损坏");
            String controlStatus=cancelControl.getString("status");
            if("pending".equals(controlStatus)){
                if(cancelControl.length()!=2||number(cancelControl,"intent_at_ms")<0)throw new IllegalStateException("本地停止意图损坏");
            }else if("verified_cancelled".equals(controlStatus)&&cancelControl.has("confirmed_at_ms")){
                if(cancelControl.length()!=3||number(cancelControl,"intent_at_ms")<0||number(cancelControl,"confirmed_at_ms")<number(cancelControl,"intent_at_ms"))throw new IllegalStateException("本地停止确认时间损坏");
            }else{
                if(cancelControl.length()!=4)throw new IllegalStateException("远程停止状态字段无效");
                long requested=number(cancelControl,"requested_at"),controlUpdated=number(cancelControl,"updated_at");
                if(number(cancelControl,"intent_at_ms")<0||requested<0||controlUpdated<requested)throw new IllegalStateException("停止请求时间损坏");
            }
            if(("verified_cancelled".equals(controlStatus))!=("cancelled".equals(status)&&"cancelled".equals(error))||
                    (("requested".equals(controlStatus)||"unconfirmed".equals(controlStatus))&&!("running".equals(status)||"unknown".equals(status)))||
                    ("pending".equals(controlStatus)&&!("running".equals(status)||"unknown".equals(status)||"succeeded".equals(status)||"failed".equals(status))))
                throw new IllegalStateException("停止请求状态与操作不匹配");
        }
        return new Operation(action,key,client,operationId,status,j.optString("error"),
                parameters,resultObject?(JSONObject)result:null,
                uncertain,created,updated,cancelControl);
    }
    private static boolean safePositive(JSONObject j,String key){Object v=j.opt(key);return (v instanceof Integer||v instanceof Long)&&((Number)v).longValue()>=1&&((Number)v).longValue()<=9007199254740991L;}
    private static long number(JSONObject j,String key){Object value=j.opt(key);if(!(value instanceof Integer||value instanceof Long))throw new IllegalStateException("视频时间字段损坏");return ((Number)value).longValue();}
    private static boolean digest(String value){return value.matches("[a-f0-9]{64}");}
    private static void validateResultBinding(Operation operation){try{
        if("storyboard".equals(operation.action)){JSONObject p=operation.parameters,r=operation.result;if(r.getLong("revision")!=p.getLong("revision")||r.getLong("event_cursor")!=p.getLong("event_cursor")+2||!"awaiting_storyboard_review".equals(r.getString("status"))||!"awaiting_approval".equals(r.getString("phase")))throw new IllegalStateException("视频分镜结果绑定损坏");}
        if("approve".equals(operation.action)){JSONObject p=operation.parameters,r=operation.result,a=r.getJSONObject("approval");if(r.getLong("event_cursor")!=p.getLong("event_cursor")+1||r.getLong("revision")!=p.getLong("revision")||!r.getString("review_sha256").equals(p.getString("review_sha256"))||!r.getString("lesson_ir_sha256").equals(p.getString("lesson_ir_sha256"))||!"approved".equals(r.getString("status"))||!"approval_pending".equals(r.getString("phase"))||a.getLong("revision")!=p.getLong("revision")||!a.getString("review_sha256").equals(p.getString("review_sha256"))||!a.getString("lesson_ir_sha256").equals(p.getString("lesson_ir_sha256")))throw new IllegalStateException("视频批准结果绑定损坏");}
        if("produce".equals(operation.action)){JSONObject p=operation.parameters,r=operation.result,a=r.getJSONObject("receipt");java.util.Set<String> receiptKeys=new java.util.HashSet<>(java.util.Arrays.asList("operation_id","attempt_id","action","payload_digest","source_snapshot_digest","request_sha256","input_event_cursor","result_event_cursor","revision","review_sha256","lesson_ir_sha256","allow_cloud_tts"));if(!keys(a).equals(receiptKeys)||!"completed".equals(r.getString("status"))||!"completed".equals(r.getString("phase"))||!a.getString("operation_id").equals(operation.operationId)||!a.getString("attempt_id").matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")||!"produce".equals(a.getString("action"))||strictLong(r,"event_cursor")!=strictLong(a,"result_event_cursor")||strictLong(a,"result_event_cursor")<=strictLong(a,"input_event_cursor")||strictLong(a,"input_event_cursor")!=strictLong(p,"event_cursor")||strictLong(a,"revision")!=strictLong(p,"revision")||!Boolean.TRUE.equals(a.opt("allow_cloud_tts"))||!a.getString("review_sha256").equals(p.getString("review_sha256"))||!a.getString("lesson_ir_sha256").equals(p.getString("lesson_ir_sha256")))throw new IllegalStateException("视频生成回执绑定损坏");for(String key:new String[]{"payload_digest","source_snapshot_digest","request_sha256","review_sha256","lesson_ir_sha256"})if(!a.getString(key).matches("[a-f0-9]{64}"))throw new IllegalStateException("视频生成回执摘要损坏");}
        if("produce".equals(operation.action)){JSONObject p=operation.parameters,r=operation.result,a=r.getJSONObject("receipt");if(!"completed".equals(r.getString("status"))||!"completed".equals(r.getString("phase"))||strictLong(r,"event_cursor")!=strictLong(a,"result_event_cursor")||strictLong(a,"input_event_cursor")!=strictLong(p,"event_cursor")||strictLong(a,"revision")!=strictLong(p,"revision")||!a.getString("review_sha256").equals(p.getString("review_sha256"))||!a.getString("lesson_ir_sha256").equals(p.getString("lesson_ir_sha256"))||!a.getString("operation_id").equals(operation.operationId)||!"produce".equals(a.getString("action"))||!Boolean.TRUE.equals(a.opt("allow_cloud_tts")))throw new IllegalStateException("视频生成回执绑定损坏");}
    }catch(Exception e){throw new IllegalStateException("视频操作结果绑定损坏",e);}}
    private static java.util.Set<String> keys(JSONObject object){java.util.HashSet<String> result=new java.util.HashSet<>();java.util.Iterator<String> iterator=object.keys();while(iterator.hasNext())result.add(iterator.next());return result;}
    private static long strictLong(JSONObject object,String key){Object value=object.opt(key);if(!(value instanceof Integer||value instanceof Long))throw new IllegalStateException("视频数值字段类型无效");return ((Number)value).longValue();}
    static boolean terminal(String status){return "succeeded".equals(status)||"failed".equals(status)||"cancelled".equals(status);}
    static JSONObject copy(JSONObject j){if(j==null)return null;try{return new JSONObject(j.toString());}catch(Exception e){throw new IllegalArgumentException(e);}}
    private static String value(String s){return s==null?"":s;}
}
