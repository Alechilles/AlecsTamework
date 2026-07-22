package com.alechilles.alecstamework.ownership;

/**
 * Durable lifecycle state for one canonical companion profile.
 *
 * <p>Lifecycle does not decide whether an owner slot is consumed. Every entry with a non-null
 * owner consumes exactly one slot, including dormant entries.
 */
public enum CompanionLifecycleState {
    ACTIVE,
    UNLOADED,
    CAPTURED,
    COOP,
    DEAD_REVIVABLE,
    LOST,
    RESTORING,
    /** A command-roster profile whose projection is being durably removed. */
    STORING,
    /** An owned command-roster profile with no live or item-backed projection. */
    ROSTER_STORED,
    UNKNOWN_DORMANT,
    PROVISIONED_DORMANT,
    RELEASED
}
