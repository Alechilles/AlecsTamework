package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceMetricsSnapshot;

/** Passive hooks for bounded persistence throughput evidence. */
public interface PersistenceThroughputMetrics {
    PersistenceThroughputMetrics NO_OP = new PersistenceThroughputMetrics() {
    };

    default void projectionBatchLoaded(
            long sequencePositions,
            int relevantRows
    ) {
    }

    default void projectionBatchAcknowledged() {
    }

    default void projectionPublicationMerged() {
    }

    default void checkpointMaintenance(
            MaintenanceMetricsSnapshot snapshot
    ) {
    }

    default void profileMaintenance(
            MaintenanceMetricsSnapshot snapshot
    ) {
    }

    default void criticalFlushFailed() {
    }

    default PersistenceThroughputSnapshot snapshot() {
        return PersistenceThroughputSnapshot.empty();
    }
}
