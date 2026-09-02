package com.alechilles.alecstamework.metrics;

import com.alechilles.beacon.api.TelemetryEventContext;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared low-cardinality context builders for Tamework telemetry events.
 */
public final class TameworkTelemetryContext {
    private TameworkTelemetryContext() {
    }

    @Nonnull
    public static TelemetryEventContext.Builder linkedCompanion(@Nonnull String featureKey,
                                                               @Nonnull String operation,
                                                               @Nonnull String detail) {
        return TameworkTelemetryEvents.featureContext("linked_companion", featureKey, "runtime")
                .operation(operation)
                .detail(detail);
    }

    @Nonnull
    public static TelemetryEventContext.Builder persistence(@Nonnull String service,
                                                           @Nonnull String operation,
                                                           @Nonnull String reason,
                                                           @Nonnull String detail) {
        return TameworkTelemetryEvents.featureContext("persistence", "sqlite", "runtime")
                .operation(operation)
                .target(normalizeToken(service))
                .detail(detail)
                .detail("service", normalizeToken(service))
                .detail("operation", normalizeToken(operation))
                .detail("reason", normalizeReason(reason));
    }

    @Nonnull
    public static TelemetryEventContext.Builder uiPage(@Nonnull String page,
                                                      @Nonnull String source,
                                                      @Nonnull String operation,
                                                      @Nonnull String detail) {
        return TameworkTelemetryEvents.featureContext("ui", "ui_page", source)
                .operation(operation)
                .target(page)
                .detail(detail)
                .detail("page", bounded(page, 120))
                .detail("source", normalizeToken(source))
                .detail("operation", normalizeToken(operation));
    }

    @Nonnull
    public static String normalizeReason(@Nullable String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return bounded(reason.trim()
                .replace(' ', '_')
                .replace(',', '+')
                .toLowerCase(Locale.ROOT), 120);
    }

    @Nonnull
    public static String normalizeToken(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return bounded(value.trim()
                .replace(' ', '_')
                .replace(',', '+')
                .toLowerCase(Locale.ROOT), 80);
    }

    @Nonnull
    public static String idState(@Nullable Object value) {
        return value == null ? "missing" : "present";
    }

    @Nonnull
    public static String countBucket(int value) {
        if (value <= 0) {
            return "0";
        }
        if (value == 1) {
            return "1";
        }
        if (value <= 3) {
            return "2-3";
        }
        if (value <= 10) {
            return "4-10";
        }
        return "11+";
    }

    @Nonnull
    public static String bounded(@Nullable String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }
}
