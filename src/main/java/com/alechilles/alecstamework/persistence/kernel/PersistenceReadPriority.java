package com.alechilles.alecstamework.persistence.kernel;

/** Isolated replacement read lanes so diagnostics cannot starve gameplay-critical reads. */
public enum PersistenceReadPriority {
    GAMEPLAY_CRITICAL,
    DIAGNOSTIC
}
