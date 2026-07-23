package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;

/** Positive group headroom reserved by one shared operation envelope. */
public record PopulationGroupReservation(
        @Nonnull OperationId operationId,
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nonnull PopulationGroupBucket bucket,
        int ownedDelta,
        int activeDelta,
        int snapshottedMaxOwned,
        int snapshottedMaxActive,
        long policyRevision,
        long createdAtMs
) {
    public PopulationGroupReservation {
        if (operationId == null || profileId == null
                || expectedLifecycleRevision == null || bucket == null
                || ownedDelta < 0 || activeDelta < 0
                || activeDelta > ownedDelta
                || (ownedDelta == 0 && activeDelta == 0)
                || snapshottedMaxOwned < 0
                || snapshottedMaxActive < 0
                || policyRevision < 0) {
            throw new IllegalArgumentException(
                    "Valid positive group reservation is required"
            );
        }
    }
}
