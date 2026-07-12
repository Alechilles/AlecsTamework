package com.alechilles.alecstamework.integration.claims;

/**
 * Completeness state for the durable physical-occupancy index.
 */
public enum ClaimOccupancyReadiness {
    LOADING,
    RECONCILING,
    READY,
    DEGRADED;

    public boolean allowsPositiveAdmissions() {
        return this == READY;
    }
}
