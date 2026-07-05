package com.alechilles.alecstamework.integration.claims;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stable provider-neutral identity for one claim population bucket.
 */
public record ClaimPopulationKey(@Nonnull String providerId,
                                 @Nonnull String worldName,
                                 @Nonnull String ownerType,
                                 @Nonnull UUID ownerId,
                                 @Nullable String claimId) {
    public ClaimPopulationKey {
        providerId = requireText(providerId, "providerId");
        worldName = requireText(worldName, "worldName");
        ownerType = requireText(ownerType, "ownerType");
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        claimId = normalizeOptional(claimId);
    }

    @Nonnull
    public static ClaimPopulationKey simpleClaims(@Nonnull String worldName, @Nonnull UUID partyId) {
        return new ClaimPopulationKey("simpleclaims", worldName, "PARTY", partyId, partyId.toString());
    }

    @Nonnull
    public static ClaimPopulationKey questLines(@Nonnull String worldName,
                                                @Nonnull String ownerType,
                                                @Nonnull UUID ownerId,
                                                @Nullable Object claimId) {
        return new ClaimPopulationKey(
                "questlines-claims",
                worldName,
                ownerType,
                ownerId,
                claimId == null ? null : String.valueOf(claimId)
        );
    }

    @Nonnull
    private static String requireText(@Nullable String value, @Nonnull String fieldName) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }

    @Nullable
    private static String normalizeOptional(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
