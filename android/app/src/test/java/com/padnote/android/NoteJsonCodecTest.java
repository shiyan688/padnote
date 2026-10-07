package com.padnote.android;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public final class NoteJsonCodecTest {
    @Test public void omittedFieldDigestEncodingKeepsNestedNegativeZero() throws Exception {
        JSONObject source = new JSONObject().put("keep", -0.0d).put("digest", "old");
        String encoded = NoteJsonCodec.stringifyWithout(source, "digest");
        assertEquals("{\"keep\":-0.0}", encoded);
        assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(
                new JSONObject(encoded).getDouble("keep")));
    }

    @Test public void keepsNegativeZeroAndLargeIntegerTokensRoundTrippable() throws Exception {
        JSONObject source = new JSONObject().put("negativeZero", -0.0d)
                .put("largeInteger", 9007199254740993L)
                .put("fraction", 768.00001d);
        String encoded = NoteJsonCodec.stringify(source);
        assertEquals(true, encoded.contains("\"negativeZero\":-0.0"));
        JSONObject reopened = new JSONObject(encoded);
        assertEquals(Double.doubleToRawLongBits(-0.0d),
                Double.doubleToRawLongBits(reopened.getDouble("negativeZero")));
        assertEquals(9007199254740993L, reopened.getLong("largeInteger"));
        assertEquals(Double.doubleToRawLongBits(768.00001d),
                Double.doubleToRawLongBits(reopened.getDouble("fraction")));
    }
}
