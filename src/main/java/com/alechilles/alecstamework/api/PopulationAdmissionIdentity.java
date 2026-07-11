package com.alechilles.alecstamework.api;

import javax.annotation.Nullable;

/**
 * Stable identity used to make a mutation-bound admission idempotent.
 *
 * <p>A canonical and provisional profile are mutually exclusive. An idempotency key may accompany
 * either identity, or stand alone while the authority allocates a provisional profile.
 */
public record PopulationAdmissionIdentity(@Nullable String canonicalProfileId,
                                          @Nullable String provisionalProfileId,
                                          @Nullable String idempotencyKey) {
    public PopulationAdmissionIdentity {
        canonicalProfileId = normalize(canonicalProfileId);
        provisionalProfileId = normalize(provisionalProfileId);
        idempotencyKey = normalize(idempotencyKey);
        if (canonicalProfileId != null && provisionalProfileId != null) {
            throw new IllegalArgumentException("Canonical and provisional profile ids are mutually exclusive.");
        }
        if (canonicalProfileId == null && provisionalProfileId == null && idempotencyKey == null) {
            throw new IllegalArgumentException(
                    "A canonical profile id, provisional profile id, or idempotency key is required."
            );
        }
    }

    public boolean canonical() {
        return canonicalProfileId != null;
    }

    public boolean provisional() {
        return provisionalProfileId != null;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
