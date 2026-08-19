package com.alechilles.alecstamework.npc.progression;

import javax.annotation.Nonnull;

/**
 * Conservative reuse policy for needs resource path preflights.
 */
final class NeedsResourcePreflightPolicy {
    static final long RECENT_READY_TTL_MS = 10_000L;
    private static final double MAX_HORIZONTAL_CORRIDOR = 4.0;
    private static final int MAX_VERTICAL_DELTA = 2;
    private static final double EPSILON = 0.000001;

    private NeedsResourcePreflightPolicy() {
    }

    static boolean canReuseRecentReady(@Nonnull NeedsResourcePathPreflightService.PreflightKey previous,
                                       @Nonnull NeedsResourcePathPreflightService.PreflightKey current) {
        if (!previous.npcUuid().equals(current.npcUuid())
                || !previous.worldName().equals(current.worldName())
                || !previous.resourceType().equals(current.resourceType())
                || !previous.motionControllerType().equals(current.motionControllerType())
                || previous.targetX() != current.targetX()
                || previous.targetY() != current.targetY()
                || previous.targetZ() != current.targetZ()
                || previous.stopDistanceKey() != current.stopDistanceKey()) {
            return false;
        }
        return isConservativeProgress(previous, current);
    }

    private static boolean isConservativeProgress(
            @Nonnull NeedsResourcePathPreflightService.PreflightKey previous,
            @Nonnull NeedsResourcePathPreflightService.PreflightKey current) {
        int segmentX = previous.targetX() - previous.startX();
        int segmentY = previous.targetY() - previous.startY();
        int segmentZ = previous.targetZ() - previous.startZ();
        int moveX = current.startX() - previous.startX();
        int moveY = current.startY() - previous.startY();
        int moveZ = current.startZ() - previous.startZ();
        double segmentLengthSquared = squared(segmentX) + squared(segmentY) + squared(segmentZ);
        double previousDistanceSquared = squared(previous.targetX() - previous.startX())
                + squared(previous.targetY() - previous.startY())
                + squared(previous.targetZ() - previous.startZ());
        double currentDistanceSquared = squared(current.targetX() - current.startX())
                + squared(current.targetY() - current.startY())
                + squared(current.targetZ() - current.startZ());
        if (currentDistanceSquared > previousDistanceSquared + EPSILON
                || Math.abs(moveY) > MAX_VERTICAL_DELTA) {
            return false;
        }
        if (segmentLengthSquared <= EPSILON) {
            return moveX == 0 && moveY == 0 && moveZ == 0;
        }
        double progress = moveX * (double) segmentX
                + moveY * (double) segmentY
                + moveZ * (double) segmentZ;
        if (progress < -EPSILON || progress > segmentLengthSquared + EPSILON) {
            return false;
        }
        return horizontalDistanceFromSegmentSquared(
                previous.startX(),
                previous.startZ(),
                segmentX,
                segmentZ,
                current.startX(),
                current.startZ()
        ) <= MAX_HORIZONTAL_CORRIDOR * MAX_HORIZONTAL_CORRIDOR + EPSILON;
    }

    private static double horizontalDistanceFromSegmentSquared(int startX,
                                                               int startZ,
                                                               int segmentX,
                                                               int segmentZ,
                                                               int currentX,
                                                               int currentZ) {
        double segmentLengthSquared = squared(segmentX) + squared(segmentZ);
        if (segmentLengthSquared <= EPSILON) {
            return squared(currentX - startX) + squared(currentZ - startZ);
        }
        double projection = ((currentX - startX) * (double) segmentX
                + (currentZ - startZ) * (double) segmentZ) / segmentLengthSquared;
        projection = Math.max(0.0, Math.min(1.0, projection));
        double nearestX = startX + projection * segmentX;
        double nearestZ = startZ + projection * segmentZ;
        return squared(currentX - nearestX) + squared(currentZ - nearestZ);
    }

    private static double squared(double value) {
        return value * value;
    }
}
