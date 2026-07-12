package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural regression gates for the compatibility-facade cutover. */
class CommandLinkedNpcCoopFacadeArchitectureTest {
    private static final Path ITEMS = Path.of(
            "src/main/java/com/alechilles/alecstamework/items");

    @Test
    void facadeCollaboratorsRemainFocusedAndDormantSystemsStayDeleted() throws Exception {
        for (String file : List.of(
                "CommandLinkedNpcCoopService.java",
                "LegacyCommandCoopLedger.java",
                "LegacyCoopLedgerPersistence.java",
                "LegacyCoopLedgerEntry.java",
                "LegacyCoopLedgerSupport.java",
                "LegacyCoopSnapshotPool.java",
                "ManagedCoopAssignmentQuery.java")) {
            Path path = ITEMS.resolve(file);
            assertTrue(Files.exists(path), file);
            assertTrue(Files.readAllLines(path).size() <= 500, file + " exceeds 500 lines");
        }
        assertFalse(Files.exists(ITEMS.resolve("CommandCoopResidentSyncSystem.java")));
        assertFalse(Files.exists(ITEMS.resolve("CoopFeatureHandler.java")));
    }

    @Test
    void managedAssignmentQueryIsReadOnlyAndLegacyClassesDeclareRollbackScope() throws Exception {
        String managed = Files.readString(ITEMS.resolve("ManagedCoopAssignmentQuery.java"));
        assertFalse(managed.contains("ManagedCoopResidentRepository residentRepository"));
        assertFalse(managed.contains("claimHoused("));
        assertFalse(managed.contains("beginRelease("));
        assertFalse(managed.contains("finishRelease("));

        String ledger = Files.readString(ITEMS.resolve("LegacyCommandCoopLedger.java"));
        String persistence = Files.readString(ITEMS.resolve("LegacyCoopLedgerPersistence.java"));
        assertTrue(ledger.contains("Rollback-only"));
        assertTrue(persistence.contains("never a managed-coop authority"));
    }
}
