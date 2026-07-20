package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityDecision;
import com.alechilles.alecstelemetry.api.TelemetryBreadcrumbContext;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Emits throttled logs and privacy-bounded breadcrumbs for rejected relocation preflights. */
final class CommandRelocationPreflightDiagnostics {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long LOG_THROTTLE_MS = 30_000L;
    private static final ConcurrentHashMap<String, Long> LAST_LOGGED_AT = new ConcurrentHashMap<>();

    private CommandRelocationPreflightDiagnostics() {
    }

    static void recordRejected(
            @Nonnull String operation,
            @Nonnull PersistenceMutationAvailabilityDecision decision,
            @Nonnull UUID npcUuid,
            @Nullable String profileId,
            @Nullable String sourceWorld,
            @Nonnull String destinationWorld,
            boolean liveProjectionExists
    ) {
        TameworkTelemetryEvents.recordBreadcrumbIfAvailable(
                breadcrumb(
                        operation,
                        decision,
                        profileId,
                        sourceWorld,
                        destinationWorld,
                        liveProjectionExists
                )
        );
        logThrottled(operation, decision, npcUuid, profileId, sourceWorld, destinationWorld);
    }

    @Nonnull
    static TelemetryBreadcrumbContext breadcrumb(
            @Nonnull String operation,
            @Nonnull PersistenceMutationAvailabilityDecision decision,
            @Nullable String profileId,
            @Nullable String sourceWorld,
            @Nonnull String destinationWorld,
            boolean liveProjectionExists
    ) {
        String reason = TameworkTelemetryContext.normalizeReason(decision.reasonCode());
        String status = decision.status().name().toLowerCase(java.util.Locale.ROOT);
        return TelemetryBreadcrumbContext.builder("persistence", reason)
                .phase("admission")
                .operation(TameworkTelemetryContext.normalizeToken(operation))
                .scopeType("profile")
                .failureClass(status)
                .disposition("rejected")
                .attribute("reason", reason)
                .attribute("status", status)
                .attribute("profileId", profileId == null ? "missing" : "present")
                .attribute("sameWorld", Boolean.toString(Objects.equals(
                        normalize(sourceWorld), normalize(destinationWorld))))
                .attribute("liveProjection", Boolean.toString(liveProjectionExists))
                .build();
    }

    private static void logThrottled(
            String operation,
            PersistenceMutationAvailabilityDecision decision,
            UUID npcUuid,
            @Nullable String profileId,
            @Nullable String sourceWorld,
            String destinationWorld
    ) {
        long now = System.currentTimeMillis();
        String key = operation + '|' + decision.status() + '|' + decision.reasonCode();
        Long prior = LAST_LOGGED_AT.put(key, now);
        if (prior != null && now - prior < LOG_THROTTLE_MS) {
            return;
        }
        LOGGER.at(Level.WARNING).log(
                "Companion relocation preflight rejected: operation=" + operation
                        + " status=" + decision.status()
                        + " reason=" + decision.reasonCode()
                        + " npc=" + npcUuid
                        + " profile=" + profileId
                        + " sourceWorld=" + sourceWorld
                        + " destinationWorld=" + destinationWorld
        );
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
