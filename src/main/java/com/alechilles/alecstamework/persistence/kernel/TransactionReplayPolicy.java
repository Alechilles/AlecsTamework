package com.alechilles.alecstamework.persistence.kernel;

/** Declares whether a known-rolled-back database transaction may be replayed after SQLite busy. */
public enum TransactionReplayPolicy {
    NEVER,
    SAFE_DATABASE_ONLY
}
