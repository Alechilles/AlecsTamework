package com.alechilles.alecstamework.npc.progression;

import javax.annotation.Nonnull;

/**
 * Conservative reuse policy for needs resource path preflights.
 */
final class NeedsResourcePreflightPolicy {
    static final long RECENT_READY_TTL_MS = 3_000L;
    private static final int MAX_START_BLOCK_DELTA = 2;

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
        return Math.abs(previous.startX() - current.startX()) <= MAX_START_BLOCK_DELTA
                && Math.abs(previous.startY() - current.startY()) <= 1
                && Math.abs(previous.startZ() - current.startZ()) <= MAX_START_BLOCK_DELTA;
    }
}
