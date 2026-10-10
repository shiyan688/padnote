package com.padnote.android;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import com.padnote.android.streaming.StreamingGroupStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Marker-bound read projection used by legacy UI APIs after an explicit group update. */
@TargetApi(27)
final class GroupAuthorityBridge {
    private static final long PDF_MAX=512L*1024*1024;
    private GroupAuthorityBridge() {}

    /** A single list invocation owns one strict inventory and compact summaries of freshly verified groups. */
    static final class CatalogReader {
        private final NoteGroupFacade facade;
        private StreamingGroupStore.Store.VisibleCatalog visibleCatalog;
        CatalogReader(Context context)throws Exception { this(context,null); }
        CatalogReader(Context context,com.padnote.android.streaming.StreamingGroupStore.ReadDiagnostics diagnostics)throws Exception {
            facade=diagnostics==null?new NoteGroupFacade(context):new NoteGroupFacade(context,null,diagnostics);
        }
        StreamingGroupStore.Snapshot open(String localId)throws Exception {
            if(Build.VERSION.SDK_INT<27)return null;
            return facade.openGroup(localId);
        }
        StreamingGroupStore.Snapshot openIncludingRetired(String localId)throws Exception {
            if(Build.VERSION.SDK_INT<27)return null;
            return facade.openGroupIncludingRetired(localId);
        }
        void prepareVisibleCatalog(java.util.Set<String> requestedLocalIds)throws Exception {
            if(Build.VERSION.SDK_INT<27)return;
            if(visibleCatalog!=null)throw new IOException("VISIBLE_CATALOG_ALREADY_PREPARED");
            visibleCatalog=facade.readVisibleCatalog(requestedLocalIds,snapshot->{
                byte[] body=snapshot.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX);
                final JSONObject document;
                try{document=new JSONObject(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(body)).toString());}
                catch(Exception invalid){throw new IOException("GROUP_BODY_INVALID",invalid);}
                if(!snapshot.localId.equals(document.optString("id",null)))throw new IOException("GROUP_BODY_ID_MISMATCH");
                org.json.JSONArray strokes;
                try{strokes=document.getJSONArray("strokes");}catch(Exception invalid){throw new IOException("GROUP_BODY_INVALID",invalid);}
                int pageCount=Math.max(1,document.optInt("pageCount",1));
                String title=document.has("title")&&!document.isNull("title")?document.optString("title",null):null;
                return new StreamingGroupStore.Store.ShelfMetadata(snapshot.localId,title,
                        document.optLong("updatedAt",0),strokes==null?0:strokes.length(),pageCount,
                        document.has("title")&&!document.isNull("title"),document.has("updatedAt")&&!document.isNull("updatedAt"),
                        NoteGroupFacade.isRetiredDocument(document));
            });
        }
        StreamingGroupStore.Store.ShelfMetadata shelfMetadataIfManaged(String localId)throws Exception {
            if(Build.VERSION.SDK_INT<27)return null;
            if(visibleCatalog==null)throw new IOException("VISIBLE_CATALOG_NOT_PREPARED");
            StreamingGroupStore.Store.CatalogEntry entry=visibleCatalog.find(localId);
            if(entry==null)return null;
            if(entry.failureCode!=null)throw new IOException(entry.failureCode);
            return entry.metadata;
        }
        /** Full document reads always re-open and verify a complete group snapshot. */
        JSONObject bodyIfManaged(String localId)throws Exception {
            if(Build.VERSION.SDK_INT<27)return null;
            if(visibleCatalog!=null)throw new IOException("GROUP_CATALOG_BODY_NOT_AVAILABLE");
            return bodyFromSnapshot(openIncludingRetired(localId));
        }
        private JSONObject bodyFromSnapshot(StreamingGroupStore.Snapshot snapshot)throws Exception {
            if(snapshot==null)return null;
            byte[] body=snapshot.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX);
            final JSONObject document;
            try{document=new JSONObject(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(body)).toString());}
            catch(Exception invalid){throw new IOException("GROUP_BODY_INVALID",invalid);}
            if(NoteGroupFacade.isRetiredDocument(document))throw new IOException("GROUP_NOTE_RETIRED");
            return document;
        }
        /** List reconciliation consumes compact metadata; standalone lookups return full bodies. */
        JSONObject bodyIfManagedFresh(String localId)throws Exception {
            if(Build.VERSION.SDK_INT<27)return null;
            if(visibleCatalog!=null)throw new IOException("GROUP_CATALOG_BODY_NOT_AVAILABLE");
            facade.resetCatalogReadWitnessesForFreshLookup();
            return bodyIfManaged(localId);
        }
    }
    static CatalogReader catalogReader(Context context)throws Exception { return new CatalogReader(context); }
    static CatalogReader catalogReader(Context context,com.padnote.android.streaming.StreamingGroupStore.ReadDiagnostics diagnostics)throws Exception {
        return new CatalogReader(context,diagnostics);
    }

    static final class VaultView {
        final String fileName, materialId, noteId, title;
        final int pageCount;
        final long digitizedAt, sourceModifiedAt;
        final byte[] markdown;
        VaultView(String fileName,String materialId,String noteId,String title,int pageCount,long digitizedAt,long sourceModifiedAt,byte[] markdown){
            this.fileName=fileName;this.materialId=materialId;this.noteId=noteId;this.title=title;this.pageCount=pageCount;
            this.digitizedAt=digitizedAt;this.sourceModifiedAt=sourceModifiedAt;this.markdown=markdown==null?null:markdown.clone();
        }
    }

    static List<VaultView> vaultViews(Context context,String localId)throws Exception {
        StreamingGroupStore.Snapshot snapshot=open(context,localId);
        if(snapshot==null)return null;
        return vaultViews(snapshot);
    }

    static List<VaultView> vaultViews(StreamingGroupStore.Snapshot snapshot)throws Exception {
        return vaultViews(snapshot,null,true);
    }

    /** Verifies every Vault member while retaining metadata only for shelf rows. */
    static List<VaultView> vaultSummaries(StreamingGroupStore.Snapshot snapshot)throws Exception {
        return vaultViews(snapshot,null,false);
    }

    /** Fully verifies one selected member from the supplied, already-open snapshot. */
    static VaultView vaultView(StreamingGroupStore.Snapshot snapshot,String materialId)throws Exception {
        if(materialId==null||!materialId.matches("[0-9a-f-]{36}"))throw new IOException("GROUP_VAULT_MATERIAL_ID_INVALID");
        List<VaultView> views=vaultViews(snapshot,materialId,true);
        if(views.isEmpty())throw new IOException("GROUP_VAULT_MATERIAL_REMOVED");
        return views.get(0);
    }

    private static List<VaultView> vaultViews(StreamingGroupStore.Snapshot snapshot,
                                               String onlyMaterialId,boolean retainMarkdown)throws Exception {
        if(snapshot==null)throw new IOException("GROUP_SNAPSHOT_REQUIRED");
        String localId=snapshot.localId;
        List<VaultView> result=new ArrayList<>();
        for(String member:snapshot.memberSizes().keySet()){
            if(!member.matches("vault/[0-9a-f-]{36}/metadata\\.bin"))continue;
            String materialId=member.substring("vault/".length(),member.length()-"/metadata.bin".length());
            if(onlyMaterialId!=null&&!onlyMaterialId.equals(materialId))continue;
            byte[] descriptor=snapshot.readSmall(member,1<<20);
            padnote.material.MaterialCodec.VaultDescriptorSummary vaultDescriptor=padnote.material.MaterialCodec.inspectVaultDescriptor(descriptor);
            padnote.material.MaterialCodec.DescriptorSummary summary=vaultDescriptor.descriptor();
            if(!"vault".equals(summary.kind())||!materialId.equals(summary.materialId())
                    ||!snapshot.lineage.equals(summary.ownerLineageId())
                    ||!snapshot.lineage.equals(summary.sourceLineageId())
                    ||!"linked_note".equals(summary.sourceState()))
                throw new IOException("GROUP_VAULT_DESCRIPTOR_IDENTITY_MISMATCH");
            String contentMember="vault/"+materialId+"/content.bin";
            byte[] markdown=snapshot.readSmall(contentMember,16*1024*1024);
            if(markdown.length!=summary.contentSize()||!shaHex(markdown).equals(summary.contentSha256()))
                throw new IOException("GROUP_VAULT_CONTENT_DESCRIPTOR_MISMATCH");
            final String text;
            try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(markdown)).toString();}
            catch(Exception invalid){throw new IOException("GROUP_VAULT_UTF8_INVALID",invalid);}
            try{
                padnote.material.StorageAdapter.androidVaultMarkdown(markdown,
                        new padnote.material.StorageAdapter.Association(materialId,snapshot.lineage,"linked_note",snapshot.lineage));
            }catch(Exception invalid){throw new IOException("GROUP_VAULT_MARKDOWN_INVALID",invalid);}
            String head=text.substring(0,Math.min(text.length(),4096));
            if(!localId.equals(VaultStore.frontValue(head,"note-id"))
                    ||!summary.title().equals(VaultStore.frontValue(head,"title")))
                throw new IOException("GROUP_VAULT_NOTE_BINDING_MISMATCH");
            int pages;long created,modified;
            try{pages=Integer.parseInt(VaultStore.frontValue(head,"pages"));created=Long.parseLong(VaultStore.frontValue(head,"digitized-epoch"));modified=Long.parseLong(VaultStore.frontValue(head,"source-modified"));}
            catch(Exception invalid){throw new IOException("GROUP_VAULT_TIMESTAMP_INVALID",invalid);}
            if(pages<0||created<0||modified<0||vaultDescriptor.pageCount()==null||vaultDescriptor.pageCount()!=pages
                    ||!"unix_ms_i64".equals(vaultDescriptor.sourceRevisionRepresentation())
                    ||!"unix_ms_i64".equals(vaultDescriptor.createdAtRepresentation())
                    ||!Long.valueOf(modified).equals(vaultDescriptor.sourceRevisionMillis())
                    ||!Long.valueOf(created).equals(vaultDescriptor.createdAtMillis()))
                throw new IOException("GROUP_VAULT_TIMESTAMP_INVALID");
            result.add(new VaultView(virtualVaultName(localId,materialId),materialId,localId,summary.title(),pages,created,modified,
                    retainMarkdown?markdown:null));
        }
        result.sort((a,b)->Long.compare(b.digitizedAt,a.digitizedAt));
        return Collections.unmodifiableList(result);
    }

    static byte[] readVirtualVault(Context context,String fileName,int maximumBytes)throws Exception {
        String[] identity=parseVirtualVaultName(fileName);
        if(identity==null)return null;
        if(Build.VERSION.SDK_INT<27)throw new IOException("GROUP_VAULT_REQUIRES_API_27");
        if(maximumBytes<0)throw new IOException("VAULT_READ_LIMIT_INVALID");
        StreamingGroupStore.Snapshot snapshot=open(context,identity[0]);
        if(snapshot==null)throw new IOException("GROUP_VAULT_MARKER_MISSING");
        String prefix="vault/"+identity[1]+"/";
        byte[] descriptor=snapshot.readSmall(prefix+"metadata.bin",1<<20);
        padnote.material.MaterialCodec.VaultDescriptorSummary vaultDescriptor=padnote.material.MaterialCodec.inspectVaultDescriptor(descriptor);
        padnote.material.MaterialCodec.DescriptorSummary summary=vaultDescriptor.descriptor();
        if(!"vault".equals(summary.kind())||!identity[1].equals(summary.materialId())
                ||!snapshot.lineage.equals(summary.ownerLineageId())||!snapshot.lineage.equals(summary.sourceLineageId())
                ||!"linked_note".equals(summary.sourceState()))throw new IOException("GROUP_VAULT_DESCRIPTOR_IDENTITY_MISMATCH");
        if(summary.contentSize()>maximumBytes)throw new IOException("VAULT_ENTRY_TOO_LARGE");
        byte[] markdown=snapshot.readSmall(prefix+"content.bin",Math.min(maximumBytes,16*1024*1024));
        if(markdown.length!=summary.contentSize()||!shaHex(markdown).equals(summary.contentSha256()))throw new IOException("GROUP_VAULT_CONTENT_DESCRIPTOR_MISMATCH");
        String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(markdown)).toString();}
        catch(Exception invalid){throw new IOException("GROUP_VAULT_UTF8_INVALID",invalid);}
        String head=text.substring(0,Math.min(text.length(),4096));
        if(!identity[0].equals(VaultStore.frontValue(head,"note-id"))||!summary.title().equals(VaultStore.frontValue(head,"title")))throw new IOException("GROUP_VAULT_NOTE_BINDING_MISMATCH");
        try{
            int pages=Integer.parseInt(VaultStore.frontValue(head,"pages"));long created=Long.parseLong(VaultStore.frontValue(head,"digitized-epoch"));long modified=Long.parseLong(VaultStore.frontValue(head,"source-modified"));
            if(pages<0||created<0||modified<0||vaultDescriptor.pageCount()==null||vaultDescriptor.pageCount()!=pages
                    ||!"unix_ms_i64".equals(vaultDescriptor.sourceRevisionRepresentation())
                    ||!"unix_ms_i64".equals(vaultDescriptor.createdAtRepresentation())
                    ||!Long.valueOf(modified).equals(vaultDescriptor.sourceRevisionMillis())
                    ||!Long.valueOf(created).equals(vaultDescriptor.createdAtMillis()))throw new IOException("GROUP_VAULT_TIMESTAMP_INVALID");
        }catch(NumberFormatException invalid){throw new IOException("GROUP_VAULT_TIMESTAMP_INVALID",invalid);}
        return markdown;
    }

    static File vaultProjection(Context context,String fileName)throws Exception {
        String[] identity=parseVirtualVaultName(fileName);if(identity==null)return null;
        StreamingGroupStore.Snapshot snapshot=open(context,identity[0]);if(snapshot==null)return null;
        // Reuse the ordinary reader's full descriptor, owner, UTF-8, timestamp, and byte checks.
        readVirtualVault(context,fileName,16*1024*1024);
        return projectMember(context,snapshot,"vault/"+identity[1]+"/content.bin",16*1024*1024);
    }

    static List<VideoAttachmentStore.Attachment> videoViews(Context context,String localId)throws Exception {
        StreamingGroupStore.Snapshot snapshot=open(context,localId);if(snapshot==null)return null;
        return videoViews(snapshot);
    }

    static List<VideoAttachmentStore.Attachment> videoViews(StreamingGroupStore.Snapshot snapshot)throws Exception {
        if(snapshot==null)throw new IOException("GROUP_SNAPSHOT_REQUIRED");
        String localId=snapshot.localId;
        List<VideoAttachmentStore.Attachment> result=new ArrayList<>();
        for(String member:snapshot.memberSizes().keySet()){
            if(!member.matches("video/[0-9a-f-]{36}/metadata\\.bin"))continue;
            String materialId=member.substring("video/".length(),member.length()-"/metadata.bin".length());
            final padnote.material.MaterialCodec.Video video;
            try{video=padnote.material.MaterialCodec.inspectVideoDescriptor(snapshot.readSmall(member,1<<20));}
            catch(RuntimeException invalid){throw new IOException("GROUP_VIDEO_DESCRIPTOR_INVALID",invalid);}
            boolean linked="linked_note".equals(video.sourceState),independent="independent".equals(video.sourceState);
            boolean detached="source_deleted".equals(video.sourceState)||"source_not_selected".equals(video.sourceState);
            if(!materialId.equals(video.materialId)||!"video/mp4".equals(video.mediaType)
                    ||!"verified_local_copy".equals(video.offlineState)||!(linked||independent||detached)
                    ||linked&&(!snapshot.lineage.equals(video.ownerLineageId)||!snapshot.lineage.equals(video.sourceLineageId)
                            ||!localId.equals(video.sourceNoteId))
                    ||detached&&(!snapshot.lineage.equals(video.ownerLineageId)||video.sourceLineageId!=null)
                    ||independent&&(video.ownerLineageId!=null||video.sourceLineageId!=null))
                throw new IOException("GROUP_VIDEO_DESCRIPTOR_IDENTITY_MISMATCH");
            if(video.createdAt==null||video.createdAt.integerValue()==null
                    ||!"unix_ms_i64".equals(video.createdAt.representation))
                throw new IOException("GROUP_VIDEO_TIMESTAMP_UNSUPPORTED");
            String contentMember="video/"+materialId+"/content.bin";Long size=snapshot.memberSizes().get(contentMember);
            if(size==null||size!=video.byteLength||!snapshot.memberSha256(contentMember).equals(video.contentSha256))
                throw new IOException("GROUP_VIDEO_CONTENT_DESCRIPTOR_MISMATCH");
            VideoAttachmentStore.Attachment attachment=new VideoAttachmentStore.Attachment(materialId,localId,
                    video.sourceRevisionRaw,video.sourceBundleSha256,video.taskPayloadSha256,video.connectionRevision,
                    video.connectionKind,video.transport,video.certificateSha256,video.taskId,video.remoteTaskId,
                    video.connectionId,video.bridgeId,video.instanceId,video.artifactId,video.displayName,
                    video.mediaType,video.byteLength,video.contentSha256,"video-"+materialId+".mp4",
                    video.createdAt.integerValue(),video.originKind,video.sourceState,video.sourceNoteId,
                    video.sourceRevisionPrecisionMs,video.digestKind,video.offlineState);
            result.add(attachment);
        }
        result.sort((a,b)->Long.compare(b.createdAt,a.createdAt));return Collections.unmodifiableList(result);
    }

    static File videoProjection(Context context,VideoAttachmentStore.Attachment expected)throws Exception {
        if(expected==null)throw new IOException("GROUP_VIDEO_EXPECTED_ATTACHMENT_REQUIRED");
        StreamingGroupStore.Snapshot snapshot=open(context,expected.noteId);if(snapshot==null)return null;
        return videoProjection(context,snapshot,expected);
    }

    static File videoProjection(Context context,StreamingGroupStore.Snapshot snapshot,
                                VideoAttachmentStore.Attachment expected)throws Exception {
        if(snapshot==null||expected==null||!snapshot.localId.equals(expected.noteId))
            throw new IOException("GROUP_VIDEO_EXPECTED_ATTACHMENT_REQUIRED");
        List<VideoAttachmentStore.Attachment> current=videoViews(snapshot);VideoAttachmentStore.Attachment match=null;
        for(VideoAttachmentStore.Attachment item:current)if(item.id.equals(expected.id)){match=item;break;}
        if(match==null)throw new IOException("GROUP_VIDEO_NOT_IN_COMMITTED_REVISION");
        if(match.sizeBytes!=expected.sizeBytes||!match.sha256.equalsIgnoreCase(expected.sha256)
                ||match.sourceRevision!=expected.sourceRevision||!match.name.equals(expected.name)
                ||!match.sourceState.equals(expected.sourceState)||!match.sourceNoteId.equals(expected.sourceNoteId))
            throw new IOException("GROUP_VIDEO_VERSION_CHANGED");
        return projectMember(context,snapshot,"video/"+match.id+"/content.bin",100L*1024*1024);
    }

    static String virtualVaultName(String localId,String materialId){
        String encoded=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(localId.getBytes(StandardCharsets.UTF_8));
        return "group-vault-"+encoded+"-"+materialId+".md";
    }
    static boolean isVirtualVaultName(String fileName){return parseVirtualVaultName(fileName)!=null;}
    private static String[] parseVirtualVaultName(String fileName){
        if(fileName==null||!fileName.startsWith("group-vault-")||!fileName.endsWith(".md"))return null;
        String value=fileName.substring(12,fileName.length()-3);int materialStart=value.length()-36;
        if(materialStart<2||value.charAt(materialStart-1)!='-')return null;
        String encoded=value.substring(0,materialStart-1),material=value.substring(materialStart);
        if(encoded.length()>214||!material.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))return null;
        try{
            java.util.UUID parsed=java.util.UUID.fromString(material);if(!parsed.toString().equals(material))return null;
            byte[] raw=java.util.Base64.getUrlDecoder().decode(encoded);
            if(!java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(encoded))return null;
            String local=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString();
            if(!local.matches("[A-Za-z0-9_-]{1,160}"))return null;
            return new String[]{local,material};
        }catch(Exception invalid){return null;}
    }
    private static String shaHex(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}

    static StreamingGroupStore.Snapshot open(Context context,String localId)throws Exception {
        if(Build.VERSION.SDK_INT<27)return null;
        return new NoteGroupFacade(context).openGroup(localId);
    }

    static JSONObject bodyIfManaged(Context context,String localId)throws Exception {
        if(Build.VERSION.SDK_INT<27)return null;
        StreamingGroupStore.Snapshot snapshot=new NoteGroupFacade(context).openGroupIncludingRetired(localId);
        if(snapshot==null)return null;
        byte[] body=snapshot.readSmall("body.bin",(int)StreamingGroupStore.BODY_MAX);
        final JSONObject document;
        try{document=new JSONObject(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(body)).toString());}
        catch(Exception invalid){throw new IOException("GROUP_BODY_INVALID",invalid);}
        if(NoteGroupFacade.isRetiredDocument(document))throw new IOException("GROUP_NOTE_RETIRED");
        return document;
    }

    static File pdfProjectionOrNull(Context context,String localId)throws Exception {
        StreamingGroupStore.Snapshot snapshot=open(context,localId);
        if(snapshot==null)return null;
        return pdfProjectionForSnapshot(context,snapshot);
    }

    static File pdfProjectionForSnapshot(Context context,StreamingGroupStore.Snapshot snapshot)throws Exception {
        if(snapshot==null)throw new IOException("GROUP_SNAPSHOT_REQUIRED");
        if(!snapshot.memberSizes().containsKey("pdf.bin"))return missingProjection(context,snapshot,"pdf.bin");
        return projectMember(context,snapshot,"pdf.bin",PDF_MAX);
    }

    static File bodyProjection(Context context,String localId)throws Exception {
        StreamingGroupStore.Snapshot snapshot=open(context,localId);
        if(snapshot==null)throw new IOException("GROUP_NOTE_MARKER_MISSING");
        return projectMember(context,snapshot,"body.bin",StreamingGroupStore.BODY_MAX);
    }

    static File coverProjectionOrNull(Context context,String localId)throws Exception {
        StreamingGroupStore.Snapshot snapshot=open(context,localId);
        if(snapshot==null)return null;
        return coverProjectionForSnapshot(context,snapshot);
    }

    static File coverProjectionForSnapshot(Context context,StreamingGroupStore.Snapshot snapshot)throws Exception {
        if(snapshot==null)throw new IOException("GROUP_SNAPSHOT_REQUIRED");
        if(!snapshot.memberSizes().containsKey("cover.bin"))return missingProjection(context,snapshot,"cover.bin");
        return projectMember(context,snapshot,"cover.bin",StreamingGroupStore.COVER_MAX);
    }

    static File memberProjectionOrNull(Context context,StreamingGroupStore.Snapshot snapshot,
                                       String member,long maximumBytes)throws Exception {
        if(snapshot==null)throw new IOException("GROUP_SNAPSHOT_REQUIRED");
        if(!snapshot.memberSizes().containsKey(member))return null;
        return projectMember(context,snapshot,member,maximumBytes);
    }

    static void requireLegacyWriteAllowed(Context context,String localId)throws Exception {
        if(Build.VERSION.SDK_INT<27)return;
        if(open(context,localId)!=null)throw new IOException("GROUP_NOTE_REQUIRES_FULL_REVISION_SAVE");
    }

    private static File missingProjection(Context context,StreamingGroupStore.Snapshot snapshot,String member)throws Exception {
        return new File(projectionDirectory(context,snapshot),key(snapshot,member)+".absent");
    }

    private static File projectMember(Context context,StreamingGroupStore.Snapshot snapshot,String member,long cap)throws Exception {
        Map<String,Long> sizes=snapshot.memberSizes();Long size=sizes.get(member);
        if(size==null||size<=0||size>cap)throw new IOException("GROUP_PROJECTION_MEMBER_INVALID");
        File directory=projectionDirectory(context,snapshot),target=new File(directory,key(snapshot,member)+".bin");
        String expected=snapshot.memberSha256(member);
        if(target.exists()) { verifyProjection(target,size,expected); return target; }
        File temporary=File.createTempFile(".group-view-",".tmp",directory);boolean promoted=false;
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;byte[] buffer=new byte[32*1024];
            try(InputStream input=snapshot.openContent(member);FileOutputStream output=new FileOutputStream(temporary)){
                for(int n;(n=input.read(buffer))!=-1;){if(n==0)continue;total=Math.addExact(total,n);if(total>cap||total>size)throw new IOException("GROUP_PROJECTION_SIZE_CHANGED");digest.update(buffer,0,n);output.write(buffer,0,n);}
                output.flush();output.getFD().sync();
            }
            if(total!=size||!hex(digest.digest()).equals(expected))throw new IOException("GROUP_PROJECTION_DIGEST_MISMATCH");
            if(!temporary.renameTo(target)) {
                if(!target.exists())throw new IOException("GROUP_PROJECTION_PROMOTE_FAILED");
                verifyProjection(target,size,expected);
            }
            promoted=true;verifyProjection(target,size,expected);return target;
        } catch(ArithmeticException overflow){throw new IOException("GROUP_PROJECTION_SIZE_OVERFLOW",overflow);}
        finally{if(!promoted&&temporary.exists()&&!temporary.delete())temporary.deleteOnExit();}
    }

    private static File projectionDirectory(Context context,StreamingGroupStore.Snapshot snapshot)throws Exception {
        File cache=context.getCacheDir();if(cache==null)throw new IOException("GROUP_PROJECTION_CACHE_MISSING");
        File root=new File(cache,"group-read-view");
        if(!root.exists()&&!root.mkdir())throw new IOException("GROUP_PROJECTION_DIRECTORY_CREATE");
        StructStat rootStat=Os.lstat(root.getAbsolutePath());if(!OsConstants.S_ISDIR(rootStat.st_mode)||OsConstants.S_ISLNK(rootStat.st_mode)||rootStat.st_uid!=android.os.Process.myUid())throw new IOException("GROUP_PROJECTION_DIRECTORY_UNSAFE");
        File directory=new File(root,key(snapshot,"revision"));
        if(!directory.exists()&&!directory.mkdir())throw new IOException("GROUP_PROJECTION_DIRECTORY_CREATE");
        StructStat directoryStat=Os.lstat(directory.getAbsolutePath());if(!OsConstants.S_ISDIR(directoryStat.st_mode)||OsConstants.S_ISLNK(directoryStat.st_mode)||directoryStat.st_uid!=android.os.Process.myUid())throw new IOException("GROUP_PROJECTION_DIRECTORY_UNSAFE");
        if(!directory.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))throw new IOException("GROUP_PROJECTION_DIRECTORY_SCOPE");
        return directory;
    }

    private static void verifyProjection(File file,long size,String sha)throws Exception {
        StructStat link=Os.lstat(file.getAbsolutePath());if(!OsConstants.S_ISREG(link.st_mode)||OsConstants.S_ISLNK(link.st_mode)||link.st_nlink!=1||link.st_size!=size)throw new IOException("GROUP_PROJECTION_FILE_UNSAFE");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;byte[] buffer=new byte[32*1024];
        try(FileInputStream input=new FileInputStream(file)){for(int n;(n=input.read(buffer))!=-1;){if(n==0)continue;total+=n;if(total>size)throw new IOException("GROUP_PROJECTION_SIZE_CHANGED");digest.update(buffer,0,n);}}
        StructStat after=Os.lstat(file.getAbsolutePath());if(total!=size||after.st_dev!=link.st_dev||after.st_ino!=link.st_ino||after.st_size!=size||after.st_nlink!=1||!hex(digest.digest()).equals(sha))throw new IOException("GROUP_PROJECTION_IDENTITY_CHANGED");
    }

    private static String key(StreamingGroupStore.Snapshot snapshot,String member)throws Exception {
        byte[] input=(snapshot.localId+'\0'+snapshot.lineage+'\0'+snapshot.revision+'\0'+snapshot.digest+'\0'+member).getBytes(StandardCharsets.UTF_8);
        return hex(MessageDigest.getInstance("SHA-256").digest(input));
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
