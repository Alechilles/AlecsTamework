package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.progression.CompanionNeedsEnvironmentService;
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
    void foodConsumeRangeSearchResolvesToCurrentPosition() {
        Vector3d currentPosition = new Vector3d(10.0, 64.0, 2.0);
        CompanionNeedsEnvironmentService.FoodTargetSearchResult result =
                new CompanionNeedsEnvironmentService.FoodTargetSearchResult(null, true, true);

        assertEquals(
                "food_already_in_consume_range",
                SensorTameworkNeedsResourceTarget.resolveFoodSearchReasonForTests(result, currentPosition)
        );
        assertSame(
                currentPosition,
                SensorTameworkNeedsResourceTarget.resolveFoodSearchTargetForTests(result, currentPosition)
        );
    }

    @Test
    void foodSourceOutsideConsumeRangeStillReportsNoStandTarget() {
        CompanionNeedsEnvironmentService.FoodTargetSearchResult result =
                new CompanionNeedsEnvironmentService.FoodTargetSearchResult(null, true, false);

        assertEquals(
                "food_source_found_but_no_stand_target",
                SensorTameworkNeedsResourceTarget.resolveFoodSearchReasonForTests(result, new Vector3d())
        );
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
