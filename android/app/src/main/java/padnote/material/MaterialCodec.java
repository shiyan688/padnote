package padnote.material;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.*;

/** Typed metadata and portable identity sidecar oracle; not a product store/importer. */
public final class MaterialCodec {
    private MaterialCodec() {}
    private static final byte[] MAGIC={'P','N','M','D',0,1};
    private static final byte[] DOMAIN="PadNote/MaterialDescriptor/v1\0".getBytes(StandardCharsets.UTF_8);
    private static final int NULL=0, UTF8=1, I64=2, BYTES=3, F64=4, RECORD=5;
    static volatile String ioFailureForTests;
    public static final class Video {
        public String materialId,ownerLineageId,sourceLineageId,sourceState,sourceNoteId,sourceBundleSha256,taskPayloadSha256,digestKind,taskId,remoteTaskId;
        public String connectionId,connectionKind,transport,bridgeId,instanceId,certificateSha256,artifactId,displayName,mediaType,contentSha256,offlineState,originKind;
        public long sourceRevisionRaw,connectionRevision,byteLength; public int sourceRevisionPrecisionMs;
        public String sourceRevisionRepresentation; public Timestamp createdAt;
    }
    public static final class Vault {
        public String materialId,ownerLineageId,sourceLineageId,sourceState,sourceNoteId,title;
        public Timestamp sourceRevision,createdAt; public Integer pageCount; public byte[] markdownUtf8;
    }
    public static final class Timestamp {
        public final String representation; private final Long integer; private final Double floating;
        private Timestamp(String r,Long i,Double f){representation=r;integer=i;floating=f;}
        public static Timestamp integer(String representation,long value){return new Timestamp(representation,value,null);}
        public static Timestamp floating(String representation,double value){return new Timestamp(representation,null,value);}
        public Long integerValue(){return integer;}
        public Double floatingValue(){return floating;}
    }
    public static final class Identity {
        public final String materialId,lineageId,kind; private byte[] descriptorBytes;
        public Identity(String m,String l,String k){materialId=m;lineageId=l;kind=k;}
        public byte[] descriptorBytes(){require(descriptorBytes!=null,"descriptor_unbound");return descriptorBytes.clone();}
    }
    @android.annotation.TargetApi(27)
    public static final class Sidecar {
        private final Map<String,Identity> byLocal=new HashMap<>();
        private final Map<String,String> localByMaterial=new HashMap<>();
        public synchronized Identity associate(String local,String lineage,String kind,String materialId){
            require(local!=null&&!local.isEmpty(),"local_locator"); if(lineage!=null)validId(lineage);validId(materialId);require(kind.equals("video")||kind.equals("vault"),"kind");
            require(!byLocal.containsKey(local)&&!localByMaterial.containsKey(materialId),"identity_duplicate");
            Identity x=new Identity(materialId,lineage,kind);byLocal.put(local,x);localByMaterial.put(materialId,local);return x;
        }
        public synchronized Identity associateNew(String local,String lineage,String kind){return associate(local,lineage,kind,UUID.randomUUID().toString().toLowerCase(Locale.ROOT));}
        public synchronized void bindDescriptor(String local,byte[] descriptor){Identity x=byLocal.get(local);require(x!=null,"identity_missing");Binding v=binding(descriptor);require(v.materialId.equals(x.materialId)&&Objects.equals(v.ownerLineageId,x.lineageId)&&v.kind.equals(x.kind),"descriptor_identity_mismatch");x.descriptorBytes=descriptor.clone();}
        public synchronized Identity exportIdentity(String local){Identity x=byLocal.get(local);require(x!=null,"identity_missing");x.descriptorBytes();return x;}
        public synchronized void importRemap(Identity x,String newLocal){require(x!=null,"identity_missing");Identity y=associate(newLocal,x.lineageId,x.kind,x.materialId);y.descriptorBytes=x.descriptorBytes();}
        public synchronized String localFor(String material){return localByMaterial.get(material);}
        public synchronized byte[] portableBytes(){
            List<Identity> xs=new ArrayList<>(byLocal.values());xs.sort((a,b)->cmp(utf8(a.materialId),utf8(b.materialId)));
            Set<String> seen=new HashSet<>();List<Field[]> rows=new ArrayList<>();for(Identity x:xs){require(seen.add(x.materialId),"identity_duplicate");rows.add(new Field[]{s(1,x.materialId),nullable(2,x.lineageId),s(3,x.kind),b(4,sha(x.descriptorBytes()))});}
            return collection((byte)3,rows);
        }
        public synchronized byte[] localStateBytes(){
            List<String> keys=new ArrayList<>(byLocal.keySet());keys.sort((a,b)->cmp(utf8(a),utf8(b)));StringBuilder out=new StringBuilder("PNSLOCAL1\n");
            for(String key:keys){Identity x=byLocal.get(key);String desc=Base64.getUrlEncoder().withoutPadding().encodeToString(x.descriptorBytes());out.append(enc(key)).append('|').append(enc(x.materialId)).append('|').append(x.lineageId==null?"":enc(x.lineageId)).append('|').append(x.kind).append('|').append(desc).append('\n');}
            return out.toString().getBytes(StandardCharsets.US_ASCII);
        }
        public synchronized void writeLocal(Path file)throws IOException{
            Path parent=file.toAbsolutePath().getParent();require(parent!=null,"sidecar_parent");plainAncestors(parent);regularSingleLinkIfPresent(file);require(!Files.exists(file,LinkOption.NOFOLLOW_LINKS),"sidecar_exists");byte[] expected=localStateBytes();Path temp=Files.createTempFile(parent,".material-sidecar-",".tmp");Object tempKey=fileKey(temp);
            try {try(FileChannel ch=FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){ByteBuffer b=ByteBuffer.wrap(expected);while(b.hasRemaining())ch.write(b);maybeFail("file_fsync");ch.force(true);}require(owns(temp,tempKey),"sidecar_temp_changed");plainAncestors(parent);require(!Files.exists(file,LinkOption.NOFOLLOW_LINKS),"sidecar_exists");Files.createLink(file,temp);require(sameInode(file,tempKey)&&sameInode(temp,tempKey),"sidecar_install_changed");Files.delete(temp);try(FileChannel dir=FileChannel.open(parent,StandardOpenOption.READ)){maybeFail("directory_fsync");dir.force(true);}require(Arrays.equals(expected,readBounded(file)),"sidecar_verify");}
            catch(Exception failure){IOException primary=failure instanceof IOException?(IOException)failure:new IOException("SIDECAR_WRITE",failure);try{if(sameInode(file,tempKey))Files.delete(file);if(sameInode(temp,tempKey))Files.delete(temp);}catch(Exception cleanup){primary.addSuppressed(cleanup);}throw primary;}
            finally{if(sameInode(temp,tempKey))Files.deleteIfExists(temp);}
        }
        public static Sidecar readLocal(Path file)throws IOException{
            plainAncestors(file.toAbsolutePath().getParent());require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file),"sidecar_target");byte[] raw=readBounded(file);String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}catch(Exception e){throw new IOException("SIDECAR_UTF8");}
            require(text.startsWith("PNSLOCAL1\n")&&text.endsWith("\n"),"sidecar_format");Sidecar out=new Sidecar();if(text.length()>10){String[] lines=text.substring(10,text.length()-1).split("\n",-1);for(String line:lines){String[] c=line.split("\\|",-1);require(c.length==5,"sidecar_row");String local=dec(c[0]),material=dec(c[1]),owner=c[2].isEmpty()?null:dec(c[2]),kind=c[3];out.associate(local,owner,kind,material);out.bindDescriptor(local,Base64.getUrlDecoder().decode(c[4]));}}require(Arrays.equals(raw,out.localStateBytes()),"sidecar_noncanonical");return out;
        }
        private static String enc(String x){return Base64.getUrlEncoder().withoutPadding().encodeToString(utf8(x));}
        private static String dec(String x)throws IOException{try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(x))).toString();}catch(Exception e){throw new IOException("SIDECAR_FIELD");}}
        private static byte[] readBounded(Path p)throws IOException{BasicFileAttributes before=attributes(p);require(before.isRegularFile()&&!Files.isSymbolicLink(p)&&singleLink(p),"sidecar_target");long n=before.size();if(n<0||n>16L*1024*1024)throw new IOException("SIDECAR_SIZE");try(SeekableByteChannel ch=Files.newByteChannel(p,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){ByteArrayOutputStream out=new ByteArrayOutputStream((int)n);ByteBuffer chunk=ByteBuffer.allocate(8192);int count;while((count=ch.read(chunk))>=0){if(count==0)continue;if(out.size()+count>16*1024*1024)throw new IOException("SIDECAR_SIZE");out.write(chunk.array(),0,count);chunk.clear();}byte[]b=out.toByteArray();BasicFileAttributes after=attributes(p);if(b.length!=n||!same(before,after)||!singleLink(p))throw new IOException("SIDECAR_CHANGED");return b;}}
        private static void plainAncestors(Path p)throws IOException{for(Path x=p;x!=null;x=x.getParent()){if(Files.exists(x,LinkOption.NOFOLLOW_LINKS)&&Files.isSymbolicLink(x))throw new IOException("SIDECAR_LINK");}if(!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("SIDECAR_PARENT");}
        private static BasicFileAttributes attributes(Path p)throws IOException{return Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        private static Object fileKey(Path p)throws IOException{Object k=attributes(p).fileKey();if(k==null)throw new IOException("SIDECAR_IDENTITY_UNAVAILABLE");return k;}
        private static boolean same(BasicFileAttributes a,BasicFileAttributes b){return Objects.equals(a.fileKey(),b.fileKey())&&a.size()==b.size()&&a.lastModifiedTime().equals(b.lastModifiedTime())&&a.creationTime().equals(b.creationTime())&&a.isRegularFile()==b.isRegularFile();}
        private static boolean owns(Path p,Object key)throws IOException{return Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&attributes(p).isRegularFile()&&!Files.isSymbolicLink(p)&&Objects.equals(key,attributes(p).fileKey())&&singleLink(p);}
        private static boolean sameInode(Path p,Object key)throws IOException{return Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&attributes(p).isRegularFile()&&!Files.isSymbolicLink(p)&&Objects.equals(key,attributes(p).fileKey());}
        private static boolean singleLink(Path p)throws IOException{try{return ((Number)Files.getAttribute(p,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()==1;}catch(UnsupportedOperationException|IllegalArgumentException e){return true;}}
        private static void regularSingleLinkIfPresent(Path p)throws IOException{if(Files.exists(p,LinkOption.NOFOLLOW_LINKS))require(attributes(p).isRegularFile()&&!Files.isSymbolicLink(p)&&singleLink(p),"sidecar_target");}
        private static void maybeFail(String point)throws IOException{if(point.equals(ioFailureForTests)){ioFailureForTests=null;throw new IOException("INJECTED_"+point);}}
    }
    public static byte[] videoBytes(Video v){
        require(v!=null,"video_missing");checkIdentity(v.materialId,v.ownerLineageId,v.sourceLineageId,v.sourceState);
        require(v.sourceRevisionRaw>=0&&(v.sourceRevisionPrecisionMs==1||v.sourceRevisionPrecisionMs==1000),"source_revision_precision");
        require(("unix_ms_i64".equals(v.sourceRevisionRepresentation)&&(v.sourceRevisionPrecisionMs==1||v.sourceRevisionPrecisionMs==1000))||("unix_seconds_integer".equals(v.sourceRevisionRepresentation)&&v.sourceRevisionPrecisionMs==1000),"source_revision_representation");
        require(v.connectionRevision>=0&&v.byteLength>0&&v.byteLength<=100L*1024*1024,"video_bounds");validCreatedAt(v.createdAt);
        require("video/mp4".equals(v.mediaType)&&"verified_local_copy".equals(v.offlineState),"video_media_state");
        hash(v.sourceBundleSha256,false);hash(v.taskPayloadSha256,true);hash(v.certificateSha256,true);hash(v.contentSha256,false);require(Arrays.asList("computer_task","restored_archive").contains(v.originKind),"origin_kind");
        req(v.sourceNoteId,"source_note_id");req(v.digestKind,"digest_kind");req(v.taskId,"task_id");req(v.remoteTaskId,"remote_task_id");req(v.connectionId,"connection_id");req(v.connectionKind,"connection_kind");opt(v.transport);opt(v.bridgeId);opt(v.instanceId);req(v.artifactId,"artifact_id");req(v.displayName,"display_name");
        List<Field> f=new ArrayList<>();f.add(s(1,v.materialId));f.add(nullable(2,v.ownerLineageId));f.add(s(3,v.sourceState));f.add(nullable(4,v.sourceLineageId));f.add(s(5,v.sourceNoteId));f.add(time(6,v.sourceRevisionRepresentation,v.sourceRevisionRaw));f.add(i(7,v.sourceRevisionPrecisionMs));f.add(b(8,unhex(v.sourceBundleSha256)));f.add(nullableHash(9,v.taskPayloadSha256));f.add(s(10,v.digestKind));f.add(s(11,v.taskId));f.add(s(12,v.remoteTaskId));f.add(s(13,v.connectionId));f.add(i(14,v.connectionRevision));f.add(s(15,v.connectionKind));f.add(nullable(16,v.transport));f.add(nullable(17,v.bridgeId));f.add(nullable(18,v.instanceId));f.add(nullableHash(19,v.certificateSha256));f.add(s(20,v.artifactId));f.add(s(21,v.displayName));f.add(s(22,v.mediaType));f.add(i(23,v.byteLength));f.add(b(24,unhex(v.contentSha256)));f.add(time(25,v.createdAt));f.add(s(26,v.offlineState));f.add(s(27,v.originKind));return record((byte)1,f);
    }
    public static byte[] vaultBytes(Vault v){
        require(v!=null,"vault_missing");checkIdentity(v.materialId,v.ownerLineageId,v.sourceLineageId,v.sourceState);req(v.sourceNoteId,"source_note_id");req(v.title,"title");validVaultRevision(v.sourceRevision);validCreatedAt(v.createdAt);require(v.markdownUtf8!=null&&v.markdownUtf8.length<=16*1024*1024,"vault_body_limit");strictUtf8(v.markdownUtf8);
        require(v.pageCount==null||v.pageCount>=0,"vault_page_count");return vaultDescriptor(v,sha(v.markdownUtf8),v.markdownUtf8.length);
    }
    private static byte[] vaultDescriptor(Vault v,byte[] bodyHash,long bodyLength){require(v!=null&&bodyHash.length==32&&bodyLength>=0&&bodyLength<=16*1024*1024,"vault_descriptor_bounds");checkIdentity(v.materialId,v.ownerLineageId,v.sourceLineageId,v.sourceState);req(v.sourceNoteId,"source_note_id");req(v.title,"title");validVaultRevision(v.sourceRevision);validCreatedAt(v.createdAt);require(v.pageCount==null||v.pageCount>=0,"vault_page_count");return record((byte)2,Arrays.asList(s(1,v.materialId),nullable(2,v.ownerLineageId),s(3,v.sourceState),nullable(4,v.sourceLineageId),s(5,v.sourceNoteId),time(6,v.sourceRevision),time(7,v.createdAt),s(8,v.title),i(9,bodyLength),b(10,bodyHash),i(11,1),v.pageCount==null?new Field(12,NULL,new byte[0]):i(12,v.pageCount)));}
    public static byte[] digest(byte[] descriptor){return sha(concat(DOMAIN,descriptor));}
    public static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    private static byte[] record(byte kind,List<Field> fs){ByteArrayOutputStream o=new ByteArrayOutputStream();o.write(MAGIC,0,MAGIC.length);o.write(kind);u32(o,fs.size());int last=0;for(Field f:fs){require(f.tag>last,"tag_order");last=f.tag;o.write((f.tag>>>8)&255);o.write(f.tag&255);o.write(f.type);u32(o,f.data.length);o.write(f.data,0,f.data.length);}return o.toByteArray();}
    private static byte[] collection(byte kind,List<Field[]> rows){ByteArrayOutputStream o=new ByteArrayOutputStream();o.write(MAGIC,0,MAGIC.length);o.write(kind);u32(o,rows.size());for(Field[] row:rows){byte[] r=record((byte)4,Arrays.asList(row));u32(o,r.length);o.write(r,0,r.length);}return o.toByteArray();}
    private static final class Field{int tag,type;byte[]data;Field(int a,int b,byte[]c){tag=a;type=b;data=c;}}
    private static Field s(int t,String x){return new Field(t,UTF8,utf8(x));}private static Field nullable(int t,String x){return x==null?new Field(t,NULL,new byte[0]):s(t,x);}
    private static Field i(int t,long x){return new Field(t,I64,ByteBuffer.allocate(8).putLong(x).array());}private static Field b(int t,byte[] x){return new Field(t,BYTES,x);}
    private static Field nullableHash(int t,String x){return x==null?new Field(t,NULL,new byte[0]):b(t,unhex(x));}
    private static final class Binding{String materialId,ownerLineageId,sourceState,sourceLineageId,sourceNoteId,title,kind,mediaType,contentSha256;long contentSize;Video video;}
    /** Read-only semantic projection of a canonical stored descriptor. */
    public record DescriptorSummary(String materialId,String ownerLineageId,String sourceState,
                                    String sourceLineageId,String sourceNoteId,String title,String kind,String mediaType,
                                    long contentSize,String contentSha256) {}
    public record VaultDescriptorSummary(DescriptorSummary descriptor,String sourceRevisionRepresentation,
                                         Long sourceRevisionMillis,String createdAtRepresentation,
                                         Long createdAtMillis,Integer pageCount) {}
    private static final class Parsed{int kind;List<Field> fields;}
    public static DescriptorSummary inspectDescriptor(byte[] d){
        Binding b=binding(d);
        return new DescriptorSummary(b.materialId,b.ownerLineageId,b.sourceState,b.sourceLineageId,b.sourceNoteId,b.title,b.kind,b.mediaType,b.contentSize,b.contentSha256);
    }
    /** Parses and canonically verifies a stored Video descriptor into a fresh value object. */
    public static Video inspectVideoDescriptor(byte[] d){Binding b=binding(d);require("video".equals(b.kind),"descriptor_kind");return b.video;}
    public static VaultDescriptorSummary inspectVaultDescriptor(byte[] d){
        DescriptorSummary summary=inspectDescriptor(d);require("vault".equals(summary.kind()),"descriptor_kind");
        Parsed p=parseRecord(d,2,12);Timestamp source=timestamp(p.fields,5,6),created=timestamp(p.fields,6,7);
        Field page=fldAny(p.fields,11,12,new int[]{NULL,I64});
        return new VaultDescriptorSummary(summary,source.representation,source.integer,created.representation,created.integer,
                page.type==NULL?null:Math.toIntExact(lng(p.fields,11,12)));
    }
    private static Binding binding(byte[] d){require(d!=null&&d.length<=16*1024*1024,"descriptor_size");require(d.length>=11&&Arrays.equals(Arrays.copyOf(d,6),MAGIC),"descriptor_invalid");int k=d[6]&255;require(k==1||k==2,"descriptor_kind");Parsed p=parseRecord(d,k,k==1?27:12);Video v=null;Vault w=null;List<Field> f=p.fields;
        if(k==1){v=new Video();v.materialId=str(f,0,1,false);v.ownerLineageId=str(f,1,2,true);v.sourceState=str(f,2,3,false);v.sourceLineageId=str(f,3,4,true);v.sourceNoteId=str(f,4,5,false);Timestamp revision=timestamp(f,5,6);require(revision.integer!=null,"descriptor_video_revision_type");v.sourceRevisionRepresentation=revision.representation;v.sourceRevisionRaw=revision.integer;v.sourceRevisionPrecisionMs=(int)lng(f,6,7);v.sourceBundleSha256=hexBytes(fld(f,7,8,BYTES,false).data);v.taskPayloadSha256=optionalHex(f,8,9);v.digestKind=str(f,9,10,false);v.taskId=str(f,10,11,false);v.remoteTaskId=str(f,11,12,false);v.connectionId=str(f,12,13,false);v.connectionRevision=lng(f,13,14);v.connectionKind=str(f,14,15,false);v.transport=str(f,15,16,true);v.bridgeId=str(f,16,17,true);v.instanceId=str(f,17,18,true);v.certificateSha256=optionalHex(f,18,19);v.artifactId=str(f,19,20,false);v.displayName=str(f,20,21,false);v.mediaType=str(f,21,22,false);v.byteLength=lng(f,22,23);v.contentSha256=hexBytes(fld(f,23,24,BYTES,false).data);v.createdAt=timestamp(f,24,25);v.offlineState=str(f,25,26,false);v.originKind=str(f,26,27,false);require(Arrays.equals(d,videoBytes(v)),"descriptor_noncanonical");}
        else {w=new Vault();w.materialId=str(f,0,1,false);w.ownerLineageId=str(f,1,2,true);w.sourceState=str(f,2,3,false);w.sourceLineageId=str(f,3,4,true);w.sourceNoteId=str(f,4,5,false);w.sourceRevision=timestamp(f,5,6);w.createdAt=timestamp(f,6,7);w.title=str(f,7,8,false);long n=lng(f,8,9);byte[] hash=fld(f,9,10,BYTES,false).data;require(hash.length==32,"descriptor_hash_length");require(lng(f,10,11)==1,"descriptor_vault_schema");Field page=fldAny(f,11,12,new int[]{NULL,I64});w.pageCount=page.type==NULL?null:Math.toIntExact(lng(f,11,12));require(Arrays.equals(d,vaultDescriptor(w,hash,n)),"descriptor_noncanonical");}
        Binding out=new Binding();out.materialId=k==1?v.materialId:w.materialId;out.ownerLineageId=k==1?v.ownerLineageId:w.ownerLineageId;out.sourceState=k==1?v.sourceState:w.sourceState;out.sourceLineageId=k==1?v.sourceLineageId:w.sourceLineageId;out.sourceNoteId=k==1?v.sourceNoteId:w.sourceNoteId;out.title=k==1?v.displayName:w.title;out.kind=k==1?"video":"vault";out.mediaType=k==1?v.mediaType:null;out.contentSize=k==1?v.byteLength:lng(f,8,9);out.contentSha256=k==1?v.contentSha256:hexBytes(fld(f,9,10,BYTES,false).data);out.video=v;return out;}
    private static Parsed parseRecord(byte[] d,int expectedKind,int expectedCount){require(d.length>=11&&Arrays.equals(Arrays.copyOf(d,6),MAGIC)&&(d[6]&255)==expectedKind,"descriptor_record");long count=u32at(d,7);require(count==expectedCount,"descriptor_field_count");int p=11,last=0;List<Field> fs=new ArrayList<>();for(int i=0;i<expectedCount;i++){require(p+7<=d.length,"descriptor_truncated");int tag=((d[p]&255)<<8)|(d[p+1]&255),type=d[p+2]&255;long n=u32at(d,p+3);require(tag==i+1&&tag>last&&n<=d.length-p-7,"descriptor_field_shape");last=tag;p+=7;byte[] b=Arrays.copyOfRange(d,p,p+(int)n);p+=(int)n;if(type==NULL)require(n==0,"descriptor_null_length");if(type==I64||type==F64)require(n==8,"descriptor_number_length");fs.add(new Field(tag,type,b));}require(p==d.length,"descriptor_trailing_bytes");Parsed out=new Parsed();out.kind=expectedKind;out.fields=fs;return out;}
    private static long u32at(byte[]d,int p){return ((long)(d[p]&255)<<24)|((long)(d[p+1]&255)<<16)|((long)(d[p+2]&255)<<8)|(d[p+3]&255L);}
    private static Field fld(List<Field> f,int idx,int tag,int type,boolean nullable){require(idx<f.size(),"descriptor_missing_field");Field x=f.get(idx);require(x.tag==tag&&(x.type==type||nullable&&x.type==NULL),"descriptor_field_type");return x;}
    private static Field fldAny(List<Field>f,int idx,int tag,int[]types){require(idx<f.size(),"descriptor_missing_field");Field x=f.get(idx);boolean ok=false;for(int t:types)ok|=x.type==t;require(x.tag==tag&&ok,"descriptor_field_type");return x;}
    private static String str(List<Field>f,int idx,int tag,boolean nullable){Field x=fld(f,idx,tag,UTF8,nullable);if(x.type==NULL)return null;try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(x.data)).toString();}catch(Exception e){throw new IllegalArgumentException("descriptor_utf8");}}
    private static long lng(List<Field>f,int idx,int tag){byte[]b=fld(f,idx,tag,I64,false).data;return ByteBuffer.wrap(b).getLong();}
    private static String hexBytes(byte[]b){return hex(b);}
    private static String optionalHex(List<Field>f,int idx,int tag){Field x=fld(f,idx,tag,BYTES,true);return x.type==NULL?null:hexBytes(x.data);}
    private static Timestamp timestamp(List<Field>f,int idx,int tag){Field x=fld(f,idx,tag,RECORD,false);Parsed p=parseRecord(x.data,5,2);String repr=str(p.fields,0,1,false);Field n=p.fields.get(1);require(n.tag==2&&(n.type==I64||n.type==F64),"descriptor_timestamp_type");return n.type==I64?Timestamp.integer(repr,ByteBuffer.wrap(n.data).getLong()):Timestamp.floating(repr,Double.longBitsToDouble(ByteBuffer.wrap(n.data).getLong()));}
    private static Field time(int tag,String representation,long value){return time(tag,Timestamp.integer(representation,value));}
    private static Field time(int tag,Timestamp t){validTimestamp(t);return new Field(tag,RECORD,record((byte)5,Arrays.asList(s(1,t.representation),t.integer!=null?i(2,t.integer):f(2,t.floating))));}
    private static Field f(int t,double x){require(Double.isFinite(x),"timestamp_f64");return new Field(t,F64,ByteBuffer.allocate(8).putLong(Double.doubleToRawLongBits(x)).array());}
    private static void validTimestamp(Timestamp t){require(t!=null&&t.representation!=null&&(t.integer!=null)^(t.floating!=null),"timestamp_shape");utf8(t.representation);if(t.floating!=null){require("apple_reference_seconds_f64".equals(t.representation)||"source_updated_at_ms_f64".equals(t.representation),"timestamp_representation");double min="apple_reference_seconds_f64".equals(t.representation)?-978307200d:0d;require(Double.isFinite(t.floating)&&t.floating>=min,"timestamp_f64");}if(t.integer!=null){require("unix_ms_i64".equals(t.representation)||"unix_seconds_integer".equals(t.representation),"timestamp_representation");require(t.integer>=0,"timestamp_i64");}}
    private static void validCreatedAt(Timestamp t){validTimestamp(t);require("unix_ms_i64".equals(t.representation)||"apple_reference_seconds_f64".equals(t.representation),"created_at_representation");}
    private static void validVaultRevision(Timestamp t){validTimestamp(t);require("unix_ms_i64".equals(t.representation)||"source_updated_at_ms_f64".equals(t.representation),"vault_revision_representation");}
    private static byte[] utf8(String x){require(x!=null,"string");try{ByteBuffer b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(x));byte[]r=new byte[b.remaining()];b.get(r);return r;}catch(CharacterCodingException e){throw new IllegalArgumentException("invalid_unicode_scalar");}}
    private static void strictUtf8(byte[]x){try{StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(x));}catch(Exception e){throw new IllegalArgumentException("invalid_utf8");}}
    private static void validId(String x){req(x,"id");require(x.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),"id_format");}
    private static void checkIdentity(String m,String owner,String source,String state){validId(m);require(state!=null&&Arrays.asList("linked_note","source_deleted","source_not_selected","independent").contains(state),"source_state");if(owner!=null)validId(owner);if(source!=null)validId(source);require(!state.equals("independent")||owner==null&&source==null,"independent_lineage_forbidden");require(state.equals("independent")||owner!=null,"owner_lineage_missing");require(!state.equals("linked_note")||Objects.equals(owner,source),"source_lineage_mismatch");require(state.equals("independent")||source==null||Objects.equals(owner,source),"source_lineage_mismatch");}
    private static void hash(String x,boolean nullable){if(x==null){require(nullable,"hash_missing");return;}require(x.matches("[0-9a-f]{64}"),"hash_format");}
    private static byte[] unhex(String x){byte[]r=new byte[x.length()/2];for(int i=0;i<r.length;i++)r[i]=(byte)Integer.parseInt(x.substring(i*2,i*2+2),16);return r;}
    private static byte[] sha(byte[]x){try{return MessageDigest.getInstance("SHA-256").digest(x);}catch(Exception e){throw new AssertionError(e);}}
    private static byte[] concat(byte[]a,byte[]b){byte[]r=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,r,a.length,b.length);return r;}
    private static int cmp(byte[]a,byte[]b){for(int i=0;i<Math.min(a.length,b.length);i++){int x=a[i]&255,y=b[i]&255;if(x!=y)return Integer.compare(x,y);}return Integer.compare(a.length,b.length);}
    private static void u32(ByteArrayOutputStream o,int x){o.write((x>>>24)&255);o.write((x>>>16)&255);o.write((x>>>8)&255);o.write(x&255);}
    private static void req(String x,String c){require(x!=null&&!x.isEmpty(),c);utf8(x);}private static void opt(String x){if(x!=null)utf8(x);}
    private static void require(boolean b,String c){if(!b)throw new IllegalArgumentException(c);}
}
