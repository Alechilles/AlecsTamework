package com.alechilles.alecstamework.persistence.incidents;

/** Durable incident lifecycle. */
public enum PersistenceIncidentStatus {
    OPEN,
    RECOVERING,
    RESOLVED,
    SUPERSEDED
}
