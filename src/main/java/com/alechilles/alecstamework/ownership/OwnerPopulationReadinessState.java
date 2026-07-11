package com.alechilles.alecstamework.ownership;

import java.util.Objects;

/**
 * Lock-confined global/per-world readiness dimensions for the owner index.
 */
final class OwnerPopulationReadinessState {
    private OwnerPopulationReadiness global = OwnerPopulationReadiness.LOADING;
    private OwnerPopulationReadiness perWorld = OwnerPopulationReadiness.LOADING;

    void setBoth(OwnerPopulationReadiness readiness) {
        set(readiness, readiness);
    }

    void set(OwnerPopulationReadiness global, OwnerPopulationReadiness perWorld) {
        this.global = Objects.requireNonNull(global, "global");
        this.perWorld = Objects.requireNonNull(perWorld, "perWorld");
    }

    OwnerPopulationReadiness forScope(OwnerPopulationLimitScope scope) {
        return scope == OwnerPopulationLimitScope.GLOBAL ? global : perWorld;
    }

    OwnerPopulationReadiness overall() {
        if (global == perWorld) {
            return global;
        }
        if (global == OwnerPopulationReadiness.DEGRADED
                || perWorld == OwnerPopulationReadiness.DEGRADED) {
            return OwnerPopulationReadiness.DEGRADED;
        }
        if (global == OwnerPopulationReadiness.RECONCILING
                || perWorld == OwnerPopulationReadiness.RECONCILING) {
            return OwnerPopulationReadiness.RECONCILING;
        }
        return OwnerPopulationReadiness.LOADING;
    }
}
