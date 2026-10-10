package com.padnote.android.streaming;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import com.padnote.android.OwnedFdStreams;

/** No-follow identity-stable reads and bounded staging of legacy members inside app files. */
@android.annotation.TargetApi(27)
public final class LegacyFileCapture {
    private LegacyFileCapture() {}
    public static final class Result {
        public final Path path; public final long size; public final String sha256; public final byte[] bytes;
        private Result(Path p,long n,String h,byte[] b){path=p;size=n;sha256=h;bytes=b;}
        public StreamingGroupStore.ContentInput content(){return new StreamingGroupStore.ContentInput(path,size,sha256,null);}
    }
    public static Result inspect(Context context, File file, long maxBytes) throws IOException { return capture(context,file,maxBytes,false,false); }
    /** Hashes and fsyncs the same no-follow descriptor before returning a durable-input receipt. */
    public static Result inspectAndSync(Context context, File file, long maxBytes) throws IOException { return capture(context,file,maxBytes,false,true); }
    public static Result read(Context context, File file, long maxBytes) throws IOException { return capture(context,file,maxBytes,true,false); }

    /** Copies an inspected input to a new private file using bounded streaming and fresh identity checks. */
    public static Result copyTo(Context context,Result expected,File target,long maxBytes)throws IOException {
        if(context==null||expected==null||target==null||maxBytes<=0||expected.size<=0||expected.size>maxBytes)
            throw new IOException("LEGACY_CAPTURE_COPY_ARGUMENT");
        TrustedFilesRoot trusted=TrustedFilesRoot.fromApplicationContext(context);
        AndroidIdentityOps ops=new AndroidIdentityOps(trusted,trusted.path().resolve(".legacy-group-capture"));
        Path source=expected.path.toAbsolutePath().normalize();
        Path destination=target.getAbsoluteFile().toPath().normalize();
        trusted.requireInScope(source);trusted.requireInScope(destination);
        Path parent=destination.getParent();if(parent==null)throw new IOException("LEGACY_CAPTURE_COPY_PARENT_REQUIRED");
        ops.requirePlainDirectory(parent);
        if(ops.noFollowState(destination)!=StreamingGroupStore.IdentityOps.NoFollowState.MISSING)
            throw new IOException("LEGACY_CAPTURE_COPY_DESTINATION_EXISTS");
        StreamingGroupStore.FileIdentity pathBefore=ops.identity(source);
        requireCopySource(pathBefore,expected,maxBytes);
        MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}
        catch(Exception unavailable){throw new IOException("SHA256_UNAVAILABLE",unavailable);}
        StructStat createdIdentity=null;
        StructStat completedState=null;
        FileOutputStream outputStream=null;
        Throwable primaryFailure=null;
        try {
            try(StreamingGroupStore.StableInput input=ops.open(source,expected.size)) {
                StreamingGroupStore.FileIdentity fdBefore=input.before();requireCopySource(fdBefore,expected,maxBytes);
                if(!pathBefore.equals(fdBefore))throw new IOException("LEGACY_CAPTURE_COPY_SOURCE_CHANGED");
                java.io.FileDescriptor outputFd;
                try{outputFd=Os.open(destination.toString(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL
                        |OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);}
                catch(ErrnoException error){throw new IOException("LEGACY_CAPTURE_COPY_CREATE",error);}
                try {
                    createdIdentity=Os.fstat(outputFd);
                    requireCopyOutputObject(createdIdentity);
                } catch(ErrnoException error) {
                    try{Os.close(outputFd);}catch(ErrnoException close){error.addSuppressed(close);}
                    throw new IOException("LEGACY_CAPTURE_COPY_OUTPUT_IDENTITY",error);
                } catch(IOException failure) {
                    try{Os.close(outputFd);}catch(ErrnoException close){failure.addSuppressed(close);}
                    throw failure;
                }
                outputStream=OwnedFdStreams.output(outputFd);
                long count=0;byte[] buffer=new byte[StreamingGroupStore.COPY_BUFFER_BYTES];
                for(int n;(n=input.stream().read(buffer))!=-1;) {
                    if(n==0)continue;
                    try{count=Math.addExact(count,n);}catch(ArithmeticException overflow){throw new IOException("LEGACY_CAPTURE_COPY_OVERFLOW",overflow);}
                    if(count>maxBytes||count>expected.size)throw new IOException("LEGACY_CAPTURE_COPY_SIZE_CHANGED");
                    digest.update(buffer,0,n);outputStream.write(buffer,0,n);
                }
                outputStream.flush();outputStream.getFD().sync();
                StructStat fdAfter=stat(outputStream.getFD(),"LEGACY_CAPTURE_COPY_OUTPUT_FSTAT");
                StructStat pathAfter=pathStat(destination,"LEGACY_CAPTURE_COPY_OUTPUT_LSTAT");
                requireCopyOutputState(createdIdentity,fdAfter,pathAfter,count);
                completedState=fdAfter;
                StreamingGroupStore.FileIdentity sourceFdAfter=input.after(),sourcePathAfter=ops.identity(source);
                ops.requireStable(source,input.pathBefore(),fdBefore,sourceFdAfter,sourcePathAfter);
                if(count!=expected.size||!hex(digest.digest()).equalsIgnoreCase(expected.sha256))
                    throw new IOException("LEGACY_CAPTURE_COPY_HASH_CHANGED");
                trusted.verify();
            }
            final Result copied=inspect(context,destination.toFile(),maxBytes);
            if(copied.size!=expected.size||!copied.sha256.equalsIgnoreCase(expected.sha256))
                throw new IOException("LEGACY_CAPTURE_COPY_RESULT_MISMATCH");
            StructStat finalState=pathStat(destination,"LEGACY_CAPTURE_COPY_OUTPUT_LSTAT");
            requireCopyOutputState(createdIdentity,completedState,finalState,expected.size);
            return copied;
        } catch(Throwable failure) {
            primaryFailure=failure;
            cleanupOwnedOutput(destination,createdIdentity,completedState,failure);
            if(failure instanceof IOException)throw(IOException)failure;
            if(failure instanceof RuntimeException)throw(RuntimeException)failure;
            if(failure instanceof Error)throw(Error)failure;
            throw new IOException("LEGACY_CAPTURE_COPY_FAILED",failure);
        } finally {
            if(outputStream!=null)try{outputStream.close();}
            catch(IOException close) {
                if(primaryFailure!=null)primaryFailure.addSuppressed(close);
                else {cleanupOwnedOutput(destination,createdIdentity,completedState,close);throw close;}
            }
        }
    }

    private static StructStat stat(java.io.FileDescriptor descriptor,String error)throws IOException {
        try{return Os.fstat(descriptor);}catch(ErrnoException failure){throw new IOException(error,failure);}
    }
    private static StructStat pathStat(Path path,String error)throws IOException {
        try{return Os.lstat(path.toString());}catch(ErrnoException failure){throw new IOException(error,failure);}
    }
    private static void requireCopyOutputObject(StructStat stat)throws IOException {
        if(stat==null||!OsConstants.S_ISREG(stat.st_mode)||stat.st_uid!=android.os.Process.myUid()
                ||stat.st_nlink!=1||(stat.st_mode&0077)!=0)
            throw new IOException("LEGACY_CAPTURE_COPY_OUTPUT_INVALID");
    }
    private static void requireCopyOutputState(StructStat created,StructStat fdState,
            StructStat pathState,long expectedSize)throws IOException {
        if(!sameCopyOutputObject(created,fdState)||!sameCopyOutputObject(created,pathState)
                ||!sameCopyOutputState(fdState,pathState)||fdState.st_size!=expectedSize)
            throw new IOException("LEGACY_CAPTURE_COPY_OUTPUT_CHANGED");
    }
    private static boolean sameCopyOutputObject(StructStat expected,StructStat actual) {
        return expected!=null&&actual!=null&&OsConstants.S_ISREG(expected.st_mode)
                &&OsConstants.S_ISREG(actual.st_mode)&&expected.st_dev==actual.st_dev
                &&expected.st_ino==actual.st_ino&&expected.st_mode==actual.st_mode
                &&expected.st_nlink==1&&actual.st_nlink==1
                &&expected.st_uid==actual.st_uid&&expected.st_gid==actual.st_gid;
    }
    private static boolean sameCopyOutputState(StructStat first,StructStat second) {
        return sameCopyOutputObject(first,second)&&first.st_size==second.st_size
                &&first.st_mtime==second.st_mtime&&first.st_ctime==second.st_ctime;
    }
    static void cleanupOwnedOutput(Path destination,StructStat created,StructStat completed,Throwable failure) {
        if(created==null)return;
        try {
            StructStat current;
            try{current=Os.lstat(destination.toString());}
            catch(ErrnoException missing){if(missing.errno==OsConstants.ENOENT)return;throw missing;}
            if(!sameCopyOutputObject(created,current)
                    ||(completed!=null&&!sameCopyOutputState(completed,current))) {
                failure.addSuppressed(new IOException("LEGACY_CAPTURE_COPY_CLEANUP_IDENTITY_CHANGED"));
                return;
            }
            Os.remove(destination.toString());
        } catch(Exception cleanup) {
            failure.addSuppressed(new IOException("LEGACY_CAPTURE_COPY_CLEANUP",cleanup));
        }
    }

    private static void requireCopySource(StreamingGroupStore.FileIdentity identity,Result expected,long maxBytes)
            throws IOException {
        if(!OsConstants.S_ISREG(identity.mode())||identity.reparse()||identity.links()!=1
                ||identity.size()!=expected.size||identity.size()>maxBytes)
            throw new IOException("LEGACY_CAPTURE_COPY_SOURCE_INVALID");
    }

    private static Result capture(Context context,File file,long maxBytes,boolean keep,boolean sync) throws IOException {
        if(context==null||file==null||maxBytes<=0)throw new IOException("LEGACY_CAPTURE_ARGUMENT");
        TrustedFilesRoot trusted=TrustedFilesRoot.fromApplicationContext(context); Path store=trusted.path().resolve(".legacy-group-capture");
        AndroidIdentityOps ops=new AndroidIdentityOps(trusted,store); Path path=file.getAbsoluteFile().toPath().normalize();trusted.requireInScope(path);
        StreamingGroupStore.FileIdentity identity=ops.identity(path);if(identity.size()<=0||identity.size()>maxBytes||identity.links()!=1||identity.reparse())throw new IOException("LEGACY_CAPTURE_SIZE_OR_IDENTITY");
        MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IOException("SHA256_UNAVAILABLE",e);}
        ByteArrayOutputStream out=keep?new ByteArrayOutputStream((int)Math.min(identity.size(),1<<20)):null;long count=0;byte[] buffer=new byte[StreamingGroupStore.COPY_BUFFER_BYTES];
        try(StreamingGroupStore.StableInput in=ops.open(path,identity.size())){
            StreamingGroupStore.FileIdentity before=in.before();if(!identity.equals(before))throw new IOException("LEGACY_CAPTURE_CHANGED_BEFORE_OPEN");
            for(int n;(n=in.stream().read(buffer))!=-1;){if(n==0)continue;count=Math.addExact(count,n);if(count>maxBytes||count>identity.size())throw new IOException("LEGACY_CAPTURE_SIZE_CHANGED");digest.update(buffer,0,n);if(out!=null)out.write(buffer,0,n);}
            StreamingGroupStore.FileIdentity after=in.after(),pathAfter=ops.identity(path);ops.requireStable(path,in.pathBefore(),before,after,pathAfter);if(!identity.equals(after)||!identity.equals(pathAfter)||count!=identity.size())throw new IOException("LEGACY_CAPTURE_IDENTITY_CHANGED");
            if(sync){
                in.sync();
                StreamingGroupStore.FileIdentity syncedFd=in.after(),syncedPath=ops.identity(path);
                ops.requireStable(path,in.pathBefore(),before,syncedFd,syncedPath);
                if(!identity.equals(syncedFd)||!identity.equals(syncedPath)||count!=identity.size())
                    throw new IOException("LEGACY_CAPTURE_CHANGED_DURING_SYNC");
            }
        } catch(ArithmeticException e){throw new IOException("LEGACY_CAPTURE_SIZE_OVERFLOW",e);}
        trusted.verify();return new Result(path,count,hex(digest.digest()),out==null?null:out.toByteArray());
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
