package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import java.util.Map;
import javax.annotation.Nonnull;

/** Bounded passive measurements used by replacement release gates. */
public record PublicPersistencePerformanceSnapshot(
        @Nonnull Map<PersistenceStartupNode, Latency> startupNodes,
        @Nonnull QueuePerformance writer,
        @Nonnull QueuePerformance reads,
        @Nonnull Latency shutdownDrain,
        long walBytes,
        int lastCheckpointLogFrames,
        int lastCheckpointedFrames
) {
    public PublicPersistencePerformanceSnapshot {
        if (startupNodes == null
                || !startupNodes.keySet().equals(
                java.util.Set.of(PersistenceStartupNode.values())
        ) || startupNodes.values().stream().anyMatch(
                java.util.Objects::isNull
        ) || writer == null || reads == null || shutdownDrain == null
                || walBytes < 0 || lastCheckpointLogFrames < 0
                || lastCheckpointedFrames < 0
                || lastCheckpointedFrames > lastCheckpointLogFrames) {
            throw new IllegalArgumentException(
                    "Complete persistence performance metrics are required"
            );
        }
        startupNodes = Map.copyOf(startupNodes);
    }

    /** Count and bounded nanosecond percentile estimates. */
    public record Latency(
            long count,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            long maxNanos
    ) {
        public Latency {
            if (count < 0 || p50Nanos < 0 || p95Nanos < p50Nanos
                    || p99Nanos < p95Nanos || maxNanos < 0
                    || (count == 0 && (p50Nanos != 0 || p95Nanos != 0
                    || p99Nanos != 0 || maxNanos != 0))) {
                throw new IllegalArgumentException(
                        "Consistent latency metrics are required"
                );
            }
        }
    }

    /** Queue pressure, wait, and execution latency for one bounded lane set. */
    public record QueuePerformance(
            int maximumDepth,
            @Nonnull Latency queueWait,
            @Nonnull Latency execution
    ) {
        public QueuePerformance {
            if (maximumDepth < 0 || queueWait == null
                    || execution == null) {
                throw new IllegalArgumentException(
                        "Complete queue performance metrics are required"
                );
            }
        }
    }
}
