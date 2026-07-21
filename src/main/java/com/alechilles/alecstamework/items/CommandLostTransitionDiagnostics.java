package com.alechilles.alecstamework.items;

import com.alechilles.alecstelemetry.api.TelemetryBreadcrumbContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.persistence.sqlite.RecoveredProjectionSnapshotLoadResult;
import java.util.Locale;
import javax.annotation.Nonnull;

/** Emits privacy-bounded breadcrumbs for restart recovery of lost-transition state. */
final class CommandLostTransitionDiagnostics {
    private CommandLostTransitionDiagnostics() {
    }

    static void recordDurableFallback(
            @Nonnull RecoveredProjectionSnapshotLoadResult result) {
        String reason = TameworkTelemetryContext.normalizeReason(result.reason());
        String status = result.status().name().toLowerCase(Locale.ROOT);
        TameworkTelemetryEvents.recordBreadcrumbIfAvailable(
                TelemetryBreadcrumbContext.builder("persistence", reason)
                        .phase("recovery")
                        .operation("lost_transition_durable_snapshot")
                        .scopeType("profile")
                        .failureClass(status)
                        .disposition(result.isFound() ? "accepted" : "rejected")
                        .attribute("reason", reason)
                        .attribute("status", status)
                        .attribute("profileId", result.profileId() == null ? "missing" : "present")
                        .attribute("sourceNpcUuid", result.sourceNpcUuid() == null ? "missing" : "present")
                        .attribute("fullSnapshot", result.snapshot() == null ? "missing" : "present")
                        .build()
        );
    }
}
