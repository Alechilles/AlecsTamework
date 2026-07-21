package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Public health/readiness snapshot for population-group classification. */
public record PopulationGroupReconciliationView(@Nonnull Readiness readiness,
                                                @Nonnull String reason,
                                                long configRevision,
                                                long classifiedProfiles,
                                                long pendingProfiles,
                                                long overLimitBuckets,
                                                long updatedAtMs) {
    public PopulationGroupReconciliationView {
        readiness = Objects.requireNonNull(readiness, "readiness");
        reason = Objects.requireNonNull(reason, "reason").trim();
        if (reason.isEmpty()) throw new IllegalArgumentException("reason is required.");
        if (configRevision < 0L || classifiedProfiles < 0L || pendingProfiles < 0L
                || overLimitBuckets < 0L) {
            throw new IllegalArgumentException("Population-group reconciliation values cannot be negative.");
        }
        if (updatedAtMs < 0L) throw new IllegalArgumentException("updatedAtMs cannot be negative.");
    }

    public static PopulationGroupReconciliationView unavailable() {
        return new PopulationGroupReconciliationView(
                Readiness.UNAVAILABLE, "population-group-authority-unavailable", 0L, 0L, 0L, 0L, 0L
        );
    }

    public enum Readiness {
        READY,
        RECONCILING,
        DEGRADED,
        UNAVAILABLE
    }
}
