package com.alechilles.alecstamework.performance;

import java.util.EnumMap;
import javax.annotation.Nonnull;

/**
 * Tracks recent Tamework runtime work and exposes conservative backoff multipliers for retry caches.
 */
public final class TameworkRuntimePressureService {
    public static final long WINDOW_MS = 5_000L;

    private static final TameworkRuntimePressureService INSTANCE = new TameworkRuntimePressureService();
    private static final long WARM_NANOS_PER_WINDOW = 8_000_000L;
    private static final long HOT_NANOS_PER_WINDOW = 24_000_000L;
    private static final long EMERGENCY_NANOS_PER_WINDOW = 64_000_000L;
    private static final long WARM_OPERATIONS_PER_WINDOW = 128L;
    private static final long HOT_OPERATIONS_PER_WINDOW = 512L;
    private static final long EMERGENCY_OPERATIONS_PER_WINDOW = 1_024L;
    private static final long MAX_SCALED_TTL_MS = 30_000L;

    private final EnumMap<RuntimePressureDomain, DomainState> states =
            new EnumMap<>(RuntimePressureDomain.class);

    TameworkRuntimePressureService() {
        for (RuntimePressureDomain domain : RuntimePressureDomain.values()) {
            states.put(domain, new DomainState());
        }
    }

    @Nonnull
    public static TameworkRuntimePressureService getInstance() {
        return INSTANCE;
    }

    public void recordWork(@Nonnull RuntimePressureDomain domain, long elapsedNanos, long nowMs) {
        DomainState state = states.get(domain);
        if (state != null) {
            state.recordWork(Math.max(0L, elapsedNanos), nowMs);
        }
    }

    public long scaleTtlMs(@Nonnull RuntimePressureDomain domain, long baseTtlMs, long nowMs) {
        if (baseTtlMs <= 0L) {
            return 0L;
        }
        DomainState state = states.get(domain);
        double multiplier = state != null ? state.multiplier(nowMs) : 1.0;
        long scaled = (long) Math.ceil(baseTtlMs * multiplier);
        return Math.max(baseTtlMs, Math.min(MAX_SCALED_TTL_MS, scaled));
    }

    @Nonnull
    public RuntimePressureLevel level(@Nonnull RuntimePressureDomain domain, long nowMs) {
        DomainState state = states.get(domain);
        return state != null ? state.level(nowMs) : RuntimePressureLevel.NORMAL;
    }

    public boolean isAtLeast(
            @Nonnull RuntimePressureDomain domain, @Nonnull RuntimePressureLevel minimum, long nowMs) {
        return level(domain, nowMs).isAtLeast(minimum);
    }

    public void clearForTests() {
        for (DomainState state : states.values()) {
            state.clear();
        }
    }

    private static final class DomainState {
        private long windowStartMs = Long.MIN_VALUE;
        private long workNanos;
        private long operations;
        private RuntimePressureLevel level = RuntimePressureLevel.NORMAL;

        private synchronized void recordWork(long elapsedNanos, long nowMs) {
            rolloverIfNeeded(nowMs);
            workNanos += elapsedNanos;
            operations++;
            level = max(level, classify(workNanos, operations));
        }

        private synchronized double multiplier(long nowMs) {
            rolloverIfNeeded(nowMs);
            return level.multiplier();
        }

        @Nonnull
        private synchronized RuntimePressureLevel level(long nowMs) {
            rolloverIfNeeded(nowMs);
            return level;
        }

        private synchronized void clear() {
            windowStartMs = Long.MIN_VALUE;
            workNanos = 0L;
            operations = 0L;
            level = RuntimePressureLevel.NORMAL;
        }

        private void rolloverIfNeeded(long nowMs) {
            long window = nowMs - Math.floorMod(nowMs, WINDOW_MS);
            if (windowStartMs == Long.MIN_VALUE) {
                windowStartMs = window;
                return;
            }
            if (window <= windowStartMs) {
                return;
            }
            long skippedWindows = Math.max(1L, (window - windowStartMs) / WINDOW_MS);
            workNanos = 0L;
            operations = 0L;
            windowStartMs = window;
            for (long i = 0L; i < skippedWindows; i++) {
                level = level.decayOneStep();
                if (level == RuntimePressureLevel.NORMAL) {
                    break;
                }
            }
        }

        @Nonnull
        private static RuntimePressureLevel classify(long workNanos, long operations) {
            if (workNanos >= EMERGENCY_NANOS_PER_WINDOW || operations >= EMERGENCY_OPERATIONS_PER_WINDOW) {
                return RuntimePressureLevel.EMERGENCY;
            }
            if (workNanos >= HOT_NANOS_PER_WINDOW || operations >= HOT_OPERATIONS_PER_WINDOW) {
                return RuntimePressureLevel.HOT;
            }
            if (workNanos >= WARM_NANOS_PER_WINDOW || operations >= WARM_OPERATIONS_PER_WINDOW) {
                return RuntimePressureLevel.WARM;
            }
            return RuntimePressureLevel.NORMAL;
        }

        @Nonnull
        private static RuntimePressureLevel max(
                @Nonnull RuntimePressureLevel left, @Nonnull RuntimePressureLevel right) {
            return left.ordinal() >= right.ordinal() ? left : right;
        }
    }
}
