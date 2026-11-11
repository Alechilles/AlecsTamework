package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.api.access.AccessAction;
import com.alechilles.alecstamework.api.access.AccessLevel;
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
        // This entry point knows only the two ids, so the attacker is the owner or a stranger.
        // A caller that knows the attacker's shared access uses evaluateOwnerProtection directly.
        AccessLevel attackerAccess = ownerPolicy != null
                && ownerPolicy.ownerUuid() != null
                && ownerPolicy.ownerUuid().equals(attackerPlayerUuid)
                ? AccessLevel.OWNER
                : AccessLevel.NONE;
        TamedDamageDecision ownerDecision = evaluateOwnerProtection(ownerPolicy, attackerPlayerUuid, attackerAccess);
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

    /**
     * Decides the owner damage settings for one hit, or returns null when they do not block it.
     *
     * <p>{@code attackerAccess} is the attacking player's access to the target, resolved by the
     * caller. A player who may cull (a manager) is treated exactly like the owner. A player with
     * lower access (a caretaker or handler) can never damage the animal. A player with no access
     * follows the non-owner settings.</p>
     */
    @Nullable
    TamedDamageDecision evaluateOwnerProtection(
            @Nullable TamedDamageOwnerPolicy ownerPolicy,
            @Nullable UUID attackerPlayerUuid,
            @Nonnull AccessLevel attackerAccess) {
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
        boolean actsAsOwner = AccessAction.CULL.allowedAt(attackerAccess);
        if (!actsAsOwner && attackerAccess.atLeast(AccessLevel.CARETAKER)) {
            return TamedDamageDecision.denyOwner("block-shared-access-damage");
        }
        if (ownerPolicy.blockNonOwnerPlayerDamage() && !actsAsOwner) {
            return TamedDamageDecision.denyOwner("block-non-owner-player-damage");
        }
        if (ownerPolicy.blockOwnerDamage() && actsAsOwner) {
            return TamedDamageDecision.denyOwner("block-owner-damage");
        }
        return null;
    }
}
