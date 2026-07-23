package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import java.util.List;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable positive capacity changes to reserve for one existing shared operation.
 *
 * @param profileId profile whose canonical lifecycle will change
 * @param expectedLifecycleRevision exact source revision, or null for a new profile
 * @param increases unique owner scopes that require positive headroom
 */
public record OwnerPopulationAdmissionPlan(
        @Nonnull ProfileId profileId,
        @Nullable LifecycleRevision expectedLifecycleRevision,
        @Nonnull List<LimitIncrease> increases
) {
    public OwnerPopulationAdmissionPlan {
        if (profileId == null || increases == null || increases.isEmpty()
                || increases.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Population admission profile and positive increases are required"
            );
        }
        TreeSet<OwnerPopulationScope> scopes = new TreeSet<>();
        java.util.HashSet<OwnerId> owners = new java.util.HashSet<>();
        for (LimitIncrease increase : increases) {
            if (!scopes.add(increase.scope())) {
                throw new IllegalArgumentException(
                        "Population admission scopes must be unique"
                );
            }
            owners.add(increase.scope().ownerId());
        }
        if (owners.size() != 1) {
            throw new IllegalArgumentException(
                    "One profile admission can increase only one owner's capacity"
            );
        }
        increases = increases.stream()
                .sorted(java.util.Comparator.comparing(LimitIncrease::scope))
                .toList();
    }

    /** One positive capacity delta with its preparation-time limit snapshot. */
    public record LimitIncrease(
            @Nonnull OwnerPopulationScope scope,
            int capacityDelta,
            int snapshottedLimit
    ) {
        public LimitIncrease {
            if (scope == null || capacityDelta <= 0 || snapshottedLimit < 0) {
                throw new IllegalArgumentException(
                        "Positive population increase and non-negative limit are required"
                );
            }
        }
    }
}
