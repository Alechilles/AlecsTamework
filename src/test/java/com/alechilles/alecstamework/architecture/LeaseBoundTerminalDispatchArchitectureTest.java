package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards lease-bound rejection callbacks for prepared population and relocation world work. */
class LeaseBoundTerminalDispatchArchitectureTest {
    private static final Path MAIN = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework"
    );

    @Test
    void everyPreparedPopulationHandoffUsesLeaseBoundDispatch() throws IOException {
        List<Path> prepared = List.of(
                MAIN.resolve("items/CommandPreparedRestoreSpawnService.java"),
                MAIN.resolve("items/NpcOwnedBatchSpawnService.java"),
                MAIN.resolve("items/SpawnerPreparedSpawnService.java"),
                MAIN.resolve("items/CoopPreparedReleaseSpawnService.java"),
                MAIN.resolve("items/CompanionPreparedSpawnService.java"),
                MAIN.resolve("npc/actions/BreedingPreparedPairingHandoffService.java"),
                MAIN.resolve("npc/actions/BreedingPairingEffectsService.java")
        );

        for (Path path : prepared) {
            String source = read(path);
            assertTrue(source.contains("LeaseBoundWorldDispatcher.execute("), path.toString());
            assertFalse(source.contains("world.execute("), path.toString());
        }

        String breeding = read(MAIN.resolve(
                "npc/actions/BreedingPreparedPairingHandoffService.java"
        ));
        assertTrue(breeding.contains(
                "() -> terminality.cancel(\"breeding-world-unavailable\")"
        ));
        String effects = read(MAIN.resolve(
                "npc/actions/BreedingPairingEffectsService.java"
        ));
        assertTrue(effects.contains("() -> fail(pairing)"));
    }

    @Test
    void spawnCommitAndSourceFinalizationDispatchOwnRejectionTerminality() throws IOException {
        String continuation = read(MAIN.resolve(
                "items/CompanionSpawnCommitContinuation.java"
        ));
        String preparedSpawn = read(MAIN.resolve(
                "items/CompanionPreparedSpawnService.java"
        ));
        String batchSpawn = read(MAIN.resolve("items/NpcOwnedBatchSpawnService.java"));
        String restoreSpawn = read(MAIN.resolve(
                "items/CommandPreparedRestoreSpawnService.java"
        ));

        assertTrue(continuation.contains("dispatcher.dispatch(task, rejected)"));
        assertTrue(continuation.contains("spawn-commit-continuation-world-unavailable"));
        assertTrue(continuation.contains("spawn-source-finalization-world-unavailable"));
        assertTrue(continuation.contains("void dispatch(@Nonnull Runnable task, @Nonnull Runnable rejected)"));
        assertTrue(continuation.contains("Consumer<String> dispatchRejected"));
        assertTrue(preparedSpawn.contains("callbacks.onWorldDispatchRejected(reason)"));
        assertTrue(preparedSpawn.contains("must not access live ECS or player state"));
        assertTrue(batchSpawn.contains("tracker.abandonWithoutCompletion("));

        int restoreCancel = restoreSpawn.indexOf("private static void cancelPrepared(");
        int restoreDispatch = restoreSpawn.indexOf("private static void dispatch(", restoreCancel);
        String restoreRejection = restoreSpawn.substring(restoreCancel, restoreDispatch);
        assertFalse(restoreRejection.contains("callbacks.onDenied("));
    }

    @Test
    void relocationAdmissionAndPostRemoveTransferCannotUseRawWorldDispatch() throws IOException {
        String access = read(MAIN.resolve("items/CommandRelocationWorldAccess.java"));
        String gate = read(MAIN.resolve("items/CommandRelocationAdmissionGate.java"));
        String service = read(MAIN.resolve("items/CommandNpcRelocationService.java"));

        assertTrue(access.contains("LeaseBoundWorldDispatcher.execute("));
        assertFalse(access.contains("world.execute("));
        assertTrue(gate.contains("dispatcher.dispatch(completion, rejected)"));
        assertFalse(gate.contains("dispatcher.accept(completion)"));
        assertTrue(service.contains("leaseBoundDispatcher(world)"));
        assertFalse(service.contains("world::execute"));

        int sourceRemove = service.indexOf("sourceStore.removeEntity(sourceRef, RemoveReason.UNLOAD)");
        int destinationDispatch = service.indexOf(
                "worldAccess.execute(destinationWorld", sourceRemove
        );
        assertTrue(sourceRemove >= 0 && destinationDispatch > sourceRemove);
        String postRemove = service.substring(sourceRemove, service.indexOf(
                "private void restoreSourceEntityAndApplyFailure", destinationDispatch
        ));
        assertTrue(postRemove.contains("terminalizeDrainedTransferAsLost("));
        assertFalse(postRemove.contains("restoreSourceEntityAndTerminalize("));

        int lostHandler = service.indexOf("private void terminalizeDrainedTransferAsLost(");
        int nextMethod = service.indexOf("private void handleSourceRemoveFailure(", lostHandler);
        String handler = service.substring(lostHandler, nextMethod);
        assertTrue(handler.contains("commitUnconfirmedRelocationAsLost("));
        assertTrue(handler.contains("null, npcUuid, pending"));
        assertFalse(handler.contains("worldAccess.isEntityPresent("));
        assertFalse(handler.contains("worldAccess.restoreSourceEntity("));
    }

    @Test
    void coopWatchdogCleanupUsesCapturedThreadSafeFlightKey() throws IOException {
        String system = read(MAIN.resolve(
                "items/CommandCoopManagedWildCaptureSystem.java"
        ));

        assertTrue(system.contains("String terminalFlightKey = releaseFlightKey(world, slotContext)"));
        assertTrue(system.contains("completeReleaseFlight(terminalFlightKey)"));
        assertTrue(system.contains("May run from a lease watchdog"));
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
