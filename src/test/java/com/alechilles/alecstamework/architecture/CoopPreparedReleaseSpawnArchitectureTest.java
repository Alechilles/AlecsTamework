package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the admission-before-spawn ordering for replacement coop residents. */
class CoopPreparedReleaseSpawnArchitectureTest {
    private static final Path ITEMS = Paths.get(
            "src", "main", "java", "com", "alechilles", "alecstamework", "items"
    );

    @Test
    void managedCoopSystemCannotSpawnAReplacementBeforePopulationAdmission() throws IOException {
        String gateway = read("HytaleManagedCoopReleaseProjectionGateway.java");
        String adapter = read("ManagedCoopReleaseRuntimeAdapter.java");
        String spawner = read("ManagedCoopReleaseProjectionSpawner.java");
        String population = read("ManagedCoopReleasePopulationCoordinator.java");

        assertFalse(
                gateway.contains("npcPlugin.spawnEntity("),
                "Managed coop orchestration must delegate live creation to the release spawner."
        );
        int prepare = gateway.indexOf("populations.prepareAsync(");
        int dispatch = gateway.indexOf("dispatchPrepared(", prepare);
        int claim = adapter.indexOf("populations.claimForSpawn(prepared, claim)");
        int coordinate = adapter.indexOf("orchestrator.coordinate(", claim);
        int spawn = adapter.indexOf("() -> spawnWithPopulation(", coordinate);
        int commit = adapter.indexOf("populations.commitAsync(prepared", spawn);
        assertTrue(prepare >= 0 && dispatch > prepare,
                "Population preparation must complete before world-thread projection dispatch.");
        assertTrue(claim < coordinate && coordinate < spawn,
                "No denied/recheck-failed release may create a live NPC.");
        assertTrue(spawn < commit,
                "The exact materialized identity must be supplied to the atomic finalizer.");
        assertTrue(spawner.contains("plugin.spawnEntity("),
                "The dedicated managed-release spawner remains the only live creation point.");
        assertTrue(adapter.contains("populationProjectionSpawner.spawn("));
        assertTrue(population.contains("backend.commit(prepared.backendHandle)"));
        assertFalse(adapter.contains("resolveReleaseInPopulationCommit("));
    }

    /** Regression: an exception after Store.addEntity cannot safely cancel the coop admission. */
    @Test
    void ambiguousSpawnOutcomeIsProbedAndQuarantinedWithoutCancellation() throws IOException {
        String adapter = read("ManagedCoopReleaseRuntimeAdapter.java");
        int method = adapter.indexOf("private SpawnAttempt spawnWithPopulation(");
        int next = adapter.indexOf("private VerifiedSnapshot verifySnapshot(", method);
        String spawn = adapter.substring(method, next);

        assertTrue(spawn.contains("liveIdentityGuard.inspect("));
        assertTrue(spawn.contains("populations.markReadinessDegraded("));
        assertTrue(spawn.contains("SpawnAttempt.ambiguous("));
        assertTrue(spawn.contains("managed_coop_release_post_spawn_identity_ambiguous"));
        assertFalse(spawn.contains("populations.cancelAsync("),
                "A possibly materialized projection must keep its APPLYING recovery evidence.");
    }

    /** Regression: definite rollback cannot delete an uncertain live projection. */
    @Test
    void preProjectionRollbackIsExactAndNeverDespawnsAnUncertainEntity() throws IOException {
        String rollback = read("ManagedCoopReleaseLifecycleRollbackService.java");
        String spawner = read("ManagedCoopReleaseProjectionSpawner.java");

        assertTrue(rollback.contains("failBeforeProjection("));
        assertTrue(rollback.contains("operationId, operationGeneration"));
        assertFalse(rollback.contains("setToDespawn("));
        assertTrue(spawner.contains("never despawns an uncertain"));
    }

    private static String read(String fileName) throws IOException {
        return Files.readString(ITEMS.resolve(fileName), StandardCharsets.UTF_8);
    }
}
