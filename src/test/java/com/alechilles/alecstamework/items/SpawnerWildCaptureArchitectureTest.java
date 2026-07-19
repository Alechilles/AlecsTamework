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
        int capture = source.indexOf(
                "captureFromItemInteraction(player, itemStack, targetRef, captureBurstParticleSystem)",
                removeAura
        );

        assertTrue(complete >= 0);
        assertTrue(removeAura > complete);
        assertTrue(capture > removeAura);
    }

    @Test
    void captureBurstRunsOnlyAfterTransactionalCaptureApplies() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"
        ));
        int callback = source.indexOf("public void onApplied(String profileId,");
        int burst = source.indexOf("spawnCaptureSuccessParticle(captureBurstParticleSystem, context)", callback);
        int denied = source.indexOf("public void onDenied(String reason)", callback);

        assertTrue(callback >= 0);
        assertTrue(burst > callback);
        assertTrue(denied > burst);
    }

    @Test
    void captureChannelExposesACompletionBurstParticleField() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/interactions/TameworkCaptureChannelInteraction.java"
        ));

        assertTrue(source.contains("new KeyedCodec<>(\"CaptureBurstParticleSystem\", Codec.STRING)"));
        assertTrue(source.contains("captureBurstParticleSystem"));
        assertTrue(source.contains("case COMPLETE -> commandBuffer.run"));
    }

    @Test
    void captureChannelPropagatesAuthoredParticleTravelDuration() throws Exception {
        String interaction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/interactions/TameworkCaptureChannelInteraction.java"
        ));
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"
        ));

        assertTrue(interaction.contains(
                "new KeyedCodec<>(\"BeamNativeDurationSeconds\", Codec.DOUBLE)"
        ));
        assertTrue(interaction.contains("beamNativeDurationSeconds,"));
        assertTrue(handler.contains("double beamNativeDurationSeconds,"));
        assertTrue(handler.contains("beamNativeDurationSeconds,\n                        scaleBeamToTarget,"));
    }

    @Test
    void captureChannelCanReverseParticlesFromTargetToHeldItem() throws Exception {
        String interaction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/interactions/TameworkCaptureChannelInteraction.java"
        ));
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"
        ));

        assertTrue(interaction.contains("new KeyedCodec<>(\"BeamFromTarget\", Codec.BOOLEAN)"));
        assertTrue(interaction.contains("beamFromTarget,"));
        assertTrue(handler.contains("boolean beamFromTarget,"));
        assertTrue(handler.contains("beamFromTarget,\n                        channelDurationSeconds,"));
    }

    @Test
    void captureChannelPropagatesHomingMoteSettingsWithoutChangingTerminalGates() throws Exception {
        String interaction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/interactions/TameworkCaptureChannelInteraction.java"
        ));
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"
        ));

        assertTrue(interaction.contains("new KeyedCodec<>(\"HomingProjectileEnabled\", Codec.BOOLEAN)"));
        assertTrue(interaction.contains("new KeyedCodec<>(\"HomingProjectileModelId\", Codec.STRING)"));
        assertTrue(interaction.contains("new CaptureHomingProjectileSettings("));
        assertTrue(handler.contains("CaptureHomingProjectileSettings homingProjectileSettings"));
        assertTrue(handler.contains("config.getCaptureChannelAuraEffectId(),\n                        homingProjectileSettings"));
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

    @Test
    void channelBeginsBeforeTerminalHealthAndTranquilizerRequirementsPass() throws Exception {
        String interaction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/interactions/TameworkCaptureChannelInteraction.java"
        ));
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"
        ));
        String policy = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerCapturePolicyService.java"
        ));

        assertTrue(interaction.contains(
                "case BEGIN -> handler.canBeginCaptureChannelInteraction(player, targetRef, heldItem)"
        ));
        assertTrue(interaction.contains(
                "case COMPLETE -> handler.canCaptureInteraction(player, targetRef, heldItem)"
        ));
        assertTrue(handler.contains("capturePolicyService.canBeginCaptureChannel("));
        assertTrue(policy.contains(
                "enforceTerminalRequirements && !meetsHealthRequirement(targetRef, config, store)"
        ));
        assertTrue(policy.contains(
                "enforceTerminalRequirements && !hasRequiredEffect(targetRef, config, store)"
        ));
    }
}
