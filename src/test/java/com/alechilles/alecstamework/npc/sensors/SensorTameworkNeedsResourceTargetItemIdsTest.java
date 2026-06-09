package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
