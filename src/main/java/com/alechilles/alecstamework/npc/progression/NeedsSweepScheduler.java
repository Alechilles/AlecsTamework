package com.alechilles.alecstamework.npc.progression;

import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Stable scheduling helpers for spreading companion needs work across ticks.
 */
final class NeedsSweepScheduler {
    private NeedsSweepScheduler() {
    }

    static long stableOffsetMs(@Nullable UUID npcId, long intervalMs) {
        if (npcId == null || intervalMs <= 1L) {
            return 0L;
        }
        long mixed = npcId.getMostSignificantBits() ^ Long.rotateLeft(npcId.getLeastSignificantBits(), 21);
        return Math.floorMod(mixed, intervalMs);
    }

    static boolean shouldRunSweep(@Nullable UUID npcId, long lastSweepMs, long nowMs, long intervalMs) {
        if (intervalMs <= 0L) {
            return true;
        }
        if (lastSweepMs <= 0L || lastSweepMs > nowMs) {
            return nowMs >= stableOffsetMs(npcId, intervalMs);
        }
        return nowMs - lastSweepMs >= intervalMs;
    }
}
