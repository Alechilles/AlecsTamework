package com.alechilles.alecstamework.persistence.health;

/** Result categories exposed to mutation front doors and player feedback. */
public enum PersistenceMutationAvailabilityStatus {
    ALLOW,
    RETRYABLE_DENIAL,
    QUARANTINED,
    AUTHORITY_NOT_READY,
    FEATURE_PAUSED,
    GLOBAL_READ_ONLY
}
