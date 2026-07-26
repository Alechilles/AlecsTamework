package com.alechilles.alecstamework.api;

/** Stable result codes returned by every bonded-companion operation. */
public enum BondedCompanionResultCode {
    UNAVAILABLE,
    SUCCESS,
    NOT_FOUND,
    NOT_OWNER,
    INVALID_STATE,
    REVISION_CONFLICT,
    POLICY_DENIED,
    WORLD_UNAVAILABLE,
    VALIDATION_FAILED,
    INTERNAL_FAILURE
}
