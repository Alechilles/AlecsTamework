package com.alechilles.alecstamework.ownership;

/** Identifies the gameplay intent behind an owner population transition. */
public enum OwnerPopulationOperation {
    NEW_OWNERSHIP,
    OWNER_TRANSFER,
    OWNER_CLEAR,
    RESTORE,
    REHOME,
    LIFECYCLE_CHANGE,
    LEGACY_ADOPTION,
    BREEDING,
    ADMIN_FORCE
}
