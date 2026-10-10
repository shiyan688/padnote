package padnote.material;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.*;

/** Offline adapter for the documented persisted Android/iOS metadata profiles. */
public final class StorageAdapter {
    private StorageAdapter() {}
    public record Association(String materialId, String ownerLineageId, String sourceState, String sourceLineageId) {
        public Association { Objects.requireNonNull(materialId); Objects.requireNonNull(sourceState); }
    }
    public record Projection(String kind, byte[] descriptor, String transportJsonSha256) {}

    /** The active note edge in the input adapter, if that persisted profile has one. */
    public static String activeNoteBindingId(String raw,String platform,String role) {
        require(raw!=null&&platform!=null&&role!=null,"association_input");
        if(role.equals("VIDEO")){
            Map<String,Object> j=object(new Parser(raw).parse());
            return str(j,platform.equals("android")?"noteId":"noteID");
        }
        if(!role.equals("VAULT"))throw new IllegalArgumentException("association_role");
        if(platform.equals("ios")){
            Map<String,Object> j=object(new Parser(raw).parse());
            return nullableString(j,"archiveLinkedNoteID");
        }
        if(!platform.equals("android"))throw new IllegalArgumentException("association_platform");
        byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);androidVaultMarkdown(bytes,new Association("00000000-0000-4000-8000-000000000001",null,"independent",null));
        String[] lines=raw.split("\\n",-1);String value=null;int found=0;
        for(String line:lines)if(line.startsWith("note-id: ")){value=line.substring("note-id: ".length());found++;}
        require(found==1,"vault_note_id_count");return value;
    }

    public static Projection videoJson(String raw, String platform, Association a, byte[] savedMp4) {
        if (savedMp4 == null) throw new IllegalArgumentException("saved_media_missing");
        return videoJson(raw, platform, a, savedMp4.length, hex(sha(savedMp4)));
    }

    /** Metadata-only projection after a trusted streaming pass computed the staged size and SHA. */
    public static Projection videoJson(String raw, String platform, Association a, long savedSize, String savedSha256) {
        Map<String,Object> j=object(new Parser(raw).parse());
        Set<String> android=Set.of("id","noteId","sourceRevision","sourceBundleSha256","taskPayloadSha256","connectionRevision","kind","transport","certSha256","taskId","remoteTaskId","connectionId","bridgeId","instanceId","artifactId","name","mediaType","sizeBytes","sha256","storedName","createdAt","originKind","sourceState","sourceNoteId","sourceRevisionPrecisionMs","digestKind","offlineState");
        Set<String> ios=Set.of("id","noteID","taskID","remoteTaskID","sourceRevision","sourceSnapshotSHA256","connectionID","connectionRevision","connectionKind","bridgeID","instanceID","certSHA256","artifactID","displayName","sizeBytes","sha256","createdAt");
        boolean isAndroid=platform.equals("android"); require(isAndroid||platform.equals("ios"),"platform"); keys(j,isAndroid?android:ios);uuid(str(j,"id"));
        String note=str(j,isAndroid?"noteId":"noteID"); String state=isAndroid?optionalDefault(j,"sourceState","linked_note"):a.sourceState;
        String srcNote=normalizeUUID(isAndroid?optString(j,"sourceNoteId",note):note);
        require(a.sourceState.equals(state),"association_state_mismatch");
        if("linked_note".equals(state)) require(Objects.equals(a.sourceLineageId,a.ownerLineageId),"association_source_mismatch");
        MaterialCodec.Video v=new MaterialCodec.Video();v.materialId=a.materialId;v.ownerLineageId=a.ownerLineageId;v.sourceState=state;v.sourceLineageId=a.sourceLineageId;v.sourceNoteId=srcNote;
        v.sourceRevisionRaw=integer(j,"sourceRevision");require(v.sourceRevisionRaw>0,"source_revision");
        if(isAndroid){long precision=j.containsKey("sourceRevisionPrecisionMs")?integer(j,"sourceRevisionPrecisionMs"):1;require(precision==1||precision==1000,"source_revision_precision");v.sourceRevisionPrecisionMs=(int)precision;v.sourceRevisionRepresentation="unix_ms_i64";}
        else {v.sourceRevisionPrecisionMs=1000;v.sourceRevisionRepresentation="unix_seconds_integer";}
        v.sourceBundleSha256=str(j,isAndroid?"sourceBundleSha256":"sourceSnapshotSHA256");v.taskPayloadSha256=isAndroid?nullableString(j,"taskPayloadSha256"):null;
        v.digestKind=isAndroid?optionalDefault(j,"digestKind","source_and_task_payload"):"source_snapshot_only";
        v.taskId=isAndroid?safeLegacyId(str(j,"taskId")):uuid(str(j,"taskID"));v.remoteTaskId=str(j,isAndroid?"remoteTaskId":"remoteTaskID");
        v.originKind=isAndroid?optionalDefault(j,"originKind","computer_task"):"computer_task";
        v.connectionId=isAndroid?safeLegacyId(str(j,"connectionId")):uuid(str(j,"connectionID"));v.connectionRevision=integer(j,"connectionRevision");require(v.originKind.equals("restored_archive")?v.connectionRevision>=0:v.connectionRevision>0,"connection_revision");
        v.connectionKind=connectionKind(str(j,isAndroid?"kind":"connectionKind"));v.transport=isAndroid?nullableString(j,"transport"):null;
        v.bridgeId=nullableString(j,isAndroid?"bridgeId":"bridgeID");v.instanceId=nullableString(j,isAndroid?"instanceId":"instanceID");
        v.certificateSha256=isAndroid?emptyOptionalHashAsNull(nullableString(j,"certSha256")):nullableString(j,"certSHA256");v.artifactId=str(j,isAndroid?"artifactId":"artifactID");
        v.displayName=str(j,isAndroid?"name":"displayName");v.mediaType=isAndroid?str(j,"mediaType"):"video/mp4";
        v.byteLength=integer(j,"sizeBytes");v.contentSha256=str(j,"sha256");v.offlineState=isAndroid?optionalDefault(j,"offlineState","verified_local_copy"):"verified_local_copy";
        if(isAndroid)require(("video-"+str(j,"id")+".mp4").equalsIgnoreCase(str(j,"storedName")),"stored_name");
        Object created=j.get("createdAt"); if(created instanceof JsonNumber n){ if(isAndroid){v.createdAt=MaterialCodec.Timestamp.integer("unix_ms_i64",parseInteger(n.lexeme));} else v.createdAt=MaterialCodec.Timestamp.floating("apple_reference_seconds_f64",Double.parseDouble(n.lexeme)); } else fail("created_at_type");
        require(savedSize==v.byteLength,"saved_media_length");require(savedSha256!=null&&savedSha256.matches("[0-9a-fA-F]{64}")&&savedSha256.equalsIgnoreCase(v.contentSha256),"saved_media_hash");
        byte[] desc=MaterialCodec.videoBytes(v);return new Projection("video",desc,hex(sha(raw.getBytes(StandardCharsets.UTF_8))));
    }

    /** iOS default-Codable VaultNote profile; Date is Apple-reference seconds and sourceUpdatedAt is milliseconds. */
    public static Projection iosVaultJson(String raw, Association a) {
        Map<String,Object> j=object(new Parser(raw).parse());
        Set<String> allowed=Set.of("id","title","markdown","sourceUpdatedAt","createdAt","archiveOrigin","archiveSourceNoteID","archiveLinkedNoteID","archiveSourceState","restoreTransactionID","restoreGroupID");keys(j,allowed);
        for(String k:List.of("archiveOrigin","archiveSourceNoteID","archiveLinkedNoteID","archiveSourceState","restoreTransactionID","restoreGroupID"))nullableString(j,k);
        String id=str(j,"id"),title=str(j,"title"),markdown=str(j,"markdown");require(!id.isEmpty()&&id.getBytes(StandardCharsets.UTF_8).length<=512,"vault_id");require(!title.isEmpty()&&title.getBytes(StandardCharsets.UTF_8).length<=512,"vault_title");
        String sourceState=optionalDefault(j,"archiveSourceState",a.sourceState);require(sourceState.equals(a.sourceState),"association_state_mismatch");
        if(j.containsKey("archiveSourceState")){String linked=nullableString(j,"archiveLinkedNoteID");if(sourceState.equals("linked_note"))require(linked!=null,"vault_linked_target_missing");else require(linked==null,"vault_detached_linked_target");}
        String sourceNote=nullableString(j,"archiveSourceNoteID");if(sourceNote==null)sourceNote=id;
        Object revision=j.get("sourceUpdatedAt"),created=j.get("createdAt");require(revision instanceof JsonNumber&&created instanceof JsonNumber,"vault_timestamp_type");
        MaterialCodec.Vault v=new MaterialCodec.Vault();v.materialId=a.materialId;v.ownerLineageId=a.ownerLineageId;v.sourceLineageId=a.sourceLineageId;v.sourceState=sourceState;v.sourceNoteId=sourceNote;v.title=title;
        v.sourceRevision=MaterialCodec.Timestamp.floating("source_updated_at_ms_f64",Double.parseDouble(((JsonNumber)revision).lexeme));v.createdAt=MaterialCodec.Timestamp.floating("apple_reference_seconds_f64",Double.parseDouble(((JsonNumber)created).lexeme));v.markdownUtf8=markdown.getBytes(StandardCharsets.UTF_8);
        byte[] desc=MaterialCodec.vaultBytes(v);return new Projection("vault",desc,hex(sha(raw.getBytes(StandardCharsets.UTF_8))));
    }

    /** Android VaultStore UTF-8 Markdown profile; descriptor body/hash cover the exact saved file bytes. */
    public static Projection androidVaultMarkdown(byte[] raw, Association a) {
        final String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}catch(Exception e){throw new IllegalArgumentException("vault_utf8");}
        String[] lines=text.split("\\n",-1);require(lines.length>=8&&lines[0].equals("---"),"vault_frontmatter");int end=-1;for(int i=1;i<Math.min(lines.length,20);i++)if(lines[i].equals("---")){end=i;break;}require(end>1,"vault_frontmatter_end");
        Map<String,String> h=new LinkedHashMap<>();Set<String> required=Set.of("title","note-id","pages","digitized","digitized-epoch","source-modified");
        Set<String> allowed=Set.of("title","note-id","pages","digitized","digitized-epoch","source-modified","digitization-operation-id");
        for(int i=1;i<end;i++){int colon=lines[i].indexOf(": ");require(colon>0,"vault_header_line");String k=lines[i].substring(0,colon);require(allowed.contains(k)&&!h.containsKey(k),"vault_header_key");h.put(k,lines[i].substring(colon+2));}
        require(h.keySet().containsAll(required),"vault_header_fields");
        if(h.containsKey("digitization-operation-id"))require(h.get("digitization-operation-id").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),"vault_operation_id");
        long pages=parseNonnegative(h.get("pages")),created=parseNonnegative(h.get("digitized-epoch")),source=parseNonnegative(h.get("source-modified"));
        MaterialCodec.Vault v=new MaterialCodec.Vault();v.materialId=a.materialId;v.ownerLineageId=a.ownerLineageId;v.sourceLineageId=a.sourceLineageId;v.sourceState=a.sourceState;v.sourceNoteId=h.get("note-id");v.title=h.get("title");v.pageCount=Math.toIntExact(pages);v.sourceRevision=MaterialCodec.Timestamp.integer("unix_ms_i64",source);v.createdAt=MaterialCodec.Timestamp.integer("unix_ms_i64",created);v.markdownUtf8=raw.clone();
        return new Projection("vault",MaterialCodec.vaultBytes(v),hex(sha(raw)));
    }
    /** Stable canonical descriptor identity for an Android VaultStore filename (which is a storage key). */
    public static String androidVaultMaterialId(String sourceFileName) {
        require(sourceFileName!=null&&sourceFileName.matches("note-[0-9a-f]{64}\\.md"),"vault_source_filename");
        return UUID.nameUUIDFromBytes(("PadNote/AndroidVaultMaterial/v1\0"+sourceFileName).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static long parseNonnegative(String s){try{long n=Long.parseLong(s);require(n>=0,"vault_header_number");return n;}catch(Exception e){throw new IllegalArgumentException("vault_header_number");}}

    /** Strict JSON reader: rejects duplicate object keys, invalid escapes, trailing bytes and non-JSON numbers. */
    private record JsonNumber(String lexeme){}
    private static final class Parser {
        final String s; int p; Parser(String s){this.s=Objects.requireNonNull(s);} Object parse(){Object x=value();ws();require(p==s.length(),"json_trailing");return x;}
        Object value(){ws();require(p<s.length(),"json_eof");char c=s.charAt(p);if(c=='{')return obj();if(c=='[')return arr();if(c=='"')return string();if(c=='t'){lit("true");return true;}if(c=='f'){lit("false");return false;}if(c=='n'){lit("null");return null;}return number();}
        Map<String,Object> obj(){p++;ws();Map<String,Object> m=new LinkedHashMap<>();if(take('}'))return m;while(true){ws();require(peek()=='"',"json_key");String k=string();require(!m.containsKey(k),"json_duplicate_key");ws();need(':');m.put(k,value());ws();if(take('}'))return m;need(',');}}
        List<Object> arr(){p++;ws();List<Object> a=new ArrayList<>();if(take(']'))return a;while(true){a.add(value());ws();if(take(']'))return a;need(',');}}
        String string(){need('"');StringBuilder b=new StringBuilder();while(p<s.length()){char c=s.charAt(p++);if(c=='"'){for(int i=0;i<b.length();i++){char q=b.charAt(i);if(Character.isHighSurrogate(q))require(i+1<b.length()&&Character.isLowSurrogate(b.charAt(++i)),"json_surrogate");else require(!Character.isLowSurrogate(q),"json_surrogate");}return b.toString();}if(c<32)fail("json_control");if(c!='\\'){b.append(c);continue;}require(p<s.length(),"json_escape");char e=s.charAt(p++);switch(e){case '"','\\','/'->b.append(e);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{require(p+4<=s.length(),"json_unicode");int cp=Integer.parseInt(s.substring(p,p+4),16);p+=4;b.append((char)cp);}default->fail("json_escape");}}fail("json_string_eof");return "";}
        JsonNumber number(){int start=p;if(take('-')){}if(take('0')){}else{digits();}if(take('.'))digits();if(take('e')||take('E')){if(take('+')||take('-')){}digits();}return new JsonNumber(s.substring(start,p));}
        void digits(){int q=p;while(p<s.length()&&Character.isDigit(s.charAt(p)))p++;require(q<p,"json_digits");}void lit(String x){require(s.startsWith(x,p),"json_literal");p+=x.length();}void ws(){while(p<s.length()&&" \n\r\t".indexOf(s.charAt(p))>=0)p++;}char peek(){require(p<s.length(),"json_eof");return s.charAt(p);}void need(char c){require(take(c),"json_token");}boolean take(char c){if(p<s.length()&&s.charAt(p)==c){p++;return true;}return false;}
    }
    private static Map<String,Object> object(Object x){require(x instanceof Map,"json_object");@SuppressWarnings("unchecked") Map<String,Object> m=(Map<String,Object>)x;return m;}
    private static void keys(Map<String,Object> m,Set<String> allowed){for(String k:m.keySet())require(allowed.contains(k),"unknown_field");}
    private static String str(Map<String,Object> m,String k){Object x=m.get(k);require(x instanceof String,"field_type");return (String)x;}
    private static String nullableString(Map<String,Object> m,String k){Object x=m.get(k);require(x==null||x instanceof String,"field_type");return (String)x;}
    private static String optString(Map<String,Object> m,String k,String d){return m.containsKey(k)?str(m,k):d;}
    private static String optionalDefault(Map<String,Object> m,String k,String d){return m.containsKey(k)?str(m,k):d;}
    private static String uuid(String s){require(s.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),"uuid_field");return UUID.fromString(s).toString().toLowerCase(Locale.ROOT);}
    private static String safeLegacyId(String s){require(s.matches("[A-Za-z0-9_-]{1,160}"),"legacy_id_format");return s;}
    private static String emptyOptionalHashAsNull(String s){return s!=null&&s.isEmpty()?null:s;}
    private static String normalizeUUID(String s){return s.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")?UUID.fromString(s).toString().toLowerCase(Locale.ROOT):s;}
    private static String connectionKind(String s){return switch(s){case "HERMES","hermes"->"HERMES";case "OPENCLAW","openClaw"->"OPENCLAW";case "BUILTIN_VIDEO","builtin_video"->"BUILTIN_VIDEO";default->throw new IllegalArgumentException("connection_kind");};}
    private static long integer(Map<String,Object> m,String k){Object x=m.get(k);require(x instanceof JsonNumber,"integer_type");return parseInteger(((JsonNumber)x).lexeme);}
    private static long parseInteger(String raw){require(raw.matches("-?(0|[1-9][0-9]*)"),"integer_type");try{return Long.parseLong(raw);}catch(Exception e){throw new IllegalArgumentException("integer_range");}}
    private static byte[] sha(byte[] b){try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(Exception e){throw new AssertionError(e);}}private static String hex(byte[] b){return MaterialCodec.hex(b);}
    private static void require(boolean x,String c){if(!x)throw new IllegalArgumentException(c);}private static void fail(String c){throw new IllegalArgumentException(c);}
}
