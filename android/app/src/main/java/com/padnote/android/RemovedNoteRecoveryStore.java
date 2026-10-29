package com.padnote.android;

import android.content.Context;
import android.os.Build;

import com.padnote.android.streaming.StreamingGroupStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only, per-invocation inventory of fully verified managed groups removed from the shelf. */
final class RemovedNoteRecoveryStore {
    static final class Entry {
        final String noteId;
        final String title;
        final long updatedAt;
        final int pageCount;
        final int strokeCount;

        Entry(StreamingGroupStore.Store.ShelfMetadata metadata, String fallbackTitle) {
            noteId = metadata.localId;
            title = metadata.title == null || metadata.title.trim().isEmpty()
                    ? fallbackTitle : metadata.title;
            updatedAt = metadata.updatedAt;
            pageCount = metadata.pageCount;
            strokeCount = metadata.strokeCount;
        }
    }

    static final class Inventory {
        final List<Entry> entries;
        final int unavailableCount;

        Inventory(List<Entry> entries, int unavailableCount) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.unavailableCount = unavailableCount;
        }
    }

    private RemovedNoteRecoveryStore() {}

    static Inventory list(Context context) throws Exception {
        if (context == null) throw new IllegalArgumentException("CONTEXT_REQUIRED");
        if (Build.VERSION.SDK_INT < 27) return new Inventory(Collections.emptyList(), 0);

        List<String> noteIds = NoteStore.indexedNoteIds(context);
        Set<String> requested = new HashSet<>(noteIds);
        GroupAuthorityBridge.CatalogReader catalog = GroupAuthorityBridge.catalogReader(context);
        catalog.prepareVisibleCatalog(requested);

        List<Entry> retired = new ArrayList<>();
        int unavailable = 0;
        for (String noteId : noteIds) {
            final StreamingGroupStore.Store.ShelfMetadata metadata;
            try {
                metadata = catalog.shelfMetadataIfManaged(noteId);
            } catch (Exception invalidGroup) {
                // Keep the rest of the verified list usable, but disclose that a row was omitted.
                unavailable++;
                continue;
            }
            if (metadata != null && metadata.retired) {
                retired.add(new Entry(metadata, context.getString(R.string.core_removed_notes_fallback_title)));
            }
        }
        retired.sort(Comparator.comparingLong((Entry item) -> item.updatedAt).reversed()
                .thenComparing(item -> item.title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(item -> item.noteId));
        return new Inventory(retired, unavailable);
    }
}

