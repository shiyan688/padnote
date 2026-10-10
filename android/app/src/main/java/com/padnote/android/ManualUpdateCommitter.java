package com.padnote.android;

import com.padnote.android.streaming.StreamingGroupStore;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Commits one explicitly selected full-group update against the exact local target
 * revision shown in preview. The caller supplies already validated source profile,
 * one-to-one material mapping, and bounded staged inputs through the sealed plan.
 *
 * This class is a storage boundary only. It does not make legacy v1 archives updateable
 * and must not be reported as app integration until archive import and app callers use it.
 */
@android.annotation.TargetApi(27)
public final class ManualUpdateCommitter {
    private ManualUpdateCommitter() { }

    public interface Preview {
        /** Return true only after presenting every member of the incoming group. */
        boolean approve(StreamingGroupStore.Snapshot current,
                        StreamingGroupStore.VerifiedGroup incoming) throws Exception;
    }

    public static StreamingGroupStore.Snapshot commit(
            StreamingGroupStore.Store store,
            NativeManualUpdatePlan plan,
            StreamingGroupStore.ContentInput pdf,
            StreamingGroupStore.ContentInput cover,
            List<StreamingGroupStore.MemberInput> videos,
            List<StreamingGroupStore.MemberInput> vault,
            Preview preview) throws Exception {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(videos, "videos must be present even when empty");
        Objects.requireNonNull(vault, "vault must be present even when empty");
        Objects.requireNonNull(preview, "explicit preview required");
        if (plan.isLegacyAdoption()) throw new IOException("MANUAL_UPDATE_PLAN_REQUIRED");
        try(LegacyGroupMutationLock.Lease publicationGate=LegacyGroupMutationLock.acquire()) {
            StreamingGroupStore.Snapshot base = store.read(plan.targetLineage());
            requireSelectedBase(base, plan);
            StreamingGroupStore.Envelope envelope = plan.envelope(
                    UUID.randomUUID().toString(), pdf, cover, videos, vault);
            return store.commitManualUpdateV2(envelope, (current, incoming) -> {
                // The shared legacy-writer gate prevents an ordinary async save
                // from publishing a sibling while preview and the marker CAS run.
                requireSelectedBase(current, plan);
                return preview.approve(current, incoming);
            }, plan);
        }
    }

    private static void requireSelectedBase(StreamingGroupStore.Snapshot current,
                                           NativeManualUpdatePlan plan) throws IOException {
        if (current == null || !current.localId.equals(plan.destinationLocalId())
                || !current.lineage.equals(plan.targetLineage())
                || !current.revision.equals(plan.targetBaseRevision())
                || !current.digest.equals(plan.targetBaseDigest())) {
            throw new IOException("BASE_CAS_CONFLICT");
        }
    }
}
