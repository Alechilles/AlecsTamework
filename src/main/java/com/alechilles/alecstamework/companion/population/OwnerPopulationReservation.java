package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Positive owner-capacity reservation attached to one shared operation envelope.
 *
 * @param operationId owning shared operation
 * @param profileId profile whose canonical lifecycle will change
 * @param expectedLifecycleRevision exact source revision, or null for a new profile
 * @param scope capacity bucket increased by the transition
 * @param capacityDelta positive requested headroom
 * @param snapshottedLimit configured cap, or zero when disabled
 * @param createdAtMs signed persisted preparation time
 */
public record OwnerPopulationReservation(
        @Nonnull OperationId operationId,
        @Nonnull ProfileId profileId,
        @Nullable LifecycleRevision expectedLifecycleRevision,
        @Nonnull OwnerPopulationScope scope,
        int capacityDelta,
        int snapshottedLimit,
        long createdAtMs
) {
    public OwnerPopulationReservation {
        if (operationId == null || profileId == null || scope == null) {
            throw new IllegalArgumentException(
                    "Population reservation identity and scope are required"
            );
        }
        if (capacityDelta <= 0 || snapshottedLimit < 0) {
            throw new IllegalArgumentException(
                    "Population reservation delta must be positive and limit non-negative"
            );
        }
    }
}

