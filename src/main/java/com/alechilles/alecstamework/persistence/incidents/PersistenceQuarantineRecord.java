package com.alechilles.alecstamework.persistence.incidents;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable denial fence loaded into the process-local quarantine index. */
public record PersistenceQuarantineRecord(@Nonnull String quarantineId,
                                          @Nonnull String incidentId,
                                          @Nonnull PersistenceScope scope,
                                          @Nonnull PersistenceDomain domain,
                                          @Nonnull String reasonCode,
                                          @Nonnull PersistenceQuarantineState state,
                                          @Nonnull String evidenceHash,
                                          long generation,
                                          long createdAtMs,
                                          long updatedAtMs,
                                          long clearedAtMs,
                                          @Nullable String clearVerifier) {
    public PersistenceQuarantineRecord {
        quarantineId = requireText(quarantineId, "quarantineId");
        incidentId = requireText(incidentId, "incidentId");
        if (scope == null || domain == null || state == null) throw new IllegalArgumentException("scope/domain/state");
        reasonCode = requireText(reasonCode, "reasonCode");
        evidenceHash = requireText(evidenceHash, "evidenceHash");
        if (generation < 0L) throw new IllegalArgumentException("generation");
        clearVerifier = clearVerifier == null || clearVerifier.isBlank() ? null : clearVerifier.trim();
    }

    public boolean isActive() {
        return state == PersistenceQuarantineState.ACTIVE || state == PersistenceQuarantineState.VERIFYING;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name);
        return value.trim();
    }
}
