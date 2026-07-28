package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable active-lease summary; a zero expiry means an unlimited lease. */
public record BondedCompanionLeaseView(
        @Nonnull String leaseToken,
        @Nullable UUID liveNpcUuid,
        @Nonnull String worldKey,
        long startedAtMs,
        long expiresAtMs
) {
    public BondedCompanionLeaseView {
        leaseToken = requireText(leaseToken, "leaseToken");
        worldKey = requireText(worldKey, "worldKey");
        if (expiresAtMs != 0L && expiresAtMs < startedAtMs) {
            throw new IllegalArgumentException(
                    "expiresAtMs must be zero or not precede startedAtMs."
            );
        }
    }

    public boolean unlimited() {
        return expiresAtMs == 0L;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
