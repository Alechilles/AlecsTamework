package com.alechilles.alecstamework.items;

import com.hypixel.hytale.math.vector.Vector3d;
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

        assertEquals(2.5, clamped.x);
        assertEquals(11.0, clamped.y);
        assertEquals(4.5, clamped.z);
    }
}
