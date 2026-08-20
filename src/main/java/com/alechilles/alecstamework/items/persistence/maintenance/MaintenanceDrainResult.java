package com.alechilles.alecstamework.items.persistence.maintenance;

/** Reports the exact work that remained when a maintenance drain returned. */
public record MaintenanceDrainResult(
        boolean drained,
        int pendingKeys,
        int pendingWork,
        int inFlightWork
) {
    public MaintenanceDrainResult {
        if (pendingKeys < 0 || pendingWork < 0 || inFlightWork < 0
                || pendingWork < pendingKeys
                || (drained && (pendingKeys != 0
                || pendingWork != 0 || inFlightWork != 0))) {
            throw new IllegalArgumentException(
                    "Consistent maintenance drain counts are required"
            );
        }
    }

    /** Preserves the original constructor for single-lane callers. */
    public MaintenanceDrainResult(
            boolean drained,
            int pendingKeys,
            int inFlightWork
    ) {
        this(drained, pendingKeys, pendingKeys, inFlightWork);
    }

}
