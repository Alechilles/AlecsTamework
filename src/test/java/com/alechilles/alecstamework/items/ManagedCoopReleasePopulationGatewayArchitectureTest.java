package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static ordering guardrails for managed release population admission and rollback. */
class ManagedCoopReleasePopulationGatewayArchitectureTest {
    private static final Path ITEMS = Path.of(
            "src/main/java/com/alechilles/alecstamework/items");

    @Test
    void definitivePrePreparationFailuresRollbackWhileAmbiguousPreparationIsRetained()
            throws Exception {
        String source = Files.readString(ITEMS.resolve(
                "HytaleManagedCoopReleaseProjectionGateway.java"));
        int failed = source.indexOf("PreparationStatus.FAILED");
        int failedAmbiguous = source.indexOf(
                "completion.complete(ambiguous(result.detail()))", failed);
        int denied = source.indexOf("PreparationStatus.DENIED");
        int deniedRollback = source.indexOf(
                "rollbackBeforePopulation(command, result.detail(), completion)", denied);

        assertTrue(source.contains("managed_coop_release_world_unavailable"));
        assertTrue(source.contains("managed_coop_release_world_dispatch_rejected"));
        assertTrue(source.contains("rollbackBeforePreparationAsync("));
        assertTrue(failed >= 0 && failedAmbiguous > failed);
        assertTrue(denied > failed && deniedRollback > denied);
        assertTrue(source.contains("cancelThenComplete("));
    }

    @Test
    void populationClaimHolderWriteAndAtomicCommitStayInRequiredOrder() throws Exception {
        String adapter = Files.readString(ITEMS.resolve(
                "ManagedCoopReleaseRuntimeAdapter.java"));
        String spawner = Files.readString(ITEMS.resolve(
                "ManagedCoopReleaseProjectionSpawner.java"));
        int claim = adapter.indexOf("populations.claimForSpawn(prepared, claim)");
        int spawn = adapter.indexOf("populationProjectionSpawner.spawn(");
        int finalizer = adapter.indexOf("populations.commitAsync(");
        int stateInstall = spawner.indexOf("stateInstaller.install(request, npc, holder)");
        int holderWrite = spawner.indexOf("request.holderWriter().write(holder)");

        assertTrue(claim >= 0 && claim < spawn);
        assertTrue(finalizer > claim);
        assertTrue(stateInstall >= 0 && stateInstall < holderWrite);
        assertTrue(spawner.contains("Status.HOLDER_WRITE_FAILED"));
    }
}
