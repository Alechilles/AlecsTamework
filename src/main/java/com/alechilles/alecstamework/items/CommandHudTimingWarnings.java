package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Measures contributor callbacks and throttles safe slow-callback warnings. */
final class CommandHudTimingWarnings {
    /** A callback is slow only when it is strictly greater than ten milliseconds. */
    static final long SLOW_THRESHOLD_NANOS = 10_000_000L;
    /** One contributor emits at most one warning during this interval. */
    static final long WARNING_INTERVAL_NANOS = 60_000_000_000L;

    private final LongSupplier nanoTime;
    private final Consumer<Warning> warningSink;
    private final ConcurrentMap<String, Long> lastWarningNanos =
            new ConcurrentHashMap<>();

    CommandHudTimingWarnings() {
        this(System::nanoTime, null);
    }

    CommandHudTimingWarnings(
            @Nonnull LongSupplier nanoTime,
            @Nullable Consumer<Warning> warningSink
    ) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.warningSink = warningSink == null ? ignored -> { } : warningSink;
    }

    long start() {
        return nanoTime.getAsLong();
    }

    /** Finishes one observation and emits a throttled warning when needed. */
    @Nonnull
    Observation finish(
            @Nonnull String contributorId,
            long generation,
            long startedAtNanos
    ) {
        String id = requireContributorId(contributorId);
        if (generation < 0L) {
            throw new IllegalArgumentException("Contributor generation cannot be negative.");
        }
        long now = nanoTime.getAsLong();
        long elapsed = now - startedAtNanos;
        if (elapsed < 0L) elapsed = 0L;
        boolean slow = elapsed > SLOW_THRESHOLD_NANOS;
        boolean warningEmitted = slow && claimWarning(id, now);
        if (warningEmitted) {
            try {
                warningSink.accept(new Warning(id, generation, elapsed));
            } catch (RuntimeException | LinkageError ignored) {
                // Diagnostics must not break composition.
            }
        }
        return new Observation(elapsed, slow, warningEmitted);
    }

    void clear() {
        lastWarningNanos.clear();
    }

    private boolean claimWarning(String contributorId, long now) {
        AtomicBoolean claimed = new AtomicBoolean();
        lastWarningNanos.compute(contributorId, (ignored, previous) -> {
            if (previous == null || now < previous
                    || now - previous >= WARNING_INTERVAL_NANOS) {
                claimed.set(true);
                return now;
            }
            return previous;
        });
        return claimed.get();
    }

    @Nonnull
    private static String requireContributorId(@Nullable String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Contributor ID is required.");
        }
        return normalized;
    }

    /** Timing summary for one contributor callback. */
    record Observation(long elapsedNanos, boolean slow, boolean warningEmitted) {
        Observation {
            if (elapsedNanos < 0L) {
                throw new IllegalArgumentException("Elapsed callback time cannot be negative.");
            }
        }
    }

    /** Redacted warning payload with no contributor values or target identity. */
    record Warning(@Nonnull String contributorId, long generation, long elapsedNanos) {
        Warning {
            contributorId = requireContributorId(contributorId);
            if (generation < 0L) {
                throw new IllegalArgumentException("Contributor generation cannot be negative.");
            }
            if (elapsedNanos < 0L) {
                throw new IllegalArgumentException("Elapsed callback time cannot be negative.");
            }
        }
    }
}
