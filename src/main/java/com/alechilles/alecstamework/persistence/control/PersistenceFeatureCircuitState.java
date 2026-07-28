package com.alechilles.alecstamework.persistence.control;

/** Durable admission state for one descriptor-owned feature circuit. */
public enum PersistenceFeatureCircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
