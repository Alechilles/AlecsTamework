package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Regression coverage for Task 5 production lifecycle registration. */
class BondedCompanionLifecycleIntegrationTest {
    private static final Path MAIN = Path.of(
            "src/main/java/com/alechilles/alecstamework"
    );

    @Test
    void pluginOpensBondedBeforeGenericAndRegistersEveryAvailableSignal()
            throws Exception {
        String source = Files.readString(MAIN.resolve("Tamework.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.indexOf("TameworkBondedCompanionComposition.open(")
                < source.indexOf("TameworkPersistenceComposition.create("));
        assertTrue(source.contains("bondedCompanionComposition::onWorldLoad"));
        assertTrue(source.contains("bondedCompanionComposition::onPlayerAdded"));
        assertTrue(source.contains("bondedCompanionComposition::onPlayerLogout"));
        assertTrue(source.contains("new BondedCompanionMaintenanceSystem("));
        assertTrue(source.contains("new BondedCompanionDeathSystem("));
        assertTrue(source.contains("activateBondedOnlyFallback("));
    }

    @Test
    void lifecycleBridgeDrivesCleanupExpiryAndEveryObserverPath()
            throws Exception {
        String source = Files.readString(
                MAIN.resolve("TameworkBondedCompanionComposition.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("replayPendingCleanup("));
        assertTrue(source.contains("expiry.tick("));
        assertTrue(source.contains("observer.onWorldLoad("));
        assertTrue(source.contains("observer.onPlayerJoin("));
        assertTrue(source.contains("observer.onPlayerWorldTransfer("));
        assertTrue(source.contains("observer.onPlayerLogout("));
        assertTrue(source.contains("observer.onConfirmedDeath("));
        assertTrue(source.contains("projectionRecovery.tick(now)"));
        assertTrue(source.contains("durability::liveLeases"));
        assertTrue(source.contains("store.pruneCleanup(now, 64)"));
    }
}
