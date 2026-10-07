package com.padnote.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Transaction-scoped storage for restored Vault entries, offline videos, and user cover presets.
 * It does not edit the note database or grant visibility: callers must supply a durable CommitGate.
 */
final class RestoredLibraryMaterialStore {
    enum Kind { VAULT, VIDEO, COVER_PRESET }

    interface CommitGate {
        boolean isCommitted(CommitProof proof) throws Exception;
    }

    /** A transaction-owned lease that holds the durable group lock and proves this proof is still pending. */
    interface PendingRollbackPermit extends java.io.Closeable {
        boolean stillPending(CommitProof proof) throws Exception;
    }

    /** Must acquire and retain the group's durable transaction lock until the returned permit is closed. */
    interface PendingRollbackGate {
        PendingRollbackPermit acquirePendingGroup(CommitProof proof) throws Exception;
    }

    static final class CommitProof {
        final String transactionId,groupId,localId,payloadSha256,bindingSha256,currentLocalNoteId;
        final Kind kind;
        CommitProof(String tx,String group,String id,Kind kind,String payloadSha,String bindingSha,String currentNote) {
            transactionId=tx;groupId=group;localId=id;this.kind=kind;payloadSha256=payloadSha;bindingSha256=bindingSha;currentLocalNoteId=currentNote;
        }
    }

    interface MediaValidator {
        void validate(Kind kind, File file) throws IOException;
    }

    static final class Record {
        final String transactionId, groupId, localId, sourceItemId, currentLocalNoteId, promotionToken;
        final Kind kind;
        final String sourceState, historicalSourceNoteId;
        final long sourceRevisionMs, createdAtMs, byteLength;
        final int sourceRevisionPrecisionMs;
        final String sha256, mediaType, displayName, digestKind, offlineState;
        final String originKind, sourceOriginKind, sourceBundleSha256, taskPayloadSha256;
        final String taskId, remoteTaskId, connectionId, connectionKind, transport, bridgeId, instanceId;
        final String certificateSha256, artifactId, title;
        final Long connectionRevision;

        Record(String tx, String group, String id, Kind kind, String sourceItemId, String currentNoteId,
                String sourceState, String historicalSourceNoteId, long sourceRevisionMs, int precision,
                long createdAtMs, long byteLength, String sha256, String mediaType, String displayName,
                String digestKind, String offlineState, String originKind, String sourceOriginKind,
                String sourceBundleSha256, String taskPayloadSha256, String taskId, String remoteTaskId,
                String connectionId, Long connectionRevision, String connectionKind, String transport,
                String bridgeId, String instanceId, String certificateSha256, String artifactId, String title) {
            this(tx,group,id,kind,sourceItemId,currentNoteId,sourceState,historicalSourceNoteId,sourceRevisionMs,precision,
                    createdAtMs,byteLength,sha256,mediaType,displayName,digestKind,offlineState,originKind,sourceOriginKind,
                    sourceBundleSha256,taskPayloadSha256,taskId,remoteTaskId,connectionId,connectionRevision,connectionKind,
                    transport,bridgeId,instanceId,certificateSha256,artifactId,title,UUID.randomUUID().toString());
        }
        Record(String tx, String group, String id, Kind kind, String sourceItemId, String currentNoteId,
                String sourceState, String historicalSourceNoteId, long sourceRevisionMs, int precision,
                long createdAtMs, long byteLength, String sha256, String mediaType, String displayName,
                String digestKind, String offlineState, String originKind, String sourceOriginKind,
                String sourceBundleSha256, String taskPayloadSha256, String taskId, String remoteTaskId,
                String connectionId, Long connectionRevision, String connectionKind, String transport,
                String bridgeId, String instanceId, String certificateSha256, String artifactId, String title,String promotionToken) {
            this.transactionId=tx; this.groupId=group; this.localId=id; this.kind=kind;
            this.sourceItemId=sourceItemId; this.currentLocalNoteId=currentNoteId;
            this.promotionToken=promotionToken;
            this.sourceState=sourceState; this.historicalSourceNoteId=historicalSourceNoteId;
            this.sourceRevisionMs=sourceRevisionMs; this.sourceRevisionPrecisionMs=precision;
            this.createdAtMs=createdAtMs; this.byteLength=byteLength; this.sha256=sha256;
            this.mediaType=mediaType; this.displayName=displayName; this.digestKind=digestKind;
            this.offlineState=offlineState; this.originKind=originKind; this.sourceOriginKind=sourceOriginKind;
            this.sourceBundleSha256=sourceBundleSha256; this.taskPayloadSha256=taskPayloadSha256;
            this.taskId=taskId; this.remoteTaskId=remoteTaskId; this.connectionId=connectionId;
            this.connectionRevision=connectionRevision; this.connectionKind=connectionKind; this.transport=transport;
            this.bridgeId=bridgeId; this.instanceId=instanceId; this.certificateSha256=certificateSha256;
            this.artifactId=artifactId; this.title=title;
        }
    }

    static final class Prepared {
        final Record record;
        final File payload, intent;
        private final LibraryBackupArchive.SafeFiles files;
        private Prepared(Record record, File payload, File intent, LibraryBackupArchive.SafeFiles files) {
            this.record=record; this.payload=payload; this.intent=intent; this.files=files;
        }
        CommitProof commitProof() throws IOException { return proofFor(record); }
    }

    static final class ListResult {
        final List<Record> records;
        final Map<String,Integer> diagnostics;
        private ListResult(List<Record> rows,Map<String,Integer> issues) {
            records=Collections.unmodifiableList(new ArrayList<>(rows));
            diagnostics=Collections.unmodifiableMap(new HashMap<>(issues));
        }
    }

    private static final long MAX_VAULT_BYTES=16L*1024*1024;
    private static final long MAX_VIDEO_BYTES=100L*1024*1024;
    private static final long MAX_PNG_BYTES=8L*1024*1024;
    private static final int MAX_RECORD_BYTES=64*1024;
    private final File root, pendingRoot, payloadRoot, recordRoot;
    private final LibraryBackupArchive.SafeFiles files;
    private final MediaValidator media;

    RestoredLibraryMaterialStore(File appFilesDirectory) {
        this(new File(appFilesDirectory,"restored-library-materials"), new AndroidFiles(), new AndroidMediaValidator());
    }

    /** Read gate for backup/export consumers; it trusts only the immutable committed v2 group proof. */
    static CommitGate committedV2Gate(File appFilesDirectory) {
        if(appFilesDirectory==null)throw new IllegalArgumentException("MATERIAL_STORE_CONFIG");
        File transactions=new File(appFilesDirectory,"library-backup-v2/transactions");
        return proof->LibraryRestoreGroupV2.isCommittedMaterialReference(transactions,proof);
    }

    RestoredLibraryMaterialStore(File root, LibraryBackupArchive.SafeFiles files, MediaValidator media) {
        if(root==null||files==null||media==null)throw new IllegalArgumentException("MATERIAL_STORE_CONFIG");
        this.root=root;this.files=files;this.media=media;
        this.pendingRoot=new File(root,"pending");this.payloadRoot=new File(root,"payloads");this.recordRoot=new File(root,"records");
    }

    Prepared prepareVault(String transactionId,String groupId,String localId,String currentLocalNoteId,
            String sourceItemId,LibraryBackupArchive.StagedArchive archive) throws Exception {
        LibraryBackupManifest.VaultEntry descriptor=findVault(archive,sourceItemId);
        return prepareFromArchive(transactionId,groupId,localId,Kind.VAULT,currentLocalNoteId,descriptor,archive);
    }
    CommitProof previewVaultV2(String transactionId,String groupId,String localId,String currentLocalNoteId,
            String sourceItemId,LibraryBackupArchive.StagedArchive archive)throws Exception {
        return previewFromArchiveV2(transactionId,groupId,localId,Kind.VAULT,currentLocalNoteId,findVault(archive,sourceItemId),archive);
    }
    Prepared prepareVaultV2(String transactionId,String groupId,String localId,String currentLocalNoteId,
            String sourceItemId,LibraryBackupArchive.StagedArchive archive)throws Exception {
        return prepareFromArchiveV2(transactionId,groupId,localId,Kind.VAULT,currentLocalNoteId,findVault(archive,sourceItemId),archive);
    }

    Prepared prepareVideo(String transactionId,String groupId,String localId,String currentLocalNoteId,
            String sourceItemId,LibraryBackupArchive.StagedArchive archive) throws Exception {
        LibraryBackupManifest.VideoAttachment descriptor=findVideo(archive,sourceItemId);
        return prepareFromArchive(transactionId,groupId,localId,Kind.VIDEO,currentLocalNoteId,descriptor,archive);
    }
    CommitProof previewVideoV2(String transactionId,String groupId,String localId,String currentLocalNoteId,
            String sourceItemId,LibraryBackupArchive.StagedArchive archive)throws Exception {
        return previewFromArchiveV2(transactionId,groupId,localId,Kind.VIDEO,currentLocalNoteId,findVideo(archive,sourceItemId),archive);
    }
    Prepared prepareVideoV2(String transactionId,String groupId,String localId,String currentLocalNoteId,
            String sourceItemId,LibraryBackupArchive.StagedArchive archive)throws Exception {
        return prepareFromArchiveV2(transactionId,groupId,localId,Kind.VIDEO,currentLocalNoteId,findVideo(archive,sourceItemId),archive);
    }

    Prepared preparePreset(String transactionId,String groupId,String localId,String sourceItemId,
            LibraryBackupArchive.StagedArchive archive) throws Exception {
        LibraryBackupManifest.CoverPreset descriptor=findPreset(archive,sourceItemId);
        return prepareFromArchive(transactionId,groupId,localId,Kind.COVER_PRESET,null,descriptor,archive);
    }
    CommitProof previewPresetV2(String transactionId,String groupId,String localId,String sourceItemId,
            LibraryBackupArchive.StagedArchive archive)throws Exception {
        return previewFromArchiveV2(transactionId,groupId,localId,Kind.COVER_PRESET,null,findPreset(archive,sourceItemId),archive);
    }
    Prepared preparePresetV2(String transactionId,String groupId,String localId,String sourceItemId,
            LibraryBackupArchive.StagedArchive archive)throws Exception {
        return prepareFromArchiveV2(transactionId,groupId,localId,Kind.COVER_PRESET,null,findPreset(archive,sourceItemId),archive);
    }

    private Prepared prepareFromArchive(String transactionId,String groupId,String localId,Kind kind,String currentLocalNoteId,
            Object descriptor,LibraryBackupArchive.StagedArchive archive) throws Exception {
        return prepareFromArchive(transactionId,groupId,localId,kind,currentLocalNoteId,descriptor,archive,null);
    }
    private Prepared prepareFromArchiveV2(String transactionId,String groupId,String localId,Kind kind,String currentLocalNoteId,
            Object descriptor,LibraryBackupArchive.StagedArchive archive)throws Exception {
        return prepareFromArchive(transactionId,groupId,localId,kind,currentLocalNoteId,descriptor,archive,plannedPromotionToken(transactionId,groupId,localId,kind));
    }
    private CommitProof previewFromArchiveV2(String transactionId,String groupId,String localId,Kind kind,String currentLocalNoteId,
            Object descriptor,LibraryBackupArchive.StagedArchive archive)throws Exception {
        requireUuid(transactionId,"MATERIAL_TRANSACTION");requireUuid(groupId,"MATERIAL_GROUP");requireUuid(localId,"MATERIAL_LOCAL_ID");
        String resourceId=descriptorResourceId(descriptor);LibraryBackupManifest.Resource resource=archive.manifest().resourceById(resourceId);
        if(resource==null)throw new IOException("MATERIAL_RESOURCE_BINDING");
        Record record=makeRecord(transactionId,groupId,localId,kind,currentLocalNoteId,descriptor,resource,archive.resourceFile(resourceId));
        return proofFor(withPromotionToken(record,plannedPromotionToken(transactionId,groupId,localId,kind)));
    }
    private Prepared prepareFromArchive(String transactionId,String groupId,String localId,Kind kind,String currentLocalNoteId,
            Object descriptor,LibraryBackupArchive.StagedArchive archive,String plannedToken) throws Exception {
        requireUuid(transactionId,"MATERIAL_TRANSACTION");requireUuid(groupId,"MATERIAL_GROUP");requireUuid(localId,"MATERIAL_LOCAL_ID");
        if(kind==null||descriptor==null||archive==null)throw new IOException("MATERIAL_INPUT_INVALID");
        String resourceId=descriptorResourceId(descriptor);
        LibraryBackupManifest.Resource resource=archive.manifest().resourceById(resourceId);
        if(resource==null)throw new IOException("MATERIAL_RESOURCE_BINDING");
        File stagedResource=archive.resourceFile(resource.resourceId);
        ensureLayout();
        Record record=makeRecord(transactionId,groupId,localId,kind,currentLocalNoteId,descriptor,resource,stagedResource);
        if(plannedToken!=null)record=withPromotionToken(record,plannedToken);
        File dir=pendingDirectory(record.transactionId,record.groupId,true);
        File payload=new File(dir,"payload-"+record.localId+".bin");
        File intent=new File(dir,"intent-"+record.localId+".json");
        boolean intentAbsent=files.isAbsentNoFollow(intent);
        File recordPath=new File(recordRoot,record.localId+".json");
        if(intentAbsent&&!files.isAbsentNoFollow(recordPath)) {
            Record completed=readRecord(recordPath);
            if(!sameRequest(completed,record))throw new IOException("MATERIAL_ID_CONFLICT");
            File completedPayload=new File(payloadRoot,completed.localId+extension(completed.kind));
            verifyFile(completedPayload,completed.byteLength,completed.sha256);
            media.validate(completed.kind,completedPayload);
            File tokenPath=new File(dir,"promotion-"+completed.localId+".json");
            if(!files.isAbsentNoFollow(tokenPath)) {
                String token=reservePromotionToken(dir,completed);
                if(!completed.promotionToken.equals(token))throw new IOException("MATERIAL_TOKEN_CHANGED");
            }
            if(!files.isAbsentNoFollow(payload)) {verifyFile(payload,completed.byteLength,completed.sha256);files.unlink(payload);files.syncDirectory(dir);}
            File staleStage=new File(dir,"payload-"+completed.localId+"-"+completed.promotionToken+".tmp");
            if(!files.isAbsentNoFollow(staleStage)) {verifyFile(staleStage,completed.byteLength,completed.sha256);files.unlink(staleStage);files.syncDirectory(dir);}
            return new Prepared(completed,payload,intent,files);
        }
        record=withPromotionToken(record,reservePromotionToken(dir,record));
        if(intentAbsent&&(!files.isAbsentNoFollow(payload)||!files.isAbsentNoFollow(new File(dir,"payload-"+record.localId+"-"+record.promotionToken+".tmp"))))throw new IOException("MATERIAL_ORPHAN_PAYLOAD");
        if(!intentAbsent) {
            try {
                Record old=readRecord(intent);
                if(!sameBinding(old,withPromotionToken(record,old.promotionToken)))throw new IOException("MATERIAL_ID_CONFLICT");
                record=old;
            } catch(IOException notACompleteRecord) {
                publishCanonicalBytes(intent,encodeRecord(record),record,"material-intent");
                record=readRecord(intent);
            }
        }
        if(files.isAbsentNoFollow(intent))publishCanonicalBytes(intent,encodeRecord(record),record,"material-intent");
        File stage=new File(dir,"payload-"+record.localId+"-"+record.promotionToken+".tmp");
        if(!files.isAbsentNoFollow(payload)) {
            verifyFile(payload,record.byteLength,record.sha256);media.validate(kind,payload);
            return new Prepared(record,payload,intent,files);
        }
        if(files.isAbsentNoFollow(stage))copyVerified(stagedResource,stage,record.byteLength,record.sha256,limit(kind));
        else verifyFile(stage,record.byteLength,record.sha256);
        media.validate(kind,stage);
        publishOwnedFile(stage,payload,record.byteLength,record.sha256,record,"material-pending-payload");
        files.syncDirectory(dir);
        return new Prepared(record,payload,intent,files);
    }

    private static LibraryBackupManifest.VaultEntry findVault(LibraryBackupArchive.StagedArchive archive,String itemId)throws IOException {
        if(archive==null||itemId==null)throw new IOException("MATERIAL_INPUT_INVALID");
        for(LibraryBackupManifest.VaultEntry row:archive.manifest().vaultEntries)if(row.itemId.equals(itemId))return row;
        throw new IOException("MATERIAL_ITEM_NOT_FOUND");
    }
    private static LibraryBackupManifest.VideoAttachment findVideo(LibraryBackupArchive.StagedArchive archive,String itemId)throws IOException {
        if(archive==null||itemId==null)throw new IOException("MATERIAL_INPUT_INVALID");
        for(LibraryBackupManifest.VideoAttachment row:archive.manifest().videoAttachments)if(row.itemId.equals(itemId))return row;
        throw new IOException("MATERIAL_ITEM_NOT_FOUND");
    }
    private static LibraryBackupManifest.CoverPreset findPreset(LibraryBackupArchive.StagedArchive archive,String itemId)throws IOException {
        if(archive==null||itemId==null)throw new IOException("MATERIAL_INPUT_INVALID");
        for(LibraryBackupManifest.CoverPreset row:archive.manifest().coverPresets)if(row.itemId.equals(itemId))return row;
        throw new IOException("MATERIAL_ITEM_NOT_FOUND");
    }
    private static String descriptorResourceId(Object descriptor)throws IOException {
        if(descriptor instanceof LibraryBackupManifest.VaultEntry)return ((LibraryBackupManifest.VaultEntry)descriptor).resourceId;
        if(descriptor instanceof LibraryBackupManifest.VideoAttachment)return ((LibraryBackupManifest.VideoAttachment)descriptor).resourceId;
        if(descriptor instanceof LibraryBackupManifest.CoverPreset)return ((LibraryBackupManifest.CoverPreset)descriptor).resourceId;
        throw new IOException("MATERIAL_DESCRIPTOR_KIND");
    }

    /** Writes the durable ownership record before exposing the final payload path. */
    synchronized Record promote(Prepared prepared) throws Exception {
        if(prepared==null||prepared.files!=files)throw new IOException("MATERIAL_PREPARED_INVALID");
        Record expected=prepared.record;
        ensureLayout();
        File intentPath=new File(pendingDirectory(expected.transactionId,expected.groupId,false),"intent-"+expected.localId+".json");
        File pendingPayload=new File(intentPath.getParentFile(),"payload-"+expected.localId+".bin");
        if(!intentPath.equals(prepared.intent)||!pendingPayload.equals(prepared.payload))throw new IOException("MATERIAL_PREPARED_INVALID");
        File recordPath=new File(recordRoot,expected.localId+".json");
        File finalPayload=new File(payloadRoot,expected.localId+extension(expected.kind));
        if(files.isAbsentNoFollow(intentPath)) {
            if(!exists(recordPath))throw new IOException("MATERIAL_INTENT_MISSING");
            Record already=readRecord(recordPath);
            if(!sameBinding(already,expected))throw new IOException("MATERIAL_ID_CONFLICT");
            verifyFile(finalPayload,already.byteLength,already.sha256);
            media.validate(already.kind,finalPayload);
            return already;
        }
        Record intent;
        try { intent=readRecord(intentPath); }
        catch(IOException notRecord) {
            File tokenPath=new File(intentPath.getParentFile(),"promotion-"+expected.localId+".json");
            if(files.isAbsentNoFollow(tokenPath))throw notRecord;
            if(!sameBinding(expected,withPromotionToken(expected,reservePromotionToken(intentPath.getParentFile(),expected))))throw new IOException("MATERIAL_INTENT_MARKER_OWNER");
            publishCanonicalBytes(intentPath,encodeRecord(expected),expected,"material-intent");
            intent=readRecord(intentPath);
        }
        if(!sameBinding(intent,expected))throw new IOException("MATERIAL_INTENT_MISMATCH");
        if(files.isAbsentNoFollow(recordPath))publishCanonicalBytes(recordPath,encodeRecord(intent),intent,"material-record");
        else {
            try { if(!sameBinding(readRecord(recordPath),intent))throw new IOException("MATERIAL_ID_CONFLICT"); }
            catch(IOException markerOrCorrupt) { publishCanonicalBytes(recordPath,encodeRecord(intent),intent,"material-record"); }
        }
        File publishStage=new File(payloadRoot,"promote-"+intent.localId+"-"+intent.promotionToken+".tmp");
        boolean finalPayloadVerified=false;
        if(!files.isAbsentNoFollow(finalPayload)) {
            try { verifyFile(finalPayload,intent.byteLength,intent.sha256); finalPayloadVerified=true; }
            catch(IOException notPublishedPayload) { /* publishOwnedFile will accept only our exact marker. */ }
        }
        if(finalPayloadVerified) {
            media.validate(intent.kind,finalPayload);
            if(!files.isAbsentNoFollow(pendingPayload)) {verifyFile(pendingPayload,intent.byteLength,intent.sha256);files.unlink(pendingPayload);files.syncDirectory(intentPath.getParentFile());}
            if(!files.isAbsentNoFollow(publishStage)) {verifyFile(publishStage,intent.byteLength,intent.sha256);files.unlink(publishStage);files.syncDirectory(payloadRoot);}
            files.syncDirectory(payloadRoot);
            files.unlink(intentPath);
            File tokenPath=new File(intentPath.getParentFile(),"promotion-"+intent.localId+".json");
            if(!files.isAbsentNoFollow(tokenPath)) {String token=reservePromotionToken(intentPath.getParentFile(),intent);if(!intent.promotionToken.equals(token))throw new IOException("MATERIAL_TOKEN_CHANGED");files.unlink(tokenPath);}
            files.syncDirectory(intentPath.getParentFile());
            return intent;
        }
        if(!files.isAbsentNoFollow(pendingPayload)) {
            verifyFile(pendingPayload,intent.byteLength,intent.sha256);media.validate(intent.kind,pendingPayload);
            if(files.isAbsentNoFollow(publishStage))copyVerified(pendingPayload,publishStage,intent.byteLength,intent.sha256,limit(intent.kind));
            else verifyFile(publishStage,intent.byteLength,intent.sha256);
            media.validate(intent.kind,publishStage);
            files.unlink(pendingPayload);files.syncDirectory(intentPath.getParentFile());
        } else if(files.isAbsentNoFollow(publishStage)&&files.isAbsentNoFollow(finalPayload)) {
            throw new IOException("MATERIAL_PROMOTION_STATE_INVALID");
        }
        if(files.isAbsentNoFollow(finalPayload)||!files.isAbsentNoFollow(publishStage)) {
            publishOwnedFile(publishStage,finalPayload,intent.byteLength,intent.sha256,intent,"material-final-payload");
        } else verifyFile(finalPayload,intent.byteLength,intent.sha256);
        verifyFile(finalPayload,intent.byteLength,intent.sha256);
        media.validate(intent.kind,finalPayload);
        files.syncDirectory(payloadRoot);
        files.unlink(intentPath);
        File tokenPath=new File(intentPath.getParentFile(),"promotion-"+intent.localId+".json");
        if(!files.isAbsentNoFollow(tokenPath)) {String token=reservePromotionToken(intentPath.getParentFile(),intent);if(!intent.promotionToken.equals(token))throw new IOException("MATERIAL_TOKEN_CHANGED");files.unlink(tokenPath);}
        files.syncDirectory(intentPath.getParentFile());
        return intent;
    }

    /** Rolls back only a full-proof row while the caller-held durable pending-group lease remains valid. */
    synchronized void rollback(CommitProof expected,CommitGate gate,PendingRollbackGate rollbackGate) throws Exception {
        if(expected==null)throw new IOException("MATERIAL_ROLLBACK_PROOF_REQUIRED");
        requireUuid(expected.transactionId,"MATERIAL_TRANSACTION");requireUuid(expected.groupId,"MATERIAL_GROUP");requireUuid(expected.localId,"MATERIAL_LOCAL_ID");
        if(expected.kind==null||!isHash(expected.payloadSha256)||!isHash(expected.bindingSha256))throw new IOException("MATERIAL_ROLLBACK_PROOF_INVALID");
        if(gate==null)throw new IOException("MATERIAL_COMMIT_GATE_REQUIRED");
        if(rollbackGate==null)throw new IOException("MATERIAL_ROLLBACK_AUTHORITY_REQUIRED");
        PendingRollbackPermit permit=rollbackGate.acquirePendingGroup(expected);
        if(permit==null)throw new IOException("MATERIAL_ROLLBACK_AUTHORITY_REQUIRED");
        try(PendingRollbackPermit held=permit) {
        if(!held.stillPending(expected))throw new IOException("MATERIAL_ROLLBACK_NOT_PENDING");
        if(gate.isCommitted(expected))throw new IOException("MATERIAL_ALREADY_COMMITTED");
        ensureLayout();
        String transactionId=expected.transactionId,groupId=expected.groupId,localId=expected.localId;
        File recordPath=new File(recordRoot,localId+".json");
        Record row=null;
        File txDir=new File(pendingRoot,transactionId),dir=new File(txDir,groupId);
        File intentPath=new File(dir,"intent-"+localId+".json");
        boolean recordFull=false,intentFull=false,recordMalformed=false,intentMalformed=false;
        boolean recordPresent;
        try { recordPresent=exists(recordPath); }
        catch(IOException unavailable) { throw new IOException("MATERIAL_ROLLBACK_RECOVERY_NEEDED"); }
        if(recordPresent) {
            try { row=readRecord(recordPath);recordFull=true; }
            catch(IOException malformed) { recordMalformed=true; }
        }
        boolean groupDirectoryPresent=!files.isAbsentNoFollow(txDir)&&!files.isAbsentNoFollow(dir);
        if(groupDirectoryPresent)files.validatePrivateDirectory(dir);
        boolean intentPresent=false;
        if(groupDirectoryPresent) {
            try { intentPresent=exists(intentPath); }
            catch(IOException unavailable) { throw new IOException("MATERIAL_ROLLBACK_RECOVERY_NEEDED"); }
            if(intentPresent) {
                Record intentRow;
                try { intentRow=readRecord(intentPath); }
                catch(IOException malformed) { intentMalformed=true;intentRow=null; }
                if(intentRow!=null) {
                    intentFull=true;
                    if(row!=null&&!sameBinding(row,intentRow))throw new IOException("MATERIAL_ROLLBACK_OWNERSHIP_MISMATCH");
                    if(row==null)row=intentRow;
                }
            }
        }
        if(row==null) {
            if(recordPresent||intentPresent||recordMalformed||intentMalformed)throw new IOException("MATERIAL_ROLLBACK_RECOVERY_NEEDED");
            final boolean residue;
            try { residue=hasRollbackResidue(transactionId,groupId,localId,txDir,dir,recordPath,intentPath); }
            catch(IOException unavailable) { throw new IOException("MATERIAL_ROLLBACK_RECOVERY_NEEDED"); }
            if(residue)throw new IOException("MATERIAL_ROLLBACK_RECOVERY_NEEDED");
            return;
        }
        if(!row.transactionId.equals(transactionId)||!row.groupId.equals(groupId)||!row.localId.equals(localId)||
                !sameProof(proofFor(row),expected))throw new IOException("MATERIAL_ROLLBACK_OWNERSHIP_MISMATCH");
        if(!held.stillPending(expected))throw new IOException("MATERIAL_ROLLBACK_NOT_PENDING");
        if(gate.isCommitted(expected))throw new IOException("MATERIAL_ALREADY_COMMITTED");
        File finalPayload=new File(payloadRoot,localId+extension(row.kind));
        File pendingPayload=new File(intentPath.getParentFile(),"payload-"+localId+".bin");
        File pendingStage=new File(intentPath.getParentFile(),"payload-"+localId+"-"+row.promotionToken+".tmp");
        File promoteStage=new File(payloadRoot,"promote-"+localId+"-"+row.promotionToken+".tmp");
        File tokenPath=new File(intentPath.getParentFile(),"promotion-"+localId+".json");
        File recordStage=new File(recordRoot,".stage-"+row.promotionToken+"-"+recordPath.getName()+".tmp");
        File intentStage=new File(intentPath.getParentFile(),".stage-"+row.promotionToken+"-"+intentPath.getName()+".tmp");

        // Validate every known object before deleting any of them. An unknown object preserves the full recovery state.
        List<RollbackDelete> payloadDeletes=new ArrayList<>();
        addPayloadDelete(payloadDeletes,finalPayload,row,"material-final-payload",true);
        addPayloadDelete(payloadDeletes,pendingPayload,row,"material-pending-payload",true);
        addPayloadDelete(payloadDeletes,pendingStage,row,null,false);
        addPayloadDelete(payloadDeletes,promoteStage,row,null,false);
        List<RollbackDelete> metadataDeletes=new ArrayList<>();
        addCanonicalDelete(metadataDeletes,recordStage,row);
        addCanonicalDelete(metadataDeletes,intentStage,row);
        addTokenDelete(metadataDeletes,tokenPath,row);
        addCanonicalOrMarkerDelete(metadataDeletes,recordPath,row,"material-record");
        addCanonicalOrMarkerDelete(metadataDeletes,intentPath,row,"material-intent");
        if(!recordFull&&!intentFull)throw new IOException("MATERIAL_ROLLBACK_ROW_UNVERIFIED");
        File authorityPath=intentFull?intentPath:recordPath;
        for(int i=0;i<metadataDeletes.size();i++)if(metadataDeletes.get(i).path.equals(authorityPath)) {
            RollbackDelete authority=metadataDeletes.remove(i);metadataDeletes.add(authority);break;
        }

        if(!held.stillPending(expected)||gate.isCommitted(expected))throw new IOException("MATERIAL_ROLLBACK_NOT_PENDING");
        for(RollbackDelete item:payloadDeletes)deleteRollbackItem(item,row);
        files.syncDirectory(payloadRoot);files.syncDirectory(dir);
        for(int i=0;i<metadataDeletes.size();i++) {
            RollbackDelete item=metadataDeletes.get(i);
            // Keep at least one complete record/intent as restart authority until every other owned item is gone.
            if(i==metadataDeletes.size()-1)continue;
            deleteRollbackItem(item,row);
        }
        files.syncDirectory(recordRoot);files.syncDirectory(dir);
        if(!held.stillPending(expected)||gate.isCommitted(expected))throw new IOException("MATERIAL_ROLLBACK_NOT_PENDING");
        if(!metadataDeletes.isEmpty())deleteRollbackItem(metadataDeletes.get(metadataDeletes.size()-1),row);
        files.syncDirectory(recordRoot);files.syncDirectory(dir);
        }
    }

    /** No-record rollback is idempotent only when every namespace for this exact local ID is absent. */
    private boolean hasRollbackResidue(String tx,String group,String localId,File txDir,File groupDir,
            File recordPath,File intentPath)throws IOException {
        File[] direct={recordPath,intentPath,new File(payloadRoot,localId+".json"),new File(payloadRoot,localId+".mp4"),new File(payloadRoot,localId+".png")};
        File[] roots={recordRoot,pendingRoot,payloadRoot,payloadRoot,payloadRoot};
        for(int i=0;i<direct.length;i++)if(!isAbsentWithinOwnedRoot(direct[i],roots[i]))return true;
        if(!isAbsentWithinOwnedRoot(groupDir,pendingRoot)) {
            files.validatePrivateDirectory(groupDir);
            File[] children=groupDir.listFiles();
            if(children==null)throw new IOException("MATERIAL_ROLLBACK_RECOVERY_NEEDED");
            for(File child:children) {
                String name=child.getName();
                if(name.contains(localId))return true;
            }
        }
        if(!isAbsentWithinOwnedRoot(txDir,pendingRoot)) {
            files.validatePrivateDirectory(txDir);
            File[] groups=txDir.listFiles();
            if(groups==null)throw new IOException("MATERIAL_ROLLBACK_RECOVERY_NEEDED");
        }
        File[] payloads=payloadRoot.listFiles();
        if(payloads==null)throw new IOException("MATERIAL_ROLLBACK_RECOVERY_NEEDED");
        for(File child:payloads)if(child.getName().contains(localId))return true;
        File[] records=recordRoot.listFiles();
        if(records==null)throw new IOException("MATERIAL_ROLLBACK_RECOVERY_NEEDED");
        for(File child:records)if(child.getName().contains(localId))return true;
        return false;
    }

    /** Missing descendants are absent only when each existing ancestor below this owned root is safe. */
    private boolean isAbsentWithinOwnedRoot(File path,File ownedRoot)throws IOException {
        List<String> relative;
        try { relative=AndroidFileCompat.descendantComponents(ownedRoot,path); }
        catch(IOException e) { throw new IOException("MATERIAL_PATH_UNSAFE"); }
        files.validatePrivateDirectory(ownedRoot);
        File cursor=new File(AndroidFileCompat.normalizeAbsolute(ownedRoot));
        for(int i=0;i<relative.size();i++) {
            cursor=new File(cursor,relative.get(i));
            if(files.isAbsentNoFollow(cursor))return true;
            if(i<relative.size()-1)files.validatePrivateDirectory(cursor);
        }
        return false;
    }

    /** Verifies a promoted material row and bytes for a group-journal commit, without changing visibility. */
    synchronized boolean verifyPromotedProof(CommitProof proof) throws IOException {
        if(proof==null||!isUuid(proof.transactionId)||!isUuid(proof.groupId)||!isUuid(proof.localId)||
                proof.kind==null||!isHash(proof.payloadSha256)||!isHash(proof.bindingSha256))
            throw new IOException("MATERIAL_PROOF_INVALID");
        ensureLayout();
        Record row=readRecord(new File(recordRoot,proof.localId+".json"));
        if(!sameProof(proofFor(row),proof))throw new IOException("MATERIAL_PROOF_MISMATCH");
        File payload=finalPayload(row);verifyFile(payload,row.byteLength,row.sha256);media.validate(row.kind,payload);
        return true;
    }

    /** True only when this proof's complete local-ID namespace is absent; unknown state is an error. */
    synchronized boolean isProofNamespaceAbsent(CommitProof proof) throws IOException {
        if(proof==null||!isUuid(proof.transactionId)||!isUuid(proof.groupId)||!isUuid(proof.localId)||
                proof.kind==null||!isHash(proof.payloadSha256)||!isHash(proof.bindingSha256))
            throw new IOException("MATERIAL_PROOF_INVALID");
        ensureLayout();
        File txDir=new File(pendingRoot,proof.transactionId),groupDir=new File(txDir,proof.groupId);
        File record=new File(recordRoot,proof.localId+".json"),intent=new File(groupDir,"intent-"+proof.localId+".json");
        return !hasRollbackResidue(proof.transactionId,proof.groupId,proof.localId,txDir,groupDir,record,intent);
    }

    /** Checks a planned but not yet prepared member; any unreadable or matching residue is recovery-needed. */
    synchronized boolean isMemberNamespaceAbsent(String transactionId,String groupId,String localId,Kind kind) throws IOException {
        if(!isUuid(transactionId)||!isUuid(groupId)||!isUuid(localId)||kind==null)throw new IOException("MATERIAL_PROOF_INVALID");
        ensureLayout();
        File txDir=new File(pendingRoot,transactionId),groupDir=new File(txDir,groupId);
        File record=new File(recordRoot,localId+".json"),intent=new File(groupDir,"intent-"+localId+".json");
        return !hasRollbackResidue(transactionId,groupId,localId,txDir,groupDir,record,intent);
    }

    private static final class RollbackDelete {
        final File path;final LibraryBackupArchive.FileIdentity identity;final byte[] exactBytes;
        final boolean payload;final long size;final String sha;
        RollbackDelete(File path,LibraryBackupArchive.FileIdentity identity,byte[] exactBytes,boolean payload,long size,String sha) {
            this.path=path;this.identity=identity;this.exactBytes=exactBytes;this.payload=payload;this.size=size;this.sha=sha;
        }
    }
    private void addPayloadDelete(List<RollbackDelete> plan,File path,Record row,String markerPurpose,boolean allowMarker)throws IOException {
        if(files.isAbsentNoFollow(path))return;
        try {verifyFile(path,row.byteLength,row.sha256);plan.add(new RollbackDelete(path,files.inspect(path,false),null,true,row.byteLength,row.sha256));return;}
        catch(IOException notPayload) {
            if(!allowMarker||markerPurpose==null)throw notPayload;
        }
        byte[] marker=publishMarker(path,row.byteLength,row.sha256,row,markerPurpose);
        LibraryBackupArchive.FileIdentity identity=files.inspect(path,false);
        if(!isExactMarker(path,marker,identity))throw new IOException("MATERIAL_ROLLBACK_OBJECT_UNKNOWN");
        plan.add(new RollbackDelete(path,identity,marker,false,marker.length,hex(digest().digest(marker))));
    }
    private void addCanonicalDelete(List<RollbackDelete> plan,File path,Record row)throws IOException {
        if(files.isAbsentNoFollow(path))return;
        addExactBytesDelete(plan,path,encodeRecord(row));
    }
    private void addTokenDelete(List<RollbackDelete> plan,File path,Record row)throws IOException {
        if(files.isAbsentNoFollow(path))return;
        Map<String,Object> m=new java.util.TreeMap<>();m.put("schema_version",1);m.put("transaction_id",row.transactionId);m.put("group_id",row.groupId);
        m.put("local_id",row.localId);m.put("kind",row.kind.name());m.put("promotion_token",row.promotionToken);
        addExactBytesDelete(plan,path,LibraryBackupJson.encode(m));
    }
    private void addCanonicalOrMarkerDelete(List<RollbackDelete> plan,File path,Record row,String purpose)throws IOException {
        if(files.isAbsentNoFollow(path))return;
        byte[] canonical=encodeRecord(row);
        try {addExactBytesDelete(plan,path,canonical);return;}
        catch(IOException notCanonical) {
            byte[] marker=publishMarker(path,canonical.length,hex(digest().digest(canonical)),row,purpose);
            LibraryBackupArchive.FileIdentity identity=files.inspect(path,false);
            if(!isExactMarker(path,marker,identity))throw new IOException("MATERIAL_ROLLBACK_OBJECT_UNKNOWN");
            plan.add(new RollbackDelete(path,identity,marker,false,marker.length,hex(digest().digest(marker))));
        }
    }
    private void addExactBytesDelete(List<RollbackDelete> plan,File path,byte[] expected)throws IOException {
        LibraryBackupArchive.FileIdentity identity=files.inspect(path,false);
        if(identity.links!=1||identity.size!=expected.length)throw new IOException("MATERIAL_ROLLBACK_OBJECT_UNKNOWN");
        byte[] actual=readBounded(path,expected.length);
        if(!java.util.Arrays.equals(actual,expected)||!identity.equals(files.inspect(path,false)))throw new IOException("MATERIAL_ROLLBACK_OBJECT_UNKNOWN");
        plan.add(new RollbackDelete(path,identity,expected.clone(),false,expected.length,hex(digest().digest(expected))));
    }
    private void deleteRollbackItem(RollbackDelete item,Record row)throws IOException {
        LibraryBackupArchive.FileIdentity before=files.inspect(item.path,false);
        if(!before.equals(item.identity))throw new IOException("MATERIAL_ROLLBACK_OBJECT_CHANGED");
        if(item.payload)verifyFile(item.path,item.size,item.sha);
        else if(before.links!=1||before.size!=item.exactBytes.length||
                !java.util.Arrays.equals(readBounded(item.path,item.exactBytes.length),item.exactBytes)||
                !before.equals(files.inspect(item.path,false)))throw new IOException("MATERIAL_ROLLBACK_OBJECT_CHANGED");
        files.unlink(item.path);
    }

    /** Only committed rows are returned; no default or implicit approval path exists. */
    synchronized List<Record> list(CommitGate gate) throws Exception {
        return listDetailed(gate).records;
    }

    /** Returns independently validated visible rows while retaining path-free fixed-code diagnostics for bad rows. */
    synchronized ListResult listDetailed(CommitGate gate) throws Exception {
        requireGate(gate);ensureLayout();File[] children=recordRoot.listFiles();
        if(children==null)throw new IOException("MATERIAL_RECORDS_UNREADABLE");
        java.util.Arrays.sort(children,(a,b)->a.getName().compareTo(b.getName()));
        Set<String> expectedLockNames=new HashSet<>();
        for(File child:children)if(child.getName().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")){
            try{Record candidate=readRecord(child);if(child.getName().equals(candidate.localId+".json"))expectedLockNames.add(publishLockArtifactName(child.getName()));}
            catch(IOException invalidRecord){/* The normal pass below reports the fixed diagnostic. */}
        }
        List<Record> result=new ArrayList<>();Map<String,Integer> diagnostics=new HashMap<>();
        for(File child:children) {
            if(!child.getName().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")) {
                if(expectedLockNames.contains(child.getName()))try{if(files.isPersistentPublishLockArtifact(child))continue;}catch(IOException invalidLock){addDiagnostic(diagnostics,"record_name_invalid");continue;}
                addDiagnostic(diagnostics,"record_name_invalid"); continue;
            }
            Record record;
            try { record=readRecord(child); if(!child.getName().equals(record.localId+".json"))throw new IOException("MATERIAL_RECORD_INVALID"); }
            catch(IOException invalid) { addDiagnostic(diagnostics,diagnosticCode(invalid)); continue; }
            // Journal failures are not corrupt-row diagnostics: fail closed rather than showing partial authorization.
            if(!gate.isCommitted(proofFor(record)))continue;
            try { File content=finalPayload(record);verifyFile(content,record.byteLength,record.sha256);media.validate(record.kind,content);result.add(record); }
            catch(IOException invalid) { addDiagnostic(diagnostics,diagnosticCode(invalid)); }
        }
        return new ListResult(result,diagnostics);
    }

    private static String publishLockArtifactName(String destinationName){return ".padnote-publish-"+hex(digest().digest(destinationName.getBytes(StandardCharsets.UTF_8)))+".lock";}

    private static void addDiagnostic(Map<String,Integer> diagnostics,String code) {diagnostics.put(code,diagnostics.containsKey(code)?diagnostics.get(code)+1:1);}
    private static String diagnosticCode(IOException error) {
        String code=error.getMessage();
        if(code!=null&&java.util.Arrays.asList("MATERIAL_RECORD_INVALID","MATERIAL_RECORD_FIELDS","MATERIAL_FILE_INVALID",
                "MATERIAL_FILE_CHANGED","MATERIAL_FILE_UNAVAILABLE","MATERIAL_FILE_UNSAFE","MATERIAL_SIZE_LIMIT",
                "MATERIAL_VIDEO_UNPLAYABLE","MATERIAL_PNG_INVALID","MATERIAL_RECORD_STRING","MATERIAL_RECORD_SCHEMA",
                "MATERIAL_KIND","MATERIAL_LENGTH","MATERIAL_SHA_INVALID").contains(code))return code.toLowerCase(Locale.ROOT);
        return "material_row_invalid";
    }

    synchronized byte[] readVault(String localId,CommitGate gate) throws Exception {
        Record row=authorized(localId,gate);
        if(row.kind!=Kind.VAULT||row.byteLength>MAX_VAULT_BYTES)throw new IOException("MATERIAL_KIND_MISMATCH");
        File payload=finalPayload(row);verifyFile(payload,row.byteLength,row.sha256);
        byte[] bytes=readBounded(payload,MAX_VAULT_BYTES);
        validateVaultPayload(row,bytes);
        return bytes;
    }

    /** Writes a verified committed media copy to a new caller-selected file without replacing it. */
    synchronized void copyForShare(String localId,File destination,CommitGate gate) throws Exception {
        Record row=authorized(localId,gate);
        if(row.kind==Kind.VAULT)throw new IOException("MATERIAL_KIND_MISMATCH");
        if(destination==null||exists(destination))throw new IOException("MATERIAL_SHARE_DESTINATION_EXISTS");
        File parent=destination.getAbsoluteFile().getParentFile();files.validatePrivateDirectory(parent);
        File temp=new File(parent,".material-share-"+UUID.randomUUID()+".part");
        Exception failure=null;
        try {
            copyVerified(finalPayload(row),temp,row.byteLength,row.sha256,limit(row.kind));
            media.validate(row.kind,temp);
            publishOwnedFile(temp,destination,row.byteLength,row.sha256,row,"material-share");
            verifyFile(destination,row.byteLength,row.sha256);
        } catch(Exception original) {
            failure=original;
            throw original;
        } finally {
            try {
                if(!files.isAbsentNoFollow(temp))files.unlink(temp);
            } catch(Exception cleanup) {
                IOException safe=new IOException("MATERIAL_SHARE_TEMP_CLEANUP_FAILED");
                if(failure!=null)failure.addSuppressed(safe);
                else throw safe;
            }
        }
    }

    private Record authorized(String localId,CommitGate gate)throws Exception {
        requireGate(gate);if(localId==null||!isUuid(localId))throw new IOException("MATERIAL_LOCAL_ID");
        File recordPath=new File(recordRoot,localId+".json");Record row=readRecord(recordPath);
        if(!gate.isCommitted(proofFor(row)))throw new IOException("MATERIAL_NOT_COMMITTED");
        verifyFile(finalPayload(row),row.byteLength,row.sha256);return row;
    }

    private Record makeRecord(String tx,String group,String id,Kind kind,String currentNote,Object descriptor,
            LibraryBackupManifest.Resource resource,File file)throws Exception {
        String sourceItem,sourceState=null,historical=null,display=null,digestKind=null,offline=null,origin=null,sourceOrigin=null;
        String sourceBundle=null,taskPayload=null,taskId=null,remoteTaskId=null,connectionId=null,connectionKind=null,transport=null,bridge=null,instance=null,cert=null,artifact=null,title=null,mediaType;
        Long connectionRevision=null;long revision=0,created=0,size;int precision=1;
        String expectedRole=kind==Kind.VAULT?"vault_entry_json":kind==Kind.VIDEO?"video_attachment_mp4":"user_cover_preset_png";
        String expectedMedia=kind==Kind.VAULT?"application/json":kind==Kind.VIDEO?"video/mp4":"image/png";
        if(!expectedRole.equals(resource.role)||!expectedMedia.equals(resource.mediaType)||!isHash(resource.sha256)||
                resource.byteLength<=0||resource.byteLength>limit(kind))throw new IOException("MATERIAL_RESOURCE_INVALID");
        if(kind==Kind.VAULT) {
            if(!(descriptor instanceof LibraryBackupManifest.VaultEntry))throw new IOException("MATERIAL_DESCRIPTOR_KIND");
            LibraryBackupManifest.VaultEntry d=(LibraryBackupManifest.VaultEntry)descriptor;
            if(!d.resourceId.equals(resource.resourceId))throw new IOException("MATERIAL_RESOURCE_BINDING");
            sourceItem=d.itemId;sourceState=d.sourceState;historical=d.sourceNoteId;revision=d.sourceRevisionMs;created=d.createdAtMs;
            if(!associationValid(sourceState,d.noteItemId,currentNote))throw new IOException("MATERIAL_ASSOCIATION_INVALID");
            size=resource.byteLength;mediaType="application/json";
            if(size<=0||size>MAX_VAULT_BYTES)throw new IOException("MATERIAL_SIZE_LIMIT");
            String sha=resource.sha256;verifyFile(file,size,sha);
            Record candidate=new Record(tx,group,id,kind,sourceItem,currentNote,sourceState,historical,revision,1,created,size,sha,mediaType,
                    null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
            byte[] payload=readBounded(file,MAX_VAULT_BYTES);validateVaultPayload(candidate,payload);
            title=vaultTitle(payload);
            return withTitle(candidate,title);
        } else if(kind==Kind.VIDEO) {
            if(!(descriptor instanceof LibraryBackupManifest.VideoAttachment))throw new IOException("MATERIAL_DESCRIPTOR_KIND");
            LibraryBackupManifest.VideoAttachment d=(LibraryBackupManifest.VideoAttachment)descriptor;
            if(!d.resourceId.equals(resource.resourceId)||d.byteLength!=resource.byteLength||!d.sha256.equals(resource.sha256))throw new IOException("MATERIAL_RESOURCE_BINDING");
            sourceItem=d.itemId;sourceState=d.sourceState;historical=d.sourceNoteId;revision=d.sourceRevisionMs;created=d.createdAtMs;
            precision=d.sourceRevisionPrecisionMs;digestKind=d.digestKind;offline=d.offlineState;sourceOrigin=d.originKind;
            origin="restored_archive";sourceBundle=d.sourceBundleSha256;taskPayload=d.taskPayloadSha256;taskId=d.taskId;remoteTaskId=d.remoteTaskId;
            connectionId=d.connectionProvenance==null?null:d.connectionProvenance.connectionId;
            connectionRevision=d.connectionProvenance==null?null:d.connectionProvenance.connectionRevision;
            connectionKind=d.connectionProvenance==null?null:d.connectionProvenance.kind;
            transport=d.connectionProvenance==null?null:d.connectionProvenance.transport;
            bridge=d.connectionProvenance==null?null:d.connectionProvenance.bridgeId;instance=d.connectionProvenance==null?null:d.connectionProvenance.instanceId;
            cert=d.connectionProvenance==null?null:d.connectionProvenance.certificateSha256;artifact=d.artifactId;display=d.displayName;mediaType=d.mediaType;
            if(!associationValid(sourceState,d.noteItemId,currentNote)||!"video/mp4".equals(mediaType)||
                    !"verified_local_copy".equals(offline)||!"restored_archive".equals(origin)||
                    !(precision==1||precision==1000)||(precision==1000&&revision%1000!=0))throw new IOException("MATERIAL_VIDEO_INVALID");
            size=d.byteLength;if(size<=0||size>MAX_VIDEO_BYTES)throw new IOException("MATERIAL_SIZE_LIMIT");
            String sha=d.sha256;if(!isHash(sha))throw new IOException("MATERIAL_SHA_INVALID");verifyFile(file,size,sha);
        } else {
            if(!(descriptor instanceof LibraryBackupManifest.CoverPreset))throw new IOException("MATERIAL_DESCRIPTOR_KIND");
            LibraryBackupManifest.CoverPreset d=(LibraryBackupManifest.CoverPreset)descriptor;
            if(!d.resourceId.equals(resource.resourceId))throw new IOException("MATERIAL_RESOURCE_BINDING");
            if(currentNote!=null)throw new IOException("MATERIAL_ASSOCIATION_INVALID");
            sourceItem=d.itemId;display=d.displayName;sourceState="independent";historical=null;mediaType="image/png";
            size=resource.byteLength;if(size<=0||size>MAX_PNG_BYTES)throw new IOException("MATERIAL_SIZE_LIMIT");verifyFile(file,size,resource.sha256);
            return new Record(tx,group,id,kind,sourceItem,null,sourceState,null,0,1,0,size,resource.sha256,mediaType,display,
                    null,"verified_local_copy",null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
        }
        return new Record(tx,group,id,kind,sourceItem,currentNote,sourceState,historical,revision,precision,created,size,
                resource.sha256,mediaType,display,
                digestKind,offline,origin,sourceOrigin,sourceBundle,taskPayload,taskId,remoteTaskId,connectionId,connectionRevision,
                connectionKind,transport,bridge,instance,cert,artifact,title);
    }

    private static Record withTitle(Record r,String title) {return new Record(r.transactionId,r.groupId,r.localId,r.kind,r.sourceItemId,r.currentLocalNoteId,r.sourceState,r.historicalSourceNoteId,r.sourceRevisionMs,r.sourceRevisionPrecisionMs,r.createdAtMs,r.byteLength,r.sha256,r.mediaType,r.displayName,r.digestKind,r.offlineState,r.originKind,r.sourceOriginKind,r.sourceBundleSha256,r.taskPayloadSha256,r.taskId,r.remoteTaskId,r.connectionId,r.connectionRevision,r.connectionKind,r.transport,r.bridgeId,r.instanceId,r.certificateSha256,r.artifactId,title,r.promotionToken);}
    private static Record withPromotionToken(Record r,String token) {return new Record(r.transactionId,r.groupId,r.localId,r.kind,r.sourceItemId,r.currentLocalNoteId,r.sourceState,r.historicalSourceNoteId,r.sourceRevisionMs,r.sourceRevisionPrecisionMs,r.createdAtMs,r.byteLength,r.sha256,r.mediaType,r.displayName,r.digestKind,r.offlineState,r.originKind,r.sourceOriginKind,r.sourceBundleSha256,r.taskPayloadSha256,r.taskId,r.remoteTaskId,r.connectionId,r.connectionRevision,r.connectionKind,r.transport,r.bridgeId,r.instanceId,r.certificateSha256,r.artifactId,r.title,token);}
    private static String plannedPromotionToken(String tx,String group,String local,Kind kind) {
        return UUID.nameUUIDFromBytes((tx+"|"+group+"|"+local+"|"+kind.name()).getBytes(StandardCharsets.UTF_8)).toString();
    }
    static CommitProof proofFor(Record r)throws IOException {return new CommitProof(r.transactionId,r.groupId,r.localId,r.kind,r.sha256,hex(digest().digest(encodeRecord(r))),r.currentLocalNoteId);}

    private void ensureLayout()throws IOException {
        File parent=root.getAbsoluteFile().getParentFile();files.validatePrivateDirectory(parent);
        ensureDirectory(root);ensureDirectory(pendingRoot);ensureDirectory(payloadRoot);ensureDirectory(recordRoot);
    }
    private void ensureDirectory(File dir)throws IOException {try{files.validatePrivateDirectory(dir);}catch(IOException missing){files.mkdirExclusive(dir);files.validatePrivateDirectory(dir);}}
    private File pendingDirectory(String tx,String group,boolean create)throws IOException {
        File txDir=new File(pendingRoot,tx),groupDir=new File(txDir,group);
        if(create){ensureDirectory(txDir);ensureDirectory(groupDir);} else {files.validatePrivateDirectory(txDir);files.validatePrivateDirectory(groupDir);}
        return groupDir;
    }
    private void writeExclusive(File path,byte[] bytes)throws IOException {
        try(FileOutputStream out=files.createExclusive(path)){out.write(bytes);out.flush();out.getFD().sync();}
    }
    private String reservePromotionToken(File dir,Record record)throws IOException {
        File path=new File(dir,"promotion-"+record.localId+".json");
        try(LibraryBackupArchive.SafeFiles.PublishLock ignored=files.lockPublish(path)) {
            if(files.isAbsentNoFollow(path)) {
                Map<String,Object> m=new java.util.TreeMap<>();m.put("schema_version",1);m.put("transaction_id",record.transactionId);
                m.put("group_id",record.groupId);m.put("local_id",record.localId);m.put("kind",record.kind.name());
                m.put("promotion_token",record.promotionToken);byte[] bytes=LibraryBackupJson.encode(m);
                writeExclusive(path,bytes);files.syncDirectory(dir);return record.promotionToken;
            }
            byte[] bytes=readBounded(path,4096);Map<String,Object> m=LibraryBackupJson.parseCheckedObject(bytes,4096);
            LibraryBackupJson.exactKeys(m,"MATERIAL_TOKEN_FIELDS","schema_version","transaction_id","group_id","local_id","kind","promotion_token");
            if(LibraryBackupJson.integer(m.get("schema_version"),1,1,"MATERIAL_TOKEN_SCHEMA")!=1||
                    !record.transactionId.equals(str(m,"transaction_id"))||!record.groupId.equals(str(m,"group_id"))||
                    !record.localId.equals(str(m,"local_id"))||!record.kind.name().equals(str(m,"kind")))throw new IOException("MATERIAL_TOKEN_OWNERSHIP");
            String token=str(m,"promotion_token");requireUuid(token,"MATERIAL_TOKEN_INVALID");return token;
        }
    }
    private void publishCanonicalBytes(File target,byte[] bytes,Record owner,String purpose)throws IOException {
        String sha=hex(digest().digest(bytes));File parent=target.getAbsoluteFile().getParentFile();files.validatePrivateDirectory(parent);
        File source=new File(parent,".stage-"+owner.promotionToken+"-"+target.getName()+".tmp");
        if(files.isAbsentNoFollow(source))writeExclusive(source,bytes);else verifyFile(source,bytes.length,sha);
        publishOwnedFile(source,target,bytes.length,sha,owner,purpose);
    }
    private void publishOwnedFile(File source,File target,long size,String sha,Record owner,String purpose)throws IOException {
        File parent=target.getAbsoluteFile().getParentFile();files.validatePrivateDirectory(parent);
        try(LibraryBackupArchive.SafeFiles.PublishLock ignored=files.lockPublish(target)) {
            files.validatePrivateDirectory(parent);
            boolean sourceAbsent=files.isAbsentNoFollow(source);
            boolean targetAbsent=files.isAbsentNoFollow(target);
            byte[] marker=publishMarker(target,size,sha,owner,purpose);
            LibraryBackupArchive.FileIdentity markerIdentity=null;
            if(targetAbsent) {
                if(sourceAbsent)throw new IOException("MATERIAL_PUBLISH_SOURCE_MISSING");
                verifyFile(source,size,sha);
                markerIdentity=createMarker(target,marker);
            } else {
                LibraryBackupArchive.FileIdentity current=files.inspect(target,false);
                if(current.size==size&&current.links==1) {
                    try {verifyFile(target,size,sha);if(!sourceAbsent)throw new IOException("MATERIAL_PUBLISH_DUPLICATE_SOURCE");files.syncDirectory(parent);return;}
                    catch(IOException notCompleted) {
                        if(!isExactMarker(target,marker,current))throw notCompleted;
                        markerIdentity=current;
                    }
                } else if(isExactMarker(target,marker,current))markerIdentity=current;
                else throw new IOException("MATERIAL_PUBLISH_TARGET_UNKNOWN");
            }
            if(sourceAbsent)throw new IOException("MATERIAL_PUBLISH_SOURCE_MISSING");
            verifyFile(source,size,sha);
            if(markerIdentity==null)throw new IOException("MATERIAL_PUBLISH_MARKER_UNKNOWN");
            files.replaceOwnedMarker(source,target,marker,markerIdentity);
            verifyFile(target,size,sha);
            if(!files.isAbsentNoFollow(source))throw new IOException("MATERIAL_PUBLISH_SOURCE_REMAINS");
            files.syncDirectory(parent);
        }
    }
    private byte[] publishMarker(File target,long size,String sha,Record owner,String purpose)throws IOException {
        Map<String,Object> marker=new java.util.TreeMap<>();marker.put("schema_version",1);marker.put("purpose",purpose);
        marker.put("destination",target.getName());marker.put("expected_size",size);marker.put("sha256",sha);
        marker.put("transaction_id",owner.transactionId);marker.put("group_id",owner.groupId);marker.put("local_id",owner.localId);
        marker.put("kind",owner.kind.name());marker.put("binding_sha256",hex(digest().digest(encodeRecord(owner))));
        marker.put("promotion_token",owner.promotionToken);marker.put("current_local_note_id",owner.currentLocalNoteId);
        return LibraryBackupJson.encode(marker);
    }
    private LibraryBackupArchive.FileIdentity createMarker(File target,byte[] marker)throws IOException {
        LibraryBackupArchive.FileIdentity created=null;
        try(FileOutputStream out=files.createExclusive(target)) {
            created=files.inspect(target,false);out.write(marker);out.flush();out.getFD().sync();
        } catch(IOException failure) {if(created!=null)try{if(files.matchesFile(target,created))files.unlink(target);}catch(IOException ignored){}throw failure;}
        LibraryBackupArchive.FileIdentity id=files.inspect(target,false);
        if(id.links!=1||id.size!=marker.length||!isExactMarker(target,marker,id))throw new IOException("MATERIAL_MARKER_WRITE_FAILED");
        files.syncDirectory(target.getAbsoluteFile().getParentFile());return id;
    }
    private boolean isExactMarker(File target,byte[] marker,LibraryBackupArchive.FileIdentity expected)throws IOException {
        try {
            LibraryBackupArchive.FileIdentity before=files.inspect(target,false);
            if(!before.equals(expected)||before.links!=1||before.size!=marker.length)return false;
            byte[] actual=new byte[marker.length];try(LibraryBackupArchive.Seekable input=files.openRead(target)) {
                if(!before.equals(input.openedIdentity())||input.size()!=marker.length)return false;
                input.readFully(0,actual,0,actual.length);
                if(!before.equals(input.openedIdentity())||!before.equals(input.currentPathIdentity()))return false;
            }
            return java.util.Arrays.equals(marker,actual);
        } catch(IOException unsafe) {return false;}
    }
    private void copyVerified(File source,File target,long expected,String sha,long max)throws Exception {
        if(expected<0||expected>max||!isHash(sha))throw new IOException("MATERIAL_SIZE_LIMIT");
        LibraryBackupArchive.FileIdentity before=files.inspect(source,false);
        if(before.size!=expected)throw new IOException("MATERIAL_SOURCE_CHANGED");
        LibraryBackupArchive.FileIdentity created=null;boolean good=false;
        try(LibraryBackupArchive.Seekable input=files.openRead(source);FileOutputStream output=files.createExclusive(target)) {
            created=files.inspect(target,false);
            if(!before.equals(input.openedIdentity())||input.size()!=expected)throw new IOException("MATERIAL_SOURCE_CHANGED");
            MessageDigest digest=digest();byte[] buffer=new byte[32*1024];long offset=0;
            while(offset<expected){int n=(int)Math.min(buffer.length,expected-offset);input.readFully(offset,buffer,0,n);digest.update(buffer,0,n);output.write(buffer,0,n);offset+=n;}
            output.flush();output.getFD().sync();
            if(!hex(digest.digest()).equals(sha)||!before.equals(input.openedIdentity())||!before.equals(input.currentPathIdentity()))throw new IOException("MATERIAL_SOURCE_CHANGED");
            good=true;
        } finally {
            if(!good&&created!=null)try{if(files.matchesFile(target,created))files.unlink(target);}catch(Exception ignored){}
        }
        verifyFile(target,expected,sha);
    }

    private void verifyFile(File file,long size,String sha)throws IOException {
        verifyFile(file,size,sha,1);
    }
    private void verifyFile(File file,long size,String sha,long expectedLinks)throws IOException {
        LibraryBackupArchive.FileIdentity before=expectedLinks==2?files.inspectOwnedPair(file):files.inspect(file,false);
        if(before.size!=size||before.links!=expectedLinks||!isHash(sha))throw new IOException("MATERIAL_FILE_INVALID");
        try(LibraryBackupArchive.Seekable in=expectedLinks==2?files.openOwnedPair(file):files.openRead(file)) {
            if(!before.equals(in.openedIdentity())||in.size()!=size||!hashSeekable(in).equals(sha)||
                    !before.equals(in.openedIdentity())||!before.equals(in.currentPathIdentity()))throw new IOException("MATERIAL_FILE_CHANGED");
        }
    }
    private String hashFile(File file,long expected)throws IOException {
        LibraryBackupArchive.FileIdentity before=files.inspect(file,false);if(before.size!=expected||before.links!=1)throw new IOException("MATERIAL_FILE_INVALID");
        try(LibraryBackupArchive.Seekable in=files.openRead(file)){if(!before.equals(in.openedIdentity()))throw new IOException("MATERIAL_FILE_CHANGED");String sha=hashSeekable(in);if(!before.equals(in.currentPathIdentity()))throw new IOException("MATERIAL_FILE_CHANGED");return sha;}
    }
    private static String hashSeekable(LibraryBackupArchive.Seekable in)throws IOException {MessageDigest d=digest();byte[] buffer=new byte[32*1024];long pos=0,size=in.size();while(pos<size){int n=(int)Math.min(buffer.length,size-pos);in.readFully(pos,buffer,0,n);d.update(buffer,0,n);pos+=n;}return hex(d.digest());}
    private byte[] readBounded(File file,long max)throws IOException {
        LibraryBackupArchive.FileIdentity before=files.inspect(file,false);if(before.size<0||before.size>max||before.size>Integer.MAX_VALUE||before.links!=1)throw new IOException("MATERIAL_SIZE_LIMIT");
        byte[] result=new byte[(int)before.size];try(LibraryBackupArchive.Seekable in=files.openRead(file)){if(!before.equals(in.openedIdentity())||in.size()!=before.size)throw new IOException("MATERIAL_FILE_CHANGED");in.readFully(0,result,0,result.length);if(!before.equals(in.openedIdentity())||!before.equals(in.currentPathIdentity()))throw new IOException("MATERIAL_FILE_CHANGED");}return result;
    }
    private Record readRecord(File path)throws IOException {return decodeRecord(readBounded(path,MAX_RECORD_BYTES));}
    private boolean exists(File file)throws IOException {return !files.isAbsentNoFollow(file);}
    private static void requireGate(CommitGate gate)throws IOException {if(gate==null)throw new IOException("MATERIAL_COMMIT_GATE_REQUIRED");}
    private static boolean associationValid(String state,String sourceNote,String currentNote) {
        if("linked_note".equals(state))return sourceNote!=null&&currentNote!=null&&currentNote.matches("[A-Za-z0-9_-]{1,160}");
        return ("source_deleted".equals(state)||"source_not_selected".equals(state)||"independent".equals(state))&&sourceNote==null&&currentNote==null;
    }
    private static long limit(Kind kind){return kind==Kind.VAULT?MAX_VAULT_BYTES:kind==Kind.VIDEO?MAX_VIDEO_BYTES:MAX_PNG_BYTES;}
    private static String extension(Kind kind){return kind==Kind.VIDEO?".mp4":kind==Kind.COVER_PRESET?".png":".json";}
    private File finalPayload(Record r){return new File(payloadRoot,r.localId+extension(r.kind));}
    private static boolean isUuid(String value){if(value==null)return false;try{return UUID.fromString(value).toString().equals(value)&&value.length()==36;}catch(IllegalArgumentException e){return false;}}
    private static void requireUuid(String value,String code)throws IOException {if(!isUuid(value))throw new IOException(code);}
    private static boolean isHash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static boolean sameBinding(Record a,Record b)throws IOException{return a!=null&&b!=null&&java.util.Arrays.equals(encodeRecord(a),encodeRecord(b));}
    private static boolean sameRequest(Record persisted,Record requested)throws IOException {
        String sentinel="00000000-0000-0000-0000-000000000000";
        return sameBinding(withPromotionToken(persisted,sentinel),withPromotionToken(requested,sentinel));
    }
    private static boolean sameProof(CommitProof a,CommitProof b) {
        return a!=null&&b!=null&&a.transactionId.equals(b.transactionId)&&a.groupId.equals(b.groupId)&&
                a.localId.equals(b.localId)&&a.kind==b.kind&&a.payloadSha256.equals(b.payloadSha256)&&
                a.bindingSha256.equals(b.bindingSha256)&&java.util.Objects.equals(a.currentLocalNoteId,b.currentLocalNoteId);
    }
    private boolean sameFile(File a,File b)throws IOException {LibraryBackupArchive.FileIdentity x=files.inspect(a,false),y=files.inspect(b,false);return x.device==y.device&&x.inode==y.inode;}
    private void deleteVerifiedIfPresent(File path,Record row)throws IOException {if(exists(path)){verifyFile(path,row.byteLength,row.sha256);files.unlink(path);}}
    private void deleteOwnedMetadataIfPresent(File path,Record row)throws IOException {if(exists(path)){Record actual=readRecord(path);if(!sameBinding(actual,row))throw new IOException("MATERIAL_ROLLBACK_OWNERSHIP_MISMATCH");LibraryBackupArchive.FileIdentity id=files.inspect(path,false);if(id.links!=1)throw new IOException("MATERIAL_ROLLBACK_OWNERSHIP_MISMATCH");files.unlink(path);}}

    private static byte[] encodeRecord(Record r)throws IOException {
        Map<String,Object> m=new java.util.TreeMap<>();m.put("schema_version",2);m.put("transaction_id",r.transactionId);m.put("group_id",r.groupId);m.put("local_id",r.localId);m.put("kind",r.kind.name());m.put("source_item_id",r.sourceItemId);m.put("current_local_note_id",r.currentLocalNoteId);m.put("source_state",r.sourceState);m.put("historical_source_note_id",r.historicalSourceNoteId);m.put("source_revision_ms",r.sourceRevisionMs);m.put("source_revision_precision_ms",r.sourceRevisionPrecisionMs);m.put("created_at_ms",r.createdAtMs);m.put("byte_length",r.byteLength);m.put("sha256",r.sha256);m.put("media_type",r.mediaType);m.put("display_name",r.displayName);m.put("digest_kind",r.digestKind);m.put("offline_state",r.offlineState);m.put("origin_kind",r.originKind);m.put("source_origin_kind",r.sourceOriginKind);m.put("source_bundle_sha256",r.sourceBundleSha256);m.put("task_payload_sha256",r.taskPayloadSha256);m.put("task_id",r.taskId);m.put("remote_task_id",r.remoteTaskId);m.put("connection_id",r.connectionId);m.put("connection_revision",r.connectionRevision);m.put("connection_kind",r.connectionKind);m.put("transport",r.transport);m.put("bridge_id",r.bridgeId);m.put("instance_id",r.instanceId);m.put("certificate_sha256",r.certificateSha256);m.put("artifact_id",r.artifactId);m.put("title",r.title);m.put("promotion_token",r.promotionToken);return LibraryBackupJson.encode(m);
    }
    private static Record decodeRecord(byte[] bytes)throws IOException {
        Map<String,Object> m=LibraryBackupJson.parseCheckedObject(bytes,MAX_RECORD_BYTES);
        LibraryBackupJson.exactKeys(m,"MATERIAL_RECORD_FIELDS","schema_version","transaction_id","group_id","local_id","kind","source_item_id","current_local_note_id","source_state","historical_source_note_id","source_revision_ms","source_revision_precision_ms","created_at_ms","byte_length","sha256","media_type","display_name","digest_kind","offline_state","origin_kind","source_origin_kind","source_bundle_sha256","task_payload_sha256","task_id","remote_task_id","connection_id","connection_revision","connection_kind","transport","bridge_id","instance_id","certificate_sha256","artifact_id","title","promotion_token");
        if(LibraryBackupJson.integer(m.get("schema_version"),2,2,"MATERIAL_RECORD_SCHEMA")!=2)throw new IOException("MATERIAL_RECORD_SCHEMA");
        Kind kind;try{kind=Kind.valueOf(LibraryBackupJson.string(m.get("kind"),"MATERIAL_KIND"));}catch(IllegalArgumentException e){throw new IOException("MATERIAL_KIND");}
        String tx=str(m,"transaction_id"),group=str(m,"group_id"),id=str(m,"local_id");requireUuid(tx,"MATERIAL_TRANSACTION");requireUuid(group,"MATERIAL_GROUP");requireUuid(id,"MATERIAL_LOCAL_ID");
        Object rev=m.get("connection_revision");Long connectionRevision=rev==null?null:LibraryBackupJson.integer(rev,0,Long.MAX_VALUE,"MATERIAL_CONNECTION_REVISION");
        Record result=new Record(tx,group,id,kind,str(m,"source_item_id"),nullable(m,"current_local_note_id"),str(m,"source_state"),nullable(m,"historical_source_note_id"),LibraryBackupJson.integer(m.get("source_revision_ms"),0,Long.MAX_VALUE,"MATERIAL_REVISION"),(int)LibraryBackupJson.integer(m.get("source_revision_precision_ms"),1,1000,"MATERIAL_PRECISION"),LibraryBackupJson.integer(m.get("created_at_ms"),0,Long.MAX_VALUE,"MATERIAL_CREATED"),LibraryBackupJson.integer(m.get("byte_length"),0,MAX_VIDEO_BYTES,"MATERIAL_LENGTH"),str(m,"sha256"),str(m,"media_type"),nullable(m,"display_name"),nullable(m,"digest_kind"),nullable(m,"offline_state"),nullable(m,"origin_kind"),nullable(m,"source_origin_kind"),nullable(m,"source_bundle_sha256"),nullable(m,"task_payload_sha256"),nullable(m,"task_id"),nullable(m,"remote_task_id"),nullable(m,"connection_id"),connectionRevision,nullable(m,"connection_kind"),nullable(m,"transport"),nullable(m,"bridge_id"),nullable(m,"instance_id"),nullable(m,"certificate_sha256"),nullable(m,"artifact_id"),nullable(m,"title"),str(m,"promotion_token"));
        validateRecord(result);
        return result;
    }
    private static void validateRecord(Record r)throws IOException {
        if(!isUuid(r.transactionId)||!isUuid(r.groupId)||!isUuid(r.localId)||!isUuid(r.promotionToken)||!isHash(r.sha256)||r.byteLength<=0||r.createdAtMs<0||r.sourceRevisionMs<0)
            throw new IOException("MATERIAL_RECORD_INVALID");
        if(r.sourceItemId==null||!r.sourceItemId.matches("[ivac]-[0-9a-f]{32}"))throw new IOException("MATERIAL_RECORD_INVALID");
        if(!associationValid(r.sourceState,r.currentLocalNoteId==null?null:"linked",r.currentLocalNoteId))throw new IOException("MATERIAL_RECORD_INVALID");
        if(r.currentLocalNoteId!=null&&!r.currentLocalNoteId.matches("[A-Za-z0-9_-]{1,160}"))throw new IOException("MATERIAL_RECORD_INVALID");
        if(r.historicalSourceNoteId!=null&&(r.historicalSourceNoteId.isEmpty()||r.historicalSourceNoteId.length()>4096))throw new IOException("MATERIAL_RECORD_INVALID");
        if(r.kind==Kind.VAULT) {
            if(!"application/json".equals(r.mediaType)||r.byteLength>MAX_VAULT_BYTES||r.sourceRevisionPrecisionMs!=1||r.originKind!=null)
                throw new IOException("MATERIAL_RECORD_INVALID");
        } else if(r.kind==Kind.VIDEO) {
            if(!"video/mp4".equals(r.mediaType)||r.byteLength>MAX_VIDEO_BYTES||!(r.sourceRevisionPrecisionMs==1||r.sourceRevisionPrecisionMs==1000)||
                    (r.sourceRevisionPrecisionMs==1000&&r.sourceRevisionMs%1000!=0)||!"restored_archive".equals(r.originKind)||
                    !"verified_local_copy".equals(r.offlineState)||r.historicalSourceNoteId==null||!isHash(r.sourceBundleSha256))
                throw new IOException("MATERIAL_RECORD_INVALID");
        } else if(r.kind==Kind.COVER_PRESET) {
            if(!"image/png".equals(r.mediaType)||r.byteLength>MAX_PNG_BYTES||r.currentLocalNoteId!=null||
                    !"independent".equals(r.sourceState)||r.historicalSourceNoteId!=null||r.sourceRevisionMs!=0||r.sourceRevisionPrecisionMs!=1)
                throw new IOException("MATERIAL_RECORD_INVALID");
        }
    }
    private static String str(Map<String,Object> m,String k)throws IOException{return LibraryBackupJson.string(m.get(k),"MATERIAL_RECORD_STRING");}
    private static String nullable(Map<String,Object> m,String k)throws IOException{Object v=m.get(k);return v==null?null:LibraryBackupJson.string(v,"MATERIAL_RECORD_STRING");}
    private static boolean isNull(Object value){return value==null;}

    private static void validateVaultPayload(Record descriptor,byte[] bytes)throws IOException {
        Map<String,Object> payload=LibraryBackupJson.parseCheckedObject(bytes,(int)MAX_VAULT_BYTES);
        LibraryBackupJson.exactKeys(payload,"VAULT_PAYLOAD_FIELDS","schema_version","title","markdown","source_note_id","source_revision_ms","created_at_ms");
        if(LibraryBackupJson.integer(payload.get("schema_version"),1,1,"VAULT_PAYLOAD_SCHEMA")!=1)throw new IOException("VAULT_PAYLOAD_SCHEMA");
        String title=LibraryBackupJson.string(payload.get("title"),"VAULT_TITLE"),markdown=LibraryBackupJson.string(payload.get("markdown"),"VAULT_MARKDOWN");
        if(title.trim().isEmpty()||markdown.length()>MAX_VAULT_BYTES||!descriptor.historicalSourceNoteId.equals(LibraryBackupJson.string(payload.get("source_note_id"),"VAULT_SOURCE"))||descriptor.sourceRevisionMs!=LibraryBackupJson.integer(payload.get("source_revision_ms"),0,Long.MAX_VALUE,"VAULT_REVISION")||descriptor.createdAtMs!=LibraryBackupJson.integer(payload.get("created_at_ms"),0,Long.MAX_VALUE,"VAULT_CREATED"))throw new IOException("VAULT_DESCRIPTOR_PAYLOAD_MISMATCH");
    }
    private static String vaultTitle(byte[] bytes)throws IOException {return LibraryBackupJson.string(LibraryBackupJson.parseCheckedObject(bytes,(int)MAX_VAULT_BYTES).get("title"),"VAULT_TITLE");}

    private static long limitNotUsed(Kind kind){return 0;}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}

    private static final class AndroidMediaValidator implements MediaValidator {
        @Override public void validate(Kind kind,File file)throws IOException {
            if(kind==Kind.COVER_PRESET) {
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
                if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>NoteImage.MAX_DOCUMENT_PIXELS)throw new IOException("MATERIAL_PNG_INVALID");
                Bitmap decoded=BitmapFactory.decodeFile(file.getAbsolutePath());if(decoded==null)throw new IOException("MATERIAL_PNG_INVALID");decoded.recycle();
            } else if(kind==Kind.VIDEO) {
                MediaMetadataRetriever retriever=new MediaMetadataRetriever();
                try {retriever.setDataSource(file.getAbsolutePath());String duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);String width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);String height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);if(parsePositive(duration)<=0||parsePositive(width)<=0||parsePositive(height)<=0)throw new IOException("MATERIAL_VIDEO_UNPLAYABLE");}
                catch(RuntimeException failure){throw new IOException("MATERIAL_VIDEO_UNPLAYABLE");}
                finally {try{retriever.release();}catch(RuntimeException ignored){}}
            }
        }
        private static long parsePositive(String value){try{return Long.parseLong(value);}catch(Exception e){return 0;}}
    }

    private static final class AndroidFiles implements LibraryBackupArchive.SafeFiles {
        @Override public LibraryBackupArchive.FileIdentity inspect(File path,boolean directory)throws IOException {try{checkAncestors(path.getAbsoluteFile().getParentFile());android.system.StructStat s=Os.lstat(path.getAbsolutePath());validateStat(s,directory);return identity(s,directory);}catch(android.system.ErrnoException e){throw new IOException("MATERIAL_FILE_UNAVAILABLE");}}
        @Override public LibraryBackupArchive.FileIdentity inspectOwnedPair(File path)throws IOException {try{checkAncestors(path.getAbsoluteFile().getParentFile());android.system.StructStat s=Os.lstat(path.getAbsolutePath());validatePairStat(s);return identity(s,false);}catch(android.system.ErrnoException e){throw new IOException("MATERIAL_FILE_UNAVAILABLE");}}
        @Override public boolean isAbsentNoFollow(File path)throws IOException {try{checkAncestors(path.getAbsoluteFile().getParentFile());Os.lstat(path.getAbsolutePath());return false;}catch(android.system.ErrnoException e){if(e.errno==OsConstants.ENOENT)return true;throw new IOException("MATERIAL_FILE_UNAVAILABLE");}}
        @Override public void validatePrivateDirectory(File path)throws IOException {try{checkAncestors(path.getAbsoluteFile().getParentFile());android.system.StructStat s=Os.lstat(path.getAbsolutePath());validateStat(s,true);if(s.st_uid!=Os.getuid()||(s.st_mode&0022)!=0)throw new IOException("MATERIAL_DIRECTORY_UNSAFE");}catch(android.system.ErrnoException e){throw new IOException("MATERIAL_DIRECTORY_UNAVAILABLE");}}
        @Override public LibraryBackupArchive.Seekable openRead(File path)throws IOException {FileDescriptor fd=null;try{checkAncestors(path.getAbsoluteFile().getParentFile());android.system.StructStat before=Os.lstat(path.getAbsolutePath());validateStat(before,false);fd=Os.open(path.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);android.system.StructStat opened=Os.fstat(fd);validateStat(opened,false);if(!same(before,opened))throw new IOException("MATERIAL_FILE_CHANGED");AndroidChannel input=new AndroidChannel(fd,identity(opened,false),path);fd=null;return input;}catch(android.system.ErrnoException e){throw new IOException("MATERIAL_FILE_UNAVAILABLE");}finally{if(fd!=null)try{Os.close(fd);}catch(android.system.ErrnoException ignored){}}}
        @Override public LibraryBackupArchive.Seekable openOwnedPair(File path)throws IOException {FileDescriptor fd=null;try{checkAncestors(path.getAbsoluteFile().getParentFile());android.system.StructStat before=Os.lstat(path.getAbsolutePath());validatePairStat(before);fd=Os.open(path.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);android.system.StructStat opened=Os.fstat(fd);validatePairStat(opened);if(!same(before,opened))throw new IOException("MATERIAL_FILE_CHANGED");AndroidChannel input=new AndroidChannel(fd,identity(opened,false),path);fd=null;return input;}catch(android.system.ErrnoException e){throw new IOException("MATERIAL_FILE_UNAVAILABLE");}finally{if(fd!=null)try{Os.close(fd);}catch(android.system.ErrnoException ignored){}}}
        @Override public FileOutputStream createExclusive(File path)throws IOException {try{checkAncestors(path.getAbsoluteFile().getParentFile());return new FileOutputStream(Os.open(path.getAbsolutePath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600));}catch(android.system.ErrnoException e){throw new IOException("MATERIAL_CREATE_FAILED");}}
        @Override public void mkdirExclusive(File path)throws IOException {try{checkAncestors(path.getAbsoluteFile().getParentFile());Os.mkdir(path.getAbsolutePath(),0700);}catch(android.system.ErrnoException e){throw new IOException("MATERIAL_DIRECTORY_CREATE_FAILED");}}
        @Override public void syncDirectory(File path)throws IOException {FileDescriptor fd=null;try{fd=Os.open(path.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);android.system.StructStat s=Os.fstat(fd);if(!OsConstants.S_ISDIR(s.st_mode)||OsConstants.S_ISLNK(s.st_mode))throw new IOException("MATERIAL_DIRECTORY_UNSAFE");Os.fsync(fd);}catch(android.system.ErrnoException e){throw new IOException("MATERIAL_DIRECTORY_SYNC_FAILED");}finally{if(fd!=null)try{Os.close(fd);}catch(android.system.ErrnoException ignored){}}}
        @Override public void deleteOwnedTree(File path)throws IOException {android.system.StructStat s;try{s=Os.lstat(path.getAbsolutePath());}catch(android.system.ErrnoException e){return;}if(!OsConstants.S_ISDIR(s.st_mode)||OsConstants.S_ISLNK(s.st_mode))throw new IOException("MATERIAL_CLEANUP_UNSAFE");File[] children=path.listFiles();if(children==null)throw new IOException("MATERIAL_CLEANUP_FAILED");for(File child:children){try{android.system.StructStat c=Os.lstat(child.getAbsolutePath());if(OsConstants.S_ISDIR(c.st_mode)&&!OsConstants.S_ISLNK(c.st_mode))deleteOwnedTree(child);else Os.remove(child.getAbsolutePath());}catch(android.system.ErrnoException e){throw new IOException("MATERIAL_CLEANUP_FAILED");}}try{Os.remove(path.getAbsolutePath());}catch(android.system.ErrnoException e){throw new IOException("MATERIAL_CLEANUP_FAILED");}}
        @Override public boolean isPersistentPublishLockArtifact(File path)throws IOException {return new LibraryBackupArchive.AndroidSafeFiles().isPersistentPublishLockArtifact(path);}
        @Override public LibraryBackupArchive.SafeFiles.PublishLock lockPublish(File destination)throws IOException { return new LibraryBackupArchive.AndroidSafeFiles().lockPublish(destination); }
        @Override public void replaceOwnedMarker(File from,File to,byte[] marker,LibraryBackupArchive.FileIdentity markerIdentity)throws IOException { new LibraryBackupArchive.AndroidSafeFiles().replaceOwnedMarker(from,to,marker,markerIdentity); }
        @Override public void linkNoReplace(File from,File to)throws IOException {try{Os.link(from.getAbsolutePath(),to.getAbsolutePath());}catch(android.system.ErrnoException e){throw new IOException("MATERIAL_PROMOTE_FAILED");}}
        @Override public void unlink(File path)throws IOException {try{Os.remove(path.getAbsolutePath());}catch(android.system.ErrnoException e){if(path.exists())throw new IOException("MATERIAL_UNLINK_FAILED");}}
        @Override public boolean matchesFile(File path,LibraryBackupArchive.FileIdentity expected)throws IOException {try{checkAncestors(path.getAbsoluteFile().getParentFile());android.system.StructStat s=Os.lstat(path.getAbsolutePath());return OsConstants.S_ISREG(s.st_mode)&&!OsConstants.S_ISLNK(s.st_mode)&&s.st_dev==expected.device&&s.st_ino==expected.inode;}catch(android.system.ErrnoException e){return false;}}
        private static void checkAncestors(File path)throws IOException {
            try { LibraryBackupArchive.AndroidSafeFiles.checkAncestors(path); }
            catch(IOException unsafe) { throw new IOException("MATERIAL_PATH_UNSAFE"); }
        }
        private static void validateStat(android.system.StructStat s,boolean dir)throws IOException {if(OsConstants.S_ISLNK(s.st_mode))throw new IOException("MATERIAL_FILE_UNSAFE");if(dir){if(!OsConstants.S_ISDIR(s.st_mode))throw new IOException("MATERIAL_FILE_UNSAFE");}else if(!OsConstants.S_ISREG(s.st_mode)||s.st_nlink!=1)throw new IOException("MATERIAL_FILE_UNSAFE");}
        private static void validatePairStat(android.system.StructStat s)throws IOException {if(OsConstants.S_ISLNK(s.st_mode)||!OsConstants.S_ISREG(s.st_mode)||s.st_nlink!=2)throw new IOException("MATERIAL_FILE_UNSAFE");}
        private static LibraryBackupArchive.FileIdentity identity(android.system.StructStat s,boolean dir){return new LibraryBackupArchive.FileIdentity(s.st_dev,s.st_ino,s.st_size,s.st_mtime*1_000_000_000L,s.st_ctime*1_000_000_000L,s.st_nlink,dir);}
        private static boolean same(android.system.StructStat a,android.system.StructStat b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size&&a.st_mtime==b.st_mtime&&a.st_ctime==b.st_ctime&&a.st_nlink==b.st_nlink&&a.st_mode==b.st_mode;}
        private static final class AndroidChannel implements LibraryBackupArchive.Seekable {final FileInputStream stream;final java.nio.channels.FileChannel channel;final LibraryBackupArchive.FileIdentity opened;final File path;AndroidChannel(FileDescriptor fd,LibraryBackupArchive.FileIdentity id,File p){stream=new FileInputStream(fd);channel=stream.getChannel();opened=id;path=p;}public long size()throws IOException{return channel.size();}public void readFully(long pos,byte[] out,int offset,int length)throws IOException{java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(out,offset,length);long p=pos;while(b.hasRemaining()){int n=channel.read(b,p);if(n<0)throw new IOException("MATERIAL_TRUNCATED");if(n==0)continue;p+=n;}}public LibraryBackupArchive.FileIdentity openedIdentity()throws IOException{try{return identity(Os.fstat(stream.getFD()),false);}catch(android.system.ErrnoException e){throw new IOException("MATERIAL_STAT_FAILED");}}public LibraryBackupArchive.FileIdentity currentPathIdentity()throws IOException{return opened.links==2?new AndroidFiles().inspectOwnedPair(path):new AndroidFiles().inspect(path,false);}public void close()throws IOException{stream.close();}}
    }
}
