package com.alechilles.alecstamework.persistence.incidents;

/** Mutation and recovery domains used for containment and operator circuit breakers. */
public enum PersistenceDomain {
    ALL_PERSISTENCE,
    TAMING_OWNERSHIP,
    OWNER_MUTATION,
    ADMIN_TAMED_SPAWN,
    TAMED_SPAWN,
    CAPTURE_INTAKE,
    CAPTURE_RELEASE,
    MANAGED_COOP_INTAKE,
    MANAGED_COOP_RELEASE,
    MANAGED_COOP_AUTOMATION,
    BREEDING_PAIRING,
    BREEDING_BIRTH,
    BREEDING,
    DEATH_LOST_RECOVERY,
    RECALL_RELOCATION,
    AUTOMATIC_SCOPED_RECOVERY,
    STORAGE
}
