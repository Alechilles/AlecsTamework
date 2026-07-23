package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact canonical owner state committed by one population transition. */
public record OwnerPopulationTransitionOutcome(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision sourceRevision,
        @Nonnull LifecycleRevision committedRevision,
        @Nullable OwnerId ownerId,
        @Nullable String ownerWorldKey,
        long updatedAtMs
) {
    public OwnerPopulationTransitionOutcome {
        if (profileId == null || sourceRevision == null
                || committedRevision == null
                || !sourceRevision.next().equals(committedRevision)) {
            throw new IllegalArgumentException(
                    "Population outcome must commit one lifecycle revision"
            );
        }
        ownerWorldKey = ownerWorldKey == null || ownerWorldKey.isBlank()
                ? null
                : ownerWorldKey.trim();
        if (ownerId == null && ownerWorldKey != null) {
            throw new IllegalArgumentException(
                    "Unowned population outcome cannot carry an owner world"
            );
        }
    }
}
