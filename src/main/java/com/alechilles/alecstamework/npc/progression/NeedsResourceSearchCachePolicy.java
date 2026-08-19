package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Policy helpers for companion needs resource-search caching.
 */
final class NeedsResourceSearchCachePolicy {
    static final int POSITION_CACHE_CELL_SIZE_BLOCKS = 2;
    static final int MAX_HORIZONTAL_SEARCH_RADIUS = 32;
    static final int MAX_VERTICAL_SCAN_RADIUS = 8;
    static final long LOCAL_HIT_TTL_MS = 1_500L;
    static final long SHARED_HIT_TTL_MS = 10_000L;
    static final long SOURCE_PRESENT_MISS_TTL_MS = 3_000L;
    static final long SOURCE_ABSENT_MISS_TTL_MS = 15_000L;

    private static final double EPSILON = 0.000001;

    private NeedsResourceSearchCachePolicy() {
    }

    static int quantizedBlockCoordinate(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, POSITION_CACHE_CELL_SIZE_BLOCKS);
    }

    static long baseTtlMs(boolean hasTarget, boolean foundConsumableSource) {
        return localBaseTtlMs(hasTarget, foundConsumableSource);
    }

    static long localBaseTtlMs(boolean hasTarget, boolean foundConsumableSource) {
        if (hasTarget) {
            return LOCAL_HIT_TTL_MS;
        }
        return foundConsumableSource ? SOURCE_PRESENT_MISS_TTL_MS : SOURCE_ABSENT_MISS_TTL_MS;
    }

    static long sharedBaseTtlMs(boolean hasTarget, boolean foundConsumableSource) {
        if (hasTarget) {
            return SHARED_HIT_TTL_MS;
        }
        return foundConsumableSource ? SOURCE_PRESENT_MISS_TTL_MS : SOURCE_ABSENT_MISS_TTL_MS;
    }

    static long scaleSharedTtlMs(long baseTtlMs, long nowMs) {
        return TameworkRuntimePressureService.getInstance().scaleTtlMs(
                RuntimePressureDomain.NEEDS_RESOURCE_SEARCH,
                baseTtlMs,
                nowMs
        );
    }

    @Nonnull
    static NeedsResourceCandidates.Snapshot scaleSharedSnapshotTtl(
            @Nonnull NeedsResourceCandidates.Snapshot snapshot,
            long nowMs) {
        return new NeedsResourceCandidates.Snapshot(
                snapshot.candidates(),
                snapshot.foundSource(),
                snapshot.sourceInConsumeRange(),
                scaleSharedTtlMs(
                        sharedBaseTtlMs(snapshot.hasCandidates(), snapshot.foundSource()),
                        nowMs
                )
        );
    }

    static double boundedSearchRadius(double radius) {
        return Double.isFinite(radius)
                ? Math.min(Math.max(radius, 0.0), MAX_HORIZONTAL_SEARCH_RADIUS)
                : radius;
    }

    static double boundedConsumeRadius(double consumeRadius) {
        return Double.isFinite(consumeRadius)
                ? Math.min(Math.max(consumeRadius, 0.0), MAX_HORIZONTAL_SEARCH_RADIUS)
                : consumeRadius;
    }

    static int boundedVerticalScanRadius(int verticalScanRadius) {
        return Math.max(0, Math.min(MAX_VERTICAL_SCAN_RADIUS, verticalScanRadius));
    }

    static boolean hasSafeOrigin(double originX,
                                 double originY,
                                 double originZ,
                                 int horizontalRadius,
                                 int verticalRadius) {
        return isSafeCoordinate(originX, horizontalRadius)
                && isSafeCoordinate(originY, verticalRadius)
                && isSafeCoordinate(originZ, horizontalRadius);
    }

    private static boolean isSafeCoordinate(double coordinate, int radius) {
        if (!Double.isFinite(coordinate)) {
            return false;
        }
        double floor = Math.floor(coordinate);
        return floor >= Integer.MIN_VALUE + (double) radius
                && floor <= Integer.MAX_VALUE - (double) radius;
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
