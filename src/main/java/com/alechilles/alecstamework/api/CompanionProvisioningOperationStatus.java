package com.alechilles.alecstamework.api;

/** Durable restart-visible stage of a provisioning or projection operation. */
public enum CompanionProvisioningOperationStatus {
    PREPARING,
    PREPARED,
    APPLYING,
    DORMANT_COMMITTED,
    PROJECTING,
    COMMITTED,
    PARTIAL_DORMANT,
    CANCELED,
    TERMINAL_DENIED,
    QUARANTINED
}
