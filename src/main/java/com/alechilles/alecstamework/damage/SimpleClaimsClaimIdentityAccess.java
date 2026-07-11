package com.alechilles.alecstamework.damage;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Claim-identity-only access used by the raw damage policy API. */
@FunctionalInterface
interface SimpleClaimsClaimIdentityAccess {
    @Nonnull
    Result lookup(@Nullable String worldName, @Nullable Vector3d position);

    enum Status {
        CLAIM_FOUND,
        NO_CLAIM,
        UNAVAILABLE,
        ERROR
    }

    record Result(@Nonnull Status status,
                  @Nullable UUID claimPartyId,
                  @Nullable String message) {
    }
}
