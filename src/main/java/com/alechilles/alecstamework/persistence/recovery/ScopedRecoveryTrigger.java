package com.alechilles.alecstamework.persistence.recovery;

/** Bounded causes that may ask a verifier to re-read authoritative evidence. */
public enum ScopedRecoveryTrigger {
    STARTUP,
    BOUNDED_RETRY,
    WORLD_READY,
    CHUNK_READY,
    COOP_READY,
    SOURCE_READY,
    OPERATOR_REQUEST
}
