package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

/** Cover file naming and decode-sampling math (pure parts of CoverStore). */
public class CoverStoreTest {

    @Test
    public void coverFileSitsNextToItsNote() {
        File directory = new File("/data/notes");
        File file = CoverStore.coverFile(directory, "note-abc123");
        assertEquals(new File("/data/notes/note-abc123.cover.png"), file);
        assertTrue(file.getName().endsWith(".cover.png"));
    }

    @Test
    public void sampleSizeKeepsDecodedWidthAtOrAboveTarget() {
        // 720/2 = 360 >= 320, so one halve is taken.
        assertEquals(2, CoverStore.sampleSize(720, 320));
        // 1440: 720 and 360 both stay above 320, two halves.
        assertEquals(4, CoverStore.sampleSize(1440, 320));
        // 2880: three halves leave 360.
        assertEquals(8, CoverStore.sampleSize(2880, 320));
    }

    @Test
    public void presetDimensionsAreLandscape() {
        assertTrue("封面应为横版", CoverStore.PRESET_WIDTH > CoverStore.PRESET_HEIGHT);
        assertTrue("存储宽度不应超过上限",
                CoverStore.PRESET_WIDTH <= CoverStore.MAX_STORED_WIDTH);
    }
}
