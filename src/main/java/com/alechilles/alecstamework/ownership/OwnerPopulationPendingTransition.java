package com.alechilles.alecstamework.ownership;

import java.util.Set;

/** Lock-confined reservation state retained by {@link OwnerPopulationIndex}. */
record OwnerPopulationPendingTransition(OwnerPopulationReservation reservation,
                                        OwnerPopulationTransitionRequest request,
                                        OwnerPopulationEntry current,
                                        OwnerPopulationEntry proposed,
                                        Set<OwnerPopulationScopeKey> additions,
                                        long expiresAtNanos) {
    OwnerPopulationPendingTransition {
        additions = Set.copyOf(additions);
    }
}
