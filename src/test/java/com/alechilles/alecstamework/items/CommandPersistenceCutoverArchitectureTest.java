package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents command gameplay from regrowing a second durable persistence graph. */
class CommandPersistenceCutoverArchitectureTest {
    private static final Path ITEMS = Path.of(
            "src/main/java/com/alechilles/alecstamework/items"
    );

    @Test
    void commandSourcesHaveNoLegacyRuntimeOrRepositoryDependency()
            throws Exception {
        try (var sources = Files.list(ITEMS)) {
            for (Path path : sources
                    .filter(value -> value.getFileName().toString()
                            .startsWith("Command"))
                    .filter(value -> value.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(path);
                assertFalse(source.contains("persistence.sqlite"), path.toString());
                assertFalse(source.contains("TameworkPersistenceRuntime"), path.toString());
                assertFalse(source.contains("CompanionIdentityResolver"), path.toString());
            }
        }
    }

    @Test
    void unreleasedRecoveryGraphAndLegacyLedgersStayDeleted() {
        for (String removed : List.of(
                "CommandLostRecoveryCoordinator.java",
                "CommandLostRecoveryAliasLease.java",
                "CommandLostTransitionPersistenceService.java",
                "CommandLostTransitionDiagnostics.java",
                "CommandLostTransitionLogger.java",
                "CommandRecoveredSourceSuppressionIndex.java",
                "CommandUnexpectedRemovalRecoveryService.java",
                "CommandRespawnService.java",
                "CommandRespawnProgressionRestoreService.java",
                "CommandLinkedNpcDeathProfileWriter.java",
                "LegacyCommandCoopLedger.java",
                "LegacyCoopLedgerPersistence.java",
                "LegacyCoopLedgerEntry.java",
                "LegacyCoopLedgerSupport.java",
                "LegacyCoopSnapshotPool.java")) {
            assertFalse(Files.exists(ITEMS.resolve(removed)), removed);
        }
    }

    @Test
    void commandHandlerUsesFocusedReplacementBoundaries()
            throws Exception {
        String source = Files.readString(
                ITEMS.resolve("CommandItemFeatureHandler.java")
        );
        assertTrue(source.contains("PersistenceDomainFacades persistence"));
        assertTrue(source.contains(
                "FreeCompanionRestorationAuthor restorationAuthor"
        ));
        assertTrue(source.contains("CommandPersistenceView"));
        assertTrue(source.contains("CommandCompanionRestorationService"));
    }
}
