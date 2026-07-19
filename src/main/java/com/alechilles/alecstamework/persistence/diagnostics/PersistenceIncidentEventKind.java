package com.alechilles.alecstamework.persistence.diagnostics;

/** Local and remote-safe lifecycle points emitted by the incident reporter. */
public enum PersistenceIncidentEventKind {
    INCIDENT_OPENED,
    INCIDENT_REPEATED,
    QUARANTINE_DURABLE,
    QUARANTINE_DURABILITY_FAILED,
    GLOBAL_READ_ONLY_ENTERED,
    GLOBAL_READ_ONLY_RECOVERED,
    QUARANTINE_CLEARED,
    RECOVERY_COMPLETED,
    RECOVERY_FAILED
}
