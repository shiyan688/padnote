package com.padnote.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Typed source identity and timestamp sidecar for one complete exported note group. */
public final class ArchiveUpdateProfile {
    public static final int SCHEMA_VERSION = 2;
    static final String VAULT_ID_PROJECTION_RULE="archive-v2-android-vault-material-uuid/v1";
    public final String noteItemId, sourceNoteId, sourceLineageId, sourceRevisionId;
    public final String groupSha256, bodySha256;
    public final List<Timestamp> timestamps;
    public final int schemaVersion;

    public static final class Timestamp {
        public final String pointer, kind;
        /** I64 value or raw IEEE-754 bits when kind is f64. */
        public final long valueBits;
        public Timestamp(String pointer, String kind, long valueBits) {
            this.pointer=pointer; this.kind=kind; this.valueBits=valueBits;
        }
    }

    ArchiveUpdateProfile(String noteItemId, String sourceNoteId, String sourceLineageId,
                         String sourceRevisionId, String groupSha256, String bodySha256,
                         List<Timestamp> timestamps) {
        this(SCHEMA_VERSION,noteItemId,sourceNoteId,sourceLineageId,sourceRevisionId,groupSha256,bodySha256,timestamps);
    }
    ArchiveUpdateProfile(int schemaVersion,String noteItemId,String sourceNoteId,String sourceLineageId,
                         String sourceRevisionId,String groupSha256,String bodySha256,List<Timestamp> timestamps) {
        if(schemaVersion!=1&&schemaVersion!=SCHEMA_VERSION)throw new IllegalArgumentException("UPDATE_PROFILE_VERSION");
        this.schemaVersion=schemaVersion;
        this.noteItemId=noteItemId; this.sourceNoteId=sourceNoteId;
        this.sourceLineageId=sourceLineageId; this.sourceRevisionId=sourceRevisionId;
        this.groupSha256=groupSha256; this.bodySha256=bodySha256;
        this.timestamps=Collections.unmodifiableList(new ArrayList<>(timestamps));
    }

    static ArchiveUpdateProfile create(LibraryBackupManifest.Note note, String platform,
            List<LibraryBackupManifest.Note> notes,
            List<LibraryBackupManifest.VaultEntry> vaults,
            List<LibraryBackupManifest.VideoAttachment> videos,
            List<LibraryBackupManifest.Resource> resources, Map<String,File> stagedFiles) throws Exception {
        Map<String,LibraryBackupManifest.Resource> byResource=new HashMap<>();
        for(LibraryBackupManifest.Resource resource:resources)byResource.put(resource.resourceId,resource);
        LibraryBackupManifest.Resource body=byResource.get(note.noteResourceId);
        if(body==null||!"note_document".equals(body.role))throw new IOException("UPDATE_PROFILE_BODY_RESOURCE");
        File bodyFile=stagedFiles.get(note.noteResourceId);
        byte[] raw=readExact(bodyFile,body.byteLength,LibraryBackupManifest.MAX_NOTE_BYTES);
        String bodySha=sha(raw);
        if(!bodySha.equals(body.sha256))throw new IOException("UPDATE_PROFILE_BODY_HASH");
        List<Timestamp> times=captureTimes(new String(raw,StandardCharsets.UTF_8),note.sourceNoteId);
        String groupSha=groupDigest(note,vaults,videos,byResource,SCHEMA_VERSION);
        String lineage=stableUuid("PadNote/source-lineage/v2\0"+platform+"\0"+note.sourceNoteId);
        String revision=stableUuid("PadNote/source-revision/v2\0"+lineage+"\0"+groupSha);
        return new ArchiveUpdateProfile(SCHEMA_VERSION,note.itemId,note.sourceNoteId,lineage,revision,groupSha,bodySha,times);
    }

    static String groupDigest(LibraryBackupManifest.Note note,
            List<LibraryBackupManifest.VaultEntry> vaults,
            List<LibraryBackupManifest.VideoAttachment> videos,
            Map<String,LibraryBackupManifest.Resource> resources) throws IOException {
        return groupDigest(note,vaults,videos,resources,1);
    }
    static String groupDigest(LibraryBackupManifest.Note note,
            List<LibraryBackupManifest.VaultEntry> vaults,
            List<LibraryBackupManifest.VideoAttachment> videos,
            Map<String,LibraryBackupManifest.Resource> resources,int profileVersion) throws IOException {
        StringBuilder value=new StringBuilder("PadNote/ArchiveNoteGroup/v2\n");
        append(value,"note",note.itemId,note.sourceNoteId,Integer.toString(note.noteSchemaVersion));
        appendResource(value,"body",note.noteResourceId,resources);
        if(note.pdfResourceId==null)value.append("pdf\0absent\n");else appendResource(value,"pdf",note.pdfResourceId,resources);
        if(note.coverResourceId==null)value.append("cover\0absent\n");else appendResource(value,"cover",note.coverResourceId,resources);
        List<String> linked=new ArrayList<>();
        for(LibraryBackupManifest.VaultEntry row:vaults)if(note.itemId.equals(row.noteItemId)&&"linked_note".equals(row.sourceState)) {
            linked.add(materialLine("vault",row.itemId,row.sourceNoteId,row.sourceRevisionMs,row.resourceId,resources));
            if(row.sourceStorageResourceId!=null){
                linked.add(materialLine("vault_storage",row.itemId,row.sourceNoteId,row.sourceRevisionMs,row.sourceStorageResourceId,resources));
                LibraryBackupManifest.Resource storage=resources.get(row.sourceStorageResourceId);
                if(storage==null)throw new IOException("UPDATE_PROFILE_VAULT_STORAGE_RESOURCE");
                if(profileVersion>=SCHEMA_VERSION)appendProjectionRule(linked,row,storage);
            }
        }
        for(LibraryBackupManifest.VideoAttachment row:videos)if(note.itemId.equals(row.noteItemId)&&"linked_note".equals(row.sourceState))
            linked.add(materialLine("video",row.itemId,row.sourceNoteId,row.sourceRevisionMs,row.resourceId,resources));
        Collections.sort(linked);for(String line:linked)value.append(line);
        return sha(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String vaultSourceMaterialId(ArchiveUpdateProfile profile,LibraryBackupManifest.VaultEntry row,
                                        LibraryBackupManifest.Resource storage)throws IOException {
        if(profile==null||profile.schemaVersion<SCHEMA_VERSION)throw new IOException("UPDATE_VAULT_STORAGE_PROFILE_REQUIRED_COPY_ONLY");
        if(row==null||storage==null||row.sourceStorageResourceId==null
                ||!row.sourceStorageResourceId.equals(storage.resourceId)
                ||!"vault_storage_markdown".equals(storage.role)
                ||!"text/markdown".equals(storage.mediaType)
                ||storage.sha256==null||!storage.sha256.matches("[0-9a-f]{64}"))throw new IOException("VAULT_STORAGE_IDENTITY_INPUT_INVALID");
        if(!profile.noteItemId.equals(row.noteItemId)||!profile.sourceNoteId.equals(row.sourceNoteId)
                ||!row.itemId.matches("v-[0-9a-f]{32}"))throw new IOException("VAULT_STORAGE_PROFILE_BINDING_MISMATCH");
        String input=VAULT_ID_PROJECTION_RULE+"\0"+profile.sourceLineageId+"\0"+profile.sourceRevisionId+"\0"
                +row.itemId+"\0"+row.sourceNoteId+"\0"+storage.resourceId+"\0"+storage.sha256;
        return stableUuid(input);
    }

    static String vaultCopyValidationMaterialId(LibraryBackupManifest.VaultEntry row,
                                                LibraryBackupManifest.Resource storage)throws IOException {
        if(row==null||storage==null||row.sourceStorageResourceId==null
                ||!row.sourceStorageResourceId.equals(storage.resourceId)
                ||!"vault_storage_markdown".equals(storage.role)||!"text/markdown".equals(storage.mediaType)
                ||storage.sha256==null||!storage.sha256.matches("[0-9a-f]{64}"))
            throw new IOException("VAULT_STORAGE_IDENTITY_INPUT_INVALID");
        return stableUuid("PadNote/archive-vault-copy-validation/v1\0"+row.itemId+"\0"+row.sourceNoteId+"\0"+storage.sha256);
    }

    private static void appendProjectionRule(List<String> linked,LibraryBackupManifest.VaultEntry row,
                                             LibraryBackupManifest.Resource storage){
        linked.add(VAULT_ID_PROJECTION_RULE+"\0"+row.itemId+"\0"+row.sourceNoteId+"\0"
                +storage.resourceId+"\0"+storage.sha256+"\n");
    }

    JSONObject toJson() throws Exception {
        JSONObject row=new JSONObject().put("schema_version",schemaVersion).put("note_item_id",noteItemId)
                .put("source_note_id",sourceNoteId).put("source_lineage_id",sourceLineageId)
                .put("source_revision_id",sourceRevisionId).put("group_sha256",groupSha256)
                .put("body_sha256",bodySha256);
        JSONArray values=new JSONArray();for(Timestamp t:timestamps)values.put(new JSONObject()
                .put("pointer",t.pointer).put("kind",t.kind).put("value_bits",Long.toUnsignedString(t.valueBits)));
        row.put("timestamps",values);return row;
    }

    static ArchiveUpdateProfile parse(Object value) throws IOException {
        Map<String,Object> row=LibraryBackupJson.object(value,"UPDATE_PROFILE_FIELDS");
        LibraryBackupJson.exactKeys(row,"UPDATE_PROFILE_FIELDS","schema_version","note_item_id","source_note_id",
                "source_lineage_id","source_revision_id","group_sha256","body_sha256","timestamps");
        int version=(int)LibraryBackupJson.integer(row.get("schema_version"),1,SCHEMA_VERSION,"UPDATE_PROFILE_VERSION");
        if(version!=1&&version!=SCHEMA_VERSION)
            throw new IOException("UPDATE_PROFILE_VERSION");
        String note=LibraryBackupJson.string(row.get("note_item_id"),"UPDATE_PROFILE_NOTE");
        String source=LibraryBackupJson.string(row.get("source_note_id"),"UPDATE_PROFILE_SOURCE_NOTE");
        String lineage=LibraryBackupJson.string(row.get("source_lineage_id"),"UPDATE_PROFILE_LINEAGE");
        String revision=LibraryBackupJson.string(row.get("source_revision_id"),"UPDATE_PROFILE_REVISION");
        String group=LibraryBackupJson.string(row.get("group_sha256"),"UPDATE_PROFILE_GROUP_SHA");
        String body=LibraryBackupJson.string(row.get("body_sha256"),"UPDATE_PROFILE_BODY_SHA");
        List<Timestamp> times=new ArrayList<>();
        for(Object item:LibraryBackupJson.array(row.get("timestamps"),"UPDATE_PROFILE_TIMESTAMPS")){
            Map<String,Object> t=LibraryBackupJson.object(item,"UPDATE_PROFILE_TIMESTAMP_FIELDS");
            LibraryBackupJson.exactKeys(t,"UPDATE_PROFILE_TIMESTAMP_FIELDS","pointer","kind","value_bits");
            String pointer=LibraryBackupJson.string(t.get("pointer"),"UPDATE_PROFILE_POINTER");
            String kind=LibraryBackupJson.string(t.get("kind"),"UPDATE_PROFILE_TIME_KIND");
            String bits=LibraryBackupJson.string(t.get("value_bits"),"UPDATE_PROFILE_TIME_BITS");
            if(!"i64".equals(kind)&&!"f64".equals(kind))throw new IOException("UPDATE_PROFILE_TIME_KIND");
            if(!bits.matches("[0-9]{1,20}"))throw new IOException("UPDATE_PROFILE_TIME_BITS");
            long valueBits;try{valueBits=Long.parseUnsignedLong(bits);}catch(NumberFormatException e){throw new IOException("UPDATE_PROFILE_TIME_BITS",e);}
            times.add(new Timestamp(pointer,kind,valueBits));
            if(times.size()>20_000)throw new IOException("UPDATE_PROFILE_TIME_LIMIT");
        }
        if(!note.matches("[ivac]-[0-9a-f]{32}")||source.isEmpty()||source.length()>4096
                ||!isUuid(lineage)||!isUuid(revision)||!group.matches("[0-9a-f]{64}")||!body.matches("[0-9a-f]{64}"))
            throw new IOException("UPDATE_PROFILE_INVALID");
        return new ArchiveUpdateProfile(version,note,source,lineage,revision,group,body,times);
    }

    TypedNoteTimeProjection.Sidecar timeSidecar() {
        Map<String,TypedNoteTimeProjection.ProvenanceValue> map=new HashMap<>();
        for(Timestamp t:timestamps)map.put(t.pointer,new TypedNoteTimeProjection.ProvenanceValue(
                "i64".equals(t.kind)?TypedNoteTimeProjection.Kind.I64:TypedNoteTimeProjection.Kind.F64,
                "i64".equals(t.kind)?t.valueBits:0,"f64".equals(t.kind)?t.valueBits:0));
        return new TypedNoteTimeProjection.Sidecar("transport",bodySha256,sourceNoteId,map);
    }

    private static List<Timestamp> captureTimes(String raw,String expectedNoteId)throws Exception {
        JSONObject doc=NotePrecisionJsonParser.parseObject(raw);
        if(!expectedNoteId.equals(doc.getString("id")))throw new IOException("UPDATE_PROFILE_NOTE_ID_MISMATCH");
        List<Timestamp> out=new ArrayList<>();capture(doc,"updatedAt","/updatedAt",out);
        JSONArray strokes=doc.getJSONArray("strokes");
        for(int i=0;i<strokes.length();i++){JSONObject stroke=strokes.getJSONObject(i);capture(stroke,"createdAt","/strokes/"+i+"/createdAt",out);
            JSONArray points=stroke.getJSONArray("points");for(int j=0;j<points.length();j++)capture(points.getJSONObject(j),"timestamp","/strokes/"+i+"/points/"+j+"/timestamp",out);}
        return out;
    }
    static void requireExactTimes(String raw,String expectedNoteId,List<Timestamp> profileTimes)throws Exception {
        List<Timestamp> actual=captureTimes(raw,expectedNoteId);
        if(actual.size()!=profileTimes.size())throw new IOException("UPDATE_PROFILE_TIME_SET_MISMATCH");
        Map<String,Timestamp> supplied=new HashMap<>();
        for(Timestamp t:profileTimes)if(supplied.put(t.pointer,t)!=null)throw new IOException("UPDATE_PROFILE_TIME_DUPLICATE");
        for(Timestamp t:actual){Timestamp got=supplied.get(t.pointer);
            if(got==null||!got.kind.equals(t.kind)||got.valueBits!=t.valueBits)throw new IOException("UPDATE_PROFILE_TIME_VALUE_MISMATCH");}
    }
    private static void capture(JSONObject obj,String key,String pointer,List<Timestamp> out)throws Exception {
        Object value=obj.get(key);if(!(value instanceof Number))throw new IOException("UPDATE_PROFILE_TIME_MISSING");
        Number number=(Number)value;
        if(number instanceof Double||number instanceof Float){double d=number.doubleValue();if(!Double.isFinite(d))throw new IOException("UPDATE_PROFILE_TIME_INVALID");out.add(new Timestamp(pointer,"f64",Double.doubleToRawLongBits(d)));}
        else {long n;try{n=number instanceof BigInteger?bigIntegerLongValueExact((BigInteger)number):number.longValue();}catch(ArithmeticException e){throw new IOException("UPDATE_PROFILE_TIME_INVALID",e);}out.add(new Timestamp(pointer,"i64",n));}
    }
    private static long bigIntegerLongValueExact(BigInteger value){
        if(value.compareTo(BigInteger.valueOf(Long.MIN_VALUE))<0||value.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0)
            throw new ArithmeticException("time_overflow");
        return value.longValue();
    }
    private static String materialLine(String kind,String item,String source,long revision,String resource,
                                      Map<String,LibraryBackupManifest.Resource> resources)throws IOException {
        LibraryBackupManifest.Resource r=resources.get(resource);if(r==null)throw new IOException("UPDATE_PROFILE_RESOURCE_MISSING");
        return kind+"\0"+item+"\0"+source+"\0"+revision+"\0"+r.byteLength+"\0"+r.sha256+"\n";
    }
    private static void appendResource(StringBuilder out,String role,String id,
                                       Map<String,LibraryBackupManifest.Resource> resources)throws IOException {
        LibraryBackupManifest.Resource r=resources.get(id);if(r==null)throw new IOException("UPDATE_PROFILE_RESOURCE_MISSING");
        append(out,role,id,Long.toString(r.byteLength),r.sha256);
    }
    private static void append(StringBuilder out,String... fields){for(String field:fields)out.append(field).append('\0');out.append('\n');}
    private static byte[] readExact(File file,long expected,long maximum)throws IOException {
        if(file==null||expected<0||expected>maximum||expected>Integer.MAX_VALUE||!file.isFile()||file.length()!=expected)
            throw new IOException("UPDATE_PROFILE_BODY_RESOURCE");
        byte[] bytes=new byte[(int)expected];try(FileInputStream in=new FileInputStream(file)){int at=0;while(at<bytes.length){int n=in.read(bytes,at,bytes.length-at);if(n<0)throw new IOException("UPDATE_PROFILE_BODY_TRUNCATED");at+=n;}if(in.read()!=-1)throw new IOException("UPDATE_PROFILE_BODY_GREW");}return bytes;
    }
    private static String stableUuid(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();}
    private static boolean isUuid(String value){try{return value!=null&&UUID.fromString(value).toString().equalsIgnoreCase(value);}catch(RuntimeException e){return false;}}
    private static String sha(byte[] bytes)throws IOException {try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new IOException("UPDATE_SHA256_UNAVAILABLE",impossible);}}
}
