package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.util.UUID;

/** Runtime checks for API 24-compatible atomic close-on-exec and no-follow/exclusive open flags. */
@RunWith(AndroidJUnit4.class)
public final class AndroidFileCompatInstrumentedTest {
    @Test public void compatibilityFlagsRemainAtomicAndPreserveNoFollowAndExclusiveCreate() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir=new File(target.getCacheDir(),"file-compat-"+UUID.randomUUID());
        assertEquals(true,dir.mkdir());
        File file=new File(dir,"owned.bin"),link=new File(dir,"linked.bin");
        try {
            FileDescriptor fd=Os.open(file.getAbsolutePath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|
                    OsConstants.O_EXCL|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);
            try {
                int descriptorFlags=Os.fcntlInt(fd,OsConstants.F_GETFD,0);
                assertTrue("O_CLOEXEC must set FD_CLOEXEC atomically",
                        (descriptorFlags & OsConstants.FD_CLOEXEC)!=0);
            } finally { Os.close(fd); }

            try {
                Os.open(file.getAbsolutePath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|
                        OsConstants.O_EXCL|AndroidFileCompat.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);
                fail("O_EXCL must reject an existing file");
            } catch (ErrnoException expected) {
                assertEquals(OsConstants.EEXIST,expected.errno);
            }

            Os.symlink(file.getAbsolutePath(),link.getAbsolutePath());
            try {
                Os.open(link.getAbsolutePath(),OsConstants.O_RDONLY|AndroidFileCompat.O_CLOEXEC|
                        OsConstants.O_NOFOLLOW,0);
                fail("O_NOFOLLOW must reject a symlink");
            } catch (ErrnoException expected) {
                assertEquals(OsConstants.ELOOP,expected.errno);
            }
        } finally {
            try { Os.remove(link.getAbsolutePath()); } catch (ErrnoException ignored) { }
            if(file.exists())file.delete();
            dir.delete();
        }
    }
}
