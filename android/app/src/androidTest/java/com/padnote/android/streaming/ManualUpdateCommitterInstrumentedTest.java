package com.padnote.android.streaming;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.padnote.android.ManualUpdateCommitter;
import com.padnote.android.NativeManualUpdatePlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** A competing target edit during preview must win; the staged update cannot replace it. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class ManualUpdateCommitterInstrumentedTest {
    private static final String BODY = "{\"schemaVersion\":8,\"id\":\"source-note\",\"title\":\"synthetic\",\"updatedAt\":16,\"pageWidth\":768.0,\"pageHeight\":1086.0,\"canvasWidth\":768.0,\"canvasHeight\":1086.0,\"pageGap\":24.0,\"pageCount\":1,\"pdfPageCount\":0,\"pageTopologyRevision\":0,\"viewportZoom\":1.0,\"viewportCenterX\":384.0,\"viewportCenterY\":543.0,\"strokes\":[],\"textFlows\":[],\"images\":[],\"pageStyle\":{\"paper\":\"ruled\",\"ratio\":\"screen\",\"landscape\":false},\"textBoxes\":[]}";

    @Test public void targetEditDuringCompletePreviewWinsAndOldRevisionRemainsReadable() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Path owned = TrustedFilesRoot.fromApplicationContext(context).path().resolve("manual-update-cas-" + UUID.randomUUID());
        Files.createDirectory(owned);
        try {
            Path root = owned.resolve("store");
            AndroidIdentityOps ops = new AndroidIdentityOps(TrustedFilesRoot.fromApplicationContext(context), root);
            StreamingGroupStore.ProjectionVerifier projection = new StreamingGroupStore.ProjectionVerifier() {
                @Override public void verifyBody(byte[] body) throws IOException {
                    if (body == null || body.length == 0) throw new IOException("BODY_EMPTY");
                }
                @Override public void verifyMetadata(String role, String materialId, byte[] metadata) throws IOException {
                    if (role == null || materialId == null || metadata == null) throw new IOException("METADATA_INVALID");
                }
            };
            StreamingGroupStore.Store store = new StreamingGroupStore.Store(root, ops, projection,
                    NativeManualUpdatePlan.durableVerifier(), phase -> {});
            String targetId = "note-" + UUID.randomUUID().toString().replace("-", "");
            String lineage = UUID.randomUUID().toString();
            String oldRevision = UUID.randomUUID().toString();
            StreamingGroupStore.Snapshot old = store.commit(new StreamingGroupStore.Envelope(
                    new StreamingGroupStore.ImportIdentity(lineage, "origin-note", targetId, lineage, null, null),
                    oldRevision, null, null, null, StreamingGroupStore.Action.FIRST_IMPORT,
                    BODY.replace("source-note", targetId).getBytes(StandardCharsets.UTF_8), null, null,
                    Collections.emptyList(), Collections.emptyList()), (current, incoming) -> current == null);

            NativeManualUpdatePlan stale = plan(old, targetId, "source-r2", "package preview");
            final StreamingGroupStore.Snapshot[] concurrent = {null};
            try {
                ManualUpdateCommitter.commit(store, stale, null, null,
                        Collections.emptyList(), Collections.emptyList(), (previewBase, incoming) -> {
                            assertEquals("preview is bound to selected local version", old.digest, previewBase.digest);
                            NativeManualUpdatePlan competing = plan(previewBase, targetId, "source-r3", "handwritten edit");
                            concurrent[0] = ManualUpdateCommitter.commit(store, competing, null, null,
                                    Collections.emptyList(), Collections.emptyList(), (base, staged) -> true);
                            return true;
                        });
                fail("publication must reject a target changed while preview was open");
            } catch (IOException conflict) {
                assertEquals("BASE_CAS_CONFLICT", conflict.getMessage());
            }

            assertNotNull(concurrent[0]);
            StreamingGroupStore.Snapshot latest = store.read(lineage);
            assertEquals("concurrent handwritten edit remains current", concurrent[0].digest, latest.digest);
            assertEquals("concurrent revision remains current", concurrent[0].revision, latest.revision);
            assertEquals("the selected pre-update full revision remains available", targetId,
                    store.readRevision(lineage, old.revision, old.digest).localId);
            assertEquals("the concurrent target version remains available", targetId,
                    store.readRevision(lineage, concurrent[0].revision, concurrent[0].digest).localId);
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(owned)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))
                    Files.deleteIfExists(path);
            }
        }
    }

    private static NativeManualUpdatePlan plan(StreamingGroupStore.Snapshot base,
                                                String targetId, String sourceRevision,
                                                String title) throws Exception {
        NativeManualUpdatePlan.SelectedTarget selected = new NativeManualUpdatePlan.SelectedTarget(
                targetId, base.lineage, base.revision, base.digest);
        NativeManualUpdatePlan.Provenance source = new NativeManualUpdatePlan.Provenance(
                NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID, UUID.randomUUID().toString(),
                "source-note", sourceRevision, null);
        String body = BODY.replace("source-note", "source-note").replace("synthetic", title);
        return NativeManualUpdatePlan.createManualUpdate(selected, source,
                body.getBytes(StandardCharsets.UTF_8), NativeManualUpdatePlan.Presence.ABSENT, null,
                NativeManualUpdatePlan.Presence.ABSENT, null, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonMap("source-note", targetId));
    }
}
