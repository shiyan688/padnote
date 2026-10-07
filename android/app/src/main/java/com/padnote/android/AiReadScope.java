package com.padnote.android;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Immutable read authority bound to one selection session and model profile. */
final class AiReadScope {
    private final String noteId;
    private final int selectionSerial;
    private final String profileId;
    private final AiVaultSnapshot vaultSnapshot;

    private AiReadScope(String noteId, int selectionSerial, String profileId,
                        AiVaultSnapshot vaultSnapshot) {
        this.noteId = noteId == null ? "" : noteId;
        this.selectionSerial = selectionSerial;
        this.profileId = profileId == null ? "" : profileId;
        this.vaultSnapshot = vaultSnapshot;
    }

    static AiReadScope selectionOnly(String noteId, int selectionSerial, String profileId) {
        return new AiReadScope(noteId, selectionSerial, profileId, null);
    }

    AiReadScope withVault(AiVaultSnapshot snapshot) {
        return new AiReadScope(noteId, selectionSerial, profileId,
                snapshot == null || snapshot.isEmpty() ? null : snapshot);
    }

    AiReadScope withProfile(String newProfileId) {
        return new AiReadScope(noteId, selectionSerial, newProfileId, vaultSnapshot);
    }

    NoteToolRegistry createToolRegistry() {
        return vaultSnapshot == null
                ? NoteTools.createDefault() : NoteTools.createDefault(vaultSnapshot);
    }

    boolean hasVaultNotes() {
        return vaultSnapshot != null && !vaultSnapshot.isEmpty();
    }

    int vaultNoteCount() {
        return vaultSnapshot == null ? 0 : vaultSnapshot.list().size();
    }

    Set<String> vaultNoteIds() {
        return vaultSnapshot == null ? Collections.emptySet() : vaultSnapshot.noteIds();
    }

    List<String> vaultTitles() {
        return vaultSnapshot == null ? Collections.emptyList() : vaultSnapshot.titles();
    }

    AiVaultSnapshot vaultSnapshot() {
        return vaultSnapshot;
    }

    String identity() {
        return noteId + ":" + selectionSerial + ":" + profileId + ":"
                + (vaultSnapshot == null ? "selection-only" : vaultSnapshot.digest());
    }
}
