package com.alechilles.alecstamework.companion.population;

import java.util.ArrayList;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Derives only positive capacity deltas from one exact owner transition. */
public final class OwnerPopulationAdmissionPlanner {
    private OwnerPopulationAdmissionPlanner() {
    }

    /**
     * Returns reservation work only for target scopes whose committed count can increase.
     */
    @Nonnull
    public static Optional<OwnerPopulationAdmissionPlan> plan(
            @Nonnull OwnerPopulationTransitionRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Population transition request is required"
            );
        }
        if (request.targetOwnerId() == null) {
            return Optional.empty();
        }
        ArrayList<OwnerPopulationAdmissionPlan.LimitIncrease> increases =
                new ArrayList<>();
        boolean ownerChanged = !request.targetOwnerId().equals(
                request.expectedOwnerId()
        );
        if (ownerChanged) {
            increases.add(increase(
                    OwnerPopulationScope.global(request.targetOwnerId()),
                    request.globalLimit()
            ));
        }
        if (request.targetOwnerWorldKey() != null
                && (ownerChanged || !request.targetOwnerWorldKey().equals(
                request.expectedOwnerWorldKey()
        ))) {
            increases.add(increase(
                    OwnerPopulationScope.perWorld(
                            request.targetOwnerId(),
                            request.targetOwnerWorldKey()
                    ),
                    request.perWorldLimit()
            ));
        }
        return increases.isEmpty()
                ? Optional.empty()
                : Optional.of(new OwnerPopulationAdmissionPlan(
                        request.profileId(),
                        request.expectedLifecycleRevision(),
                        increases
                ));
    }

    private static OwnerPopulationAdmissionPlan.LimitIncrease increase(
            OwnerPopulationScope scope,
            int limit
    ) {
        return new OwnerPopulationAdmissionPlan.LimitIncrease(
                scope,
                1,
                limit
        );
    }
}
