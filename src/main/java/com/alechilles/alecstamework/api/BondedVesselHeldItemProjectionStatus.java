package com.alechilles.alecstamework.api;

/** Stable result of resolving exact held-item evidence to one authoritative bonded vessel. */
public enum BondedVesselHeldItemProjectionStatus {
    VALID,
    NOT_FOUND,
    NOT_BONDED,
    SOURCE_CHANGED,
    OWNER_MISMATCH,
    STATE_MISMATCH,
    STALE_GENERATION,
    AMBIGUOUS,
    QUARANTINED,
    UNAVAILABLE
}
