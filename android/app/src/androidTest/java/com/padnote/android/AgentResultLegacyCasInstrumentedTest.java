package com.padnote.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

/** Legacy note CAS regression isolated under a per-test temporary app-data root. */
@RunWith(AndroidJUnit4.class)
public final class AgentResultLegacyCasInstrumentedTest {
    @Test public void appendPersistsSchemaNineAndRejectsAnAlreadyOpenStaleEditor() throws Exception {
        try (Fixture fixture = Fixture.create()) {
            NoteStore.Entry entry = NoteStore.create(fixture.context, "Agent result CAS fixture");
            JSONObject original = NoteStore.load(fixture.context, entry.id);
            String originalSha = NoteStore.legacyBodySha256(fixture.context, entry.id);
            String output = "Result from an isolated completed task";
            AgentTaskStore.Task task = new AgentTaskStore.Task(UUID.randomUUID().toString(), "fixture-connection",
                    1L, "fixture-origin", AgentConnectionStore.Kind.HERMES,
                    AgentConnectionStore.Transport.BRIDGE, "fixture-credential", "fixture-bridge",
                    "fixture-instance", "Fixture task", "Fixture", entry.id, original.optLong("updatedAt"),
                    "{}", AgentResultProvenance.sha256("{}".getBytes(StandardCharsets.UTF_8)), "fixture-remote",
                    AgentTaskStore.Status.COMPLETED, output, "", "", "", "", Collections.emptyList(), 1L, 2L);
            AgentResultProvenance provenance = AgentResultProvenance.create(task, entry.id,
                    task.noteRevision, output);
            JSONObject flow = new JSONObject().put("id", "flow-agent-" + provenance.applicationId)
                    .put("format", "markdown").put("source", output).put("fontSizeSp", 16.0)
                    .put("lineHeight", 1.35).put("width", 600.0).put("anchorPageIndex", 0)
                    .put("anchorXInPage", 16.0).put("anchorYInPage", 16.0)
                    .put("agentResult", provenance.toJson());
            JSONObject appended = new JSONObject(original.toString()).put("schemaVersion", 9)
                    .put("textFlows", new JSONArray().put(flow));
            NoteStore.validateDocument(appended);

            NoteStore.VersionedSaveResult committed = NoteStore.saveLegacyIfBodyMatches(fixture.context,
                    entry.id, entry.title, NoteJsonCodec.stringify(appended), originalSha);
            JSONObject restored = NoteStore.load(fixture.context, entry.id);
            assertEquals(9, restored.getInt("schemaVersion"));
            assertEquals(output, restored.getJSONArray("textFlows").getJSONObject(0).getString("source"));
            assertEquals(provenance.resultSha256, restored.getJSONArray("textFlows").getJSONObject(0)
                    .getJSONObject("agentResult").getString("resultSha256"));
            assertNotEquals(originalSha, committed.bodySha256);

            JSONObject staleEditor = new JSONObject(original.toString()).put("title", "stale overwrite");
            try {
                NoteStore.saveLegacyIfBodyMatches(fixture.context, entry.id, "stale overwrite",
                        NoteJsonCodec.stringify(staleEditor), originalSha);
                fail("an editor holding the old body SHA must not overwrite the appended result");
            } catch (java.io.IOException expected) {
                assertEquals("BASE_CAS_CONFLICT", expected.getMessage());
            }
            JSONObject afterConflict = NoteStore.load(fixture.context, entry.id);
            assertEquals(9, afterConflict.getInt("schemaVersion"));
            assertEquals(provenance.applicationId, afterConflict.getJSONArray("textFlows").getJSONObject(0)
                    .getJSONObject("agentResult").getString("applicationId"));

            String currentSha = NoteStore.legacyBodySha256(fixture.context, entry.id);
            NoteStore.saveLegacyIfBodyMatches(fixture.context, entry.id, "Ordinary later edit",
                    NoteJsonCodec.stringify(afterConflict), currentSha);
            JSONObject afterOrdinarySave = NoteStore.load(fixture.context, entry.id);
            assertEquals("schema 9 provenance survives subsequent normal editor saves", 9,
                    afterOrdinarySave.getInt("schemaVersion"));
            assertEquals(provenance.applicationId, afterOrdinarySave.getJSONArray("textFlows")
                    .getJSONObject(0).getJSONObject("agentResult").getString("applicationId"));

            if (Build.VERSION.SDK_INT >= 27) {
                NoteStore.Entry adoptionEntry = NoteStore.create(fixture.context, "Schema nine adoption fixture");
                JSONObject adoptionBase = NoteStore.load(fixture.context, adoptionEntry.id);
                AgentResultProvenance adoptionReceipt = AgentResultProvenance.create(task,
                        adoptionEntry.id, adoptionBase.optLong("updatedAt"), output);
                JSONObject adoptionFlow = new JSONObject().put("id",
                        "flow-agent-" + adoptionReceipt.applicationId).put("format", "markdown")
                        .put("source", output).put("fontSizeSp", 16.0).put("lineHeight", 1.35)
                        .put("width", 600.0).put("anchorPageIndex", 0).put("anchorXInPage", 16.0)
                        .put("anchorYInPage", 16.0).put("agentResult", adoptionReceipt.toJson());
                JSONObject adoptionDocument = new JSONObject(adoptionBase.toString())
                        .put("schemaVersion", 9).put("textFlows", new JSONArray().put(adoptionFlow));
                String adoptionBaseSha = NoteStore.legacyBodySha256(fixture.context, adoptionEntry.id);
                NoteStore.saveLegacyIfBodyMatches(fixture.context, adoptionEntry.id, adoptionEntry.title,
                        NoteJsonCodec.stringify(adoptionDocument), adoptionBaseSha);
                com.padnote.android.streaming.StreamingGroupStore.Snapshot adopted =
                        new NoteGroupFacade(fixture.context).adoptLegacyNote(adoptionEntry.id,
                                (before, proposed) -> true);
                JSONObject adoptedBody = new JSONObject(new String(adopted.readSmall("body.bin",
                        (int) com.padnote.android.streaming.StreamingGroupStore.BODY_MAX),
                        StandardCharsets.UTF_8));
                assertEquals("legacy-to-managed adoption retains typed result provenance", 9,
                        adoptedBody.getInt("schemaVersion"));
                assertEquals(adoptionReceipt.applicationId, adoptedBody.getJSONArray("textFlows")
                        .getJSONObject(0).getJSONObject("agentResult").getString("applicationId"));
            }

            File archive = new File(fixture.appData, "schema9-result-backup.zip");
            File staging = new File(fixture.context.getFilesDir(), "library-backup");
            assertTrue(staging.mkdirs());
            try (LibraryBackupSnapshot snapshot = LibraryBackupSnapshot.create(fixture.context,
                    Collections.singleton(entry.id), null, null)) {
                assertEquals("backup descriptor retains the result note schema", 9,
                        snapshot.manifest.notes.get(0).noteSchemaVersion);
                snapshot.write(archive, null, null);
            }
            try (LibraryBackupArchive.StagedArchive staged = LibraryBackupArchive.stage(
                    archive, staging, null, null)) {
                assertEquals(9, staged.manifest().notes.get(0).noteSchemaVersion);
                String restoredId = new LibraryRestoreTransaction(fixture.context, staged)
                        .restoreNotes(null, null).values().iterator().next();
                JSONObject restoredCopy = NoteStore.load(fixture.context, restoredId);
                assertEquals(9, restoredCopy.getInt("schemaVersion"));
                assertEquals("restore keeps provenance attached to the copied flow",
                        provenance.applicationId, restoredCopy.getJSONArray("textFlows")
                                .getJSONObject(0).getJSONObject("agentResult").getString("applicationId"));
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final File appData;
        final Context context;
        private Fixture(File appData, Context context) { this.appData = appData; this.context = context; }

        static Fixture create() throws Exception {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            File appData = new File(base.getCacheDir(), "agent-result-cas-" + UUID.randomUUID());
            File files = new File(appData, "files"), cache = new File(appData, "cache");
            if (!files.mkdirs() || !cache.mkdirs()) throw new java.io.IOException("FIXTURE_ROOT_CREATE_FAILED");
            ApplicationInfo info = new ApplicationInfo(base.getApplicationInfo());
            info.dataDir = appData.getCanonicalPath();
            Context isolated = new ContextWrapper(base) {
                @Override public File getFilesDir() { return files; }
                @Override public File getCacheDir() { return cache; }
                @Override public File getDataDir() { return appData; }
                @Override public File getDir(String name, int mode) {
                    if(name==null||!name.matches("[A-Za-z0-9._-]{1,64}"))
                        throw new IllegalArgumentException("FIXTURE_DIR_NAME_INVALID");
                    File directory=new File(files,"app_"+name);
                    if(!directory.isDirectory()&&!directory.mkdirs())
                        throw new IllegalStateException("FIXTURE_DIR_CREATE_FAILED");
                    return directory;
                }
                @Override public ApplicationInfo getApplicationInfo() { return info; }
                @Override public Context getApplicationContext() { return this; }
            };
            return new Fixture(appData, isolated);
        }

        @Override public void close() { deleteTree(appData); }
        private static void deleteTree(File file) {
            if (file == null || !file.exists()) return;
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
            if (!file.delete() && file.exists()) throw new IllegalStateException("FIXTURE_CLEANUP_FAILED");
        }
    }
}
