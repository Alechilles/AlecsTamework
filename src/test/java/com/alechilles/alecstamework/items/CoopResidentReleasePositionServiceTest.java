package com.alechilles.alecstamework.items;

import org.joml.Vector3d;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopResidentReleasePositionServiceTest {

    private final CoopResidentReleasePositionService service = new CoopResidentReleasePositionService();

    @Test
    void acceptsSpawnsUpToOneBlockBelowTheCoop() {
        assertTrue(service.isWithinVerticalLimit(12, new Vector3d(0.5, 11.0, 0.5)));
    }

    @Test
    void rejectsSpawnsMoreThanOneBlockBelowTheCoop() {
        assertFalse(service.isWithinVerticalLimit(12, new Vector3d(0.5, 10.99, 0.5)));
    }

    @Test
    void clampsFallbackPositionsToTheOneBlockVerticalLimit() {
        Vector3d clamped = service.clampToVerticalLimit(12, new Vector3d(2.5, 8.0, 4.5));

        assertEquals(2.5, clamped.x, 0.000001);
        assertEquals(11.0, clamped.y, 0.000001);
        assertEquals(4.5, clamped.z, 0.000001);
    }

    @Test
    void rotatesSpawnOffsetWithCoopYaw() {
        int rotationIndex = RotationTuple.of(Rotation.Ninety, Rotation.None).index();

        Vector3d rotated = service.rotateHorizontalOffset(rotationIndex, 0.0, 0.0, 3.0);

        assertEquals(3.0, rotated.x, 0.000001);
        assertEquals(0.0, rotated.y, 0.000001);
        assertEquals(0.0, rotated.z, 0.000001);
    }

    @Test
    void defaultsZeroOffsetForwardToTheCoopFront() {
        int rotationIndex = RotationTuple.of(Rotation.TwoSeventy, Rotation.None).index();

        Vector3d forward = service.resolveForwardDirection(rotationIndex, 0.0, 0.0);

        assertEquals(-1.0, forward.x, 0.000001);
        assertEquals(0.0, forward.y, 0.000001);
        assertEquals(0.0, forward.z, 0.000001);
    }
}
