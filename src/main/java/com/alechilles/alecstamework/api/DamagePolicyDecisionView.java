package com.alechilles.alecstamework.api;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record DamagePolicyDecisionView(@Nonnull String profileId,
                                       @Nullable UUID attackerPlayerUuid,
                                       boolean allowed,
                                       @Nonnull Status status,
                                       @Nonnull String reason,
                                       @Nonnull OwnershipPolicyView ownership,
                                       @Nullable ClaimAccessDecisionView claimAccess) {
    public enum Status {
        ALLOWED,
        DENIED_OWNER_PROTECTION,
        DENIED_CLAIM_PROTECTION,
        ALLOWED_FAIL_OPEN,
        ALLOWED_SKIPPED,
        UNAVAILABLE
    }
}
