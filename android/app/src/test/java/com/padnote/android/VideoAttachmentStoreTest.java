package com.padnote.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VideoAttachmentStoreTest {
    private static final String BUNDLE="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @Test public void verifiedAttachmentSurvivesStoreReopenAndDuplicateLinkIsIdempotent() throws Exception {
        File root=temp("padnote-video-attachments");
        File source=new File(root,"verified.mp4");byte[] bytes={0,0,0,24,102,116,121,112,109,112,52,50};Files.write(source.toPath(),bytes);
        String sha=sha(bytes);AtomicBoolean exists=new AtomicBoolean(true);
        VideoAttachmentStore first=new VideoAttachmentStore(new File(root,"private"),id->exists.get()?new NoteStore.Entry(id,"n",1234,1,1):null);
        VideoAttachmentStore.Attachment a=first.attach("note-1",1234,BUNDLE,"padnote-task1","remote1","conn1","bridge1","instance1","artifact1","课件.mp4","video/mp4",bytes.length,sha,source);
        assertArrayEquals(bytes,Files.readAllBytes(first.openVerified(a).toPath()));
        VideoAttachmentStore.Attachment same=first.attach("note-1",1234,BUNDLE,"padnote-task1","remote1","conn1","bridge1","instance1","artifact1","different-name.mp4","video/mp4",bytes.length,sha,source);
        assertEquals(a.id,same.id);
        assertEquals("",same.certSha256);assertEquals("HERMES",same.kind);assertEquals("BRIDGE",same.transport);
        assertEquals(BUNDLE,same.sourceBundleSha256);assertEquals(BUNDLE,same.taskPayloadSha256);
        assertEquals("课件.mp4",same.name);
        assertTrue(!VideoAttachmentStore.isSourceStale(new NoteStore.Entry("note-1","n",1234,1,1),same));
        assertTrue(VideoAttachmentStore.isSourceStale(new NoteStore.Entry("note-1","n",1235,1,1),same));
        assertTrue(VideoAttachmentStore.isSourceStale(null,same));
        VideoAttachmentStore reopened=new VideoAttachmentStore(new File(root,"private"),id->exists.get()?new NoteStore.Entry(id,"n",1234,1,1):null);
        List<VideoAttachmentStore.Attachment> found=reopened.listForNote("note-1");
        assertEquals(1,found.size());assertArrayEquals(bytes,Files.readAllBytes(reopened.openVerified(found.get(0)).toPath()));
        assertEquals("conn1",found.get(0).connectionId);assertEquals("remote1",found.get(0).remoteTaskId);
        assertEquals(1234L,found.get(0).sourceRevision);assertEquals(1L,found.get(0).connectionRevision);
        reopened.remove(a.id);assertTrue(reopened.listForNote("note-1").isEmpty());
        assertTrue(root.delete()||root.exists());
    }

    @Test public void wrongShaDuplicateAndDeletedSourceAreRejectedWithoutReplacingExistingBytes() throws Exception {
        File root=temp("padnote-video-reject");File base=new File(root,"src");
        byte[] original={1,2,3,4};Files.write(base.toPath(),original);AtomicBoolean exists=new AtomicBoolean(true);
        VideoAttachmentStore store=new VideoAttachmentStore(new File(root,"private"),id->exists.get()?new NoteStore.Entry(id,"n",9,0,1):null);
        String hash=sha(original);VideoAttachmentStore.Attachment a=store.attach("note-2",9,BUNDLE,"padnote-task2","remote2","conn2","bridge2","instance2","artifact2","x.mp4","video/mp4",4,hash,base);
        File bad=new File(root,"bad");Files.write(bad.toPath(),new byte[]{9,9,9,9});
        try{store.attach("note-2",9,BUNDLE,"padnote-task3","remote3","conn2","bridge2","instance2","artifact3","bad.mp4","video/mp4",4,hash,bad);fail("wrong SHA accepted");}
        catch(java.io.IOException expected){}
        try{store.attach("note-2",9,BUNDLE,"padnote-task2","remote2","conn2","bridge2","instance2","artifact2","x.mp4","video/mp4",4,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",base);fail("conflicting duplicate accepted");}
        catch(java.io.IOException expected){}
        try{store.attach("note-2",10,BUNDLE,"padnote-task2","remote2","conn2","bridge2","instance2","artifact2","x.mp4","video/mp4",4,hash,base);fail("changed revision accepted for existing link");}
        catch(java.io.IOException expected){}
        assertArrayEquals(original,Files.readAllBytes(store.openVerified(a).toPath()));
        exists.set(false);
        try{store.attach("note-2",9,BUNDLE,"padnote-task4","remote4","conn2","bridge2","instance2","artifact4","x.mp4","video/mp4",4,hash,base);fail("deleted source accepted");}
        catch(java.io.IOException expected){}
        assertEquals(1,store.listForNote("note-2").size());
    }

    @Test public void corruptedIndexRecoversOnlyFromValidatedBackup() throws Exception {
        File root=temp("padnote-video-backup");File src=new File(root,"src");byte[] bytes={3,4,5};Files.write(src.toPath(),bytes);
        VideoAttachmentStore store=new VideoAttachmentStore(new File(root,"private"),id->new NoteStore.Entry(id,"n",1,0,1));
        store.attach("note-b",1,BUNDLE,"task-b","remote-b","connection-b","bridge-b","instance-b","artifact-b","名片.mp4","video/mp4",bytes.length,sha(bytes),src);
        File index=new File(root,"private/index.json"),backup=new File(root,"private/index.bak");Files.copy(index.toPath(),backup.toPath());Files.write(index.toPath(),new byte[]{1,2,3});
        assertEquals(1,store.listForNote("note-b").size());assertTrue(index.isFile());assertTrue(!backup.exists());
    }

    @Test public void sameRemoteIdentifierOnDifferentConnectionsRemainsDistinct() throws Exception {
        File root=temp("padnote-video-identities");File src=new File(root,"src");byte[] bytes={7,8};Files.write(src.toPath(),bytes);
        VideoAttachmentStore store=new VideoAttachmentStore(new File(root,"private"),id->new NoteStore.Entry(id,"n",1,0,1));
        store.attach("note-c",1,BUNDLE,BUNDLE,"local-task-1","remote-same","connection-a",1,
                "HERMES","BRIDGE","bridge-a","instance-a","","artifact-a","a.mp4","video/mp4",bytes.length,sha(bytes),src);
        store.attach("note-c",1,BUNDLE,BUNDLE,"local-task-2","remote-same","connection-b",1,
                "HERMES","BRIDGE","bridge-b","instance-b","","artifact-a","b.mp4","video/mp4",bytes.length,sha(bytes),src);
        assertEquals(2,store.listForNote("note-c").size());
    }

    @Test public void sourceSymlinkIsRejectedBeforeCopy() throws Exception {
        File root=temp("padnote-video-link");File actual=new File(root,"real");Files.write(actual.toPath(),new byte[]{1});
        File link=new File(root,"link");try{Files.createSymbolicLink(link.toPath(),actual.toPath());}catch(UnsupportedOperationException|java.io.IOException denied){return;}
        VideoAttachmentStore store=new VideoAttachmentStore(new File(root,"private"),id->new NoteStore.Entry(id,"n",1,0,1));
        try{store.attach("note-link",1,BUNDLE,"task-link","remote-link","connection-link","bridge-link","instance-link","artifact-link","x.mp4","video/mp4",1,sha(new byte[]{1}),link);fail("source symlink accepted");}catch(java.io.IOException expected){}
    }

    @Test public void attachmentRootSymlinkCannotRedirectStorage() throws Exception {
        File root=temp("padnote-video-root-link");File outside=new File(root,"other");assertTrue(outside.mkdirs());
        File linkedRoot=new File(root,"private");
        try{Files.createSymbolicLink(linkedRoot.toPath(),outside.toPath());}
        catch(UnsupportedOperationException|java.io.IOException denied){return;}
        File src=new File(root,"src");byte[] bytes={4,5};Files.write(src.toPath(),bytes);
        VideoAttachmentStore store=new VideoAttachmentStore(linkedRoot,id->new NoteStore.Entry(id,"n",1,0,1));
        try{
            store.attach("note-root-link",1,BUNDLE,"task-root-link","remote-root-link","connection-root-link",
                    "bridge-root-link","instance-root-link","artifact-root-link","x.mp4","video/mp4",
                    bytes.length,sha(bytes),src);
            fail("attachment root symlink redirected storage");
        }catch(java.io.IOException expected){}
        assertTrue(!new File(outside,"index.json").exists());
    }

    @Test public void canonicalParentAliasIsAllowedButStoredLeafSymlinkIsRejected() throws Exception {
        File root=temp("padnote-video-parent-alias");File realParent=new File(root,"real");assertTrue(realParent.mkdirs());
        File parentAlias=new File(root,"alias");
        try{Files.createSymbolicLink(parentAlias.toPath(),realParent.toPath());}
        catch(UnsupportedOperationException|java.io.IOException denied){return;}
        File source=new File(root,"verified.mp4");byte[] bytes={0,0,0,24};Files.write(source.toPath(),bytes);
        VideoAttachmentStore store=new VideoAttachmentStore(new File(parentAlias,"private"),
                id->new NoteStore.Entry(id,"n",1,0,1));
        VideoAttachmentStore.Attachment attached=store.attach("note-alias",1,BUNDLE,"task-alias","remote-alias",
                "connection-alias","bridge-alias","instance-alias","artifact-alias","alias.mp4","video/mp4",
                bytes.length,sha(bytes),source);
        File stored=store.openVerified(attached);assertTrue(stored.isFile());
        assertTrue(stored.delete());Files.createSymbolicLink(stored.toPath(),source.toPath());
        try{store.openVerified(attached);fail("stored video symlink accepted");}catch(java.io.IOException expected){}
    }


    @Test public void failedNoteCleanupKeepsFailedFileRecordForRetry() throws Exception {
        File root=temp("padnote-video-cleanup");File src=new File(root,"src");byte[] bytes={8,9};Files.write(src.toPath(),bytes);
        VideoAttachmentStore store=new VideoAttachmentStore(new File(root,"private"),id->new NoteStore.Entry(id,"n",1,0,1));
        VideoAttachmentStore.Attachment a=store.attach("note-d",1,BUNDLE,"task-d","remote-d","connection-d","bridge-d","instance-d","artifact-d","d.mp4","video/mp4",2,sha(bytes),src);
        File media=store.openVerified(a);assertTrue(media.delete());assertTrue(media.mkdir());Files.write(new File(media,"keep").toPath(),new byte[]{1});
        try{store.removeForNote("note-d");fail("nonempty directory cleanup claimed success");}catch(java.io.IOException expected){}
        assertEquals(1,store.listForNote("note-d").size());new File(media,"keep").delete();media.delete();
        store.removeForNote("note-d");assertTrue(store.listForNote("note-d").isEmpty());
    }

    private static File temp(String prefix)throws Exception{return Files.createTempDirectory(prefix).toFile().getCanonicalFile();}
    private static String sha(byte[] bytes)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
