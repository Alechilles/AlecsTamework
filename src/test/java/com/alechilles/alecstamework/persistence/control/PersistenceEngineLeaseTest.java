package com.alechilles.alecstamework.persistence.control;

import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Engine selection, exclusivity, failed-startup, and rollback guarantees. */
class PersistenceEngineLeaseTest {
    @TempDir
    Path tempDir;

    @Test
    void heldLeaseExcludesBothOldAndReplacementEngines() {
        try (PersistenceEngineLease ignored =
                     PersistenceEngineLease.acquire(
                             tempDir,
                             PersistenceEngineLineage.LEGACY_PUBLIC,
                             () -> 10
                     )) {
            assertLockUnavailable(PersistenceEngineLineage.LEGACY_PUBLIC);
            assertLockUnavailable(PersistenceEngineLineage.REPLACEMENT);
        }
    }

    @Test
    void failedReplacementStartupPreservesLegacyManifestByteForByte() throws Exception {
        Path manifestPath = tempDir.resolve(PersistenceFiles.ENGINE_MANIFEST);
        try (PersistenceEngineLease legacy =
                     PersistenceEngineLease.acquire(
                             tempDir,
                             PersistenceEngineLineage.LEGACY_PUBLIC,
                             () -> 10
                     )) {
            legacy.publishStartupComplete();
        }
        byte[] before = Files.readAllBytes(manifestPath);

        try (PersistenceEngineLease replacement =
                     PersistenceEngineLease.acquire(
                             tempDir,
                             PersistenceEngineLineage.REPLACEMENT,
                             () -> 20
                     )) {
            assertEquals(
                    PersistenceEngineLineage.LEGACY_PUBLIC,
                    replacement.manifest().orElseThrow().lineage()
            );
        }

        assertTrue(java.util.Arrays.equals(
                before,
                Files.readAllBytes(manifestPath)
        ));
    }

    @Test
    void successfulReplacementCutoverPermanentlyRefusesLegacyEngine() {
        AtomicLong clock = new AtomicLong(100);
        try (PersistenceEngineLease replacement =
                     PersistenceEngineLease.acquire(
                             tempDir,
                             PersistenceEngineLineage.REPLACEMENT,
                             clock::getAndIncrement
                     )) {
            assertTrue(replacement.manifest().isEmpty());
            replacement.publishStartupComplete();
            PersistenceEngineManifest active =
                    replacement.manifest().orElseThrow();
            assertEquals(
                    PersistenceEngineLineage.REPLACEMENT,
                    active.lineage()
            );
            assertTrue(active.startupComplete());
            assertFalse(active.cleanShutdown());
        }

        IllegalStateException rejected = assertThrows(
                IllegalStateException.class,
                () -> PersistenceEngineLease.acquire(
                        tempDir,
                        PersistenceEngineLineage.LEGACY_PUBLIC,
                        clock::getAndIncrement
                )
        );
        assertEquals(
                "replacement_persistence_lineage_already_selected",
                rejected.getMessage()
        );
        try (PersistenceEngineLease replacement =
                     PersistenceEngineLease.acquire(
                             tempDir,
                             PersistenceEngineLineage.REPLACEMENT,
                             clock::getAndIncrement
                     )) {
            assertTrue(
                    replacement.manifest().orElseThrow().cleanShutdown()
            );
        }
    }

    @Test
    void malformedManifestFailsClosedWithoutLeakingTheLock() throws Exception {
        Path manifestPath = tempDir.resolve(PersistenceFiles.ENGINE_MANIFEST);
        Files.writeString(
                manifestPath,
                "{\"lineage\":\"replacement\"}",
                StandardCharsets.UTF_8
        );

        assertThrows(
                IllegalStateException.class,
                () -> PersistenceEngineLease.acquireReplacement(tempDir)
        );
        Files.delete(manifestPath);
        try (PersistenceEngineLease ignored =
                     PersistenceEngineLease.acquireReplacement(tempDir)) {
            assertTrue(ignored.manifest().isEmpty());
        }
    }

    @Test
    void legacyStartupAndRepeatedClosePublishCleanState() {
        AtomicLong clock = new AtomicLong(1);
        PersistenceEngineLease legacy = PersistenceEngineLease.acquire(
                tempDir,
                PersistenceEngineLineage.LEGACY_PUBLIC,
                clock::getAndIncrement
        );
        assertFalse(legacy.manifest().orElseThrow().startupComplete());
        legacy.publishStartupComplete();
        legacy.close();
        legacy.close();
        PersistenceEngineManifest closed;
        try {
            closed = new PersistenceEngineManifestStore(tempDir)
                    .read()
                    .orElseThrow();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        assertTrue(closed.startupComplete());
        assertTrue(closed.cleanShutdown());

        try (PersistenceEngineLease reopened =
                     PersistenceEngineLease.acquire(
                             tempDir,
                             PersistenceEngineLineage.LEGACY_PUBLIC,
                             clock::getAndIncrement
                     )) {
            PersistenceEngineManifest current =
                    reopened.manifest().orElseThrow();
            assertEquals(
                    PersistenceEngineLineage.LEGACY_PUBLIC,
                    current.lineage()
            );
            assertFalse(current.startupComplete());
            assertFalse(current.cleanShutdown());
        }
    }

    @Test
    void uncleanReleaseNeverPublishesAFalseCleanShutdown() {
        AtomicLong clock = new AtomicLong(1);
        PersistenceEngineLease replacement = PersistenceEngineLease.acquire(
                tempDir,
                PersistenceEngineLineage.REPLACEMENT,
                clock::getAndIncrement
        );
        replacement.publishStartupComplete();

        replacement.closeUnclean();
        replacement.close();

        PersistenceEngineManifest closed;
        try {
            closed = new PersistenceEngineManifestStore(tempDir)
                    .read()
                    .orElseThrow();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        assertTrue(closed.startupComplete());
        assertFalse(closed.cleanShutdown());
    }

    private void assertLockUnavailable(PersistenceEngineLineage lineage) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> PersistenceEngineLease.acquire(
                        tempDir,
                        lineage,
                        () -> 11
                )
        );
        assertEquals(
                "persistence_engine_lock_unavailable",
                failure.getMessage()
        );
    }
}
