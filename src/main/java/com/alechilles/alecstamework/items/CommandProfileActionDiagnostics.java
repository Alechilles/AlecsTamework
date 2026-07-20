package com.alechilles.alecstamework.items;

import com.alechilles.alecstelemetry.api.TelemetryBreadcrumbContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Emits privacy-bounded diagnostics and resolves precise feedback for rejected profile actions. */
final class CommandProfileActionDiagnostics {
    private CommandProfileActionDiagnostics() {
    }

    static void recordRejected(
            @Nonnull String operation,
            @Nullable CommandNpcProfileActionResolver.ActionTarget target) {
        TameworkTelemetryEvents.recordBreadcrumbIfAvailable(breadcrumb(operation, target));
    }

    @Nonnull
    static TelemetryBreadcrumbContext breadcrumb(
            @Nonnull String operation,
            @Nullable CommandNpcProfileActionResolver.ActionTarget target) {
        String reason = target != null && target.reason() != null
                ? TameworkTelemetryContext.normalizeReason(target.reason())
                : "target_unavailable";
        String status = target != null
                ? target.status().name().toLowerCase(Locale.ROOT)
                : "unresolved";
        return TelemetryBreadcrumbContext.builder("linked_companion", reason)
                .phase("preflight")
                .operation(TameworkTelemetryContext.normalizeToken(operation))
                .scopeType("profile")
                .failureClass(status)
                .disposition("rejected")
                .attribute("reason", reason)
                .attribute("resolutionStatus", status)
                .attribute("profileId", target != null && target.profileId() != null ? "present" : "missing")
                .attribute("cachedNpcUuid", target != null && target.cachedNpcUuid() != null ? "present" : "missing")
                .attribute("targetNpcUuid", target != null && target.targetNpcUuid() != null ? "present" : "missing")
                .attribute("aliasCountBucket", TameworkTelemetryContext.countBucket(
                        target != null ? target.aliases().size() : 0))
                .attribute("liveCountBucket", TameworkTelemetryContext.countBucket(
                        target != null ? target.liveUuids().size() : 0))
                .build();
    }

    @Nullable
    static String feedbackKey(@Nullable CommandNpcProfileActionResolver.ActionTarget target) {
        String reason = target != null ? target.reason() : null;
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case "profile_is_captured" -> "tamework.ui.notifications.command.move.captured";
            case "profile_is_dead" -> "tamework.ui.notifications.command.move.dead";
            case "profile_is_cooped" -> "tamework.ui.notifications.command.move.inCoop";
            case "profile_is_lost", "profile_recovery_active" ->
                    "tamework.ui.notifications.command.move.lost";
            default -> null;
        };
    }
}
