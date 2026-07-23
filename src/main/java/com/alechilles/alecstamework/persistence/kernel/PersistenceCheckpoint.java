package com.alechilles.alecstamework.persistence.kernel;

/** Deterministic fault-injection boundaries in the replacement transaction lifecycle. */
public enum PersistenceCheckpoint {
    BEFORE_BEGIN,
    AFTER_BEGIN,
    BEFORE_COMMIT,
    COMMIT_RETURNED,
    AFTER_COMMIT,
    CLOSE
}
