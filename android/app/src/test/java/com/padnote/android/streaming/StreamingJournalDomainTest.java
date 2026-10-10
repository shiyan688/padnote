package com.padnote.android.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class StreamingJournalDomainTest {
    @Test public void acceptsOnlyTheTwoTerminatedJournalDomains() throws Exception {
        assertEquals("PadNote/StreamingTransaction/v1", domain("PadNote/StreamingTransaction/v1\0payload"));
        assertEquals("PadNote/StreamingManualUpdateTransaction/v2", domain("PadNote/StreamingManualUpdateTransaction/v2\0payload"));
    }

    @Test public void legacyCorruptShortBytesKeepJournalInvalidClassification() throws Exception {
        assertCode("JOURNAL_INVALID", new byte[]{0x42, 0x41, 0x44});
    }

    @Test public void truncatedKnownDomainWithoutTerminatorIsJournalInvalid() throws Exception {
        assertCode("JOURNAL_INVALID", "PadNote/StreamingTransaction/v1".getBytes(StandardCharsets.US_ASCII));
        assertCode("JOURNAL_INVALID", "PadNote/StreamingManualUpdateTransaction/v2".getBytes(StandardCharsets.US_ASCII));
    }

    @Test public void terminatedButUnsupportedDomainRemainsSpecific() throws Exception {
        assertCode("JOURNAL_DOMAIN_INVALID", new byte[]{'P','a','d','N','o','t','e','/','X',0,1,2});
    }

    private static String domain(String text) throws Exception {
        return StreamingGroupStore.Store.parseJournalDomain(text.getBytes(StandardCharsets.US_ASCII));
    }
    private static void assertCode(String code, byte[] bytes) throws Exception {
        try { StreamingGroupStore.Store.parseJournalDomain(bytes); fail("expected " + code); }
        catch (IOException expected) { assertEquals(code, expected.getMessage()); }
    }
}
