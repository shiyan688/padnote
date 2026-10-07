package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class NoteStoreIntegratedRecoveryTest {
    @Test public void corruptNewestRevisionReopensValidatedBackupAndKeepsBadBytes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NoteStore.Entry entry = NoteStore.create(context, "存储恢复隔离测试");
        File target = new File(new File(context.getFilesDir(), NoteStore.notesDirectoryName()),
                entry.id + ".json");
        try {
            NoteStore.save(context, entry.id, entry.title, document(entry.id, "OLD").toString());
            NoteStore.save(context, entry.id, entry.title, document(entry.id, "NEW").toString());
            try (FileOutputStream output = new FileOutputStream(target, false)) {
                output.write("{truncated".getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }

            JSONObject recovered = NoteStore.load(context, entry.id);
            assertEquals("OLD", recovered.getJSONArray("textFlows")
                    .getJSONObject(0).getString("source"));
            File[] quarantined = target.getParentFile().listFiles((directory, name) ->
                    name.startsWith(target.getName() + ".corrupt-"));
            assertTrue("corrupt newest bytes must remain exportable",
                    quarantined != null && quarantined.length > 0);
            assertTrue("recovered backup must stay visible on the shelf",
                    NoteStore.list(context).stream().anyMatch(item -> item.id.equals(entry.id)));
        } finally {
            NoteStore.delete(context, entry.id);
        }
    }

    private static JSONObject document(String id, String source) throws Exception {
        return new JSONObject().put("schemaVersion", 8).put("id", id)
                .put("title", "存储恢复隔离测试").put("pageWidth", 736)
                .put("pageHeight", 1040).put("pageGap", 24).put("pageCount", 1)
                .put("strokes", new JSONArray()).put("images", new JSONArray())
                .put("textBoxes", new JSONArray()).put("textFlows", new JSONArray().put(
                        new TextFlow("flow", NoteTextBox.Format.MARKDOWN, source,
                                16, 1.35f, 600, 0, 30, 30).toJson()));
    }
}
