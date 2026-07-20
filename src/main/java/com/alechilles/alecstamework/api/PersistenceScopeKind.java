package com.alechilles.alecstamework.api;

/** Stable local scope kinds used by read-only persistence availability queries. */
public enum PersistenceScopeKind {
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
