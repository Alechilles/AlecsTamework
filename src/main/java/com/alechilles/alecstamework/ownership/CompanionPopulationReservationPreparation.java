package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionDecision;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Combined in-memory reservation captured before owner durability is submitted. */
record CompanionPopulationReservationPreparation(
        boolean allowed,
        @Nonnull String reason,
        @Nonnull ClaimAdmissionDecision claimDecision,
        @Nullable OwnerPopulationReservationPreparation ownerReservation
) {
    CompanionPopulationReservationPreparation {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(claimDecision, "claimDecision");
        if (allowed != (claimDecision.allowed()
                && claimDecision.reservation() != null
                && ownerReservation != null
                && ownerReservation.allowed())) {
            throw new IllegalArgumentException("Combined reservation preparation state is inconsistent.");
        }
    }
}
