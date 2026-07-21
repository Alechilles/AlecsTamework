package com.alechilles.alecstamework.api;

/** Durable, restart-visible stage of one versioned profile-data mutation. */
public enum ProfileDataOperationStatus {
    PREPARED,
    APPLYING,
    COMMITTED,
    TERMINAL_DENIED,
    QUARANTINED
}
