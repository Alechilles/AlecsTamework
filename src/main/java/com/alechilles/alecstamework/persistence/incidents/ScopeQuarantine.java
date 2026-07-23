package com.alechilles.alecstamework.persistence.incidents;

import com.alechilles.alecstamework.persistence.operation.OperationScope;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable mutation-denial fence attached to the narrowest proven operation scope. */
public record ScopeQuarantine(@Nonnull OperationScope scope,
                              @Nonnull IncidentId incidentId,
                              @Nonnull QuarantineState state,
                              @Nonnull String reasonCode,
                              long createdAtMs,
                              @Nullable Long releasedAtMs) {
    public ScopeQuarantine {
        if (scope == null || incidentId == null || state == null) {
            throw new IllegalArgumentException("Quarantine scope, incident, and state are required");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("Quarantine reason code is required");
        }
        reasonCode = reasonCode.trim();
        if ((state == QuarantineState.RELEASED) != (releasedAtMs != null)) {
            throw new IllegalArgumentException("Only released quarantines carry release time");
        }
    }
}
