package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionDecision;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionReservation;
import java.util.Objects;
import javax.annotation.Nonnull;

/** One opaque owner-and-claim capability prepared for a single world mutation. */
public record PreparedCompanionPopulationAdmission(
        @Nonnull PreparedOwnerPopulationAdmission ownerAdmission,
        @Nonnull ClaimAdmissionDecision claimDecision
) {
    public PreparedCompanionPopulationAdmission {
        Objects.requireNonNull(ownerAdmission, "ownerAdmission");
        Objects.requireNonNull(claimDecision, "claimDecision");
        if (!claimDecision.allowed() || claimDecision.reservation() == null) {
            throw new IllegalArgumentException("A prepared companion admission requires a claim reservation.");
        }
    }

    @Nonnull
    public ClaimAdmissionReservation claimReservation() {
        return claimDecision.reservation();
    }
}
