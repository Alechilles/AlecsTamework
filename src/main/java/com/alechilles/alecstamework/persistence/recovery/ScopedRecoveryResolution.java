package com.alechilles.alecstamework.persistence.recovery;

/** Evidence result returned by a domain-specific scoped persistence verifier. */
public enum ScopedRecoveryResolution {
    RESOLVED_OLD_STATE,
    RESOLVED_NEW_STATE,
    STILL_AMBIGUOUS,
    AUTHORITY_UNAVAILABLE,
    CONTRADICTORY_EVIDENCE,
    FAILED;

    public boolean isResolved() {
        return this == RESOLVED_OLD_STATE || this == RESOLVED_NEW_STATE;
    }
}
