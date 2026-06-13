package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class PositionTargetReservationCacheTest {
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
}
