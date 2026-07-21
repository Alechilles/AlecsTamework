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
    UNKNOWN_DORMANT,
    PROVISIONED_DORMANT,
    RELEASED
}
