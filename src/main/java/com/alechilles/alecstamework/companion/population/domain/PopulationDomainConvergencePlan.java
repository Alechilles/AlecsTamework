package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Frozen source-row evidence for one lifecycle population-domain convergence.
 *
 * <p>The source rows are the complete committed rows observed before live work.
 * Each row carries the old persisted deltas and the exact residual to keep after
 * the lifecycle transition. Target rows, when present, are the exact pending
 * rows owned by the current operation.</p>
 */
public record PopulationDomainConvergencePlan(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision sourceLifecycleRevision,
        @Nullable OwnerId sourceOwner,
        @Nullable String sourceWorldKey,
        @Nonnull LifecycleState sourceState,
        @Nullable OwnerId targetOwner,
        @Nullable String targetWorldKey,
        @Nonnull LifecycleState targetState,
        @Nonnull List<SourceRow> sourceRows,
        @Nonnull List<PopulationDomainReservation> targetReservations
) {
    /** Compatibility constructor for source-only convergence plans. */
    public PopulationDomainConvergencePlan(
            ProfileId profileId,
            LifecycleRevision sourceLifecycleRevision,
            OwnerId sourceOwner,
            String sourceWorldKey,
            LifecycleState sourceState,
            OwnerId targetOwner,
            String targetWorldKey,
            LifecycleState targetState,
            List<SourceRow> sourceRows
    ) {
        this(
                profileId,
                sourceLifecycleRevision,
                sourceOwner,
                sourceWorldKey,
                sourceState,
                targetOwner,
                targetWorldKey,
                targetState,
                sourceRows,
                List.of()
        );
    }

    public PopulationDomainConvergencePlan {
        if (profileId == null || sourceLifecycleRevision == null
                || sourceState == null || targetState == null
                || sourceRows == null
                || sourceRows.stream().anyMatch(Objects::isNull)
                || targetReservations == null
                || targetReservations.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Complete population-domain convergence evidence is required"
            );
        }
        sourceWorldKey = normalizeWorld(sourceOwner, sourceWorldKey);
        targetWorldKey = normalizeWorld(targetOwner, targetWorldKey);
        if (sourceOwner == null && !sourceRows.isEmpty()) {
            throw new IllegalArgumentException(
                    "An unowned source cannot retain domain rows"
            );
        }
        Set<RowIdentity> identities = new HashSet<>();
        ArrayList<SourceRow> sorted = new ArrayList<>(sourceRows.size());
        for (SourceRow row : sourceRows) {
            if (!row.expected().profileId().equals(profileId)) {
                throw new IllegalArgumentException(
                        "Convergence rows must belong to the source profile"
                );
            }
            if (!identities.add(RowIdentity.of(row.expected()))) {
                throw new IllegalArgumentException(
                        "Convergence rows must have unique operation and bucket identity"
                );
            }
            sorted.add(row);
        }
        sorted.sort(Comparator.comparing(
                        (SourceRow row) -> row.expected().operationId().toString()
                )
                .thenComparing(row -> row.expected().bucket()));
        sourceRows = List.copyOf(sorted);

        PopulationDomainLifecycleClassifier.Classification targetClassification =
                PopulationDomainLifecycleClassifier.classify(targetState);
        if (targetOwner == null && !targetReservations.isEmpty()) {
            throw new IllegalArgumentException(
                    "An unowned target cannot retain domain rows"
            );
        }
        Set<RowIdentity> targetIdentities = new HashSet<>();
        OperationId targetOperationId = null;
        ArrayList<PopulationDomainReservation> sortedTargets =
                new ArrayList<>(targetReservations.size());
        for (PopulationDomainReservation target : targetReservations) {
            PopulationDomainBucket bucket = target.bucket();
            boolean worldMatches = bucket.scope() == PopulationDomainScope.GLOBAL
                    ? bucket.ownerWorldKey() == null
                    : Objects.equals(
                    bucket.ownerWorldKey(), normalizeWorld(
                            targetOwner, targetWorldKey
                    )
            );
            if (!target.profileId().equals(profileId)
                    || !bucket.ownerId().equals(targetOwner)
                    || !worldMatches
                    || target.ownedDelta() < 0
                    || target.deployableDelta() < 0
                    || (target.ownedDelta() == 0 && target.deployableDelta() == 0)
                    || (target.ownedDelta() > 0 && !targetClassification.owned())
                    || (target.deployableDelta() > 0
                    && !targetClassification.deployable())) {
                throw new IllegalArgumentException(
                        "Target rows must match the target lifecycle"
                );
            }
            if (!targetIdentities.add(RowIdentity.of(target))) {
                throw new IllegalArgumentException(
                        "Target rows must have unique operation and bucket identity"
                );
            }
            if (targetOperationId == null) {
                targetOperationId = target.operationId();
            } else if (!targetOperationId.equals(target.operationId())) {
                throw new IllegalArgumentException(
                        "Target rows must belong to one current operation"
                );
            }
            sortedTargets.add(target);
        }
        sortedTargets.sort(Comparator.comparing(
                        (PopulationDomainReservation row) -> row.operationId().toString()
                )
                .thenComparing(PopulationDomainReservation::bucket));
        targetReservations = List.copyOf(sortedTargets);
    }

    /** Returns whether at least one source row needs a persisted change. */
    public boolean mutatesSourceRows() {
        return sourceRows.stream().anyMatch(SourceRow::changesDeltas);
    }

    /** Returns all source buckets, preserving one entry per distinct bucket. */
    @Nonnull
    public List<PopulationDomainBucket> sourceBuckets() {
        ArrayList<PopulationDomainBucket> buckets = new ArrayList<>();
        for (SourceRow row : sourceRows) {
            if (!buckets.contains(row.expected().bucket())) {
                buckets.add(row.expected().bucket());
            }
        }
        return List.copyOf(buckets);
    }

    private static String normalizeWorld(OwnerId owner, String world) {
        if (owner == null) {
            if (world != null && !world.isBlank()) {
                throw new IllegalArgumentException(
                        "An unowned lifecycle cannot carry an owner world"
                );
            }
            return null;
        }
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException(
                    "An owned lifecycle requires an owner world"
            );
        }
        return world.trim();
    }

    private record RowIdentity(
            String operationId,
            PopulationDomainBucket bucket
    ) {
        private static RowIdentity of(PopulationDomainReservation reservation) {
            return new RowIdentity(
                    reservation.operationId().toString(),
                    reservation.bucket()
            );
        }
    }

    /** One exact prior row and its post-transition residual values. */
    public record SourceRow(
            @Nonnull PopulationDomainReservation expected,
            int residualOwnedDelta,
            int residualDeployableDelta
    ) {
        public SourceRow {
            if (expected == null || residualOwnedDelta < 0
                    || residualDeployableDelta < 0
                    || residualOwnedDelta > expected.ownedDelta()
                    || residualDeployableDelta > expected.deployableDelta()
                    || (residualOwnedDelta == 0 && residualDeployableDelta == 0
                    && expected.ownedDelta() == 0
                    && expected.deployableDelta() == 0)) {
                throw new IllegalArgumentException(
                        "Valid exact source-row residual is required"
                );
            }
        }

        /** Returns whether the persisted row values change. */
        public boolean changesDeltas() {
            return residualOwnedDelta != expected.ownedDelta()
                    || residualDeployableDelta != expected.deployableDelta();
        }

        /** Returns the row evidence expected after convergence, or null after deletion. */
        @Nullable
        public PopulationDomainReservation residualOrNull() {
            if (residualOwnedDelta == 0 && residualDeployableDelta == 0) {
                return null;
            }
            return new PopulationDomainReservation(
                    expected.operationId(),
                    expected.profileId(),
                    expected.expectedLifecycleRevision(),
                    expected.bucket(),
                    residualOwnedDelta,
                    residualDeployableDelta,
                    expected.weight(),
                    expected.snapshottedMaxOwned(),
                    expected.snapshottedMaxDeployable(),
                    expected.providerSnapshotRevision(),
                    expected.managedConfigRevision(),
                    expected.policyRevision(),
                    expected.createdAtMs()
            );
        }
    }
}
