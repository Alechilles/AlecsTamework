package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void waterTargetMissCacheIsLongEnoughToThrottleRepeatedFullScans() {
        assertTrue(SensorTameworkNeedsResourceTarget.targetCacheTtlMs(false) >= 3_000L);
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
}
