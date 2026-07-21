package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Health/readiness snapshot for the bonded-vessel authority. */
public record BondedVesselReadinessView(@Nonnull Readiness readiness,
                                       @Nonnull String reason,
                                       long bindingCount,
                                       long pendingOperationCount,
                                       long quarantinedCount,
                                       long updatedAtMs) {
    public BondedVesselReadinessView {
        readiness = Objects.requireNonNull(readiness, "readiness");
        reason = Objects.requireNonNull(reason, "reason").trim();
        if (reason.isEmpty()) throw new IllegalArgumentException("reason is required.");
        if (bindingCount < 0L || pendingOperationCount < 0L || quarantinedCount < 0L) {
            throw new IllegalArgumentException("Vessel readiness counts cannot be negative.");
        }
        if (updatedAtMs < 0L) throw new IllegalArgumentException("updatedAtMs cannot be negative.");
    }

    public static BondedVesselReadinessView unavailable() {
        return new BondedVesselReadinessView(
                Readiness.UNAVAILABLE, "bonded-vessel-authority-unavailable", 0L, 0L, 0L, 0L
        );
    }

    public enum Readiness {
        READY,
        RECOVERING,
        DEGRADED,
        UNAVAILABLE
    }
}
