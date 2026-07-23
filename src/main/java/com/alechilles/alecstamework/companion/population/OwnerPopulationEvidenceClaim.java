package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable reference to exact positive evidence or a complete sealed-absence proof.
 */
public record OwnerPopulationEvidenceClaim(
        @Nonnull Kind kind,
        @Nonnull String bootId,
        @Nonnull String worldKey,
        @Nonnull ReconciliationGeneration generation,
        @Nullable PopulationEvidenceBatch.Source source,
        @Nullable OwnerId observedOwnerId,
        @Nullable String observedOwnerWorldKey
) {
    public OwnerPopulationEvidenceClaim {
        if (kind == null || generation == null) {
            throw new IllegalArgumentException(
                    "Population evidence claim kind and generation are required"
            );
        }
        bootId = requireText(bootId, "Population evidence boot ID");
        worldKey = requireText(worldKey, "Population evidence world");
        observedOwnerWorldKey = normalize(observedOwnerWorldKey);
        if (kind == Kind.POSITIVE && source == null) {
            throw new IllegalArgumentException(
                    "Positive evidence claim requires one source"
            );
        }
        if (kind == Kind.ABSENCE
                && (source != null || observedOwnerId != null
                || observedOwnerWorldKey != null)) {
            throw new IllegalArgumentException(
                    "Absence claim cannot carry positive observation evidence"
            );
        }
        if (observedOwnerId == null && observedOwnerWorldKey != null) {
            throw new IllegalArgumentException(
                    "Observed owner world requires an owner"
            );
        }
        if (kind == Kind.POSITIVE
                && observedOwnerId != null
                && observedOwnerWorldKey == null) {
            throw new IllegalArgumentException(
                    "Owned positive evidence requires an owner world"
            );
        }
    }

    /** Creates an immutable claim from one exact owner-observing row. */
    @Nonnull
    public static OwnerPopulationEvidenceClaim positive(
            @Nonnull PopulationEvidenceObservation observation
    ) {
        if (observation == null || !observation.ownerObserved()) {
            throw new IllegalArgumentException(
                    "Positive reconciliation claim requires observed ownership"
            );
        }
        return new OwnerPopulationEvidenceClaim(
                Kind.POSITIVE,
                observation.batchKey().bootId(),
                observation.batchKey().worldKey(),
                observation.batchKey().generation(),
                observation.batchKey().source(),
                observation.ownerId(),
                observation.ownerWorldKey()
        );
    }

    /** Creates a claim requiring both matching disk and live batches to be sealed. */
    @Nonnull
    public static OwnerPopulationEvidenceClaim absence(
            @Nonnull String bootId,
            @Nonnull String worldKey,
            @Nonnull ReconciliationGeneration generation
    ) {
        return new OwnerPopulationEvidenceClaim(
                Kind.ABSENCE,
                bootId,
                worldKey,
                generation,
                null,
                null,
                null
        );
    }

    /** Returns the exact source key of a positive claim. */
    @Nonnull
    public PopulationEvidenceBatch.Key positiveBatchKey() {
        if (kind != Kind.POSITIVE) {
            throw new IllegalStateException(
                    "Absence claim has no single source batch"
            );
        }
        return new PopulationEvidenceBatch.Key(
                bootId, worldKey, generation, source
        );
    }

    /** Proves a stored observation is exactly the immutable positive claim. */
    public boolean matches(@Nonnull PopulationEvidenceObservation observation) {
        return kind == Kind.POSITIVE
                && observation != null
                && observation.ownerObserved()
                && observation.batchKey().equals(positiveBatchKey())
                && Objects.equals(
                observation.ownerId(), observedOwnerId
        )
                && Objects.equals(
                observation.ownerWorldKey(), observedOwnerWorldKey
        );
    }

    public enum Kind {
        POSITIVE,
        ABSENCE
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
