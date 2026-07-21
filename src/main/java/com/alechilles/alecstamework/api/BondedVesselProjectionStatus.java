package com.alechilles.alecstamework.api;

/** Durable reconciliation state of a bonded vessel's authoritative item projection. */
public enum BondedVesselProjectionStatus {
    PRESENT,
    MISSING,
    AMBIGUOUS,
    REISSUE_PENDING,
    QUARANTINED,
    UNKNOWN
}
