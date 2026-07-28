package com.alechilles.alecstamework.persistence.control;

/** Readiness derived from startup evidence and control-plane containment. */
public enum PersistenceReadinessLevel {
    CLOSED,
    CANONICAL_READ_ONLY,
    RECOVERING,
    PROJECTION_READY,
    WORLD_EVIDENCE_PENDING,
    MUTATION_READY,
    QUARANTINED,
    GLOBAL_READ_ONLY
}
