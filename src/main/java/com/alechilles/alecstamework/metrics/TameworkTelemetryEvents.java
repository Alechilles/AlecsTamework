package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstamework.Tamework;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Small orchestration wrapper for live Tamework telemetry event emission.
 */
public final class TameworkTelemetryEvents {

    public void recordError(@Nonnull String eventName,
                            @Nullable Throwable throwable,
                            @Nullable String detail) {
        CrashTelemetryService service = resolveService();
        if (service != null) {
            service.recordError(eventName, throwable, detail);
        }
    }

    public void recordLifecycle(@Nonnull String eventName,
                                int durationMs,
                                boolean success,
                                @Nullable String detail) {
        CrashTelemetryService service = resolveService();
        if (service != null) {
            service.recordLifecycle(eventName, durationMs, success, detail);
        }
    }

    public void recordPerformance(@Nonnull String eventName,
                                  int durationMs,
                                  @Nullable Double metricValue,
                                  @Nullable String detail) {
        CrashTelemetryService service = resolveService();
        if (service != null) {
            service.recordPerformance(eventName, durationMs, metricValue, detail);
        }
    }

    public void recordUsage(@Nonnull String eventName,
                            @Nullable String detail) {
        CrashTelemetryService service = resolveService();
        if (service != null) {
            service.recordUsage(eventName, detail);
        }
    }

    public int elapsedMillis(long startedAtNanos) {
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        if (elapsedNanos <= 0L) {
            return 0;
        }
        long elapsedMs = elapsedNanos / 1_000_000L;
        return elapsedMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsedMs;
    }

    public static void recordErrorIfAvailable(@Nonnull String eventName,
                                              @Nullable Throwable throwable,
                                              @Nullable String detail) {
        try {
            TameworkTelemetryEvents telemetry = resolveAvailable();
            if (telemetry != null) {
                telemetry.recordError(eventName, throwable, detail);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void recordUsageIfAvailable(@Nonnull String eventName,
                                              @Nullable String detail) {
        try {
            TameworkTelemetryEvents telemetry = resolveAvailable();
            if (telemetry != null) {
                telemetry.recordUsage(eventName, detail);
            }
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private CrashTelemetryService resolveService() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getCrashTelemetryService();
    }

    @Nullable
    private static TameworkTelemetryEvents resolveAvailable() {
        Tamework plugin = Tamework.getInstance();
        return plugin != null ? plugin.getTelemetryEvents() : null;
    }
}
