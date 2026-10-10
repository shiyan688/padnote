package com.padnote.android.streaming;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.padnote.android.NativeManualUpdatePlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Source for root-owned API 27+ verification; this candidate does not run a device. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class ManualUpdateV2InstrumentedTest {
    private static final String BODY = "{\"schemaVersion\":8,\"id\":\"source-note\",\"title\":\"synthetic\",\"updatedAt\":16,\"pageWidth\":768.0,\"pageHeight\":1086.0,\"canvasWidth\":768.0,\"canvasHeight\":1086.0,\"pageGap\":24.0,\"pageCount\":1,\"pdfPageCount\":0,\"pageTopologyRevision\":0,\"viewportZoom\":1.0,\"viewportCenterX\":384.0,\"viewportCenterY\":543.0,\"strokes\":[],\"textFlows\":[],\"images\":[],\"pageStyle\":{\"paper\":\"ruled\",\"ratio\":\"screen\",\"landscape\":false},\"textBoxes\":[]}";

    @Test public void nativeStoreRequiresPlanAndGatesCommitColdReadAndHistory() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Path owned = TrustedFilesRoot.fromApplicationContext(context).path().resolve("manual-update-v2-" + UUID.randomUUID());
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
            String targetId = "note-" + UUID.randomUUID().toString().replace("-", ""), targetLineage = UUID.randomUUID().toString();
            String oldRevision = UUID.randomUUID().toString();
            StreamingGroupStore.ImportIdentity oldIdentity = new StreamingGroupStore.ImportIdentity(
                    targetLineage, "original-source-note", targetId, targetLineage, null, null);
            StreamingGroupStore.Envelope seed = new StreamingGroupStore.Envelope(oldIdentity, oldRevision,
                    null, null, null, StreamingGroupStore.Action.FIRST_IMPORT, BODY.replace("source-note", targetId)
                    .getBytes(StandardCharsets.UTF_8), null, null, Collections.emptyList(), Collections.emptyList());
            StreamingGroupStore.Snapshot original = store.commit(seed, (current, incoming) -> current == null);

            NativeManualUpdatePlan.SelectedTarget selected = new NativeManualUpdatePlan.SelectedTarget(
                    original.localId, original.lineage, original.revision, original.digest);
            NativeManualUpdatePlan.Provenance source = new NativeManualUpdatePlan.Provenance(
                    NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID, UUID.randomUUID().toString(),
                    "source-note", "source-r1", null);
            NativeManualUpdatePlan plan = NativeManualUpdatePlan.createManualUpdate(selected, source,
                    BODY.getBytes(StandardCharsets.UTF_8), NativeManualUpdatePlan.Presence.ABSENT, null,
                    NativeManualUpdatePlan.Presence.ABSENT, null, Collections.emptyList(), Collections.emptyList(),
                    Collections.singletonMap("source-note", targetId));
            String newRevision = UUID.randomUUID().toString();
            StreamingGroupStore.Envelope update = plan.envelope(newRevision, null, null,
                    Collections.emptyList(), Collections.emptyList());

            try {
                store.commit(update, (current, incoming) -> true);
                fail("direct commit bypassed the sealed manual-update plan");
            } catch (IOException expected) {
                assertEquals("MANUAL_UPDATE_V2_PLAN_REQUIRED", expected.getMessage());
            }
            assertEquals(original.digest, store.read(targetLineage).digest);

            StreamingGroupStore.Snapshot committed = store.commitManualUpdateV2(update,
                    (current, incoming) -> current != null && current.digest.equals(original.digest), plan);
            assertEquals(targetId, committed.localId);
            assertEquals(newRevision, store.read(targetLineage).revision);
            assertEquals(targetId, store.readRevision(targetLineage, newRevision, committed.digest).localId);
            assertEquals(targetId, store.readRevision(targetLineage, oldRevision, original.digest).localId);
            assertEquals(StreamingGroupStore.Phase.COMMITTED, store.recover(committed.transactionID));
            assertArrayEquals(BODY.replace("source-note", targetId).getBytes(StandardCharsets.UTF_8),
                    store.read(targetLineage).readSmall("body.bin", 4096));

            NativeManualUpdatePlan.SelectedTarget secondBase = new NativeManualUpdatePlan.SelectedTarget(
                    targetId, targetLineage, committed.revision, committed.digest);
            NativeManualUpdatePlan.Provenance secondSource = new NativeManualUpdatePlan.Provenance(
                    NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID, UUID.randomUUID().toString(),
                    "source-note", "source-r2", null);
            NativeManualUpdatePlan secondPlan = NativeManualUpdatePlan.createManualUpdate(secondBase, secondSource,
                    BODY.getBytes(StandardCharsets.UTF_8), NativeManualUpdatePlan.Presence.ABSENT, null,
                    NativeManualUpdatePlan.Presence.ABSENT, null, Collections.emptyList(), Collections.emptyList(),
                    Collections.singletonMap("source-note", targetId));
            String secondRevision = UUID.randomUUID().toString();
            StreamingGroupStore.Snapshot newer = store.commitManualUpdateV2(
                    secondPlan.envelope(secondRevision, null, null, Collections.emptyList(), Collections.emptyList()),
                    (current, incoming) -> current != null && current.digest.equals(committed.digest), secondPlan);
            NativeManualUpdatePlan.SelectedTarget thirdBase = new NativeManualUpdatePlan.SelectedTarget(
                    targetId,targetLineage,newer.revision,newer.digest);
            NativeManualUpdatePlan.Provenance thirdSource = new NativeManualUpdatePlan.Provenance(
                    NativeManualUpdatePlan.SourceKind.NATIVE_ANDROID,UUID.randomUUID().toString(),
                    "source-note","source-r3",null);
            NativeManualUpdatePlan thirdPlan = NativeManualUpdatePlan.createManualUpdate(thirdBase,thirdSource,
                    BODY.getBytes(StandardCharsets.UTF_8),NativeManualUpdatePlan.Presence.ABSENT,null,
                    NativeManualUpdatePlan.Presence.ABSENT,null,Collections.emptyList(),Collections.emptyList(),
                    Collections.singletonMap("source-note",targetId));
            String thirdRevision=UUID.randomUUID().toString();
            java.util.Set<String> transactionsBeforeRestore=new java.util.HashSet<>(store.transactionIDs());
            try {
                store.restore(targetLineage,newRevision,committed.digest,newer.revision,newer.digest,(current,incoming)->{
                    assertEquals(newer.revision,current.revision);
                    store.commitManualUpdateV2(thirdPlan.envelope(thirdRevision,null,null,Collections.emptyList(),Collections.emptyList()),
                            (base,incomingWinner)->base!=null&&base.digest.equals(newer.digest),thirdPlan);
                    return true;
                });
                fail("restore preview must not overwrite a concurrent winner");
            } catch(IOException expected) { assertEquals("BASE_CAS_CONFLICT",expected.getMessage()); }
            java.util.Set<String> restoreTransactions=new java.util.HashSet<>(store.transactionIDs());
            restoreTransactions.removeAll(transactionsBeforeRestore);
            assertEquals("winner and rejected restore each retain a recovery journal",2,restoreTransactions.size());
            int committedRecoveries=0,conflictRecoveries=0;
            for(String transaction:restoreTransactions){StreamingGroupStore.Phase phase=store.recover(transaction);
                if(phase==StreamingGroupStore.Phase.COMMITTED)committedRecoveries++;if(phase==StreamingGroupStore.Phase.CONFLICT)conflictRecoveries++;}
            assertEquals(1,committedRecoveries);assertEquals(1,conflictRecoveries);
            StreamingGroupStore.Snapshot concurrentWinner=store.read(targetLineage);
            assertEquals(thirdRevision,concurrentWinner.revision);
            StreamingGroupStore.Snapshot restored = store.restore(targetLineage, newRevision, committed.digest,
                    concurrentWinner.revision, concurrentWinner.digest, (current, incoming) -> current != null && current.digest.equals(concurrentWinner.digest));
            assertNotEquals("history restore must allocate a fresh CAS revision", newRevision, restored.revision);
            assertEquals(restored.revision, store.read(targetLineage).revision);
            assertEquals("selected historical revision remains immutable and readable", targetId, store.readRevision(targetLineage,newRevision,committed.digest).localId);
            try { store.restore(targetLineage,newRevision,committed.digest,newRevision,committed.digest,(current,incoming)->true); fail("stale pre-restore CAS must not become current again"); } catch(IOException expected) { assertEquals("BASE_CAS_CONFLICT",expected.getMessage()); }
            byte[] originalProvenance=store.readRevision(targetLineage,newRevision,committed.digest).readSmall("source-provenance.bin",1<<20);
            StreamingGroupStore.Envelope recursiveRestore=new StreamingGroupStore.Envelope(restored.identity,
                    UUID.randomUUID().toString(),restored.revision,restored.revision,restored.digest,
                    StreamingGroupStore.Action.HISTORY_RESTORE_V1,restored.readSmall("body.bin",4096),null,null,
                    Collections.emptyList(),Collections.emptyList(),originalProvenance,restored.revision,restored.digest);
            try { store.commit(recursiveRestore,(current,incoming)->true);fail("restore manifests cannot form a recursive source chain"); }
            catch(IOException expected) { assertEquals("HISTORY_RESTORE_SOURCE_NOT_CANONICAL",expected.getMessage()); }
            StreamingGroupStore.Snapshot restoredAgain=store.restore(targetLineage,restored.revision,restored.digest,
                    restored.revision,restored.digest,(current,incoming)->true);
            assertNotEquals("restoring an already restored revision still creates a fresh token",restored.revision,restoredAgain.revision);
            assertArrayEquals("repeated restore retains the exact original provenance bytes",originalProvenance,restoredAgain.readSmall("source-provenance.bin",1<<20));
            assertEquals("canonical source history remains readable",newRevision,store.readRevision(targetLineage,newRevision,committed.digest).revision);
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(owned)) {
                Path[] all = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path path : all) Files.deleteIfExists(path);
            }
            assertEquals("owned fixture root removed", StreamingGroupStore.IdentityOps.NoFollowState.MISSING,
                    new AndroidIdentityOps(TrustedFilesRoot.fromApplicationContext(context), owned.resolve("cleanup-check"))
                            .noFollowState(owned));
        }
    }
}
