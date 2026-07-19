package com.alechilles.alecstamework.persistence.incidents;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Complete durable incident row; diagnostic text is bounded by its producer. */
public record PersistenceIncident(@Nonnull String incidentId,
                                  @Nonnull String fingerprint,
                                  @Nonnull PersistenceIncidentStatus status,
                                  @Nonnull PersistenceIncidentSeverity severity,
                                  @Nonnull PersistenceFailureClass failureClass,
                                  @Nonnull PersistenceDisposition disposition,
                                  @Nonnull PersistenceDomain domain,
                                  @Nonnull PersistenceOperationPhase phase,
                                  @Nonnull String reasonCode,
                                  @Nullable String operationId,
                                  @Nonnull String bootId,
                                  long openedAtMs,
                                  long lastSeenAtMs,
                                  long resolvedAtMs,
                                  long occurrenceCount,
                                  long recoveryAttempts,
                                  @Nullable String lastErrorType,
                                  @Nullable String lastErrorMessage,
                                  @Nonnull String evidenceJson,
                                  @Nullable String resolutionCode,
                                  @Nullable String telemetryCorrelationId) {
    public PersistenceIncident {
        incidentId = requireText(incidentId, "incidentId");
        fingerprint = requireText(fingerprint, "fingerprint");
        reasonCode = requireText(reasonCode, "reasonCode");
        bootId = requireText(bootId, "bootId");
        evidenceJson = requireText(evidenceJson, "evidenceJson");
        operationId = normalize(operationId, 160);
        lastErrorType = normalize(lastErrorType, 240);
        lastErrorMessage = normalize(lastErrorMessage, 1_000);
        resolutionCode = normalize(resolutionCode, 160);
        telemetryCorrelationId = normalize(telemetryCorrelationId, 160);
        if (status == null || severity == null || failureClass == null || disposition == null
                || domain == null || phase == null) throw new IllegalArgumentException("incident classification");
        if (occurrenceCount < 1L || recoveryAttempts < 0L) throw new IllegalArgumentException("incident counters");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name);
        return value.trim();
    }

    private static String normalize(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), max));
    }
}
