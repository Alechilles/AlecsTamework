package com.alechilles.alecstamework.persistence.health;

/** Global SQLite authority lifecycle; domain conflicts must never enter these states directly. */
public enum PersistenceStorageState {
    HEALTHY,
    RETRYING,
    READ_ONLY,
    RECOVERING,
    CLOSED
}
