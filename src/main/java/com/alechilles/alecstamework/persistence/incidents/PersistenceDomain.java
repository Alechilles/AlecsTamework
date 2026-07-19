package com.alechilles.alecstamework.persistence.incidents;

/** Mutation and recovery domains used for containment and operator circuit breakers. */
public enum PersistenceDomain {
    ALL_PERSISTENCE,
    OWNER_MUTATION,
    TAMED_SPAWN,
    CAPTURE_RELEASE,
    MANAGED_COOP_INTAKE,
    MANAGED_COOP_RELEASE,
    BREEDING,
    DEATH_LOST_RECOVERY,
    RECALL_RELOCATION,
    RECONCILIATION,
    STORAGE
}
