package com.padnote.android;

import android.system.ErrnoException;
import android.system.Os;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Java streams around Os.open descriptors, with explicit and idempotent descriptor ownership. */
public final class OwnedFdStreams {
    private OwnedFdStreams() {}

    public static FileInputStream input(FileDescriptor descriptor) throws IOException {
        if (descriptor == null) throw new IOException("OWNED_FD_REQUIRED");
        try { return new OwnedInput(descriptor); }
        catch (RuntimeException | Error failure) {
            closeAfterConstructionFailure(descriptor, failure);
            throw failure;
        }
    }

    public static FileOutputStream output(FileDescriptor descriptor) throws IOException {
        if (descriptor == null) throw new IOException("OWNED_FD_REQUIRED");
        try { return new OwnedOutput(descriptor); }
        catch (RuntimeException | Error failure) {
            closeAfterConstructionFailure(descriptor, failure);
            throw failure;
        }
    }

    private static void closeAfterConstructionFailure(FileDescriptor descriptor, Throwable failure) {
        try { Os.close(descriptor); }
        catch (ErrnoException closeFailure) { failure.addSuppressed(new IOException("OWNED_FD_CLOSE_FAILED", closeFailure)); }
    }

    private static IOException close(FileDescriptor descriptor, IOException failure) {
        try { Os.close(descriptor); }
        catch (ErrnoException closeFailure) {
            IOException wrapped = new IOException("OWNED_FD_CLOSE_FAILED", closeFailure);
            if (failure == null) failure = wrapped; else failure.addSuppressed(wrapped);
        }
        return failure;
    }

    private static final class OwnedInput extends FileInputStream {
        private final FileDescriptor descriptor;
        private boolean closed;
        OwnedInput(FileDescriptor descriptor) { super(descriptor); this.descriptor = descriptor; }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try { super.close(); } catch (IOException error) { failure = error; }
            failure = OwnedFdStreams.close(descriptor, failure);
            if (failure != null) throw failure;
        }
    }

    private static final class OwnedOutput extends FileOutputStream {
        private final FileDescriptor descriptor;
        private boolean closed;
        OwnedOutput(FileDescriptor descriptor) { super(descriptor); this.descriptor = descriptor; }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try { super.close(); } catch (IOException error) { failure = error; }
            failure = OwnedFdStreams.close(descriptor, failure);
            if (failure != null) throw failure;
        }
    }
}
