package com.padnote.android;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/** Strict, portable provenance for one image placed from a completed Bridge task. */
final class AgentImageReceipt {
    static final String[] KEYS = {"schemaVersion", "applicationId", "taskId", "connectionId",
            "instanceId", "agentKind", "transport", "sourceNoteId", "sourceNoteRevision",
            "artifactId", "artifactName", "mediaType", "sizeBytes", "artifactSha256",
            "pngSha256", "addedAt"};

    final String applicationId, taskId, connectionId, instanceId, agentKind, transport;
    final String sourceNoteId, artifactId, artifactName, mediaType, artifactSha256, pngSha256;
    final long sourceNoteRevision, sizeBytes, addedAt;

    AgentImageReceipt(String taskId, String connectionId, String instanceId, String agentKind,
                      String transport, String sourceNoteId, long sourceNoteRevision,
                      String artifactId, String artifactName, String mediaType, long sizeBytes,
                      String artifactSha256, String pngSha256, long addedAt) {
        this(calculateApplicationId(taskId, connectionId, instanceId, agentKind, transport,
                        artifactId, artifactName, mediaType, sizeBytes, artifactSha256), taskId,
                connectionId, instanceId, agentKind, transport, sourceNoteId, sourceNoteRevision,
                artifactId, artifactName, mediaType, sizeBytes, artifactSha256, pngSha256, addedAt);
    }

    private AgentImageReceipt(String applicationId, String taskId, String connectionId,
                              String instanceId, String agentKind, String transport,
                              String sourceNoteId, long sourceNoteRevision, String artifactId,
                              String artifactName, String mediaType, long sizeBytes,
                              String artifactSha256, String pngSha256, long addedAt) {
        this.applicationId=applicationId; this.taskId=taskId; this.connectionId=connectionId;
        this.instanceId=instanceId; this.agentKind=agentKind; this.transport=transport;
        this.sourceNoteId=sourceNoteId; this.sourceNoteRevision=sourceNoteRevision;
        this.artifactId=artifactId; this.artifactName=artifactName; this.mediaType=mediaType;
        this.sizeBytes=sizeBytes; this.artifactSha256=artifactSha256; this.pngSha256=pngSha256;
        this.addedAt=addedAt;
        validate();
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("schemaVersion",1).put("applicationId",applicationId)
                .put("taskId",taskId).put("connectionId",connectionId)
                .put("instanceId",instanceId==null?JSONObject.NULL:instanceId)
                .put("agentKind",agentKind).put("transport",transport)
                .put("sourceNoteId",sourceNoteId==null?JSONObject.NULL:sourceNoteId)
                .put("sourceNoteRevision",sourceNoteRevision).put("artifactId",artifactId)
                .put("artifactName",artifactName).put("mediaType",mediaType)
                .put("sizeBytes",sizeBytes).put("artifactSha256",artifactSha256)
                .put("pngSha256",pngSha256).put("addedAt",addedAt);
    }

    static AgentImageReceipt fromJson(JSONObject json) throws JSONException {
        if(json==null || json.length()!=KEYS.length) throw new JSONException("agent_image_receipt_keys_invalid");
        for(String key:KEYS) if(!json.has(key)) throw new JSONException("agent_image_receipt_keys_invalid");
        if(strictLong(json,"schemaVersion")!=1) throw new JSONException("agent_image_receipt_schema_invalid");
        for(String key:new String[]{"applicationId","taskId","connectionId","agentKind","transport",
                "artifactId","artifactName","mediaType","artifactSha256","pngSha256"})
            if(!(json.get(key) instanceof String))throw new JSONException("agent_image_receipt_string_invalid:"+key);
        Object instance=json.get("instanceId"), source=json.get("sourceNoteId");
        if(!(instance instanceof String || instance==JSONObject.NULL)
                || !(source instanceof String || source==JSONObject.NULL))
            throw new JSONException("agent_image_receipt_nullable_field_invalid");
        try {
            return new AgentImageReceipt(json.getString("applicationId"),json.getString("taskId"),
                    json.getString("connectionId"),instance==JSONObject.NULL?null:(String)instance,
                    json.getString("agentKind"),json.getString("transport"),
                    source==JSONObject.NULL?null:(String)source,strictLong(json,"sourceNoteRevision"),
                    json.getString("artifactId"),json.getString("artifactName"),json.getString("mediaType"),
                    strictLong(json,"sizeBytes"),json.getString("artifactSha256"),json.getString("pngSha256"),
                    strictLong(json,"addedAt"));
        } catch(IllegalArgumentException invalid) { throw new JSONException("agent_image_receipt_invalid:"+invalid.getMessage()); }
    }

    void validatePng(byte[] canonicalPng) throws JSONException {
        if(canonicalPng==null || !pngSha256.equals(sha256(canonicalPng)))
            throw new JSONException("agent_image_png_digest_mismatch");
    }

    byte[] canonicalBytes() {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
            out.write("PadNote/AgentImage/receipt-v1\0".getBytes(StandardCharsets.UTF_8));
            out.writeLong(1); lp(out,applicationId); lp(out,taskId); lp(out,connectionId);
            nullable(out,instanceId); lp(out,agentKind); lp(out,transport); nullable(out,sourceNoteId);
            out.writeLong(sourceNoteRevision); lp(out,artifactId); lp(out,artifactName); lp(out,mediaType);
            out.writeLong(sizeBytes); lp(out,artifactSha256); lp(out,pngSha256); out.writeLong(addedAt);
            out.flush(); return bytes.toByteArray();
        } catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }

    static String calculateApplicationId(String taskId,String connectionId,String instanceId,
                                         String agentKind,String transport,String artifactId,
                                         String artifactName,String mediaType,long sizeBytes,
                                         String artifactSha256) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            bytes.write("padnote.agent-image.v1\n".getBytes(StandardCharsets.UTF_8));
            DataOutputStream out=new DataOutputStream(bytes);
            for(String value:new String[]{taskId,connectionId,instanceId==null?"":instanceId,
                    agentKind,transport,artifactId,artifactName,mediaType,Long.toString(sizeBytes),artifactSha256}) lp(out,value);
            out.flush(); return sha256(bytes.toByteArray());
        } catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private void validate() {
        identifier(taskId,false,256); if(!canonicalTaskId(taskId)) fail("task_id");
        identifier(connectionId,false,256); identifier(instanceId,true,256); identifier(sourceNoteId,true,256);
        token(agentKind); token(transport); identifier(artifactId,false,256);
        identifier(artifactName,false,256);
        if(artifactName.indexOf('/')>=0 || artifactName.indexOf('\\')>=0) fail("artifact_name_path");
        if(!"image/png".equals(mediaType) && !"image/jpeg".equals(mediaType)) fail("media_type");
        if(sizeBytes<1 || sizeBytes>20L*1024*1024 || sourceNoteRevision<0 || addedAt<0) fail("integer_range");
        if(!hex64(applicationId)||!hex64(artifactSha256)||!hex64(pngSha256)) fail("digest");
        String expected=calculateApplicationId(taskId,connectionId,instanceId,agentKind,transport,
                artifactId,artifactName,mediaType,sizeBytes,artifactSha256);
        if(!applicationId.equals(expected)) fail("application_id");
    }

    private static void identifier(String value,boolean nullable,int maxBytes) {
        if(value==null) { if(!nullable) fail("identifier_required"); return; }
        if(!AgentResultProvenance.wellFormedUtf16(value)) fail("identifier_unicode");
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length==0||bytes.length>maxBytes) fail("identifier_size");
        for(int i=0;i<value.length();) { int cp=value.codePointAt(i); i+=Character.charCount(cp);
            if(cp<=0x1f||(cp>=0x7f&&cp<=0x9f)) fail("identifier_control"); }
    }
    private static void token(String value) {
        identifier(value,false,64);
        if(!value.matches("[a-z0-9._-]{1,64}")) fail("token");
    }
    private static boolean canonicalTaskId(String value) {
        if(value!=null && value.matches("padnote-[0-9a-f]{32}")) return true;
        if(value==null||value.length()!=36||!value.equals(value.toLowerCase(Locale.ROOT))) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch(IllegalArgumentException invalid) { return false; }
    }
    private static boolean hex64(String value) { return value!=null&&value.matches("[0-9a-f]{64}"); }
    private static void lp(DataOutputStream out,String value)throws Exception { byte[] b=value.getBytes(StandardCharsets.UTF_8); out.writeInt(b.length); out.write(b); }
    private static void nullable(DataOutputStream out,String value)throws Exception { if(value==null) out.writeByte(0); else {out.writeByte(1);lp(out,value);} }
    private static void fail(String reason) { throw new IllegalArgumentException("AGENT_IMAGE_RECEIPT_"+reason.toUpperCase(Locale.ROOT)); }
    private static long strictLong(JSONObject json,String key)throws JSONException {
        Object raw=json.get(key); if(!(raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long))
            throw new JSONException("agent_image_receipt_integer_invalid:"+key);
        return ((Number)raw).longValue();
    }
    static String sha256(byte[] value) {
        try { byte[] d=MessageDigest.getInstance("SHA-256").digest(value); StringBuilder out=new StringBuilder(64);
            for(byte b:d) out.append(String.format(Locale.ROOT,"%02x",b&255)); return out.toString(); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
