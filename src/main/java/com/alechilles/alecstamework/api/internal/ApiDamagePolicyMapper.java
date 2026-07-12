package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ClaimAccessDecisionView;
import com.alechilles.alecstamework.api.DamagePolicyDecisionView;
import com.alechilles.alecstamework.api.OwnershipPolicyView;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.damage.TamedDamageDecision;
import com.alechilles.alecstamework.damage.TamedDamageOwnerPolicyResolver;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps the shared runtime damage decision onto the stable public API records. */
final class ApiDamagePolicyMapper {
    private ApiDamagePolicyMapper() {
    }

    @Nonnull
    static DamagePolicyDecisionView profileMissing(@Nullable String profileId,
                                                   @Nullable UUID attackerPlayerUuid) {
        String normalizedProfileId = profileId == null ? "" : profileId;
        OwnershipPolicyView ownership = new OwnershipPolicyView(
                normalizedProfileId,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                false,
                false,
                false
        );
        return new DamagePolicyDecisionView(
                normalizedProfileId,
                attackerPlayerUuid,
                true,
                DamagePolicyDecisionView.Status.UNAVAILABLE,
                "profile-not-found",
                ownership,
                null
        );
    }

    @Nonnull
    static DamagePolicyDecisionView map(@Nonnull OwnershipPolicyView ownership,
                                        @Nullable UUID attackerPlayerUuid,
                                        @Nonnull TamedDamageDecision decision,
                                        @Nullable String worldName,
                                        @Nullable Vector3View position) {
        ClaimAccessDecisionView claimAccess = mapClaimAccess(decision, worldName, position);
        return new DamagePolicyDecisionView(
                ownership.profileId(),
                attackerPlayerUuid,
                decision.allowed(),
                mapStatus(decision.status()),
                decision.reason(),
                ownership,
                claimAccess
        );
    }

    @Nonnull
    static OwnershipPolicyView withLiveOwnerPolicy(
            @Nonnull OwnershipPolicyView persisted,
            @Nonnull TamedDamageOwnerPolicyResolver.Resolution live
    ) {
        UUID ownerId = live.policy().ownerUuid();
        return new OwnershipPolicyView(
                persisted.profileId(),
                persisted.currentNpcUuid(),
                ownerId,
                Objects.equals(ownerId, persisted.ownerUuid()) ? persisted.ownerName() : null,
                live.roleId(),
                persisted.tamed(),
                persisted.inCoop(),
                persisted.coopId(),
                persisted.coopSlot(),
                live.policy().blockOwnerDamage(),
                live.policy().blockAllPlayerDamageIfOwned(),
                live.policy().invulnerableIfOwned()
        );
    }

    @Nonnull
    private static DamagePolicyDecisionView.Status mapStatus(@Nonnull TamedDamageDecision.Status status) {
        return switch (status) {
            case ALLOW_SKIPPED -> DamagePolicyDecisionView.Status.ALLOWED_SKIPPED;
            case ALLOW_ENFORCED -> DamagePolicyDecisionView.Status.ALLOWED;
            case DENY_OWNER -> DamagePolicyDecisionView.Status.DENIED_OWNER_PROTECTION;
            case DENY_CLAIM -> DamagePolicyDecisionView.Status.DENIED_CLAIM_PROTECTION;
            case ALLOW_FAIL_OPEN -> DamagePolicyDecisionView.Status.ALLOWED_FAIL_OPEN;
            case UNAVAILABLE -> DamagePolicyDecisionView.Status.UNAVAILABLE;
        };
    }

    @Nullable
    private static ClaimAccessDecisionView mapClaimAccess(@Nonnull TamedDamageDecision decision,
                                                          @Nullable String worldName,
                                                          @Nullable Vector3View position) {
        if (decision.status() == TamedDamageDecision.Status.DENY_OWNER) {
            return null;
        }
        ClaimAccessDecisionView.Status status = switch (decision.status()) {
            case ALLOW_SKIPPED -> ClaimAccessDecisionView.Status.SKIPPED;
            case ALLOW_ENFORCED -> ClaimAccessDecisionView.Status.ALLOWED;
            case DENY_CLAIM -> ClaimAccessDecisionView.Status.DENIED;
            case ALLOW_FAIL_OPEN -> ClaimAccessDecisionView.Status.ALLOW_FAIL_OPEN;
            case UNAVAILABLE -> ClaimAccessDecisionView.Status.UNAVAILABLE;
            case DENY_OWNER -> throw new IllegalStateException("Owner denial has no claim decision.");
        };
        return new ClaimAccessDecisionView(
                decision.claimAccessAvailable(),
                decision.allowed(),
                status,
                firstNonBlank(decision.detail(), decision.reason()),
                decision.claimPartyId(),
                null,
                worldName,
                position,
                worldName != null && position != null ? "live" : null
        );
    }

    @Nonnull
    private static String firstNonBlank(@Nullable String preferred, @Nonnull String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}
