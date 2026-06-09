package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class CompanionNeedsEnvironmentServiceWaterTargetTest {
    @Test
    void waterSearchResultPreservesSourceFoundWhenNoStandTargetExists() {
        CompanionNeedsEnvironmentService.WaterTargetSearchResult result =
                CompanionNeedsEnvironmentService.WaterTargetSearchResult.miss(true);

        assertNull(result.target());
        assertTrue(result.foundConsumableSource());
    }

    @Test
    void waterSearchResultMarksTargetAsSourceFound() {
        Vector3d target = new Vector3d(1.0, 2.0, 3.0);

        CompanionNeedsEnvironmentService.WaterTargetSearchResult result =
                CompanionNeedsEnvironmentService.WaterTargetSearchResult.target(target);

        assertSame(target, result.target());
        assertTrue(result.foundConsumableSource());
    }

    @Test
    void waterSearchMissCacheIsLongEnoughToThrottleRepeatedFullScans() {
        assertTrue(CompanionNeedsEnvironmentService.searchCacheTtlMs(false) >= 3_000L);
    }
}
