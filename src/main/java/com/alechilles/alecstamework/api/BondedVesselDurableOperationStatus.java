package com.alechilles.alecstamework.api;

/** Durable, restart-visible stage of a bonded-vessel operation journal. */
public enum BondedVesselDurableOperationStatus {
    PREPARED,
    APPLYING,
    APPLIED,
    COMMITTED,
    CANCELED,
    TERMINAL_DENIED,
    QUARANTINED
}
