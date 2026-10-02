package com.alechilles.alecstamework.api;

/** Stable player-facing categories for unavailable bonded panel actions. */
public enum BondedCompanionActionBlockReason {
    NONE,
    REFRESHING,
    REFRESH_FAILED,
    AUTHORITY_UNAVAILABLE,
    COOLDOWN_ACTIVE,
    CAPACITY_REACHED,
    /** The owner's owned companion limit (general or family) is reached; the active limit is {@link #CAPACITY_REACHED}. */
    OWNED_LIMIT_REACHED,
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
    GENERIC_FAILURE,
    /** The owner's limit of companions out in the world is reached. */
    DEPLOYED_LIMIT_REACHED
}
