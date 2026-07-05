package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightOwnerPoseVisualSystemArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "avatarflight",
            "AvatarFlightOwnerPoseVisualSystem.java"
    );

    @Test
    void ownerPoseSyncQueuesTransformUpdateToSelfViewer() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("EntityTrackerSystems.EntityViewer"),
                "avatar flight must bypass vanilla transform sync's self-update skip");
        assertTrue(source.contains("AvatarFlightInputComponent"),
                "owner pose sync must use fresh packet-derived look input instead of stale transform yaw");
        assertTrue(source.contains("input.isStale(System.currentTimeMillis(), resolveIntentTimeoutMs(flight))"),
                "owner pose sync should skip stale look input instead of fighting mouse look");
        assertTrue(source.contains("viewer.queueUpdate(ref, new TransformUpdate"),
                "the owner client needs an explicit transform update for pitch and roll");
        assertTrue(source.contains("modelTransform.bodyOrientation = PositionUtil.toDirectionPacket(createBodyRotation(transform, input))"),
                "body orientation must carry the server-authored roll and pitch while preserving live look yaw");
        assertTrue(source.contains("modelTransform.lookOrientation = PositionUtil.toDirectionPacket(createLookRotation(input))"),
                "look orientation should use live pitch/yaw with no injected visual roll");
        assertTrue(source.contains("bodyRotation.setYaw((float) input.getYawRadians())"),
                "body yaw should come from fresh owner input to avoid mouse snapback");
        assertTrue(source.contains("new Rotation3f((float) input.getPitchRadians(), (float) input.getYawRadians(), 0.0f)"),
                "look roll should stay zero so model banking does not become camera roll");
    }

    @Test
    void ownerPoseSyncRunsAfterVanillaTransformTracking() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("Order.AFTER, TransformSystems.EntityTrackerUpdate.class"),
                "owner pose sync must run after vanilla tracking so it can fill the self-update gap");
        assertTrue(source.contains("return EntityTrackerSystems.QUEUE_UPDATE_GROUP"),
                "owner pose sync should queue the same update type in the normal tracker update phase");
    }
}
