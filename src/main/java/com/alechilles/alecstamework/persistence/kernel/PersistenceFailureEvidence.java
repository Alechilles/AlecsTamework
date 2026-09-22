package com.alechilles.alecstamework.persistence.kernel;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Immutable, already-redacted evidence captured at a failing storage boundary.
 * Attached as a suppressed exception so the original failure, SQL classification,
 * and recovery outcome remain unchanged. Contains no connection or live state.
 */
public final class PersistenceFailureEvidence extends Exception {
    public static final int MAX_BYTES = 96 * 1024;
    private final String json;

    /** Only adapter-owned, allowlisted and pseudonymized JSON may cross this boundary. */
    public PersistenceFailureEvidence(String json) {
        super("persistence_failure_evidence", null, false, false);
        this.json = Objects.requireNonNull(json, "json");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("failure_evidence_too_large");
        }
    }

    public String json() {
        return json;
    }
}
