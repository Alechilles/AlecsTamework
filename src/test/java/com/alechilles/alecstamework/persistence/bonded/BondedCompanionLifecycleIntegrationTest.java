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
    void lifecycleBridgeUsesOnlyLocalRecoveryAndDirectMarkerDeath()
            throws Exception {
        String source = Files.readString(
                MAIN.resolve("TameworkBondedCompanionComposition.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("replayPendingCleanupForWorld("));
        assertTrue(source.contains("localLifecycle.reconcileCurrentWorld("));
        assertTrue(source.contains("localLifecycle.storeOwnerInWorld("));
        assertTrue(source.contains("RecoveryCause.WORLD_LOAD"));
        assertTrue(source.contains("RecoveryCause.WORLD_TRANSFER"));
        assertTrue(source.contains("RecoveryCause.LOGOUT"));
        assertTrue(source.contains("localLifecycle.onConfirmedDeath("));
        assertTrue(source.contains("durability.settleResidualLeases("));
        assertTrue(source.contains("store.pruneCleanup(now, 64)"));
        assertTrue(!source.contains("scanBoundedRecoveryAsync"));
        assertTrue(!source.contains("activeLeases(256)"));
    }

    @Test
    void worldGatewayNeverEnumeratesWorldsOrWaitsSynchronously()
            throws Exception {
        String source = Files.readString(MAIN.resolve(
                        "companion/bonded/runtime/"
                                + "HytaleBondedCompanionWorldGateway.java"),
                StandardCharsets.UTF_8);

        assertTrue(!source.contains("getWorlds()"));
        assertTrue(!source.contains(".join()"));
        assertTrue(source.contains("Universe.get().getWorld(worldKey)"));
        assertTrue(source.contains("world.execute(read)"));
    }
}
