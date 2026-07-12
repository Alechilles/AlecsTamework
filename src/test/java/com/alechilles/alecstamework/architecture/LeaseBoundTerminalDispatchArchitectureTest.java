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
                MAIN.resolve("npc/actions/BreedingPreparedPairingHandoffService.java")
        );

        for (Path path : prepared) {
            String source = read(path);
            assertTrue(source.contains("LeaseBoundWorldDispatcher.execute("), path.toString());
            assertFalse(source.contains("world.execute("), path.toString());
        }

        String breeding = read(prepared.get(prepared.size() - 1));
        assertTrue(breeding.contains(
                "() -> terminality.cancel(\"breeding-world-unavailable\")"
        ));
    }

    @Test
    void spawnCommitAndSourceFinalizationDispatchOwnRejectionTerminality() throws IOException {
        String continuation = read(MAIN.resolve(
                "items/CompanionSpawnCommitContinuation.java"
        ));

        assertTrue(continuation.contains("dispatcher.dispatch(task, rejected)"));
        assertTrue(continuation.contains("spawn-commit-continuation-world-unavailable"));
        assertTrue(continuation.contains("spawn-source-finalization-world-unavailable"));
        assertTrue(continuation.contains("void dispatch(@Nonnull Runnable task, @Nonnull Runnable rejected)"));
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
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
