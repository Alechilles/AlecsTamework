package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One immutable exact profile observation within a population evidence batch.
 *
 * <p>{@code ownerObserved} distinguishes an explicit unowned observation from a source that
 * cannot attest ownership.</p>
 */
public record PopulationEvidenceObservation(
        @Nonnull PopulationEvidenceBatch.Key batchKey,
        @Nonnull ProfileId profileId,
        boolean ownerObserved,
        @Nullable OwnerId ownerId,
        @Nullable String ownerWorldKey,
        long observedAtMs
) {
    public PopulationEvidenceObservation {
        if (batchKey == null || profileId == null) {
            throw new IllegalArgumentException(
                    "Evidence batch and profile are required"
            );
        }
        ownerWorldKey = normalize(ownerWorldKey);
        if (!ownerObserved && (ownerId != null || ownerWorldKey != null)) {
            throw new IllegalArgumentException(
                    "Unobserved ownership cannot carry owner evidence"
            );
        }
        if (ownerId == null && ownerWorldKey != null) {
            throw new IllegalArgumentException(
                    "Owner-world evidence requires an observed owner"
            );
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
