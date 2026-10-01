package com.alechilles.alecstamework.api;

import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Companion store diagnostics. The first seven accessors keep their 2.x names because consumers
 * read them by reflection; on the file store {@code databasePath} is the companion folder,
 * {@code totalBytes} is its last measured size and the three SQLite sizes are 0.
 *
 * @param recordsByLocation companion records per location kind name (LIVE, ITEM, COOP, STORED,
 *                          DEAD, LOST, RELEASED); kinds with no record are present with 0
 * @param lastFlushAtMs     wall-clock time of the last successful write, 0 before the first one
 * @param lastFailure       the writer's current failure, or null while writes succeed
 * @param unreadableRecords records that could not be decoded when the store was loaded
 */
public record PersistenceDiagnosticsView(@Nonnull String databasePath,
                                         long sqliteBytes,
                                         long walBytes,
                                         long shmBytes,
                                         long totalBytes,
                                         @Nonnull QueueMetricsView queueMetrics,
                                         @Nonnull HealthView health,
                                         @Nonnull Map<String, Long> recordsByLocation,
                                         long lastFlushAtMs,
                                         @Nullable String lastFailure,
                                         int unreadableRecords) {
    public PersistenceDiagnosticsView {
        recordsByLocation = Map.copyOf(recordsByLocation);
    }

    /** The 2.x shape, for implementations that have no file-store details. */
    public PersistenceDiagnosticsView(@Nonnull String databasePath,
                                      long sqliteBytes,
                                      long walBytes,
                                      long shmBytes,
                                      long totalBytes,
                                      @Nonnull QueueMetricsView queueMetrics,
                                      @Nonnull HealthView health) {
        this(databasePath, sqliteBytes, walBytes, shmBytes, totalBytes, queueMetrics, health,
                Map.of(), 0L, null, 0);
    }

    public record QueueMetricsView(int queueDepth,
                                   int lastBatchSize,
                                   int maxBatchSize,
                                   long batchesProcessed,
                                   long operationsProcessed,
                                   long retryAttempts,
                                   long failedBatches,
                                   double averageBatchSize,
                                   double averageWriteMs,
                                   double lastBatchWriteMs,
                                   @Nullable String lastFailureReason,
                                   long lastFailureAtMs) {
    }

    public record HealthView(@Nonnull String status,
                             @Nullable String reason,
                             long lastFailureAtMs) {
    }
}
