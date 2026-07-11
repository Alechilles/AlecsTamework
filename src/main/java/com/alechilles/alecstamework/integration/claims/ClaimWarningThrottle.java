package com.alechilles.alecstamework.integration.claims;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies a low-noise warning interval independently for each claim diagnostic category,
 * provider, and runtime context.
 */
public final class ClaimWarningThrottle {
    private static final long DEFAULT_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1L);

    private final long intervalNanos;
    private final LongSupplier monotonicClock;
    private final Map<WarningKey, Long> nextWarningByKey = new ConcurrentHashMap<>();

    public ClaimWarningThrottle() {
        this(DEFAULT_INTERVAL_NANOS, System::nanoTime);
    }

    ClaimWarningThrottle(long intervalNanos, @Nonnull LongSupplier monotonicClock) {
        if (intervalNanos <= 0L) {
            throw new IllegalArgumentException("intervalNanos must be positive");
        }
        this.intervalNanos = intervalNanos;
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
    }

    /**
     * Returns {@code true} when the supplied diagnostic key may emit now.
     *
     * <p>Provider and context are separate key dimensions so an outage in one provider or world
     * cannot hide an actionable warning from another.</p>
     */
    public boolean tryAcquire(@Nonnull String category,
                              @Nullable String providerId,
                              @Nullable String context) {
        WarningKey key = new WarningKey(category, providerId, context);
        long now = monotonicClock.getAsLong();
        AtomicBoolean acquired = new AtomicBoolean();
        nextWarningByKey.compute(key, (ignored, nextWarningAt) -> {
            if (nextWarningAt == null || deadlineReached(now, nextWarningAt)) {
                acquired.set(true);
                return now + intervalNanos;
            }
            return nextWarningAt;
        });
        return acquired.get();
    }

    private static boolean deadlineReached(long now, long deadline) {
        return now - deadline >= 0L;
    }

    private record WarningKey(@Nonnull String category,
                              @Nonnull String providerId,
                              @Nonnull String context) {
        private WarningKey {
            category = requireCategory(category);
            providerId = normalize(providerId);
            context = normalize(context);
        }

        @Nonnull
        private static String requireCategory(@Nullable String category) {
            String normalized = normalize(category);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("category cannot be blank");
            }
            return normalized;
        }

        @Nonnull
        private static String normalize(@Nullable String value) {
            return value == null ? "" : value.trim();
        }
    }
}
