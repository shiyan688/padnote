package com.padnote.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Prevents the documented migration fixtures and instrumentation assets from drifting. */
public final class LegacyFixtureParityTest {
    private static final Set<String> EXPECTED = new HashSet<>(Arrays.asList(
            "schema1.json", "schema2.json", "schema3.json", "schema4.json",
            "schema5.json", "schema6.json", "schema7.json", "schema7-source.pdf",
            "schema8.json", "schema8-corrupt-tail.json"));

    @Test public void documentedFixturesExactlyMatchPackagedTestAssets() throws Exception {
        File repository = findRepositoryRoot();
        File documented = new File(repository, "docs/fixtures/legacy-notes");
        File packaged = new File(repository,
                "android/app/src/androidTest/assets/legacy-notes");
        assertNotNull("documented fixture directory is missing", documented.listFiles());
        assertNotNull("packaged fixture directory is missing", packaged.listFiles());

        Set<String> documentedNames = dataFileNames(documented);
        Set<String> packagedNames = dataFileNames(packaged);
        org.junit.Assert.assertEquals(EXPECTED, documentedNames);
        org.junit.Assert.assertEquals(EXPECTED, packagedNames);
        for (String name : EXPECTED) {
            assertArrayEquals("fixture mirror differs: " + name,
                    Files.readAllBytes(new File(documented, name).toPath()),
                    Files.readAllBytes(new File(packaged, name).toPath()));
        }
    }

    private static Set<String> dataFileNames(File directory) {
        File[] files = directory.listFiles((ignored, name) ->
                name.endsWith(".json") || name.endsWith(".pdf"));
        assertNotNull(files);
        Set<String> names = new HashSet<>();
        for (File file : files) names.add(file.getName());
        return names;
    }

    private static File findRepositoryRoot() {
        File cursor = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int depth = 0; depth < 8 && cursor != null; depth++, cursor = cursor.getParentFile()) {
            if (new File(cursor, "android/app/src/androidTest").isDirectory()
                    && new File(cursor, "docs/fixtures/legacy-notes").isDirectory()) {
                return cursor;
            }
        }
        throw new AssertionError("cannot locate repository root from "
                + System.getProperty("user.dir", "."));
    }
}
