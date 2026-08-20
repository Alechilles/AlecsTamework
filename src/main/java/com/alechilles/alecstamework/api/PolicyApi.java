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
    PopulationCapDecisionView evaluatePopulationCap(@Nullable UUID ownerUuid);

    /**
     * Evaluates an owner-only cap with explicit world and slot context.
     *
     * <p>This remains informational; use {@link #populationAdmissions()} to bind a gameplay
     * mutation to reserved capacity.</p>
     */
    @Nonnull
    default OwnerPopulationCapDecisionViewV2 evaluatePopulationCap(
            @Nonnull OwnerPopulationCapRequestV2 request
    ) {
        if (request == null) {
            throw new NullPointerException("request");
        }
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

    /** Returns the external population-admission provider registry when advertised. */
    @Nonnull
    default AdmissionProviderApi admissionProviders() {
        return AdmissionProviderApi.unavailable();
    }

}
