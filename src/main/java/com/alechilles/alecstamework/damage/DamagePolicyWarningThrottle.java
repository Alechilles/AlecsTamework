package com.alechilles.alecstamework.damage;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies a low-noise warning interval independently for each damage diagnostic key. */
final class DamagePolicyWarningThrottle {
    private static final long DEFAULT_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1L);

    private final long intervalNanos;
    private final LongSupplier monotonicClock;
    private final Map<WarningKey, Long> nextWarningByKey = new ConcurrentHashMap<>();

    DamagePolicyWarningThrottle() {
        this(DEFAULT_INTERVAL_NANOS, System::nanoTime);
    }

    DamagePolicyWarningThrottle(long intervalNanos, @Nonnull LongSupplier monotonicClock) {
        if (intervalNanos <= 0L) {
            throw new IllegalArgumentException("intervalNanos must be positive");
        }
        this.intervalNanos = intervalNanos;
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
    }

    boolean tryAcquire(@Nonnull String category,
                       @Nullable String providerId,
                       @Nullable String context) {
        WarningKey key = new WarningKey(category, providerId, context);
        long now = monotonicClock.getAsLong();
        AtomicBoolean acquired = new AtomicBoolean();
        nextWarningByKey.compute(key, (ignored, nextWarningAt) -> {
            if (nextWarningAt == null || now - nextWarningAt >= 0L) {
                acquired.set(true);
                return now + intervalNanos;
            }
            return nextWarningAt;
        });
        return acquired.get();
    }

    private record WarningKey(@Nonnull String category,
                              @Nonnull String providerId,
                              @Nonnull String context) {
        private WarningKey {
            category = normalize(category);
            providerId = normalize(providerId);
            context = normalize(context);
            if (category.isEmpty()) {
                throw new IllegalArgumentException("category cannot be blank");
            }
        }

        @Nonnull
        private static String normalize(@Nullable String value) {
            return value == null ? "" : value.trim();
        }
    }
}
