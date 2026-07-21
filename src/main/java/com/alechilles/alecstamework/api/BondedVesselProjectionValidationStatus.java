package com.alechilles.alecstamework.api;

/** Outcome of checking one supplied item or live-entity projection. */
public enum BondedVesselProjectionValidationStatus {
    CONSISTENT,
    PENDING,
    MISSING,
    DUPLICATE,
    STALE_GENERATION,
    QUARANTINED,
    UNKNOWN
}
