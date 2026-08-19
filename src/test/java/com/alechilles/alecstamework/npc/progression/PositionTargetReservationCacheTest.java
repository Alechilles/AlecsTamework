package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PositionTargetReservationCacheTest {
    @AfterEach
    void clearReservations() {
        PositionTargetReservationCache.clearForTests();
    }

    @Test
    void activeReservationBlocksOtherNpcUntilReleasedOrExpired() {
        PositionTargetReservationCache.clearForTests();
        UUID owner = new UUID(0L, 1L);
        UUID other = new UUID(0L, 2L);
        Vector3d target = new Vector3d(-776.5, 122.65, 459.5);

        assertTrue(PositionTargetReservationCache.reserve(owner, "world-a", "Water", target, 20.0, 1_000L));
        assertFalse(PositionTargetReservationCache.isReservedByOther(owner, "world-a", "Water", target, 1_001L));
        assertTrue(PositionTargetReservationCache.isReservedByOther(other, "world-a", "Water", target, 1_001L));

        PositionTargetReservationCache.release(owner, "world-a", "Water", target);
        assertFalse(PositionTargetReservationCache.isReservedByOther(other, "world-a", "Water", target, 1_002L));

        assertTrue(PositionTargetReservationCache.reserve(owner, "world-a", "Water", target, 1.0, 2_000L));
        assertFalse(PositionTargetReservationCache.isReservedByOther(other, "world-a", "Water", target, 3_000L));
    }

    @Test
    void reservationIsScopedByWorldAndResourceLabel() {
        PositionTargetReservationCache.clearForTests();
        UUID owner = new UUID(0L, 3L);
        UUID other = new UUID(0L, 4L);
        Vector3d target = new Vector3d(10.25, 64.05, -3.75);

        assertTrue(PositionTargetReservationCache.reserve(owner, "world-a", "FoodContainer", target, 20.0, 1_000L));

        assertTrue(PositionTargetReservationCache.isReservedByOther(other, "world-a", "FoodContainer", target, 1_001L));
        assertFalse(PositionTargetReservationCache.isReservedByOther(other, "world-b", "FoodContainer", target, 1_001L));
        assertFalse(PositionTargetReservationCache.isReservedByOther(other, "world-a", "Water", target, 1_001L));
    }

    @Test
    void fullCacheRefusesNewReservationWithoutEvictingActiveOwner() {
        PositionTargetReservationCache.clearForTests();
        UUID originalOwner = new UUID(0L, 5L);
        UUID other = new UUID(0L, 6L);
        Vector3d originalTarget = new Vector3d(-10.5, 64.5, -10.5);
        assertTrue(PositionTargetReservationCache.reserve(
                originalOwner, "world-a", "Water", originalTarget, 120.0, 1_000L
        ));
        for (int i = 1; i < PositionTargetReservationCache.MAX_ENTRIES; i++) {
            assertTrue(PositionTargetReservationCache.reserve(
                    new UUID(7L, i),
                    "world-a",
                    "Water",
                    new Vector3d(i, 64.5, 0.5),
                    120.0,
                    1_000L
            ));
        }

        assertFalse(PositionTargetReservationCache.reserve(
                other, "world-a", "Water", new Vector3d(99_999.5, 64.5, 0.5), 120.0, 1_001L
        ));
        assertTrue(PositionTargetReservationCache.isReservedByOther(
                other, "world-a", "Water", originalTarget, 1_001L
        ));
    }
}
