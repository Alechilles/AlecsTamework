package com.alechilles.alecstamework.npc.actions;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static regression guards for the shared job-owned breeding runtime architecture. */
class BreedingRuntimeWiringTest {
    @Test
    void manualAndPassiveEntrypointsUseOneSharedBreedingServiceSeam() throws Exception {
        String manual = source("InteractionBreedingEffects.java");
        String passive = source("PassiveBreedingSweepService.java");

        assertTrue(manual.contains("TameworkBreedingServices.shared()"));
        assertTrue(passive.contains("TameworkBreedingServices.shared()"));
        assertFalse(passive.contains("PassiveBreedingBirthReservation"));
        assertFalse(passive.contains("claimReservations"));
        assertFalse(passive.contains("playerReservations"));
    }

    @Test
    void compatibilityFacadeDoesNotOwnRandomnessSpawningOrDelayedCallbacks() throws Exception {
        String facade = source("BreedingOffspringService.java");

        assertTrue(facade.contains("BreedingPairingCoordinator"));
        assertTrue(facade.contains("BreedingJobExecutionService"));
        assertTrue(facade.contains("executionService::failScheduledJob"));
        assertFalse(facade.contains("CompletableFuture"));
        assertFalse(facade.contains("Math.random"));
        assertFalse(facade.contains("spawnEntity"));
        assertFalse(facade.contains("scheduleWorldAction"));
    }

    @Test
    void delayedSchedulerCarriesJobIdAndExecutorConsumesOnlyPlannedRoles() throws Exception {
        String scheduler = source("HytaleBreedingJobScheduler.java");
        String runtime = source("BreedingHytaleJobRuntime.java");
        String childSpawn = source("BreedingPreparedChildSpawnService.java");
        String parentState = source("BreedingParentStateService.java");

        assertTrue(scheduler.contains("() -> dispatch(jobId)"));
        assertFalse(scheduler.contains("Ref<EntityStore>"));
        assertFalse(scheduler.contains("NPCEntity"));
        assertFalse(scheduler.contains("currentHandler.accept(jobId);\n            return;"));
        assertFalse(scheduler.contains("currentFailureHandler.accept(jobId);\n            return;"));
        assertTrue(childSpawn.contains("child.roleId()"));
        assertFalse(childSpawn.contains("resolveSpawnRole("));
        assertFalse(childSpawn.contains("rollOffspring("));
        assertFalse(parentState.contains("loadProfileByNpcUuid"));
        assertFalse(parentState.contains("NpcProfileRepository"));
        assertFalse(parentState.contains("java.lang.reflect"));
        assertFalse(parentState.contains("setAccessible"));
    }

    private static String source(String fileName) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions",
                fileName
        )).replace("\r\n", "\n");
    }
}
