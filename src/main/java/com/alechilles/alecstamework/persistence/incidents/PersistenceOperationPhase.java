package com.alechilles.alecstamework.persistence.incidents;

/** Durable and visible checkpoints shared by incident, journal, and telemetry records. */
public enum PersistenceOperationPhase {
    REQUESTED,
    PREPARED,
    APPLYING,
    LIVE_MUTATION,
    COMMIT,
    SOURCE_FINALIZATION,
    PUBLICATION,
    TERMINAL,
    RECOVERY
}
