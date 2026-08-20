package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Positive weighted domain capacity reserved by one operation envelope. */
public record PopulationDomainReservation(
        @Nonnull OperationId operationId,
        @Nonnull ProfileId profileId,
        @Nullable LifecycleRevision expectedLifecycleRevision,
        @Nonnull PopulationDomainBucket bucket,
        int ownedDelta,
        int deployableDelta,
        int weight,
        int snapshottedMaxOwned,
        int snapshottedMaxDeployable,
        long providerSnapshotRevision,
        long managedConfigRevision,
        long policyRevision,
        long createdAtMs
) {
    public PopulationDomainReservation {
        if (operationId == null || profileId == null || bucket == null
                || ownedDelta < 0 || deployableDelta < 0
                || (ownedDelta == 0 && deployableDelta == 0)
                || weight <= 0 || snapshottedMaxOwned < 0
                || snapshottedMaxDeployable < 0
                || providerSnapshotRevision < 0 || managedConfigRevision < 0
                || policyRevision < 0) {
            throw new IllegalArgumentException("Valid positive domain reservation is required");
        }
    }

    /** Weighted cost for the owned claim. */
    public long weightedOwnedDelta() {
        return Math.multiplyExact((long) weight, ownedDelta);
    }

    /** Weighted cost for the deployable claim. */
    public long weightedDeployableDelta() {
        return Math.multiplyExact((long) weight, deployableDelta);
    }
}
