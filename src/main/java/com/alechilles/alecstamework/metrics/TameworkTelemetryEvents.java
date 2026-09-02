package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.beacon.api.TelemetryEventContext;
import com.alechilles.beacon.api.TelemetryBreadcrumbContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Small orchestration wrapper for live Tamework telemetry event emission.
 */
public final class TameworkTelemetryEvents {

    @Nonnull
    public static TelemetryEventContext.Builder context() {
        return TelemetryEventContext.builder().runtimeSide("server");
    }

    @Nonnull
    public static TelemetryEventContext.Builder featureContext(@Nonnull String subsystem,
                                                              @Nonnull String featureKey,
                                                              @Nullable String entryPoint) {
        return context()
                .subsystem(subsystem)
                .featureKey(featureKey)
                .entryPoint(entryPoint);
    }

    @Nonnull
    public static TelemetryEventContext.Builder commandContext(@Nonnull String commandName,
                                                              @Nonnull String subsystem,
                                                              @Nonnull String featureKey) {
        return featureContext(subsystem, featureKey, commandName)
                .commandName(commandName);
    }

    public void recordError(@Nonnull String eventName,
                            @Nullable Throwable throwable,
                            @Nullable String detail) {
        recordError(eventName, throwable, TelemetryEventContext.builder().detail(detail).build());
    }

    public void recordError(@Nonnull String eventName,
                            @Nullable Throwable throwable,
                            @Nullable TelemetryEventContext context) {
        CrashTelemetryService service = resolveService();
        if (service != null) {
            service.recordError(eventName, throwable, context);
        }
    }

    public void recordLifecycle(@Nonnull String eventName,
                                int durationMs,
                                boolean success,
                                @Nullable String detail) {
        recordLifecycle(eventName, durationMs, success, TelemetryEventContext.builder().detail(detail).build());
    }

    public void recordLifecycle(@Nonnull String eventName,
                                int durationMs,
                                boolean success,
                                @Nullable TelemetryEventContext context) {
        CrashTelemetryService service = resolveService();
        if (service != null) {
            service.recordLifecycle(eventName, durationMs, success, context);
        }
    }

    public void recordPerformance(@Nonnull String eventName,
                                  int durationMs,
                                  @Nullable Double metricValue,
                                  @Nullable String detail) {
        recordPerformance(eventName, durationMs, metricValue, TelemetryEventContext.builder().detail(detail).build());
    }

    public void recordPerformance(@Nonnull String eventName,
                                  int durationMs,
                                  @Nullable Double metricValue,
                                  @Nullable TelemetryEventContext context) {
        CrashTelemetryService service = resolveService();
        if (service != null) {
            service.recordPerformance(eventName, durationMs, metricValue, context);
        }
    }

    public void recordUsage(@Nonnull String eventName,
                            @Nullable String detail) {
        recordUsage(eventName, TelemetryEventContext.builder().detail(detail).build());
    }

    public void recordUsage(@Nonnull String eventName,
                            @Nullable TelemetryEventContext context) {
        CrashTelemetryService service = resolveService();
        if (service != null) {
            service.recordUsage(eventName, context);
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
        recordErrorIfAvailable(eventName, throwable, TelemetryEventContext.builder().detail(detail).build());
    }

    public static void recordErrorIfAvailable(@Nonnull String eventName,
                                              @Nullable Throwable throwable,
                                              @Nullable TelemetryEventContext context) {
        try {
            TameworkTelemetryEvents telemetry = resolveAvailable();
            if (telemetry != null) {
                telemetry.recordError(eventName, throwable, context);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void recordUsageIfAvailable(@Nonnull String eventName,
                                              @Nullable String detail) {
        recordUsageIfAvailable(eventName, TelemetryEventContext.builder().detail(detail).build());
    }

    public static void recordBreadcrumbIfAvailable(@Nonnull TelemetryBreadcrumbContext context) {
        try {
            Tamework plugin = Tamework.getInstance();
            CrashTelemetryService service = plugin == null ? null : plugin.getCrashTelemetryService();
            if (service != null) service.recordBreadcrumb(context);
        } catch (Throwable ignored) {
        }
    }

    public static void recordUsageIfAvailable(@Nonnull String eventName,
                                              @Nullable TelemetryEventContext context) {
        try {
            TameworkTelemetryEvents telemetry = resolveAvailable();
            if (telemetry != null) {
                telemetry.recordUsage(eventName, context);
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
