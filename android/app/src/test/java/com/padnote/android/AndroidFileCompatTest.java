package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;

public final class AndroidFileCompatTest {
    @Test public void normalizesDotSegmentsAndRepeatedSeparatorsLexically() throws Exception {
        assertEquals("/safe/root/item", AndroidFileCompat.normalizeAbsolute(
                new File("/safe//root/./folder/../item")));
        assertEquals("/", AndroidFileCompat.normalizeAbsolute(new File("/../.././")));
    }

    @Test public void acceptsOnlyStrictDescendantsWithComponentBoundaries() throws Exception {
        assertEquals(Arrays.asList("nested", "item"), AndroidFileCompat.descendantComponents(
                new File("/data/app"), new File("/data/app/a/../nested/item")));
        assertEquals(Arrays.asList("tmp", "item"), AndroidFileCompat.descendantComponents(
                new File("/"), new File("/tmp/item")));
    }

    @Test public void rejectsRootItselfPrefixSiblingAndLexicalEscape() throws Exception {
        rejects(new File("/data/app"), new File("/data/app"));
        rejects(new File("/data/app"), new File("/data/application/child"));
        rejects(new File("/data/app"), new File("/data/app/../outside"));
    }

    private static void rejects(File root, File target) throws Exception {
        try {
            AndroidFileCompat.descendantComponents(root, target);
            fail("accepted path outside strict descendant namespace: " + target);
        } catch (IOException expected) {
            // Expected: boundary and traversal checks are the security contract.
        }
    }
}
