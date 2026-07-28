package com.alechilles.alecstamework.api;

/** Stable player-facing categories for unavailable bonded panel actions. */
public enum BondedCompanionActionBlockReason {
    NONE,
    REFRESHING,
    REFRESH_FAILED,
    AUTHORITY_UNAVAILABLE,
    COOLDOWN_ACTIVE,
    CAPACITY_REACHED,
    FEATURE_DISABLED,
    POLICY_DENIED,
    ROLE_NOT_ALLOWED,
    PLACEMENT_UNAVAILABLE,
    WORLD_UNAVAILABLE,
    PAYMENT_UNAVAILABLE,
    REVISION_CONFLICT,
    NOT_FOUND,
    NOT_OWNER,
    INVALID_STATE,
    VALIDATION_FAILED,
    GENERIC_FAILURE
}
