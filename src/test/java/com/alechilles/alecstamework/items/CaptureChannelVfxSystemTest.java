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
    void beamPositiveXAxisRotatesOntoTargetVector() {
        assertBeamDirection(new Vector3d(8.0D, 3.0D, -5.0D));
        assertBeamDirection(new Vector3d(-4.0D, -2.0D, 7.0D));
    }

    @Test
    void channelUsesShortBoundedSegmentsAndTracksBothEndpoints() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CaptureChannelVfxSystem.java"
        ));

        assertTrue(source.contains("SEGMENT_MAX_DURATION_SECONDS = 0.18F"));
        assertTrue(source.contains("world.getEntityRef(session.playerUuid)"));
        assertTrue(source.contains("world.getEntityRef(session.targetUuid)"));
        assertTrue(source.contains("rotationForPositiveXBeam"));
        assertTrue(source.contains("ACTIVE.remove(session.playerUuid, session)"));
    }

    private static void assertBeamDirection(Vector3d targetDirection) {
        Vector3d actual = CaptureChannelVfxSystem.rotationForPositiveXBeam(targetDirection)
                .transform(new Vector3d(1.0D, 0.0D, 0.0D))
                .normalize();
        Vector3d expected = new Vector3d(targetDirection).normalize();
        assertTrue(actual.dot(expected) > 0.99999D,
                () -> "beam axis " + actual + " did not match target " + expected);
    }
}
