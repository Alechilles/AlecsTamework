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
public final class CommandUiContributorTimingWarnings {
    /** A callback is slow only when it is strictly greater than ten milliseconds. */
    public static final long SLOW_THRESHOLD_NANOS = 10_000_000L;
    /** One contributor can emit at most one warning during this interval. */
    public static final long WARNING_INTERVAL_NANOS = 60_000_000_000L;

    private final LongSupplier nanoTime;
    private final Consumer<Warning> warningSink;
    private final ConcurrentMap<String, Long> lastWarningNanos =
            new ConcurrentHashMap<>();

    /** Creates a warning tracker backed by the monotonic system clock. */
    public CommandUiContributorTimingWarnings() {
        this(System::nanoTime, CommandUiContributorTimingWarnings::logWarning);
    }

    /** Creates a warning tracker with an injected monotonic clock and sink. */
    public CommandUiContributorTimingWarnings(
            @Nonnull LongSupplier nanoTime,
            @Nullable Consumer<Warning> warningSink
    ) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.warningSink = warningSink == null ? ignored -> { } : warningSink;
    }

    /** Captures a monotonic start timestamp for one callback. */
    public long start() {
        return nanoTime.getAsLong();
    }

    /**
     * Completes one callback measurement and emits a throttled warning when
     * the elapsed duration is over the strict threshold.
     */
    @Nonnull
    public Observation finish(
            @Nonnull String contributorId,
            long generation,
            long startedAtNanos
    ) {
        String id = requireContributorId(contributorId);
        if (generation < 0L) {
            throw new IllegalArgumentException(
                    "Contributor generation cannot be negative.");
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
                // Diagnostics must not break the world-thread composition path.
            }
        }
        return new Observation(elapsed, slow, warningEmitted);
    }

    /** Clears throttle state when the owning command UI service closes. */
    public void clear() {
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

    private static void logWarning(@Nonnull Warning warning) {
        java.util.logging.Logger.getLogger(
                CommandUiContributorTimingWarnings.class.getName())
                .warning("Slow command UI contributor composition: contributor="
                        + warning.contributorId() + ", generation="
                        + warning.generation() + ", elapsedNanos="
                        + warning.elapsedNanos());
    }

    /** Timing summary for one contributor callback. */
    public record Observation(long elapsedNanos,
                              boolean slow,
                              boolean warningEmitted) {
        public Observation {
            if (elapsedNanos < 0L) {
                throw new IllegalArgumentException(
                        "Elapsed callback time cannot be negative.");
            }
        }
    }

    /** Redacted warning payload. */
    public record Warning(@Nonnull String contributorId,
                          long generation,
                          long elapsedNanos) {
        public Warning {
            contributorId = requireContributorId(contributorId);
            if (generation < 0L) {
                throw new IllegalArgumentException(
                        "Contributor generation cannot be negative.");
            }
            if (elapsedNanos < 0L) {
                throw new IllegalArgumentException(
                        "Elapsed callback time cannot be negative.");
            }
        }
    }
}
