package com.padnote.android;

import java.io.InterruptedIOException;
import java.util.List;

/** Pure, deterministic limits shared by the real staged-media verifier. */
final class FullMediaPreflight {
    static final int MAX_VIDEO_TRACKS = 16;
    static final int MAX_DECODED_FRAME_EDGE = 256;
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10
    };

    static final class VideoTrackFacts {
        final boolean video;
        final int width;
        final int height;
        final long durationUs;

        VideoTrackFacts(boolean video, int width, int height, long durationUs) {
            this.video = video;
            this.width = width;
            this.height = height;
            this.durationUs = durationUs;
        }
    }

    private FullMediaPreflight() {}

    static void requirePngSignature(byte[] bytes, String label) throws java.io.IOException {
        if (bytes == null || bytes.length < PNG_SIGNATURE.length) throw new java.io.IOException(label + "_PNG_SIGNATURE_INVALID");
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) throw new java.io.IOException(label + "_PNG_SIGNATURE_INVALID");
        }
    }

    static void requirePngMime(String mime, String label) throws java.io.IOException {
        if (!"image/png".equals(mime)) throw new java.io.IOException(label + "_PNG_TYPE_INVALID");
    }

    static void validateVideoTracks(List<VideoTrackFacts> tracks, int declaredTrackCount, long maxPixels) throws java.io.IOException {
        if (tracks == null || declaredTrackCount < 1 || declaredTrackCount > MAX_VIDEO_TRACKS || tracks.size() != declaredTrackCount) {
            throw new java.io.IOException("VIDEO_TRACK_COUNT");
        }
        boolean hasVideo = false;
        for (VideoTrackFacts track : tracks) {
            checkInterrupted();
            if (!track.video) continue;
            hasVideo = true;
            if (track.durationUs <= 0 || track.width <= 0 || track.height <= 0
                    || (long) track.width * (long) track.height > maxPixels) {
                throw new java.io.IOException("VIDEO_TRACK_FACTS_INVALID");
            }
        }
        if (!hasVideo) throw new java.io.IOException("VIDEO_TRACK_FACTS_INVALID");
    }

    static int[] scaledFrameSize(int width, int height) throws java.io.IOException {
        if (width <= 0 || height <= 0) throw new java.io.IOException("VIDEO_TRACK_FACTS_INVALID");
        double scale = Math.min(1d, (double) MAX_DECODED_FRAME_EDGE / Math.max(width, height));
        int outWidth = Math.max(1, (int) Math.floor(width * scale));
        int outHeight = Math.max(1, (int) Math.floor(height * scale));
        if (outWidth > MAX_DECODED_FRAME_EDGE || outHeight > MAX_DECODED_FRAME_EDGE
                || (long) outWidth * outHeight > (long) MAX_DECODED_FRAME_EDGE * MAX_DECODED_FRAME_EDGE) {
            throw new java.io.IOException("VIDEO_DECODED_FRAME_BUDGET");
        }
        return new int[] { outWidth, outHeight };
    }

    static void requireDecodedFrame(int width, int height) throws java.io.IOException {
        if (width <= 0 || height <= 0 || width > MAX_DECODED_FRAME_EDGE || height > MAX_DECODED_FRAME_EDGE
                || (long) width * height > (long) MAX_DECODED_FRAME_EDGE * MAX_DECODED_FRAME_EDGE) {
            throw new java.io.IOException("VIDEO_DECODED_FRAME_BUDGET");
        }
    }

    /** Cooperative only: synchronous native codec calls cannot be interrupted by this check. */
    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("MEDIA_VALIDATION_INTERRUPTED");
    }
}
