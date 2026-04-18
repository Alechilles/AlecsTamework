package com.alechilles.alecstamework.metrics;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Small orchestration wrapper for Alec's Telemetry event emission from live Tamework flows.
 */
public final class TameworkTelemetryEvents {

    private final AlecsTelemetryBridge bridge = new AlecsTelemetryBridge();

    public void recordError(@Nonnull String eventName,
                            @Nullable Throwable throwable,
                            @Nullable String detail) {
        bridge.recordError(eventName, throwable, detail);
    }

    public void recordLifecycle(@Nonnull String eventName,
                                int durationMs,
                                boolean success,
                                @Nullable String detail) {
        bridge.recordLifecycle(eventName, durationMs, success, detail);
    }

    public void recordPerformance(@Nonnull String eventName,
                                  int durationMs,
                                  @Nullable Double metricValue,
                                  @Nullable String detail) {
        bridge.recordPerformance(eventName, durationMs, metricValue, detail);
    }

    public void recordUsage(@Nonnull String eventName,
                            @Nullable String detail) {
        bridge.recordUsage(eventName, detail);
    }

    public int elapsedMillis(long startedAtNanos) {
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        if (elapsedNanos <= 0L) {
            return 0;
        }
        long elapsedMs = elapsedNanos / 1_000_000L;
        return elapsedMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsedMs;
    }
}
