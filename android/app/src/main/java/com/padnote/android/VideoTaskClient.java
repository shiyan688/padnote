package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Fixed-origin client for the Bridge video projection and durable operations API. */
final class VideoTaskClient {
    static final int MAX_JSON=2*1024*1024, MAX_PNG=8*1024*1024;
    private final AgentHttpTransport http;
    VideoTaskClient(){this(AgentHttpTransport.production());}
    VideoTaskClient(AgentHttpTransport http){this.http=http;}

    JSONObject diagnostics(AgentTaskStore.Task task,AgentConnectionStore.Config connection)throws Exception{
        JSONObject value=json("GET",agentRoot(task,connection)+"/video/diagnostics",task,connection,null,null,MAX_JSON,200);
        exact(value,"schema_version","runtime_verified","video_ready","checks");
        if(!"1.0".equals(value.getString("schema_version"))||value.getBoolean("runtime_verified")||value.getBoolean("video_ready"))throw new IllegalStateException("电脑视频诊断结构无效");
        JSONObject checks=value.getJSONObject("checks");String[] names={"worker_modules","storyboard_browser","render_browser","ffmpeg","ffprobe","tts"};
        if(checks.length()!=names.length)throw new IllegalStateException("电脑视频诊断项目不完整");
        for(String name:names){JSONObject item=checks.getJSONObject(name);exact(item,"status","reason");if(!java.util.Arrays.asList("available","missing","unchecked","not_configured").contains(item.getString("status"))||item.getString("reason").length()>128)throw new IllegalStateException("电脑视频诊断状态无效");}
        return value;
    }
    JSONObject review(AgentTaskStore.Task task,AgentConnectionStore.Config connection)throws Exception{
        JSONObject value=json("GET",root(task,connection)+"/video/review",task,connection,null,null,MAX_JSON,200);
        validateReview(value,task);return value;
    }
    JSONObject submit(AgentTaskStore.Task task,AgentConnectionStore.Config connection,
                      VideoOperationStore.Operation operation)throws Exception{
        requireCapability(connection);
        if("produce".equals(operation.action)&&!connection.capabilities.contains("video_production"))throw new IllegalStateException("此电脑尚未验证配音与视频生成功能");
        JSONObject body=new JSONObject().put("action",operation.action).put("parameters",operation.parameters);
        Map<String,String> headers=new LinkedHashMap<>();headers.put("Idempotency-Key",operation.key);
        JSONObject value=json("POST",root(task,connection)+"/video/operations",task,connection,headers,
                body.toString().getBytes(StandardCharsets.UTF_8),MAX_JSON,202);
        return validateOperation(value,task,operation);
    }
    JSONObject operation(AgentTaskStore.Task task,AgentConnectionStore.Config connection,
                         VideoOperationStore.Operation operation)throws Exception{
        requireOperationId(operation.operationId);
        JSONObject value=json("GET",root(task,connection)+"/video/operations/"+path(operation.operationId),
                task,connection,null,null,MAX_JSON,200);
        return validateOperation(value,task,operation);
    }
    JSONObject cancel(AgentTaskStore.Task task,AgentConnectionStore.Config connection,
                      VideoOperationStore.Operation operation)throws Exception{
        requireOperationId(operation.operationId);
        JSONObject value=json("POST",root(task,connection)+"/video/operations/"+path(operation.operationId)+"/cancel",
                task,connection,null,new byte[]{'{','}'},MAX_JSON,200);
        return validateOperation(value,task,operation);
    }
    JSONObject reconcile(AgentTaskStore.Task task,AgentConnectionStore.Config connection,
                         VideoOperationStore.Operation operation)throws Exception{
        requireOperationId(operation.operationId);
        if(!"unknown".equals(operation.status))throw new IllegalStateException("只有结果未知的操作可以核对");
        JSONObject value=json("POST",root(task,connection)+"/video/operations/"+path(operation.operationId)+"/reconcile",
                task,connection,null,new byte[]{'{','}'},MAX_JSON,200);
        String status=value.optString("status");
        if(!"unknown".equals(status)&&!"succeeded".equals(status)&&!"cancelled".equals(status))
            throw new IllegalStateException("核对响应状态无效");
        return validateOperation(value,task,operation);
    }
    JSONObject cancelRunning(AgentTaskStore.Task task,AgentConnectionStore.Config connection,
                             VideoOperationStore.Operation operation)throws Exception{
        requireOperationId(operation.operationId);
        if(!("storyboard".equals(operation.action)||"produce".equals(operation.action))||
                !("running".equals(operation.status)||"unknown".equals(operation.status)))
            throw new IllegalStateException("当前操作不支持运行中停止请求");
        String suffix="produce".equals(operation.action)?"cancel":"cancel-running";
        JSONObject value=json("POST",root(task,connection)+"/video/operations/"+path(operation.operationId)+"/"+suffix,
                task,connection,null,new byte[]{'{','}'},MAX_JSON,200);
        return validateCancelRequest(value,task,operation);
    }
    JSONObject cancelRequest(AgentTaskStore.Task task,AgentConnectionStore.Config connection,
                            VideoOperationStore.Operation operation)throws Exception{
        requireOperationId(operation.operationId);
        String suffix="produce".equals(operation.action)?"cancel":"cancel-running";
        JSONObject value=json("GET",root(task,connection)+"/video/operations/"+path(operation.operationId)+"/"+suffix,
                task,connection,null,null,MAX_JSON,200);
        return validateCancelRequest(value,task,operation);
    }
    private static JSONObject validateCancelRequest(JSONObject value,AgentTaskStore.Task task,
                                                     VideoOperationStore.Operation operation)throws Exception{
        exact(value,"object","protocol_version","operation_id","task_id","status","requested_at","updated_at");
        if(!"padnote.video.cancel_request".equals(value.getString("object"))||strictLong(value,"protocol_version")!=1||
                !operation.operationId.equals(value.getString("operation_id"))||!task.remoteTaskId.equals(value.getString("task_id"))||
                !java.util.Arrays.asList("requested","unconfirmed","verified_cancelled","too_late").contains(value.getString("status")))
            throw new IllegalStateException("停止请求响应绑定无效");
        long requested=strictLong(value,"requested_at"),updated=strictLong(value,"updated_at");
        if(requested<0||updated<requested)throw new IllegalStateException("停止请求时间无效");
        return value;
    }
    byte[] preview(AgentTaskStore.Task task,AgentConnectionStore.Config connection,JSONObject review,
                   JSONObject metadata)throws Exception{
        validateReview(review,task);
        String id=metadata.optString("id");if(!id.matches("[a-f0-9]{64}"))throw new IllegalStateException("预览图片ID无效");
        boolean listed=false;JSONArray scenes=review.getJSONArray("scenes");for(int i=0;i<scenes.length();i++){
            JSONObject p=scenes.getJSONObject(i).getJSONObject("preview");
            if(id.equals(p.optString("id"))&&p.toString().equals(metadata.toString()))listed=true;
        }if(!listed)throw new IllegalStateException("预览图片不属于当前审阅版本");
        long declaredLong=strictLong(metadata,"size_bytes");if(declaredLong<1||declaredLong>MAX_PNG)throw new IllegalStateException("预览图片声明无效");int declared=(int)declaredLong;if(
                !"image/png".equals(metadata.optString("media_type"))||!metadata.optString("sha256").matches("[a-f0-9]{64}"))
            throw new IllegalStateException("预览图片声明无效");
        URL url=new URL(root(task,connection)+"/video/previews/"+path(id));
        AgentHttpTransport.Response response=http.execute(new AgentHttpTransport.Request("GET",url,
                connection.token,Collections.emptyMap(),null,MAX_PNG,connection.certSha256));
        AgentConnectionClient.rejectRedirect(response);identity(task,connection);
        String length=header(response,"Content-Length");if(!length.isEmpty())try{if(Long.parseLong(length)!=declared)throw new IllegalStateException("预览声明长度不匹配");}catch(NumberFormatException e){throw new IllegalStateException("预览响应长度无效");}
        if(response.status!=200||response.body.length!=declared||response.body.length>MAX_PNG)
            throw new IllegalStateException("预览图片长度不匹配");
        String type=header(response,"Content-Type");if(!"image/png".equals(type.trim().toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalStateException("预览响应不是PNG");
        byte[] b=response.body;
        if(b.length<33||b[0]!=(byte)0x89||b[1]!='P'||b[2]!='N'||b[3]!='G'||b[4]!='\r'||b[5]!='\n'||b[6]!=0x1a||b[7]!='\n'||b[12]!='I'||b[13]!='H'||b[14]!='D'||b[15]!='R')
            throw new IllegalStateException("预览PNG头无效");
        long width=u32(b,16),height=u32(b,20);if(width<1||height<1||width>4096||height>4096||width*height>8388608L||
                width!=metadata.optLong("width")||height!=metadata.optLong("height"))throw new IllegalStateException("预览PNG尺寸无效");
        if(!sha256(b).equals(metadata.optString("sha256")))throw new IllegalStateException("预览图片摘要不匹配");
        return b;
    }
    private JSONObject json(String method,String text,AgentTaskStore.Task task,AgentConnectionStore.Config connection,
                            Map<String,String> headers,byte[] body,int limit,int expected)throws Exception{
        identity(task,connection);URL url=new URL(text);
        AgentHttpTransport.Response response=http.execute(new AgentHttpTransport.Request(method,url,connection.token,
                headers,body,limit,connection.certSha256));AgentConnectionClient.rejectRedirect(response);identity(task,connection);
        if(response.status!=expected)throw new HttpError(response.status);
        JSONObject value=new JSONObject(response.utf8());if(value.length()>32)throw new IllegalStateException("视频响应字段过多");
        return value;
    }
    private static JSONObject validateOperation(JSONObject j,AgentTaskStore.Task task,
                                                 VideoOperationStore.Operation operation)throws Exception{
        exact(j,"object","protocol_version","operation_id","task_id","client_operation_id","action","status","created_at","updated_at","result","error");
        if(!"padnote.video.operation".equals(j.getString("object"))||strictLong(j,"protocol_version")!=1||
                !task.remoteTaskId.equals(j.getString("task_id"))||!operation.key.equals(j.getString("client_operation_id"))||
                !operation.action.equals(j.getString("action")))throw new IllegalStateException("视频操作响应绑定不匹配");
        requireOperationId(j.getString("operation_id"));
        if(!operation.operationId.isEmpty()&&!operation.operationId.equals(j.getString("operation_id")))throw new IllegalStateException("视频操作ID响应不匹配");
        String status=j.getString("status");if(!java.util.Arrays.asList("queued","running","succeeded","failed","unknown","cancelled").contains(status))
            throw new IllegalStateException("视频操作状态未知");
        if("cancelled".equals(status)&&(!"cancelled".equals(j.optString("error"))||!j.isNull("result")))
            throw new IllegalStateException("取消结果投影无效");
        long created=strictLong(j,"created_at"),updated=strictLong(j,"updated_at");if(created<0||updated<created)throw new IllegalStateException("视频操作时间无效");
        if(!j.isNull("error")&&(!(j.get("error") instanceof String)||!java.util.Arrays.asList("worker_failed","worker_unknown","worker_interrupted","worker_result_invalid","worker_timeout","authorization_revoked","instance_retired","run_unavailable","source_changed","binding_changed","cancelled").contains(j.getString("error"))))throw new IllegalStateException("视频操作错误字段无效");
        if(!j.isNull("result")){
            JSONObject result=j.getJSONObject("result");validateInspection(result,task);
            if("storyboard".equals(operation.action)&&("succeeded".equals(status))){JSONObject p=operation.parameters;
                if(strictLong(result,"revision")!=strictLong(p,"revision")||strictLong(result,"event_cursor")!=strictLong(p,"event_cursor")+2||
                        !"awaiting_storyboard_review".equals(result.getString("status"))||!"awaiting_approval".equals(result.getString("phase")))throw new IllegalStateException("分镜结果与提交版本不匹配");}
            if("approve".equals(operation.action)&&("succeeded".equals(status))){JSONObject p=operation.parameters,a=result.optJSONObject("approval");
                if(a==null||strictLong(result,"event_cursor")!=strictLong(p,"event_cursor")+1||!"approved".equals(result.getString("status"))||!"approval_pending".equals(result.getString("phase"))||
                        strictLong(result,"revision")!=strictLong(p,"revision")||!result.getString("review_sha256").equals(p.getString("review_sha256"))||!result.getString("lesson_ir_sha256").equals(p.getString("lesson_ir_sha256"))||
                        strictLong(a,"revision")!=strictLong(p,"revision")||!a.getString("review_sha256").equals(p.getString("review_sha256"))||!a.getString("lesson_ir_sha256").equals(p.getString("lesson_ir_sha256")))throw new IllegalStateException("批准结果与审阅版本不匹配");}
            if("produce".equals(operation.action)&&"succeeded".equals(status)){JSONObject p=operation.parameters,a=result.optJSONObject("receipt");
                if(a==null||!"completed".equals(result.getString("status"))||!"completed".equals(result.getString("phase"))||
                        !keys(a).equals(new java.util.HashSet<>(java.util.Arrays.asList("operation_id","attempt_id","action","payload_digest","source_snapshot_digest","request_sha256","input_event_cursor","result_event_cursor","revision","review_sha256","lesson_ir_sha256","allow_cloud_tts")))||
                        !a.getString("operation_id").equals(j.getString("operation_id"))||!a.getString("attempt_id").matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")||
                        strictLong(result,"event_cursor")!=strictLong(a,"result_event_cursor")||strictLong(a,"result_event_cursor")<=strictLong(a,"input_event_cursor")||strictLong(a,"input_event_cursor")!=strictLong(p,"event_cursor")||strictLong(a,"revision")!=strictLong(p,"revision")||
                        !a.getString("review_sha256").equals(p.getString("review_sha256"))||!a.getString("lesson_ir_sha256").equals(p.getString("lesson_ir_sha256"))||
                        !"produce".equals(a.getString("action"))||!(a.opt("allow_cloud_tts") instanceof Boolean)||!a.getBoolean("allow_cloud_tts")||
                        !digest(a,"payload_digest")||!digest(a,"source_snapshot_digest")||!digest(a,"request_sha256")||
                        !digest(a,"review_sha256")||!digest(a,"lesson_ir_sha256"))throw new IllegalStateException("视频生成回执绑定无效");}
        }
        return j;
    }
    static void validateReview(JSONObject j,AgentTaskStore.Task task)throws Exception{
        exact(j,"object","protocol_version","task_id","worker_task_id","status","event_cursor","revision","review_sha256","lesson_ir_sha256","episode","scenes");
        if(!isVideoBundle(task)||!"padnote.video.review".equals(j.getString("object"))||strictLong(j,"protocol_version")!=1||
                !task.remoteTaskId.equals(j.getString("task_id"))||!workerTaskId(task).equals(j.getString("worker_task_id"))||
                !j.getString("status").matches("awaiting_storyboard_review|approved|ready_to_render|interrupted|failed|cancelled|completed")||
                strictLong(j,"event_cursor")<1||strictLong(j,"revision")<1||strictLong(j,"revision")>9007199254740991L||
                !j.getString("review_sha256").matches("[a-f0-9]{64}")||!j.getString("lesson_ir_sha256").matches("[a-f0-9]{64}"))
            throw new IllegalStateException("视频审阅版本无效");
        JSONObject episode=j.getJSONObject("episode");exact(episode,"title","audience","learning_goal","language");
        bounded(episode,"title",300);bounded(episode,"audience",500);bounded(episode,"learning_goal",500);bounded(episode,"language",32);
        JSONArray scenes=j.getJSONArray("scenes");if(scenes.length()<1||scenes.length()>60)throw new IllegalStateException("分镜数量无效");
        java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(int i=0;i<scenes.length();i++){
            JSONObject scene=scenes.getJSONObject(i);exact(scene,"id","learning_objective","narration","screen_text","visual_kind","preview");
            String id=scene.getString("id");if(!id.matches("[a-z][a-z0-9-]{0,63}")||!ids.add(id))throw new IllegalStateException("场景ID无效");
            bounded(scene,"learning_objective",500);bounded(scene,"narration",3000);
            String kind=scene.getString("visual_kind");if(!java.util.Arrays.asList("title","formula_steps","concept_map","process","comparison","annotated_source","quantity_change").contains(kind))throw new IllegalStateException("场景类型无效");
            JSONArray text=scene.getJSONArray("screen_text");if(text.length()>8)throw new IllegalStateException("屏幕文字过多");for(int n=0;n<text.length();n++)if(!(text.get(n) instanceof String)||((String)text.get(n)).isEmpty()||((String)text.get(n)).length()>300)throw new IllegalStateException("屏幕文字无效");
            JSONObject p=scene.getJSONObject("preview");exact(p,"id","media_type","size_bytes","sha256","width","height");
            long size=strictLong(p,"size_bytes"),width=strictLong(p,"width"),height=strictLong(p,"height");if(!p.getString("id").matches("[a-f0-9]{64}")||!"image/png".equals(p.getString("media_type"))||size<1||size>MAX_PNG||!p.getString("sha256").matches("[a-f0-9]{64}")||width<1||width>4096||height<1||height>4096||width*height>8388608L)throw new IllegalStateException("预览声明无效");
            if(!previewId(j,scene).equals(p.getString("id")))throw new IllegalStateException("预览ID未绑定当前审阅版本");
        }
    }
    static String previewId(JSONObject review,JSONObject scene)throws Exception{
        String sceneId=scene.getString("id");
        if(!sceneId.matches("[a-z][a-z0-9-]{0,63}"))throw new IllegalStateException("场景ID无效");
        String material="{\"lesson_ir_sha256\":\""+review.getString("lesson_ir_sha256")+
                "\",\"revision\":"+strictLong(review,"revision")+
                ",\"review_sha256\":\""+review.getString("review_sha256")+
                "\",\"scene_id\":\""+sceneId+"\"}";
        return sha256(material.getBytes(StandardCharsets.UTF_8));
    }
    static void validateInspection(JSONObject result,AgentTaskStore.Task task)throws Exception{
        java.util.Set<String> required=new java.util.HashSet<>(java.util.Arrays.asList("protocol_version","task_id","status","phase","event_cursor"));
        java.util.Set<String> allowed=new java.util.HashSet<>(required);allowed.addAll(java.util.Arrays.asList("revision","lesson_ir_sha256","review_sha256","approval","receipt"));
        java.util.Set<String> resultKeys=keys(result);if(!allowed.containsAll(resultKeys)||!resultKeys.containsAll(required)||!task.remoteTaskId.equals(result.getString("task_id"))||strictLong(result,"protocol_version")!=1||
                !java.util.Arrays.asList("initialized","awaiting_storyboard_review","approved","ready_to_render","running","interrupted","failed","cancelling","cancelled","completed").contains(result.getString("status"))||
                !java.util.Arrays.asList("idle","storyboard","awaiting_approval","approval_pending","approval_consumed","tts_starting","audio_ready","render_starting","completed","cancelling","cancelled").contains(result.getString("phase"))||strictLong(result,"event_cursor")<1||strictLong(result,"event_cursor")>9007199254740991L)
            throw new IllegalStateException("视频操作inspection无效");
        boolean revision=result.has("revision"),ir=result.has("lesson_ir_sha256"),review=result.has("review_sha256");
        if(revision!=ir||revision!=review)throw new IllegalStateException("视频inspection版本绑定不完整");
        if(revision&&(strictLong(result,"revision")<1||strictLong(result,"revision")>9007199254740991L||!result.getString("lesson_ir_sha256").matches("[a-f0-9]{64}")||!result.getString("review_sha256").matches("[a-f0-9]{64}")))throw new IllegalStateException("视频inspection摘要无效");
        if(result.has("approval")){JSONObject a=result.getJSONObject("approval");java.util.Set<String> ak=new java.util.HashSet<>(java.util.Arrays.asList("approval_id","revision","lesson_ir_sha256","review_sha256","granted_at","consumed_at"));java.util.Set<String> approvalKeys=keys(a);if(!ak.containsAll(approvalKeys)||!approvalKeys.containsAll(java.util.Arrays.asList("approval_id","revision","lesson_ir_sha256","review_sha256","granted_at"))||!a.getString("approval_id").matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")||strictLong(a,"revision")<1||!a.getString("lesson_ir_sha256").matches("[a-f0-9]{64}")||!a.getString("review_sha256").matches("[a-f0-9]{64}")||!a.getString("granted_at").matches("\\d{4}-\\d{2}-\\d{2}T[^\\s]{1,64}")||(a.has("consumed_at")&&!a.getString("consumed_at").matches("\\d{4}-\\d{2}-\\d{2}T[^\\s]{1,64}")))throw new IllegalStateException("视频approval摘要无效");if(revision&&(strictLong(a,"revision")!=strictLong(result,"revision")||!a.getString("lesson_ir_sha256").equals(result.getString("lesson_ir_sha256"))||!a.getString("review_sha256").equals(result.getString("review_sha256"))))throw new IllegalStateException("视频approval摘要与版本不匹配");}
    }
    static boolean isVideoBundle(AgentTaskStore.Task task){
        try{
            if(task==null||task.transport!=AgentConnectionStore.Transport.BRIDGE)return false;
            JSONObject submission=new JSONObject(task.submissionJson);
            if(!task.clientTaskId.equals(submission.getString("client_task_id"))||
                    !AgentTaskStore.sha256(task.submissionJson.getBytes(StandardCharsets.UTF_8)).equals(task.submissionSha256))return false;
            String encoded=submission.getString("bundle_base64");if(encoded.length()>((8*1024*1024+2)/3)*4)return false;
            byte[] zip=decodeCanonicalBase64(encoded);if(zip.length>8*1024*1024||!sha256(zip).equalsIgnoreCase(submission.getString("bundle_sha256")))return false;
            ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(zip));ZipEntry entry;byte[] request=null;int entries=0;long total=0;
            while((entry=in.getNextEntry())!=null){if(++entries>8)return false;ByteArrayOutputStream out="request.json".equals(entry.getName())?new ByteArrayOutputStream():null;byte[] buffer=new byte[4096];int n;long entryBytes=0;
                while((n=in.read(buffer))>=0){entryBytes+=n;total+=n;if(total>8*1024*1024L||entryBytes>8*1024*1024L||("request.json".equals(entry.getName())&&entryBytes>1024*1024L))return false;if(out!=null)out.write(buffer,0,n);}
                if("request.json".equals(entry.getName())){if(request!=null||entry.isDirectory())return false;request=out.toByteArray();}}
            if(request==null)return false;JSONObject body=new JSONObject(new String(request,StandardCharsets.UTF_8));
            return "video.explain.v1".equals(body.getString("task_type"))&&body.getString("task_id").matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
        }catch(Exception ignored){return false;}
    }
    private static void exact(JSONObject object,String...keys){java.util.HashSet<String> expected=new java.util.HashSet<>(java.util.Arrays.asList(keys));if(object.length()!=expected.size()||!keys(object).equals(expected))throw new IllegalStateException("视频响应结构无效");}
    private static java.util.Set<String> keys(JSONObject object){java.util.HashSet<String> result=new java.util.HashSet<>();java.util.Iterator<String> iterator=object.keys();while(iterator.hasNext())result.add(iterator.next());return result;}
    private static void bounded(JSONObject j,String key,int max)throws Exception{String s=j.getString(key);if(s.isEmpty()||s.length()>max)throw new IllegalStateException("视频文本超出范围");}
    private static String agentRoot(AgentTaskStore.Task task,AgentConnectionStore.Config c)throws Exception{identity(task,c);return AgentConnectionClient.normalizeEndpoint(task.origin)+"/padnote/v1/agents/"+AgentConnectionClient.path(task.instanceId);}
    private static String root(AgentTaskStore.Task task,AgentConnectionStore.Config c)throws Exception{return agentRoot(task,c)+"/runs/"+AgentConnectionClient.path(task.remoteTaskId);}
    private static void identity(AgentTaskStore.Task task,AgentConnectionStore.Config c){
        if(task==null||c==null||task.transport!=AgentConnectionStore.Transport.BRIDGE||task.remoteTaskId.isEmpty()||!task.matches(c)||
                c.token==null||c.token.isEmpty()||!(c.kind==AgentConnectionStore.Kind.HERMES||c.kind==AgentConnectionStore.Kind.BUILTIN_VIDEO))
            throw new IllegalStateException("Bridge视频连接身份不可用");
        String pin;
        try{pin=AgentCertificatePin.normalize(c.certSha256);}catch(IllegalArgumentException invalid){
            throw new IllegalStateException("Bridge视频连接证书指纹无效",invalid);
        }
        if(c.kind==AgentConnectionStore.Kind.BUILTIN_VIDEO&&pin.isEmpty())
            throw new IllegalStateException("内置视频连接缺少证书指纹");
    }
    private static void requireCapability(AgentConnectionStore.Config c){if(!c.capabilities.contains("video_operations"))throw new IllegalStateException("Bridge尚未验证视频操作能力，请刷新连接状态");}
    private static void requireOperationId(String id){if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw new IllegalStateException("视频操作ID无效");}
    private static long strictLong(JSONObject object,String key){Object value=object.opt(key);if(!(value instanceof Integer||value instanceof Long))throw new IllegalStateException("视频数值字段类型无效");return ((Number)value).longValue();}
    private static boolean digest(JSONObject object,String key){return object.optString(key).matches("[a-f0-9]{64}");}
    private static String workerTaskId(AgentTaskStore.Task task)throws Exception{JSONObject submission=new JSONObject(task.submissionJson);byte[] zip=decodeCanonicalBase64(submission.getString("bundle_base64"));if(zip.length>8*1024*1024||!sha256(zip).equalsIgnoreCase(submission.getString("bundle_sha256")))throw new IllegalStateException("视频任务包摘要无效");ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(zip));ZipEntry entry;long total=0;int count=0;while((entry=in.getNextEntry())!=null){if(++count>8)throw new IllegalStateException("视频任务包无效");ByteArrayOutputStream out="request.json".equals(entry.getName())?new ByteArrayOutputStream():null;byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))>=0){total+=n;if(total>8*1024*1024L||("request.json".equals(entry.getName())&&out.size()+n>1024*1024))throw new IllegalStateException("视频任务包过大");if(out!=null)out.write(buffer,0,n);}if(out!=null)return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)).getString("task_id");}throw new IllegalStateException("视频任务包缺少request");}
    private static byte[] decodeCanonicalBase64(String value){
        if(value==null||value.isEmpty()||value.length()%4!=0||value.length()>12*1024*1024)throw new IllegalStateException("视频任务包编码无效");
        int length=value.length(),padding=value.endsWith("==")?2:value.endsWith("=")?1:0;
        long decoded=(long)(length/4)*3-padding;if(decoded<1||decoded>8*1024*1024L)throw new IllegalStateException("视频任务包过大");
        byte[] out=new byte[(int)decoded];int at=0;
        for(int i=0;i<length;i+=4){boolean last=i+4==length;int a=base64Value(value.charAt(i)),b=base64Value(value.charAt(i+1));
            char c=value.charAt(i+2),d=value.charAt(i+3);int cv=c=='='?-1:base64Value(c),dv=d=='='?-1:base64Value(d);
            if(a<0||b<0||cv<0&&c!='='||dv<0&&d!='='||(!last&&(c=='='||d=='='))||c=='='&&d!='='||c=='='&&(b&15)!=0||d=='='&&c!='='&&(cv&3)!=0)throw new IllegalStateException("视频任务包编码无效");
            int bits=(a<<18)|(b<<12)|((cv<0?0:cv)<<6)|(dv<0?0:dv);out[at++]=(byte)(bits>>>16);if(c!='=')out[at++]=(byte)(bits>>>8);if(d!='=')out[at++]=(byte)bits;
        }
        return out;
    }
    private static int base64Value(char c){if(c>='A'&&c<='Z')return c-'A';if(c>='a'&&c<='z')return c-'a'+26;if(c>='0'&&c<='9')return c-'0'+52;if(c=='+')return 62;if(c=='/')return 63;return -1;}
    private static String path(String s)throws Exception{return AgentConnectionClient.path(s);}
    private static String header(AgentHttpTransport.Response r,String name){for(Map.Entry<String,java.util.List<String>> e:r.headers.entrySet())if(e.getKey()!=null&&e.getKey().equalsIgnoreCase(name)&&!e.getValue().isEmpty())return e.getValue().get(0);return "";}
    private static long u32(byte[] b,int i){return ((long)(b[i]&255)<<24)|((long)(b[i+1]&255)<<16)|((long)(b[i+2]&255)<<8)|(b[i+3]&255L);}
    private static String sha256(byte[] b)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte x:d)s.append(String.format("%02x",x&255));return s.toString();}
    static final class HttpError extends Exception {final int status;HttpError(int status){super("视频服务请求失败（HTTP "+status+"）");this.status=status;}}
}
