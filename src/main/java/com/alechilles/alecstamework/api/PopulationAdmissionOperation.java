package com.alechilles.alecstamework.api;

/** Stable public operation classification for mutation-bound owner and claim admissions. */
public enum PopulationAdmissionOperation {
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
