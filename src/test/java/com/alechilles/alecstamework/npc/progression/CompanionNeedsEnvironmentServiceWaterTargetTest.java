package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CompanionNeedsEnvironmentServiceWaterTargetTest {
    @AfterEach
    void clearRuntimePressure() {
        TameworkRuntimePressureService.getInstance().clearForTests();
    }

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
                CompanionNeedsEnvironmentService.WaterTargetSearchResult.target(target, 2.5);

        assertSame(target, result.target());
        assertTrue(result.foundConsumableSource());
        assertEquals(2.5, result.approachRadius(), 0.000001);
    }

    @Test
    void waterSearchMissCacheIsLongEnoughToThrottleRepeatedFullScans() {
        assertTrue(CompanionNeedsEnvironmentService.searchCacheTtlMs(false) >= 3_000L);
    }

    @Test
    void sourceAbsentMissCacheBacksOffLongerThanSourcePresentMiss() {
        long sourcePresentMissTtl = CompanionNeedsEnvironmentService.searchCacheTtlMs(false, true);
        long sourceAbsentMissTtl = CompanionNeedsEnvironmentService.searchCacheTtlMs(false, false);

        assertTrue(sourceAbsentMissTtl > sourcePresentMissTtl);
        assertEquals(CompanionNeedsEnvironmentService.searchCacheTtlMs(false), sourcePresentMissTtl);
    }

    @Test
    void sourceAbsentMissCacheUsesRuntimePressureBackoff() {
        TameworkRuntimePressureService pressure = TameworkRuntimePressureService.getInstance();
        long baseTtl = CompanionNeedsEnvironmentService.searchCacheTtlMs(false, false);

        for (int i = 0; i < 700; i++) {
            pressure.recordWork(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 100_000L, System.currentTimeMillis());
        }

        assertTrue(CompanionNeedsEnvironmentService.searchCacheTtlMs(false, false) > baseTtl);
    }

    @Test
    void expandedWaterSearchIsSkippedWhenPrimaryScanFoundNoConsumableSource() {
        CompanionNeedsEnvironmentService.WaterTargetSearchResult result =
                CompanionNeedsEnvironmentService.WaterTargetSearchResult.miss(false);

        assertFalse(CompanionNeedsEnvironmentService.shouldRunExpandedWaterSearchForTests(result, 4.0));
    }

    @Test
    void expandedWaterSearchStillRunsWhenPrimaryScanFoundOnlyRejectedSource() {
        CompanionNeedsEnvironmentService.WaterTargetSearchResult result =
                CompanionNeedsEnvironmentService.WaterTargetSearchResult.miss(true);

        assertTrue(CompanionNeedsEnvironmentService.shouldRunExpandedWaterSearchForTests(result, 4.0));
    }

    @Test
    void rejectorWaterSearchCachesOnlyTrueNoSourceMisses() {
        CompanionNeedsEnvironmentService.WaterTargetSearchResult noSource =
                CompanionNeedsEnvironmentService.WaterTargetSearchResult.miss(false);
        CompanionNeedsEnvironmentService.WaterTargetSearchResult sourceWithoutTarget =
                CompanionNeedsEnvironmentService.WaterTargetSearchResult.miss(true);

        assertTrue(CompanionNeedsEnvironmentService.shouldCacheRejectorWaterSearchResultForTests(noSource));
        assertFalse(CompanionNeedsEnvironmentService.shouldCacheRejectorWaterSearchResultForTests(sourceWithoutTarget));
    }

    @Test
    void foodSearchResultPreservesConsumeRangeMissMetadata() {
        CompanionNeedsEnvironmentService.FoodTargetSearchResult result =
                new CompanionNeedsEnvironmentService.FoodTargetSearchResult(null, true, true);

        assertNull(result.target());
        assertTrue(result.foundConsumableSource());
        assertTrue(result.foundConsumableSourceInConsumeRange());
    }

    @Test
    void foodSearchCachePreservesMetadataAndCopiesTarget() {
        Vector3d target = new Vector3d(3.5, 4.05, 7.5);
        CompanionNeedsEnvironmentService.FoodTargetSearchResult result =
                CompanionNeedsEnvironmentService.FoodTargetSearchResult.target(target, 1.75);

        CompanionNeedsEnvironmentService.FoodTargetSearchResult cachedResult =
                CompanionNeedsEnvironmentService.cacheFoodSearchResultRoundTripForTests(result, 1_000L);

        assertTrue(cachedResult.foundConsumableSource());
        assertTrue(cachedResult.foundConsumableSourceInConsumeRange());
        assertEquals(1.75, cachedResult.approachRadius(), 0.000001);
        assertTrue(cachedResult.target() != target);
        assertEquals(target.x, cachedResult.target().x, 0.000001);
        assertEquals(target.y, cachedResult.target().y, 0.000001);
        assertEquals(target.z, cachedResult.target().z, 0.000001);
    }
}
