package com.alechilles.alecstamework.api;

/** Projection outcome reported independently from canonical profile creation. */
public enum CompanionProvisioningProjectionStatus {
    NOT_REQUESTED,
    PENDING,
    ACTIVE,
    FAILED_RECOVERABLE,
    UNAVAILABLE
}
