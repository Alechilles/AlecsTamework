package com.alechilles.alecstamework.persistence.incidents;

/** Required handling after failure classification. */
public enum PersistenceDisposition {
    RETRY_SAME_OPERATION,
    CANCEL_OPERATION,
    DOMAIN_REJECTION,
    SCOPED_QUARANTINE,
    AUTHORITY_NOT_READY,
    GLOBAL_READ_ONLY,
    OPERATOR_RESTORE_REQUIRED
}
