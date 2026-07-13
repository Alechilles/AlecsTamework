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
        int ambiguous = source.indexOf("PreparationStatus.AMBIGUOUS", failed);
        int retainedAmbiguous = source.indexOf(
                "managed_coop_population_preparation_retained_ambiguous", ambiguous);
        int denied = source.indexOf("PreparationStatus.DENIED");
        int deniedRollback = source.indexOf(
                "rollbackBeforePopulation(command, result.detail(), completion)", denied);

        assertTrue(source.contains("managed_coop_release_world_unavailable"));
        assertTrue(source.contains("managed_coop_release_world_dispatch_rejected"));
        assertTrue(source.contains("rollbackBeforePreparationAsync("));
        assertTrue(failed >= 0 && failedAmbiguous > failed);
        assertTrue(ambiguous > failed && retainedAmbiguous > ambiguous);
        assertTrue(denied > ambiguous && deniedRollback > denied);
        assertTrue(!source.substring(ambiguous, denied).contains("rollbackBeforePopulation("));
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

    /** Regression: a durable finalization cannot leave scheduler/command indexes on the claim. */
    @Test
    void atomicCommitPublishesThePairedFinalizedIndexEpoch() throws Exception {
        String population = Files.readString(ITEMS.resolve(
                "ManagedCoopReleasePopulationCoordinator.java"));
        String composition = Files.readString(ITEMS.resolve(
                "ManagedCoopRuntimeComposition.java"));
        int commit = population.indexOf("backend.commit(prepared.backendHandle)");
        int publish = population.indexOf("publishFinalizedIndexes(result.reason())", commit);

        assertTrue(commit >= 0 && publish > commit);
        assertTrue(population.contains("finalizedIndexRefresh.refresh()"));
        assertTrue(population.contains("!finalizedIndexRefresh.isTrusted()"));
        assertTrue(composition.contains(
                "services.lifecycleRepository(),\n"
                        + "                        services.compositeIndexRefreshService())"));
    }

    @Test
    void releaseReplayRequiresProjectionWideIndexProofWithoutUniverseScans()
            throws Exception {
        String guard = Files.readString(ITEMS.resolve(
                "ManagedCoopReleaseLiveIdentityGuard.java"));
        String probe = Files.readString(ITEMS.resolve(
                "ManagedCoopReleaseProjectionProbe.java"));

        assertTrue(guard.contains("new ManagedCoopReleaseProjectionProbe"));
        assertTrue(guard.contains("release_projection_identity_ambiguous"));
        assertTrue(probe.contains("identities::probeProjection"));
        assertTrue(probe.contains("counts.alternate() > 0"));
        assertTrue(!guard.contains("Universe"));
        assertTrue(!probe.contains("Universe"));
    }

    /** Regression: a marker add+unload after recovery cannot reopen physical projection. */
    @Test
    void persistedAbsenceTokenIsRecheckedAtBothWorldThreadSpawnBoundaries()
            throws Exception {
        String gateway = Files.readString(ITEMS.resolve(
                "HytaleManagedCoopReleaseProjectionGateway.java"));
        String lifecycle = Files.readString(ITEMS.resolve(
                "ManagedCoopLifecycleRecoveryService.java"));
        String recovery = Files.readString(ITEMS.resolve(
                "ManagedCoopReleaseRecoveryService.java"));
        int directCheck = gateway.indexOf("requireRecoveryCurrent(command);");
        int directRelease = gateway.indexOf("releases.release(", directCheck);
        int preparedCheck = gateway.indexOf(
                "requireRecoveryCurrent(command);", directCheck + 1);
        int preparedRelease = gateway.indexOf("releases.release(", preparedCheck);

        assertTrue(directCheck >= 0 && directRelease > directCheck);
        assertTrue(preparedCheck > directRelease && preparedRelease > preparedCheck);
        assertTrue(recovery.contains("withProjectionToken(new ProjectionToken("));
        assertTrue(lifecycle.contains("recovered.projectionToken()"));
        assertTrue(lifecycle.contains("releaseRecovery::projectionCurrent"));
    }

    /** Regression: a durable SPAWN_CLAIMED transition invalidates the sweep's HOUSED DTO. */
    @Test
    void releaseReResolvesCommittedResidentBeforeAdmissionAndSpawn() throws Exception {
        String gateway = Files.readString(ITEMS.resolve(
                "HytaleManagedCoopReleaseProjectionGateway.java"));
        int firstResolve = gateway.indexOf("ResidentRecord currentResident = currentResident(command)");
        int populationPrepare = gateway.indexOf("populations.prepareAsync(", firstResolve);
        int secondResolve = gateway.indexOf(
                "ResidentRecord currentResident = currentResident(command)", firstResolve + 1);
        int preparedRelease = gateway.indexOf("releases.release(", secondResolve);

        assertTrue(firstResolve >= 0 && populationPrepare > firstResolve);
        assertTrue(secondResolve > populationPrepare && preparedRelease > secondResolve);
        assertTrue(!gateway.substring(populationPrepare, preparedRelease)
                .contains("command.resident(),"));
    }
}
