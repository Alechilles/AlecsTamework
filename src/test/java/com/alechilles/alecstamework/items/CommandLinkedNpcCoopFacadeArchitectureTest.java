package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural regression gates for the process-local coop presentation cache. */
class CommandLinkedNpcCoopFacadeArchitectureTest {
    private static final Path ITEMS = Path.of(
            "src/main/java/com/alechilles/alecstamework/items");

    @Test
    void cacheHasNoPersistenceAuthorityOrLegacyCollaborators()
            throws Exception {
        Path facade = ITEMS.resolve("CommandLinkedNpcCoopService.java");
        String source = Files.readString(facade);

        assertTrue(Files.readAllLines(facade).size() <= 180);
        assertFalse(source.contains("persistence.sqlite"));
        assertFalse(source.contains("Repository"));
        assertFalse(source.contains("Path"));
        assertFalse(source.contains("loadLegacy"));
        assertFalse(source.contains("ManagedCoop"));

        for (String removed : List.of(
                "LegacyCommandCoopLedger.java",
                "LegacyCoopLedgerPersistence.java",
                "LegacyCoopLedgerEntry.java",
                "LegacyCoopLedgerSupport.java",
                "LegacyCoopSnapshotPool.java")) {
            assertFalse(Files.exists(ITEMS.resolve(removed)), removed);
        }
    }

    @Test
    void durableCoopGameplayUsesReplacementAuthor() throws Exception {
        String system = Files.readString(
                ITEMS.resolve("CommandDirectLiveCoopSystem.java")
        );
        assertTrue(system.contains("PersistenceDomainFacades"));
        assertTrue(system.contains("DirectLiveCoopAuthor"));
        assertFalse(system.contains("CommandLinkedNpcCoopService"));
        assertFalse(system.contains("persistence.sqlite"));
    }
}
