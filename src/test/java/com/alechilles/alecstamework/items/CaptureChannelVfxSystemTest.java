package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureChannelVfxSystemTest {
    @Test
    void beamScaleEndsAtTargetDistance() {
        assertEquals(0.2F, CaptureChannelVfxSystem.scaleForDistance(10.0D, 50.0D), 0.00001F);
        assertEquals(0.0F, CaptureChannelVfxSystem.scaleForDistance(10.0D, 0.0D), 0.00001F);
    }

    @Test
    void travelingOrbKeepsFixedScaleAndUsesDistanceForLifetime() {
        assertEquals(1.0F,
                CaptureChannelVfxSystem.particleScaleForDistance(7.5D, 15.0D, false),
                0.00001F);
        assertEquals(0.25F,
                CaptureChannelVfxSystem.particleMaxDurationForDistance(7.5D, 15.0D, 0.5D, false),
                0.00001F);
        assertEquals(0.5F,
                CaptureChannelVfxSystem.particleScaleForDistance(7.5D, 15.0D, true),
                0.00001F);
        assertEquals(0.5F,
                CaptureChannelVfxSystem.particleMaxDurationForDistance(7.5D, 15.0D, 0.5D, true),
                0.00001F);
        assertEquals(0.0F,
                CaptureChannelVfxSystem.particleMaxDurationForDistance(7.5D, 0.0D, 0.5D, false),
                0.00001F);
        assertEquals(0.0F,
                CaptureChannelVfxSystem.particleMaxDurationForDistance(7.5D, 15.0D, 0.0D, false),
                0.00001F);
    }

    @Test
    void slowTravelingOrbUsesAuthoredSpeedAcrossCaptureRange() {
        assertEquals(1.0F,
                CaptureChannelVfxSystem.particleMaxDurationForDistance(8.0D, 12.0D, 1.5D, false),
                0.00001F);
        assertEquals(1.5F,
                CaptureChannelVfxSystem.particleMaxDurationForDistance(12.0D, 12.0D, 1.5D, false),
                0.00001F);
    }

    @Test
    void beamPacketNegativeXAxisRotatesOntoTargetVector() {
        assertBeamDirection(new Vector3d(8.0D, 3.0D, -5.0D));
        assertBeamDirection(new Vector3d(-4.0D, -2.0D, 7.0D));
    }

    @Test
    void tallTargetEyeHeightDoesNotSuppressBeamInsideCaptureRange() {
        Vector3d playerRoot = new Vector3d(0.0D, 0.0D, 0.0D);
        Vector3d dragonRoot = new Vector3d(8.0D, 0.0D, 0.0D);
        Vector3d playerEye = new Vector3d(0.0D, 1.7D, 0.0D);
        Vector3d dragonEye = new Vector3d(8.0D, 12.0D, 0.0D);

        assertTrue(playerEye.distance(dragonEye) > 12.0D,
                "the historical eye-distance gate must reproduce the tall-dragon failure");
        assertTrue(CaptureChannelVfxSystem.isWithinConfiguredRange(playerRoot, dragonRoot, 12.0D));
    }

    @Test
    void beamTargetsBodyCenterInsteadOfFloatingAtEyeHeight() {
        assertEquals(0.27D, CaptureChannelVfxSystem.targetAnchorHeight(0.6D), 0.00001D);
        assertEquals(0.15D, CaptureChannelVfxSystem.targetAnchorHeight(0.0D), 0.00001D);
        assertEquals(2.5D, CaptureChannelVfxSystem.targetAnchorHeight(12.0D), 0.00001D);
    }

    @Test
    void beamStartsAtRightHandItemOffsetInsteadOfPlayerEyes() {
        Vector3d offset = CaptureChannelVfxSystem.heldItemOffset(0.0F, 0.0F);

        assertEquals(0.32D, offset.x, 0.00001D);
        assertEquals(-0.42D, offset.y, 0.00001D);
        assertEquals(-0.28D, offset.z, 0.00001D);
    }

    @Test
    void completionRetainsLockedTargetAfterVisualChannelEnds() {
        long visualEndsAtMs = 10_000L;
        long targetExpiresAtMs = CaptureChannelVfxSystem.targetLockExpiresAt(visualEndsAtMs);

        assertEquals(12_000L, targetExpiresAtMs);
        assertTrue(CaptureChannelVfxSystem.retainsTargetLock(visualEndsAtMs + 1_500L, targetExpiresAtMs),
                "interaction completion may arrive after the three-second visual cutoff");
        assertFalse(CaptureChannelVfxSystem.retainsTargetLock(targetExpiresAtMs, targetExpiresAtMs));
    }

    @Test
    void channelLaunchesIndividuallyBoundedParticlesAtWorldTickCadence() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CaptureChannelVfxSystem.java"
        ));

        assertEquals(50L, CaptureChannelVfxSystem.emissionIntervalMsForTests());
        assertEquals(0.5F,
                CaptureChannelVfxSystem.particleMaxDurationForDistance(15.0D, 15.0D, 0.5D, false));
        assertTrue(source.contains("world.getEntityRef(session.playerUuid)"));
        assertTrue(source.contains("world.getEntityRef(session.targetUuid)"));
        assertTrue(source.contains("session.nextEmitAtMs = nowMs + EMIT_INTERVAL_MS"));
        assertTrue(source.contains("rotationForBeamPacket"));
        assertTrue(source.contains("ACTIVE.remove(session.playerUuid, session)"));
    }

    private static void assertBeamDirection(Vector3d targetDirection) {
        Vector3d actual = CaptureChannelVfxSystem.rotationForBeamPacket(targetDirection)
                .transform(new Vector3d(-1.0D, 0.0D, 0.0D))
                .normalize();
        Vector3d expected = new Vector3d(targetDirection).normalize();
        assertTrue(actual.dot(expected) > 0.99999D,
                () -> "beam axis " + actual + " did not match target " + expected);
    }
}
