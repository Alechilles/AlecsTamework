package com.alechilles.alecstamework.npc.progression;

import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * Policy helpers for companion needs resource-search caching.
 */
final class NeedsResourceSearchCachePolicy {
    static final int POSITION_CACHE_CELL_SIZE_BLOCKS = 2;
    static final long HIT_TTL_MS = 1_500L;
    static final long SOURCE_PRESENT_MISS_TTL_MS = 3_000L;
    static final long SOURCE_ABSENT_MISS_TTL_MS = 15_000L;

    private static final double EPSILON = 0.000001;

    private NeedsResourceSearchCachePolicy() {
    }

    static int quantizedBlockCoordinate(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, POSITION_CACHE_CELL_SIZE_BLOCKS);
    }

    static long baseTtlMs(boolean hasTarget, boolean foundConsumableSource) {
        if (hasTarget) {
            return HIT_TTL_MS;
        }
        return foundConsumableSource ? SOURCE_PRESENT_MISS_TTL_MS : SOURCE_ABSENT_MISS_TTL_MS;
    }

    static boolean isCachedTargetUsable(@Nullable Vector3d currentPosition,
                                        @Nullable Vector3d cachedTarget,
                                        double radius,
                                        int verticalScanRadius) {
        if (cachedTarget == null) {
            return true;
        }
        if (currentPosition == null
                || !Double.isFinite(currentPosition.x)
                || !Double.isFinite(currentPosition.y)
                || !Double.isFinite(currentPosition.z)
                || !Double.isFinite(cachedTarget.x)
                || !Double.isFinite(cachedTarget.y)
                || !Double.isFinite(cachedTarget.z)
                || !Double.isFinite(radius)
                || radius <= 0.0) {
            return false;
        }
        double dx = cachedTarget.x - currentPosition.x;
        double dz = cachedTarget.z - currentPosition.z;
        if ((dx * dx) + (dz * dz) > (radius * radius) + EPSILON) {
            return false;
        }
        return Math.abs(Math.floor(cachedTarget.y) - Math.floor(currentPosition.y)) <= Math.max(0, verticalScanRadius);
    }
}
