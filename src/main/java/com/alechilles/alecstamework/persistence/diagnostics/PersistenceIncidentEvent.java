package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDisposition;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClass;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncident;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable, sanitized evidence record shared by local journal and optional telemetry. */
public record PersistenceIncidentEvent(int formatVersion,
                                       long timestampMs,
                                       @Nonnull PersistenceIncidentEventKind eventKind,
                                       @Nonnull String bootId,
                                       @Nonnull String incidentId,
                                       @Nullable String traceId,
                                       @Nullable String operationId,
                                       @Nonnull PersistenceDomain domain,
                                       @Nonnull PersistenceOperationPhase phase,
                                       @Nonnull String reasonCode,
                                       @Nonnull PersistenceFailureClass failureClass,
                                       @Nonnull PersistenceDisposition disposition,
                                       @Nonnull List<SafeScope> scopes,
                                       long repeatCount,
                                       long recoveryAttempt,
                                       @Nullable String result) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public PersistenceIncidentEvent {
        if (eventKind == null || domain == null || phase == null
                || failureClass == null || disposition == null) {
            throw new IllegalArgumentException("incident event classification");
        }
        bootId = requireText(bootId, "bootId");
        incidentId = requireText(incidentId, "incidentId");
        reasonCode = requireText(reasonCode, "reasonCode");
        traceId = normalize(traceId, 120);
        operationId = normalize(operationId, 160);
        result = normalize(result, 160);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    @Nonnull
    public static PersistenceIncidentEvent from(@Nonnull PersistenceIncident incident,
                                                @Nonnull PersistenceIncidentEventKind kind,
                                                @Nonnull List<PersistenceScope> scopes,
                                                @Nullable String result) {
        return new PersistenceIncidentEvent(
                CURRENT_FORMAT_VERSION, System.currentTimeMillis(), kind, incident.bootId(),
                incident.incidentId(), null, incident.operationId(), incident.domain(), incident.phase(),
                incident.reasonCode(), incident.failureClass(), incident.disposition(),
                scopes.stream().map(SafeScope::from).toList(), incident.occurrenceCount(),
                incident.recoveryAttempts(), result);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name);
        return value.trim();
    }

    private static String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), maxLength));
    }

    /** Contains only type and pre-derived remote-safe fingerprint, never the local raw key. */
    public record SafeScope(@Nonnull String type,
                            @Nonnull String scopeHash,
                            @Nullable String authorityDimension) {
        private static SafeScope from(PersistenceScope scope) {
            return new SafeScope(scope.type().name(), scope.scopeHash(), scope.authorityDimension());
        }
    }
}
