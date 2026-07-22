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
    void reverseBeamTravelsFromNpcAnchorToHeldItem() {
        Vector3d heldItem = new Vector3d(1.0D, 2.0D, 3.0D);
        Vector3d npcAnchor = new Vector3d(8.0D, 1.0D, -4.0D);

        assertEquals(npcAnchor, CaptureChannelVfxSystem.beamOrigin(heldItem, npcAnchor, true));
        assertEquals(heldItem, CaptureChannelVfxSystem.beamDestination(heldItem, npcAnchor, true));
        assertEquals(heldItem, CaptureChannelVfxSystem.beamOrigin(heldItem, npcAnchor, false));
        assertEquals(npcAnchor, CaptureChannelVfxSystem.beamDestination(heldItem, npcAnchor, false));
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
    void orphanSweepCoversDisconnectTransferAndTimeout() {
        assertFalse(CaptureChannelVfxSystem.shouldSweepOrphanedSession(
                9_999L, 10_000L, true, true));
        assertTrue(CaptureChannelVfxSystem.shouldSweepOrphanedSession(
                9_999L, 10_000L, true, false));
        assertTrue(CaptureChannelVfxSystem.shouldSweepOrphanedSession(
                9_999L, 10_000L, false, true));
        assertTrue(CaptureChannelVfxSystem.shouldSweepOrphanedSession(
                10_000L, 10_000L, false, false));
    }

    @Test
    void channelSupportsLegacyParticlesAndHomingMoteCadence() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CaptureChannelVfxSystem.java"
        ));

        assertEquals(50L, CaptureChannelVfxSystem.emissionIntervalMsForTests());
        assertEquals(120L, CaptureChannelVfxSystem.homingEmissionIntervalMsForTests(
                new CaptureHomingProjectileSettings(
                        true, "Capture_Mote", 0.12D, 8.0D, 0.0D, 0.18D, 2.0D, 16
                )
        ));
        assertEquals(0.5F,
                CaptureChannelVfxSystem.particleMaxDurationForDistance(15.0D, 15.0D, 0.5D, false));
        assertTrue(source.contains("chunk.getReferenceTo(index)"));
        assertTrue(source.contains("world.getEntityRef(session.targetUuid)"));
        assertTrue(source.contains("session.nextEmitAtMs = nowMs + session.emissionIntervalMs()"));
        assertTrue(source.contains("HomingVisualProjectileSpawner.spawn("));
        assertTrue(source.contains("HomingVisualProjectileSpawner.spawn(\n                    commandBuffer,"));
        assertFalse(source.contains("HomingVisualProjectileSpawner.spawn(\n                    store,"));
        assertTrue(source.contains("HomingVisualProjectileSessionRegistry.activate("));
        assertTrue(source.contains("HomingVisualProjectileSessionRegistry.deactivate("));
        assertTrue(source.contains("rotationForBeamPacket"));
        assertTrue(source.contains("ACTIVE.remove(session.playerUuid, session)"));
        String plugin = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/Tamework.java"
        ));
        assertTrue(plugin.contains("new CaptureChannelSessionCleanupSystem()"));
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
