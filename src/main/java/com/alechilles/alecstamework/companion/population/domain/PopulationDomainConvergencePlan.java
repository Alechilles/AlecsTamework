package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
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
 * the lifecycle transition. The plan does not contain a target admission; a
 * positive target participant remains the owner of that concern.</p>
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
        @Nonnull List<SourceRow> sourceRows
) {
    public PopulationDomainConvergencePlan {
        if (profileId == null || sourceLifecycleRevision == null
                || sourceState == null || targetState == null
                || sourceRows == null
                || sourceRows.stream().anyMatch(Objects::isNull)) {
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
