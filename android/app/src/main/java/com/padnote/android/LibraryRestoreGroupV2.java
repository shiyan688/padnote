package com.padnote.android;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Internal v2 restore group ledger. It gives one durable visibility marker to
 * note, PDF, assigned-cover, and restored-material members. v1 journals remain
 * interpreted by LibraryRestoreTransaction and are never rewritten here.
 */
final class LibraryRestoreGroupV2 {
    enum Kind { NOTE, PDF, ASSIGNED_COVER, VAULT, VIDEO, COVER_PRESET }
    enum RecoveryPhase { INSTALLATION_PENDING, PROOFS_PARTIAL, READY_TO_COMMIT, COMMIT_RESERVED, COMMITTED, ROLLBACK_IN_PROGRESS, ROLLED_BACK }

    static final class Request {
        final String sourceItemId, sourceResourceId, sourceNoteItemId;
        final String requestedLocalObjectId;
        final Kind kind;
        final long byteLength;
        final String payloadSha256, semanticBindingSha256;

        Request(String sourceItemId, String sourceResourceId, String sourceNoteItemId,
                Kind kind, long byteLength, String payloadSha256, String semanticBindingSha256) {
            this(sourceItemId,sourceResourceId,sourceNoteItemId,null,kind,byteLength,payloadSha256,semanticBindingSha256);
        }
        Request(String sourceItemId, String sourceResourceId, String sourceNoteItemId,
                String requestedLocalObjectId, Kind kind, long byteLength, String payloadSha256, String semanticBindingSha256) {
            this.sourceItemId=sourceItemId; this.sourceResourceId=sourceResourceId;
            this.sourceNoteItemId=sourceNoteItemId; this.requestedLocalObjectId=requestedLocalObjectId; this.kind=kind; this.byteLength=byteLength;
            this.payloadSha256=payloadSha256; this.semanticBindingSha256=semanticBindingSha256;
        }
    }

    static final class Member {
        final String memberId, sourceItemId, sourceResourceId, sourceNoteItemId;
        final String localObjectId, currentLocalNoteId;
        final Kind kind;
        final long byteLength;
        final String payloadSha256, semanticBindingSha256;
        Member(String memberId, Request request, String localObjectId, String currentLocalNoteId) {
            this.memberId=memberId; sourceItemId=request.sourceItemId; sourceResourceId=request.sourceResourceId;
            sourceNoteItemId=request.sourceNoteItemId; this.localObjectId=localObjectId;
            this.currentLocalNoteId=currentLocalNoteId; kind=request.kind; byteLength=request.byteLength;
            payloadSha256=request.payloadSha256; semanticBindingSha256=request.semanticBindingSha256;
        }
    }

    static final class MemberProof {
        final String memberId, transactionId, groupId, localObjectId, currentLocalNoteId;
        final Kind kind;
        final String payloadSha256, semanticBindingSha256, storageBindingSha256;
        MemberProof(String memberId, String transactionId, String groupId, String localObjectId,
                String currentLocalNoteId, Kind kind, String payloadSha256,
                String semanticBindingSha256, String storageBindingSha256) {
            this.memberId=memberId; this.transactionId=transactionId; this.groupId=groupId;
            this.localObjectId=localObjectId; this.currentLocalNoteId=currentLocalNoteId;
            this.kind=kind; this.payloadSha256=payloadSha256;
            this.semanticBindingSha256=semanticBindingSha256; this.storageBindingSha256=storageBindingSha256;
        }
    }

    static final class Plan {
        final String transactionId, archiveSha256, sourceGroupKey, groupId;
        final List<Member> members;
        private Plan(String tx, String archive, String source, String group, List<Member> members) {
            transactionId=tx; archiveSha256=archive; sourceGroupKey=source; groupId=group;
            this.members=Collections.unmodifiableList(new ArrayList<>(members));
        }
        Member member(String sourceItemId, Kind kind) throws IOException {
            for (Member m:members) if (m.sourceItemId.equals(sourceItemId)&&m.kind==kind) return m;
            throw new IOException("RESTORE_V2_MEMBER_UNKNOWN");
        }
    }

    interface Verifier { void verify(Member member, MemberProof proof) throws Exception; }
    interface RollbackVerifier { void verifyAbsent(Member member) throws Exception; }
    interface FaultInjector {
        void afterCommitReservationDurable(String groupId) throws Exception;
        default void afterCommitPathReservationDurable(String groupId) throws Exception { }
        default void afterCommitMarkerPayloadDurable(String groupId) throws Exception { }
        default void afterCommitDirectoryCreated(String groupId) throws Exception { }
        default void afterCommitDirectorySynced(String groupId) throws Exception { }
    }

    private static final int MAX_BYTES=2*1024*1024;
    private final File transactionRoot;
    private final File ownedRoot;
    private final LibraryBackupArchive.SafeFiles files;
    private final FaultInjector faults;

    LibraryRestoreGroupV2(File transactionRoot, LibraryBackupArchive.SafeFiles files) {
        this(transactionRoot,files,null);
    }
    LibraryRestoreGroupV2(File transactionRoot, LibraryBackupArchive.SafeFiles files,FaultInjector faults) {
        if (transactionRoot==null||files==null) throw new IllegalArgumentException("RESTORE_V2_CONFIG");
        File parent=transactionRoot.getAbsoluteFile().getParentFile();
        if(parent==null||parent.getParentFile()==null)throw new IllegalArgumentException("RESTORE_V2_CONFIG");
        this.transactionRoot=transactionRoot.getAbsoluteFile();this.ownedRoot=parent.getParentFile();this.files=files;this.faults=faults;
    }

    /** Persists stable group/member/local object IDs before any installation side effect. */
    Plan ensurePlan(String transactionId, String archiveSha256, String sourceGroupKey,
                    List<Request> requests) throws IOException {
        requireUuid(transactionId,"RESTORE_V2_TRANSACTION"); requireHash(archiveSha256,"RESTORE_V2_ARCHIVE");
        validateSourceKey(sourceGroupKey); List<Request> sorted=validateAndSort(requests);
        ensureDirectory(transactionRoot);
        File tx=new File(transactionRoot,transactionId); ensureDirectory(tx);
        String key=sha256(sourceGroupKey.getBytes(StandardCharsets.UTF_8)); File path=new File(tx,"source-"+key+".json");
        try (LibraryBackupArchive.SafeFiles.PublishLock ignored=files.lockPublish(path)) {
            Plan plan;
            if (!files.isAbsentNoFollow(path)) {
                plan=decodePlan(read(path),transactionId,archiveSha256,sourceGroupKey);
                if (!sameRequests(plan,sorted)) throw new IOException("RESTORE_V2_REQUEST_CHANGED");
                syncDurable(path);
            } else {
                String group=UUID.randomUUID().toString(); Map<String,String> noteLocal=new LinkedHashMap<>();
                for (Request r:sorted) if (r.kind==Kind.NOTE) noteLocal.put(r.sourceItemId,r.requestedLocalObjectId==null?"note-"+UUID.randomUUID().toString().replace("-",""):r.requestedLocalObjectId);
                List<Member> members=new ArrayList<>();
                for (Request r:sorted) {
                    String local;
                    if (r.kind==Kind.NOTE) local=noteLocal.get(r.sourceItemId);
                    else if (r.kind==Kind.PDF||r.kind==Kind.ASSIGNED_COVER) {
                        String sourceNote=r.sourceNoteItemId;
                        local=sourceNote==null?null:noteLocal.get(sourceNote);
                        if (local==null) throw new IOException("RESTORE_V2_LINK_TARGET_INVALID");
                    } else local=r.requestedLocalObjectId==null?UUID.randomUUID().toString():r.requestedLocalObjectId;
                    String currentNote=r.kind==Kind.NOTE?local:
                            (r.sourceNoteItemId==null?null:noteLocal.get(r.sourceNoteItemId));
                    if (r.sourceNoteItemId!=null&&currentNote==null) throw new IOException("RESTORE_V2_LINK_TARGET_INVALID");
                    members.add(new Member(UUID.randomUUID().toString(),r,local,currentNote));
                }
                validateMembers(members);
                plan=new Plan(transactionId,archiveSha256,sourceGroupKey,group,members);
                writeNew(path,encodePlan(plan));
            }
            File groupDir=groupDir(plan); ensureDirectory(groupDir);
            File membership=new File(groupDir,"members.json"); byte[] expected=encodePlan(plan);
            if (files.isAbsentNoFollow(membership)) writeNew(membership,expected);
            else if (!MessageDigest.isEqual(expected,read(membership))) throw new IOException("RESTORE_V2_MEMBERSHIP_CHANGED");
            else syncDurable(membership);
            ensureDirectory(new File(groupDir,"proofs")); files.syncDirectory(groupDir); files.syncDirectory(tx);
            return plan;
        }
    }

    Plan loadPlan(String transactionId,String archiveSha256,String sourceGroupKey)throws IOException {
        requireUuid(transactionId,"RESTORE_V2_TRANSACTION");requireHash(archiveSha256,"RESTORE_V2_ARCHIVE");validateSourceKey(sourceGroupKey);
        File tx=new File(transactionRoot,transactionId),mapping=new File(tx,"source-"+sha256(sourceGroupKey.getBytes(StandardCharsets.UTF_8))+".json");
        if(files.isAbsentNoFollow(mapping))throw new IOException("RESTORE_V2_PLAN_MISSING");
        Plan plan=decodePlan(read(mapping),transactionId,archiveSha256,sourceGroupKey);
        File membership=new File(groupDir(plan),"members.json");if(files.isAbsentNoFollow(membership)||!MessageDigest.isEqual(encodePlan(plan),read(membership)))throw new IOException("RESTORE_V2_MEMBERSHIP_CHANGED");
        syncDurable(mapping);return plan;
    }

    Lease acquire(Plan plan) throws IOException {
        if(plan==null)throw new IOException("RESTORE_V2_PLAN_REQUIRED");
        File dir=groupDir(plan); files.validatePrivateDirectory(dir);
        LibraryBackupArchive.SafeFiles.PublishLock lock=files.lockPublish(new File(dir,"lease"));
        try {
            Plan persisted=decodePlan(read(new File(dir,"members.json")),plan.transactionId,plan.archiveSha256,plan.sourceGroupKey);
            if(!samePlan(persisted,plan))throw new IOException("RESTORE_V2_PLAN_MISMATCH");
            return new Lease(plan,dir,lock);
        } catch(Exception e) {
            try{lock.close();}catch(Exception ignored){}
            if(e instanceof IOException)throw(IOException)e;
            throw new IOException("RESTORE_V2_JOURNAL_INVALID");
        }
    }

    final class Lease implements AutoCloseable {
        final Plan plan; final File directory; private final LibraryBackupArchive.SafeFiles.PublishLock lock;
        private boolean closed;
        private Lease(Plan plan,File directory,LibraryBackupArchive.SafeFiles.PublishLock lock){this.plan=plan;this.directory=directory;this.lock=lock;}
        void registerBeforeInstall(MemberProof proof) throws IOException {
            requireOpen(); Member m=find(plan,proof.memberId);
            if(m==null||!matchesRequest(m,proof))throw new IOException("RESTORE_V2_PROOF_MISMATCH");
            if(isCommittedUnderLease())throw new IOException("RESTORE_V2_ALREADY_COMMITTED");
            if(!files.isAbsentNoFollow(new File(directory,"commit-reservation.json"))||
                    !files.isAbsentNoFollow(new File(directory,"rollback.json")))
                throw new IOException("RESTORE_V2_REGISTRATION_CLOSED");
            File path=proofPath(directory,m.memberId);byte[] bytes=encodeProof(proof);
            if(files.isAbsentNoFollow(path))writeNew(path,bytes);
            else if(!MessageDigest.isEqual(bytes,read(path)))throw new IOException("RESTORE_V2_PROOF_CHANGED");
            else syncDurable(path);
        }
        boolean isCommitted()throws IOException{requireOpen();return isCommittedUnderLease();}
        MemberProof proof(String memberId)throws IOException{requireOpen();return findProof(readProofs(),memberId);}
        /** Classifies a restart while the caller holds the group OS lease. Unknown residue is an error. */
        RecoveryPhase recoveryStatus() throws IOException {
            requireOpen();
            boolean rolled=!files.isAbsentNoFollow(new File(directory,"rolled-back.json"));
            boolean rollback=!files.isAbsentNoFollow(new File(directory,"rollback.json"));
            File committedPath=new File(directory,"committed.json");
            boolean committedPathExists=!files.isAbsentNoFollow(committedPath);
            File reservation=new File(directory,"commit-reservation.json"),markerFile=new File(directory,"commit-marker.json"),intent=new File(directory,"commit-directory-intent.json");
            boolean reserved=!files.isAbsentNoFollow(reservation),markerExists=!files.isAbsentNoFollow(markerFile),intentExists=!files.isAbsentNoFollow(intent);
            boolean legacyTempExists=!files.isAbsentNoFollow(new File(directory,"commit-temp.json"));
            List<MemberProof> rows=readProofs();byte[] marker=rows.size()==plan.members.size()?encodeCommit(plan,rows):null;
            if(rolled){if(committedPathExists||reserved||markerExists||intentExists||legacyTempExists)throw new IOException("RESTORE_V2_RECOVERY_RESIDUE");readState(new File(directory,"rolled-back.json"),"rolled_back");return RecoveryPhase.ROLLED_BACK;}
            if(rollback){if(committedPathExists||reserved||markerExists||intentExists||legacyTempExists)throw new IOException("RESTORE_V2_RECOVERY_RESIDUE");readState(new File(directory,"rollback.json"),"rollback");return RecoveryPhase.ROLLBACK_IN_PROGRESS;}
            if(committedPathExists){
                if(rows.size()!=plan.members.size()||!reserved||!markerExists||!intentExists||legacyTempExists)throw new IOException("RESTORE_V2_RECOVERY_RESIDUE");
                if(!isCommittedUnderLease())throw new IOException("RESTORE_V2_RECOVERY_RESIDUE");
                // A prior mkdir may have succeeded before the parent fsync failed. Sync again before
                // reporting committed; this is idempotent and never recreates or replaces the point.
                files.syncDirectory(directory);if(!isCommittedUnderLease())throw new IOException("RESTORE_V2_RECOVERY_RESIDUE");return RecoveryPhase.COMMITTED;
            }
            if(legacyTempExists)throw new IOException("RESTORE_V2_COMMIT_TEMP_WITHOUT_MARKER");
            if(reserved){
                if(rows.size()!=plan.members.size())throw new IOException("RESTORE_V2_RECOVERY_RESIDUE");
                validateReservation(reservation,marker);
                if(markerExists&&!MessageDigest.isEqual(marker,read(markerFile)))throw new IOException("RESTORE_V2_COMMIT_MARKER_INVALID");
                if(intentExists)validateCommitDirectoryIntent(intent,marker);
                return RecoveryPhase.COMMIT_RESERVED;
            }
            if(markerExists||intentExists)throw new IOException("RESTORE_V2_COMMIT_RESIDUE_WITHOUT_RESERVATION");
            if(rows.isEmpty())return RecoveryPhase.INSTALLATION_PENDING;
            return rows.size()==plan.members.size()?RecoveryPhase.READY_TO_COMMIT:RecoveryPhase.PROOFS_PARTIAL;
        }
        void commit(Verifier verifier) throws Exception {
            requireOpen(); if(verifier==null)throw new IOException("RESTORE_V2_VERIFIER_REQUIRED");
            if(!files.isAbsentNoFollow(new File(directory,"rolled-back.json")))throw new IOException("RESTORE_V2_ROLLED_BACK");
            List<MemberProof> proofs=readProofs();if(proofs.size()!=plan.members.size())throw new IOException("RESTORE_V2_GROUP_INCOMPLETE");
            Map<String,MemberProof> by=new LinkedHashMap<>();for(MemberProof p:proofs)by.put(p.memberId,p);
            for(Member m:plan.members){MemberProof p=by.get(m.memberId);if(p==null)throw new IOException("RESTORE_V2_GROUP_INCOMPLETE");verifier.verify(m,p);}
            byte[] marker=encodeCommit(plan,proofs);File committedPath=new File(directory,"committed.json");
            File reservation=new File(directory,"commit-reservation.json"),markerFile=new File(directory,"commit-marker.json"),intent=new File(directory,"commit-directory-intent.json");
            if(!files.isAbsentNoFollow(new File(directory,"commit-temp.json")))throw new IOException("RESTORE_V2_COMMIT_TEMP_RESIDUE");
            if(!files.isAbsentNoFollow(committedPath)){
                if(!isCommittedUnderLease())throw new IOException("RESTORE_V2_COMMIT_CONFLICT");
                if(files.isAbsentNoFollow(markerFile)||!MessageDigest.isEqual(marker,read(markerFile)))throw new IOException("RESTORE_V2_COMMIT_CONFLICT");
                files.syncDirectory(directory);if(!isCommittedUnderLease())throw new IOException("RESTORE_V2_COMMIT_NOT_DURABLE");return;
            }
            byte[] reservationBytes=commitReservationBytes(marker);
            if(files.isAbsentNoFollow(reservation))writeNew(reservation,reservationBytes);
            else if(!MessageDigest.isEqual(reservationBytes,read(reservation)))throw new IOException("RESTORE_V2_RESERVATION_CONFLICT");else syncDurable(reservation);
            if(faults!=null)faults.afterCommitReservationDurable(plan.groupId);
            if(files.isAbsentNoFollow(markerFile))writeNew(markerFile,marker);
            else if(!MessageDigest.isEqual(marker,read(markerFile)))throw new IOException("RESTORE_V2_COMMIT_MARKER_CONFLICT");else syncDurable(markerFile);
            validateReservation(reservation,marker);
            byte[] intentBytes=commitDirectoryIntentBytes(marker);
            if(files.isAbsentNoFollow(intent))writeNew(intent,intentBytes);
            else if(!MessageDigest.isEqual(intentBytes,read(intent)))throw new IOException("RESTORE_V2_COMMIT_INTENT_CONFLICT");else syncDurable(intent);
            validateCommitDirectoryIntent(intent,marker);
            if(faults!=null)faults.afterCommitMarkerPayloadDurable(plan.groupId);
            if(faults!=null)faults.afterCommitPathReservationDurable(plan.groupId);
            // The complete marker payload and bound intent are durable before this atomic no-replace commit point.
            files.mkdirExclusive(committedPath);
            if(faults!=null)faults.afterCommitDirectoryCreated(plan.groupId);
            files.syncDirectory(directory);
            if(faults!=null)faults.afterCommitDirectorySynced(plan.groupId);
            if(!isCommittedUnderLease())throw new IOException("RESTORE_V2_COMMIT_NOT_DURABLE");
        }
        void markRollbackIntent() throws IOException {
            requireOpen();if(isCommittedUnderLease())throw new IOException("RESTORE_V2_ALREADY_COMMITTED");
            File p=new File(directory,"rollback.json");byte[] data=stateBytes(plan,"rollback");
            if(files.isAbsentNoFollow(p))writeNew(p,data);else if(!MessageDigest.isEqual(data,read(p)))throw new IOException("RESTORE_V2_ROLLBACK_CONFLICT");else syncDurable(p);
        }
        void markRolledBack(RollbackVerifier verifier) throws Exception {
            requireOpen();if(isCommittedUnderLease())throw new IOException("RESTORE_V2_ALREADY_COMMITTED");
            if(files.isAbsentNoFollow(new File(directory,"rollback.json")))throw new IOException("RESTORE_V2_ROLLBACK_NOT_STARTED");
            if(verifier==null)throw new IOException("RESTORE_V2_ROLLBACK_VERIFIER_REQUIRED");
            readProofs();
            for(Member m:plan.members)verifier.verifyAbsent(m);
            File p=new File(directory,"rolled-back.json");byte[] data=stateBytes(plan,"rolled_back");
            if(files.isAbsentNoFollow(p))writeNew(p,data);else if(!MessageDigest.isEqual(data,read(p)))throw new IOException("RESTORE_V2_ROLLBACK_CONFLICT");else syncDurable(p);
        }
        CommitGate gate(){return (proof)->{requireOpen();return isCommittedMaterial(proof);};}
        RestoredLibraryMaterialStore.PendingRollbackGate pendingRollbackGate(){
            return proof->{requireOpen();MemberProof registered=findRegisteredMaterialProof(proof);if(registered==null||isCommittedMaterial(proof)||files.isAbsentNoFollow(new File(directory,"rollback.json")))throw new IOException("RESTORE_V2_ROLLBACK_NOT_PENDING");
                return new RestoredLibraryMaterialStore.PendingRollbackPermit(){private boolean permitClosed;public boolean stillPending(RestoredLibraryMaterialStore.CommitProof candidate)throws Exception{return !permitClosed&&!closed&&sameMaterialProof(proof,candidate)&&files.isAbsentNoFollow(new File(directory,"committed.json"))&&!files.isAbsentNoFollow(new File(directory,"rollback.json"))&&sameProof(registered,findProof(readProofs(),registered.memberId));}public void close(){permitClosed=true;}};};
        }
        MemberProof memberProofFor(RestoredLibraryMaterialStore.CommitProof proof)throws IOException {
            requireOpen();MemberProof row=findRegisteredMaterialProof(proof);if(row==null)throw new IOException("RESTORE_V2_PROOF_MISMATCH");return row;
        }
        private MemberProof findRegisteredMaterialProof(RestoredLibraryMaterialStore.CommitProof proof)throws IOException {
            if(proof==null||!plan.transactionId.equals(proof.transactionId)||!plan.groupId.equals(proof.groupId))return null;
            for(MemberProof row:readProofs())if(matchesMaterialProof(row,proof))return row;return null;
        }
        private boolean isCommittedMaterial(RestoredLibraryMaterialStore.CommitProof proof)throws IOException {
            if(proof==null)return isCommittedUnderLease();
            if(!isCommittedUnderLease())return false;
            Map<String,Object> parsed=LibraryBackupJson.parseCheckedObject(read(new File(directory,"commit-marker.json")),MAX_BYTES);
            List<MemberProof> rows=parseProofs(parsed.get("proofs"));
            MemberProof target=null;for(MemberProof row:rows){Member member=find(plan,row.memberId);if(member==null||!matchesRequest(member,row))throw new IOException("RESTORE_V2_COMMIT_INVALID");if(matchesMaterialProof(row,proof))target=row;}
            if(target==null)return false;MemberProof same=findProof(readProofs(),target.memberId);if(!sameProof(target,same))throw new IOException("RESTORE_V2_COMMIT_INVALID");return true;
        }
        private boolean isCommittedUnderLease()throws IOException {
            File path=new File(directory,"committed.json");if(files.isAbsentNoFollow(path))return false;
            LibraryBackupArchive.FileIdentity dirId=files.inspect(path,true);File[] children=path.listFiles();
            if(children==null||children.length!=0)throw new IOException("RESTORE_V2_COMMIT_DIRECTORY_INVALID");
            File markerFile=new File(directory,"commit-marker.json"),reservation=new File(directory,"commit-reservation.json"),intent=new File(directory,"commit-directory-intent.json");
            if(files.isAbsentNoFollow(markerFile)||files.isAbsentNoFollow(reservation)||files.isAbsentNoFollow(intent))throw new IOException("RESTORE_V2_COMMIT_DIRECTORY_INVALID");
            byte[] marker=read(markerFile);validateReservation(reservation,marker);validateCommitDirectoryIntent(intent,marker);
            if(!dirId.equals(files.inspect(path,true)))throw new IOException("RESTORE_V2_COMMIT_DIRECTORY_CHANGED");
            Map<String,Object> parsed=LibraryBackupJson.parseCheckedObject(marker,MAX_BYTES);
            LibraryBackupJson.exactKeys(parsed,"RESTORE_V2_COMMIT_INVALID","schema_version","transaction_id","archive_sha256","source_group_key","group_id","proofs");
            if(LibraryBackupJson.integer(parsed.get("schema_version"),2,2,"RESTORE_V2_COMMIT_INVALID")!=2||
                    !plan.transactionId.equals(LibraryBackupJson.string(parsed.get("transaction_id"),"RESTORE_V2_COMMIT_INVALID"))||
                    !plan.archiveSha256.equals(LibraryBackupJson.string(parsed.get("archive_sha256"),"RESTORE_V2_COMMIT_INVALID"))||
                    !plan.sourceGroupKey.equals(LibraryBackupJson.string(parsed.get("source_group_key"),"RESTORE_V2_COMMIT_INVALID"))||
                    !plan.groupId.equals(LibraryBackupJson.string(parsed.get("group_id"),"RESTORE_V2_COMMIT_INVALID")))throw new IOException("RESTORE_V2_COMMIT_INVALID");
            List<MemberProof> recorded=parseProofs(parsed.get("proofs"));if(recorded.size()!=plan.members.size())throw new IOException("RESTORE_V2_COMMIT_INVALID");
            List<MemberProof> persisted=readProofs();if(persisted.size()!=recorded.size())throw new IOException("RESTORE_V2_COMMIT_INVALID");
            Map<String,MemberProof> by=new LinkedHashMap<>();for(MemberProof p:recorded)if(by.put(p.memberId,p)!=null)throw new IOException("RESTORE_V2_COMMIT_INVALID");
            for(Member m:plan.members){MemberProof p=by.get(m.memberId);if(p==null||!matchesRequest(m,p)||!sameProof(p,findProof(persisted,p.memberId)))throw new IOException("RESTORE_V2_COMMIT_INVALID");}
            return true;
        }
        private void readState(File path,String expected)throws IOException { Map<String,Object> s=LibraryBackupJson.parseCheckedObject(read(path),4096);LibraryBackupJson.exactKeys(s,"RESTORE_V2_STATE_INVALID","schema_version","transaction_id","group_id","source_group_key","state");if(LibraryBackupJson.integer(s.get("schema_version"),2,2,"RESTORE_V2_STATE_INVALID")!=2||!plan.transactionId.equals(LibraryBackupJson.string(s.get("transaction_id"),"RESTORE_V2_STATE_INVALID"))||!plan.groupId.equals(LibraryBackupJson.string(s.get("group_id"),"RESTORE_V2_STATE_INVALID"))||!plan.sourceGroupKey.equals(LibraryBackupJson.string(s.get("source_group_key"),"RESTORE_V2_STATE_INVALID"))||!expected.equals(LibraryBackupJson.string(s.get("state"),"RESTORE_V2_STATE_INVALID")))throw new IOException("RESTORE_V2_STATE_INVALID"); }
        private void validateReservation(File reservation,byte[] marker)throws IOException { Map<String,Object> r=LibraryBackupJson.parseCheckedObject(read(reservation),4096);LibraryBackupJson.exactKeys(r,"RESTORE_V2_RESERVATION_INVALID","schema_version","transaction_id","group_id","plan_sha256","marker_sha256");if(LibraryBackupJson.integer(r.get("schema_version"),2,2,"RESTORE_V2_RESERVATION_INVALID")!=2||!plan.transactionId.equals(LibraryBackupJson.string(r.get("transaction_id"),"RESTORE_V2_RESERVATION_INVALID"))||!plan.groupId.equals(LibraryBackupJson.string(r.get("group_id"),"RESTORE_V2_RESERVATION_INVALID"))||!sha256(encodePlan(plan)).equals(LibraryBackupJson.string(r.get("plan_sha256"),"RESTORE_V2_RESERVATION_INVALID"))||!sha256(marker).equals(LibraryBackupJson.string(r.get("marker_sha256"),"RESTORE_V2_RESERVATION_INVALID")))throw new IOException("RESTORE_V2_RESERVATION_INVALID"); }
        private byte[] commitDirectoryIntentBytes(byte[] marker)throws IOException {Map<String,Object> value=new java.util.TreeMap<>();value.put("schema_version",2);value.put("transaction_id",plan.transactionId);value.put("group_id",plan.groupId);value.put("plan_sha256",sha256(encodePlan(plan)));value.put("marker_sha256",sha256(marker));value.put("commit_point","exclusive-directory");return LibraryBackupJson.encode(value);}
        private void validateCommitDirectoryIntent(File intent,byte[] marker)throws IOException {byte[] actual=read(intent);if(!MessageDigest.isEqual(actual,commitDirectoryIntentBytes(marker)))throw new IOException("RESTORE_V2_COMMIT_INTENT_INVALID");Map<String,Object> value=LibraryBackupJson.parseCheckedObject(actual,4096);LibraryBackupJson.exactKeys(value,"RESTORE_V2_COMMIT_INTENT_INVALID","schema_version","transaction_id","group_id","plan_sha256","marker_sha256","commit_point");}
        private byte[] commitReservationBytes(byte[] marker)throws IOException {Map<String,Object> value=new java.util.TreeMap<>();value.put("schema_version",2);value.put("transaction_id",plan.transactionId);value.put("group_id",plan.groupId);value.put("plan_sha256",sha256(encodePlan(plan)));value.put("marker_sha256",sha256(marker));return LibraryBackupJson.encode(value);}
        private List<MemberProof> readProofs()throws IOException {
            File dir=new File(directory,"proofs");files.validatePrivateDirectory(dir);File[] children=dir.listFiles();if(children==null)throw new IOException("RESTORE_V2_PROOFS_UNREADABLE");
            Map<String,Member> expected=new LinkedHashMap<>();for(Member m:plan.members)expected.put("proof-"+m.memberId+".json",m);
            List<MemberProof> out=new ArrayList<>();for(File child:children){Member m=expected.remove(child.getName());if(m==null)throw new IOException("RESTORE_V2_PROOF_UNKNOWN");MemberProof proof=decodeProof(read(child));if(!m.memberId.equals(proof.memberId)||!matchesRequest(m,proof))throw new IOException("RESTORE_V2_PROOF_INVALID");out.add(proof);}
            return out;
        }
        private void requireOpen()throws IOException{if(closed)throw new IOException("RESTORE_V2_LEASE_CLOSED");}
        @Override public void close()throws IOException{if(closed)return;closed=true;lock.close();}
    }

    interface CommitGate { boolean isCommitted(RestoredLibraryMaterialStore.CommitProof proof) throws Exception; }

    /** Lock-free strict reader used by NoteStore/CoverStore after the lease is released. */
    static boolean isCommittedReference(File transactionRoot, String transactionId, String groupId,
            String memberId, String localObjectId, Kind kind, String payloadSha256,
            String semanticBindingSha256, String storageBindingSha256) throws IOException {
        return isCommittedReference(transactionRoot,transactionId,groupId,memberId,localObjectId,kind,payloadSha256,semanticBindingSha256,storageBindingSha256,new LibraryBackupArchive.AndroidSafeFiles());
    }
    static boolean isCommittedReference(File transactionRoot, String transactionId, String groupId,
            String memberId, String localObjectId, Kind kind, String payloadSha256,
            String semanticBindingSha256, String storageBindingSha256,LibraryBackupArchive.SafeFiles safe) throws IOException {
        requireUuid(transactionId,"RESTORE_V2_REFERENCE_INVALID");requireUuid(groupId,"RESTORE_V2_REFERENCE_INVALID");
        requireUuid(memberId,"RESTORE_V2_REFERENCE_INVALID");requireHash(payloadSha256,"RESTORE_V2_REFERENCE_INVALID");
        requireHash(semanticBindingSha256,"RESTORE_V2_REFERENCE_INVALID");requireHash(storageBindingSha256,"RESTORE_V2_REFERENCE_INVALID");
        File dir=new File(new File(new File(transactionRoot,transactionId),"groups"),groupId);
        safe.validatePrivateDirectory(dir);
        File planFile=new File(dir,"members.json");
        LibraryBackupArchive.FileIdentity id=safe.inspect(planFile,false);
        if(id.links!=1||id.size<0||id.size>MAX_BYTES)throw new IOException("RESTORE_V2_REFERENCE_INVALID");
        byte[] planBytes=readSafe(safe,planFile,id);
        Map<String,Object> planMap=LibraryBackupJson.parseCheckedObject(planBytes,MAX_BYTES);
        LibraryBackupJson.exactKeys(planMap,"RESTORE_V2_REFERENCE_INVALID","schema_version","transaction_id","archive_sha256","source_group_key","group_id","members");
        if(LibraryBackupJson.integer(planMap.get("schema_version"),2,2,"RESTORE_V2_REFERENCE_INVALID")!=2||!transactionId.equals(LibraryBackupJson.string(planMap.get("transaction_id"),"RESTORE_V2_REFERENCE_INVALID"))||!groupId.equals(LibraryBackupJson.string(planMap.get("group_id"),"RESTORE_V2_REFERENCE_INVALID")))throw new IOException("RESTORE_V2_REFERENCE_INVALID");
        String archive=LibraryBackupJson.string(planMap.get("archive_sha256"),"RESTORE_V2_REFERENCE_INVALID"),source=LibraryBackupJson.string(planMap.get("source_group_key"),"RESTORE_V2_REFERENCE_INVALID");requireHash(archive,"RESTORE_V2_REFERENCE_INVALID");validateSourceKey(source);
        Plan plan=new LibraryRestoreGroupV2(transactionRoot,safe).decodePlan(planBytes,transactionId,archive,source);
        File marker=new File(dir,"committed.json");if(safe.isAbsentNoFollow(marker))return false;
        LibraryBackupArchive.FileIdentity markerId=safe.inspect(marker,true);File[] markerChildren=marker.listFiles();
        if(markerChildren==null||markerChildren.length!=0)throw new IOException("RESTORE_V2_REFERENCE_INVALID");
        File reservation=new File(dir,"commit-reservation.json"),payload=new File(dir,"commit-marker.json"),intent=new File(dir,"commit-directory-intent.json");
        if(safe.isAbsentNoFollow(reservation)||safe.isAbsentNoFollow(payload)||safe.isAbsentNoFollow(intent))throw new IOException("RESTORE_V2_REFERENCE_INVALID");
        LibraryBackupArchive.FileIdentity payloadId=safe.inspect(payload,false);if(payloadId.links!=1||payloadId.size<0||payloadId.size>MAX_BYTES)throw new IOException("RESTORE_V2_REFERENCE_INVALID");
        byte[] markerBytes=readSafe(safe,payload,payloadId);LibraryRestoreGroupV2 sentinelParser=new LibraryRestoreGroupV2(transactionRoot,safe);
        validateStaticReservation(safe,reservation,intent,plan,markerBytes,sentinelParser);
        Map<String,Object> data=LibraryBackupJson.parseCheckedObject(markerBytes,MAX_BYTES);
        LibraryBackupJson.exactKeys(data,"RESTORE_V2_REFERENCE_INVALID","schema_version","transaction_id","archive_sha256","source_group_key","group_id","proofs");
        if(LibraryBackupJson.integer(data.get("schema_version"),2,2,"RESTORE_V2_REFERENCE_INVALID")!=2||!transactionId.equals(LibraryBackupJson.string(data.get("transaction_id"),"RESTORE_V2_REFERENCE_INVALID"))||!archive.equals(LibraryBackupJson.string(data.get("archive_sha256"),"RESTORE_V2_REFERENCE_INVALID"))||!source.equals(LibraryBackupJson.string(data.get("source_group_key"),"RESTORE_V2_REFERENCE_INVALID"))||!groupId.equals(LibraryBackupJson.string(data.get("group_id"),"RESTORE_V2_REFERENCE_INVALID")))throw new IOException("RESTORE_V2_REFERENCE_INVALID");
        List<MemberProof> proofs=new LibraryRestoreGroupV2(transactionRoot,safe).parseProofs(data.get("proofs"));if(proofs.size()!=plan.members.size())throw new IOException("RESTORE_V2_REFERENCE_INVALID");
        Map<String,MemberProof> by=new LinkedHashMap<>();for(MemberProof proof:proofs)if(by.put(proof.memberId,proof)!=null)throw new IOException("RESTORE_V2_REFERENCE_INVALID");
        MemberProof found=null;for(Member member:plan.members){MemberProof proof=by.get(member.memberId);if(proof==null||!matchesRequest(member,proof))throw new IOException("RESTORE_V2_REFERENCE_INVALID");if(member.memberId.equals(memberId))found=proof;}
        File proofDir=new File(dir,"proofs");safe.validatePrivateDirectory(proofDir);File[] proofFiles=proofDir.listFiles();if(proofFiles==null||proofFiles.length!=plan.members.size())throw new IOException("RESTORE_V2_REFERENCE_INVALID");
        Map<String,MemberProof> stored=new LinkedHashMap<>();LibraryRestoreGroupV2 parser=new LibraryRestoreGroupV2(transactionRoot,safe);
        for(File proofFile:proofFiles){String name=proofFile.getName();if(!name.matches("proof-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json"))throw new IOException("RESTORE_V2_REFERENCE_INVALID");LibraryBackupArchive.FileIdentity pi=safe.inspect(proofFile,false);if(pi.links!=1||pi.size<0||pi.size>MAX_BYTES)throw new IOException("RESTORE_V2_REFERENCE_INVALID");MemberProof row=parser.decodeProof(readSafe(safe,proofFile,pi));if(!name.equals("proof-"+row.memberId+".json")||stored.put(row.memberId,row)!=null||!sameProof(row,by.get(row.memberId)))throw new IOException("RESTORE_V2_REFERENCE_INVALID");}
        if(found==null)return false;
        if(!found.localObjectId.equals(localObjectId)||found.kind!=kind||!found.payloadSha256.equals(payloadSha256)||!found.semanticBindingSha256.equals(semanticBindingSha256)||!found.storageBindingSha256.equals(storageBindingSha256))return false;
        return sameFileIdentity(safe,planFile,id)&&markerId.equals(safe.inspect(marker,true))&&sameFileIdentity(safe,payload,payloadId);
    }

    /** Finds a material member by its immutable store proof, then applies the same full group check. */
    static boolean isCommittedMaterialReference(File transactionRoot, RestoredLibraryMaterialStore.CommitProof proof) throws IOException {
        if(proof==null||proof.kind==null||proof.transactionId==null||proof.groupId==null)throw new IOException("RESTORE_V2_REFERENCE_INVALID");
        requireUuid(proof.transactionId,"RESTORE_V2_REFERENCE_INVALID");requireUuid(proof.groupId,"RESTORE_V2_REFERENCE_INVALID");
        File dir=new File(new File(new File(transactionRoot,proof.transactionId),"groups"),proof.groupId);
        LibraryBackupArchive.SafeFiles safe=new LibraryBackupArchive.AndroidSafeFiles();if(safe.isAbsentNoFollow(dir))return false;safe.validatePrivateDirectory(dir);
        if(safe.isAbsentNoFollow(new File(dir,"committed.json")))return false;
        File planFile=new File(dir,"members.json");LibraryBackupArchive.FileIdentity id=safe.inspect(planFile,false);
        if(id.links!=1||id.size<0||id.size>MAX_BYTES)throw new IOException("RESTORE_V2_REFERENCE_INVALID");
        byte[] raw=readSafe(safe,planFile,id);Map<String,Object> plan=LibraryBackupJson.parseCheckedObject(raw,MAX_BYTES);
        LibraryBackupJson.exactKeys(plan,"RESTORE_V2_REFERENCE_INVALID","schema_version","transaction_id","archive_sha256","source_group_key","group_id","members");
        String archive=LibraryBackupJson.string(plan.get("archive_sha256"),"RESTORE_V2_REFERENCE_INVALID"),key=LibraryBackupJson.string(plan.get("source_group_key"),"RESTORE_V2_REFERENCE_INVALID");
        Plan decoded=new LibraryRestoreGroupV2(transactionRoot,safe).decodePlan(raw,proof.transactionId,archive,key);
        for(Member member:decoded.members)if(member.localObjectId.equals(proof.localId)&&member.kind.name().equals(proof.kind.name())){
            if(!java.util.Objects.equals(member.currentLocalNoteId,proof.currentLocalNoteId))return false;
            return isCommittedReference(transactionRoot,proof.transactionId,proof.groupId,member.memberId,
                    proof.localId,member.kind,proof.payloadSha256,member.semanticBindingSha256,proof.bindingSha256);
        }
        return false;
    }

    private static byte[] readSafe(LibraryBackupArchive.SafeFiles safe,File path,LibraryBackupArchive.FileIdentity before)throws IOException {
        byte[] out=new byte[(int)before.size];try(LibraryBackupArchive.Seekable input=safe.openRead(path)){if(!before.equals(input.openedIdentity())||input.size()!=before.size)throw new IOException("RESTORE_V2_FILE_CHANGED");input.readFully(0,out,0,out.length);if(!before.equals(input.openedIdentity())||!before.equals(input.currentPathIdentity()))throw new IOException("RESTORE_V2_FILE_CHANGED");}return out;
    }
    private static boolean sameFileIdentity(LibraryBackupArchive.SafeFiles safe,File path,LibraryBackupArchive.FileIdentity expected)throws IOException{return expected.equals(safe.inspect(path,false));}

    private static void validateStaticReservation(LibraryBackupArchive.SafeFiles safe,File reservation,File intent,Plan plan,byte[] marker,LibraryRestoreGroupV2 parser)throws IOException {
        LibraryBackupArchive.FileIdentity ri=safe.inspect(reservation,false),ii=safe.inspect(intent,false);
        if(ri.links!=1||ri.size<0||ri.size>4096||ii.links!=1||ii.size<0||ii.size>4096)throw new IOException("RESTORE_V2_REFERENCE_INVALID");
        String planHash=sha256(parser.encodePlan(plan)),markerHash=sha256(marker);
        Map<String,Object> expectedReservation=new java.util.TreeMap<>();expectedReservation.put("schema_version",2);expectedReservation.put("transaction_id",plan.transactionId);expectedReservation.put("group_id",plan.groupId);expectedReservation.put("plan_sha256",planHash);expectedReservation.put("marker_sha256",markerHash);
        Map<String,Object> expectedIntent=new java.util.TreeMap<>();expectedIntent.put("schema_version",2);expectedIntent.put("transaction_id",plan.transactionId);expectedIntent.put("group_id",plan.groupId);expectedIntent.put("plan_sha256",planHash);expectedIntent.put("marker_sha256",markerHash);expectedIntent.put("commit_point","exclusive-directory");
        if(!MessageDigest.isEqual(LibraryBackupJson.encode(expectedReservation),readSafe(safe,reservation,ri))||!MessageDigest.isEqual(LibraryBackupJson.encode(expectedIntent),readSafe(safe,intent,ii)))throw new IOException("RESTORE_V2_REFERENCE_INVALID");
    }

    private File groupDir(Plan p){return new File(new File(transactionRoot,p.transactionId),"groups/"+p.groupId);}
    private File proofPath(File dir,String member){return new File(new File(dir,"proofs"),"proof-"+member+".json");}
    private void ensureDirectory(File path)throws IOException {
        if(path==null)throw new IOException("RESTORE_V2_PATH_INVALID");File absolute=path.getAbsoluteFile(),parent=absolute.getParentFile();
        if(AndroidFileCompat.normalizeAbsolute(absolute).equals(AndroidFileCompat.normalizeAbsolute(ownedRoot))){files.validatePrivateDirectory(ownedRoot);return;}
        if(isAbsentWithinOwnedRoot(absolute,ownedRoot)){if(parent!=null&&!parent.equals(absolute))ensureDirectory(parent);files.mkdirExclusive(absolute);}
        files.validatePrivateDirectory(absolute);if(parent!=null&&!parent.equals(absolute))files.syncDirectory(parent);
    }
    /** Missing paths are absent only when every existing ancestor is beneath a validated private root. */
    private boolean isAbsentWithinOwnedRoot(File path,File root)throws IOException {
        List<String> relative;
        try { relative=AndroidFileCompat.descendantComponents(root,path); }
        catch(IOException e) { throw new IOException("RESTORE_V2_PATH_INVALID"); }
        files.validatePrivateDirectory(root);
        File cursor=new File(AndroidFileCompat.normalizeAbsolute(root));
        for(int i=0;i<relative.size();i++){
            cursor=new File(cursor,relative.get(i));
            if(files.isAbsentNoFollow(cursor))return true;
            if(i<relative.size()-1)files.validatePrivateDirectory(cursor);
        }
        return false;
    }
    private void writeNew(File path,byte[] bytes)throws IOException {
        try(FileOutputStream out=files.createExclusive(path)){out.write(bytes);out.flush();out.getFD().sync();}
        files.syncDirectory(path.getAbsoluteFile().getParentFile());
    }
    private void syncDurable(File path)throws IOException {
        LibraryBackupArchive.FileIdentity identity=files.inspect(path,false);if(identity.links!=1)throw new IOException("RESTORE_V2_FILE_UNSAFE");
        files.syncExistingFile(path,identity);if(!identity.equals(files.inspect(path,false)))throw new IOException("RESTORE_V2_FILE_CHANGED");files.syncDirectory(path.getAbsoluteFile().getParentFile());
    }
    private byte[] read(File path)throws IOException {
        LibraryBackupArchive.FileIdentity before=files.inspect(path,false);if(before.links!=1||before.size<0||before.size>MAX_BYTES)throw new IOException("RESTORE_V2_FILE_UNSAFE");
        byte[] bytes=new byte[(int)before.size];try(LibraryBackupArchive.Seekable in=files.openRead(path)){if(!before.equals(in.openedIdentity())||in.size()!=before.size)throw new IOException("RESTORE_V2_FILE_CHANGED");in.readFully(0,bytes,0,bytes.length);if(!before.equals(in.openedIdentity())||!before.equals(in.currentPathIdentity()))throw new IOException("RESTORE_V2_FILE_CHANGED");}return bytes;
    }
    private Plan decodePlan(byte[] raw,String tx,String archive,String key)throws IOException {
        Map<String,Object> m=LibraryBackupJson.parseCheckedObject(raw,MAX_BYTES);LibraryBackupJson.exactKeys(m,"RESTORE_V2_PLAN_INVALID","schema_version","transaction_id","archive_sha256","source_group_key","group_id","members");
        if(LibraryBackupJson.integer(m.get("schema_version"),2,2,"RESTORE_V2_PLAN_INVALID")!=2||!tx.equals(LibraryBackupJson.string(m.get("transaction_id"),"RESTORE_V2_PLAN_INVALID"))||!archive.equals(LibraryBackupJson.string(m.get("archive_sha256"),"RESTORE_V2_PLAN_INVALID"))||!key.equals(LibraryBackupJson.string(m.get("source_group_key"),"RESTORE_V2_PLAN_INVALID")))throw new IOException("RESTORE_V2_PLAN_INVALID");
        String group=LibraryBackupJson.string(m.get("group_id"),"RESTORE_V2_PLAN_INVALID");requireUuid(group,"RESTORE_V2_PLAN_INVALID");List<Member> members=new ArrayList<>();
        for(Object row:LibraryBackupJson.array(m.get("members"),"RESTORE_V2_PLAN_INVALID")){Map<String,Object> x=LibraryBackupJson.object(row,"RESTORE_V2_PLAN_INVALID");LibraryBackupJson.exactKeys(x,"RESTORE_V2_PLAN_INVALID","member_id","source_item_id","source_resource_id","source_note_item_id","local_object_id","current_local_note_id","kind","byte_length","payload_sha256","semantic_binding_sha256");
            String memberId=LibraryBackupJson.string(x.get("member_id"),"RESTORE_V2_PLAN_INVALID"),source=LibraryBackupJson.string(x.get("source_item_id"),"RESTORE_V2_PLAN_INVALID"),resource=LibraryBackupJson.string(x.get("source_resource_id"),"RESTORE_V2_PLAN_INVALID"),local=LibraryBackupJson.string(x.get("local_object_id"),"RESTORE_V2_PLAN_INVALID"),kindName=LibraryBackupJson.string(x.get("kind"),"RESTORE_V2_PLAN_INVALID");requireUuid(memberId,"RESTORE_V2_PLAN_INVALID");requireSourceItem(source);if(!resource.matches("r-[0-9a-f]{32}"))throw new IOException("RESTORE_V2_PLAN_INVALID");Kind kind;try{kind=Kind.valueOf(kindName);}catch(Exception e){throw new IOException("RESTORE_V2_PLAN_INVALID");}
            String sourceNote=nullable(x.get("source_note_item_id")),current=nullable(x.get("current_local_note_id"));long size=LibraryBackupJson.integer(x.get("byte_length"),0,100L*1024*1024,"RESTORE_V2_PLAN_INVALID");String sha=LibraryBackupJson.string(x.get("payload_sha256"),"RESTORE_V2_PLAN_INVALID"),binding=LibraryBackupJson.string(x.get("semantic_binding_sha256"),"RESTORE_V2_PLAN_INVALID");requireHash(sha,"RESTORE_V2_PLAN_INVALID");requireHash(binding,"RESTORE_V2_PLAN_INVALID");
            Request r=new Request(source,resource,sourceNote,kind,size,sha,binding);members.add(new Member(memberId,r,local,current));}
        validateMembers(members);return new Plan(tx,archive,key,group,members);
    }
    private byte[] encodePlan(Plan p)throws IOException {
        List<Object> rows=new ArrayList<>();for(Member m:p.members){Map<String,Object> x=new java.util.TreeMap<>();x.put("member_id",m.memberId);x.put("source_item_id",m.sourceItemId);x.put("source_resource_id",m.sourceResourceId);x.put("source_note_item_id",m.sourceNoteItemId);x.put("local_object_id",m.localObjectId);x.put("current_local_note_id",m.currentLocalNoteId);x.put("kind",m.kind.name());x.put("byte_length",m.byteLength);x.put("payload_sha256",m.payloadSha256);x.put("semantic_binding_sha256",m.semanticBindingSha256);rows.add(x);}
        Map<String,Object> root=new java.util.TreeMap<>();root.put("schema_version",2);root.put("transaction_id",p.transactionId);root.put("archive_sha256",p.archiveSha256);root.put("source_group_key",p.sourceGroupKey);root.put("group_id",p.groupId);root.put("members",rows);return LibraryBackupJson.encode(root);
    }
    private byte[] encodeProof(MemberProof p)throws IOException {
        Map<String,Object> m=new java.util.TreeMap<>();m.put("schema_version",2);m.put("member_id",p.memberId);m.put("transaction_id",p.transactionId);m.put("group_id",p.groupId);m.put("local_object_id",p.localObjectId);m.put("current_local_note_id",p.currentLocalNoteId);m.put("kind",p.kind.name());m.put("payload_sha256",p.payloadSha256);m.put("semantic_binding_sha256",p.semanticBindingSha256);m.put("storage_binding_sha256",p.storageBindingSha256);return LibraryBackupJson.encode(m);
    }
    private MemberProof decodeProof(byte[] bytes)throws IOException {
        Map<String,Object> m=LibraryBackupJson.parseCheckedObject(bytes,MAX_BYTES);LibraryBackupJson.exactKeys(m,"RESTORE_V2_PROOF_INVALID","schema_version","member_id","transaction_id","group_id","local_object_id","current_local_note_id","kind","payload_sha256","semantic_binding_sha256","storage_binding_sha256");
        if(LibraryBackupJson.integer(m.get("schema_version"),2,2,"RESTORE_V2_PROOF_INVALID")!=2)throw new IOException("RESTORE_V2_PROOF_INVALID");String member=LibraryBackupJson.string(m.get("member_id"),"RESTORE_V2_PROOF_INVALID"),tx=LibraryBackupJson.string(m.get("transaction_id"),"RESTORE_V2_PROOF_INVALID"),group=LibraryBackupJson.string(m.get("group_id"),"RESTORE_V2_PROOF_INVALID"),local=LibraryBackupJson.string(m.get("local_object_id"),"RESTORE_V2_PROOF_INVALID");requireUuid(member,"RESTORE_V2_PROOF_INVALID");requireUuid(tx,"RESTORE_V2_PROOF_INVALID");requireUuid(group,"RESTORE_V2_PROOF_INVALID");Object n=m.get("current_local_note_id");String note=n==null?null:LibraryBackupJson.string(n,"RESTORE_V2_PROOF_INVALID");Kind kind;try{kind=Kind.valueOf(LibraryBackupJson.string(m.get("kind"),"RESTORE_V2_PROOF_INVALID"));}catch(Exception e){throw new IOException("RESTORE_V2_PROOF_INVALID");}String payload=LibraryBackupJson.string(m.get("payload_sha256"),"RESTORE_V2_PROOF_INVALID"),semantic=LibraryBackupJson.string(m.get("semantic_binding_sha256"),"RESTORE_V2_PROOF_INVALID"),storage=LibraryBackupJson.string(m.get("storage_binding_sha256"),"RESTORE_V2_PROOF_INVALID");requireHash(payload,"RESTORE_V2_PROOF_INVALID");requireHash(semantic,"RESTORE_V2_PROOF_INVALID");requireHash(storage,"RESTORE_V2_PROOF_INVALID");return new MemberProof(member,tx,group,local,note,kind,payload,semantic,storage);
    }
    private byte[] encodeCommit(Plan p,List<MemberProof> proofs)throws IOException {
        List<MemberProof> ordered=new ArrayList<>(proofs);ordered.sort(Comparator.comparing(proof->proof.memberId));
        List<Object> rows=new ArrayList<>();for(MemberProof proof:ordered)rows.add(LibraryBackupJson.parse(encodeProof(proof),MAX_BYTES));Map<String,Object> root=new java.util.TreeMap<>();root.put("schema_version",2);root.put("transaction_id",p.transactionId);root.put("archive_sha256",p.archiveSha256);root.put("source_group_key",p.sourceGroupKey);root.put("group_id",p.groupId);root.put("proofs",rows);return LibraryBackupJson.encode(root);
    }
    private byte[] stateBytes(Plan p,String state)throws IOException {Map<String,Object> m=new java.util.TreeMap<>();m.put("schema_version",2);m.put("transaction_id",p.transactionId);m.put("group_id",p.groupId);m.put("source_group_key",p.sourceGroupKey);m.put("state",state);return LibraryBackupJson.encode(m);}
    private List<MemberProof> parseProofs(Object raw)throws IOException {List<MemberProof> out=new ArrayList<>();for(Object value:LibraryBackupJson.array(raw,"RESTORE_V2_COMMIT_INVALID"))out.add(decodeProof(LibraryBackupJson.encode(value)));return out;}
    private static List<Request> validateAndSort(List<Request> requests)throws IOException {if(requests==null||requests.isEmpty()||requests.size()>4096)throw new IOException("RESTORE_V2_MEMBERS_INVALID");List<Request> rows=new ArrayList<>(requests);for(Request r:rows){if(r==null||r.kind==null)throw new IOException("RESTORE_V2_MEMBER_INVALID");requireSourceItem(r.sourceItemId);if(r.sourceResourceId==null||!r.sourceResourceId.matches("r-[0-9a-f]{32}"))throw new IOException("RESTORE_V2_MEMBER_INVALID");if(r.byteLength<0||r.byteLength>100L*1024*1024)throw new IOException("RESTORE_V2_MEMBER_INVALID");requireHash(r.payloadSha256,"RESTORE_V2_MEMBER_INVALID");requireHash(r.semanticBindingSha256,"RESTORE_V2_MEMBER_INVALID");if(r.sourceNoteItemId!=null&&!r.sourceNoteItemId.matches("i-[0-9a-f]{32}"))throw new IOException("RESTORE_V2_MEMBER_INVALID");}rows.sort(Comparator.comparing((Request r)->r.sourceItemId).thenComparing(r->r.kind.name()).thenComparing(r->r.sourceResourceId));for(int i=1;i<rows.size();i++)if(rows.get(i-1).sourceItemId.equals(rows.get(i).sourceItemId)&&rows.get(i-1).kind==rows.get(i).kind)throw new IOException("RESTORE_V2_MEMBER_DUPLICATE");return rows;}
    private static void validateMembers(List<Member> rows)throws IOException {
        if(rows.isEmpty())throw new IOException("RESTORE_V2_MEMBERS_INVALID");
        java.util.HashSet<String> ids=new java.util.HashSet<>(),pairs=new java.util.HashSet<>();Map<String,Member> notes=new LinkedHashMap<>();
        for(Member m:rows){
            if(!ids.add(m.memberId)||!pairs.add(m.sourceItemId+"|"+m.kind)||m.localObjectId==null||m.localObjectId.length()>160)throw new IOException("RESTORE_V2_MEMBERS_INVALID");
            if(m.kind==Kind.NOTE){if(!m.localObjectId.matches("note-[0-9a-f]{32}")||!m.localObjectId.equals(m.currentLocalNoteId)||notes.put(m.sourceItemId,m)!=null)throw new IOException("RESTORE_V2_MEMBERS_INVALID");}
            else if(m.kind==Kind.PDF||m.kind==Kind.ASSIGNED_COVER){if(!m.localObjectId.matches("note-[0-9a-f]{32}"))throw new IOException("RESTORE_V2_MEMBERS_INVALID");}
            else {requireUuid(m.localObjectId,"RESTORE_V2_MEMBERS_INVALID");}
        }
        for(Member m:rows){
            if(m.sourceNoteItemId!=null){Member note=notes.get(m.sourceNoteItemId);if(note==null||!note.localObjectId.equals(m.currentLocalNoteId))throw new IOException("RESTORE_V2_LINK_TARGET_INVALID");}
            if((m.kind==Kind.PDF||m.kind==Kind.ASSIGNED_COVER)&&m.sourceNoteItemId==null)throw new IOException("RESTORE_V2_LINK_TARGET_INVALID");
            if(m.kind==Kind.NOTE&&m.sourceNoteItemId!=null)throw new IOException("RESTORE_V2_MEMBER_INVALID");
        }
    }
    private static boolean sameRequests(Plan p,List<Request> req){if(p.members.size()!=req.size())return false;for(int i=0;i<req.size();i++){Member m=p.members.get(i);Request r=req.get(i);if(!m.sourceItemId.equals(r.sourceItemId)||!m.sourceResourceId.equals(r.sourceResourceId)||!java.util.Objects.equals(m.sourceNoteItemId,r.sourceNoteItemId)||m.kind!=r.kind||m.byteLength!=r.byteLength||!m.payloadSha256.equals(r.payloadSha256)||!m.semanticBindingSha256.equals(r.semanticBindingSha256)||(r.requestedLocalObjectId!=null&&!m.localObjectId.equals(r.requestedLocalObjectId)))return false;}return true;}
    private static boolean samePlan(Plan a,Plan b)throws IOException{return MessageDigest.isEqual(encodeStatic(a),encodeStatic(b));}
    private static byte[] encodeStatic(Plan p)throws IOException {List<Object> rows=new ArrayList<>();for(Member m:p.members){Map<String,Object>x=new java.util.TreeMap<>();x.put("member_id",m.memberId);x.put("source_item_id",m.sourceItemId);x.put("source_resource_id",m.sourceResourceId);x.put("source_note_item_id",m.sourceNoteItemId);x.put("local_object_id",m.localObjectId);x.put("current_local_note_id",m.currentLocalNoteId);x.put("kind",m.kind.name());x.put("byte_length",m.byteLength);x.put("payload_sha256",m.payloadSha256);x.put("semantic_binding_sha256",m.semanticBindingSha256);rows.add(x);}Map<String,Object>x=new java.util.TreeMap<>();x.put("schema_version",2);x.put("transaction_id",p.transactionId);x.put("archive_sha256",p.archiveSha256);x.put("source_group_key",p.sourceGroupKey);x.put("group_id",p.groupId);x.put("members",rows);return LibraryBackupJson.encode(x);}
    private static Member find(Plan p,String id){for(Member m:p.members)if(m.memberId.equals(id))return m;return null;}
    private static MemberProof findProof(List<MemberProof> list,String id){for(MemberProof p:list)if(p.memberId.equals(id))return p;return null;}
    private static boolean matchesRequest(Member m,MemberProof p){return m.memberId.equals(p.memberId)&&m.kind==p.kind&&m.localObjectId.equals(p.localObjectId)&&java.util.Objects.equals(m.currentLocalNoteId,p.currentLocalNoteId)&&m.payloadSha256.equals(p.payloadSha256)&&m.semanticBindingSha256.equals(p.semanticBindingSha256);}
    private static boolean matchesMaterialProof(MemberProof p,RestoredLibraryMaterialStore.CommitProof proof){return p.transactionId.equals(proof.transactionId)&&p.groupId.equals(proof.groupId)&&p.localObjectId.equals(proof.localId)&&p.kind.name().equals(proof.kind.name())&&p.payloadSha256.equals(proof.payloadSha256)&&p.storageBindingSha256.equals(proof.bindingSha256)&&java.util.Objects.equals(p.currentLocalNoteId,proof.currentLocalNoteId);}
    private static boolean sameProof(MemberProof a,MemberProof b){return a!=null&&b!=null&&a.memberId.equals(b.memberId)&&a.transactionId.equals(b.transactionId)&&a.groupId.equals(b.groupId)&&a.localObjectId.equals(b.localObjectId)&&java.util.Objects.equals(a.currentLocalNoteId,b.currentLocalNoteId)&&a.kind==b.kind&&a.payloadSha256.equals(b.payloadSha256)&&a.semanticBindingSha256.equals(b.semanticBindingSha256)&&a.storageBindingSha256.equals(b.storageBindingSha256);}
    private static boolean sameMaterialProof(RestoredLibraryMaterialStore.CommitProof a,RestoredLibraryMaterialStore.CommitProof b){return a!=null&&b!=null&&a.transactionId.equals(b.transactionId)&&a.groupId.equals(b.groupId)&&a.localId.equals(b.localId)&&a.kind==b.kind&&a.payloadSha256.equals(b.payloadSha256)&&a.bindingSha256.equals(b.bindingSha256)&&java.util.Objects.equals(a.currentLocalNoteId,b.currentLocalNoteId);}
    private static void requireSourceItem(String s)throws IOException{if(s==null||!s.matches("[ivac]-[0-9a-f]{32}"))throw new IOException("RESTORE_V2_SOURCE_ITEM_INVALID");}
    private static void validateSourceKey(String s)throws IOException{if(s==null||s.isEmpty()||s.length()>256||!s.matches("[A-Za-z0-9:_-]+"))throw new IOException("RESTORE_V2_SOURCE_GROUP_INVALID");}
    private static void requireUuid(String s,String code)throws IOException{try{if(s==null||!UUID.fromString(s).toString().equals(s))throw new IOException(code);}catch(IllegalArgumentException e){throw new IOException(code);}}
    private static void requireHash(String s,String code)throws IOException{if(s==null||!s.matches("[0-9a-f]{64}"))throw new IOException(code);}
    private static String nullable(Object value)throws IOException{if(value==null)return null;if(!(value instanceof String))throw new IOException("RESTORE_V2_PLAN_INVALID");return(String)value;}
    private static String sha256(byte[] bytes){try{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}
