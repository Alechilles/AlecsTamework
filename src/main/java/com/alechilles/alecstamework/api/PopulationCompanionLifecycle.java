package com.alechilles.alecstamework.api;

/** Durable companion lifecycle requested by a mutation-bound population admission. */
public enum PopulationCompanionLifecycle {
    ACTIVE,
    UNLOADED,
    CAPTURED,
    COOP,
    DEAD_REVIVABLE,
    LOST,
    RESTORING,
    STORING,
    ROSTER_STORED,
    UNKNOWN_DORMANT,
    PROVISIONED_DORMANT,
    RELEASED;

    /** Active and unloaded companions occupy their physical claim location while owned. */
    public boolean occupiesPhysicalClaim() {
        return this == ACTIVE || this == UNLOADED || this == STORING;
    }
}
