package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

public final class AiConversationStoreBinary64Test {
    @Test public void nestedNegativeZeroSurvivesPersistedAiReceiptValidation() throws Exception {
        JSONObject before = new JSONObject().put("id", "flow-zero")
                .put("anchorXInPage", -0.0d).put("anchorYInPage", 0.0d);
        JSONObject change = new JSONObject().put("id", "flow-zero").put("before", before);
        JSONObject unsigned = new JSONObject().put("schemaVersion", 1)
                .put("changes", new JSONArray().put(change));
        String digest = sha256(NoteJsonCodec.stringify(unsigned));
        JSONObject signed = new JSONObject(NoteJsonCodec.stringify(unsigned)).put("digest", digest);
        String receiptJson = AiConversationStore.encodeEditReceipt(signed);

        AiConversationStore.VisibleEntry entry = new AiConversationStore.VisibleEntry(
                "result", "assistant", "updated", "local", false,
                Collections.singletonList("flow-zero"), digest, receiptJson);
        AiConversationStore.VisibleEntry reopened = AiConversationStore.VisibleEntry
                .fromJson(entry.toJson());

        assertNotNull(reopened);
        assertTrue(reopened.receiptJson.contains("\"anchorXInPage\":-0.0"));
        JSONObject restoredReceipt = new JSONObject(reopened.receiptJson);
        long actual = Double.doubleToRawLongBits(restoredReceipt.getJSONArray("changes")
                .getJSONObject(0).getJSONObject("before").getDouble("anchorXInPage"));
        assertEquals(Double.doubleToRawLongBits(-0.0d), actual);
    }

    @Test public void platformNormalizedNewReceiptRejectsLostNegativeZero() throws Exception {
        JSONObject before = new JSONObject().put("id", "flow-zero-platform")
                .put("anchorXInPage", -0.0d).put("anchorYInPage", 16.0d);
        JSONObject unsigned = new JSONObject().put("schemaVersion", 1)
                .put("changes", new JSONArray().put(new JSONObject()
                        .put("id", "flow-zero-platform").put("before", before)));
        JSONObject sealed = AiConversationStore.sealEditReceipt(unsigned);
        assertTrue(AiConversationStore.hasValidEditReceiptDigest(sealed));

        JSONObject exactReopen = new JSONObject(AiConversationStore.encodeEditReceipt(sealed));
        assertTrue(NoteJsonCodec.signedZeroPathsMatch(exactReopen));
        assertTrue(AiConversationStore.hasValidEditReceiptDigest(exactReopen));
        String normalizedJson = AiConversationStore.encodeEditReceipt(sealed)
                .replace("\"anchorXInPage\":-0.0", "\"anchorXInPage\":0");
        JSONObject platformNormalized = new JSONObject(normalizedJson);
        assertTrue(!NoteJsonCodec.signedZeroPathsMatch(platformNormalized));
        assertFalse(AiConversationStore.hasValidEditReceiptDigest(platformNormalized));
    }

    @Test public void compatibilityDigestBindsStoredCanonicalDigest() throws Exception {
        JSONObject unsigned = new JSONObject().put("schemaVersion", 1)
                .put("changes", new JSONArray().put(new JSONObject().put("id", "flow-bound")
                        .put("before", new JSONObject().put("fontSizeSp", 16.0d))));
        JSONObject sealed = AiConversationStore.sealEditReceipt(unsigned);
        String validNormalized = AiConversationStore.encodeEditReceipt(sealed)
                .replace("\"fontSizeSp\":16.0", "\"fontSizeSp\":16");
        JSONObject normalized = new JSONObject(validNormalized);
        assertTrue(AiConversationStore.hasValidEditReceiptDigest(normalized));

        normalized.put("digest", new String(new char[64]).replace('\0', '0'));
        assertFalse(AiConversationStore.hasValidEditReceiptDigest(normalized));
        JSONObject missingPair = new JSONObject(validNormalized);
        missingPair.remove("legacyDigest");
        assertFalse(AiConversationStore.hasValidEditReceiptDigest(missingPair));

        String alteredJson = AiConversationStore.encodeEditReceipt(normalized);
        AiConversationStore.VisibleEntry altered = new AiConversationStore.VisibleEntry(
                "result", "assistant", "updated", "local", false,
                Collections.singletonList("flow-bound"), normalized.getString("digest"), alteredJson);
        try {
            AiConversationStore.VisibleEntry.fromJson(altered.toJson());
            throw new AssertionError("parser accepted changed embedded and outer canonical digest");
        } catch (org.json.JSONException expected) {
            assertTrue(expected.getMessage().contains("摘要不匹配"));
        }
    }

    @Test public void platformNormalizedWholeDoubleReceiptRetainsCompatibility() throws Exception {
        JSONObject before = new JSONObject().put("id", "flow-whole-double")
                .put("fontSizeSp", 16.0d);
        JSONObject unsigned = new JSONObject().put("schemaVersion", 1)
                .put("changes", new JSONArray().put(new JSONObject()
                        .put("id", "flow-whole-double").put("before", before)));
        JSONObject sealed = AiConversationStore.sealEditReceipt(unsigned);

        String normalized = AiConversationStore.encodeEditReceipt(sealed)
                .replace("\"fontSizeSp\":16.0", "\"fontSizeSp\":16");
        AiConversationStore.VisibleEntry entry = new AiConversationStore.VisibleEntry(
                "result", "assistant", "updated", "local", false,
                Collections.singletonList("flow-whole-double"), sealed.getString("digest"), normalized);
        AiConversationStore.VisibleEntry reopened = AiConversationStore.VisibleEntry
                .fromJson(entry.toJson());
        assertNotNull(reopened);
    }


    @Test public void acceptsLegacySchemaOneReceiptDigestAfterPlatformNumberNormalization() throws Exception {
        JSONObject before = new JSONObject().put("id", "flow-legacy")
                .put("fontSizeSp", Float.valueOf(16.0f)).put("lineHeight", 1.35d)
                .put("small", 1.0e-7d);
        JSONObject unsigned = new JSONObject().put("schemaVersion", 1)
                .put("changes", new JSONArray().put(new JSONObject()
                        .put("id", "flow-legacy").put("before", before)));
        String legacyDigest = sha256(unsigned.toString());
        JSONObject oldReceipt = new JSONObject(unsigned.toString()).put("digest", legacyDigest);
        String oldReceiptJson = oldReceipt.toString();
        AiConversationStore.VisibleEntry legacy = new AiConversationStore.VisibleEntry(
                "result", "assistant", "updated", "local", false,
                Collections.singletonList("flow-legacy"), legacyDigest, oldReceiptJson);

        AiConversationStore.VisibleEntry reopened = AiConversationStore.VisibleEntry
                .fromJson(legacy.toJson());

        assertNotNull(reopened);
        assertEquals(legacyDigest, new JSONObject(reopened.receiptJson).getString("digest"));
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte item : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        return hex.toString();
    }
}
