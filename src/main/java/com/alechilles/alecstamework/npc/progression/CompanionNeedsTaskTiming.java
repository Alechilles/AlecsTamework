package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstelemetry.api.TelemetryBreadcrumbContext;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.function.Consumer;

/** Emits one low-cardinality profiler correlation when a needs batch reaches 50 ms. */
public final class CompanionNeedsTaskTiming {
    private static final long SLOW_TASK_NANOS = 50_000_000L;
    private static final String CATEGORY = "companion.needs.slow-task";
    private static final String DETAIL = "Companion needs batch exceeded 50 ms.";

    private CompanionNeedsTaskTiming() {
    }

    public static void record(long startedAtNanos,
                              long completedAtNanos,
                              @Nonnull Consumer<TelemetryBreadcrumbContext> sink) {
        Objects.requireNonNull(sink, "sink");
        if (completedAtNanos < startedAtNanos
                || completedAtNanos - startedAtNanos < SLOW_TASK_NANOS) {
            return;
        }
        sink.accept(TelemetryBreadcrumbContext.of(CATEGORY, DETAIL));
    }
}
