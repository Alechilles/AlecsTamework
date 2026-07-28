package com.alechilles.alecstamework.companion.capture.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the world-thread and NPC-authority rules of live tame-and-link capture. */
class HytaleCaptureTameLiveBoundaryArchitectureTest {
    private static final Path TARGET = Path.of(
            "src/main/java/com/alechilles/alecstamework/companion/capture/"
                    + "runtime/HytaleCaptureTameTargetGateway.java"
    );
    private static final Path ATTEMPT = Path.of(
            "src/main/java/com/alechilles/alecstamework/companion/capture/"
                    + "runtime/HytaleCompanionCaptureTameAttemptGateway.java"
    );

    @Test
    void targetMutationDetachesSpawnAuthorityAndRequestsDetachedRoleChange()
            throws Exception {
        String source = Files.readString(TARGET).replace("\r\n", "\n");

        assertTrue(source.contains(
                "removeIfPresent(target.reference(), target.markerType())"
        ));
        assertTrue(source.contains(
                "removeIfPresent(target.reference(), target.beaconType())"
        ));
        assertTrue(source.contains("updateSpawnTrackingState(false)"));
        assertTrue(source.contains(
                "setSpawnConfiguration(\n"
                        + "                AssetMapWithIndexes.NOT_FOUND"
        ));
        assertTrue(source.contains(
                "CompanionProgressionBootstrapService.ensureProgressionComponents("
        ));

        int roleChange = source.indexOf(
                "RoleChangeSystem.requestRoleChange("
        );
        int detached = source.indexOf(
                "                true,\n"
                        + "                store",
                roleChange
        );
        assertTrue(roleChange >= 0 && detached > roleChange);
    }

    @Test
    void deferredContinuationsResolveLiveStateOnlyOnTheWorldThread()
            throws Exception {
        String source = Files.readString(ATTEMPT).replace("\r\n", "\n");

        assertTrue(source.contains(
                "world.execute(() -> resumeInto(continuation, completion))"
        ));
        assertTrue(source.contains("store.assertThread()"));
        assertTrue(source.contains(
                "world.getEntityRef(request.source().actorUuid())"
        ));
        for (String forbidden : List.of(
                "Universe.get().getPlayers",
                "PlayerRef.getComponent",
                "CompletableFuture.supplyAsync",
                "CompletableFuture.runAsync(\n"
                        + "                continuation"
        )) {
            assertFalse(source.contains(forbidden), forbidden);
        }
    }
}
