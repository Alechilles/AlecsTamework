package com.alechilles.alecstamework.damage;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Claim-only view of the shared SimpleClaims damage policy. */
public record SimpleClaimsRawAccessDecision(boolean available,
                                            boolean allowed,
                                            @Nonnull Status status,
                                            @Nonnull String reason,
                                            @Nullable UUID claimPartyId) {
    public enum Status {
        ALLOWED,
        DENIED,
        ALLOW_FAIL_OPEN,
        SKIPPED,
        UNAVAILABLE
    }
}
