package com.padnote.android.streaming;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.padnote.android.OwnedFdStreams;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies failed-copy cleanup does not unlink a replacement path owned by another inode. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class LegacyFileCaptureOwnershipInstrumentedTest {
    @Test public void failedCopyCleanupLeavesAReplacedForeignDestinationUntouched() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path fixture=target.getFilesDir().toPath().resolve("legacy-copy-replacement-"+UUID.randomUUID());
        Files.createDirectory(fixture);
        try {
            Path destination=fixture.resolve("owned.mp4");byte[] createdBytes={1,2,3};
            java.io.FileDescriptor fd=Os.open(destination.toString(),OsConstants.O_WRONLY|OsConstants.O_CREAT|
                    OsConstants.O_EXCL|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);
            StructStat created;
            try(FileOutputStream output=OwnedFdStreams.output(fd)) {
                output.write(createdBytes);output.flush();output.getFD().sync();created=Os.fstat(output.getFD());
            }
            Path displaced=fixture.resolve("displaced-owned.mp4");Files.move(destination,displaced);
            byte[] foreign={9,8,7,6};Files.write(destination,foreign);
            assertArrayEquals("the original owned inode is still held at a different name",createdBytes,Files.readAllBytes(displaced));
            IOException original=new IOException("expected_copy_failure");
            LegacyFileCapture.cleanupOwnedOutput(destination,created,null,original);
            assertArrayEquals("cleanup preserves a different inode at the destination",foreign,Files.readAllBytes(destination));
            assertEquals("identity mismatch is recorded without exposing paths",1,original.getSuppressed().length);
            assertEquals("LEGACY_CAPTURE_COPY_CLEANUP_IDENTITY_CHANGED",original.getSuppressed()[0].getMessage());
        } finally {
            if(Files.exists(fixture,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try(Stream<Path> paths=Files.walk(fixture)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception error){throw new RuntimeException(error);}});
                }
            }
        }
    }
}
