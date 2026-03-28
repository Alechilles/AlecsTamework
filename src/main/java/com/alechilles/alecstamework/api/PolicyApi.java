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
}
