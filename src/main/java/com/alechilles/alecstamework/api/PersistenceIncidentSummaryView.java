package com.alechilles.alecstamework.api;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Sanitized incident summary containing only random correlation IDs and hashed scopes. */
public record PersistenceIncidentSummaryView(
        @Nonnull String incidentId,
        @Nonnull String status,
        @Nonnull String domain,
        @Nonnull String phase,
        @Nonnull String reasonCode,
        @Nonnull String failureClass,
        @Nonnull String disposition,
        long openedAtMs,
        long lastSeenAtMs,
        long occurrenceCount,
        long recoveryAttempts,
        @Nullable String resolutionCode,
        @Nullable String telemetryCorrelationId,
        @Nonnull List<ScopeView> scopes) {
    public PersistenceIncidentSummaryView {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    /** Scope keys are intentionally omitted; the hash is installation-local. */
    public record ScopeView(@Nonnull String kind,
                            @Nonnull String scopeHash,
                            @Nullable String authorityDimension) {
    }
}
