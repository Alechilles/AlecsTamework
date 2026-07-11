package com.alechilles.alecstamework.ownership.reconciliation;

import javax.annotation.Nonnull;

/** Immutable diagnostics snapshot for startup population reconciliation. */
public record CompanionPopulationReconciliationProgress(
        @Nonnull Status status,
        @Nonnull String reason,
        long scannedUnits,
        long totalUnits,
        int profileCount,
        int duplicateObservations,
        int recoveredOperations,
        int canceledOperations,
        long startedAtMs,
        long completedAtMs
) {
    public enum Status {
        IDLE,
        RUNNING,
        READY,
        RECONCILING,
        DEGRADED,
        CLOSED
    }

    public CompanionPopulationReconciliationProgress {
        if (status == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reconciliation status and reason are required.");
        }
        if (scannedUnits < 0L || totalUnits < 0L || scannedUnits > totalUnits) {
            throw new IllegalArgumentException("Invalid reconciliation progress counts.");
        }
    }

    @Nonnull
    public static CompanionPopulationReconciliationProgress idle() {
        return new CompanionPopulationReconciliationProgress(
                Status.IDLE, "reconciliation-not-started", 0L, 0L,
                0, 0, 0, 0, 0L, 0L
        );
    }
}
