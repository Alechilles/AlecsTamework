package com.alechilles.alecstamework.ownership.reconciliation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentWorldDirectoryCatalogTest {
    @Test
    void snapshotsOnlyImmediateWorldDirectories(@TempDir Path tempDir) throws Exception {
        Path worldsRoot = Files.createDirectories(tempDir.resolve("universe/worlds"));
        Path alpha = Files.createDirectory(worldsRoot.resolve("Alpha"));
        Path beta = Files.createDirectory(worldsRoot.resolve("Beta"));
        Files.createDirectories(alpha.resolve("chunks/region-data"));
        Files.writeString(worldsRoot.resolve("README.txt"), "not a world");

        PersistentWorldDirectoryCatalog.Snapshot snapshot =
                PersistentWorldDirectoryCatalog.filesystem().snapshot(worldsRoot);

        assertEquals(
                List.of(alpha.toAbsolutePath().normalize(), beta.toAbsolutePath().normalize()),
                snapshot.worldDirectories()
        );
    }

    @Test
    void missingWorldsRootIsACompleteFreshCatalog(@TempDir Path tempDir) throws Exception {
        Path worldsRoot = tempDir.resolve("universe/worlds");

        PersistentWorldDirectoryCatalog.Snapshot snapshot =
                PersistentWorldDirectoryCatalog.filesystem().snapshot(worldsRoot);

        assertTrue(snapshot.worldDirectories().isEmpty());
        assertTrue(snapshot.compareToLiveWorlds(List.of()).complete());
    }

    @Test
    void flagsPersistedWorldThatUniverseOmitted(@TempDir Path tempDir) throws Exception {
        Path worldsRoot = Files.createDirectories(tempDir.resolve("universe/worlds"));
        Path alpha = Files.createDirectory(worldsRoot.resolve("Alpha"));
        Path failed = Files.createDirectory(worldsRoot.resolve("FailedWorld"));
        PersistentWorldDirectoryCatalog.Snapshot snapshot =
                PersistentWorldDirectoryCatalog.filesystem().snapshot(worldsRoot);

        PersistentWorldDirectoryCatalog.Coverage coverage =
                snapshot.compareToLiveWorlds(List.of(alpha));

        assertFalse(coverage.complete());
        assertEquals(List.of(failed.toAbsolutePath().normalize()), coverage.missingWorldDirectories());
    }

    @Test
    void exactCatalogAndAdditionalExternalLiveWorldRemainComplete(@TempDir Path tempDir) throws Exception {
        Path worldsRoot = Files.createDirectories(tempDir.resolve("universe/worlds"));
        Path alpha = Files.createDirectory(worldsRoot.resolve("Alpha"));
        Path external = Files.createDirectories(tempDir.resolve("trusted/Instance"));
        PersistentWorldDirectoryCatalog.Snapshot snapshot =
                PersistentWorldDirectoryCatalog.filesystem().snapshot(worldsRoot);

        PersistentWorldDirectoryCatalog.Coverage coverage =
                snapshot.compareToLiveWorlds(List.of(alpha, external));

        assertTrue(coverage.complete());
    }

    @Test
    void rejectsInjectedCatalogEntriesOutsideTheWorldsRoot(@TempDir Path tempDir) throws IOException {
        Path worldsRoot = Files.createDirectories(tempDir.resolve("universe/worlds"));
        Path unrelated = Files.createDirectories(tempDir.resolve("mods/not-a-world"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistentWorldDirectoryCatalog.Snapshot(worldsRoot, List.of(unrelated))
        );
    }
}
