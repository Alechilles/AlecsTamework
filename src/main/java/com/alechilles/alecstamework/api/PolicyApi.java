package com.alechilles.alecstamework.api;

import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface PolicyApi {
    Optional<OwnershipPolicyView> getOwnershipByProfileId(String profileId);

    Optional<OwnershipPolicyView> getOwnershipByNpcUuid(UUID npcUuid);

    boolean isOwner(String profileId, UUID playerUuid);

    @Nonnull
    ClaimAccessDecisionView evaluateClaimAccess(String profileId, @Nullable UUID playerUuid);

    @Nonnull
    DamagePolicyDecisionView evaluateDamage(String profileId, @Nullable UUID attackerPlayerUuid);

    @Nonnull
    @Deprecated(since = "0.7.0", forRemoval = false)
    PopulationCapDecisionView evaluatePopulationCap(@Nullable UUID ownerUuid);

    /**
     * Evaluates an owner-only cap with explicit world and slot context. This remains informational;
     * use {@link #populationAdmissions()} to bind a gameplay mutation to reserved capacity.
     */
    @Nonnull
    default OwnerPopulationCapDecisionViewV2 evaluatePopulationCap(@Nonnull OwnerPopulationCapRequestV2 request) {
        return OwnerPopulationCapDecisionViewV2.unavailable(
                request,
                OwnerPopulationCapDecisionViewV2.Scope.UNKNOWN,
                "owner-population-v2-authority-unavailable"
        );
    }

    /** Returns the mutation-bound try/claim-for-apply/commit/cancel API. */
    @Nonnull
    default PopulationAdmissionApi populationAdmissions() {
        return PopulationAdmissionApi.unavailable();
    }
}
