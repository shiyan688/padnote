package com.padnote.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class NoteStoreDiagnosticElapsedTest {
    @Test public void terminalListElapsedIsStableWhileActiveElapsedRemainsLive() {
        assertEquals(75L, NoteStore.diagnosticElapsedMs(100L,75L,10_000L));
        assertEquals(999L, NoteStore.diagnosticElapsedMs(100L,-1L,1_099L));
        assertEquals(0L, NoteStore.diagnosticElapsedMs(0L,-1L,1_099L));
    }
}
