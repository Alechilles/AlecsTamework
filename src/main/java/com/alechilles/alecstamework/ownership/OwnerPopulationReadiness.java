package com.alechilles.alecstamework.ownership;

/** Describes whether the owner index can safely admit positive capped transitions. */
public enum OwnerPopulationReadiness {
    LOADING,
    RECONCILING,
    READY,
    DEGRADED;

    /** Only a fully reconciled index may authorize a positive, non-forced capped delta. */
    public boolean allowsPositiveCappedAdmissions() {
        return this == READY;
    }
}
