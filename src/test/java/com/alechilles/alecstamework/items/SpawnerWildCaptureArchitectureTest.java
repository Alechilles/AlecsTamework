package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerWildCaptureArchitectureTest {
    @Test
    void wildCaptureAssignsOwnershipInsideCapturedLifecycleMutation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerCaptureFinalizerService.java"
        ));
        int wildBranch = source.indexOf("if (config.isCaptureTamesTarget())");
        int assignPlayer = source.indexOf("retainedOwnerId = player.getUuid()", wildBranch);
        int newOwnership = source.indexOf("OwnerPopulationOperation.NEW_OWNERSHIP", assignPlayer);
        int capturedLifecycle = source.indexOf("CompanionLifecycleState.CAPTURED", newOwnership);
        int durableExpectation = source.indexOf(
                "prepared.expectedLiveOwnerId(), prepared.retainedOwnerId()",
                capturedLifecycle
        );

        assertTrue(wildBranch >= 0);
        assertTrue(assignPlayer > wildBranch);
        assertTrue(newOwnership > assignPlayer);
        assertTrue(capturedLifecycle > newOwnership);
        assertTrue(durableExpectation > capturedLifecycle);
    }

    @Test
    void channelCompletionRemovesAuraBeforeRevalidatingCapture() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"
        ));
        int complete = source.indexOf("boolean completeCaptureChannel(");
        int removeAura = source.indexOf("endCaptureChannel(player, targetRef, itemStack)", complete);
        int capture = source.indexOf("captureFromItemInteraction(player, itemStack, targetRef)", removeAura);

        assertTrue(complete >= 0);
        assertTrue(removeAura > complete);
        assertTrue(capture > removeAura);
    }

    @Test
    void channelLocksInitialTargetBeforeCompletionAndCancel() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/interactions/TameworkCaptureChannelInteraction.java"
        ));
        int nonBegin = source.indexOf("phase != Phase.BEGIN");
        int lockedTarget = source.indexOf("CaptureChannelVfxSystem.resolveTarget", nonBegin);
        int contextTarget = source.indexOf("context.getTargetEntity()", lockedTarget);

        assertTrue(nonBegin >= 0);
        assertTrue(lockedTarget > nonBegin);
        assertTrue(contextTarget > lockedTarget);
    }
}
