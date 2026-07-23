package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.control.PersistenceEngineLease;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLineage;
import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proves the old runtime participates in the same irreversible engine gate. */
class TameworkPersistenceRuntimeEngineLeaseTest {
    @TempDir
    Path tempDir;

    @Test
    void liveLegacyRuntimeExcludesReplacementBeforeAnyReplacementWrite() {
        try (TameworkPersistenceRuntime ignored =
                     TameworkPersistenceRuntime.initialize(tempDir, null)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> PersistenceEngineLease.acquireReplacement(tempDir)
            );
            assertFalse(Files.exists(
                    PersistenceFiles.replacementDatabase(tempDir)
            ));
        }
    }

    @Test
    void publishedReplacementRefusesLegacyBeforeLegacyDatabaseCreation() {
        try (PersistenceEngineLease replacement =
                     PersistenceEngineLease.acquireReplacement(tempDir)) {
            replacement.publishStartupComplete();
        }

        assertThrows(
                IllegalStateException.class,
                () -> TameworkPersistenceRuntime.initialize(tempDir, null)
        );
        assertFalse(Files.exists(PersistenceFiles.legacyDatabase(tempDir)));
    }

    @Test
    void cleanLegacyShutdownLeavesReplacementUpgradeAdmissible() {
        TameworkPersistenceRuntime legacy =
                TameworkPersistenceRuntime.initialize(tempDir, null);
        legacy.close();
        legacy.close();

        try (PersistenceEngineLease replacement =
                     PersistenceEngineLease.acquireReplacement(tempDir)) {
            replacement.publishStartupComplete();
            org.junit.jupiter.api.Assertions.assertEquals(
                    PersistenceEngineLineage.REPLACEMENT,
                    replacement.manifest().orElseThrow().lineage()
            );
        }
    }
}
