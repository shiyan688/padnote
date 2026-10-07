package com.padnote.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Isolated Stage B journal adapter. A material-only group marker is the visibility point. */
final class LibraryMaterialGroupJournal {
    static final class MemberRequest {
        final String sourceItemId;
        final RestoredLibraryMaterialStore.Kind kind;
        final String currentLocalNoteId;
        MemberRequest(String sourceItemId,RestoredLibraryMaterialStore.Kind kind,String currentLocalNoteId) {
            this.sourceItemId=sourceItemId;this.kind=kind;this.currentLocalNoteId=currentLocalNoteId;
        }
    }
    static final class Member {
        final String sourceItemId,localId,currentLocalNoteId;
        final RestoredLibraryMaterialStore.Kind kind;
        Member(String item,String id,String note,RestoredLibraryMaterialStore.Kind kind) {
            sourceItemId=item;localId=id;currentLocalNoteId=note;this.kind=kind;
        }
    }
    static final class GroupPlan {
        final String transactionId,sourceGroupKey,groupId;
        final List<Member> members;
        private GroupPlan(String tx,String key,String group,List<Member> items) {
            transactionId=tx;sourceGroupKey=key;groupId=group;
            members=Collections.unmodifiableList(new ArrayList<>(items));
        }
        Member memberForSourceItem(String item) throws IOException {
            for(Member member:members)if(member.sourceItemId.equals(item))return member;
            throw new IOException("MATERIAL_GROUP_MEMBER_UNKNOWN");
        }
    }

    private static final int MAX_JOURNAL_BYTES=1024*1024;
    private final File root;
    private final LibraryBackupArchive.SafeFiles files;
    private final RestoredLibraryMaterialStore store;

    LibraryMaterialGroupJournal(File root,LibraryBackupArchive.SafeFiles files,RestoredLibraryMaterialStore store) {
        if(root==null||files==null||store==null)throw new IllegalArgumentException("MATERIAL_GROUP_CONFIG");
        this.root=root;this.files=files;this.store=store;
    }

    /** Persists stable group/member UUIDs before callers invoke prepare or promote. */
    synchronized GroupPlan ensurePendingGroup(String transactionId,String sourceGroupKey,List<MemberRequest> requests)throws IOException {
        requireUuid(transactionId,"MATERIAL_TRANSACTION");validateSourceKey(sourceGroupKey);
        List<MemberRequest> sorted=validateAndSort(requests);
        File txDir=new File(root,transactionId);ensureDirectory(root);ensureDirectory(txDir);
        String keyHash=sha256(sourceGroupKey.getBytes(StandardCharsets.UTF_8));
        File mapPath=new File(txDir,"source-"+keyHash+".json");
        try(LibraryBackupArchive.SafeFiles.PublishLock ignored=files.lockPublish(mapPath)) {
            GroupPlan plan;
            if(!files.isAbsentNoFollow(mapPath)) {
                plan=decodePlan(readBounded(mapPath),transactionId,sourceGroupKey);
                if(!sameRequests(plan,sorted))throw new IOException("MATERIAL_GROUP_REQUEST_CHANGED");
                syncDurable(mapPath);
            } else {
                String groupId=UUID.randomUUID().toString();List<Member> members=new ArrayList<>();
                for(MemberRequest request:sorted)members.add(new Member(request.sourceItemId,UUID.randomUUID().toString(),request.currentLocalNoteId,request.kind));
                plan=new GroupPlan(transactionId,sourceGroupKey,groupId,members);
                writeNew(mapPath,encodePlan(plan));
            }
            File groupDir=groupDirectory(plan);ensureDirectory(groupDir);ensureDirectory(proofDirectory(plan));
            File membership=new File(groupDir,"members.json");byte[] expected=encodeMembership(plan);
            if(files.isAbsentNoFollow(membership))writeNew(membership,expected);
            else { if(!MessageDigest.isEqual(expected,readBounded(membership)))throw new IOException("MATERIAL_GROUP_MEMBERSHIP_CHANGED");syncDurable(membership); }
            files.syncDirectory(groupDir);files.syncDirectory(txDir);
            return plan;
        }
    }

    GroupLease acquire(GroupPlan plan)throws IOException {
        if(plan==null)throw new IOException("MATERIAL_GROUP_REQUIRED");
        return acquire(plan.transactionId,plan.groupId);
    }

    GroupLease acquire(String transactionId,String groupId)throws IOException {
        requireUuid(transactionId,"MATERIAL_TRANSACTION");requireUuid(groupId,"MATERIAL_GROUP");
        File txDir=new File(root,transactionId),groupDir=new File(new File(txDir,"groups"),groupId);
        files.validatePrivateDirectory(groupDir);
        File lockTarget=new File(groupDir,"group-lock");
        LibraryBackupArchive.SafeFiles.PublishLock lock=files.lockPublish(lockTarget);
        try {
            GroupPlan plan=readMembership(new File(groupDir,"members.json"),transactionId,groupId);
            File mapping=new File(txDir,"source-"+sha256(plan.sourceGroupKey.getBytes(StandardCharsets.UTF_8))+".json");
            if(files.isAbsentNoFollow(mapping))throw new IOException("MATERIAL_GROUP_MAPPING_MISSING");
            GroupPlan mapped=decodePlan(readBounded(mapping),transactionId,plan.sourceGroupKey);
            if(!MessageDigest.isEqual(encodePlan(mapped),encodePlan(plan)))throw new IOException("MATERIAL_GROUP_MAPPING_MISMATCH");
            return new GroupLease(plan,groupDir,lock);
        } catch(Exception failure) {
            try{lock.close();}catch(IOException ignored){}
            if(failure instanceof IOException)throw (IOException)failure;
            throw new IOException("MATERIAL_GROUP_JOURNAL_INVALID");
        }
    }

    private File groupDirectory(GroupPlan plan)throws IOException {
        File txDir=new File(root,plan.transactionId),groups=new File(txDir,"groups"),group=new File(groups,plan.groupId);
        ensureDirectory(groups);return group;
    }
    private File proofDirectory(GroupPlan plan){return new File(groupDirectoryPath(plan),"proofs");}
    private File groupDirectoryPath(GroupPlan plan){return new File(new File(new File(root,plan.transactionId),"groups"),plan.groupId);}
    private void ensureDirectory(File path)throws IOException {
        if(path==null)throw new IOException("MATERIAL_GROUP_DIRECTORY_INVALID");
        File absolute=path.getAbsoluteFile(),parent=absolute.getParentFile();
        if(files.isAbsentNoFollow(absolute)) {
            if(parent!=null&&!parent.equals(absolute))ensureDirectory(parent);
            files.mkdirExclusive(absolute);
        }
        files.validatePrivateDirectory(absolute);
        if(parent!=null&&!parent.equals(absolute))files.syncDirectory(parent);
    }
    private void writeNew(File path,byte[] bytes)throws IOException {
        try(FileOutputStream out=files.createExclusive(path)){out.write(bytes);out.flush();out.getFD().sync();}
        files.syncDirectory(path.getAbsoluteFile().getParentFile());
    }
    private void syncDurable(File path)throws IOException {
        LibraryBackupArchive.FileIdentity identity=files.inspect(path,false);
        if(identity.links!=1)throw new IOException("MATERIAL_GROUP_JOURNAL_INVALID");
        files.syncExistingFile(path,identity);
        if(!identity.equals(files.inspect(path,false)))throw new IOException("MATERIAL_GROUP_JOURNAL_CHANGED");
        files.syncDirectory(path.getAbsoluteFile().getParentFile());
    }
    private byte[] readBounded(File path)throws IOException {
        LibraryBackupArchive.FileIdentity before=files.inspect(path,false);
        if(before.links!=1||before.size<0||before.size>MAX_JOURNAL_BYTES)throw new IOException("MATERIAL_GROUP_JOURNAL_INVALID");
        byte[] bytes=new byte[(int)before.size];
        try(LibraryBackupArchive.Seekable input=files.openRead(path)) {
            if(!before.equals(input.openedIdentity())||input.size()!=before.size)throw new IOException("MATERIAL_GROUP_JOURNAL_CHANGED");
            input.readFully(0,bytes,0,bytes.length);
            if(!before.equals(input.openedIdentity())||!before.equals(input.currentPathIdentity()))throw new IOException("MATERIAL_GROUP_JOURNAL_CHANGED");
        }
        return bytes;
    }

    final class GroupLease implements AutoCloseable {
        final GroupPlan plan;final File directory;private final LibraryBackupArchive.SafeFiles.PublishLock lock;
        private boolean closed;
        private GroupLease(GroupPlan plan,File directory,LibraryBackupArchive.SafeFiles.PublishLock lock) {
            this.plan=plan;this.directory=directory;this.lock=lock;
        }
        GroupPlan plan(){return plan;}
        void registerProof(RestoredLibraryMaterialStore.CommitProof proof)throws IOException {
            requireOpen();Member member=findMember(plan,proof);
            if(member==null)throw new IOException("MATERIAL_GROUP_PROOF_MISMATCH");
            if(isCommittedUnderLease(proof))throw new IOException("MATERIAL_GROUP_ALREADY_COMMITTED");
            if(!files.isAbsentNoFollow(new File(directory,"rollback-in-progress.json")))throw new IOException("MATERIAL_GROUP_ROLLBACK_STARTED");
            File proofDir=proofDirectory(plan);files.validatePrivateDirectory(proofDir);
            File path=new File(proofDir,"proof-"+member.localId+".json");byte[] bytes=encodeProof(proof);
            if(files.isAbsentNoFollow(path))writeNew(path,bytes);
            else { if(!MessageDigest.isEqual(bytes,readBounded(path)))throw new IOException("MATERIAL_GROUP_PROOF_CHANGED");syncDurable(path); }
        }
        void commit()throws IOException {
            requireOpen();if(!files.isAbsentNoFollow(new File(directory,"rollback-in-progress.json")))throw new IOException("MATERIAL_GROUP_ROLLBACK_STARTED");
            List<RestoredLibraryMaterialStore.CommitProof> proofs=readAllProofs();
            if(proofs.size()!=plan.members.size())throw new IOException("MATERIAL_GROUP_INCOMPLETE");
            for(RestoredLibraryMaterialStore.CommitProof proof:proofs)store.verifyPromotedProof(proof);
            byte[] marker=encodeCommit(plan,proofs);File path=new File(directory,"committed.json");
            publishCommitMarker(path,marker);
        }
        void markRollbackInProgress()throws IOException {
            requireOpen();if(isCommittedUnderLease(null))throw new IOException("MATERIAL_GROUP_ALREADY_COMMITTED");
            File path=new File(directory,"rollback-in-progress.json");byte[] marker=encodeState(plan,"rollback_in_progress");
            if(files.isAbsentNoFollow(path))writeNew(path,marker);else {if(!MessageDigest.isEqual(marker,readBounded(path)))throw new IOException("MATERIAL_GROUP_JOURNAL_INVALID");syncDurable(path);}
        }
        void markRolledBack()throws IOException {
            requireOpen();if(isCommittedUnderLease(null))throw new IOException("MATERIAL_GROUP_ALREADY_COMMITTED");
            if(files.isAbsentNoFollow(new File(directory,"rollback-in-progress.json")))throw new IOException("MATERIAL_GROUP_ROLLBACK_NOT_STARTED");
            List<RestoredLibraryMaterialStore.CommitProof> proofs=readAllProofs();
            Map<String,RestoredLibraryMaterialStore.CommitProof> byId=new HashMap<>();for(RestoredLibraryMaterialStore.CommitProof proof:proofs)byId.put(proof.localId,proof);
            for(Member member:plan.members) {
                RestoredLibraryMaterialStore.CommitProof proof=byId.get(member.localId);
                if(proof!=null) {if(!store.isProofNamespaceAbsent(proof))throw new IOException("MATERIAL_GROUP_RECOVERY_NEEDED");}
                else if(!store.isMemberNamespaceAbsent(plan.transactionId,plan.groupId,member.localId,member.kind))throw new IOException("MATERIAL_GROUP_RECOVERY_NEEDED");
            }
            File path=new File(directory,"rolled-back.json");byte[] marker=encodeState(plan,"rolled_back");
            if(files.isAbsentNoFollow(path))writeNew(path,marker);else {if(!MessageDigest.isEqual(marker,readBounded(path)))throw new IOException("MATERIAL_GROUP_JOURNAL_INVALID");syncDurable(path);}
        }
        LibraryBackupArchive.SafeFiles.PublishLock lockForTestOnly(){return lock;}
        RestoredLibraryMaterialStore.CommitGate commitGate(){return proof->{requireOpen();return isCommittedUnderLease(proof);};}
        RestoredLibraryMaterialStore.PendingRollbackGate pendingRollbackGate(){
            return proof->{requireOpen();if(findMember(plan,proof)==null||isCommittedUnderLease(proof)|| 
                    !rollbackMarkerValid()||!registeredProofMatches(proof))throw new IOException("MATERIAL_ROLLBACK_NOT_PENDING");
                return new RestoredLibraryMaterialStore.PendingRollbackPermit(){private boolean permitClosed;
                    public boolean stillPending(RestoredLibraryMaterialStore.CommitProof candidate)throws Exception {
                        return !permitClosed&&!closed&&sameProof(proof,candidate)&&findMember(plan,candidate)!=null&&
                                files.isAbsentNoFollow(new File(directory,"committed.json"))&&
                                rollbackMarkerValid()&&registeredProofMatches(candidate);
                    }
                    public void close(){permitClosed=true;}
                };};
        }
        private boolean isCommittedUnderLease(RestoredLibraryMaterialStore.CommitProof proof)throws IOException {
            File marker=new File(directory,"committed.json");if(files.isAbsentNoFollow(marker))return false;
            Map<String,Object> m=LibraryBackupJson.parseCheckedObject(readBounded(marker),MAX_JOURNAL_BYTES);
            LibraryBackupJson.exactKeys(m,"MATERIAL_GROUP_COMMIT_INVALID","schema_version","transaction_id","group_id","source_group_key","proofs");
            if(LibraryBackupJson.integer(m.get("schema_version"),1,1,"MATERIAL_GROUP_COMMIT_INVALID")!=1||
                    !plan.transactionId.equals(LibraryBackupJson.string(m.get("transaction_id"),"MATERIAL_GROUP_COMMIT_INVALID"))||
                    !plan.groupId.equals(LibraryBackupJson.string(m.get("group_id"),"MATERIAL_GROUP_COMMIT_INVALID"))||
                    !plan.sourceGroupKey.equals(LibraryBackupJson.string(m.get("source_group_key"),"MATERIAL_GROUP_COMMIT_INVALID")))throw new IOException("MATERIAL_GROUP_COMMIT_INVALID");
            List<RestoredLibraryMaterialStore.CommitProof> recorded;
            try { recorded=parseProofs(m.get("proofs")); }
            catch(IOException invalid) { throw new IOException("MATERIAL_GROUP_COMMIT_INVALID"); }
            if(recorded.size()!=plan.members.size())throw new IOException("MATERIAL_GROUP_COMMIT_INVALID");
            List<RestoredLibraryMaterialStore.CommitProof> persisted=readAllProofs();
            if(persisted.size()!=recorded.size())throw new IOException("MATERIAL_GROUP_COMMIT_INVALID");
            for(int i=0;i<recorded.size();i++)if(!sameProof(recorded.get(i),persisted.get(i)))throw new IOException("MATERIAL_GROUP_COMMIT_INVALID");
            if(proof==null)return true;
            for(RestoredLibraryMaterialStore.CommitProof candidate:recorded)if(sameProof(candidate,proof))return true;
            return false;
        }
        private boolean registeredProofMatches(RestoredLibraryMaterialStore.CommitProof proof)throws IOException {
            Member member=findMember(plan,proof);if(member==null)return false;
            File path=new File(proofDirectory(plan),"proof-"+member.localId+".json");
            if(files.isAbsentNoFollow(path))return false;
            try{return sameProof(decodeProof(readBounded(path)),proof);}
            catch(IOException invalid){throw new IOException("MATERIAL_GROUP_PROOF_INVALID");}
        }
        private boolean rollbackMarkerValid()throws IOException {
            File path=new File(directory,"rollback-in-progress.json");
            if(files.isAbsentNoFollow(path))return false;
            return MessageDigest.isEqual(encodeState(plan,"rollback_in_progress"),readBounded(path));
        }
        private void publishCommitMarker(File target,byte[] complete)throws IOException {
            if(!files.isAbsentNoFollow(target)) {
                byte[] current=readBounded(target);
                if(MessageDigest.isEqual(current,complete)) {
                    LibraryBackupArchive.FileIdentity committedIdentity=files.inspect(target,false);
                    if(committedIdentity.links!=1||!MessageDigest.isEqual(current,readBounded(target)))throw new IOException("MATERIAL_GROUP_COMMIT_MISMATCH");
                    syncDurable(target);
                    if(!committedIdentity.equals(files.inspect(target,false))||!MessageDigest.isEqual(complete,readBounded(target)))throw new IOException("MATERIAL_GROUP_COMMIT_MISMATCH");
                    files.syncDirectory(directory);
                    return;
                }
                Map<String,Object> reservation;
                try{reservation=LibraryBackupJson.parseCheckedObject(current,4096);}
                catch(IOException malformed){throw new IOException("MATERIAL_GROUP_COMMIT_MISMATCH");}
                LibraryBackupJson.exactKeys(reservation,"MATERIAL_GROUP_COMMIT_MISMATCH","schema_version","transaction_id","group_id","source_group_key","state","token","temporary_file","marker_sha256");
                String token=LibraryBackupJson.string(reservation.get("token"),"MATERIAL_GROUP_COMMIT_MISMATCH");
                requireUuid(token,"MATERIAL_GROUP_COMMIT_MISMATCH");
                String tempName=".commit-"+token+".tmp";
                if(LibraryBackupJson.integer(reservation.get("schema_version"),1,1,"MATERIAL_GROUP_COMMIT_MISMATCH")!=1||
                        !plan.transactionId.equals(LibraryBackupJson.string(reservation.get("transaction_id"),"MATERIAL_GROUP_COMMIT_MISMATCH"))||
                        !plan.groupId.equals(LibraryBackupJson.string(reservation.get("group_id"),"MATERIAL_GROUP_COMMIT_MISMATCH"))||
                        !plan.sourceGroupKey.equals(LibraryBackupJson.string(reservation.get("source_group_key"),"MATERIAL_GROUP_COMMIT_MISMATCH"))||
                        !"committing".equals(LibraryBackupJson.string(reservation.get("state"),"MATERIAL_GROUP_COMMIT_MISMATCH"))||
                        !tempName.equals(LibraryBackupJson.string(reservation.get("temporary_file"),"MATERIAL_GROUP_COMMIT_MISMATCH"))||
                        !sha256(complete).equals(LibraryBackupJson.string(reservation.get("marker_sha256"),"MATERIAL_GROUP_COMMIT_MISMATCH")))
                    throw new IOException("MATERIAL_GROUP_COMMIT_MISMATCH");
                File tempFile=new File(directory,tempName);
                if(files.isAbsentNoFollow(tempFile))writeNew(tempFile,complete);
                else {
                    if(!MessageDigest.isEqual(complete,readBounded(tempFile)))throw new IOException("MATERIAL_GROUP_COMMIT_RECOVERY_NEEDED");
                    syncDurable(tempFile);
                }
                requireOnlyCommitTemp(tempName);
                LibraryBackupArchive.FileIdentity reservedIdentity=files.inspect(target,false);
                if(reservedIdentity.links!=1||!MessageDigest.isEqual(current,readBounded(target)))throw new IOException("MATERIAL_GROUP_COMMIT_MISMATCH");
                syncDurable(target);
                reservedIdentity=files.inspect(target,false);
                if(reservedIdentity.links!=1||!MessageDigest.isEqual(current,readBounded(target)))throw new IOException("MATERIAL_GROUP_COMMIT_MISMATCH");
                files.replaceOwnedMarker(tempFile,target,current,reservedIdentity);
                files.syncDirectory(directory);
                return;
            }
            requireOnlyCommitTemp(null);
            String token=UUID.randomUUID().toString(),tempName=".commit-"+token+".tmp";File tempFile=new File(directory,tempName);
            Map<String,Object> m=new java.util.TreeMap<>();m.put("schema_version",1);m.put("transaction_id",plan.transactionId);m.put("group_id",plan.groupId);
            m.put("source_group_key",plan.sourceGroupKey);m.put("state","committing");m.put("token",token);m.put("temporary_file",tempName);m.put("marker_sha256",sha256(complete));
            byte[] reservation=encode(m);writeNew(target,reservation);
            LibraryBackupArchive.FileIdentity identity=files.inspect(target,false);
            if(identity.links!=1||!MessageDigest.isEqual(reservation,readBounded(target)))throw new IOException("MATERIAL_GROUP_COMMIT_RESERVATION_CHANGED");
            writeNew(tempFile,complete);
            files.replaceOwnedMarker(tempFile,target,reservation,identity);
            files.syncDirectory(directory);
        }
        private void requireOnlyCommitTemp(String allowed)throws IOException {
            File[] children=directory.listFiles();if(children==null)throw new IOException("MATERIAL_GROUP_DIRECTORY_UNREADABLE");
            for(File child:children) {
                String name=child.getName();
                if(name.startsWith(".commit-")&&name.endsWith(".tmp")&&!name.equals(allowed))
                    throw new IOException("MATERIAL_GROUP_COMMIT_RECOVERY_NEEDED");
            }
        }
        private List<RestoredLibraryMaterialStore.CommitProof> readAllProofs()throws IOException {
            List<RestoredLibraryMaterialStore.CommitProof> out=new ArrayList<>();File dir=proofDirectory(plan);files.validatePrivateDirectory(dir);
            File[] children=dir.listFiles();if(children==null)throw new IOException("MATERIAL_GROUP_PROOFS_UNREADABLE");
            java.util.HashSet<String> expectedNames=new java.util.HashSet<>();for(Member member:plan.members)expectedNames.add("proof-"+member.localId+".json");
            for(File child:children)if(!expectedNames.remove(child.getName()))throw new IOException("MATERIAL_GROUP_PROOF_UNKNOWN");
            for(Member member:plan.members) {
                File path=new File(dir,"proof-"+member.localId+".json");if(files.isAbsentNoFollow(path))continue;
                RestoredLibraryMaterialStore.CommitProof proof;
                try { proof=decodeProof(readBounded(path)); }
                catch(IOException invalid) { throw new IOException("MATERIAL_GROUP_PROOF_INVALID"); }
                if(findMember(plan,proof)==null)throw new IOException("MATERIAL_GROUP_PROOF_MISMATCH");out.add(proof);
            }
            // Missing expected rows remain a valid pending state; commit separately requires a full set.
            return out;
        }
        private void requireOpen()throws IOException{if(closed)throw new IOException("MATERIAL_GROUP_LEASE_CLOSED");}
        @Override public void close()throws IOException{if(closed)return;closed=true;lock.close();}
    }

    private GroupPlan readMembership(File path,String tx,String group)throws IOException {
        Map<String,Object> m=LibraryBackupJson.parseCheckedObject(readBounded(path),MAX_JOURNAL_BYTES);
        LibraryBackupJson.exactKeys(m,"MATERIAL_GROUP_MEMBERSHIP_INVALID","schema_version","transaction_id","group_id","source_group_key","members");
        if(LibraryBackupJson.integer(m.get("schema_version"),1,1,"MATERIAL_GROUP_MEMBERSHIP_INVALID")!=1||
                !tx.equals(LibraryBackupJson.string(m.get("transaction_id"),"MATERIAL_GROUP_MEMBERSHIP_INVALID"))||
                !group.equals(LibraryBackupJson.string(m.get("group_id"),"MATERIAL_GROUP_MEMBERSHIP_INVALID")))throw new IOException("MATERIAL_GROUP_MEMBERSHIP_INVALID");
        return decodePlan(encode(m),tx,LibraryBackupJson.string(m.get("source_group_key"),"MATERIAL_GROUP_MEMBERSHIP_INVALID"));
    }
    private GroupPlan decodePlan(byte[] bytes,String tx,String sourceKey)throws IOException {
        Map<String,Object> m=LibraryBackupJson.parseCheckedObject(bytes,MAX_JOURNAL_BYTES);
        LibraryBackupJson.exactKeys(m,"MATERIAL_GROUP_MAPPING_INVALID","schema_version","transaction_id","source_group_key","group_id","members");
        if(LibraryBackupJson.integer(m.get("schema_version"),1,1,"MATERIAL_GROUP_MAPPING_INVALID")!=1||
                !tx.equals(LibraryBackupJson.string(m.get("transaction_id"),"MATERIAL_GROUP_MAPPING_INVALID"))||
                !sourceKey.equals(LibraryBackupJson.string(m.get("source_group_key"),"MATERIAL_GROUP_MAPPING_INVALID")))throw new IOException("MATERIAL_GROUP_MAPPING_INVALID");
        String group=LibraryBackupJson.string(m.get("group_id"),"MATERIAL_GROUP_MAPPING_INVALID");requireUuid(group,"MATERIAL_GROUP_MAPPING_INVALID");
        List<Member> members=new ArrayList<>();java.util.HashSet<String> ids=new java.util.HashSet<>(),items=new java.util.HashSet<>();
        for(Object value:LibraryBackupJson.array(m.get("members"),"MATERIAL_GROUP_MAPPING_INVALID")) {
            Map<String,Object> row=LibraryBackupJson.object(value,"MATERIAL_GROUP_MAPPING_INVALID");
            LibraryBackupJson.exactKeys(row,"MATERIAL_GROUP_MAPPING_INVALID","source_item_id","local_id","kind","current_local_note_id");
            String item=LibraryBackupJson.string(row.get("source_item_id"),"MATERIAL_GROUP_MAPPING_INVALID"),id=LibraryBackupJson.string(row.get("local_id"),"MATERIAL_GROUP_MAPPING_INVALID");
            requireItemId(item);requireUuid(id,"MATERIAL_GROUP_MAPPING_INVALID");
            if(!items.add(item)||!ids.add(id))throw new IOException("MATERIAL_GROUP_MAPPING_INVALID");
            String kindName=LibraryBackupJson.string(row.get("kind"),"MATERIAL_GROUP_MAPPING_INVALID");RestoredLibraryMaterialStore.Kind kind;
            try{kind=RestoredLibraryMaterialStore.Kind.valueOf(kindName);}catch(IllegalArgumentException bad){throw new IOException("MATERIAL_GROUP_MAPPING_INVALID");}
            Object noteValue=row.get("current_local_note_id");String note=noteValue==null?null:LibraryBackupJson.string(noteValue,"MATERIAL_GROUP_MAPPING_INVALID");
            if(note!=null&&!note.matches("[A-Za-z0-9_-]{1,160}"))throw new IOException("MATERIAL_GROUP_MAPPING_INVALID");
            members.add(new Member(item,id,note,kind));
        }
        if(members.isEmpty())throw new IOException("MATERIAL_GROUP_MAPPING_INVALID");
        members.sort(Comparator.comparing(a->a.sourceItemId));
        return new GroupPlan(tx,sourceKey,group,members);
    }
    private byte[] encodePlan(GroupPlan plan)throws IOException {
        Map<String,Object> m=new java.util.TreeMap<>();m.put("schema_version",1);m.put("transaction_id",plan.transactionId);m.put("source_group_key",plan.sourceGroupKey);m.put("group_id",plan.groupId);m.put("members",memberJson(plan));return encode(m);
    }
    private byte[] encodeMembership(GroupPlan plan)throws IOException {
        Map<String,Object> m=new java.util.TreeMap<>();m.put("schema_version",1);m.put("transaction_id",plan.transactionId);m.put("group_id",plan.groupId);m.put("source_group_key",plan.sourceGroupKey);m.put("members",memberJson(plan));return encode(m);
    }
    private List<Object> memberJson(GroupPlan plan) {
        List<Object> values=new ArrayList<>();for(Member member:plan.members){Map<String,Object> m=new java.util.TreeMap<>();m.put("source_item_id",member.sourceItemId);m.put("local_id",member.localId);m.put("kind",member.kind.name());m.put("current_local_note_id",member.currentLocalNoteId);values.add(m);}return values;
    }
    private byte[] encodeProof(RestoredLibraryMaterialStore.CommitProof p)throws IOException {
        Map<String,Object> m=new java.util.TreeMap<>();m.put("schema_version",1);m.put("transaction_id",p.transactionId);m.put("group_id",p.groupId);m.put("local_id",p.localId);m.put("kind",p.kind.name());m.put("payload_sha256",p.payloadSha256);m.put("binding_sha256",p.bindingSha256);m.put("current_local_note_id",p.currentLocalNoteId);return encode(m);
    }
    private RestoredLibraryMaterialStore.CommitProof decodeProof(byte[] bytes)throws IOException {
        Map<String,Object> m=LibraryBackupJson.parseCheckedObject(bytes,MAX_JOURNAL_BYTES);
        LibraryBackupJson.exactKeys(m,"MATERIAL_GROUP_PROOF_INVALID","schema_version","transaction_id","group_id","local_id","kind","payload_sha256","binding_sha256","current_local_note_id");
        if(LibraryBackupJson.integer(m.get("schema_version"),1,1,"MATERIAL_GROUP_PROOF_INVALID")!=1)throw new IOException("MATERIAL_GROUP_PROOF_INVALID");
        String tx=LibraryBackupJson.string(m.get("transaction_id"),"MATERIAL_GROUP_PROOF_INVALID"),group=LibraryBackupJson.string(m.get("group_id"),"MATERIAL_GROUP_PROOF_INVALID"),id=LibraryBackupJson.string(m.get("local_id"),"MATERIAL_GROUP_PROOF_INVALID");
        requireUuid(tx,"MATERIAL_GROUP_PROOF_INVALID");requireUuid(group,"MATERIAL_GROUP_PROOF_INVALID");requireUuid(id,"MATERIAL_GROUP_PROOF_INVALID");
        RestoredLibraryMaterialStore.Kind kind;try{kind=RestoredLibraryMaterialStore.Kind.valueOf(LibraryBackupJson.string(m.get("kind"),"MATERIAL_GROUP_PROOF_INVALID"));}catch(IllegalArgumentException bad){throw new IOException("MATERIAL_GROUP_PROOF_INVALID");}
        String payload=LibraryBackupJson.string(m.get("payload_sha256"),"MATERIAL_GROUP_PROOF_INVALID"),binding=LibraryBackupJson.string(m.get("binding_sha256"),"MATERIAL_GROUP_PROOF_INVALID");
        if(!hash(payload)||!hash(binding))throw new IOException("MATERIAL_GROUP_PROOF_INVALID");Object noteValue=m.get("current_local_note_id");String note=noteValue==null?null:LibraryBackupJson.string(noteValue,"MATERIAL_GROUP_PROOF_INVALID");
        return new RestoredLibraryMaterialStore.CommitProof(tx,group,id,kind,payload,binding,note);
    }
    private byte[] encodeCommit(GroupPlan plan,List<RestoredLibraryMaterialStore.CommitProof> proofs)throws IOException {
        List<Object> proofRows=new ArrayList<>();for(RestoredLibraryMaterialStore.CommitProof proof:proofs)proofRows.add(LibraryBackupJson.parse(encodeProof(proof),MAX_JOURNAL_BYTES));
        Map<String,Object> m=new java.util.TreeMap<>();m.put("schema_version",1);m.put("transaction_id",plan.transactionId);m.put("group_id",plan.groupId);m.put("source_group_key",plan.sourceGroupKey);m.put("proofs",proofRows);return encode(m);
    }
    private byte[] encodeState(GroupPlan plan,String state)throws IOException {
        Map<String,Object> m=new java.util.TreeMap<>();m.put("schema_version",1);m.put("transaction_id",plan.transactionId);m.put("group_id",plan.groupId);m.put("source_group_key",plan.sourceGroupKey);m.put("state",state);return encode(m);
    }
    private List<RestoredLibraryMaterialStore.CommitProof> parseProofs(Object value)throws IOException {
        List<RestoredLibraryMaterialStore.CommitProof> proofs=new ArrayList<>();for(Object row:LibraryBackupJson.array(value,"MATERIAL_GROUP_COMMIT_INVALID"))proofs.add(decodeProof(encode(row)));return proofs;
    }
    private static byte[] encode(Object object)throws IOException{return LibraryBackupJson.encode(object);}
    private static String sha256(byte[] bytes){try{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static boolean hash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static void requireUuid(String value,String code)throws IOException{try{if(value==null||!UUID.fromString(value).toString().equals(value))throw new IOException(code);}catch(IllegalArgumentException bad){throw new IOException(code);}}
    private static void validateSourceKey(String value)throws IOException{if(value==null||value.isEmpty()||value.length()>256||!value.matches("[A-Za-z0-9:_-]+"))throw new IOException("MATERIAL_GROUP_SOURCE_KEY_INVALID");}
    private static void requireItemId(String value)throws IOException{if(value==null||!value.matches("[ivac]-[0-9a-f]{32}"))throw new IOException("MATERIAL_GROUP_MEMBER_INVALID");}
    private static List<MemberRequest> validateAndSort(List<MemberRequest> requests)throws IOException {
        if(requests==null||requests.isEmpty()||requests.size()>1000)throw new IOException("MATERIAL_GROUP_MEMBERS_INVALID");
        List<MemberRequest> sorted=new ArrayList<>(requests);
        for(MemberRequest request:sorted) {
            if(request==null||request.kind==null)throw new IOException("MATERIAL_GROUP_MEMBER_INVALID");
            requireItemId(request.sourceItemId);
            if(request.currentLocalNoteId!=null&&!request.currentLocalNoteId.matches("[A-Za-z0-9_-]{1,160}"))throw new IOException("MATERIAL_GROUP_MEMBER_INVALID");
        }
        sorted.sort(Comparator.comparing(a->a.sourceItemId));String previous=null;
        for(MemberRequest request:sorted){if(request.sourceItemId.equals(previous))throw new IOException("MATERIAL_GROUP_MEMBER_DUPLICATE");previous=request.sourceItemId;}
        return sorted;
    }
    private static boolean sameRequests(GroupPlan plan,List<MemberRequest> requests) {
        if(plan.members.size()!=requests.size())return false;for(int i=0;i<requests.size();i++){Member a=plan.members.get(i);MemberRequest b=requests.get(i);if(!a.sourceItemId.equals(b.sourceItemId)||a.kind!=b.kind||!java.util.Objects.equals(a.currentLocalNoteId,b.currentLocalNoteId))return false;}return true;
    }
    private static Member findMember(GroupPlan plan,RestoredLibraryMaterialStore.CommitProof proof)throws IOException {
        if(proof==null||!plan.transactionId.equals(proof.transactionId)||!plan.groupId.equals(proof.groupId))return null;
        for(Member member:plan.members)if(member.localId.equals(proof.localId)&&member.kind==proof.kind&&java.util.Objects.equals(member.currentLocalNoteId,proof.currentLocalNoteId))return member;
        return null;
    }
    private static boolean sameProof(RestoredLibraryMaterialStore.CommitProof a,RestoredLibraryMaterialStore.CommitProof b){return a!=null&&b!=null&&a.transactionId.equals(b.transactionId)&&a.groupId.equals(b.groupId)&&a.localId.equals(b.localId)&&a.kind==b.kind&&a.payloadSha256.equals(b.payloadSha256)&&a.bindingSha256.equals(b.bindingSha256)&&java.util.Objects.equals(a.currentLocalNoteId,b.currentLocalNoteId);}
}
