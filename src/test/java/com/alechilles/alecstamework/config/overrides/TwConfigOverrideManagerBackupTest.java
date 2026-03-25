package com.alechilles.alecstamework.config.overrides;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies rolling backup retention for override snapshots. */
class TwConfigOverrideManagerBackupTest {

    @TempDir
    Path tempDir;

    @Test
    void pruneSnapshotBackupsRetainsNewestDirectoriesByName() throws Exception {
        Path snapshotsRoot = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsRoot);
        for (int i = 1; i <= 12; i++) {
            Files.createDirectories(snapshotsRoot.resolve(String.format("20260101-1200%02d-%02d", i, i)));
        }

        TwConfigOverrideManager.pruneSnapshotBackups(snapshotsRoot, 10);

        List<String> names = Files.list(snapshotsRoot)
                .filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();

        assertEquals(10, names.size());
        assertFalse(names.contains("20260101-120001-01"));
        assertFalse(names.contains("20260101-120002-02"));
        assertTrue(names.contains("20260101-120012-12"));
    }
}
