package com.alechilles.alecstamework.persistence.runtime;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAccumulator;

/**
 * Allocation-free logarithmic latency histogram for passive runtime metrics.
 *
 * <p>Percentiles are bounded upper estimates in nanoseconds. The fixed bucket
 * count prevents persistence diagnostics from becoming another stateful
 * subsystem.</p>
 */
final class PersistenceLatencyHistogram {
    private static final int BUCKETS = 63;

    private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
    private final AtomicLong total = new AtomicLong();
    private final LongAccumulator maximum =
            new LongAccumulator(Long::max, 0);

    void observe(long nanos) {
        long bounded = Math.max(0, nanos);
        counts.incrementAndGet(bucket(bounded));
        total.incrementAndGet();
        maximum.accumulate(bounded);
    }

    PublicPersistencePerformanceSnapshot.Latency snapshot() {
        long observations = total.get();
        return new PublicPersistencePerformanceSnapshot.Latency(
                observations,
                percentile(observations, 50),
                percentile(observations, 95),
                percentile(observations, 99),
                maximum.get()
        );
    }

    private int bucket(long nanos) {
        if (nanos <= 1) {
            return 0;
        }
        return Math.min(
                BUCKETS - 1,
                64 - Long.numberOfLeadingZeros(nanos - 1)
        );
    }

    private long percentile(long observations, int percentile) {
        if (observations == 0) {
            return 0;
        }
        long target = Math.max(1, percentileRank(
                observations, percentile
        ));
        long seen = 0;
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            seen += counts.get(bucket);
            if (seen >= target) {
                return upperBound(bucket);
            }
        }
        return Long.MAX_VALUE;
    }

    private long upperBound(int bucket) {
        return bucket >= 62 ? Long.MAX_VALUE : 1L << bucket;
    }

    private long percentileRank(long observations, int percentile) {
        long whole = observations / 100;
        long remainder = observations % 100;
        return whole * percentile
                + (remainder * percentile + 99) / 100;
    }
}
