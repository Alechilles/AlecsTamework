package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Immutable exact live-projection lease for one bonded profile. */
public record SqliteBondedCompanionLeaseRow(
        @Nonnull String profileId,
        @Nonnull String leaseToken,
        @Nonnull UUID liveNpcUuid,
        @Nonnull String worldKey,
        long startedAtMs,
        long expiresAtMs,
        @Nonnull String projectionState
) {
    public SqliteBondedCompanionLeaseRow {
        profileId = requireText(profileId, "profileId");
        leaseToken = requireText(leaseToken, "leaseToken");
        liveNpcUuid = Objects.requireNonNull(liveNpcUuid, "liveNpcUuid");
        worldKey = requireText(worldKey, "worldKey");
        projectionState = requireText(projectionState, "projectionState");
        if (expiresAtMs != 0 && expiresAtMs < startedAtMs) {
            throw new IllegalArgumentException(
                    "Lease expiry must be zero or not precede its start"
            );
        }
    }

    /** Returns whether zero expiry grants an unlimited lease. */
    public boolean unlimited() {
        return expiresAtMs == 0;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
