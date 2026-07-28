package com.alechilles.alecstamework.companion.bonded;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable lease for one disposable live projection. */
public record BondedCompanionLease(
        @Nonnull String leaseToken,
        @Nonnull String worldKey,
        long startedAtMs,
        long expiresAtMs
) {
    public BondedCompanionLease {
        leaseToken = text(leaseToken, "leaseToken");
        worldKey = text(worldKey, "worldKey");
        if (expiresAtMs != 0L && expiresAtMs < startedAtMs) {
            throw new IllegalArgumentException(
                    "expiresAtMs must be zero or not precede startedAtMs"
            );
        }
    }

    /** Returns whether policy gave this lease no expiry. */
    public boolean unlimited() {
        return expiresAtMs == 0L;
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
