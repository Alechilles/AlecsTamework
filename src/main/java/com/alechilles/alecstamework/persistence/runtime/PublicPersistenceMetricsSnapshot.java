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
        @Nonnull Map<PersistenceFeatureId, FeatureMetrics> features,
        @Nonnull PersistenceThroughputSnapshot throughput
) implements PersistenceThroughputSnapshot {
    /** Preserves the original public constructor without throughput metrics. */
    public PublicPersistenceMetricsSnapshot(
            long readsCompleted,
            long readsFailed,
            long checkpointFailures,
            long shutdownTimeouts,
            @Nullable String lastGlobalFailureCode,
            @Nonnull Map<PersistenceFeatureId, FeatureMetrics> features
    ) {
        this(
                readsCompleted,
                readsFailed,
                checkpointFailures,
                shutdownTimeouts,
                lastGlobalFailureCode,
                features,
                PersistenceThroughputSnapshot.empty()
        );
    }

    public PublicPersistenceMetricsSnapshot {
        if (readsCompleted < 0 || readsFailed < 0
                || checkpointFailures < 0 || shutdownTimeouts < 0
                || features == null || features.isEmpty()
                || features.values().stream().anyMatch(
                java.util.Objects::isNull
        ) || throughput == null) {
            throw new IllegalArgumentException(
                    "Complete public persistence metrics are required"
            );
        }
        lastGlobalFailureCode = normalize(lastGlobalFailureCode);
        features = Map.copyOf(features);
    }

    @Override
    public long projectionRelevantRows() {
        return throughput.projectionRelevantRows();
    }

    @Override
    public long projectionSequencePositionsBypassed() {
        return throughput.projectionSequencePositionsBypassed();
    }

    @Override
    public long projectionBatchAcknowledgements() {
        return throughput.projectionBatchAcknowledgements();
    }

    @Override
    public long projectionPublicationMerges() {
        return throughput.projectionPublicationMerges();
    }

    @Override
    public long checkpointMaintenanceSubmissions() {
        return throughput.checkpointMaintenanceSubmissions();
    }

    @Override
    public long checkpointMaintenanceReplacements() {
        return throughput.checkpointMaintenanceReplacements();
    }

    @Override
    public long checkpointMaintenanceFailures() {
        return throughput.checkpointMaintenanceFailures();
    }

    @Override
    public int checkpointPendingKeys() {
        return throughput.checkpointPendingKeys();
    }

    @Override
    public int checkpointPendingWork() {
        return throughput.checkpointPendingWork();
    }

    @Override
    public int checkpointInFlightWork() {
        return throughput.checkpointInFlightWork();
    }

    @Override
    public int checkpointMaximumInFlightWork() {
        return throughput.checkpointMaximumInFlightWork();
    }

    @Override
    public long checkpointOldestPendingAgeNanos() {
        return throughput.checkpointOldestPendingAgeNanos();
    }

    @Override
    public long profileMaintenanceSubmissions() {
        return throughput.profileMaintenanceSubmissions();
    }

    @Override
    public long profileMaintenanceReplacements() {
        return throughput.profileMaintenanceReplacements();
    }

    @Override
    public long profileMaintenanceFailures() {
        return throughput.profileMaintenanceFailures();
    }

    @Override
    public int profilePendingKeys() {
        return throughput.profilePendingKeys();
    }

    @Override
    public int profilePendingWork() {
        return throughput.profilePendingWork();
    }

    @Override
    public int profileInFlightWork() {
        return throughput.profileInFlightWork();
    }

    @Override
    public int profileMaximumInFlightWork() {
        return throughput.profileMaximumInFlightWork();
    }

    @Override
    public long profileOldestPendingAgeNanos() {
        return throughput.profileOldestPendingAgeNanos();
    }

    @Override
    public long criticalFlushFailures() {
        return throughput.criticalFlushFailures();
    }

    @Override
    public long readSaturationFailures() {
        return throughput.readSaturationFailures();
    }

    @Override
    public int writerMaximumDepth() {
        return throughput.writerMaximumDepth();
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
