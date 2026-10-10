package com.padnote.android.streaming;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import com.padnote.android.NativeManualUpdatePlan;

/** Native app-backed version of the immutable group engine. */
@android.annotation.TargetApi(27)
public final class StreamingGroupStore {
    public static final int COPY_BUFFER_BYTES = 64 * 1024;
    public static final long BODY_MAX=50L<<20, PDF_MAX=100L<<20, COVER_MAX=8L<<20;
    public static final long VIDEO_MAX=100L<<20, VAULT_MAX=16L<<20;
    public static final long RESOURCE_TOTAL_MAX=1_073_741_824L, GROUP_TOTAL_MAX=1_181_116_006L;
    private static final byte[] DOMAIN="PadNote/StreamingImmutableUpdateGroup/v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HISTORY_RESTORE_DOMAIN="PadNote/StreamingHistoryRestoreGroup/v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final ConcurrentHashMap<String,ReentrantLock> LOCKS=new ConcurrentHashMap<>();
    private StreamingGroupStore() {}
    public enum Action { FIRST_IMPORT, UPDATE, EXPLICIT_COPY, MANUAL_UPDATE_V2, LEGACY_ADOPTION_V2, HISTORY_RESTORE_V1 }
    public enum Phase { STAGED, IMMUTABLE, PREVIEW_APPROVED, LEGACY_SOURCE_RECHECK_REQUIRED, COMMITTED, CONFLICT, ABORTED }

    public interface IdentityOps {
        /** Exact no-follow final-component state. MISSING means the platform proved ENOENT;
         * permission, I/O, and unsupported errors must throw instead of becoming MISSING. */
        enum NoFollowState { PRESENT, MISSING }
        NoFollowState noFollowState(Path path) throws IOException;
        StableInput open(Path path, long expectedSize) throws IOException;
        void requireStable(Path path, FileIdentity pathBefore, FileIdentity fdBefore,
                           FileIdentity fdAfter, FileIdentity pathAfter) throws IOException;
        FileIdentity identity(Path path) throws IOException;
        /** Creates only the requested directory while validating every ancestor with no-follow semantics. */
        void ensurePlainDirectory(Path path) throws IOException;
        void requirePlainDirectory(Path path) throws IOException;
        /** Must make this app-owned object immutable to normal store writes or fail closed. */
        FileIdentity sealImmutable(Path path, FileIdentity expected) throws IOException;
        /** Confirms the same immutable inode/object identity; timestamps alone are insufficient. */
        void requireImmutable(Path path, FileIdentity witness, FileIdentity current) throws IOException;
        /** Durably syncs a verified directory entry set; unsupported sync is a hard failure. */
        void syncDirectory(Path path) throws IOException;
        /** Atomic same-volume rename that must fail when destination exists; never replace. */
        void moveNoReplace(Path source, Path destination) throws IOException;
        /** Installs the exact two-file immutable group directory without replacing an existing destination. */
        default void installImmutableGroup(Path source,Path destination)throws IOException { moveNoReplace(source,destination); }
        /** Cross-process exclusive lease on a no-follow, single-link lock file. */
        LockLease lock(Path lockFile) throws IOException;
        /** Non-blocking transaction lease used by explicit recovery; empty means active. */
        Optional<LockLease> tryLock(Path lockFile) throws IOException;
        /** Atomic marker replacement, after caller's CAS and exclusive lease. */
        void replaceAtomic(Path source, Path destination) throws IOException;
    }
    /** Optional per-reader counters; the engine itself remains Android independent. */
    public interface ReadDiagnostics {
        void lookupStarted();
        void markerScan(long entries,long bytes,long elapsedNanos,boolean complete);
        default void markerRecordRead(long bytes) { }
        void groupVerification(long elapsedNanos,boolean complete);
        void objectVerification(long bytesHashed,long elapsedNanos,boolean witnessRecheck);
        void lookupFinished(long elapsedNanos,boolean complete);
    }
    public interface LockLease extends AutoCloseable { @Override void close() throws IOException; }
    public interface StableInput extends AutoCloseable {
        InputStream stream();
        FileIdentity pathBefore();
        FileIdentity before();
        FileIdentity after() throws IOException;
        /** Sync the exact opened object after its contents have been verified. */
        default void sync() throws IOException { throw fail("VERIFIED_INPUT_SYNC_UNAVAILABLE"); }
        /** Returns an independently closeable descriptor for this same opened object, if supported. */
        default java.io.FileDescriptor duplicateDescriptor() throws IOException { throw fail("VERIFIED_DESCRIPTOR_UNAVAILABLE"); }
        /** Rewinds the duplicated open-file description after its source stream was hashed. */
        default void rewindDuplicateDescriptor(java.io.FileDescriptor descriptor) throws IOException { throw fail("VERIFIED_DESCRIPTOR_REWIND_UNAVAILABLE"); }
        default void closeDuplicateDescriptor(java.io.FileDescriptor descriptor) throws IOException { throw fail("VERIFIED_DESCRIPTOR_CLOSE_UNAVAILABLE"); }
        @Override void close() throws IOException;
    }
    public static final class FileIdentity {
        private final String device,fileKey; private final long size,modifiedNanos,changedNanos,links; private final int mode; private final boolean reparse;
        public FileIdentity(String device,String fileKey,long size,long modifiedNanos,long changedNanos,int mode,long links,boolean reparse){this.device=device;this.fileKey=fileKey;this.size=size;this.modifiedNanos=modifiedNanos;this.changedNanos=changedNanos;this.mode=mode;this.links=links;this.reparse=reparse;}
        public String device(){return device;} public String fileKey(){return fileKey;} public long size(){return size;} public long modifiedNanos(){return modifiedNanos;} public long changedNanos(){return changedNanos;} public int mode(){return mode;} public long links(){return links;} public boolean reparse(){return reparse;}
        @Override public boolean equals(Object o){if(!(o instanceof FileIdentity))return false;FileIdentity x=(FileIdentity)o;return size==x.size&&modifiedNanos==x.modifiedNanos&&changedNanos==x.changedNanos&&mode==x.mode&&links==x.links&&reparse==x.reparse&&Objects.equals(device,x.device)&&Objects.equals(fileKey,x.fileKey);}
        @Override public int hashCode(){return Objects.hash(device,fileKey,size,modifiedNanos,changedNanos,mode,links,reparse);}
    }
    public interface ProjectionVerifier {
        void verifyBody(byte[] body) throws IOException;
        void verifyMetadata(String role, String materialId, byte[] metadata) throws IOException;
    }
    /** Mandatory semantic gate for MANUAL_UPDATE_V2, invoked on staging and every cold read/recovery. */
    public interface CompleteGroupVerifier {
        void verifyCompleteGroup(VerifiedGroup group, byte[] sourceProvenance) throws IOException;
    }
    public interface Preview { boolean approve(Snapshot current, VerifiedGroup incoming) throws Exception; }
    public interface CommitObserver { void afterDurablePhase(Phase phase) throws IOException; }

    /** Small structured inputs are copied at construction; their bounds are checked before staging. */
    public static final class MemberInput {
        public final String role, materialId, mediaType;
        public final ContentInput content;
        private final byte[] metadata;
        public MemberInput(String role, String materialId, String mediaType, byte[] metadata,
                           Path source, long size, String sha256) {
            this(role,materialId,mediaType,metadata,new ContentInput(source,size,sha256,null));
        }
        public MemberInput(String role, String materialId, String mediaType, byte[] metadata, ContentInput content) {
            this.role=role; this.materialId=materialId; this.mediaType=mediaType;
            this.metadata=metadata == null ? null : metadata.clone(); this.content=content;
        }
        public byte[] metadata() { return metadata == null ? null : metadata.clone(); }
    }
    /** Path-backed, bounded stream. The path is consumed into transaction-owned staging before preview. */
    public static final class ContentInput {
        public final Path source; public final long size; public final String sha256; public final ReuseToken reuse;
        public ContentInput(Path source,long size,String sha256,ReuseToken reuse){this.source=source;this.size=size;this.sha256=sha256;this.reuse=reuse;}
    }
    /** Unforgeable in-process capability created from a fully verified prior manifest. */
    public static final class ReuseToken {
        private final Path root; private final String groupDigest,member,role,materialId,sha256; private final long size;
        private ReuseToken(Path root,String groupDigest,Ref ref){this.root=root;this.groupDigest=groupDigest;member=ref.member;role=ref.role;materialId=ref.materialId;sha256=ref.sha256;size=ref.size;}
    }

    /**
     * Origin identity is intentionally separate from the destination's local ID.
     * First import keeps the origin lineage; an explicit duplicate forks lineage.
     */
    public static final class ImportIdentity {
        public final String originLineage,originLocalId,destinationLocalId,destinationLineage,parentOriginLineage,parentOriginLocalId;
        public ImportIdentity(String originLineage,String originLocalId,String destinationLocalId,String destinationLineage,String parentOriginLineage,String parentOriginLocalId){this.originLineage=originLineage;this.originLocalId=originLocalId;this.destinationLocalId=destinationLocalId;this.destinationLineage=destinationLineage;this.parentOriginLineage=parentOriginLineage;this.parentOriginLocalId=parentOriginLocalId;}
        @Override public boolean equals(Object o){if(!(o instanceof ImportIdentity))return false;ImportIdentity x=(ImportIdentity)o;return Objects.equals(originLineage,x.originLineage)&&Objects.equals(originLocalId,x.originLocalId)&&Objects.equals(destinationLocalId,x.destinationLocalId)&&Objects.equals(destinationLineage,x.destinationLineage)&&Objects.equals(parentOriginLineage,x.parentOriginLineage)&&Objects.equals(parentOriginLocalId,x.parentOriginLocalId);}
        @Override public int hashCode(){return Objects.hash(originLineage,originLocalId,destinationLocalId,destinationLineage,parentOriginLineage,parentOriginLocalId);}
        public String originLineage(){return originLineage;} public String originLocalId(){return originLocalId;}
        public String destinationLocalId(){return destinationLocalId;} public String destinationLineage(){return destinationLineage;}
        public String parentOriginLineage(){return parentOriginLineage;} public String parentOriginLocalId(){return parentOriginLocalId;}
    }

    public static final class Envelope {
        public final ImportIdentity identity;
        public final String revision, parentRevision, baseRevision, baseDigest;
        public final String restoreSourceRevision, restoreSourceDigest;
        public final Action action;
        private final byte[] body;
        public final ContentInput pdf, cover;
        public final List<MemberInput> videos, vault;
        private final byte[] sourceProvenance;
        public Envelope(ImportIdentity identity, String revision, String parentRevision,
                        String baseRevision, String baseDigest, Action action,
                        byte[] body, ContentInput pdf, ContentInput cover,
                        List<MemberInput> videos, List<MemberInput> vault) {
            this(identity,revision,parentRevision,baseRevision,baseDigest,action,body,pdf,cover,videos,vault,null);
        }
        public Envelope(ImportIdentity identity, String revision, String parentRevision,
                        String baseRevision, String baseDigest, Action action,
                        byte[] body, ContentInput pdf, ContentInput cover,
                        List<MemberInput> videos, List<MemberInput> vault, byte[] sourceProvenance) {
            this(identity,revision,parentRevision,baseRevision,baseDigest,action,body,pdf,cover,videos,vault,sourceProvenance,null,null);
        }
        public Envelope(ImportIdentity identity, String revision, String parentRevision,
                        String baseRevision, String baseDigest, Action action,
                        byte[] body, ContentInput pdf, ContentInput cover, List<MemberInput> videos,
                        List<MemberInput> vault, byte[] sourceProvenance, String restoreSourceRevision,
                        String restoreSourceDigest) {
            this.identity=identity; this.revision=revision; this.parentRevision=parentRevision;
            this.baseRevision=baseRevision; this.baseDigest=baseDigest; this.action=action;
            this.restoreSourceRevision=restoreSourceRevision;this.restoreSourceDigest=restoreSourceDigest;
            this.body=body==null?null:body.clone(); this.pdf=pdf; this.cover=cover;
            this.videos=videos==null?null:Collections.unmodifiableList(new ArrayList<>(videos)); this.vault=vault==null?null:Collections.unmodifiableList(new ArrayList<>(vault));
            this.sourceProvenance=sourceProvenance==null?null:sourceProvenance.clone();
        }
        public byte[] sourceProvenance() { return sourceProvenance==null?null:sourceProvenance.clone(); }
        public byte[] bodyBytes() { return body==null?null:body.clone(); }
    }
    private static final class Ref {final String member,role,materialId,sha256;final long size;Ref(String member,String role,String materialId,long size,String sha256){this.member=member;this.role=role;this.materialId=materialId;this.size=size;this.sha256=sha256;}}
    private static final class Journal {final String transactionID,revision,parentRevision,baseRevision,baseDigest,groupDigest;final Phase phase;final ImportIdentity identity;final Action action;Journal(String transactionID,Phase phase,ImportIdentity identity,String revision,String parentRevision,String baseRevision,String baseDigest,String groupDigest,Action action){this.transactionID=transactionID;this.phase=phase;this.identity=identity;this.revision=revision;this.parentRevision=parentRevision;this.baseRevision=baseRevision;this.baseDigest=baseDigest;this.groupDigest=groupDigest;this.action=action;}}
    private static final byte[] JOURNAL_DOMAIN="PadNote/StreamingTransaction/v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JOURNAL_DOMAIN_V2="PadNote/StreamingManualUpdateTransaction/v2\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JOURNAL_DOMAIN_V3="PadNote/StreamingLegacyAdoptionTransaction/v2\0".getBytes(StandardCharsets.US_ASCII);
    private static boolean usesCompleteProfile(Action a){return a==Action.MANUAL_UPDATE_V2||a==Action.LEGACY_ADOPTION_V2;}

    public static final class VerifiedGroup {
        public final ImportIdentity identity;
        public final String revision, digest;
        private final byte[] manifest;
        private final Map<String, Ref> refs;
        private final Path root;
        private final IdentityOps ops;
        private VerifiedGroup(ImportIdentity identity, String revision, byte[] manifest,
                              Map<String, Ref> refs, String digest, Path root, IdentityOps ops) {
            this.identity=identity; this.revision=revision; this.manifest=manifest.clone();
            this.refs=Collections.unmodifiableMap(new HashMap<>(refs)); this.digest=digest; this.root=root; this.ops=ops;
        }
        public byte[] readSmall(String member, int cap) throws IOException {
            Ref ref=known(member);
            if(ref.size>cap) throw fail("SMALL_MEMBER_CAP_EXCEEDED");
            ByteArrayOutputStream out=new ByteArrayOutputStream((int)ref.size);
            copyVerified(root,ref,out,ops);
            return out.toByteArray();
        }
        /** Opens no unverified external path; callers receive a fresh verified bounded stream. */
        public InputStream openContent(String member) throws IOException {
            Ref ref=known(member);
            return openVerifiedStream(root,ref,ops);
        }
        public Map<String, Long> memberSizes() {
            TreeMap<String,Long> result=new TreeMap<>(); refs.forEach((k,v)->result.put(k,v.size));
            return Collections.unmodifiableMap(result);
        }
        public String memberSha256(String member) throws IOException { return known(member).sha256; }
        /** Hashes the manifest-known content-addressed object on a held descriptor, then leases a duplicate of that inode. */
        public VerifiedContentLease openVerifiedContentLease(String member) throws IOException {
            Ref ref=known(member);
            Path path=root.resolve("objects/sha256").resolve(ref.sha256);
            StableInput stable=ops.open(path,ref.size); FileIdentity before=stable.before(); FileDescriptor duplicate=null;
            try {
                requireSingleLink(stable.pathBefore()); requireSingleLink(before);
                MessageDigest md=digest(); long count=0; byte[] buf=new byte[COPY_BUFFER_BYTES];
                for(int n;(n=stable.stream().read(buf))!=-1;){if(n==0)continue;count=Math.addExact(count,n);if(count>ref.size)throw fail("OBJECT_SIZE_MISMATCH");md.update(buf,0,n);}
                FileIdentity after=stable.after(),pathAfter=ops.identity(path); requireSingleLink(after);requireSingleLink(pathAfter);
                ops.requireStable(path,stable.pathBefore(),before,after,pathAfter);
                if(count!=ref.size||!hex(md.digest()).equals(ref.sha256))throw fail("OBJECT_HASH_MISMATCH");
                duplicate=stable.duplicateDescriptor();
                return new VerifiedContentLease(root,ref,ops,stable,duplicate,before);
            } catch(Throwable t){
                if(duplicate!=null)try{stable.closeDuplicateDescriptor(duplicate);}catch(Throwable close){t.addSuppressed(close);}
                try{stable.close();}catch(Throwable close){t.addSuppressed(close);}
                if(t instanceof IOException)throw(IOException)t; throw new IOException("VERIFIED_CONTENT_LEASE_FAILED",t);
            }
        }
        private Ref known(String member) throws IOException { Ref r=refs.get(member); if(r==null)throw fail("GROUP_MEMBER_UNKNOWN"); return r; }
    }
    public static final class VerifiedContentLease implements AutoCloseable {
        public final String member,role,materialId,sha256; public final long size;
        private final Path root,path; private final Ref ref; private final IdentityOps ops; private final StableInput stable;
        private final FileDescriptor descriptor; private final FileIdentity initial; private boolean verified,closed;
        private VerifiedContentLease(Path root,Ref ref,IdentityOps ops,StableInput stable,FileDescriptor descriptor,FileIdentity initial){this.root=root;this.ref=ref;this.ops=ops;this.stable=stable;this.descriptor=descriptor;this.initial=initial;this.member=ref.member;this.role=ref.role;this.materialId=ref.materialId;this.size=ref.size;this.sha256=ref.sha256;this.path=root.resolve("objects/sha256").resolve(ref.sha256);}
        /** Returns the verified duplicate at byte zero; POSIX dup shares its offset with the hashed source FD. */
        public FileDescriptor descriptor()throws IOException{if(closed)throw fail("LEASE_CLOSED");stable.rewindDuplicateDescriptor(descriptor);return descriptor;}
        /** Rehashes the immutable object and checks path/held-fd identity after decoding. */
        public void verifyUnchanged()throws IOException {
            if(closed)throw fail("LEASE_CLOSED"); FileIdentity after=stable.after(),named=ops.identity(path);requireSingleLink(after);requireSingleLink(named);ops.requireStable(path,stable.pathBefore(),initial,after,named);
            if(!initial.equals(after)||!initial.equals(named))throw fail("LEASE_IDENTITY_CHANGED");
            MessageDigest md=digest();long count=0;byte[] b=new byte[COPY_BUFFER_BYTES];try(StableInput check=ops.open(path,ref.size)){FileIdentity cb=check.before();requireSingleLink(check.pathBefore());requireSingleLink(cb);for(int n;(n=check.stream().read(b))!=-1;){if(n==0)continue;count=Math.addExact(count,n);if(count>ref.size)throw fail("LEASE_SIZE_CHANGED");md.update(b,0,n);}FileIdentity ca=check.after(),cp=ops.identity(path);ops.requireStable(path,check.pathBefore(),cb,ca,cp);if(!cb.equals(initial)||!ca.equals(initial)||!cp.equals(initial))throw fail("LEASE_IDENTITY_CHANGED");}
            if(count!=ref.size||!hex(md.digest()).equals(ref.sha256))throw fail("LEASE_HASH_CHANGED");verified=true;
        }
        @Override public void close()throws IOException {if(closed)return;IOException error=null;try{if(!verified)verifyUnchanged();}catch(IOException e){error=e;}try{stable.closeDuplicateDescriptor(descriptor);}catch(IOException e){if(error==null)error=e;else error.addSuppressed(e);}try{stable.close();}catch(IOException e){if(error==null)error=e;else error.addSuppressed(e);}closed=true;if(error!=null)throw error;}
    }

    public static final class Snapshot {
        public final String localId, lineage, revision, digest, transactionID;
        public final ImportIdentity identity;
        public final Path groupDirectory;
        private final byte[] manifest;
        private final Path root;
        private final IdentityOps ops;
        private Snapshot(String localId,String lineage,String revision,String digest,Path dir,byte[] manifest,Path root,IdentityOps ops,String transactionID,ImportIdentity identity) {
            this.localId=localId;this.lineage=lineage;this.revision=revision;this.digest=digest;
            groupDirectory=dir;this.manifest=manifest.clone();this.root=root;this.ops=ops;this.transactionID=transactionID;this.identity=identity;
        }
        public byte[] readSmall(String member,int cap)throws IOException {
            Ref ref=parseManifest(manifest).get(member); if(ref==null)throw fail("GROUP_MEMBER_UNKNOWN");
            if(ref.size>cap)throw fail("SMALL_MEMBER_CAP_EXCEEDED");
            ByteArrayOutputStream out=new ByteArrayOutputStream((int)ref.size); copyVerified(root,ref,out,ops); return out.toByteArray();
        }
        public InputStream openContent(String member)throws IOException {
            Ref ref=parseManifest(manifest).get(member);if(ref==null)throw fail("GROUP_MEMBER_UNKNOWN");return openVerifiedStream(root,ref,ops);
        }
        public Map<String,Long> memberSizes()throws IOException {TreeMap<String,Long> out=new TreeMap<>();parseManifest(manifest).forEach((k,v)->out.put(k,v.size));return Collections.unmodifiableMap(out);}
        /** Immutable facts for read-only preview models; this does not expose a write capability. */
        public List<MemberSummary> memberSummaries()throws IOException {
            ArrayList<MemberSummary> result=new ArrayList<>();
            for(Ref ref:parseManifest(manifest).values())result.add(new MemberSummary(ref.member,ref.role,ref.materialId,ref.size,ref.sha256));
            result.sort(Comparator.comparing((MemberSummary m)->m.role).thenComparing(m->m.materialId==null?"":m.materialId).thenComparing(m->m.member));
            return Collections.unmodifiableList(result);
        }
        public String memberSha256(String member)throws IOException {Ref ref=parseManifest(manifest).get(member);if(ref==null)throw fail("GROUP_MEMBER_UNKNOWN");return ref.sha256;}
        public ReuseToken reuseToken(String member)throws IOException {
            Ref ref=parseManifest(manifest).get(member);if(ref==null)throw fail("GROUP_MEMBER_UNKNOWN");
            return new ReuseToken(root,digest,ref);
        }
    }
    public static final class MemberSummary {
        public final String member,role,materialId,sha256; public final long size;
        private MemberSummary(String member,String role,String materialId,long size,String sha256){this.member=member;this.role=role;this.materialId=materialId;this.size=size;this.sha256=sha256;}
    }

    public static final class Store {
        private final Path root, objects, groups, visible, transactions;
        private final IdentityOps ops;
        private final ProjectionVerifier verifier;
        private final CompleteGroupVerifier completeGroupVerifier;
        private final Map<String,FileIdentity> immutableWitnesses=new HashMap<>();
        private final CommitObserver observer;
        private final ReadDiagnostics readDiagnostics;
        public Store(Path root, IdentityOps ops, ProjectionVerifier verifier) throws IOException {
            this(root,ops,verifier,null,phase->{},null);
        }
        public Store(Path root, IdentityOps ops, ProjectionVerifier verifier,CommitObserver observer) throws IOException {
            this(root,ops,verifier,null,observer,null);
        }
        public Store(Path root, IdentityOps ops, ProjectionVerifier verifier,
                     CompleteGroupVerifier completeGroupVerifier,CommitObserver observer) throws IOException {
            this(root,ops,verifier,completeGroupVerifier,observer,null);
        }
        public Store(Path root, IdentityOps ops, ProjectionVerifier verifier,
                     CompleteGroupVerifier completeGroupVerifier,CommitObserver observer,
                     ReadDiagnostics readDiagnostics) throws IOException {
            if(ops==null||verifier==null)throw fail("NATIVE_IDENTITY_ADAPTER_REQUIRED");
            this.root=root.toAbsolutePath().normalize();this.ops=ops;this.verifier=verifier;this.completeGroupVerifier=completeGroupVerifier;this.observer=observer==null?phase->{}:observer;
            this.readDiagnostics=readDiagnostics;
            this.ops.ensurePlainDirectory(this.root);this.ops.requirePlainDirectory(this.root);
            objects=this.root.resolve("objects/sha256"); groups=this.root.resolve("groups");
            visible=this.root.resolve("visible"); transactions=this.root.resolve("transactions");
            for(Path p:Arrays.asList(objects,groups,visible,transactions)){this.ops.ensurePlainDirectory(p);this.ops.requirePlainDirectory(p);this.ops.syncDirectory(p);}
        }
        /**
         * Drops only this Store instance's immutable-object verification witnesses so a
         * caller that owns an exclusive, non-shared reader can force fresh member hashing.
         * Marker enumeration and group verification still run on the next read.
         */
        public void resetObjectVerificationWitnessesForFreshRead() {
            synchronized(immutableWitnesses){immutableWitnesses.clear();}
        }
        /** Typed, bounded shelf fields extracted only after full group verification. */
        public static final class ShelfMetadata {
            public final String localId,title;
            public final long updatedAt;
            public final int strokeCount,pageCount;
            public final boolean titlePresent,updatedAtPresent,retired;
            public ShelfMetadata(String localId,String title,long updatedAt,int strokeCount,int pageCount,
                    boolean titlePresent,boolean updatedAtPresent,boolean retired) throws IOException {
                opaqueId(localId);
                if(title!=null&&title.getBytes(StandardCharsets.UTF_8).length>4096)throw fail("VISIBLE_CATALOG_RESULT_BUDGET");
                if(strokeCount<0||pageCount<1)throw fail("GROUP_BODY_INVALID");
                this.localId=localId;this.title=title;this.updatedAt=updatedAt;this.strokeCount=strokeCount;this.pageCount=pageCount;
                this.titlePresent=titlePresent;this.updatedAtPresent=updatedAtPresent;this.retired=retired;
            }
            int retainedBytes(){return 96+(title==null?0:title.getBytes(StandardCharsets.UTF_8).length)+localId.getBytes(StandardCharsets.UTF_8).length;}
        }
        public interface CatalogProjector { ShelfMetadata project(Snapshot verified) throws IOException; }
        /** Strictly scoped summary inventory; it never retains or exposes a complete Snapshot. */
        public static final class CatalogEntry {
            public final ShelfMetadata metadata;
            public final String failureCode;
            private CatalogEntry(ShelfMetadata metadata,String failureCode){this.metadata=metadata;this.failureCode=failureCode;}
        }
        public static final class VisibleCatalog {
            private final Map<String,CatalogEntry> byLocalId;
            private final Set<String> requested;
            private VisibleCatalog(Map<String,CatalogEntry> rows,Set<String> requested){byLocalId=Collections.unmodifiableMap(rows);this.requested=Collections.unmodifiableSet(requested);}
            public CatalogEntry find(String localId){return requested.contains(localId)?byLocalId.get(localId):new CatalogEntry(null,"GROUP_CATALOG_ID_NOT_REQUESTED");}
        }
        private static final class VisibleMarker {
            final String lineage,localId,revision,digest;
            VisibleMarker(String lineage,String localId,String revision,String digest){this.lineage=lineage;this.localId=localId;this.revision=revision;this.digest=digest;}
            boolean same(VisibleMarker other){return other!=null&&lineage.equals(other.lineage)&&localId.equals(other.localId)&&revision.equals(other.revision)&&digest.equals(other.digest);}
        }
        /**
         * Strictly inventories markers twice, fully verifies each requested current group once,
         * and retains only the projector's bounded shelf metadata for this invocation. The
         * application caller holds its catalog mutation fence until it consumes this result;
         * the synchronous projector is trusted to return only shelf fields, never a Snapshot.
         */
        public VisibleCatalog readVisibleCatalog(Set<String> requestedLocalIds,CatalogProjector projector)throws IOException {
            long lookupStarted=System.nanoTime();boolean lookupComplete=false;
            if(readDiagnostics!=null)readDiagnostics.lookupStarted();
            try {
                if(requestedLocalIds==null||projector==null)throw fail("VISIBLE_CATALOG_REQUEST_REQUIRED");
                if(requestedLocalIds.size()>100_000)throw fail("VISIBLE_CATALOG_REQUEST_BUDGET");
                Set<String> requested=new HashSet<>(requestedLocalIds);long requestBytes=0;
                for(String localId:requested){opaqueId(localId);requestBytes+=localId.getBytes(StandardCharsets.UTF_8).length+32L;
                    if(requestBytes>4L*1024*1024)throw fail("VISIBLE_CATALOG_REQUEST_BUDGET");}
                Map<String,VisibleMarker> first=readVisibleMarkers();
                Map<String,VisibleMarker> requestedMarkers=new HashMap<>();Set<String> duplicateIds=new HashSet<>();long selectedBytes=0;
                for(VisibleMarker marker:first.values())if(requested.contains(marker.localId)){
                    selectedBytes+=160L+marker.localId.length()*2L+marker.lineage.length()*2L;
                    if(selectedBytes>8L*1024*1024)throw fail("VISIBLE_CATALOG_MARKER_BUDGET");
                    if(requestedMarkers.put(marker.localId,marker)!=null)duplicateIds.add(marker.localId);
                }
                Map<String,CatalogEntry> result=new HashMap<>();long resultBytes=0;
                for(String requestedId:requested){
                    resultBytes+=64L+requestedId.getBytes(StandardCharsets.UTF_8).length;
                    if(resultBytes>4L*1024*1024||result.size()>=100_000)throw fail("VISIBLE_CATALOG_RESULT_BUDGET");
                    VisibleMarker marker=requestedMarkers.get(requestedId);
                    if(duplicateIds.contains(requestedId)){result.put(requestedId,new CatalogEntry(null,"DUPLICATE_LOCAL_ID"));continue;}
                    if(marker==null){result.put(requestedId,null);continue;}
                    try {
                        resetObjectVerificationWitnessesForFreshRead();
                        Snapshot snapshot=read(marker.lineage);
                        if(snapshot==null||!snapshot.localId.equals(marker.localId)||!snapshot.lineage.equals(marker.lineage)
                                ||!snapshot.revision.equals(marker.revision)||!snapshot.digest.equals(marker.digest))
                            throw fail("VISIBLE_CATALOG_CHANGED");
                        ShelfMetadata metadata=projector.project(snapshot);
                        if(metadata==null||!metadata.localId.equals(requestedId))throw fail("GROUP_CATALOG_PROJECTION_INVALID");
                        resultBytes+=metadata.retainedBytes();if(resultBytes>4L*1024*1024)throw fail("VISIBLE_CATALOG_RESULT_BUDGET");
                        result.put(requestedId,new CatalogEntry(metadata,null));
                    } catch(IOException invalidGroup) {
                        String code=invalidGroup.getMessage();
                        if("VISIBLE_CATALOG_RESULT_BUDGET".equals(code))throw invalidGroup;
                        if(code==null||!code.matches("[A-Z][A-Z0-9_]{0,79}"))code="GROUP_READ_FAILED";
                        result.put(requestedId,new CatalogEntry(null,code));
                    }
                }
                readVisibleMarkers(first);
                lookupComplete=true;
                return new VisibleCatalog(result,requested);
            } finally {
                if(readDiagnostics!=null)readDiagnostics.lookupFinished(Math.max(0,System.nanoTime()-lookupStarted),lookupComplete);
            }
        }
        private Map<String,VisibleMarker> readVisibleMarkers()throws IOException { return readVisibleMarkers(null); }
        /** A comparison pass validates rows against the first inventory without allocating a second map. */
        private Map<String,VisibleMarker> readVisibleMarkers(Map<String,VisibleMarker> expected)throws IOException {
            long started=System.nanoTime();long rows=0,bytes=0;boolean complete=false;
            try {
                ops.requirePlainDirectory(visible);Map<String,VisibleMarker> found=expected==null?new LinkedHashMap<>():null;int count=0;
                try(DirectoryStream<Path> ds=Files.newDirectoryStream(visible)) {
                    for(Path path:ds) {
                        if(++count>100_000)throw fail("VISIBLE_MARKER_COUNT_LIMIT");
                        rows++;String name=path.getFileName().toString();
                        if(!name.endsWith(".marker"))throw fail("VISIBLE_UNKNOWN_CHILD");
                        String lineage=name.substring(0,name.length()-7);id(lineage);
                        byte[] raw=readStableBounded(path,4096,ops);bytes+=raw.length;
                        if(bytes>8L*1024*1024)throw fail("VISIBLE_CATALOG_MARKER_BUDGET");
                        final String text;
                        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}
                        catch(Exception invalid){throw fail("VISIBLE_MARKER_INVALID");}
                        String[] values=text.split("\\n",-1);
                        if(values.length!=5||!values[4].isEmpty()||!values[0].equals(lineage))throw fail("VISIBLE_MARKER_INVALID");
                        opaqueId(values[1]);id(values[2]);hex(values[3]);
                        VisibleMarker parsed=new VisibleMarker(lineage,values[1],values[2],values[3]);
                        if(expected==null){if(found.put(lineage,parsed)!=null)throw fail("VISIBLE_MARKER_DUPLICATE_LINEAGE");}
                        else {VisibleMarker prior=expected.get(lineage);if(prior==null||!prior.same(parsed))throw fail("VISIBLE_CATALOG_CHANGED");}
                    }
                }
                if(expected!=null&&count!=expected.size())throw fail("VISIBLE_CATALOG_CHANGED");
                complete=true;return found;
            } finally {
                if(readDiagnostics!=null)readDiagnostics.markerScan(rows,bytes,Math.max(0,System.nanoTime()-started),complete);
            }
        }
        /** Resolves a marker-backed note by local id; malformed or duplicate markers fail closed. */
        public Snapshot readByLocalId(String localId)throws IOException {
            long lookupStarted=System.nanoTime();boolean lookupComplete=false,markerScanComplete=false;long markerRows=0,markerBytes=0,markerElapsed=0,markerSegmentStarted=System.nanoTime();
            if(readDiagnostics!=null)readDiagnostics.lookupStarted();
            try {
                opaqueId(localId);ops.requirePlainDirectory(visible);Snapshot found=null;int count=0;
                try(DirectoryStream<Path> ds=Files.newDirectoryStream(visible)){
                    for(Path p:ds){
                        if(++count>100_000)throw fail("VISIBLE_MARKER_COUNT_LIMIT");
                        markerRows++;
                        String name=p.getFileName().toString();if(!name.endsWith(".marker"))throw fail("VISIBLE_UNKNOWN_CHILD");
                        String lineage=name.substring(0,name.length()-7);id(lineage);
                        byte[] raw=readStableBounded(p,4096,ops);markerBytes+=raw.length;
                        String[] rows=new String(raw,StandardCharsets.UTF_8).split("\n",-1);
                        if(rows.length!=5||!rows[4].isEmpty()||!rows[0].equals(lineage))throw fail("VISIBLE_MARKER_INVALID");
                        if(rows[1].equals(localId)){if(found!=null)throw fail("DUPLICATE_LOCAL_ID");
                            markerElapsed+=Math.max(0,System.nanoTime()-markerSegmentStarted);
                            try{found=read(lineage);}
                            finally{markerSegmentStarted=System.nanoTime();}
                        }
                    }
                }
                markerScanComplete=true;
                lookupComplete=true;return found;
            } finally {
                long ended=System.nanoTime();markerElapsed+=Math.max(0,ended-markerSegmentStarted);
                if(readDiagnostics!=null){readDiagnostics.markerScan(markerRows,markerBytes,markerElapsed,markerScanComplete);readDiagnostics.lookupFinished(Math.max(0,ended-lookupStarted),lookupComplete);}
            }
        }
        public Snapshot read(String lineage)throws IOException {
            id(lineage);ops.requirePlainDirectory(visible); Path marker=visible.resolve(lineage+".marker");
            if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS))return null;
            byte[] b=readStableBounded(marker,4096,ops);
            if(readDiagnostics!=null)readDiagnostics.markerRecordRead(b.length);
            String[] r=new String(b,StandardCharsets.UTF_8).split("\n",-1);
            if(r.length!=5||!r[4].isEmpty()||!r[0].equals(lineage))throw fail("VISIBLE_MARKER_INVALID");
            opaqueId(r[1]);id(r[2]);hex(r[3]);Path group=groups.resolve(lineage).resolve(r[2]+"-"+r[3]);
            long verifyStarted=System.nanoTime();boolean verifyComplete=false;GroupRecord record;
            try{record=verifyGroup(group,r[3]);verifyComplete=true;}
            finally{if(readDiagnostics!=null)readDiagnostics.groupVerification(Math.max(0,System.nanoTime()-verifyStarted),verifyComplete);}
            if(!record.localId.equals(r[1]))throw fail("VISIBLE_LOCAL_ID_MISMATCH");
            if(!record.lineage.equals(lineage)||!record.revision.equals(r[2])||!record.digest.equals(r[3]))throw fail("VISIBLE_GROUP_IDENTITY_MISMATCH");
            return new Snapshot(r[1],lineage,r[2],r[3],group,record.manifest,root,ops,null,record.identity);
        }
        public Snapshot readRevision(String lineage,String revision,String digest)throws IOException {
            id(lineage);id(revision);hex(digest);Path g=groups.resolve(lineage).resolve(revision+"-"+digest);
            GroupRecord r=verifyGroup(g,digest);return new Snapshot(r.localId,lineage,revision,digest,g,r.manifest,root,ops,null,r.identity);
        }
        /** Bounded listing confined to this store's transaction directory; it never inspects arbitrary paths. */
        public List<String> transactionIDs()throws IOException {
            ops.requirePlainDirectory(transactions);ArrayList<String> out=new ArrayList<>();
            try(DirectoryStream<Path> ds=Files.newDirectoryStream(transactions)){for(Path p:ds){if(out.size()>=100_000)throw fail("TRANSACTION_COUNT_LIMIT");String name=p.getFileName().toString();String canonical;try{canonical=UUID.fromString(name).toString();}catch(RuntimeException ex){throw fail("TRANSACTION_CHILD_UNKNOWN");}if(!name.equals(canonical)||Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))throw fail("TRANSACTION_CHILD_UNSAFE");out.add(name);}}
            Collections.sort(out);return Collections.unmodifiableList(new ArrayList<>(out));
        }
        public Snapshot commit(Envelope e,Preview preview)throws Exception {
            if(e!=null&&e.action==Action.MANUAL_UPDATE_V2)throw fail("MANUAL_UPDATE_V2_PLAN_REQUIRED");
            if(e!=null&&e.action==Action.LEGACY_ADOPTION_V2)throw fail("LEGACY_ADOPTION_V2_PLAN_REQUIRED");
            if(e!=null&&usesCompleteProfile(e.action))throw fail("COMPLETE_GROUP_PLAN_REQUIRED");
            return commitInternal(e,preview,null);
        }
        /** New-format manual updates can only enter through a factory-created immutable plan. */
        public Snapshot commitManualUpdateV2(Envelope e,Preview preview,NativeManualUpdatePlan plan)throws Exception {
            if(e==null||e.action!=Action.MANUAL_UPDATE_V2||plan==null||!plan.authorizes(e))throw fail("MANUAL_UPDATE_V2_PLAN_REQUIRED");
            if(completeGroupVerifier==null)throw fail("MANUAL_UPDATE_V2_COMPLETE_VERIFIER_REQUIRED");
            return commitInternal(e,preview,plan);
        }
        /** In-place legacy adoption publishes only after complete verification, preview and CAS. */
        public Snapshot commitLegacyAdoptionV2(Envelope e,Preview preview,NativeManualUpdatePlan plan)throws Exception {
            if(e==null||e.action!=Action.LEGACY_ADOPTION_V2||plan==null||!plan.authorizesLegacyAdoption(e))throw fail("LEGACY_ADOPTION_V2_PLAN_REQUIRED");
            if(completeGroupVerifier==null)throw fail("COMPLETE_GROUP_VERIFIER_REQUIRED");
            return commitInternal(e,preview,plan);
        }
        private Snapshot commitInternal(Envelope e,Preview preview,NativeManualUpdatePlan plan)throws Exception {
            validateEnvelope(e); Objects.requireNonNull(preview);
            if(usesCompleteProfile(e.action)&&(plan==null||completeGroupVerifier==null))throw fail("COMPLETE_GROUP_VERIFIER_REQUIRED");
            byte[] body=e.body.clone(); verifier.verifyBody(body);
            ops.requirePlainDirectory(transactions);Path tx=transactions.resolve(UUID.randomUUID().toString());Files.createDirectory(tx);ops.requirePlainDirectory(tx);ops.syncDirectory(transactions);
            try(LockLease activeLease=ops.lock(tx.resolve("active.lock"))) {
            TreeMap<String,Ref> refs=new TreeMap<>(); long[] total={0},resource={0};
            try {
                stageBytes(tx,refs,"body.bin","body",null,body,50L<<20,total,resource);
                optional(tx,refs,"pdf",e.pdf,PDF_MAX,total,resource);
                optional(tx,refs,"cover",e.cover,COVER_MAX,total,resource);
                stageList(tx,refs,"video",e.videos,VIDEO_MAX,total,resource);
                stageList(tx,refs,"vault",e.vault,VAULT_MAX,total,resource);
                if(usesCompleteProfile(e.action)||e.action==Action.HISTORY_RESTORE_V1&&e.sourceProvenance!=null)stageBytes(tx,refs,"source-provenance.bin","source-provenance",null,e.sourceProvenance,1<<20,total,resource);
                if(refs.size()>30_010)throw fail("MANIFEST_MEMBER_LIMIT");
                byte[] manifest=encodeManifest(e,refs);if(manifest.length>16L<<20)throw fail("MANIFEST_SIZE_LIMIT");
                String digest=sha(manifest);String lineage=e.identity.destinationLineage;
                saveJournal(tx,new Journal(tx.getFileName().toString(),Phase.STAGED,e.identity,e.revision,e.parentRevision,e.baseRevision,e.baseDigest,digest,e.action));
                Snapshot before=read(lineage);checkBase(e,before);rejectDuplicateLocalId(e.identity.destinationLocalId,lineage);
                Path groupStage=tx.resolve("group");Files.createDirectory(groupStage);ops.requirePlainDirectory(groupStage);ops.syncDirectory(tx);
                createNew(groupStage.resolve("manifest.bin"),manifest);
                createNew(groupStage.resolve("owner.txt"),(e.identity.destinationLocalId+"\n"+lineage+"\n"+e.revision+"\n"+digest+"\n").getBytes(StandardCharsets.UTF_8));
                ops.syncDirectory(groupStage);
                verifyGroup(groupStage,digest);
                Path immutableParent=groups.resolve(lineage);ops.ensurePlainDirectory(immutableParent);ops.requirePlainDirectory(immutableParent);
                ops.syncDirectory(immutableParent.getParent());
                Path immutable=immutableParent.resolve(e.revision+"-"+digest);
                ops.installImmutableGroup(groupStage,immutable);
                ops.syncDirectory(immutableParent);
                VerifiedGroup sealed=new VerifiedGroup(e.identity,e.revision,manifest,refs,digest,root,ops);
                verifyGroup(immutable,digest); // all referenced objects are revalidated before preview
                saveJournal(tx,new Journal(tx.getFileName().toString(),Phase.IMMUTABLE,e.identity,e.revision,e.parentRevision,e.baseRevision,e.baseDigest,digest,e.action));
                if(plan!=null)plan.verifyStaged(sealed);
                if(!preview.approve(before,sealed)){saveJournal(tx,new Journal(tx.getFileName().toString(),Phase.ABORTED,e.identity,e.revision,e.parentRevision,e.baseRevision,e.baseDigest,digest,e.action));throw fail("PREVIEW_CANCELLED");}
                saveJournal(tx,new Journal(tx.getFileName().toString(),Phase.PREVIEW_APPROVED,e.identity,e.revision,e.parentRevision,e.baseRevision,e.baseDigest,digest,e.action));
                observer.afterDurablePhase(Phase.PREVIEW_APPROVED);
                ReentrantLock publication=LOCKS.computeIfAbsent(root+"/__publication__",k->new ReentrantLock(true));
                ReentrantLock lock=LOCKS.computeIfAbsent(root+"/"+lineage,k->new ReentrantLock(true));
                Path lockDir=root.resolve("locks");ops.ensurePlainDirectory(lockDir);ops.requirePlainDirectory(lockDir);
                AutoCloseable sourceMutationLease=(plan!=null&&e.action==Action.LEGACY_ADOPTION_V2)?plan.acquireLegacySourcePublicationGuard():()->{};
                try(sourceMutationLease) {
                    publication.lock();lock.lock();try(LockLease publicationLease=ops.lock(lockDir.resolve("__publication__.lock"));LockLease crossProcess=ops.lock(lockDir.resolve(lineage+".lock"))) {
                        Snapshot now=read(lineage);checkBase(e,now);rejectDuplicateLocalId(e.identity.destinationLocalId,lineage);
                        verifyGroup(immutable,digest);if(plan!=null)plan.verifyStaged(new VerifiedGroup(e.identity,e.revision,manifest,refs,digest,root,ops));
                        publishMarker(lineage,e.identity.destinationLocalId,e.revision,digest,now);
                        saveJournal(tx,new Journal(tx.getFileName().toString(),Phase.COMMITTED,e.identity,e.revision,e.parentRevision,e.baseRevision,e.baseDigest,digest,e.action));
                        GroupRecord published=verifyGroup(immutable,digest);
                        return new Snapshot(published.localId,lineage,e.revision,digest,immutable,published.manifest,root,ops,tx.getFileName().toString(),published.identity);
                    } finally { lock.unlock();publication.unlock(); }
                }
            } catch(Exception ex) { // preserve all UUID-owned evidence; marker changes only after all gates
                if(ex instanceof IOException ioe&&Files.exists(tx.resolve("journal.bin"),LinkOption.NOFOLLOW_LINKS)){
                    try{
                        Journal current=decodeJournal(readStableBounded(tx.resolve("journal.bin"),16*1024,ops));
                        if(Objects.equals(ioe.getMessage(),"LEGACY_SOURCE_CHANGED_BEFORE_PUBLICATION")&&current.action==Action.LEGACY_ADOPTION_V2&&current.phase==Phase.PREVIEW_APPROVED){
                            saveJournal(tx,withPhase(current,Phase.LEGACY_SOURCE_RECHECK_REQUIRED));
                        }else if(Objects.equals(ioe.getMessage(),"BASE_CAS_CONFLICT")||Objects.equals(ioe.getMessage(),"DUPLICATE_LOCAL_ID")){
                            saveJournal(tx,withPhase(current,Phase.CONFLICT));
                        }
                    }catch(IOException ignored){/* retain original conflict and any unknown journal state; recovery is fail-closed */}
                }
                throw ex;
            }
            }
        }
        /** Explicit history restore publishes a fresh immutable revision; old CAS tokens never become current again. */
        public Snapshot restore(String lineage,String revision,String digest,String expectedCurrentRevision,
                                String expectedCurrentDigest,Preview preview)throws Exception {
            Snapshot source=readRevision(lineage,revision,digest);Snapshot current=read(lineage);
            if(current==null||!same(current,expectedCurrentRevision,expectedCurrentDigest))throw fail("BASE_CAS_CONFLICT");
            ManifestData selectedData=parseData(source.manifest);
            String sourceRevision=selectedData.action.equals(Action.HISTORY_RESTORE_V1.name())?selectedData.restoreSourceRevision:source.revision;
            String sourceDigest=selectedData.action.equals(Action.HISTORY_RESTORE_V1.name())?selectedData.restoreSourceDigest:source.digest;
            Map<String,Long> sizes=source.memberSizes();
            ContentInput pdf=snapshotContent(source,"pdf.bin");
            ContentInput cover=snapshotContent(source,"cover.bin");
            List<MemberInput> videos=snapshotMembers(source,"video");List<MemberInput> vault=snapshotMembers(source,"vault");
            String fresh=UUID.randomUUID().toString();
            Envelope restore=new Envelope(source.identity,fresh,current.revision,current.revision,current.digest,
                    Action.HISTORY_RESTORE_V1,source.readSmall("body.bin",(int)BODY_MAX),pdf,cover,videos,vault,
                    sizes.containsKey("source-provenance.bin")?source.readSmall("source-provenance.bin",1<<20):null,sourceRevision,sourceDigest);
            return commitInternal(restore,preview,null);
        }
        private ContentInput snapshotContent(Snapshot snapshot,String member)throws IOException {
            Long size=snapshot.memberSizes().get(member);if(size==null)return null;
            return new ContentInput(null,size,snapshot.memberSha256(member),snapshot.reuseToken(member));
        }
        private List<MemberInput> snapshotMembers(Snapshot snapshot,String role)throws IOException {
            TreeSet<String> materialIds=new TreeSet<>();for(String member:snapshot.memberSizes().keySet())if(member.startsWith(role+"/")){
                String[] parts=member.substring(role.length()+1).split("/",-1);if(parts.length!=2)throw fail("RESTORE_MEMBER_SHAPE_INVALID");materialIds.add(parts[0]);}
            ArrayList<MemberInput> result=new ArrayList<>();for(String material:materialIds){String prefix=role+"/"+material+"/";
                String media=new String(snapshot.readSmall(prefix+"media-type.txt",256),StandardCharsets.UTF_8);if(!media.endsWith("\n"))throw fail("RESTORE_MEDIA_TYPE_INVALID");media=media.substring(0,media.length()-1);
                byte[] metadata=snapshot.readSmall(prefix+"metadata.bin",1<<20);String member=prefix+"content.bin";Long size=snapshot.memberSizes().get(member);if(size==null)throw fail("RESTORE_CONTENT_MISSING");
                ContentInput content=new ContentInput(null,size,snapshot.memberSha256(member),snapshot.reuseToken(member));
                result.add(new MemberInput(role,material,media,metadata,content));
            }return Collections.unmodifiableList(result);
        }
        private void replaceMarker(Path temp,Path marker)throws IOException {
            // Marker replacement is the sole mutable publication point. The adapter must provide atomic replace.
            ops.replaceAtomic(temp,marker);
        }
        private void publishMarker(String lineage,String local,String revision,String digest,Snapshot old)throws IOException {
            Path marker=visible.resolve(lineage+".marker"),temp=visible.resolve("."+UUID.randomUUID()+".marker");
            byte[] markerBytes=(lineage+"\n"+local+"\n"+revision+"\n"+digest+"\n").getBytes(StandardCharsets.UTF_8);
            createNew(temp,markerBytes);if(!Arrays.equals(readStableBounded(temp,4096,ops),markerBytes))throw fail("MARKER_TEMP_VERIFY_FAILED");
            if(old==null)ops.moveNoReplace(temp,marker);else ops.replaceAtomic(temp,marker);ops.syncDirectory(visible);
        }
        private void saveJournal(Path tx,Journal j)throws IOException {
            byte[] bytes=encodeJournal(j);Path target=tx.resolve("journal.bin");
            if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)){createNew(target,bytes);ops.syncDirectory(tx);return;}
            Journal before=decodeJournal(readStableBounded(target,16*1024,ops));
            if(!before.transactionID.equals(j.transactionID)||!sameJournalIdentity(before,j))throw fail("JOURNAL_IDENTITY_CHANGED");
            Path temp=tx.resolve("journal-"+UUID.randomUUID()+".tmp");createNew(temp,bytes);
            if(!Arrays.equals(readStableBounded(temp,16*1024,ops),bytes))throw fail("JOURNAL_TEMP_VERIFY_FAILED");
            ops.replaceAtomic(temp,target);ops.syncDirectory(tx);
        }
        /** Explicit UUID recovery; never scans transactions and never restages external inputs. */
        public Phase recover(String transactionID)throws IOException {
            String canonical;try{canonical=UUID.fromString(transactionID).toString();}catch(RuntimeException ex){throw fail("TRANSACTION_ID_INVALID");}
            if(!canonical.equals(transactionID))throw fail("TRANSACTION_ID_INVALID");
            Path tx=transactions.resolve(canonical);ops.requirePlainDirectory(tx);
            Path activePath=tx.resolve("active.lock");if(!Files.exists(activePath,LinkOption.NOFOLLOW_LINKS))throw fail("RECOVERY_UNKNOWN_RETAINED");
            requireSingleLink(ops.identity(activePath));Optional<LockLease> lease=ops.tryLock(activePath);if(!lease.isPresent())throw fail("TRANSACTION_ACTIVE");
            try(LockLease inactive=lease.get()){
                Path journalPath=tx.resolve("journal.bin");if(ops.noFollowState(journalPath)==IdentityOps.NoFollowState.MISSING)throw fail("RECOVERY_UNKNOWN_RETAINED");Journal j=decodeJournal(readStableBounded(journalPath,16*1024,ops));
                if(!j.transactionID.equals(canonical))throw fail("JOURNAL_TRANSACTION_MISMATCH");
                Path group=groups.resolve(j.identity.destinationLineage).resolve(j.revision+"-"+j.groupDigest);
                if(j.phase==Phase.COMMITTED){verifyGroup(group,j.groupDigest);return Phase.COMMITTED;}
                if(j.phase==Phase.CONFLICT)return Phase.CONFLICT;
                if(j.phase==Phase.ABORTED)return Phase.ABORTED;
                if(j.phase==Phase.LEGACY_SOURCE_RECHECK_REQUIRED)return Phase.LEGACY_SOURCE_RECHECK_REQUIRED;
                if(j.phase==Phase.STAGED||j.phase==Phase.IMMUTABLE){saveJournal(tx,withPhase(j,Phase.ABORTED));return Phase.ABORTED;}
                if(j.phase!=Phase.PREVIEW_APPROVED)throw fail("RECOVERY_UNKNOWN_RETAINED");
                GroupRecord record=verifyGroup(group,j.groupDigest);Snapshot current=read(j.identity.destinationLineage);
                if(current!=null&&current.revision.equals(j.revision)&&current.digest.equals(j.groupDigest)){saveJournal(tx,withPhase(j,Phase.COMMITTED));return Phase.COMMITTED;}
                // A legacy adoption's process-local source guard cannot survive a crash. It must
                // never be replayed from a journal alone; require a fresh capture and preview.
                if(j.action==Action.LEGACY_ADOPTION_V2){saveJournal(tx,withPhase(j,Phase.LEGACY_SOURCE_RECHECK_REQUIRED));return Phase.LEGACY_SOURCE_RECHECK_REQUIRED;}
                if(!journalBaseMatches(j,current)){saveJournal(tx,withPhase(j,Phase.CONFLICT));return Phase.CONFLICT;}
                ReentrantLock publication=LOCKS.computeIfAbsent(root+"/__publication__",k->new ReentrantLock(true));
                ReentrantLock lineage=LOCKS.computeIfAbsent(root+"/"+j.identity.destinationLineage,k->new ReentrantLock(true));
                Path lockDir=root.resolve("locks");ops.ensurePlainDirectory(lockDir);ops.requirePlainDirectory(lockDir);publication.lock();lineage.lock();
                try(LockLease publicationLease=ops.lock(lockDir.resolve("__publication__.lock"));LockLease lineageLease=ops.lock(lockDir.resolve(j.identity.destinationLineage+".lock"))){
                    Snapshot now=read(j.identity.destinationLineage);if(!journalBaseMatches(j,now)){saveJournal(tx,withPhase(j,Phase.CONFLICT));return Phase.CONFLICT;}
                    verifyGroup(group,j.groupDigest);
                    rejectDuplicateLocalId(j.identity.destinationLocalId,j.identity.destinationLineage);
                    publishMarker(j.identity.destinationLineage,record.localId,j.revision,j.groupDigest,now);
                    saveJournal(tx,withPhase(j,Phase.COMMITTED));return Phase.COMMITTED;
                }finally{lineage.unlock();publication.unlock();}
            }
        }
        private boolean journalBaseMatches(Journal j,Snapshot current){return j.baseRevision==null?current==null:current!=null&&current.revision.equals(j.baseRevision)&&current.digest.equals(j.baseDigest)&&current.localId.equals(j.identity.destinationLocalId)&&current.lineage.equals(j.identity.destinationLineage)&&(j.action==Action.MANUAL_UPDATE_V2||j.action==Action.HISTORY_RESTORE_V1||current.identity.equals(j.identity));}
        private boolean sameJournalIdentity(Journal a,Journal b){return a.identity.equals(b.identity)&&Objects.equals(a.revision,b.revision)&&Objects.equals(a.parentRevision,b.parentRevision)&&Objects.equals(a.baseRevision,b.baseRevision)&&Objects.equals(a.baseDigest,b.baseDigest)&&Objects.equals(a.groupDigest,b.groupDigest)&&a.action==b.action;}
        private Journal withPhase(Journal j,Phase phase){return new Journal(j.transactionID,phase,j.identity,j.revision,j.parentRevision,j.baseRevision,j.baseDigest,j.groupDigest,j.action);}
        private byte[] encodeJournal(Journal j)throws IOException {
            ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.write(j.action==Action.MANUAL_UPDATE_V2?JOURNAL_DOMAIN_V2:j.action==Action.LEGACY_ADOPTION_V2?JOURNAL_DOMAIN_V3:JOURNAL_DOMAIN);text(d,j.transactionID);text(d,j.phase.name());text(d,j.identity.originLineage);text(d,j.identity.originLocalId);text(d,j.identity.destinationLocalId);text(d,j.identity.destinationLineage);nullable(d,j.identity.parentOriginLineage);nullable(d,j.identity.parentOriginLocalId);text(d,j.revision);nullable(d,j.parentRevision);nullable(d,j.baseRevision);nullable(d,j.baseDigest);nullable(d,j.groupDigest);text(d,j.action.name());d.flush();if(b.size()>16*1024)throw fail("JOURNAL_SIZE_LIMIT");return b.toByteArray();
        }
        static String parseJournalDomain(byte[] bytes)throws IOException {
            if(bytes==null)throw fail("JOURNAL_INVALID");
            int limit=Math.min(bytes.length,128),end=-1;
            for(int i=0;i<limit;i++){if(bytes[i]==0){end=i;break;}}
            // Preserve the legacy corruption contract: a missing/truncated domain terminator
            // is malformed journal data. A terminated but unsupported domain is distinct.
            if(end<0)throw fail("JOURNAL_INVALID");
            String domain=new String(bytes,0,end,StandardCharsets.US_ASCII);
            if(!domain.equals("PadNote/StreamingTransaction/v1")
                    &&!domain.equals("PadNote/StreamingManualUpdateTransaction/v2")
                    &&!domain.equals("PadNote/StreamingLegacyAdoptionTransaction/v2"))
                throw fail("JOURNAL_DOMAIN_INVALID");
            return domain;
        }
        private Journal decodeJournal(byte[] bytes)throws IOException {
            try(DataInputStream d=new DataInputStream(new ByteArrayInputStream(bytes))){
                String domain=parseJournalDomain(bytes);readN(d,domain.getBytes(StandardCharsets.US_ASCII).length+1);
                boolean v1=domain.equals("PadNote/StreamingTransaction/v1");
                boolean v2=domain.equals("PadNote/StreamingManualUpdateTransaction/v2");
                boolean v3=domain.equals("PadNote/StreamingLegacyAdoptionTransaction/v2");
                if(!v1&&!v2&&!v3)throw fail("JOURNAL_DOMAIN_INVALID");
                String tx=text(d);Phase phase=Phase.valueOf(text(d));String ol=text(d),oi=text(d),dl=text(d),dd=text(d),pl=nullable(d),pi=nullable(d);
                String revision=text(d),parent=nullable(d),base=nullable(d),baseDigest=nullable(d),digest=nullable(d);Action action=Action.valueOf(text(d));if(v2!=(action==Action.MANUAL_UPDATE_V2)||v3!=(action==Action.LEGACY_ADOPTION_V2)||v1&&usesCompleteProfile(action))throw fail("JOURNAL_PROFILE_ACTION_MISMATCH");
                if(d.read()!=-1)throw fail("JOURNAL_TRAILING_DATA");
                if(!UUID.fromString(tx).toString().equals(tx))throw fail("JOURNAL_TRANSACTION_INVALID");id(ol);opaqueId(oi);opaqueId(dl);id(dd);if(pl!=null)id(pl);if(pi!=null)opaqueId(pi);id(revision);if(parent!=null)id(parent);if(base!=null)id(base);if(baseDigest!=null)hex(baseDigest);if(digest!=null)hex(digest);if((base==null)!=(baseDigest==null))throw fail("JOURNAL_BASE_PAIR");
                return new Journal(tx,phase,new ImportIdentity(ol,oi,dl,dd,pl,pi),revision,parent,base,baseDigest,digest,action);
            }catch(IllegalArgumentException|EOFException ex){throw fail("JOURNAL_INVALID");}
        }
        private VerifiedGroup loadVerified(Snapshot s)throws IOException {
            GroupRecord r=verifyGroup(s.groupDirectory,s.digest);
            return new VerifiedGroup(r.identity,r.revision,r.manifest,r.refs,r.digest,root,ops);
        }
        private void validateEnvelope(Envelope e)throws IOException {
            if(e==null||e.identity==null||e.body==null||e.videos==null||e.vault==null||e.action==null)throw fail("FIVE_COMPONENT_SCOPE_REQUIRED");
            ImportIdentity i=e.identity;id(i.originLineage);opaqueId(i.originLocalId);opaqueId(i.destinationLocalId);id(i.destinationLineage);id(e.revision);
            if((e.baseRevision==null)!=(e.baseDigest==null))throw fail("BASE_PAIR_REQUIRED");
            if(e.baseRevision!=null){id(e.baseRevision);hex(e.baseDigest);}
            switch(e.action){
                case FIRST_IMPORT -> { if(e.baseRevision!=null||!i.originLineage.equals(i.destinationLineage)||i.originLocalId.equals(i.destinationLocalId))throw fail("FIRST_IMPORT_IDENTITY_INVALID"); }
                case UPDATE -> { if(e.baseRevision==null||(i.parentOriginLineage==null)!=(i.parentOriginLocalId==null))throw fail("UPDATE_IDENTITY_INVALID");if(i.parentOriginLineage==null&&!i.originLineage.equals(i.destinationLineage))throw fail("UPDATE_IDENTITY_INVALID");if(i.parentOriginLineage!=null){id(i.parentOriginLineage);opaqueId(i.parentOriginLocalId);if(!i.originLineage.equals(i.parentOriginLineage)||!i.originLocalId.equals(i.parentOriginLocalId)||i.destinationLineage.equals(i.originLineage))throw fail("UPDATE_FORK_IDENTITY_INVALID");} }
                case EXPLICIT_COPY -> { if(e.baseRevision!=null||i.parentOriginLineage==null||i.parentOriginLocalId==null||i.destinationLineage.equals(i.parentOriginLineage)||i.destinationLocalId.equals(i.parentOriginLocalId)||!i.originLineage.equals(i.parentOriginLineage)||!i.originLocalId.equals(i.parentOriginLocalId))throw fail("COPY_LINEAGE_FORK_REQUIRED"); id(i.parentOriginLineage);opaqueId(i.parentOriginLocalId); }
                case MANUAL_UPDATE_V2 -> { if(e.baseRevision==null||e.sourceProvenance==null||e.sourceProvenance.length==0||e.sourceProvenance.length>(1<<20)||(i.parentOriginLineage==null)!=(i.parentOriginLocalId==null))throw fail("MANUAL_UPDATE_V2_IDENTITY_INVALID");if(i.parentOriginLineage!=null){id(i.parentOriginLineage);opaqueId(i.parentOriginLocalId);} }
                case LEGACY_ADOPTION_V2 -> { if(e.baseRevision!=null||e.sourceProvenance==null||e.sourceProvenance.length==0||e.sourceProvenance.length>(1<<20)||i.parentOriginLineage!=null||i.parentOriginLocalId!=null||!i.originLineage.equals(i.destinationLineage)||!i.originLocalId.equals(i.destinationLocalId))throw fail("LEGACY_ADOPTION_V2_IDENTITY_INVALID"); }
                case HISTORY_RESTORE_V1 -> { if(e.baseRevision==null||e.parentRevision==null||e.restoreSourceRevision==null||e.restoreSourceDigest==null||e.sourceProvenance!=null&&e.sourceProvenance.length>(1<<20)||e.restoreSourceRevision.equals(e.revision))throw fail("HISTORY_RESTORE_IDENTITY_INVALID");id(e.restoreSourceRevision);hex(e.restoreSourceDigest); }
            }
            if(e.action!=Action.HISTORY_RESTORE_V1&&e.parentRevision!=null&&!e.parentRevision.equals(e.baseRevision))throw fail("PARENT_BASE_MISMATCH");
            if(e.action==Action.HISTORY_RESTORE_V1&&!Objects.equals(e.parentRevision,e.baseRevision))throw fail("HISTORY_RESTORE_PARENT_BASE_MISMATCH");
            if(e.body.length>BODY_MAX||(e.pdf!=null&&(e.pdf.size<0||e.pdf.size>PDF_MAX))||(e.cover!=null&&(e.cover.size<0||e.cover.size>COVER_MAX)))throw fail("COMPONENT_SIZE_LIMIT");
            if(e.videos.size()+e.vault.size()>10_000)throw fail("RESOURCE_COUNT_LIMIT");
        }
        private void checkBase(Envelope e,Snapshot current)throws IOException {
            if(e.baseRevision==null){if(current!=null)throw fail("BASE_CAS_CONFLICT");}
            else if(current==null||!current.revision.equals(e.baseRevision)||!current.digest.equals(e.baseDigest)||!current.localId.equals(e.identity.destinationLocalId)||!current.lineage.equals(e.identity.destinationLineage)||(!(usesCompleteProfile(e.action)||e.action==Action.HISTORY_RESTORE_V1)&&!current.identity.equals(e.identity)))throw fail("BASE_CAS_CONFLICT");
        }
        private void rejectDuplicateLocalId(String localId,String ownLineage)throws IOException {
            ops.requirePlainDirectory(visible);int n=0;
            try(DirectoryStream<Path> ds=Files.newDirectoryStream(visible)){
                for(Path p:ds){if(++n>100_000)throw fail("VISIBLE_MARKER_COUNT_LIMIT");String name=p.getFileName().toString();if(!name.endsWith(".marker"))throw fail("VISIBLE_UNKNOWN_CHILD");String lineage=name.substring(0,name.length()-7);id(lineage);if(lineage.equals(ownLineage))continue;
                    String[] rows=new String(readStableBounded(p,4096,ops),StandardCharsets.UTF_8).split("\n",-1);if(rows.length!=5||!rows[4].isEmpty()||!rows[0].equals(lineage))throw fail("VISIBLE_MARKER_INVALID");opaqueId(rows[1]);if(rows[1].equals(localId))throw fail("DUPLICATE_LOCAL_ID");
                }
            }
        }
        private void optional(Path tx,Map<String,Ref> refs,String role,ContentInput value,long cap,long[] total,long[] resource)throws IOException {
            byte[] state=(value==null?"absent\n":"present\n").getBytes(StandardCharsets.US_ASCII);
            stageBytes(tx,refs,role+".state",role+"-state",null,state,64,total,resource);
            if(value!=null)stageContent(tx,refs,role+".bin",role,null,value,cap,total,resource);
        }
        private void stageList(Path tx,Map<String,Ref> refs,String role,List<MemberInput> rows,long cap,long[] total,long[] resource)throws IOException {
            byte[] state="present\n".getBytes(StandardCharsets.US_ASCII);stageBytes(tx,refs,role+".state",role+"-state",null,state,64,total,resource);
            HashSet<String> ids=new HashSet<>();
            for(MemberInput input:rows){
                if(input==null||input.content==null||(input.content.source==null&&input.content.reuse==null)||input.metadata==null||input.mediaType==null||input.mediaType.trim().isEmpty()||!role.equals(input.role))throw fail("MATERIAL_IDENTITY_OR_METADATA_MISSING");
                id(input.materialId);if(!ids.add(input.materialId))throw fail("DUPLICATE_MATERIAL_ID");
                if(input.metadata.length>1<<20||input.mediaType.getBytes(StandardCharsets.UTF_8).length>256)throw fail("METADATA_SIZE_LIMIT");
                verifier.verifyMetadata(role,input.materialId,input.metadata.clone());
                String prefix=role+"/"+input.materialId+"/";
                stageBytes(tx,refs,prefix+"media-type.txt",role,input.materialId,(input.mediaType+"\n").getBytes(StandardCharsets.UTF_8),256,total,resource);
                stageBytes(tx,refs,prefix+"metadata.bin",role,input.materialId,input.metadata,1<<20,total,resource);
                String contentMember=prefix+"content.bin";
                stageContent(tx,refs,contentMember,role,input.materialId,input.content,cap,total,resource);
            }
        }
        private void stageBytes(Path tx,Map<String,Ref> refs,String member,String role,String materialId,byte[] bytes,long cap,long[] total,long[] resource)throws IOException {
            if(bytes==null||bytes.length>cap)throw fail("RESOURCE_SIZE_LIMIT");
            String hash=sha(bytes);Path object=ensureObject(tx,hash,bytes.length,hash,new ByteArrayInputStream(bytes));
            addTotals(total,resource,bytes.length,member);refs.put(member,new Ref(member,role,materialId,bytes.length,hash));
        }
        private void stageFile(Path tx,Map<String,Ref> refs,String member,String role,String materialId,Path source,long size,String expectedHash,long cap,long[] total,long[] resource)throws IOException {
            if(size<0||size>cap)throw fail("RESOURCE_SIZE_LIMIT");hex(expectedHash);
            Path object=ensureObject(tx,expectedHash,size,expectedHash,source);
            addTotals(total,resource,size,member);refs.put(member,new Ref(member,role,materialId,size,expectedHash));
        }
        private void stageContent(Path tx,Map<String,Ref> refs,String member,String role,String materialId,ContentInput input,long cap,long[] total,long[] resource)throws IOException {
            if(input==null||input.size<0||input.size>cap)throw fail("RESOURCE_SIZE_LIMIT");hex(input.sha256);
            if(input.reuse!=null){
                ReuseToken t=input.reuse;
                if(input.source!=null||!root.equals(t.root)||t.groupDigest==null||!t.role.equals(role)||!Objects.equals(t.materialId,materialId)||t.size!=input.size||!t.sha256.equals(input.sha256)||!t.member.equals(member))throw fail("REUSE_TOKEN_SCOPE_MISMATCH");
                verifyObject(objects.resolve(t.sha256),t.size,t.sha256);
                refs.put(member,new Ref(member,role,materialId,t.size,t.sha256));addTotals(total,resource,t.size,member);return;
            }
            if(input.source==null)throw fail("CONTENT_SOURCE_REQUIRED");
            stageFile(tx,refs,member,role,materialId,input.source,input.size,input.sha256,cap,total,resource);
        }
        private void addTotals(long[] total,long[] resource,long n,String member)throws IOException {
            total[0]=Math.addExact(total[0],n);if(total[0]>GROUP_TOTAL_MAX)throw fail("GROUP_SIZE_LIMIT");
            if(!member.equals("body.bin")&&!member.endsWith(".state")&&!member.endsWith("media-type.txt")&&!member.endsWith("metadata.bin")){resource[0]=Math.addExact(resource[0],n);if(resource[0]>RESOURCE_TOTAL_MAX)throw fail("RESOURCE_TOTAL_LIMIT");}
        }
        private Path ensureObject(Path tx,String hash,long expectedSize,String expectedHash,Path source)throws IOException {
            ops.requirePlainDirectory(objects);ops.requirePlainDirectory(tx);
            Path dst=objects.resolve(hash);
            Path tmp=tx.resolve("object-"+UUID.randomUUID()+".tmp");
            try(StableInput in=ops.open(source,expectedSize);FileChannel out=FileChannel.open(tmp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                FileIdentity before=in.before();MessageDigest md=digest();long count=0;byte[] buf=new byte[COPY_BUFFER_BYTES];
                for(int n;(n=in.stream().read(buf))!=-1;){if(n==0)continue;count+=n;if(count>expectedSize)throw fail("SOURCE_GREW");md.update(buf,0,n);ByteBuffer b=ByteBuffer.wrap(buf,0,n);while(b.hasRemaining())out.write(b);}
                if(count!=expectedSize||!hex(md.digest()).equals(expectedHash))throw fail("SOURCE_SIZE_OR_HASH_MISMATCH");out.force(true);
                FileIdentity after=in.after(),pathAfter=ops.identity(source);requireSingleLink(in.pathBefore());requireSingleLink(before);requireSingleLink(after);requireSingleLink(pathAfter);ops.requireStable(source,in.pathBefore(),before,after,pathAfter);
            }
            if(Files.exists(dst,LinkOption.NOFOLLOW_LINKS)){
                verifyObject(dst,expectedSize,expectedHash);
                // The input snapshot was still fully consumed and checked. Remove only our private verified temp.
                Files.delete(tmp);
            } else try{ops.moveNoReplace(tmp,dst);ops.syncDirectory(objects);}catch(FileAlreadyExistsException race){verifyObject(dst,expectedSize,expectedHash);Files.delete(tmp);}
            verifyObject(dst,expectedSize,expectedHash);return dst;
        }
        private Path ensureObject(Path tx,String hash,long size,String expectedHash,InputStream source)throws IOException {
            ops.requirePlainDirectory(objects);ops.requirePlainDirectory(tx);
            Path dst=objects.resolve(hash);
            Path tmp=tx.resolve("object-"+UUID.randomUUID()+".tmp");MessageDigest md=digest();long count=0;
            try(FileChannel out=FileChannel.open(tmp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                byte[] buf=new byte[COPY_BUFFER_BYTES];for(int n;(n=source.read(buf))!=-1;){if(n==0)continue;count+=n;if(count>size)throw fail("SOURCE_GREW");md.update(buf,0,n);ByteBuffer b=ByteBuffer.wrap(buf,0,n);while(b.hasRemaining())out.write(b);}if(count!=size||!hex(md.digest()).equals(expectedHash))throw fail("SOURCE_SIZE_OR_HASH_MISMATCH");out.force(true);
            } finally {source.close();}
            if(Files.exists(dst,LinkOption.NOFOLLOW_LINKS)){verifyObject(dst,size,expectedHash);Files.delete(tmp);}
            else try{ops.moveNoReplace(tmp,dst);ops.syncDirectory(objects);}catch(FileAlreadyExistsException race){verifyObject(dst,size,expectedHash);Files.delete(tmp);}
            verifyObject(dst,size,expectedHash);return dst;
        }
        private void verifyObject(Path path,long size,String hash)throws IOException {
            ops.requirePlainDirectory(objects);
            if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw fail("OBJECT_NOT_PLAIN");
            FileIdentity witness;
            synchronized(immutableWitnesses){witness=immutableWitnesses.get(hash);}
            if(witness!=null){
                long verifyStarted=System.nanoTime();
                try{try(StableInput in=ops.open(path,size)){requireSingleLink(in.pathBefore());FileIdentity fdBefore=in.before(),fdAfter=in.after(),pathAfter=ops.identity(path);requireSingleLink(fdBefore);requireSingleLink(fdAfter);requireSingleLink(pathAfter);ops.requireStable(path,in.pathBefore(),fdBefore,fdAfter,pathAfter);ops.requireImmutable(path,witness,fdAfter);}
                    if(witness.size()!=size)throw fail("OBJECT_SIZE_MISMATCH");
                }finally{if(readDiagnostics!=null)readDiagnostics.objectVerification(0,Math.max(0,System.nanoTime()-verifyStarted),true);}
                return;
            }
            long verifyStarted=System.nanoTime(),bytesHashed=0;
            try{try(StableInput in=ops.open(path,size)){FileIdentity b=in.before();MessageDigest md=digest();long n=0;byte[] buf=new byte[COPY_BUFFER_BYTES];
                    for(int k;(k=in.stream().read(buf))!=-1;){if(k==0)continue;n+=k;bytesHashed=n;if(n>size)throw fail("OBJECT_SIZE_MISMATCH");md.update(buf,0,k);}
                    FileIdentity a=in.after(),p=ops.identity(path);requireSingleLink(in.pathBefore());requireSingleLink(b);requireSingleLink(a);requireSingleLink(p);ops.requireStable(path,in.pathBefore(),b,a,p);if(n!=size||!hex(md.digest()).equals(hash))throw fail("OBJECT_HASH_MISMATCH");
                    FileIdentity sealed=ops.sealImmutable(path,p);ops.requireImmutable(path,sealed,ops.identity(path));
                    synchronized(immutableWitnesses){immutableWitnesses.put(hash,sealed);}
                }
            }finally{if(readDiagnostics!=null)readDiagnostics.objectVerification(bytesHashed,Math.max(0,System.nanoTime()-verifyStarted),false);}
        }
        private static final class GroupRecord {final String localId,lineage,revision,digest;final byte[] manifest;final ImportIdentity identity;final Map<String,Ref> refs;GroupRecord(String localId,String lineage,String revision,String digest,byte[] manifest,ImportIdentity identity,Map<String,Ref> refs){this.localId=localId;this.lineage=lineage;this.revision=revision;this.digest=digest;this.manifest=manifest;this.identity=identity;this.refs=refs;}}
        private GroupRecord verifyGroup(Path dir,String expectedDigest)throws IOException {
            ops.requirePlainDirectory(dir);byte[] manifest=readStableBounded(dir.resolve("manifest.bin"),16<<20,ops);if(!sha(manifest).equals(expectedDigest))throw fail("GROUP_DIGEST_MISMATCH");
            String owner=new String(readStableBounded(dir.resolve("owner.txt"),4096,ops),StandardCharsets.UTF_8);String[] o=owner.split("\n",-1);if(o.length!=5||!o[4].isEmpty())throw fail("GROUP_OWNER_INVALID");
            HashSet<String> actual=new HashSet<>();try(DirectoryStream<Path> ds=Files.newDirectoryStream(dir)){for(Path p:ds){String n=p.getFileName().toString();if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||!actual.add(n))throw fail("GROUP_CHILD_UNSAFE");}}
            if(!actual.equals(setOf("manifest.bin","owner.txt")))throw fail("GROUP_CHILD_SET_MISMATCH");
            Map<String,Ref> refs=parseManifest(manifest);ManifestData md=parseData(manifest);
            if(!o[0].equals(md.local)||!o[1].equals(md.lineage)||!o[2].equals(md.revision)||!o[3].equals(expectedDigest))throw fail("GROUP_OWNER_MANIFEST_MISMATCH");
            opaqueId(md.local);id(md.lineage);id(md.revision);id(md.originLineage);opaqueId(md.originLocal);
            if(md.parentOriginLineage!=null)id(md.parentOriginLineage);if(md.parentOriginLocal!=null)opaqueId(md.parentOriginLocal);
            if((md.base==null)!=(md.digest==null)||(md.parent!=null&&!md.parent.equals(md.base)))throw fail("MANIFEST_BASE_INVALID");
            Action action;try{action=Action.valueOf(md.action);}catch(IllegalArgumentException ex){throw fail("MANIFEST_ACTION_INVALID");}
            if(action==Action.FIRST_IMPORT&&(!md.lineage.equals(md.originLineage)||md.local.equals(md.originLocal)||md.base!=null))throw fail("MANIFEST_IMPORT_IDENTITY_INVALID");
            if(action==Action.UPDATE&&(md.base==null||(md.parentOriginLineage==null)!=(md.parentOriginLocal==null)||(md.parentOriginLineage==null&&!md.lineage.equals(md.originLineage))||(md.parentOriginLineage!=null&&(md.lineage.equals(md.originLineage)||!md.originLineage.equals(md.parentOriginLineage)||!md.originLocal.equals(md.parentOriginLocal)))))throw fail("MANIFEST_UPDATE_IDENTITY_INVALID");
            if(action==Action.EXPLICIT_COPY&&(md.base!=null||md.lineage.equals(md.parentOriginLineage)||!Objects.equals(md.originLineage,md.parentOriginLineage)||!Objects.equals(md.originLocal,md.parentOriginLocal)))throw fail("MANIFEST_COPY_IDENTITY_INVALID");
            if(action==Action.MANUAL_UPDATE_V2&&(md.base==null||(md.parentOriginLineage==null)!=(md.parentOriginLocal==null)))throw fail("MANUAL_UPDATE_V2_IDENTITY_INVALID");
            if(action==Action.LEGACY_ADOPTION_V2&&(md.base!=null||!md.lineage.equals(md.originLineage)||!md.local.equals(md.originLocal)||md.parentOriginLineage!=null||md.parentOriginLocal!=null))throw fail("LEGACY_ADOPTION_V2_IDENTITY_INVALID");
            if(action==Action.HISTORY_RESTORE_V1&&(md.base==null||md.parent==null||md.restoreSourceRevision==null||md.restoreSourceDigest==null||md.restoreSourceRevision.equals(md.revision)))throw fail("HISTORY_RESTORE_IDENTITY_INVALID");
            for(Ref ref:refs.values())verifyObject(objects.resolve(ref.sha256),ref.size,ref.sha256);
            validateMembers(refs,action);
            if(usesCompleteProfile(action)||action==Action.HISTORY_RESTORE_V1){
                if(action!=Action.HISTORY_RESTORE_V1&&!(completeGroupVerifier instanceof NativeManualUpdatePlan.ConcreteCompleteGroupVerifier))throw fail("COMPLETE_PROFILE_CONCRETE_VERIFIER_REQUIRED");
                Ref provenance=refs.get("source-provenance.bin");
                if(action!=Action.HISTORY_RESTORE_V1&&(provenance==null||!provenance.role.equals("source-provenance")||provenance.materialId!=null||provenance.size<1||provenance.size>(1<<20)))throw fail("COMPLETE_PROFILE_PROVENANCE_REQUIRED");
                VerifiedGroup complete=new VerifiedGroup(parseIdentity(manifest),md.revision,manifest,refs,expectedDigest,root,ops);
                byte[] sourceProvenance=provenance==null?null:complete.readSmall("source-provenance.bin",1<<20);
                if(action==Action.HISTORY_RESTORE_V1){verifyHistoryRestoreBinding(complete,md,sourceProvenance);}
                else {verifyManualV2Binding(complete,sourceProvenance,md.local,md.lineage,md.revision);completeGroupVerifier.verifyCompleteGroup(complete,sourceProvenance);}
            }
            return new GroupRecord(o[0],o[1],o[2],o[3],manifest,parseIdentity(manifest),refs);
        }
        private void verifyHistoryRestoreBinding(VerifiedGroup restored,ManifestData md,byte[] provenance)throws IOException {
            if(restored.revision.equals(md.restoreSourceRevision))throw fail("HISTORY_RESTORE_PROVENANCE_INVALID");
            Path sourcePath=groups.resolve(md.lineage).resolve(md.restoreSourceRevision+"-"+md.restoreSourceDigest);
            GroupRecord source=readHistorySource(sourcePath,md.restoreSourceDigest);ManifestData sourceData=parseData(source.manifest);
            if(Action.HISTORY_RESTORE_V1.name().equals(sourceData.action)||!source.revision.equals(md.restoreSourceRevision)||!source.lineage.equals(md.lineage)||!source.localId.equals(md.local)||!source.identity.equals(restored.identity))throw fail("HISTORY_RESTORE_SOURCE_INVALID");
            Ref sourceProvenance=source.refs.get("source-provenance.bin"),restoredProvenance=restored.refs.get("source-provenance.bin");
            if((sourceProvenance==null)!=(restoredProvenance==null)||sourceProvenance!=null&&(!sameRef(sourceProvenance,restoredProvenance)||!Arrays.equals(readRefSmall(sourceProvenance,1<<20),provenance)))throw fail("HISTORY_RESTORE_PROVENANCE_MISMATCH");
            if(source.refs.size()!=restored.refs.size())throw fail("HISTORY_RESTORE_MEMBERS_MISMATCH");
            for(Map.Entry<String,Ref> entry:source.refs.entrySet()){Ref actual=restored.refs.get(entry.getKey());if(actual==null||!sameRef(entry.getValue(),actual))throw fail("HISTORY_RESTORE_MEMBERS_MISMATCH");}
            Action sourceAction;try{sourceAction=Action.valueOf(sourceData.action);}catch(IllegalArgumentException ex){throw fail("HISTORY_RESTORE_SOURCE_ACTION_INVALID");}
            validateMembers(source.refs,sourceAction);
            if(usesCompleteProfile(sourceAction)){
                if(!(completeGroupVerifier instanceof NativeManualUpdatePlan.ConcreteCompleteGroupVerifier)||sourceProvenance==null)throw fail("HISTORY_RESTORE_SOURCE_PROFILE_INVALID");
                VerifiedGroup sourceGroup=new VerifiedGroup(source.identity,source.revision,source.manifest,source.refs,source.digest,root,ops);
                byte[] encoded=sourceGroup.readSmall("source-provenance.bin",1<<20);
                verifyManualV2Binding(sourceGroup,encoded,source.localId,source.lineage,source.revision);
                completeGroupVerifier.verifyCompleteGroup(sourceGroup,encoded);
            }
        }
        /** The restored group already verified every content object; validate the canonical source manifest and semantics without hashing every large object a second time. */
        private GroupRecord readHistorySource(Path dir,String expectedDigest)throws IOException {
            ops.requirePlainDirectory(dir);byte[] manifest=readStableBounded(dir.resolve("manifest.bin"),16<<20,ops);
            if(!sha(manifest).equals(expectedDigest))throw fail("HISTORY_RESTORE_SOURCE_DIGEST_MISMATCH");
            String owner=new String(readStableBounded(dir.resolve("owner.txt"),4096,ops),StandardCharsets.UTF_8);String[] rows=owner.split("\n",-1);
            if(rows.length!=5||!rows[4].isEmpty())throw fail("HISTORY_RESTORE_SOURCE_OWNER_INVALID");
            HashSet<String> actual=new HashSet<>();try(DirectoryStream<Path> ds=Files.newDirectoryStream(dir)){for(Path path:ds){String name=path.getFileName().toString();if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!actual.add(name))throw fail("HISTORY_RESTORE_SOURCE_CHILD_UNSAFE");}}
            if(!actual.equals(setOf("manifest.bin","owner.txt")))throw fail("HISTORY_RESTORE_SOURCE_CHILD_SET_INVALID");
            ManifestData data=parseData(manifest);if(Action.HISTORY_RESTORE_V1.name().equals(data.action))throw fail("HISTORY_RESTORE_SOURCE_NOT_CANONICAL");Map<String,Ref> refs=data.refs;
            if(!rows[0].equals(data.local)||!rows[1].equals(data.lineage)||!rows[2].equals(data.revision)||!rows[3].equals(expectedDigest))throw fail("HISTORY_RESTORE_SOURCE_OWNER_MISMATCH");
            opaqueId(data.local);id(data.lineage);id(data.revision);id(data.originLineage);opaqueId(data.originLocal);
            if((data.base==null)!=(data.digest==null)||(data.parent!=null&&!data.parent.equals(data.base)))throw fail("HISTORY_RESTORE_SOURCE_BASE_INVALID");
            ImportIdentity identity=parseIdentity(manifest);if(data.parentOriginLineage!=null)id(data.parentOriginLineage);if(data.parentOriginLocal!=null)opaqueId(data.parentOriginLocal);Action sourceAction;try{sourceAction=Action.valueOf(data.action);}catch(IllegalArgumentException ex){throw fail("HISTORY_RESTORE_SOURCE_ACTION_INVALID");}
            if(sourceAction==Action.FIRST_IMPORT&&(!data.lineage.equals(data.originLineage)||data.local.equals(data.originLocal)||data.base!=null))throw fail("HISTORY_RESTORE_SOURCE_IMPORT_INVALID");
            if(sourceAction==Action.UPDATE&&(data.base==null||(data.parentOriginLineage==null)!=(data.parentOriginLocal==null)||(data.parentOriginLineage==null&&!data.lineage.equals(data.originLineage))||(data.parentOriginLineage!=null&&(data.lineage.equals(data.originLineage)||!data.originLineage.equals(data.parentOriginLineage)||!data.originLocal.equals(data.parentOriginLocal)))))throw fail("HISTORY_RESTORE_SOURCE_UPDATE_INVALID");
            if(sourceAction==Action.EXPLICIT_COPY&&(data.base!=null||data.lineage.equals(data.parentOriginLineage)||!Objects.equals(data.originLineage,data.parentOriginLineage)||!Objects.equals(data.originLocal,data.parentOriginLocal)))throw fail("HISTORY_RESTORE_SOURCE_COPY_INVALID");
            if(sourceAction==Action.MANUAL_UPDATE_V2&&(data.base==null||(data.parentOriginLineage==null)!=(data.parentOriginLocal==null)))throw fail("HISTORY_RESTORE_SOURCE_MANUAL_INVALID");
            if(sourceAction==Action.LEGACY_ADOPTION_V2&&(data.base!=null||!data.lineage.equals(data.originLineage)||!data.local.equals(data.originLocal)||data.parentOriginLineage!=null||data.parentOriginLocal!=null))throw fail("HISTORY_RESTORE_SOURCE_ADOPTION_INVALID");
            return new GroupRecord(data.local,data.lineage,data.revision,expectedDigest,manifest,identity,refs);
        }
        private boolean sameRef(Ref a,Ref b){return a.member.equals(b.member)&&a.role.equals(b.role)&&Objects.equals(a.materialId,b.materialId)&&a.size==b.size&&a.sha256.equals(b.sha256);}
        private void validateMembers(Map<String,Ref> refs,Action action)throws IOException {
            if(refs.size()>30_010)throw fail("MANIFEST_MEMBER_LIMIT");
            Ref body=refs.get("body.bin");if(body==null||!body.role.equals("body")||body.materialId!=null||body.size>BODY_MAX)throw fail("BODY_MEMBER_INVALID");
            verifier.verifyBody(readRefSmall(body,(int)BODY_MAX));
            validateOptional(refs,"pdf",PDF_MAX);validateOptional(refs,"cover",COVER_MAX);
            validateCollection(refs,"video",VIDEO_MAX);validateCollection(refs,"vault",VAULT_MAX);
            for(String key:refs.keySet()){
                if((usesCompleteProfile(action)||action==Action.HISTORY_RESTORE_V1)&&key.equals("source-provenance.bin"))continue;
                if(key.equals("body.bin")||key.equals("pdf.state")||key.equals("pdf.bin")||key.equals("cover.state")||key.equals("cover.bin")||key.equals("video.state")||key.equals("vault.state"))continue;
                boolean accepted=false;
                for(String role:Arrays.asList("video","vault"))if(key.startsWith(role+"/")){
                    String tail=key.substring((role+"/").length());String[] p=tail.split("/",-1);
                    if(p.length==2&&p[0].matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")&&setOf("media-type.txt","metadata.bin","content.bin").contains(p[1]))accepted=true;
                }
                if(!accepted)throw fail("UNKNOWN_MANIFEST_MEMBER");
            }
            long total=0,resource=0;for(Ref r:refs.values()){total=Math.addExact(total,r.size);if(total>GROUP_TOTAL_MAX)throw fail("GROUP_SIZE_LIMIT");if(!setOf("body","video-state","vault-state","pdf-state","cover-state").contains(r.role)&&!r.member.endsWith("media-type.txt")&&!r.member.endsWith("metadata.bin")){resource=Math.addExact(resource,r.size);if(resource>RESOURCE_TOTAL_MAX)throw fail("RESOURCE_TOTAL_LIMIT");}}
        }
        private void validateOptional(Map<String,Ref> refs,String role,long cap)throws IOException {
            Ref state=refs.get(role+".state");if(state==null||!state.role.equals(role+"-state")||state.materialId!=null||state.size>16)throw fail("OPTIONAL_STATE_INVALID");
            String value=new String(readRefSmall(state,16),StandardCharsets.US_ASCII);
            Ref payload=refs.get(role+".bin");if(value.equals("absent\n")){if(payload!=null)throw fail("OPTIONAL_ABSENCE_MISMATCH");}
            else if(value.equals("present\n")){if(payload==null||!payload.role.equals(role)||payload.materialId!=null||payload.size>cap)throw fail("OPTIONAL_PAYLOAD_INVALID");}
            else throw fail("OPTIONAL_STATE_INVALID");
        }
        private void validateCollection(Map<String,Ref> refs,String role,long limit)throws IOException {
            Ref state=refs.get(role+".state");if(state==null||!state.role.equals(role+"-state")||state.materialId!=null||!new String(readRefSmall(state,16),StandardCharsets.US_ASCII).equals("present\n"))throw fail("COLLECTION_STATE_INVALID");
            Map<String,Set<String>> files=new HashMap<>();for(Ref r:refs.values())if(r.member.startsWith(role+"/")){
                String[] p=r.member.substring(role.length()+1).split("/",-1);if(p.length!=2)throw fail("COLLECTION_MEMBER_INVALID");
                Set<String> kinds=files.computeIfAbsent(p[0],k->new HashSet<>());if(!kinds.add(p[1]))throw fail("COLLECTION_MEMBER_DUPLICATE");
                if(!Objects.equals(r.materialId,p[0])||!setOf("media-type.txt","metadata.bin","content.bin").contains(p[1]))throw fail("COLLECTION_MEMBER_INVALID");
                if(p[1].equals("media-type.txt")){if(r.role.equals(role)==false||r.size>256)throw fail("MEDIA_TYPE_INVALID");byte[] raw=readRefSmall(r,256);if(raw.length==0||raw[raw.length-1]!='\n')throw fail("MEDIA_TYPE_INVALID");}
                if(p[1].equals("metadata.bin")){if(!r.role.equals(role)||r.size>1<<20)throw fail("METADATA_SIZE_LIMIT");verifier.verifyMetadata(role,p[0],readRefSmall(r,1<<20));}
                if(p[1].equals("content.bin")&&(!r.role.equals(role)||r.size>limit))throw fail("CONTENT_LIMIT");
            }
            for(Map.Entry<String,Set<String>> entry:files.entrySet())if(!entry.getValue().equals(setOf("media-type.txt","metadata.bin","content.bin")))throw fail("COLLECTION_MEMBER_INCOMPLETE");
        }
        private byte[] readRefSmall(Ref ref,int cap)throws IOException {if(ref.size>cap)throw fail("SMALL_MEMBER_CAP_EXCEEDED");ByteArrayOutputStream b=new ByteArrayOutputStream((int)ref.size);copyVerified(root,ref,b,ops);return b.toByteArray();}
    }

    private static final class ManifestData {
        String local,lineage,revision,parent,base,digest,action,originLocal,originLineage,parentOriginLocal,parentOriginLineage,restoreSourceRevision,restoreSourceDigest;
        Map<String,Ref> refs;
    }
    private static void verifyManualV2Binding(VerifiedGroup group,byte[] sourceProvenance,String local,String lineage,String revision)throws IOException {
        try(DataInputStream d=new DataInputStream(new ByteArrayInputStream(sourceProvenance))){
            byte[] domain="PadNote/UpdateSourceProvenance/v2\0".getBytes(StandardCharsets.US_ASCII);
            if(!Arrays.equals(readN(d,domain.length),domain))throw fail("UPDATE_PROVENANCE_DOMAIN");
            String kind=text(d),sourceLineage=text(d),sourceNote=text(d),sourceRevision=text(d),sourceBodySha=text(d),destinationBodySha=text(d);
            if(!setOf("NATIVE_IOS","NATIVE_ANDROID","TRANSPORTED").contains(kind))throw fail("UPDATE_PROVENANCE_SOURCE_KIND");
            id(sourceLineage);opaqueId(sourceNote);id(sourceRevision);hex(sourceBodySha);hex(destinationBodySha);
            ImportIdentity identity=group.identity;
            if(!identity.originLineage().equals(sourceLineage)||!identity.originLocalId().equals(sourceNote))throw fail("UPDATE_PROVENANCE_SOURCE_IDENTITY");
            int count=d.readInt();if(count<1||count>20_000)throw fail("UPDATE_PROVENANCE_TIME_COUNT");HashSet<String> pointers=new HashSet<>();
            for(int i=0;i<count;i++){String pointer=text(d);int kindTag=d.readUnsignedByte();long value=d.readLong();if(!pointer.startsWith("/")||!pointers.add(pointer)||kindTag<0||kindTag>1)throw fail("UPDATE_PROVENANCE_TIME_ROW");}
            int mappings=d.readInt();if(mappings<1||mappings>10_000)throw fail("UPDATE_PROVENANCE_MAPPING_COUNT");HashSet<String> sources=new HashSet<>(),destinations=new HashSet<>();HashMap<String,String> mapping=new HashMap<>();boolean noteMapping=false;
            for(int i=0;i<mappings;i++){String from=text(d),to=text(d);opaqueId(from);opaqueId(to);if(!sources.add(from)||!destinations.add(to))throw fail("UPDATE_PROVENANCE_MAPPING_DUPLICATE");mapping.put(from,to);if(from.equals(sourceNote)&&to.equals(local))noteMapping=true;}
            if(!noteMapping)throw fail("UPDATE_PROVENANCE_MAPPING_INVALID");
            int materialCount=d.readInt();if(materialCount<0||materialCount>10_000)throw fail("UPDATE_PROVENANCE_MATERIAL_COUNT");HashSet<String> materialRows=new HashSet<>();
            for(int i=0;i<materialCount;i++){String role=text(d),sourceId=text(d),destId=text(d),media=text(d),platform=text(d),state=text(d),materialSourceLineage=nullable(d),sourceOwner=nullable(d);long sourceSize=d.readLong();byte[] sourceHash=readN(d,32);long destSize=d.readLong();byte[] destHash=readN(d,32),metadataHash=readN(d,32);String linkedNote=nullable(d);byte[] descriptorHash=readN(d,32);boolean knownState=setOf("linked_note","source_deleted","source_not_selected","independent").contains(state);boolean validAssociation;if("independent".equals(state))validAssociation=materialSourceLineage==null&&sourceOwner==null;else if("linked_note".equals(state))validAssociation=Objects.equals(sourceOwner,sourceLineage)&&Objects.equals(materialSourceLineage,sourceLineage);else validAssociation=Objects.equals(sourceOwner,sourceLineage)&&(materialSourceLineage==null||Objects.equals(materialSourceLineage,sourceLineage));if(!setOf("VIDEO","VAULT").contains(role)||!setOf("android","ios").contains(platform)||!knownState||!validAssociation||(state.equals("linked_note")!=(linkedNote!=null))||state.equals("linked_note")&&!Objects.equals(mapping.get(linkedNote),local)||sourceSize<0||destSize<0||sourceHash.length!=32||destHash.length!=32||metadataHash.length!=32||descriptorHash.length!=32||!sources.contains(sourceId)||!Objects.equals(mapping.get(sourceId),destId)||!materialRows.add(role+":"+destId))throw fail("UPDATE_PROVENANCE_MATERIAL_INVALID");opaqueId(sourceId);opaqueId(destId);if(media.isEmpty()||media.getBytes(StandardCharsets.UTF_8).length>256)throw fail("UPDATE_PROVENANCE_MEDIA_TYPE");}
            if(d.read()!=-1)throw fail("UPDATE_PROVENANCE_TRAILING");
            byte[] body=group.readSmall("body.bin",(int)BODY_MAX);
            if(!sha(body).equals(destinationBodySha))throw fail("UPDATE_PROVENANCE_DESTINATION_BODY_SHA");
            String bodyJson;try{bodyJson=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(body)).toString();}catch(java.nio.charset.CharacterCodingException ex){throw fail("UPDATE_BODY_UTF8_INVALID");}
            org.json.JSONObject object=new org.json.JSONObject(bodyJson);
            if(!object.getString("id").equals(local))throw fail("UPDATE_PROVENANCE_DESTINATION_NOTE");
            if(!group.identity.destinationLineage().equals(lineage)||!group.revision.equals(revision))throw fail("UPDATE_PROVENANCE_DESTINATION_GROUP");
        }catch(IOException e){throw e;}catch(Exception e){throw fail("UPDATE_PROVENANCE_INVALID");}
    }
    private static byte[] encodeManifest(Envelope e,SortedMap<String,Ref> refs)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);ImportIdentity i=e.identity;
        d.write(e.action==Action.HISTORY_RESTORE_V1?HISTORY_RESTORE_DOMAIN:e.action==Action.MANUAL_UPDATE_V2?"PadNote/StreamingManualUpdateGroup/v2\0".getBytes(StandardCharsets.US_ASCII):e.action==Action.LEGACY_ADOPTION_V2?"PadNote/StreamingLegacyAdoptionGroup/v2\0".getBytes(StandardCharsets.US_ASCII):DOMAIN);text(d,i.destinationLocalId);text(d,i.destinationLineage);text(d,e.revision);nullable(d,e.parentRevision);nullable(d,e.baseRevision);nullable(d,e.baseDigest);text(d,e.action.name());if(e.action==Action.HISTORY_RESTORE_V1){text(d,e.restoreSourceRevision);text(d,e.restoreSourceDigest);}
        text(d,i.originLineage);text(d,i.originLocalId);nullable(d,i.parentOriginLineage);nullable(d,i.parentOriginLocalId);
        d.writeInt(5);for(String n:Arrays.asList("body","pdf","cover","video","vault")){text(d,n);text(d,n.equals("pdf")?(e.pdf==null?"absent":"present"):n.equals("cover")?(e.cover==null?"absent":"present"):"present");}
        d.writeInt(refs.size());for(Ref r:refs.values()){text(d,r.member);text(d,r.role);nullable(d,r.materialId);d.writeLong(r.size);d.write(parseHex(r.sha256));}d.flush();return out.toByteArray();
    }
    private static ImportIdentity parseIdentity(byte[] manifest)throws IOException {ManifestData m=parseData(manifest);return new ImportIdentity(m.originLineage,m.originLocal,m.local,m.lineage,m.parentOriginLineage,m.parentOriginLocal);}
    private static Map<String,Ref> parseManifest(byte[] manifest)throws IOException{return parseData(manifest).refs;}
    private static ManifestData parseData(byte[] bytes)throws IOException {
        try(DataInputStream d=new DataInputStream(new ByteArrayInputStream(bytes))){d.mark(128);ByteArrayOutputStream domainBytes=new ByteArrayOutputStream();boolean terminated=false;for(int n=0;n<128;n++){int x=d.read();if(x<0)break;if(x==0){terminated=true;break;}domainBytes.write(x);}if(!terminated)throw fail("MANIFEST_DOMAIN_INVALID");String domain=new String(domainBytes.toByteArray(),StandardCharsets.US_ASCII);d.reset();readN(d,domainBytes.size()+1);boolean v1=domain.equals(new String(DOMAIN,0,DOMAIN.length-1,StandardCharsets.US_ASCII));boolean v2=domain.equals("PadNote/StreamingManualUpdateGroup/v2");boolean v3=domain.equals("PadNote/StreamingLegacyAdoptionGroup/v2");boolean v4=domain.equals(new String(HISTORY_RESTORE_DOMAIN,0,HISTORY_RESTORE_DOMAIN.length-1,StandardCharsets.US_ASCII));if(!v1&&!v2&&!v3&&!v4)throw fail("MANIFEST_DOMAIN_INVALID");ManifestData m=new ManifestData();m.local=text(d);m.lineage=text(d);m.revision=text(d);m.parent=nullable(d);m.base=nullable(d);m.digest=nullable(d);m.action=text(d);if(v1&&usesCompleteProfile(Action.valueOf(m.action))||v2&&!"MANUAL_UPDATE_V2".equals(m.action)||v3&&!"LEGACY_ADOPTION_V2".equals(m.action)||v4&&!"HISTORY_RESTORE_V1".equals(m.action))throw fail("MANIFEST_PROFILE_ACTION_MISMATCH");if(v4){m.restoreSourceRevision=text(d);m.restoreSourceDigest=text(d);id(m.restoreSourceRevision);hex(m.restoreSourceDigest);}m.originLineage=text(d);m.originLocal=text(d);m.parentOriginLineage=nullable(d);m.parentOriginLocal=nullable(d);int components=d.readInt();if(components!=5)throw fail("COMPONENT_SCOPE_INVALID");String[] names={"body","pdf","cover","video","vault"};for(int x=0;x<components;x++){String name=text(d),state=text(d);if(!name.equals(names[x]))throw fail("MANIFEST_COMPONENT_ORDER");if((x==0||x==3||x==4)&&!state.equals("present")||(x==1||x==2)&&!setOf("absent","present").contains(state))throw fail("MANIFEST_COMPONENT_STATE");}int n=d.readInt();if(n<1||n>30_010)throw fail("MANIFEST_MEMBER_LIMIT");TreeMap<String,Ref> refs=new TreeMap<>();for(int x=0;x<n;x++){String member=text(d),role=text(d),material=nullable(d);long size=d.readLong();byte[] h=readN(d,32);if(size<0||h.length!=32||refs.put(member,new Ref(member,role,material,size,hex(h)))!=null)throw fail("MANIFEST_ROW_INVALID");}if(d.read()!=-1)throw fail("MANIFEST_TRAILING_DATA");m.refs=Collections.unmodifiableMap(new HashMap<>(refs));return m;}catch(EOFException|IllegalArgumentException ex){throw fail("MANIFEST_INVALID");}
    }
    private static void copyVerified(Path root,Ref ref,OutputStream out,IdentityOps ops)throws IOException {
        Path path=root.resolve("objects/sha256").resolve(ref.sha256);if(ops==null)throw fail("NATIVE_IDENTITY_ADAPTER_REQUIRED");
        MessageDigest md=digest();long count=0;byte[] buf=new byte[COPY_BUFFER_BYTES];
        try(StableInput in=ops.open(path,ref.size)){FileIdentity b=in.before();requireSingleLink(in.pathBefore());requireSingleLink(b);for(int n;(n=in.stream().read(buf))!=-1;){if(n==0)continue;count+=n;if(count>ref.size)throw fail("OBJECT_SIZE_MISMATCH");md.update(buf,0,n);out.write(buf,0,n);}FileIdentity a=in.after(),p=ops.identity(path);requireSingleLink(a);requireSingleLink(p);ops.requireStable(path,in.pathBefore(),b,a,p);}
        if(count!=ref.size||!hex(md.digest()).equals(ref.sha256))throw fail("OBJECT_HASH_MISMATCH");
    }
    private static InputStream openVerifiedStream(Path root,Ref ref,IdentityOps ops)throws IOException {
        Path path=root.resolve("objects/sha256").resolve(ref.sha256);
        StableInput stable=ops.open(path,ref.size);FileIdentity before=stable.before();requireSingleLink(stable.pathBefore());requireSingleLink(before);MessageDigest md=digest();InputStream raw=stable.stream();
        return new InputStream() {
            long count;boolean verified,closed;
            private void finish()throws IOException {
                if(verified)return;byte[] drain=new byte[COPY_BUFFER_BYTES];
                while(count<ref.size){int n=raw.read(drain);if(n<0)break;if(n==0)continue;count+=n;if(count>ref.size)throw fail("OBJECT_SIZE_MISMATCH");md.update(drain,0,n);}
                if(raw.read()!=-1||count!=ref.size||!hex(md.digest()).equals(ref.sha256))throw fail("OBJECT_HASH_MISMATCH");
                FileIdentity after=stable.after(),pathAfter=ops.identity(path);requireSingleLink(after);requireSingleLink(pathAfter);ops.requireStable(path,stable.pathBefore(),before,after,pathAfter);verified=true;
            }
            @Override public int read()throws IOException {int v=raw.read();if(v<0){finish();return -1;}count++;if(count>ref.size)throw fail("OBJECT_SIZE_MISMATCH");md.update((byte)v);return v;}
            @Override public int read(byte[] b,int off,int len)throws IOException {int n=raw.read(b,off,len);if(n<0){finish();return -1;}if(n>0){count+=n;if(count>ref.size)throw fail("OBJECT_SIZE_MISMATCH");md.update(b,off,n);}return n;}
            @Override public void close()throws IOException {if(closed)return;IOException error=null;try{finish();}catch(IOException e){error=e;}try{stable.close();}catch(IOException e){if(error==null)error=e;}closed=true;if(error!=null)throw error;}
        };
    }
    private static boolean same(Snapshot s,String revision,String digest){return s==null?revision==null&&digest==null:Objects.equals(s.revision,revision)&&Objects.equals(s.digest,digest);}
    private static void requireSingleLink(FileIdentity identity)throws IOException {if(identity==null||identity.links()!=1||identity.reparse())throw fail("SINGLE_LINK_IDENTITY_REQUIRED");}
    private static byte[] readStableBounded(Path p,int cap,IdentityOps ops)throws IOException {
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(p))throw fail("MEMBER_NOT_PLAIN");
        long expected=Files.size(p);if(expected<0||expected>cap)throw fail("MEMBER_SIZE_LIMIT");
        try(StableInput in=ops.open(p,expected);ByteArrayOutputStream out=new ByteArrayOutputStream((int)expected)){
            FileIdentity fd=in.before();requireSingleLink(in.pathBefore());requireSingleLink(fd);byte[] b=new byte[8192];long count=0;
            for(int n;(n=in.stream().read(b))!=-1;){if(n==0)continue;count+=n;if(count>expected||count>cap)throw fail("MEMBER_SIZE_LIMIT");out.write(b,0,n);}
            FileIdentity after=in.after(),pathAfter=ops.identity(p);requireSingleLink(after);requireSingleLink(pathAfter);ops.requireStable(p,in.pathBefore(),fd,after,pathAfter);
            if(count!=expected)throw fail("MEMBER_SIZE_CHANGED");return out.toByteArray();
        }
    }
    private static void createNew(Path p,byte[] b)throws IOException {try(FileChannel c=FileChannel.open(p,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer x=ByteBuffer.wrap(b);while(x.hasRemaining())c.write(x);c.force(true);}}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new AssertionError(e);}}
    private static byte[] readN(DataInputStream input,int count)throws IOException {if(count<0)throw new IOException("negative length");byte[] out=new byte[count];input.readFully(out);return out;}
    @SafeVarargs private static <T> Set<T> setOf(T... values){return new HashSet<>(Arrays.asList(values));}
    private static byte[] parseHex(String value){if(value==null||(value.length()&1)!=0)throw new IllegalArgumentException("hex");byte[] out=new byte[value.length()/2];for(int i=0;i<out.length;i++){int hi=Character.digit(value.charAt(i*2),16),lo=Character.digit(value.charAt(i*2+1),16);if(hi<0||lo<0)throw new IllegalArgumentException("hex");out[i]=(byte)((hi<<4)|lo);}return out;}
    private static String sha(byte[] b){return hex(digest().digest(b));}
    private static String hex(byte[] b){StringBuilder out=new StringBuilder(b.length*2);for(byte x:b){int v=x&255;out.append("0123456789abcdef".charAt(v>>>4)).append("0123456789abcdef".charAt(v&15));}return out.toString();}
    private static void id(String s)throws IOException {if(s==null||!s.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")||s.equals(".")||s.equals(".."))throw fail("IDENTITY_INVALID");}
    private static void opaqueId(String s)throws IOException {if(s==null||s.isEmpty()||s.getBytes(StandardCharsets.UTF_8).length>4096||s.chars().anyMatch(c->c<0x20||c==0x7f))throw fail("OPAQUE_ID_INVALID");}
    private static void hex(String s)throws IOException {if(s==null||!s.matches("[0-9a-f]{64}"))throw fail("SHA256_INVALID");}
    private static void text(DataOutputStream d,String s)throws IOException {byte[] b=s.getBytes(StandardCharsets.UTF_8);d.writeInt(b.length);d.write(b);}
    private static void nullable(DataOutputStream d,String s)throws IOException {d.writeBoolean(s!=null);if(s!=null)text(d,s);}
    private static String text(DataInputStream d)throws IOException {int n=d.readInt();if(n<0||n>1<<20)throw fail("MANIFEST_TEXT_LIMIT");byte[] b=readN(d,n);if(b.length!=n)throw new EOFException();return new String(b,StandardCharsets.UTF_8);}
    private static String nullable(DataInputStream d)throws IOException {return d.readBoolean()?text(d):null;}
    private static IOException fail(String s){return new IOException(s);}
}
