package com.alechilles.alecstamework.companion.bonded;

/** Signals that an authoritative bounded lease read has no conclusive database evidence yet. */
public final class BondedCompanionLeaseEvidenceUnavailableException extends IllegalStateException {
    public BondedCompanionLeaseEvidenceUnavailableException(String operation, Exception cause) {
        super(operation + " could not read bonded companion lease evidence", cause);
    }
}
