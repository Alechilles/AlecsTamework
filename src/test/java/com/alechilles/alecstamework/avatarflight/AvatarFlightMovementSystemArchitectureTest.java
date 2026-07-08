package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightMovementSystemArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "avatarflight",
            "AvatarFlightMovementSystem.java"
    );

    @Test
    void controllerStateComesFromAvatarFlightComponent() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("AvatarFlightController.State.from(flight)"),
                "avatar flight must use its stored controller velocity, not physics reconciliation velocity");
        assertFalse(source.contains("currentVelocity == null ? flight.getVelocityX()"),
                "feeding live physics velocity back into the controller caused glide velocity oscillation");
    }

    @Test
    void movementSystemPersistsHudTelemetryBeforeHudSystemRuns() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("flight.setHudPitchRadians(output.visualPitchRadians())"),
                "pitch readout should come from the same controller output that drives visual pitch");
        assertTrue(source.contains("flight.setHudTargetSpeedRatio(output.hudTargetSpeedRatio())"),
                "speed target marker should be persisted for AvatarFlightHudSystem, which runs after movement");
    }

    @Test
    void freshPacketInputOwnsGroundedState() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("boolean onGround = stale ? states == null || states.onGround : input.isOnGround()"),
                "fresh packet-captured grounded state must win, but stale cached grounded state must not latch");
    }

    @Test
    void visualPoseWritesPitchAndRollThroughCommandBuffer() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("applyVisualPose(ref, commandBuffer, controllerInput, output)"),
                "avatar flight must update the transformed model pose each active tick, including grounded reset ticks");
        assertTrue(source.contains("if (transform != null && transform.getRotation() != null)"),
                "missing transform rotation must not block the HeadRotation pose write");
        assertTrue(source.contains("transform.getRotation().setPitch((float) output.visualPitchRadians())"));
        assertTrue(source.contains("transform.getRotation().setRoll((float) output.visualRollRadians())"));
        assertTrue(source.contains("commandBuffer.putComponent(ref, transformType, transform)"),
                "runtime systems must write component mutations through the command buffer");
        assertTrue(source.contains("headRotation.getRotation().setPitch((float) output.visualPitchRadians())"),
                "visible player-model pitch may follow HeadRotation rather than Transform rotation");
        assertTrue(source.contains("headRotation.getRotation().setRoll((float) output.visualRollRadians())"));
        assertTrue(source.contains("commandBuffer.putComponent(ref, headRotationType, headRotation)"));
    }

    @Test
    void visualPoseIsNotGuardedByVelocityApplication() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        int poseIndex = source.indexOf("applyVisualPose(ref, commandBuffer, controllerInput, output)");
        int velocityGuardIndex = source.indexOf("if (output.applyVelocity())");

        assertTrue(poseIndex >= 0, "avatar flight must write visual pose while the component is active");
        assertTrue(velocityGuardIndex >= 0, "test expects the velocity guard to remain present");
        assertTrue(poseIndex < velocityGuardIndex,
                "pose writes must also run on grounded ticks so stale pitch/roll cannot stick after landing");
    }

    @Test
    void ownerClientFlyingStateIsSyncedThroughPlayerApplyMovementStates() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("Player.applyMovementStates"),
                "self-client animation state needs the same saved flying-state packet creative flight uses");
        assertTrue(source.contains("new SavedMovementStates(desiredFlying)"),
                "avatar flight should sync true while flying and false when returning to grounded");
        assertTrue(source.contains("flight.isClientFlyingSynced() != desiredFlying"),
                "the self-client saved flight packet should only be sent when the desired flying state changes");
    }

    @Test
    void nativeActivationCapabilityIsDisabledDuringCustomFlight() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("syncActivationCapability(ref, commandBuffer, output.mode() == AvatarFlightMode.GROUNDED)"),
                "client canFly should be available only while waiting for explicit airborne activation");
        assertTrue(source.contains("AvatarFlightActivationCapability.setGroundedProbeEnabled("),
                "avatar flight should not reuse the standalone debug client-flight probe");
        int activationIndex = source.indexOf("syncActivationCapability(ref, commandBuffer");
        int ownerFlyingIndex = source.indexOf("syncOwnerClientFlyingState(ref, commandBuffer");
        assertTrue(activationIndex >= 0 && ownerFlyingIndex > activationIndex,
                "native canFly should be disabled before syncing owner flying animation for custom flight");
    }

    @Test
    void avatarFlightForcesFlyingMovementStateInsteadOfFallState() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("applyFlightMovementState(ref, commandBuffer, output)"),
                "avatar flight must override player movement states while applying custom air velocity");
        assertTrue(source.contains("states.flying = true"));
        assertTrue(source.contains("states.falling = false"));
        assertTrue(source.contains("states.fallingFar = false"));
        assertTrue(source.contains("states.horizontalIdle = output.horizontalIdle()"));
        assertTrue(source.contains("states.sprinting = states.sprinting || output.fastFlight()"),
                "avatar flight can still mark fast flight as sprinting for animation state");
    }

    @Test
    void movementStateSprintDoesNotDriveBoostIntent() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertFalse(source.contains("boolean liveSprint = states != null && states.sprinting"),
                "avatar flight writes sprinting for fast-flight animation, so reading it back as boost input repeats boosts on cooldown");
        assertFalse(source.contains("input.isSprinting()"),
                "held sprint state must not be treated as a level-triggered boost intent");
        assertTrue(source.contains("input.consumeSprintBoost("),
                "packet sprint should be reduced to a one-shot rising-edge boost event");
    }

    @Test
    void inactiveAvatarFlightUsesAirborneJumpPressInsteadOfHeldGroundJump() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("boolean activeFlight = flight.getMode() != AvatarFlightMode.GROUNDED"),
                "movement input conversion must know whether the controller is already in avatar-flight mode");
        assertTrue(source.contains("input.consumeAirborneJumpPress("),
                "inactive avatar flight should enter only from a one-shot jump press observed after already airborne");
        assertTrue(source.contains("? reinsFlap || (!stale && input.isJumping())"),
                "held jump should continue to repeat flaps only after avatar flight is already active");
    }

    @Test
    void reinsBoostActionCanDriveBoostIntentWithoutSprintDetection() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("input.consumeReinsBoost("),
                "Flightmaster's Reins Q action should feed the existing boost intent path");
        assertTrue(source.contains("reinsBoost || sprintBoost"),
                "Q boost must not depend on unreliable airborne sprint detection");
    }

    @Test
    void avatarFlightExplicitlyDrivesTransformedPlayerMovementAnimation() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("applyMovementAnimation(ref, commandBuffer, flight, config, output)"),
                "movement-state flags alone do not reliably drive the owner client's transformed-player animation");
        assertTrue(source.contains("AnimationUtils.playAnimation(ref, AnimationSlot.Movement"),
                "avatar flight must use the generic entity animation packet, not NPC-only helpers");
        assertTrue(source.contains("config.getAnimation().animationFor(output.horizontalIdle(), output.fastFlight())"),
                "animation names should stay config-driven instead of hardcoded in the system");
        assertTrue(source.contains("flight.setMovementAnimationId(animationId)"),
                "repeated movement animation packets should be gated by tracked avatar-flight state");
    }

    @Test
    void avatarFlightSuppressesPlayerOverlayAnimationSlotsWhileActive() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("suppressPlayerOverlayAnimations(ref, commandBuffer, flight, config)"),
                "transformed-player flight should suppress held-item/combat overlay slots while active");
        assertTrue(source.contains("animation.isSuppressNonMovementAnimations()"),
                "suppression should be config-driven for unsafe model-swap experiments");
        assertTrue(source.contains("!isPoseSlot(AnimationSlot.Status, pitchSlot, rollSlot)"),
                "configured pose-animation slots must not be erased by overlay suppression");
        assertTrue(source.contains("AnimationUtils.stopAnimation(ref, AnimationSlot.Action, true, commandBuffer)"),
                "Action slot is where item/combat animations can be applied to the wrong transformed model");
        assertTrue(source.contains("AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, commandBuffer)"),
                "Status slot should be cleared so held-item/status overlays do not persist on the transformed model");
        assertTrue(source.contains("AnimationUtils.stopAnimation(ref, AnimationSlot.Emote, true, commandBuffer)"),
                "Emote slot should be cleared by default because player emotes are authored for the player rig");
        assertTrue(source.contains("flight.setNextSuppressedAnimationAtMs(now + animation.getSuppressionIntervalMs())"),
                "slot suppression should be throttled instead of spamming packets every tick");
    }

    @Test
    void avatarFlightCanDriveOwnerSafePoseAnimationSlots() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("applyPoseAnimations(ref, commandBuffer, flight, config, output)"),
                "owner-visible pitch/bank must use animation packets instead of self TransformUpdate correction");
        assertTrue(source.contains("AvatarFlightPoseAnimationCatalog.pitchPoseAnimationFor("),
                "pitch pose selection should use the standard/catalog-driven breakpoint grid");
        assertTrue(source.contains("AvatarFlightPoseAnimationCatalog.rollPoseAnimationFor("),
                "roll pose selection should use the standard/catalog-driven breakpoint grid");
        assertTrue(source.contains("if (pitchSlot == rollSlot)"),
                "same-slot pose configs must drive one combined pose animation instead of two competing packets");
        assertTrue(source.contains("AvatarFlightPoseAnimationCatalog.sharedPoseAnimationFor("),
                "shared pose slots should preserve pitch and bank through a single selected animation");
        assertTrue(source.contains("AnimationUtils.playAnimation(ref, slot, animationId, true, commandBuffer)"),
                "pose animations must be sent to the owner client through the safe animation channel");
        assertTrue(source.contains("AnimationSlot.VALUES"),
                "pose animation slots should be parsed from config without hardcoding only one slot");
    }

    @Test
    void debugLogFormatsMovementStateFlags() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("formatMovementStates(states)"),
                "debug logs should print movement-state flags, not MovementStates object identities");
        assertFalse(source.contains("states.toString()"),
                "object identity logs do not help diagnose client animation state");
    }

    @Test
    void avatarFlightRunsAfterBaseMovementStatesBeforeAnimationTracking() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("Order.AFTER, MovementStatesSystems.TickingSystem.class"),
                "avatar flight animation states must run after base movement-state derivation");
        assertTrue(source.contains("Order.BEFORE, ModelSystems.AnimationEntityTrackerUpdate.class"),
                "avatar flight animation states must be visible before model animation tracking");
    }
}
