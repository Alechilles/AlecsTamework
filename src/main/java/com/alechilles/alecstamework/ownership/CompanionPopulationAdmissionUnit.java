package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * One independently consumable owner-and-claim unit inside a population admission batch.
 */
public record CompanionPopulationAdmissionUnit(
        @Nonnull OwnerPopulationAdmissionPlan ownerPlan,
        @Nonnull ClaimAdmissionRequest claimRequest
) {
    public CompanionPopulationAdmissionUnit {
        Objects.requireNonNull(ownerPlan, "ownerPlan");
        Objects.requireNonNull(claimRequest, "claimRequest");
        if (claimRequest.transitions().size() != 1) {
            throw new IllegalArgumentException("A batch unit must contain exactly one claim transition.");
        }
        ClaimOccupancyTransition transition = claimRequest.transitions().getFirst();
        if (!ownerPlan.transition().profileId().equals(transition.profileId())) {
            throw new IllegalArgumentException("Owner and claim unit profile IDs must match.");
        }
    }
}
