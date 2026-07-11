package com.alechilles.alecstamework.ownership;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * In-memory owner reservation captured before the durability phase is submitted to SQLite.
 */
record OwnerPopulationReservationPreparation(
        boolean allowed,
        @Nonnull String reason,
        @Nonnull OwnerPopulationAdmissionPlan plan,
        @Nonnull OwnerPopulationDecision decision
) {
    OwnerPopulationReservationPreparation {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(decision, "decision");
        if (allowed != (decision.allowed() && decision.reservation() != null)) {
            throw new IllegalArgumentException("Owner reservation preparation state is inconsistent.");
        }
    }
}
