package com.alechilles.alecstamework.items.persistence.maintenance;

/** Reports the exact work that remained when a maintenance drain returned. */
public record MaintenanceDrainResult(
        boolean drained,
        int pendingKeys,
        int inFlightWork
) {
    public MaintenanceDrainResult {
        if (pendingKeys < 0 || inFlightWork < 0
                || (drained && (pendingKeys != 0 || inFlightWork != 0))) {
            throw new IllegalArgumentException(
                    "Consistent maintenance drain counts are required"
            );
        }
    }

    /** Returns the number of retained pending values. There is at most one per key. */
    public int pendingWork() {
        return pendingKeys;
    }
}
