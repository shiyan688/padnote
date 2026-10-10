package com.padnote.android.streaming;

import android.annotation.TargetApi;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.padnote.android.OwnedFdStreams;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** API-27+ descriptor identity implementation. No path-attribute fallback is permitted. */
@TargetApi(27)
public final class AndroidIdentityOps implements StreamingGroupStore.IdentityOps {
    interface InstallObserver { void afterAtomicNoReplaceMove(Path stage,Path installed)throws IOException; }
    interface GroupInstallObserver { void afterDirectoryCreated(Path destination)throws IOException; void afterFileInstalled(Path destination,String member)throws IOException; }
    public static final class Metrics {
        private final AtomicLong objectReadBytes=new AtomicLong(),objectInstalledBytes=new AtomicLong(),maximumReadRequest=new AtomicLong();
        public long objectReadBytes(){return objectReadBytes.get();}
        public long objectInstalledBytes(){return objectInstalledBytes.get();}
        public long maximumReadRequest(){return maximumReadRequest.get();}
    }
    private final Path storeRoot,objectDirectory;
    private final TrustedFilesRoot trustedFilesRoot;
    private final Metrics metrics=new Metrics();
    private final InstallObserver installObserver;
    private final GroupInstallObserver groupInstallObserver;
    private final Map<StreamingGroupStore.FileIdentity,AncestorWitness> openAncestorWitnesses=
            Collections.synchronizedMap(new IdentityHashMap<StreamingGroupStore.FileIdentity,AncestorWitness>());
    private static final class AncestorWitness {
        final List<StreamingGroupStore.FileIdentity> directories;
        boolean invalidated;
        AncestorWitness(List<StreamingGroupStore.FileIdentity> directories){this.directories=directories;}
    }

    AndroidIdentityOps(TrustedFilesRoot trustedFilesRoot,Path storeRoot)throws IOException{this(trustedFilesRoot,storeRoot,null,null);}
    AndroidIdentityOps(TrustedFilesRoot trustedFilesRoot,Path storeRoot,InstallObserver observer)throws IOException{this(trustedFilesRoot,storeRoot,observer,null);}
    AndroidIdentityOps(TrustedFilesRoot trustedFilesRoot,Path storeRoot,InstallObserver observer,GroupInstallObserver groupObserver)throws IOException{
        StreamingFeatureGate.requireSupported();if(trustedFilesRoot==null||storeRoot==null)throw new IOException("TRUSTED_FILES_ROOT_REQUIRED");
        this.trustedFilesRoot=trustedFilesRoot;this.storeRoot=trustedFilesRoot.requireInScope(storeRoot);if(this.storeRoot.equals(trustedFilesRoot.path()))throw new IOException("STORE_ROOT_MUST_BE_DESCENDANT");
        this.objectDirectory=this.storeRoot.resolve("objects/sha256");this.installObserver=observer;this.groupInstallObserver=groupObserver;
    }
    public Metrics metrics(){return metrics;}

    /** Reports absence only when lstat of the final component returned ENOENT. */
    @Override public StreamingGroupStore.IdentityOps.NoFollowState noFollowState(Path path)throws IOException{
        requireSupported();Path absolute=trustedFilesRoot.requireInScope(path.toAbsolutePath().normalize());
        List<StreamingGroupStore.FileIdentity> before=ancestorSnapshot(absolute.getParent());
        StreamingGroupStore.IdentityOps.NoFollowState result;
        try{Os.lstat(absolute.toString());result=StreamingGroupStore.IdentityOps.NoFollowState.PRESENT;}
        catch(ErrnoException e){if(e.errno==OsConstants.ENOENT)result=StreamingGroupStore.IdentityOps.NoFollowState.MISSING;else throw io("LSTAT_NOFOLLOW_STATE",e);}
        requireSameAncestors(before,ancestorSnapshot(absolute.getParent()));
        trustedFilesRoot.verify();return result;
    }

    @Override public StreamingGroupStore.StableInput open(Path path,long expectedSize)throws IOException{
        requireSupported();Path absolute=path.toAbsolutePath().normalize();
        List<StreamingGroupStore.FileIdentity> ancestorsBefore=ancestorSnapshot(absolute.getParent());
        StreamingGroupStore.FileIdentity pathBefore=identity(absolute);requireRegularSingleLink(pathBefore);
        if(pathBefore.size()!=expectedSize)throw new IOException("SOURCE_SIZE_CHANGED");
        final FileDescriptor fd;
        try{fd=Os.open(absolute.toString(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);}catch(ErrnoException e){throw io("OPEN_NOFOLLOW",e);}
        final FileInputStream input=OwnedFdStreams.input(fd);
        try{
            StreamingGroupStore.FileIdentity fdBefore=identity(Os.fstat(fd));requireRegularSingleLink(fdBefore);requireSame(pathBefore,fdBefore,"OPEN_PATH_FD_MISMATCH");
            requireSameAncestors(ancestorsBefore,ancestorSnapshot(absolute.getParent()));
            InputStream stream=input;
            if(absolute.startsWith(objectDirectory))stream=new FilterInputStream(input){
                @Override public int read()throws IOException{metrics.maximumReadRequest.accumulateAndGet(1,Math::max);int v=super.read();if(v>=0)metrics.objectReadBytes.incrementAndGet();return v;}
                @Override public int read(byte[] b,int off,int len)throws IOException{metrics.maximumReadRequest.accumulateAndGet(len,Math::max);int n=super.read(b,off,len);if(n>0)metrics.objectReadBytes.addAndGet(n);return n;}
            };
            final InputStream returned=stream;
            StreamingGroupStore.StableInput result=new StreamingGroupStore.StableInput(){
                public InputStream stream(){return returned;}
                public StreamingGroupStore.FileIdentity pathBefore(){return pathBefore;}
                public StreamingGroupStore.FileIdentity before(){return fdBefore;}
                public StreamingGroupStore.FileIdentity after()throws IOException{try{return identity(Os.fstat(fd));}catch(ErrnoException e){throw io("FSTAT_AFTER_READ",e);}}
                public void sync()throws IOException{
                    try{
                        StreamingGroupStore.FileIdentity fdBeforeSync=identity(Os.fstat(fd));
                        StreamingGroupStore.FileIdentity pathBeforeSync=identity(absolute);
                        requireStable(absolute,pathBefore,fdBeforeSync,fdBeforeSync,pathBeforeSync);
                        Os.fsync(fd);
                        StreamingGroupStore.FileIdentity fdAfterSync=identity(Os.fstat(fd));
                        StreamingGroupStore.FileIdentity pathAfterSync=identity(absolute);
                        requireStable(absolute,pathBefore,fdBeforeSync,fdAfterSync,pathAfterSync);
                    }catch(ErrnoException e){throw io("FSYNC_STABLE_INPUT",e);}
                }
                public FileDescriptor duplicateDescriptor()throws IOException{FileDescriptor copy=null;try{copy=Os.dup(fd);StreamingGroupStore.FileIdentity duplicate=identity(Os.fstat(copy));requireRegularSingleLink(duplicate);requireSame(fdBefore,duplicate,"DUPLICATE_DESCRIPTOR_IDENTITY_MISMATCH");return copy;}catch(ErrnoException e){if(copy!=null)try{Os.close(copy);}catch(ErrnoException ignored){}throw io("DUPLICATE_VERIFIED_DESCRIPTOR",e);}catch(IOException e){if(copy!=null)try{Os.close(copy);}catch(ErrnoException ignored){}throw e;}}
                public void rewindDuplicateDescriptor(FileDescriptor duplicate)throws IOException{try{StreamingGroupStore.FileIdentity before=identity(Os.fstat(duplicate));requireRegularSingleLink(before);requireSame(fdBefore,before,"DUPLICATE_DESCRIPTOR_IDENTITY_MISMATCH");long position=Os.lseek(duplicate,0,OsConstants.SEEK_SET);if(position!=0)throw new IOException("DUPLICATE_DESCRIPTOR_REWIND_FAILED");StreamingGroupStore.FileIdentity after=identity(Os.fstat(duplicate));requireRegularSingleLink(after);requireSame(fdBefore,after,"DUPLICATE_DESCRIPTOR_IDENTITY_CHANGED");}catch(ErrnoException e){throw io("REWIND_VERIFIED_DESCRIPTOR",e);}}
                public void closeDuplicateDescriptor(FileDescriptor descriptor)throws IOException{try{Os.close(descriptor);}catch(ErrnoException e){throw io("CLOSE_DUPLICATE_DESCRIPTOR",e);}}
                public void close()throws IOException{try{input.close();}finally{synchronized(openAncestorWitnesses){openAncestorWitnesses.remove(pathBefore);}}}
            };
            synchronized(openAncestorWitnesses){if(openAncestorWitnesses.size()>=4096)throw new IOException("OPEN_ANCESTOR_WITNESS_LIMIT");openAncestorWitnesses.put(pathBefore,new AncestorWitness(ancestorsBefore));}
            return result;
        }catch(Throwable t){try{input.close();}catch(IOException close){t.addSuppressed(close);}if(t instanceof IOException)throw(IOException)t;throw new IOException("OPEN_IDENTITY_FAILED",t);}
    }

    @Override public void requireStable(Path path,StreamingGroupStore.FileIdentity pathBefore,StreamingGroupStore.FileIdentity fdBefore,
            StreamingGroupStore.FileIdentity fdAfter,StreamingGroupStore.FileIdentity pathAfter)throws IOException{
        requireSupported();AncestorWitness witness;
        // Keep the per-open ancestor witness through repeated lease verification;
        // StableInput.close releases only its own identity-keyed entry.
        synchronized(openAncestorWitnesses){witness=openAncestorWitnesses.get(pathBefore);if(witness==null)throw new IOException("ANCESTOR_WITNESS_MISSING");if(witness.invalidated)throw new IOException("DIRECTORY_ANCESTOR_CHANGED");}
        if(!pathBefore.equals(fdBefore)||!fdBefore.equals(fdAfter)||!fdAfter.equals(pathAfter))throw new IOException("FILE_IDENTITY_CHANGED");
        requireRegularSingleLink(pathAfter);
        try{requireSameAncestorDirectories(witness.directories,ancestorSnapshot(path.toAbsolutePath().normalize().getParent()));}
        catch(IOException changed){synchronized(openAncestorWitnesses){witness.invalidated=true;}throw changed;}
    }
    @Override public StreamingGroupStore.FileIdentity identity(Path path)throws IOException{
        requireSupported();Path absolute=trustedFilesRoot.requireInScope(path);try{return identity(Os.lstat(absolute.toString()));}catch(ErrnoException e){throw io("LSTAT",e);}
    }

    @Override public void ensurePlainDirectory(Path path)throws IOException{
        requireSupported();Path absolute=trustedFilesRoot.requireInScope(path);trustedFilesRoot.verify();
        Path current=trustedFilesRoot.path();if(absolute.equals(current)){requirePlainDirectory(absolute);return;}
        for(Path part:current.relativize(absolute)){requirePlainDirectory(current);current=current.resolve(part);try{StructStat st=Os.lstat(current.toString());requireAppDirectory(st,"DIRECTORY_ANCESTOR_NOT_PLAIN");}
            catch(ErrnoException e){if(e.errno!=OsConstants.ENOENT)throw io("LSTAT_DIRECTORY",e);try{Os.mkdir(current.toString(),0700);}catch(ErrnoException create){if(create.errno!=OsConstants.EEXIST)throw io("MKDIR",create);}StructStat made;try{made=Os.lstat(current.toString());}catch(ErrnoException check){throw io("LSTAT_CREATED_DIRECTORY",check);}requireAppDirectory(made,"CREATED_DIRECTORY_NOT_PLAIN");}}
        trustedFilesRoot.verify();
        requirePlainDirectory(absolute);
    }
    @Override public void requirePlainDirectory(Path path)throws IOException{
        requireSupported();Path absolute=trustedFilesRoot.requireInScope(path);ancestorSnapshot(absolute);
    }
    @Override public StreamingGroupStore.FileIdentity sealImmutable(Path path,StreamingGroupStore.FileIdentity expected)throws IOException{
        requireSupported();Path absolute=trustedFilesRoot.requireInScope(path);List<StreamingGroupStore.FileIdentity> ancestorsBefore=ancestorSnapshot(absolute.getParent());StreamingGroupStore.FileIdentity beforePath=identity(absolute);requireRegularSingleLink(beforePath);
        FileDescriptor fd;try{fd=Os.open(absolute.toString(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);}catch(ErrnoException e){throw io("SEAL_OPEN",e);}
        IOException failure=null;
        try{StreamingGroupStore.FileIdentity beforeFd=identity(Os.fstat(fd));requireSame(beforePath,beforeFd,"SEAL_PATH_FD_MISMATCH");requireSame(expected,beforeFd,"SEAL_EXPECTED_IDENTITY_MISMATCH");
            // A cold Store may verify an object another live Store already sealed. Avoid an
            // identical chmod on that read-only inode: it can perturb metadata witnesses even
            // though permissions do not change. Writable objects still receive the same seal.
            if((beforeFd.mode()&0222)!=0)Os.fchmod(fd,beforeFd.mode()&~0222);
            Os.fsync(fd);StreamingGroupStore.FileIdentity afterFd=identity(Os.fstat(fd)),afterPath=identity(absolute);
            requireRegularSingleLink(afterFd);requireSame(afterFd,afterPath,"SEAL_PATH_REPLACED");requireSameAncestors(ancestorsBefore,ancestorSnapshot(absolute.getParent()));if((afterFd.mode()&0222)!=0)throw new IOException("IMMUTABLE_MODE_NOT_SET");return afterFd;
        }catch(ErrnoException e){failure=io("SEAL_FD",e);throw failure;}finally{try{Os.close(fd);}catch(ErrnoException e){if(failure!=null)failure.addSuppressed(io("SEAL_CLOSE",e));else throw io("SEAL_CLOSE",e);}}
    }
    @Override public void requireImmutable(Path path,StreamingGroupStore.FileIdentity witness,StreamingGroupStore.FileIdentity current)throws IOException{
        requireSupported();requireRegularSingleLink(current);if(!witness.equals(current)||(current.mode()&0222)!=0)throw new IOException("OBJECT_NOT_IMMUTABLE");requirePlainAncestors(path.toAbsolutePath().normalize().getParent());
    }
    @Override public void syncDirectory(Path path)throws IOException{
        requireSupported();Path absolute=path.toAbsolutePath().normalize();requirePlainDirectory(absolute);
        FileDescriptor fd;try{fd=Os.open(absolute.toString(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);}catch(ErrnoException e){throw io("OPEN_DIRECTORY",e);}
        try{StreamingGroupStore.FileIdentity opened=identity(Os.fstat(fd)),named=identity(absolute);requireSame(opened,named,"DIRECTORY_REPLACED_BEFORE_SYNC");if(!OsConstants.S_ISDIR(opened.mode())||opened.reparse())throw new IOException("SYNC_TARGET_NOT_DIRECTORY");Os.fsync(fd);requireSame(opened,identity(absolute),"DIRECTORY_REPLACED_DURING_SYNC");}
        catch(ErrnoException e){throw io("FSYNC_DIRECTORY",e);}finally{try{Os.close(fd);}catch(ErrnoException e){throw io("CLOSE_DIRECTORY",e);}}
    }
    /** Atomically installs the R2 transaction stage without replacing an existing destination. */
    @Override public void moveNoReplace(Path source,Path destination)throws IOException{
        requireSupported();Path src=source.toAbsolutePath().normalize(),dst=destination.toAbsolutePath().normalize();
        Path transactions=storeRoot.resolve("transactions"),visible=storeRoot.resolve("visible");
        boolean objectStage=src.getParent()!=null&&src.getParent().getParent()!=null&&src.getParent().getParent().equals(transactions)
                &&src.getParent().getFileName().toString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                &&src.getFileName().toString().matches("object-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp")
                &&dst.getParent().equals(objectDirectory)&&dst.getFileName().toString().matches("[0-9a-f]{64}");
        boolean markerStage=src.getParent().equals(visible)&&src.getFileName().toString().matches("\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.marker")
                &&dst.getParent().equals(visible)&&dst.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.marker");
        if(!objectStage&&!markerStage)throw new IOException("NOREPLACE_PATH_SCOPE");
        List<StreamingGroupStore.FileIdentity> sourceAncestors=ancestorSnapshot(src.getParent());
        List<StreamingGroupStore.FileIdentity> destinationAncestors=ancestorSnapshot(dst.getParent());
        StreamingGroupStore.FileIdentity before=identity(src);requireRegularSingleLink(before);
        if(existsNoFollow(dst))throw new FileAlreadyExistsException(dst.toString());
        int error=NativeNoReplace.renameNoReplace(src.getParent(),src.getFileName().toString(),dst.getParent(),dst.getFileName().toString());
        if(error==OsConstants.EEXIST||error==OsConstants.ENOTEMPTY)throw new FileAlreadyExistsException(dst.toString());
        if(error!=0)throw new IOException("NOREPLACE_NATIVE_ERRNO_"+error);
        if(installObserver!=null)installObserver.afterAtomicNoReplaceMove(src,dst);
        StreamingGroupStore.FileIdentity installed=identity(dst);
        if(existsNoFollow(src)||!sameObject(before,installed)||installed.links()!=1)throw new IOException("NOREPLACE_INSTALL_IDENTITY");
        requireSameAncestorDirectories(sourceAncestors,ancestorSnapshot(src.getParent()));
        requireSameAncestorDirectories(destinationAncestors,ancestorSnapshot(dst.getParent()));
        if(dst.getParent().equals(objectDirectory))metrics.objectInstalledBytes.addAndGet(installed.size());
    }
    @Override public void installImmutableGroup(Path source,Path destination)throws IOException{
        requireSupported();Path stage=source.toAbsolutePath().normalize(),dst=destination.toAbsolutePath().normalize();
        if(!stage.startsWith(storeRoot.resolve("transactions"))||!dst.startsWith(storeRoot.resolve("groups")))throw new IOException("IMMUTABLE_GROUP_PATH_SCOPE");
        requirePlainDirectory(stage);requirePlainDirectory(dst.getParent());requireGroupChildren(stage);
        if(existsNoFollow(dst))throw new FileAlreadyExistsException(dst.toString());
        try{Os.mkdir(dst.toString(),0700);}catch(ErrnoException e){if(e.errno==OsConstants.EEXIST)throw new FileAlreadyExistsException(dst.toString());throw io("MKDIR_IMMUTABLE_GROUP",e);}
        StreamingGroupStore.FileIdentity dstCreated=identity(dst);requireDirectory(dstCreated);StreamingGroupStore.FileIdentity parentCreated=identity(dst.getParent());requireDirectory(parentCreated);
        if(groupInstallObserver!=null)groupInstallObserver.afterDirectoryCreated(dst);
        copyGroupFile(stage.resolve("manifest.bin"),dst.resolve("manifest.bin"),16L<<20);
        if(groupInstallObserver!=null)groupInstallObserver.afterFileInstalled(dst,"manifest.bin");
        copyGroupFile(stage.resolve("owner.txt"),dst.resolve("owner.txt"),4096);
        if(groupInstallObserver!=null)groupInstallObserver.afterFileInstalled(dst,"owner.txt");
        requireGroupChildren(dst);requireDirectorySame(dstCreated,identity(dst),"GROUP_DIRECTORY_REPLACED");requireDirectorySame(parentCreated,identity(dst.getParent()),"GROUP_PARENT_REPLACED");
        for(String name:new String[]{"manifest.bin","owner.txt"})requireRegularSingleLink(identity(dst.resolve(name)));
        syncDirectory(dst);syncDirectory(dst.getParent());
        requireGroupChildren(dst);requireDirectorySame(dstCreated,identity(dst),"GROUP_DIRECTORY_REPLACED_AFTER_SYNC");requireDirectorySame(parentCreated,identity(dst.getParent()),"GROUP_PARENT_REPLACED_AFTER_SYNC");
    }
    private void copyGroupFile(Path source,Path destination,long cap)throws IOException{
        if(existsNoFollow(destination))throw new FileAlreadyExistsException(destination.toString());
        StreamingGroupStore.FileIdentity sourcePath=identity(source);requireRegularSingleLink(sourcePath);if(sourcePath.size()<0||sourcePath.size()>cap)throw new IOException("GROUP_MEMBER_SIZE_LIMIT");
        StreamingGroupStore.StableInput in=open(source,sourcePath.size());FileDescriptor outFd=null;FileOutputStream out=null;IOException failure=null;
        try{
            outFd=Os.open(destination.toString(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);FileDescriptor ownedOut=outFd;outFd=null;out=OwnedFdStreams.output(ownedOut);
            StreamingGroupStore.FileIdentity outBefore=identity(Os.fstat(ownedOut)),outPathBefore=identity(destination);requireRegularSingleLink(outBefore);requireSame(outBefore,outPathBefore,"GROUP_OUTPUT_PATH_FD_MISMATCH");
            byte[] buffer=new byte[StreamingGroupStore.COPY_BUFFER_BYTES];long count=0;for(int n;(n=in.stream().read(buffer))!=-1;){if(n==0)continue;count+=n;if(count>sourcePath.size()||count>cap)throw new IOException("GROUP_MEMBER_GREW");out.write(buffer,0,n);}if(count!=sourcePath.size())throw new IOException("GROUP_MEMBER_TRUNCATED");out.getFD().sync();
            StreamingGroupStore.FileIdentity sourceAfter=in.after(),sourcePathAfter=identity(source);requireSameAncestorsFromOpen(source,in,sourceAfter,sourcePathAfter);
            Os.fchmod(ownedOut,outBefore.mode()&~0222);out.getFD().sync();
            StreamingGroupStore.FileIdentity outAfter=identity(Os.fstat(ownedOut)),outPathAfter=identity(destination);requireRegularSingleLink(outAfter);requireSame(outAfter,outPathAfter,"GROUP_OUTPUT_CHANGED");if(outAfter.size()!=count||(outAfter.mode()&0222)!=0)throw new IOException("GROUP_OUTPUT_SIZE_OR_MODE_MISMATCH");
        }catch(ErrnoException e){failure=io("GROUP_COPY_FD",e);throw failure;}catch(IOException e){failure=e;throw e;}finally{
            try{in.close();}catch(IOException e){if(failure!=null)failure.addSuppressed(e);else failure=e;}
            if(out!=null)try{out.close();}catch(IOException e){if(failure!=null)failure.addSuppressed(e);else failure=e;}
            if(outFd!=null)try{Os.close(outFd);}catch(ErrnoException e){IOException close=io("GROUP_COPY_OUTPUT_CLOSE",e);if(failure!=null)failure.addSuppressed(close);else failure=close;}
            if(failure!=null)throw failure;
        }
    }
    private void requireSameAncestorsFromOpen(Path source,StreamingGroupStore.StableInput in,StreamingGroupStore.FileIdentity fdAfter,StreamingGroupStore.FileIdentity pathAfter)throws IOException{
        requireStable(source,in.pathBefore(),in.before(),fdAfter,pathAfter);
    }
    private void requireGroupChildren(Path directory)throws IOException{
        requirePlainDirectory(directory);java.util.HashSet<String> names=new java.util.HashSet<String>();
        try(java.nio.file.DirectoryStream<Path> children=Files.newDirectoryStream(directory)){for(Path child:children){String name=child.getFileName().toString();if(!names.add(name))throw new IOException("GROUP_CHILD_DUPLICATE");requireRegularSingleLink(identity(child));}}
        if(!names.equals(new java.util.HashSet<String>(Arrays.asList("manifest.bin","owner.txt"))))throw new IOException("GROUP_CHILD_SET_MISMATCH");
    }
    private static void requireDirectory(StreamingGroupStore.FileIdentity id)throws IOException{if(id==null||!OsConstants.S_ISDIR(id.mode())||id.reparse())throw new IOException("DIRECTORY_NOT_PLAIN");}
    private static void requireDirectorySame(StreamingGroupStore.FileIdentity expected,StreamingGroupStore.FileIdentity actual,String code)throws IOException{requireDirectory(actual);if(!expected.device().equals(actual.device())||!expected.fileKey().equals(actual.fileKey())||expected.mode()!=actual.mode()||expected.links()!=actual.links())throw new IOException(code);}
    @Override public StreamingGroupStore.LockLease lock(Path lockFile)throws IOException{return openLock(lockFile,false).get();}
    @Override public Optional<StreamingGroupStore.LockLease> tryLock(Path lockFile)throws IOException{return openLock(lockFile,true);}
    @Override public void replaceAtomic(Path source,Path destination)throws IOException{
        requireSupported();Path src=source.toAbsolutePath().normalize(),dst=destination.toAbsolutePath().normalize();requirePlainAncestors(src.getParent());requirePlainDirectory(dst.getParent());
        StreamingGroupStore.FileIdentity s=identity(src);requireRegularSingleLink(s);if(existsNoFollow(dst))requireRegularSingleLink(identity(dst));
        try{Os.rename(src.toString(),dst.toString());}catch(ErrnoException e){throw io("ATOMIC_RENAME_REPLACE",e);}StreamingGroupStore.FileIdentity after=identity(dst);
        if(!sameObject(s,after)||after.links()!=1)throw new IOException("ATOMIC_REPLACE_IDENTITY");
    }
    private Optional<StreamingGroupStore.LockLease> openLock(Path lockFile,boolean nonBlocking)throws IOException{
        requireSupported();Path p=lockFile.toAbsolutePath().normalize();requirePlainDirectory(p.getParent());boolean existed=existsNoFollow(p);FileDescriptor fd;
        try{fd=Os.open(p.toString(),OsConstants.O_RDWR|OsConstants.O_CREAT|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);}catch(ErrnoException e){throw io("OPEN_LOCK",e);}
        FileOutputStream stream=null;FileChannel channel=null;FileDescriptor openedFd=fd,unownedFd=fd;
        try{unownedFd=null;stream=OwnedFdStreams.output(openedFd);channel=stream.getChannel();StreamingGroupStore.FileIdentity named=identity(p),opened=identity(Os.fstat(openedFd));requireRegularSingleLink(named);requireSame(named,opened,"LOCK_PATH_FD_MISMATCH");if(!existed)syncDirectory(p.getParent());FileLock lock;
            try{lock=nonBlocking?channel.tryLock():channel.lock();}catch(OverlappingFileLockException e){lock=null;}
            if(lock==null){Throwable closeFailure=closeLockResources(channel,stream,fd,null);if(closeFailure instanceof IOException)throw(IOException)closeFailure;if(closeFailure instanceof RuntimeException)throw(RuntimeException)closeFailure;return Optional.empty();}
            final FileLock held=lock;final FileChannel heldChannel=channel;final FileOutputStream heldStream=stream;
            return Optional.of(new StreamingGroupStore.LockLease(){private boolean closed;@Override public synchronized void close()throws IOException{if(closed)return;closed=true;closeLockLease(held,heldChannel,heldStream);}});
        }catch(ErrnoException e){IOException failure=io("FSTAT_LOCK",e);closeLockResources(channel,stream,unownedFd,failure);throw failure;}catch(IOException|RuntimeException|Error e){closeLockResources(channel,stream,unownedFd,e);throw e;}
    }
    private static Throwable closeLockResources(FileChannel channel,FileOutputStream stream,FileDescriptor fd,Throwable failure){
        if(channel!=null)try{channel.close();}catch(IOException close){if(failure==null)failure=close;else failure.addSuppressed(close);}
        if(stream!=null)try{stream.close();}catch(IOException close){if(failure==null)failure=close;else failure.addSuppressed(close);}
        else if(fd!=null)try{Os.close(fd);}catch(ErrnoException close){IOException wrapped=io("CLOSE_LOCK",close);if(failure==null)failure=wrapped;else failure.addSuppressed(wrapped);}
        return failure;
    }
    private static void closeLockLease(FileLock lock,FileChannel channel,FileOutputStream stream)throws IOException{
        IOException failure=null;
        try{lock.release();}catch(IOException error){failure=error;}
        try{channel.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        try{stream.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure!=null)throw failure;
    }
    private boolean existsNoFollow(Path path)throws IOException{Path absolute=trustedFilesRoot.requireInScope(path);try{Os.lstat(absolute.toString());return true;}catch(ErrnoException e){if(e.errno==OsConstants.ENOENT)return false;throw io("LSTAT_EXISTS",e);}}
    private void requirePlainAncestors(Path directory)throws IOException{ancestorSnapshot(directory);}
    private List<StreamingGroupStore.FileIdentity> ancestorSnapshot(Path directory)throws IOException{
        if(directory==null)throw new IOException("MISSING_PARENT");Path absolute=trustedFilesRoot.requireInScope(directory);trustedFilesRoot.verify();
        ArrayList<StreamingGroupStore.FileIdentity> result=new ArrayList<>();result.add(anchorDirectoryIdentity());Path current=trustedFilesRoot.path();
        for(Path part:current.relativize(absolute)){current=current.resolve(part);StructStat stat;try{stat=Os.lstat(current.toString());}catch(ErrnoException error){throw io("LSTAT_DIRECTORY_ANCESTOR",error);}requireAppDirectory(stat,"DIRECTORY_ANCESTOR_NOT_PLAIN");try{result.add(identity(stat));}catch(ErrnoException error){throw io("DIRECTORY_ANCESTOR_IDENTITY",error);}}
        trustedFilesRoot.verify();return result;
    }
    private static void requireSameAncestors(List<StreamingGroupStore.FileIdentity> a,List<StreamingGroupStore.FileIdentity> b)throws IOException{requireSameAncestorDirectories(a,b);}
    private static void requireSameAncestorDirectories(List<StreamingGroupStore.FileIdentity> a,List<StreamingGroupStore.FileIdentity> b)throws IOException{
        if(a.size()!=b.size())throw new IOException("DIRECTORY_ANCESTOR_CHAIN_CHANGED");
        for(int i=0;i<a.size();i++){
            StreamingGroupStore.FileIdentity x=a.get(i),y=b.get(i);
            if(!x.device().equals(y.device())||!x.fileKey().equals(y.fileKey())||x.mode()!=y.mode()||x.reparse()||y.reparse())
                throw new IOException("DIRECTORY_ANCESTOR_CHANGED");
        }
    }
    private static void requireRegularSingleLink(StreamingGroupStore.FileIdentity id)throws IOException{if(id==null||!OsConstants.S_ISREG(id.mode())||id.reparse()||id.links()!=1)throw new IOException("REGULAR_SINGLE_LINK_REQUIRED");}
    private static void requireSame(StreamingGroupStore.FileIdentity a,StreamingGroupStore.FileIdentity b,String code)throws IOException{if(!a.equals(b))throw new IOException(code);}
    private static boolean sameObject(StreamingGroupStore.FileIdentity a,StreamingGroupStore.FileIdentity b){return a.device().equals(b.device())&&a.fileKey().equals(b.fileKey())&&a.size()==b.size()&&a.modifiedNanos()==b.modifiedNanos()&&a.mode()==b.mode()&&a.reparse()==b.reparse();}
    private static StreamingGroupStore.FileIdentity identity(FileDescriptor fd)throws ErrnoException{return identity(Os.fstat(fd));}
    private static StreamingGroupStore.FileIdentity identity(StructStat stat)throws ErrnoException{
        if(Build.VERSION.SDK_INT<27||stat.st_mtim==null||stat.st_ctim==null)throw new ErrnoException("fstat_nanos",OsConstants.ENOTSUP);
        if(stat.st_mtim.tv_nsec<0||stat.st_mtim.tv_nsec>=1_000_000_000L||stat.st_ctim.tv_nsec<0||stat.st_ctim.tv_nsec>=1_000_000_000L)throw new ErrnoException("fstat_nsec_range",OsConstants.EOVERFLOW);
        long modified,changed;try{modified=Math.addExact(Math.multiplyExact(stat.st_mtim.tv_sec,1_000_000_000L),stat.st_mtim.tv_nsec);changed=Math.addExact(Math.multiplyExact(stat.st_ctim.tv_sec,1_000_000_000L),stat.st_ctim.tv_nsec);}catch(ArithmeticException e){throw new ErrnoException("fstat_time_overflow",OsConstants.EOVERFLOW,e);}
        return new StreamingGroupStore.FileIdentity(Long.toString(stat.st_dev),Long.toString(stat.st_ino),stat.st_size,modified,changed,stat.st_mode,stat.st_nlink,OsConstants.S_ISLNK(stat.st_mode));
    }
    private StreamingGroupStore.FileIdentity anchorDirectoryIdentity()throws IOException{
        try{return new StreamingGroupStore.FileIdentity(Long.toString(trustedFilesRoot.device()),Long.toString(trustedFilesRoot.inode()),0,0,0,trustedFilesRoot.mode(),1,false);}
        catch(RuntimeException error){throw new IOException("TRUSTED_FILES_ROOT_WITNESS_INVALID");}
    }
    private void requireAppDirectory(StructStat stat,String code)throws IOException{
        if(!OsConstants.S_ISDIR(stat.st_mode)||OsConstants.S_ISLNK(stat.st_mode)||stat.st_uid!=trustedFilesRoot.appUid())throw new IOException(code);
    }
    private static IOException io(String operation,ErrnoException e){return new IOException(operation+"_ERRNO_"+e.errno,e);}
    private static void requireSupported()throws IOException{try{StreamingFeatureGate.requireSupported();}catch(UnsupportedOperationException e){throw new IOException("STREAMING_STORE_API_UNSUPPORTED",e);}}
}
