package com.alechilles.alecstamework.persistence.incidents;

/** Durable quarantine scope types ordered from exact operations to global authority. */
public enum PersistenceScopeType {
    OPERATION,
    PROFILE,
    OWNER_GLOBAL,
    OWNER_WORLD,
    CLAIM,
    COOP_AUTHORITY,
    COOP_SLOT,
    BREEDING_ATTEMPT,
    BREEDING_PARENT,
    WORLD,
    EVIDENCE_DIMENSION,
    FEATURE_DOMAIN,
    GLOBAL
}
