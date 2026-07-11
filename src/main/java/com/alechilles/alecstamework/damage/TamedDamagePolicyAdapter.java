package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure decision adapter shared by runtime and API damage evaluation.
 */
final class TamedDamagePolicyAdapter {
    @Nonnull
    Optional<TamedDamageDecision> evaluatePreconditions(
            @Nullable TamedDamageOwnerPolicy ownerPolicy,
            @Nullable UUID attackerPlayerUuid,
            boolean integrationEnabled,
            boolean protectionEnabled,
            @Nonnull TamedDamageTargetEligibilityResolver.Status eligibility) {
        TamedDamageDecision ownerDecision = evaluateOwnerProtection(ownerPolicy, attackerPlayerUuid);
        if (ownerDecision != null) {
            return Optional.of(ownerDecision);
        }
        if (!integrationEnabled) {
            return Optional.of(TamedDamageDecision.allowSkipped("claim-integration-disabled"));
        }
        if (!protectionEnabled) {
            return Optional.of(TamedDamageDecision.allowSkipped("damage-protection-disabled"));
        }
        if (attackerPlayerUuid == null) {
            return Optional.of(TamedDamageDecision.allowSkipped("attacker-unattributed"));
        }
        if (eligibility == TamedDamageTargetEligibilityResolver.Status.LIVE_TARGET_REQUIRED) {
            return Optional.of(TamedDamageDecision.unavailable("live-target-required"));
        }
        if (eligibility == TamedDamageTargetEligibilityResolver.Status.INELIGIBLE) {
            return Optional.of(TamedDamageDecision.allowSkipped("target-not-tamed"));
        }
        return Optional.empty();
    }

    @Nonnull
    TamedDamageDecision mapNative(
            @Nullable SimpleClaimsBreedingBridge.DamageAccessResult accessResult) {
        if (accessResult == null) {
            return TamedDamageDecision.allowFailOpen(
                    "missing-access-result",
                    false,
                    null,
                    "SimpleClaims native damage result was missing."
            );
        }
        return switch (accessResult.status()) {
            case ALLOWED -> TamedDamageDecision.allowEnforced(
                    "native-policy-allowed",
                    accessResult.claimPartyId(),
                    accessResult.message()
            );
            case DENIED -> TamedDamageDecision.denyClaim(
                    "claim-protection-denied",
                    accessResult.claimPartyId(),
                    accessResult.message()
            );
            case LOOKUP_ERROR -> TamedDamageDecision.allowFailOpen(
                    "lookup-error",
                    true,
                    accessResult.claimPartyId(),
                    accessResult.message()
            );
            case UNAVAILABLE -> TamedDamageDecision.allowFailOpen(
                    "bridge-unavailable",
                    false,
                    accessResult.claimPartyId(),
                    accessResult.message()
            );
        };
    }

    @Nullable
    private TamedDamageDecision evaluateOwnerProtection(
            @Nullable TamedDamageOwnerPolicy ownerPolicy,
            @Nullable UUID attackerPlayerUuid) {
        if (ownerPolicy == null || ownerPolicy.ownerUuid() == null) {
            return null;
        }
        if (ownerPolicy.invulnerableIfOwned()) {
            return TamedDamageDecision.denyOwner("invulnerable-if-owned");
        }
        if (attackerPlayerUuid == null) {
            return null;
        }
        if (ownerPolicy.blockAllPlayerDamageIfOwned()) {
            return TamedDamageDecision.denyOwner("block-all-player-damage-if-owned");
        }
        if (ownerPolicy.blockOwnerDamage() && ownerPolicy.ownerUuid().equals(attackerPlayerUuid)) {
            return TamedDamageDecision.denyOwner("block-owner-damage");
        }
        return null;
    }
}
