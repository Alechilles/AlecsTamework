package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureChannelVfxSystemTest {
    @Test
    void beamScaleEndsAtTargetDistance() {
        assertEquals(0.2F, CaptureChannelVfxSystem.scaleForDistance(10.0D, 50.0D), 0.00001F);
        assertEquals(0.0F, CaptureChannelVfxSystem.scaleForDistance(10.0D, 0.0D), 0.00001F);
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
    void channelUsesShortBoundedSegmentsAndTracksBothEndpoints() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CaptureChannelVfxSystem.java"
        ));

        assertTrue(source.contains("EMIT_INTERVAL_MS = 350L"));
        assertTrue(source.contains("SEGMENT_MAX_DURATION_SECONDS = 0.85F"));
        assertTrue(source.contains("world.getEntityRef(session.playerUuid)"));
        assertTrue(source.contains("world.getEntityRef(session.targetUuid)"));
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
