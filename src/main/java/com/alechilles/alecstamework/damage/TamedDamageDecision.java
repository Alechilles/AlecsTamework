package com.alechilles.alecstamework.damage;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provider-neutral result returned by the shared tamed-target damage policy.
 */
public record TamedDamageDecision(boolean allowed,
                                  @Nonnull Status status,
                                  @Nonnull String reason,
                                  boolean claimAccessAvailable,
                                  @Nullable UUID claimPartyId,
                                  @Nullable String detail) {
    /** Terminal outcomes shared by runtime cancellation and the public policy API. */
    public enum Status {
        ALLOW_SKIPPED,
        ALLOW_ENFORCED,
        DENY_OWNER,
        DENY_CLAIM,
        ALLOW_FAIL_OPEN,
        UNAVAILABLE
    }

    @Nonnull
    static TamedDamageDecision allowSkipped(@Nonnull String reason) {
        return new TamedDamageDecision(true, Status.ALLOW_SKIPPED, reason, false, null, null);
    }

    @Nonnull
    static TamedDamageDecision allowEnforced(@Nonnull String reason,
                                             @Nullable UUID claimPartyId,
                                             @Nullable String detail) {
        return new TamedDamageDecision(true, Status.ALLOW_ENFORCED, reason, true, claimPartyId, detail);
    }

    @Nonnull
    static TamedDamageDecision denyOwner(@Nonnull String reason) {
        return new TamedDamageDecision(false, Status.DENY_OWNER, reason, false, null, null);
    }

    @Nonnull
    static TamedDamageDecision denyClaim(@Nonnull String reason,
                                        @Nullable UUID claimPartyId,
                                        @Nullable String detail) {
        return new TamedDamageDecision(false, Status.DENY_CLAIM, reason, true, claimPartyId, detail);
    }

    @Nonnull
    static TamedDamageDecision allowFailOpen(@Nonnull String reason,
                                             boolean claimAccessAvailable,
                                             @Nullable UUID claimPartyId,
                                             @Nullable String detail) {
        return new TamedDamageDecision(
                true,
                Status.ALLOW_FAIL_OPEN,
                reason,
                claimAccessAvailable,
                claimPartyId,
                detail
        );
    }

    @Nonnull
    static TamedDamageDecision unavailable(@Nonnull String reason) {
        return new TamedDamageDecision(true, Status.UNAVAILABLE, reason, false, null, null);
    }
}
