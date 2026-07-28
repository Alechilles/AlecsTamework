package com.alechilles.alecstamework.companion.bonded;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Selects the best available live evidence and stable recovery reason. */
final class BondedCompanionProjectionRecoveryEvidence {
    private BondedCompanionProjectionRecoveryEvidence() {
    }

    @Nullable
    static BondedCompanionSnapshot snapshot(
            @Nonnull BondedCompanionProjectionValidator.Validation validation
    ) {
        if (validation.validProjection() != null) {
            return validation.validProjection().snapshot();
        }
        for (var projection : validation.exactMatches()) {
            if (projection.snapshot() != null) {
                return projection.snapshot();
            }
        }
        return null;
    }

    @Nonnull
    static String reason(
            @Nonnull BondedCompanionProjectionService.RecoveryCause cause,
            @Nonnull BondedCompanionProjectionValidator.Status status
    ) {
        return switch (cause) {
            case EXPIRED -> "LEASE_EXPIRED";
            case WORLD_TRANSFER -> "WORLD_TRANSFER";
            case LOGOUT -> "LOGOUT";
            case STARTUP -> status
                    == BondedCompanionProjectionValidator.Status.MISSING
                    ? "PROJECTION_MISSING" : status.name();
            case WORLD_LOAD, PLAYER_JOIN, MISSING_SCAN -> status.name();
        };
    }
}
