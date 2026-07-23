package com.alechilles.alecstamework.persistence.kernel;

/** Stable outcome vocabulary for compare-and-mutate persistence stores. */
public enum PersistenceMutationStatus {
    APPLIED,
    NOT_FOUND,
    REVISION_MISMATCH,
    CONFLICT,
    FENCE_MISMATCH
}
