package com.padnote.android.streaming;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import java.io.IOException;
import java.io.File;
import java.nio.file.Path;

/** Streaming identity needs API 27 nanosecond stat data; older installs keep the existing path. */
@TargetApi(27)
public final class StreamingFeatureGate {
    private StreamingFeatureGate() {}
    public static boolean isSupported(){return Build.VERSION.SDK_INT>=27;}
    public static void requireSupported(){if(!isSupported())throw new UnsupportedOperationException("STREAMING_STORE_REQUIRES_ANDROID_API_27_NANOSECOND_STAT");}
    public static StreamingGroupStore.Store openStore(Context context,File storeDirectory,StreamingGroupStore.ProjectionVerifier verifier)throws IOException {
        return openStore(context,storeDirectory,verifier,null);
    }
    public static StreamingGroupStore.Store openStore(Context context,File storeDirectory,StreamingGroupStore.ProjectionVerifier verifier,StreamingGroupStore.CompleteGroupVerifier completeVerifier)throws IOException {
        return openStore(context,storeDirectory,verifier,completeVerifier,null);
    }
    /** Observer overload is used by isolated recovery tests to model interruption after durable approval. */
    public static StreamingGroupStore.Store openStore(Context context,File storeDirectory,StreamingGroupStore.ProjectionVerifier verifier,StreamingGroupStore.CompleteGroupVerifier completeVerifier,StreamingGroupStore.CommitObserver observer)throws IOException {
        return openStore(context,storeDirectory,verifier,completeVerifier,observer,null);
    }
    /** Read diagnostics are supplied only by a debuggable catalog list operation. */
    public static StreamingGroupStore.Store openStore(Context context,File storeDirectory,StreamingGroupStore.ProjectionVerifier verifier,StreamingGroupStore.CompleteGroupVerifier completeVerifier,StreamingGroupStore.CommitObserver observer,StreamingGroupStore.ReadDiagnostics readDiagnostics)throws IOException {
        requireSupported();
        if(context==null||storeDirectory==null||verifier==null)throw new IllegalArgumentException("STORE_ARGUMENT_REQUIRED");
        TrustedFilesRoot filesRoot=TrustedFilesRoot.fromApplicationContext(context);
        Path files=filesRoot.path();
        Path requestedRoot=storeDirectory.toPath().toAbsolutePath().normalize();
        Path root=filesRoot.requireInScope(requestedRoot);
        if(root.equals(files))throw new IOException("STORE_OUTSIDE_PRIVATE_FILES");
        filesRoot.verify();
        return new StreamingGroupStore.Store(root,new AndroidIdentityOps(filesRoot,root),verifier,completeVerifier,observer,readDiagnostics);
    }
}
