package com.alechilles.alecstamework.companion.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Allows one WARN per key per interval, so repeated failures on hot paths stay readable. */
public final class ThrottledWarnings {
    private final LongSupplier clock;
    private final long intervalMs;
    private final Map<String, Long> nextAt = new ConcurrentHashMap<>();

    public ThrottledWarnings(@Nonnull LongSupplier clock, long intervalMs) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.intervalMs = intervalMs;
    }

    public boolean shouldLog(@Nonnull String key) {
        long now = clock.getAsLong();
        boolean[] allowed = {false};
        nextAt.compute(key, (k, next) -> {
            if (next == null || now >= next) {
                allowed[0] = true;
                return now + intervalMs;
            }
            return next;
        });
        return allowed[0];
    }
}
