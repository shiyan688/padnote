package com.padnote.android;

import android.content.Context;
import android.util.Base64;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Durable Agent tasks. Each file includes its immutable connection identity and payload. */
final class AgentTaskStore {
    enum Status {
        SUBMITTING, RUNNING, WAITING_FOR_APPROVAL, STOPPING,
        COMPLETED, FAILED, CANCELLED, INTERRUPTED;

        boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
        }
    }

    static final class Artifact {
        final String id, name, mediaType, sha256;
        final long sizeBytes;
        Artifact(String id, String name, String mediaType, long sizeBytes, String sha256) {
            this.id = id; this.name = name; this.mediaType = mediaType;
            this.sizeBytes = sizeBytes; this.sha256 = sha256;
        }
        JSONObject toJson() { try { return new JSONObject().put("id", id).put("name", name)
                .put("media_type", mediaType).put("size_bytes", sizeBytes).put("sha256", sha256); }
            catch (org.json.JSONException error) { throw new IllegalStateException(error); } }
        static Artifact fromJson(JSONObject j) { return new Artifact(j.optString("id"),
                j.optString("name"), j.optString("media_type"), j.optLong("size_bytes"),
                j.optString("sha256")); }
    }

    static final class Task {
        final String clientTaskId;
        final String connectionId;
        final long connectionRevision;
        final String origin;
        final AgentConnectionStore.Kind kind;
        final AgentConnectionStore.Transport transport;
        final String credentialRef, bridgeId, instanceId;
        final String connectionName;
        final String title, noteId;
        final long noteRevision;
        final String submissionJson, submissionSha256;
        final String remoteTaskId;
        final String conversationId, parentTaskId, parentClientTaskId, childClientTaskId;
        final boolean followupAvailable;
        final String followupReason;
        final Status status;
        final String output, error, approvalId, approvalTitle, approvalDescription;
        final List<Artifact> artifacts;
        final long createdAt, updatedAt;

        Task(String clientTaskId, String connectionId, long connectionRevision, String origin,
             AgentConnectionStore.Kind kind, AgentConnectionStore.Transport transport,
             String credentialRef, String bridgeId, String instanceId, String title,
             String connectionName,
             String noteId, long noteRevision, String submissionJson, String submissionSha256,
             String remoteTaskId, Status status, String output, String error,
             String approvalId, String approvalTitle, String approvalDescription,
             List<Artifact> artifacts, long createdAt, long updatedAt) {
            this(clientTaskId, connectionId, connectionRevision, origin, kind, transport,
                    credentialRef, bridgeId, instanceId, title, connectionName, noteId,
                    noteRevision, submissionJson, submissionSha256, remoteTaskId, status,
                    output, error, approvalId, approvalTitle, approvalDescription, artifacts,
                    createdAt, updatedAt, "", "", "", "", false, "");
        }

        Task(String clientTaskId, String connectionId, long connectionRevision, String origin,
             AgentConnectionStore.Kind kind, AgentConnectionStore.Transport transport,
             String credentialRef, String bridgeId, String instanceId, String title,
             String connectionName, String noteId, long noteRevision, String submissionJson,
             String submissionSha256, String remoteTaskId, Status status, String output,
             String error, String approvalId, String approvalTitle, String approvalDescription,
             List<Artifact> artifacts, long createdAt, long updatedAt, String conversationId,
             String parentTaskId, String parentClientTaskId, String childClientTaskId,
             boolean followupAvailable, String followupReason) {
            this.clientTaskId = clientTaskId; this.connectionId = connectionId;
            this.connectionRevision = connectionRevision; this.origin = origin;
            this.kind = kind; this.transport = transport; this.credentialRef = credentialRef;
            this.bridgeId = bridgeId; this.instanceId = instanceId; this.title = title;
            this.connectionName = connectionName;
            this.noteId = noteId; this.noteRevision = noteRevision;
            this.submissionJson = submissionJson; this.submissionSha256 = submissionSha256;
            this.remoteTaskId = remoteTaskId; this.status = status; this.output = output;
            this.conversationId=value(conversationId); this.parentTaskId=value(parentTaskId);
            this.parentClientTaskId=value(parentClientTaskId); this.childClientTaskId=value(childClientTaskId);
            this.followupAvailable=followupAvailable; this.followupReason=value(followupReason);
            this.error = error; this.approvalId = approvalId; this.approvalTitle = approvalTitle;
            this.approvalDescription = approvalDescription;
            this.artifacts = Collections.unmodifiableList(new ArrayList<>(artifacts));
            this.createdAt = createdAt; this.updatedAt = updatedAt;
        }

        boolean matches(AgentConnectionStore.Config connection) {
            return connection != null && connectionId.equals(connection.id) &&
                    connectionRevision == connection.revision && origin.equals(connection.endpoint) &&
                    kind == connection.kind && transport == connection.transport &&
                    credentialRef.equals(connection.credentialRef) &&
                    instanceId.equals(connection.instanceId) && bridgeId.equals(connection.bridgeId);
        }

        JSONObject toJson() {
            JSONArray files = new JSONArray(); for (Artifact artifact : artifacts) files.put(artifact.toJson());
            try { return new JSONObject().put("schemaVersion", 2).put("clientTaskId", clientTaskId)
                    .put("connectionId", connectionId).put("connectionRevision", connectionRevision)
                    .put("origin", origin).put("kind", kind.name()).put("transport", transport.name())
                    .put("credentialRef", credentialRef).put("bridgeId", bridgeId)
                    .put("instanceId", instanceId).put("title", title).put("noteId", noteId)
                    .put("connectionName", connectionName)
                    .put("noteRevision", noteRevision).put("submissionJson", submissionJson)
                    .put("submissionSha256", submissionSha256).put("remoteTaskId", remoteTaskId)
                    .put("status", status.name()).put("output", output).put("error", error)
                    .put("approvalId", approvalId).put("approvalTitle", approvalTitle)
                    .put("approvalDescription", approvalDescription).put("artifacts", files)
                    .put("createdAt", createdAt).put("updatedAt", updatedAt)
                    .put("conversationId",conversationId).put("parentTaskId",parentTaskId)
                    .put("parentClientTaskId",parentClientTaskId).put("childClientTaskId",childClientTaskId)
                    .put("followupAvailable",followupAvailable).put("followupReason",followupReason); }
            catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        }

        static Task fromJson(JSONObject j) {
            List<Artifact> artifacts = new ArrayList<>(); JSONArray a = j.optJSONArray("artifacts");
            if (a != null) for (int i=0;i<a.length();i++) { JSONObject x=a.optJSONObject(i); if(x!=null) artifacts.add(Artifact.fromJson(x)); }
            return new Task(required(j,"clientTaskId"), required(j,"connectionId"),
                    j.optLong("connectionRevision"), required(j,"origin"),
                    AgentConnectionStore.Kind.valueOf(required(j,"kind")),
                    AgentConnectionStore.Transport.valueOf(required(j,"transport")),
                    required(j,"credentialRef"), j.optString("bridgeId"), j.optString("instanceId"),
                    j.optString("title"), j.optString("connectionName","电脑 Agent"),
                    j.optString("noteId"), j.optLong("noteRevision"),
                    required(j,"submissionJson"), required(j,"submissionSha256"),
                    j.optString("remoteTaskId"), Status.valueOf(required(j,"status")),
                    j.optString("output"), j.optString("error"), j.optString("approvalId"),
                    j.optString("approvalTitle"), j.optString("approvalDescription"), artifacts,
                    j.optLong("createdAt"), j.optLong("updatedAt"), j.optString("conversationId"),
                    j.optString("parentTaskId"),j.optString("parentClientTaskId"),
                    j.optString("childClientTaskId"),j.optBoolean("followupAvailable",false),
                    j.optString("followupReason"));
        }
    }

    private static final Object LOCK = new Object();
    private static final int MAX_INPUT = 128 * 1024;
    private static final int MAX_BUNDLE = 8 * 1024 * 1024;
    private final File directory;
    private int lastCorruptCount;

    AgentTaskStore(Context context) {
        this(new File(context.getFilesDir(), "agent-tasks"));
    }

    AgentTaskStore(File directory) {
        this.directory=directory;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建 Agent 任务目录");
        }
    }

    Task create(AgentConnectionStore.Config connection, String title, String input,
                String noteId, long noteRevision, byte[] bundle) throws Exception {
        if (connection == null || connection.id.isEmpty() || !connection.verified())
            throw new IllegalArgumentException("请选择已验证的 Agent");
        byte[] inputBytes = value(input).getBytes(StandardCharsets.UTF_8);
        if (inputBytes.length == 0 || inputBytes.length > MAX_INPUT)
            throw new IllegalArgumentException("任务说明必须在 1–128 KiB 之间");
        if (title == null || title.isEmpty() || title.length() > 256)
            throw new IllegalArgumentException("任务标题必须在 1–256 字符之间");
        if (bundle != null && bundle.length > MAX_BUNDLE)
            throw new IllegalArgumentException("任务包不能超过 8 MiB");
        String clientId = "padnote-" + UUID.randomUUID().toString().replace("-", "");
        String sourceNoteId = value(noteId).isEmpty() ? "text-" + clientId.substring(8) : value(noteId);
        JSONObject body;
        if (connection.transport == AgentConnectionStore.Transport.BRIDGE) {
            body = new JSONObject().put("client_task_id", clientId).put("title", title)
                    .put("input", input).put("source", new JSONObject().put("note_id", sourceNoteId)
                            .put("note_revision", Math.max(1L, noteRevision)));
            if (bundle != null && bundle.length > 0) {
                body.put("bundle_base64", Base64.encodeToString(bundle, Base64.NO_WRAP));
                body.put("bundle_sha256", sha256(bundle));
            }
        } else {
            if (bundle != null && bundle.length > 0)
                throw new IllegalArgumentException("Hermes 直连不支持任务包，请改用 Bridge");
            body = new JSONObject().put("input", input);
        }
        String json = body.toString(); long now = System.currentTimeMillis();
        Task task = new Task(clientId, connection.id, connection.revision, connection.endpoint,
                connection.kind, connection.transport, connection.credentialRef,
                connection.bridgeId, connection.instanceId, title, connection.name, sourceNoteId,
                Math.max(1L,noteRevision), json, sha256(json.getBytes(StandardCharsets.UTF_8)),
                "", Status.SUBMITTING, "", "", "", "", "", Collections.emptyList(), now, now);
        write(task); return task;
    }

    Task createFollowup(Task parent, AgentConnectionStore.Config connection, String input)
            throws Exception {
        synchronized (LOCK) {
            Task current=get(parent.clientTaskId);
            if(current==null)throw new IllegalArgumentException("父任务记录不存在");
            if(current.transport!=AgentConnectionStore.Transport.BRIDGE || !current.matches(connection))
                throw new IllegalStateException("任务连接身份已变化，不能继续沟通");
            if(current.childClientTaskId.length()>0) {
                Task child=get(current.childClientTaskId); if(child!=null)return existingFollowup(child,input);
            }
            for(Task candidate:list()) if(parent.clientTaskId.equals(candidate.parentClientTaskId))return existingFollowup(candidate,input);
            if(current.status!=Status.COMPLETED || !current.followupAvailable)
                throw new IllegalStateException(current.followupReason.isEmpty()?"电脑 Agent 尚未允许继续沟通":current.followupReason);
            if(current.remoteTaskId.isEmpty() || current.conversationId.isEmpty())
                throw new IllegalStateException("缺少已确认的电脑会话，不能继续沟通");
            byte[] inputBytes=value(input).getBytes(StandardCharsets.UTF_8);
            if(inputBytes.length==0||inputBytes.length>MAX_INPUT)throw new IllegalArgumentException("追问必须在 1–128 KiB 之间");
            String clientId="padnote-"+UUID.randomUUID().toString().replace("-","");
            JSONObject body=new JSONObject().put("client_task_id",clientId).put("title",current.title)
                    .put("input",input).put("source",new JSONObject().put("note_id",current.noteId)
                    .put("note_revision",current.noteRevision)).put("parent_task_id",current.remoteTaskId);
            String json=body.toString();long now=System.currentTimeMillis();
            Task child=new Task(clientId,current.connectionId,current.connectionRevision,current.origin,
                    current.kind,current.transport,current.credentialRef,current.bridgeId,current.instanceId,
                    current.title,current.connectionName,current.noteId,current.noteRevision,json,
                    sha256(json.getBytes(StandardCharsets.UTF_8)),"",Status.SUBMITTING,"","","","","",
                    Collections.emptyList(),now,now,current.conversationId,current.remoteTaskId,
                    current.clientTaskId,"",false,"等待电脑确认追问任务");
            write(child);
            write(withChild(current,clientId));
            return child;
        }
    }

    private static Task existingFollowup(Task child,String input) {
        String saved="";try{saved=new JSONObject(child.submissionJson).optString("input","");}
        catch(Exception ignored){}
        if(!saved.equals(value(input)))throw new IllegalStateException(
                "已有追问已保存，新文字未发送；请打开后续轮次");
        return child;
    }

    List<Task> children(String clientId) {
        List<Task> result=new ArrayList<>();for(Task task:list())if(clientId.equals(task.parentClientTaskId))result.add(task);
        result.sort(Comparator.comparingLong(t->t.createdAt));return result;
    }

    List<Task> list() {
        synchronized (LOCK) {
            File[] files = directory.listFiles((dir,name)->name.endsWith(".json"));
            List<Task> tasks = new ArrayList<>(); if(files==null) return tasks;
            int corrupt=0;
            for(File file:files) { try { tasks.add(readFile(file)); } catch(Exception ignored) { corrupt++; } }
            lastCorruptCount=corrupt;
            tasks.sort(Comparator.comparingLong((Task t)->t.updatedAt).reversed()); return tasks;
        }
    }

    int corruptCount() { synchronized (LOCK) { return lastCorruptCount; } }

    Task get(String id) {
        synchronized (LOCK) { try { File f=file(id); return f.isFile()?readFile(f):null; }
            catch(Exception error){ throw new IllegalStateException("Agent 任务数据损坏",error); } }
    }

    boolean applySubmission(String clientId, String connectionId, long revision,
                            String remoteId, Status status) throws Exception {
        return applySubmission(clientId,connectionId,revision,remoteId,status,"","");
    }
    boolean applySubmission(String clientId, String connectionId, long revision,
                            String remoteId, Status status,String conversationId,
                            String parentTaskId) throws Exception {
        synchronized (LOCK) { Task t=get(clientId);
            if(!submissionIdentity(t,connectionId,revision,remoteId) || t.status.terminal()) return false;
            if(t.transport==AgentConnectionStore.Transport.BRIDGE) {
                if(!t.parentTaskId.isEmpty() &&
                        (!parentTaskId.equals(t.parentTaskId)||!conversationId.equals(t.conversationId)))return false;
                if(!parentTaskId.isEmpty()&&!parentTaskId.equals(t.parentTaskId))return false;
                if(!conversationId.isEmpty() && !t.parentTaskId.isEmpty() && !conversationId.equals(t.conversationId))return false;
            }
            write(copy(t,remoteId,status,"","","","","",Collections.emptyList(),
                    conversationId.isEmpty()?t.conversationId:conversationId,
                    t.followupAvailable,t.followupReason)); return true; }
    }

    boolean applyStatus(String clientId, String connectionId, long revision, String remoteId,
                        AgentTaskClient.RemoteStatus status) throws Exception {
        synchronized (LOCK) { Task t=get(clientId);
            if(!statusIdentity(t,connectionId,revision,remoteId) ||
                    (t.status.terminal()&&status.status!=t.status) ||
                    (t.status == Status.STOPPING &&
                            (status.status == Status.RUNNING || status.status == Status.WAITING_FOR_APPROVAL))) return false;
            if(t.transport==AgentConnectionStore.Transport.BRIDGE && !status.parentTaskId.equals(t.parentTaskId))return false;
            String output=t.status.terminal()&&!t.output.isEmpty()?t.output:status.output;
            List<Artifact> artifacts=t.status.terminal()&&!t.artifacts.isEmpty()?t.artifacts:status.artifacts;
            String conversation=status.conversationId.isEmpty()?t.conversationId:status.conversationId;
            write(copy(t,remoteId,status.status,output,status.error,status.approvalId,
                    status.approvalTitle,status.approvalDescription,artifacts,
                    conversation,status.followupAvailable,status.followupReason)); return true; }
    }

    boolean markLocal(String clientId, Status status, String error) throws Exception {
        synchronized (LOCK) { Task t=get(clientId); if(t==null) return false;
            if (t.status.terminal() && status!=t.status) return false;
            write(copy(t,t.remoteTaskId,status,t.output,error,t.approvalId,t.approvalTitle,
                    t.approvalDescription,t.artifacts,t.conversationId,false,
                    error==null?"":error)); return true; }
    }

    boolean markError(String clientId, long expectedUpdatedAt, String error) throws Exception {
        synchronized (LOCK) { Task t=get(clientId);
            if(t==null || t.updatedAt!=expectedUpdatedAt || t.status.terminal()) return false;
            write(copy(t,t.remoteTaskId,t.status,t.output,error,t.approvalId,t.approvalTitle,
                    t.approvalDescription,t.artifacts,t.conversationId,t.followupAvailable,t.followupReason)); return true; }
    }

    boolean markFailed(String clientId, long expectedUpdatedAt, String error) throws Exception {
        synchronized (LOCK) { Task t=get(clientId);
            if(t==null || t.updatedAt!=expectedUpdatedAt || t.status.terminal()) return false;
            write(copy(t,t.remoteTaskId,Status.FAILED,t.output,error,t.approvalId,t.approvalTitle,
                    t.approvalDescription,t.artifacts,t.conversationId,t.followupAvailable,t.followupReason)); return true; }
    }

    private static boolean submissionIdentity(Task t,String connection,long revision,String remote) {
        return t!=null && t.connectionId.equals(connection) && t.connectionRevision==revision &&
                remote!=null && !remote.isEmpty() &&
                (t.remoteTaskId.isEmpty() || t.remoteTaskId.equals(remote));
    }
    private static boolean statusIdentity(Task t,String connection,long revision,String remote) {
        return t!=null && t.connectionId.equals(connection) && t.connectionRevision==revision &&
                remote!=null && !remote.isEmpty() && remote.equals(t.remoteTaskId);
    }
    private static Task copy(Task t,String remote,Status status,String output,String error,
                             String approvalId,String approvalTitle,String approvalDescription,
                             List<Artifact> artifacts) {
        return copy(t,remote,status,output,error,approvalId,approvalTitle,approvalDescription,
                artifacts,t.conversationId,t.followupAvailable,t.followupReason);
    }
    private static Task copy(Task t,String remote,Status status,String output,String error,
                             String approvalId,String approvalTitle,String approvalDescription,
                             List<Artifact> artifacts,String conversationId,boolean followupAvailable,
                             String followupReason) {
        return new Task(t.clientTaskId,t.connectionId,t.connectionRevision,t.origin,t.kind,t.transport,
                t.credentialRef,t.bridgeId,t.instanceId,t.title,t.connectionName,t.noteId,t.noteRevision,
                t.submissionJson,t.submissionSha256,remote,status,value(output),value(error),
                value(approvalId),value(approvalTitle),value(approvalDescription),artifacts,
                t.createdAt,Math.max(System.currentTimeMillis(),t.updatedAt+1L),conversationId,
                t.parentTaskId,t.parentClientTaskId,t.childClientTaskId,followupAvailable,followupReason);
    }

    private static Task withChild(Task t,String child) {
        return new Task(t.clientTaskId,t.connectionId,t.connectionRevision,t.origin,t.kind,t.transport,
                t.credentialRef,t.bridgeId,t.instanceId,t.title,t.connectionName,t.noteId,t.noteRevision,
                t.submissionJson,t.submissionSha256,t.remoteTaskId,t.status,t.output,t.error,t.approvalId,
                t.approvalTitle,t.approvalDescription,t.artifacts,t.createdAt,
                Math.max(System.currentTimeMillis(),t.updatedAt+1L),t.conversationId,t.parentTaskId,
                t.parentClientTaskId,child,t.followupAvailable,t.followupReason);
    }

    private void write(Task task) throws Exception {
        synchronized (LOCK) {
            byte[] bytes=task.toJson().toString().getBytes(StandardCharsets.UTF_8);
            AtomicFile atomic=new AtomicFile(file(task.clientTaskId));
            FileOutputStream out=null;
            try {
                out=atomic.startWrite();out.write(bytes);out.flush();out.getFD().sync();
                atomic.finishWrite(out);
            } catch (Exception error) {
                if(out!=null) atomic.failWrite(out);
                throw error;
            }
        }
    }
    private Task readFile(File file) throws Exception {
        if(file.length()>16L*1024*1024) throw new IllegalStateException("Agent 任务记录过大");
        byte[] data=new byte[(int)file.length()]; try(FileInputStream in=new FileInputStream(file)){
            int off=0,n; while(off<data.length&&(n=in.read(data,off,data.length-off))>0)off+=n;
            if(off!=data.length)throw new IllegalStateException("Agent 任务读取不完整"); }
        return Task.fromJson(new JSONObject(new String(data,StandardCharsets.UTF_8)));
    }
    private File file(String id) { if(id==null||!id.matches("padnote-[a-fA-F0-9]{32}")) throw new IllegalArgumentException("任务ID无效"); return new File(directory,id+".json"); }
    private static String required(JSONObject j,String k){String v=j.optString(k,"");if(v.isEmpty())throw new IllegalArgumentException("任务缺少 "+k);return v;}
    private static String value(String v){return v==null?"":v;}
    static String sha256(byte[] bytes)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format("%02x",b&255));return s.toString();}
}
