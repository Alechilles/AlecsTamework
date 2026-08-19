package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class SensorTameworkNeedsResourceTargetItemIdsTest {
    @Test
    void mergeItemIdsTrimsBlanksAndKeepsFirstCaseInsensitiveMatch() {
        String[] merged = SensorTameworkNeedsResourceTarget.mergeItemIds(
                new String[]{" hytale:Apple ", "", null, "mod:Hay"},
                new String[]{"HYTALE:apple", "mod:Seed", " mod:hay "}
        );

        assertArrayEquals(new String[]{"hytale:Apple", "mod:Hay", "mod:Seed"}, merged);
    }

    @Test
    void mergeItemIdsReturnsEmptyArrayWhenNoUsableIdsExist() {
        assertArrayEquals(
                new String[0],
                SensorTameworkNeedsResourceTarget.mergeItemIds(new String[]{" ", null}, new String[0])
        );
    }

    @Test
    void waterFallbackSearchOnlyRunsWhenFallbackRangeExpandsSearchArea() {
        assertFalse(SensorTameworkNeedsResourceTarget.shouldRunFallbackWaterSearch(16.0, 4.0));
        assertFalse(SensorTameworkNeedsResourceTarget.shouldRunFallbackWaterSearch(16.0, 16.0));
        assertTrue(SensorTameworkNeedsResourceTarget.shouldRunFallbackWaterSearch(4.0, 16.0));
    }

    @Test
    void activeSeekVerticalScanExpandsToBoundedSearchRange() {
        assertEquals(16, SensorTameworkNeedsResourceTarget.activeSeekVerticalScanRadius(2, 16.0));
        assertEquals(6, SensorTameworkNeedsResourceTarget.activeSeekVerticalScanRadius(6, 4.0));
        assertEquals(
                SensorTameworkNeedsResourceTarget.maxActiveSeekVerticalScanRadiusForTests(),
                SensorTameworkNeedsResourceTarget.activeSeekVerticalScanRadius(2, 64.0)
        );
        assertEquals(2, SensorTameworkNeedsResourceTarget.activeSeekVerticalScanRadius(2, Double.NaN));
    }

    @Test
    void targetMissCacheIsShortEnoughForMovingNpcs() {
        assertTrue(SensorTameworkNeedsResourceTarget.targetCacheTtlMs(false) > 0L);
        assertTrue(SensorTameworkNeedsResourceTarget.targetCacheTtlMs(false) <= 1_000L);
    }

    @Test
    void preflightRejectCacheIsShorterThanMovementFailureSuppression() {
        assertTrue(SensorTameworkNeedsResourceTarget.preflightRejectTtlSecondsForTests() > 0.0);
        assertTrue(SensorTameworkNeedsResourceTarget.preflightRejectTtlSecondsForTests() < 10.0);
    }

    @Test
    void targetCacheIsScopedToNpcBlockPosition() {
        assertTrue(SensorTameworkNeedsResourceTarget.targetCacheBlockMatchesForTests(
                new Vector3d(10.2, 64.0, -3.8),
                new Vector3d(10.9, 64.9, -3.1)
        ));
        assertFalse(SensorTameworkNeedsResourceTarget.targetCacheBlockMatchesForTests(
                new Vector3d(10.2, 64.0, -3.8),
                new Vector3d(11.0, 64.0, -3.8)
        ));
    }

    @Test
    void rejectedNeedsTargetIsNpcResourceSpecificAndExpires() {
        SensorTameworkNeedsResourceTarget.clearRejectedTargetsForTests();
        UUID npc = new UUID(0L, 42L);
        Vector3d target = new Vector3d(10.25, 64.05, -3.75);

        assertTrue(SensorTameworkNeedsResourceTarget.rejectTargetForTests(
                npc,
                "FoodContainer",
                target,
                5.0,
                1_000L
        ));
        assertTrue(SensorTameworkNeedsResourceTarget.isTargetRejectedForTests(
                npc,
                "FoodContainer",
                new Vector3d(10.75, 64.95, -3.25),
                5_999L
        ));
        assertFalse(SensorTameworkNeedsResourceTarget.isTargetRejectedForTests(
                npc,
                "Water",
                target,
                5_999L
        ));
        assertFalse(SensorTameworkNeedsResourceTarget.isTargetRejectedForTests(
                npc,
                "FoodContainer",
                target,
                6_000L
        ));
    }

    @Test
    void rejectedNeedsTargetCacheStaysBounded() {
        SensorTameworkNeedsResourceTarget.clearRejectedTargetsForTests();
        UUID npc = new UUID(0L, 84L);
        int maxEntries = SensorTameworkNeedsResourceTarget.rejectedTargetMaxEntriesForTests();

        for (int i = 0; i < maxEntries + 25; i++) {
            assertTrue(SensorTameworkNeedsResourceTarget.rejectTargetForTests(
                    npc,
                    "FoodContainer",
                    new Vector3d(i, 64.0, 0.0),
                    120.0,
                    1_000L + i
            ));
        }

        assertTrue(SensorTameworkNeedsResourceTarget.rejectedTargetCountForTests() <= maxEntries);
    }

    @Test
    void autoRejectedNeedsTargetAppliesToFoodAndWater() {
        SensorTameworkNeedsResourceTarget.clearRejectedTargetsForTests();
        UUID npc = new UUID(0L, 126L);
        Vector3d target = new Vector3d(3.0, 64.0, 9.0);

        assertTrue(SensorTameworkNeedsResourceTarget.rejectTargetForTests(
                npc,
                "Auto",
                target,
                5.0,
                1_000L
        ));

        assertTrue(SensorTameworkNeedsResourceTarget.isTargetRejectedForTests(npc, "Water", target, 1_001L));
        assertTrue(SensorTameworkNeedsResourceTarget.isTargetRejectedForTests(npc, "FoodContainer", target, 1_001L));
    }

    @Test
    void reservedNeedsTargetSuppressesOtherNpcUntilReleased() {
        SensorTameworkNeedsResourceTarget.clearTargetReservationsForTests();
        UUID owner = new UUID(0L, 200L);
        UUID other = new UUID(0L, 201L);
        Vector3d target = new Vector3d(-776.5, 122.65, 459.5);

        assertTrue(SensorTameworkNeedsResourceTarget.reserveTargetForTests(
                owner,
                "test-world",
                "Water",
                target,
                1_000L
        ));

        assertFalse(SensorTameworkNeedsResourceTarget.isTargetReservedByOtherForTests(
                owner,
                "test-world",
                "Water",
                target,
                1_001L
        ));
        assertTrue(SensorTameworkNeedsResourceTarget.isTargetReservedByOtherForTests(
                other,
                "test-world",
                "Water",
                target,
                1_001L
        ));

        SensorTameworkNeedsResourceTarget.releaseTargetForTests(owner, "test-world", "Water", target);
        assertFalse(SensorTameworkNeedsResourceTarget.isTargetReservedByOtherForTests(
                other,
                "test-world",
                "Water",
                target,
                1_002L
        ));
    }
}
