package com.padnote.android.streaming;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Atomic no-replace syscall bridge; streaming remains gated at API 27 and fails closed if unavailable. */
final class NativeNoReplace {
    private static volatile boolean loaded;
    private NativeNoReplace() {}

    static int renameNoReplace(Path sourceParent, String sourceName, Path destinationParent,
            String destinationName) throws IOException {
        ensureLoaded();
        byte[][] args = {utf8(sourceParent.toString()), asciiName(sourceName),
                utf8(destinationParent.toString()), asciiName(destinationName)};
        return renameNoReplaceNative(args[0], args[1], args[2], args[3]);
    }

    private static byte[] utf8(String value) throws IOException {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new IOException("NOREPLACE_PATH_ENCODING_INVALID");
        }
        final byte[] bytes;
        try {
            ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            bytes=new byte[encoded.remaining()];encoded.get(bytes);
        } catch (CharacterCodingException invalid) {
            throw new IOException("NOREPLACE_PATH_ENCODING_INVALID", invalid);
        }
        if (bytes.length > 4096) throw new IOException("NOREPLACE_PATH_LIMIT");
        return bytes;
    }

    private static byte[] asciiName(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,255}")
                || value.equals(".") || value.equals("..")) {
            throw new IOException("NOREPLACE_BASENAME_INVALID");
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void ensureLoaded() throws IOException {
        if (loaded) return;
        synchronized (NativeNoReplace.class) {
            if (loaded) return;
            try {
                System.loadLibrary("padnote_noreplace");
                loaded = true;
            } catch (LinkageError error) {
                throw new IOException("NOREPLACE_NATIVE_CAPABILITY_UNAVAILABLE", error);
            }
        }
    }

    /** Returns zero or a positive Linux errno; unsupported syscall never falls back. */
    private static native int renameNoReplaceNative(byte[] sourceParent, byte[] sourceName,
            byte[] destinationParent, byte[] destinationName);
}
