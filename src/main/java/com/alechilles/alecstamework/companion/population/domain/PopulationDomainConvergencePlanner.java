package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds exact source-row residuals from canonical lifecycle and owner evidence. */
public final class PopulationDomainConvergencePlanner {
    private PopulationDomainConvergencePlanner() {
    }

    /**
     * Plans source-row convergence without consulting a provider or inventing a
     * target claim. The supplied rows must be the complete committed rows for the
     * profile as read from the connection-bound domain authority.
     */
    @Nonnull
    public static PopulationDomainConvergencePlan plan(
            @Nonnull ProfileId profileId,
            @Nonnull LifecycleRevision sourceLifecycleRevision,
            @Nullable OwnerId sourceOwner,
            @Nullable String sourceWorldKey,
            @Nonnull LifecycleState sourceState,
            @Nullable OwnerId targetOwner,
            @Nullable String targetWorldKey,
            @Nonnull LifecycleState targetState,
            @Nonnull List<PopulationDomainReservation> committedRows
    ) {
        return plan(
                profileId,
                sourceLifecycleRevision,
                sourceOwner,
                sourceWorldKey,
                sourceState,
                targetOwner,
                targetWorldKey,
                targetState,
                committedRows,
                List.of()
        );
    }

    /** Builds a plan with the exact pending target rows owned by the current operation. */
    @Nonnull
    public static PopulationDomainConvergencePlan plan(
            @Nonnull ProfileId profileId,
            @Nonnull LifecycleRevision sourceLifecycleRevision,
            @Nullable OwnerId sourceOwner,
            @Nullable String sourceWorldKey,
            @Nonnull LifecycleState sourceState,
            @Nullable OwnerId targetOwner,
            @Nullable String targetWorldKey,
            @Nonnull LifecycleState targetState,
            @Nonnull List<PopulationDomainReservation> committedRows,
            @Nonnull List<PopulationDomainReservation> targetReservations
    ) {
        require(profileId, "Profile ID");
        require(sourceLifecycleRevision, "Source lifecycle revision");
        require(sourceState, "Source lifecycle state");
        require(targetState, "Target lifecycle state");
        require(committedRows, "Committed domain rows");
        require(targetReservations, "Target domain rows");
        if (committedRows.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Committed domain rows cannot be null");
        }
        if (sourceOwner == null && !committedRows.isEmpty()) {
            throw new IllegalStateException(
                    "population_domain_source_owner_missing"
            );
        }
        PopulationDomainLifecycleClassifier.Classification sourceClassification =
                PopulationDomainLifecycleClassifier.classify(sourceState);
        PopulationDomainLifecycleClassifier.Classification targetClassification =
                PopulationDomainLifecycleClassifier.classify(targetState);
        boolean sameOwnerBucket = Objects.equals(sourceOwner, targetOwner)
                && Objects.equals(normalize(sourceWorldKey), normalize(targetWorldKey));
        boolean sameConsumption = sourceClassification.owned()
                == targetClassification.owned()
                && sourceClassification.deployable()
                == targetClassification.deployable();
        if (committedRows.isEmpty()
                && sourceOwner != null
                && (sourceClassification.owned() || sourceClassification.deployable())
                && (!sameOwnerBucket || !sameConsumption)) {
            throw new IllegalStateException(
                    "population_domain_source_rows_missing"
            );
        }

        List<PopulationDomainConvergencePlan.SourceRow> rows = committedRows.stream()
                .map(row -> sourceRow(
                        row,
                        profileId,
                        sourceOwner,
                        sourceWorldKey,
                        targetOwner,
                        targetWorldKey,
                        sourceClassification,
                        targetClassification
                ))
                .toList();
        return new PopulationDomainConvergencePlan(
                profileId,
                sourceLifecycleRevision,
                sourceOwner,
                sourceWorldKey,
                sourceState,
                targetOwner,
                targetWorldKey,
                targetState,
                rows,
                targetReservations
        );
    }

    private static PopulationDomainConvergencePlan.SourceRow sourceRow(
            PopulationDomainReservation row,
            ProfileId profileId,
            OwnerId sourceOwner,
            String sourceWorldKey,
            OwnerId targetOwner,
            String targetWorldKey,
            PopulationDomainLifecycleClassifier.Classification source,
            PopulationDomainLifecycleClassifier.Classification target
    ) {
        if (!row.profileId().equals(profileId)) {
            throw new IllegalStateException(
                    "population_domain_source_profile_mismatch"
            );
        }
        if (!row.bucket().ownerId().equals(sourceOwner)
                || (row.bucket().scope() == PopulationDomainScope.PER_WORLD
                && !Objects.equals(row.bucket().ownerWorldKey(), normalize(sourceWorldKey)))) {
            throw new IllegalStateException(
                    "population_domain_source_bucket_mismatch"
            );
        }
        if (row.ownedDelta() > 0 && !source.owned()
                || row.deployableDelta() > 0 && !source.deployable()) {
            throw new IllegalStateException(
                    "population_domain_source_state_mismatch"
            );
        }
        boolean sameBucket = row.bucket().ownerId().equals(targetOwner)
                && (row.bucket().scope() == PopulationDomainScope.GLOBAL
                || Objects.equals(
                row.bucket().ownerWorldKey(), normalize(targetWorldKey)
        ));
        int residualOwned = sameBucket && target.owned()
                ? row.ownedDelta() : 0;
        int residualDeployable = sameBucket && target.deployable()
                ? row.deployableDelta() : 0;
        return new PopulationDomainConvergencePlan.SourceRow(
                row, residualOwned, residualDeployable
        );
    }

    private static String normalize(String worldKey) {
        return worldKey == null || worldKey.isBlank() ? null : worldKey.trim();
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
