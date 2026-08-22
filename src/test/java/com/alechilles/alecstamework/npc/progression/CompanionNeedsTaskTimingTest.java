package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstelemetry.api.TelemetryBreadcrumbContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionNeedsTaskTimingTest {

    // Regression: ordinary needs batches must not create profiler-correlation work.
    @Test
    void doesNotEmitBelowServerSlowTaskBoundary() {
        List<TelemetryBreadcrumbContext> emitted = new ArrayList<>();

        CompanionNeedsTaskTiming.record(
                1_000_000L,
                50_999_999L,
                emitted::add
        );

        assertTrue(emitted.isEmpty());
    }

    // Regression: a batch at Hytale's 50 ms warning boundary must emit one bounded correlation category.
    @Test
    void emitsCorrelationAtServerSlowTaskBoundary() {
        List<TelemetryBreadcrumbContext> emitted = new ArrayList<>();

        CompanionNeedsTaskTiming.record(
                1_000_000L,
                51_000_000L,
                emitted::add
        );

        assertEquals(1, emitted.size());
        assertEquals("companion.needs.slow-task", emitted.getFirst().category());
        assertEquals("Companion needs batch exceeded 50 ms.", emitted.getFirst().detail());
    }
}
