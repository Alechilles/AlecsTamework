package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionDecision;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Outcome of reserving claim capacity and durably preparing the matching owner transition. */
public record CompanionPopulationPreparationResult(
        boolean allowed,
        @Nonnull String reason,
        @Nullable OwnerPopulationDecision ownerDecision,
        @Nullable ClaimAdmissionDecision claimDecision,
        @Nullable PreparedCompanionPopulationAdmission preparedAdmission
) {
}
