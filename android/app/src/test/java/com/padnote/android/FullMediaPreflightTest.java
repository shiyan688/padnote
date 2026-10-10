package com.padnote.android;

import org.junit.Test;

import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public final class FullMediaPreflightTest {
    @Test public void pngSignatureAndMimeAreExact() throws Exception {
        byte[] valid={(byte)0x89,'P','N','G',13,10,26,10,0};
        FullMediaPreflight.requirePngSignature(valid,"fixture");
        FullMediaPreflight.requirePngMime("image/png","fixture");
        for(byte[] bad:new byte[][] {new byte[0],new byte[] {(byte)0x89,'P','N','G',13,10,26},
                new byte[] {'G','I','F','8','9','a',0,0},new byte[] {(byte)0xff,(byte)0xd8,(byte)0xff,0,0,0,0,0}}) {
            try { FullMediaPreflight.requirePngSignature(bad,"fixture"); fail("malformed/non-PNG signature accepted"); }
            catch(java.io.IOException expected) { assertTrue(expected.getMessage().contains("PNG_SIGNATURE")); }
        }
        try { FullMediaPreflight.requirePngMime("image/jpeg","fixture"); fail("JPEG MIME accepted as PNG"); }
        catch(java.io.IOException expected) { assertTrue(expected.getMessage().contains("PNG_TYPE")); }
    }

    @Test public void everyVideoTrackIsBoundedRatherThanOnlyTheLast() throws Exception {
        FullMediaPreflight.VideoTrackFacts audio=new FullMediaPreflight.VideoTrackFacts(false,0,0,1000);
        FullMediaPreflight.VideoTrackFacts small=new FullMediaPreflight.VideoTrackFacts(true,320,240,1000);
        FullMediaPreflight.validateVideoTracks(Arrays.asList(audio,small),2,NoteImage.MAX_DOCUMENT_PIXELS);
        assertRejects(Arrays.asList(new FullMediaPreflight.VideoTrackFacts(true,5000,3000,1000),small),2);
        assertRejects(Arrays.asList(new FullMediaPreflight.VideoTrackFacts(true,320,240,0),small),2);
        assertRejects(Collections.singletonList(audio),1);
        assertRejects(Collections.singletonList(small),FullMediaPreflight.MAX_VIDEO_TRACKS+1);
    }

    @Test public void returnedFrameBudgetIsBoundedAndScalePreservesAspectRatio() throws Exception {
        int[] size=FullMediaPreflight.scaledFrameSize(3840,2160);
        assertTrue(size[0]<=FullMediaPreflight.MAX_DECODED_FRAME_EDGE);
        assertTrue(size[1]<=FullMediaPreflight.MAX_DECODED_FRAME_EDGE);
        assertEquals(256,size[0]); assertEquals(144,size[1]);
        FullMediaPreflight.requireDecodedFrame(size[0],size[1]);
        try { FullMediaPreflight.requireDecodedFrame(257,257); fail("oversized decoded bitmap accepted"); }
        catch(java.io.IOException expected) { assertTrue(expected.getMessage().contains("FRAME_BUDGET")); }
    }

    @Test public void interruptionCheckpointIsCooperativeAndPreservesFlag() throws Exception {
        Thread.currentThread().interrupt();
        try { FullMediaPreflight.checkInterrupted(); fail("interrupted validation continued"); }
        catch(InterruptedIOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        FullMediaPreflight.checkInterrupted();
    }

    private static void assertRejects(java.util.List<FullMediaPreflight.VideoTrackFacts> tracks,int declared) throws Exception {
        try { FullMediaPreflight.validateVideoTracks(tracks,declared,NoteImage.MAX_DOCUMENT_PIXELS); fail("invalid track facts accepted"); }
        catch(java.io.IOException expected) { assertTrue(expected.getMessage().startsWith("VIDEO_TRACK")); }
    }
}
