package com.alechilles.alecstamework.persistence.control;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Operating-system process exclusivity test for both persistence lineages. */
class PersistenceEngineLeaseProcessTest {
    @TempDir
    Path tempDir;

    @Test
    void legacyProcessExcludesBothLineagesUntilItExits() throws Exception {
        assertForkedExclusion(PersistenceEngineLineage.LEGACY_PUBLIC);
    }

    @Test
    void replacementProcessExcludesBothLineagesUntilItExits()
            throws Exception {
        assertForkedExclusion(PersistenceEngineLineage.REPLACEMENT);
    }

    private void assertForkedExclusion(
            PersistenceEngineLineage heldLineage
    ) throws Exception {
        Process child = startChild(heldLineage);
        try {
            BufferedReader output = new BufferedReader(
                    new InputStreamReader(
                            child.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            );
            assertEquals("READY", output.readLine());
            assertUnavailable(PersistenceEngineLineage.LEGACY_PUBLIC);
            assertUnavailable(PersistenceEngineLineage.REPLACEMENT);
        } finally {
            child.getOutputStream().close();
            if (!child.waitFor(10, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
        try (PersistenceEngineLease ignored =
                     PersistenceEngineLease.acquireReplacement(tempDir)) {
            assertEquals(
                    PersistenceEngineLineage.REPLACEMENT,
                    ignored.requestedLineage()
            );
        }
    }

    private Process startChild(PersistenceEngineLineage lineage)
            throws Exception {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        Path javaHome = Path.of(System.getProperty("java.home"), "bin");
        Path java = javaHome.resolve("java.exe");
        if (!Files.isRegularFile(java)) {
            java = javaHome.resolve("java");
        }
        return new ProcessBuilder(
                java.toString(),
                "-cp",
                classpath,
                PersistenceEngineLeaseChild.class.getName(),
                tempDir.toString(),
                lineage.name()
        ).redirectErrorStream(true).start();
    }

    private void assertUnavailable(PersistenceEngineLineage lineage) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> {
                    if (lineage == PersistenceEngineLineage.LEGACY_PUBLIC) {
                        PersistenceEngineLease.acquireLegacy(tempDir);
                    } else {
                        PersistenceEngineLease.acquireReplacement(tempDir);
                    }
                }
        );
        assertEquals(
                "persistence_engine_lock_unavailable",
                failure.getMessage()
        );
    }
}
