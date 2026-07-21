package com.alechilles.alecstamework.api;

/** Mutation intent accepted by the bonded-vessel state machine. */
public enum BondedVesselTransition {
    SUMMON,
    STORE,
    REPAIR_DEAD_TO_STORED,
    RELEASE
}
