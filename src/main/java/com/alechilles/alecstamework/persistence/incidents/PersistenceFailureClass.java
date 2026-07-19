package com.alechilles.alecstamework.persistence.incidents;

/** Evidence-based failure taxonomy; names are persisted and emitted to telemetry. */
public enum PersistenceFailureClass {
    TRANSIENT_CONTENTION,
    DEFINITIVE_PRE_APPLY_FAILURE,
    ROLLED_BACK_DOMAIN_CONFLICT,
    POST_COMMIT_PUBLICATION_FAILURE,
    SCOPED_APPLY_AMBIGUITY,
    SCOPED_IDENTITY_CONTRADICTION,
    COVERAGE_UNAVAILABLE,
    UNKNOWN_TRANSACTION_OUTCOME,
    STORAGE_UNAVAILABLE,
    STORAGE_INTEGRITY_LOST,
    PROGRAMMING_CONTRACT_FAILURE
}
