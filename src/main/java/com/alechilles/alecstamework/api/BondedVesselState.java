package com.alechilles.alecstamework.api;

/** Canonical lifecycle of a bonded companion vessel. */
public enum BondedVesselState {
    STORED,
    SUMMONING,
    ACTIVE,
    STORING,
    DEAD,
    LOST,
    RELEASING,
    RELEASED
}
