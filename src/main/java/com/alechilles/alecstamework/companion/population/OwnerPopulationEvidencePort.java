package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Connection-bound authority for sealed owner-population reconciliation evidence. */
public interface OwnerPopulationEvidencePort {
    @Nonnull
    Optional<PopulationEvidenceBatch> findBatch(
            @Nonnull PopulationEvidenceBatch.Key key
    );

    @Nonnull
    Optional<PopulationEvidenceObservation> findObservation(
            @Nonnull PopulationEvidenceBatch.Key key,
            @Nonnull ProfileId profileId
    );

    /** Opens one idempotent source batch. */
    @Nonnull
    PersistenceMutationResult<PopulationEvidenceBatch> open(
            @Nonnull PopulationEvidenceBatch batch
    );

    /** Adds immutable positive evidence while its source batch remains open. */
    @Nonnull
    PersistenceMutationResult<PopulationEvidenceObservation> observe(
            @Nonnull PopulationEvidenceObservation observation
    );

    /** Seals or fails one exact open batch. */
    @Nonnull
    PersistenceMutationResult<PopulationEvidenceBatch> close(
            @Nonnull PopulationEvidenceBatch.Key key,
            @Nonnull PopulationEvidenceBatch.Status result,
            long closedAtMs,
            @Nullable String failureCode
    );

    /** Assesses one exact positive observation against canonical owner evidence. */
    @Nonnull
    PopulationEvidenceAssessment assessPositive(
            @Nonnull PopulationEvidenceBatch.Key key,
            @Nonnull ProfileId profileId,
            @Nullable OwnerId expectedOwnerId,
            @Nullable String expectedOwnerWorldKey
    );

    /** Proves absence only from matching sealed disk and live source batches. */
    @Nonnull
    PopulationEvidenceAssessment assessAbsence(
            @Nonnull String bootId,
            @Nonnull String worldKey,
            @Nonnull ReconciliationGeneration generation,
            @Nonnull ProfileId profileId
    );
}
