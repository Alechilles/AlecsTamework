package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstelemetry.api.TelemetryBreadcrumbContext;
import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentEvent;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentEventKind;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentSink;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;

/** Sole adapter from persistence diagnostics into Alec's Telemetry's allowlisted API. */
public final class TameworkPersistenceTelemetry implements PersistenceIncidentSink {
    public static final int PERSISTENCE_SUBSYSTEM_VERSION = 7;
    static final Set<String> REMOTE_DETAIL_KEYS = Set.of(
            "incidentId", "traceId", "operationId", "bootId", "domain", "phase", "reason",
            "failureClass", "disposition", "scopeType", "scopeCountBucket", "retryCountBucket",
            "schemaVersion", "result", "scopeHash");

    @Override
    public void record(@Nonnull PersistenceIncidentEvent event) {
        try {
            TameworkTelemetryEvents.recordBreadcrumbIfAvailable(breadcrumb(event));
            String eventName = eventName(event.eventKind());
            if (isError(event.eventKind())) {
                TameworkTelemetryEvents.recordErrorIfAvailable(eventName, null, details(event));
            } else {
                TameworkTelemetryEvents telemetry = new TameworkTelemetryEvents();
                telemetry.recordLifecycle(eventName, 0, isSuccess(event.eventKind()), details(event));
            }
        } catch (Throwable ignored) {
            // Telemetry must remain observational.
        }
    }

    TelemetryBreadcrumbContext breadcrumb(PersistenceIncidentEvent event) {
        PersistenceIncidentEvent.SafeScope first =
                event.scopes().isEmpty() ? null : event.scopes().getFirst();
        return TelemetryBreadcrumbContext.builder("persistence", event.reasonCode())
                .correlationId(event.traceId() == null ? event.operationId() : event.traceId())
                .incidentId(event.incidentId())
                .phase(token(event.phase().name()))
                .operation(token(event.domain().name()))
                .scopeType(first == null ? null : token(first.type()))
                .failureClass(token(event.failureClass().name()))
                .disposition(token(event.disposition().name()))
                .attribute("reason", event.reasonCode())
                .attribute("eventKind", token(event.eventKind().name()))
                .attribute("scopeCountBucket", countBucket(event.scopes().size()))
                .attribute("schemaVersion", PERSISTENCE_SUBSYSTEM_VERSION)
                .build();
    }

    TelemetryEventContext details(PersistenceIncidentEvent event) {
        PersistenceIncidentEvent.SafeScope first =
                event.scopes().isEmpty() ? null : event.scopes().getFirst();
        TelemetryEventContext.Builder builder = TameworkTelemetryEvents
                .featureContext("persistence", "resilience_v7", "incident_reporter")
                .operation(token(event.eventKind().name()))
                .detail(event.reasonCode())
                .detail("incidentId", event.incidentId())
                .detail("traceId", value(event.traceId()))
                .detail("operationId", value(event.operationId()))
                .detail("bootId", event.bootId())
                .detail("domain", token(event.domain().name()))
                .detail("phase", token(event.phase().name()))
                .detail("reason", event.reasonCode())
                .detail("failureClass", token(event.failureClass().name()))
                .detail("disposition", token(event.disposition().name()))
                .detail("scopeType", first == null ? "none" : token(first.type()))
                .detail("scopeCountBucket", countBucket(event.scopes().size()))
                .detail("retryCountBucket", countBucket(event.recoveryAttempt()))
                .detail("schemaVersion", PERSISTENCE_SUBSYSTEM_VERSION)
                .detail("result", value(event.result()));
        if (first != null) builder.detail("scopeHash", first.scopeHash());
        return builder.build();
    }

    String eventName(PersistenceIncidentEventKind kind) {
        return switch (kind) {
            case INCIDENT_OPENED -> "persistence_incident_opened";
            case INCIDENT_REPEATED -> "persistence_incident_repeated";
            case QUARANTINE_DURABLE -> "persistence_quarantine_opened";
            case QUARANTINE_DURABILITY_FAILED, RECOVERY_FAILED -> "persistence_recovery_failed";
            case GLOBAL_READ_ONLY_ENTERED -> "persistence_global_read_only_entered";
            case GLOBAL_READ_ONLY_RECOVERED -> "persistence_global_read_only_recovered";
            case QUARANTINE_CLEARED -> "persistence_quarantine_cleared";
            case RECOVERY_COMPLETED -> "persistence_recovery_completed";
        };
    }

    private boolean isError(PersistenceIncidentEventKind kind) {
        return switch (kind) {
            case INCIDENT_OPENED, QUARANTINE_DURABILITY_FAILED,
                    GLOBAL_READ_ONLY_ENTERED, RECOVERY_FAILED -> true;
            default -> false;
        };
    }

    private boolean isSuccess(PersistenceIncidentEventKind kind) {
        return kind == PersistenceIncidentEventKind.QUARANTINE_DURABLE
                || kind == PersistenceIncidentEventKind.QUARANTINE_CLEARED
                || kind == PersistenceIncidentEventKind.GLOBAL_READ_ONLY_RECOVERED
                || kind == PersistenceIncidentEventKind.RECOVERY_COMPLETED;
    }

    private String token(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private String countBucket(long count) {
        if (count <= 0L) return "0";
        if (count == 1L) return "1";
        if (count <= 3L) return "2-3";
        if (count <= 10L) return "4-10";
        return "11+";
    }
}
