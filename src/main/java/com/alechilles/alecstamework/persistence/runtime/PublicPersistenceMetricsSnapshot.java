package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Passive descriptor-derived counters and last unbounded failure evidence. */
public record PublicPersistenceMetricsSnapshot(
        long readsCompleted,
        long readsFailed,
        long checkpointFailures,
        long shutdownTimeouts,
        @Nullable String lastGlobalFailureCode,
        @Nonnull Map<PersistenceFeatureId, FeatureMetrics> features
) {
    public PublicPersistenceMetricsSnapshot {
        if (readsCompleted < 0 || readsFailed < 0
                || checkpointFailures < 0 || shutdownTimeouts < 0
                || features == null || features.isEmpty()
                || features.values().stream().anyMatch(
                java.util.Objects::isNull
        )) {
            throw new IllegalArgumentException(
                    "Complete public persistence metrics are required"
            );
        }
        lastGlobalFailureCode = normalize(lastGlobalFailureCode);
        features = Map.copyOf(features);
    }

    /** Counters for one feature, named by its descriptor namespace. */
    public record FeatureMetrics(
            @Nonnull String namespace,
            long writesAccepted,
            long writesRejected,
            long busyRetries,
            long unitsCompleted,
            long unitsFailed
    ) {
        public FeatureMetrics {
            if (namespace == null || namespace.isBlank()
                    || writesAccepted < 0 || writesRejected < 0
                    || busyRetries < 0 || unitsCompleted < 0
                    || unitsFailed < 0) {
                throw new IllegalArgumentException(
                        "Complete feature metrics are required"
                );
            }
            namespace = namespace.trim();
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
