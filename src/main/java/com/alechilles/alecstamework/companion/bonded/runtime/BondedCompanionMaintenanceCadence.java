package com.alechilles.alecstamework.companion.bonded.runtime;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Coordinates process and per-world bonded maintenance deadlines. */
final class BondedCompanionMaintenanceCadence {
    private static final long ACTIVE_INTERVAL_NANOS = 5_000_000_000L;
    private static final long IDLE_INTERVAL_NANOS = 30_000_000_000L;
    private final AtomicReference<Long> nextGlobalRun = new AtomicReference<>();
    private final ConcurrentMap<String, Long> nextWorldRuns = new ConcurrentHashMap<>();

    boolean claimGlobal(long nowNanos) {
        Long next = nextGlobalRun.get();
        if (next != null && nowNanos < next) {
            return false;
        }
        return nextGlobalRun.compareAndSet(
                next, safeAdd(nowNanos, ACTIVE_INTERVAL_NANOS));
    }

    @Nullable
    WorldClaim claimWorld(@Nonnull String worldKey, long nowNanos) {
        String world = Objects.requireNonNull(worldKey, "worldKey");
        Long next = nextWorldRuns.get(world);
        if (next != null && nowNanos < next) {
            return null;
        }
        long reservedUntil = safeAdd(nowNanos, ACTIVE_INTERVAL_NANOS);
        boolean claimed = next == null
                ? nextWorldRuns.putIfAbsent(world, reservedUntil) == null
                : nextWorldRuns.replace(world, next, reservedUntil);
        return claimed ? new WorldClaim(world, reservedUntil) : null;
    }

    void completeWorld(
            @Nonnull WorldClaim claim,
            long nowNanos,
            boolean active
    ) {
        WorldClaim current = Objects.requireNonNull(claim, "claim");
        long interval = active ? ACTIVE_INTERVAL_NANOS : IDLE_INTERVAL_NANOS;
        nextWorldRuns.replace(current.worldKey(), current.reservedUntilNanos(),
                safeAdd(nowNanos, interval));
    }

    private static long safeAdd(long value, long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    record WorldClaim(String worldKey, long reservedUntilNanos) { }
}
