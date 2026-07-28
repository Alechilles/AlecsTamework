package com.alechilles.alecstamework.persistence.incidents;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable replacement persistence incident evidence. */
public record IncidentRecord(@Nonnull IncidentId incidentId,
                             @Nonnull String failureKind,
                             @Nonnull String failureCode,
                             @Nonnull IncidentState state,
                             @Nonnull String summary,
                             @Nonnull String evidenceJson,
                             long createdAtMs,
                             @Nullable Long resolvedAtMs) {
    public IncidentRecord {
        if (incidentId == null || state == null) {
            throw new IllegalArgumentException("Incident identity and state are required");
        }
        failureKind = requireText(failureKind, "Incident failure kind");
        failureCode = requireText(failureCode, "Incident failure code");
        summary = requireText(summary, "Incident summary");
        evidenceJson = requireText(evidenceJson, "Incident evidence JSON");
        if ((state == IncidentState.RESOLVED) != (resolvedAtMs != null)) {
            throw new IllegalArgumentException("Only resolved incidents carry resolution time");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
