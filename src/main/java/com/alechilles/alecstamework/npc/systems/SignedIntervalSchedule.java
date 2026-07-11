package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.progression.BreedingTimeService;

/**
 * Pure signed-time interval schedule for one world-scoped breeding task.
 *
 * <p>Initialization is explicit because zero is a valid point on the world timeline. A backwards
 * clock jump starts a fresh interval and permits one immediate repair run.
 */
final class SignedIntervalSchedule {
    private boolean initialized;
    private boolean saturatedDeadlineConsumed;
    private long lastNowMs;
    private long nextRunAtMs;

    /** Updates the schedule and returns whether the caller should run now. */
    boolean shouldRun(long nowMs, long intervalMs) {
        requirePositiveInterval(intervalMs);
        if (!initialized || nowMs < lastNowMs) {
            restart(nowMs, intervalMs);
            return true;
        }
        if (nowMs < nextRunAtMs || saturatedDeadlineConsumed) {
            lastNowMs = nowMs;
            return false;
        }
        restart(nowMs, intervalMs);
        return true;
    }

    /** Starts a fresh interval without reporting a due run. */
    void restart(long nowMs, long intervalMs) {
        requirePositiveInterval(intervalMs);
        initialized = true;
        lastNowMs = nowMs;
        nextRunAtMs = BreedingTimeService.saturatingAdd(nowMs, intervalMs);
        saturatedDeadlineConsumed = nowMs == Long.MAX_VALUE && nextRunAtMs == Long.MAX_VALUE;
    }

    private static void requirePositiveInterval(long intervalMs) {
        if (intervalMs <= 0L) {
            throw new IllegalArgumentException("intervalMs must be positive");
        }
    }

    boolean isInitialized() {
        return initialized;
    }

    long lastNowMs() {
        return lastNowMs;
    }

    long nextRunAtMs() {
        return nextRunAtMs;
    }
}
