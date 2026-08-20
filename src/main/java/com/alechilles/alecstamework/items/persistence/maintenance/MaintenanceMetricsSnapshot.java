package com.alechilles.alecstamework.items.persistence.maintenance;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Immutable counters and queue-depth evidence for one maintenance coordinator. */
public record MaintenanceMetricsSnapshot(
        long submissions,
        long replacements,
        long completions,
        long failures,
        int pendingKeys,
        int pendingWork,
        int inFlightWork,
        int maximumInFlightWork,
        long oldestPendingAgeNanos
) {
    public MaintenanceMetricsSnapshot {
        if (submissions < 0 || replacements < 0 || completions < 0
                || failures < 0 || pendingKeys < 0 || pendingWork < 0
                || pendingWork < pendingKeys || inFlightWork < 0
                || maximumInFlightWork < 0
                || oldestPendingAgeNanos < 0
                || maximumInFlightWork < inFlightWork) {
            throw new IllegalArgumentException(
                    "Consistent maintenance metrics are required"
            );
        }
    }

    /** Preserves the original constructor for single-lane callers. */
    public MaintenanceMetricsSnapshot(
            long submissions,
            long replacements,
            long completions,
            long failures,
            int pendingKeys,
            int inFlightWork,
            int maximumInFlightWork,
            long oldestPendingAgeNanos
    ) {
        this(
                submissions,
                replacements,
                completions,
                failures,
                pendingKeys,
                pendingKeys,
                inFlightWork,
                maximumInFlightWork,
                oldestPendingAgeNanos
        );
    }

    /** Returns the oldest pending age as a stable wall-clock-independent duration. */
    public Duration oldestPendingAge() {
        return Duration.ofNanos(oldestPendingAgeNanos);
    }

    /** Returns the oldest pending age rounded down to milliseconds. */
    public long oldestPendingAgeMillis() {
        return TimeUnit.NANOSECONDS.toMillis(oldestPendingAgeNanos);
    }

}
