package com.alechilles.alecstamework.persistence.incidents;

/** What is durably known about the transaction boundary at failure time. */
public enum PersistenceTransactionOutcome {
    NOT_STARTED,
    ROLLED_BACK,
    COMMITTED,
    UNKNOWN
}
