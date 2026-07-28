package com.alechilles.alecstamework.persistence.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixed-memory latency percentile contracts. */
class PersistenceLatencyHistogramTest {
    @Test
    void emptyHistogramIsExplicitlyZero() {
        var snapshot = new PersistenceLatencyHistogram().snapshot();

        assertEquals(0, snapshot.count());
        assertEquals(0, snapshot.p50Nanos());
        assertEquals(0, snapshot.p95Nanos());
        assertEquals(0, snapshot.p99Nanos());
        assertEquals(0, snapshot.maxNanos());
    }

    @Test
    void percentilesAreBoundedUpperEstimatesWithoutSamples() {
        PersistenceLatencyHistogram histogram =
                new PersistenceLatencyHistogram();
        for (int value = 1; value <= 100; value++) {
            histogram.observe(value);
        }

        var snapshot = histogram.snapshot();

        assertEquals(100, snapshot.count());
        assertTrue(snapshot.p50Nanos() >= 50);
        assertTrue(snapshot.p95Nanos() >= 95);
        assertTrue(snapshot.p99Nanos() >= 99);
        assertEquals(100, snapshot.maxNanos());
    }
}
