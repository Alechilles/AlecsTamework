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
    void avatarFlightForcesFlyingMovementStateInsteadOfFallState() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("applyFlightMovementState(ref, commandBuffer, output)"),
                "avatar flight must override player movement states while applying custom air velocity");
        assertTrue(source.contains("states.flying = true"));
        assertTrue(source.contains("states.falling = false"));
        assertTrue(source.contains("states.fallingFar = false"));
        assertTrue(source.contains("states.horizontalIdle = output.horizontalIdle()"));
        assertTrue(source.contains("states.sprinting = output.fastFlight()"));
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
