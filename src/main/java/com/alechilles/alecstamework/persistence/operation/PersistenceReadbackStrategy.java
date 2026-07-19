package com.alechilles.alecstamework.persistence.operation;

/** Declares how a write can establish its outcome after a commit-boundary failure. */
public enum PersistenceReadbackStrategy {
    NONE,
    OPERATION_JOURNAL,
    CANONICAL_ROW,
    SOURCE_AND_CANONICAL,
    CUSTOM_VERIFIER
}
