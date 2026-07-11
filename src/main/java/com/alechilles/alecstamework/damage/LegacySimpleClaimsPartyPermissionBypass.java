package com.alechilles.alecstamework.damage;

import org.joml.Vector3d;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One-release compatibility seam for the historical raw SimpleClaims party-key grant. */
@FunctionalInterface
interface LegacySimpleClaimsPartyPermissionBypass {
    @Nonnull
    Result evaluate(@Nullable String worldName,
                    @Nullable Vector3d position,
                    @Nonnull UUID attackerPlayerUuid,
                    @Nonnull String permissionKey);

    enum Status {
        GRANTED,
        NOT_GRANTED,
        UNAVAILABLE,
        ERROR
    }

    record Result(@Nonnull Status status,
                  @Nullable UUID claimPartyId,
                  @Nullable String message) {
        @Nonnull
        static Result notGranted() {
            return new Result(Status.NOT_GRANTED, null, null);
        }
    }
}
