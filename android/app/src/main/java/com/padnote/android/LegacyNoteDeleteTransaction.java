package com.padnote.android;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Coordinates the durable logical commit and bounded, fail-closed legacy note cleanup. */
final class LegacyNoteDeleteTransaction {
    enum CleanupStage { INDEX_WRITE, CANONICAL_NOTE_UNLINK }
    interface CleanupFaultInjector { void before(CleanupStage stage) throws Exception; }

    private LegacyNoteDeleteTransaction() { }

    static NoteStore.DeleteResult delete(Context context, String noteId) throws Exception {
        try (LegacyGroupMutationLock.Lease ignored = LegacyGroupMutationLock.acquire()) {
            synchronized (NoteStore.class) { return deleteLocked(context,noteId); }
        }
    }

    static NoteStore.DeleteResult deleteLocked(Context context, String noteId) throws Exception {
        return deleteForTestLocked(context,noteId,null,null);
    }

    static NoteStore.DeleteResult deleteForTest(Context context, String noteId,
            LegacyDeleteJournal.FaultInjector journalFaults,
            CleanupFaultInjector cleanupFaults) throws Exception {
        try (LegacyGroupMutationLock.Lease ignored = LegacyGroupMutationLock.acquire()) {
            synchronized (NoteStore.class) {
                return deleteForTestLocked(context,noteId,journalFaults,cleanupFaults);
            }
        }
    }

    private static NoteStore.DeleteResult deleteForTestLocked(Context context, String noteId,
            LegacyDeleteJournal.FaultInjector journalFaults,
            CleanupFaultInjector cleanupFaults) throws Exception {
        if (context == null) throw new IllegalArgumentException("context required");
        NoteStore.validateNoteId(noteId);
        LegacyDeleteJournal.FileIdentityReader identities=LegacyDeleteJournal.androidIdentityReader();
        LegacyDeleteJournal.Record record=LegacyDeleteJournal.readCommitted(context.getFilesDir(),
                noteId,identities,LegacyDeleteJournal.androidDirectorySync());
        if(record==null){
            LegacyDeleteJournal.CommitResult commit=NoteStore.commitLegacyDeleteMarker(context,
                    noteId,journalFaults);
            if(commit.status==LegacyDeleteJournal.CommitStatus.NOT_COMMITTED)
                throw new IOException(commit.errorCode==null?"LEGACY_DELETE_NOT_COMMITTED":commit.errorCode);
            if(commit.status==LegacyDeleteJournal.CommitStatus.UNCERTAIN)
                return new NoteStore.DeleteResult(false,NoteStore.DeleteCommitStatus.UNCERTAIN,
                        true,commit.errorCode==null?"LEGACY_DELETE_COMMIT_UNCERTAIN":commit.errorCode);
            record=commit.record;
        }
        return cleanupCommitted(context,record,cleanupFaults);
    }

    static NoteStore.DeleteResult retryCommittedCleanup(Context context,String noteId)
            throws Exception {
        try (LegacyGroupMutationLock.Lease ignored = LegacyGroupMutationLock.acquire()) {
            synchronized (NoteStore.class) {
                LegacyDeleteJournal.Record record=LegacyDeleteJournal.readCommitted(
                        context.getFilesDir(),noteId,LegacyDeleteJournal.androidIdentityReader(),
                        LegacyDeleteJournal.androidDirectorySync());
                if(record==null)throw new IOException("DELETE_MARKER_NOT_COMMITTED");
                return cleanupCommitted(context,record,null);
            }
        }
    }

    private static NoteStore.DeleteResult cleanupCommitted(Context context,
            LegacyDeleteJournal.Record record,CleanupFaultInjector faults) {
        List<String> pending=new ArrayList<>();
        try {
            if(faults!=null)faults.before(CleanupStage.INDEX_WRITE);
            NoteStore.persistCommittedLegacyDeleteInIndex(context,record.noteId);
        } catch(Exception error) { pending.add(code(error,"DELETE_INDEX_CLEANUP_PENDING")); }

        try {
            if(faults!=null)faults.before(CleanupStage.CANONICAL_NOTE_UNLINK);
            if(!NoteStore.deleteCapturedCanonicalNote(context,record))
                pending.add("DELETE_CANONICAL_NOTE_CHANGED_RETAINED");
        } catch(Exception error) { pending.add(code(error,"DELETE_CANONICAL_NOTE_CLEANUP_PENDING")); }

        if(hasRetainedNoteFamily(context,record.noteId)) pending.add("DELETE_NOTE_SIDECARS_RETAINED");
        if(hasRetainedPrivateMaterial(context,record.noteId)) pending.add("DELETE_PRIVATE_MATERIAL_RETAINED");

        String message=pending.isEmpty()?null:join(pending);
        return new NoteStore.DeleteResult(false,NoteStore.DeleteCommitStatus.COMMITTED,
                !pending.isEmpty(),message);
    }

    private static boolean hasRetainedNoteFamily(Context context,String noteId) {
        try {
            File notes=new File(context.getFilesDir(),NoteStore.notesDirectoryName());
            File[] files=notes.listFiles();
            if(files==null)return true;
            String notePrefix=noteId+".json";
            String pdfPrefix=noteId+".pdf";
            String coverPrefix=noteId+".cover.png";
            String unsavedPrefix=noteId+".unsaved.json";
            for(File file:files) {
                String name=file.getName();
                if(name.startsWith(notePrefix+".") || name.startsWith(unsavedPrefix)
                        || name.startsWith(pdfPrefix) || name.startsWith(coverPrefix)) return true;
            }
            return false;
        } catch(Exception unavailable){return true;}
    }

    private static boolean hasRetainedPrivateMaterial(Context context,String noteId) {
        try {
            File notes=new File(context.getFilesDir(),NoteStore.notesDirectoryName());
            String coverPrefix=noteId+".cover.png";
            File[] noteFiles=notes.listFiles();
            if(noteFiles==null)return true;
            for(File file:noteFiles)if(file.getName().equals(coverPrefix)
                    ||file.getName().startsWith(coverPrefix+"."))return true;
            if(new VideoAttachmentStore(context).countForNote(noteId)>0)return true;
            ManualTextDraftStore.LoadResult draft=new ManualTextDraftStore(context).load(noteId);
            if(draft.status!=ManualTextDraftStore.LoadStatus.NONE)return true;
            File ai=new File(context.getFilesDir(),"ai-conversations");
            String prefix=noteId+".json";
            String clearedPrefix=noteId+".cleared.json";
            File[] files=ai.listFiles();
            if(files==null)return ai.exists();
            for(File file:files)if(file.getName().startsWith(prefix)
                    ||file.getName().equals(clearedPrefix)
                    ||file.getName().startsWith(clearedPrefix+"."))return true;
            return false;
        } catch(Exception unavailable){return true;}
    }

    private static String join(List<String> values) {
        StringBuilder out=new StringBuilder();
        for(String value:values){if(out.length()>0)out.append(',');out.append(value);}
        return out.toString();
    }

    private static String code(Exception error,String fallback) {
        String value=error.getMessage();
        return value!=null&&value.matches("[A-Z0-9_]{1,80}")?value:fallback;
    }
}
