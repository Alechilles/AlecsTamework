package com.alechilles.alecstamework.npc.breeding;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable provider-neutral identity for one claim population-cap scope. */
public record BreedingClaimCapacityScope(@Nonnull String providerId,
                                         @Nonnull String worldId,
                                         @Nonnull String claimId) {
    public BreedingClaimCapacityScope {
        providerId = requireNonBlank(providerId, "providerId");
        worldId = requireNonBlank(worldId, "worldId");
        claimId = requireNonBlank(claimId, "claimId");
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }
}
