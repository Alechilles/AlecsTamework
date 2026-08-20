package com.alechilles.alecstamework.persistence.runtime;

/**
 * Immutable, payload-free measurements for routed projection and maintenance
 * work.
 *
 * <p>The interface keeps the public metrics record backward compatible while
 * allowing the passive metrics bridge to return a small immutable value.</p>
 */
public interface PersistenceThroughputSnapshot {
    long projectionRelevantRows();

    long projectionSequencePositionsBypassed();

    long projectionBatchAcknowledgements();

    long projectionPublicationMerges();

    /** Compatibility alias matching the passive hook name. */
    default long projectionPublicationMerged() {
        return projectionPublicationMerges();
    }

    /** Compatibility alias matching the passive hook name. */
    default long projectionBatchAcknowledged() {
        return projectionBatchAcknowledgements();
    }

    long checkpointMaintenanceSubmissions();

    long checkpointMaintenanceReplacements();

    long checkpointMaintenanceFailures();

    int checkpointPendingKeys();

    int checkpointPendingWork();

    int checkpointInFlightWork();

    int checkpointMaximumInFlightWork();

    long checkpointOldestPendingAgeNanos();

    long profileMaintenanceSubmissions();

    long profileMaintenanceReplacements();

    long profileMaintenanceFailures();

    int profilePendingKeys();

    int profilePendingWork();

    int profileInFlightWork();

    int profileMaximumInFlightWork();

    long profileOldestPendingAgeNanos();

    long criticalFlushFailures();

    long readSaturationFailures();

    int writerMaximumDepth();

    /** Returns an all-zero immutable snapshot for compatibility constructors. */
    static PersistenceThroughputSnapshot empty() {
        return Values.EMPTY;
    }

    /** Compact immutable implementation used by the passive control plane. */
    record Values(
            long projectionRelevantRows,
            long projectionSequencePositionsBypassed,
            long projectionBatchAcknowledgements,
            long projectionPublicationMerges,
            long checkpointMaintenanceSubmissions,
            long checkpointMaintenanceReplacements,
            long checkpointMaintenanceFailures,
            int checkpointPendingKeys,
            int checkpointPendingWork,
            int checkpointInFlightWork,
            int checkpointMaximumInFlightWork,
            long checkpointOldestPendingAgeNanos,
            long profileMaintenanceSubmissions,
            long profileMaintenanceReplacements,
            long profileMaintenanceFailures,
            int profilePendingKeys,
            int profilePendingWork,
            int profileInFlightWork,
            int profileMaximumInFlightWork,
            long profileOldestPendingAgeNanos,
            long criticalFlushFailures,
            long readSaturationFailures,
            int writerMaximumDepth
    ) implements PersistenceThroughputSnapshot {
        private static final Values EMPTY = new Values(
                0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0
        );

        public Values {
            if (projectionRelevantRows < 0
                    || projectionSequencePositionsBypassed < 0
                    || projectionBatchAcknowledgements < 0
                    || projectionPublicationMerges < 0
                    || checkpointMaintenanceSubmissions < 0
                    || checkpointMaintenanceReplacements < 0
                    || checkpointMaintenanceFailures < 0
                    || checkpointPendingKeys < 0
                    || checkpointPendingWork < 0
                    || checkpointInFlightWork < 0
                    || checkpointMaximumInFlightWork < checkpointInFlightWork
                    || checkpointOldestPendingAgeNanos < 0
                    || profileMaintenanceSubmissions < 0
                    || profileMaintenanceReplacements < 0
                    || profileMaintenanceFailures < 0
                    || profilePendingKeys < 0
                    || profilePendingWork < 0
                    || profileInFlightWork < 0
                    || profileMaximumInFlightWork < profileInFlightWork
                    || profileOldestPendingAgeNanos < 0
                    || criticalFlushFailures < 0
                    || readSaturationFailures < 0
                    || writerMaximumDepth < 0) {
                throw new IllegalArgumentException(
                        "Throughput metrics must be non-negative"
                );
            }
            if (checkpointPendingWork < checkpointPendingKeys
                    || profilePendingWork < profilePendingKeys) {
                throw new IllegalArgumentException(
                        "Pending work cannot be below pending keys"
                );
            }
        }
    }
}
