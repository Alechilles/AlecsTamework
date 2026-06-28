package com.alechilles.alecstamework.npc.progression;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeedsResourceSearchCachePolicyTest {
    @Test
    void nearbyBlocksShareSearchCacheCell() {
        assertEquals(
                NeedsResourceSearchCachePolicy.quantizedBlockCoordinate(12),
                NeedsResourceSearchCachePolicy.quantizedBlockCoordinate(13)
        );
    }

    @Test
    void distantBlocksUseDifferentSearchCacheCells() {
        assertFalse(
                NeedsResourceSearchCachePolicy.quantizedBlockCoordinate(12)
                        == NeedsResourceSearchCachePolicy.quantizedBlockCoordinate(16)
        );
    }

    @Test
    void cachedTargetInsideRadiusIsUsable() {
        assertTrue(NeedsResourceSearchCachePolicy.isCachedTargetUsable(
                new Vector3d(10.5, 64.0, 10.5),
                new Vector3d(12.5, 64.0, 10.5),
                3.0,
                1
        ));
    }

    @Test
    void cachedTargetOutsideRadiusIsRejected() {
        assertFalse(NeedsResourceSearchCachePolicy.isCachedTargetUsable(
                new Vector3d(10.5, 64.0, 10.5),
                new Vector3d(15.5, 64.0, 10.5),
                3.0,
                1
        ));
    }

    @Test
    void cachedTargetOutsideVerticalScanIsRejected() {
        assertFalse(NeedsResourceSearchCachePolicy.isCachedTargetUsable(
                new Vector3d(10.5, 64.0, 10.5),
                new Vector3d(10.5, 67.0, 10.5),
                4.0,
                1
        ));
    }
}
