package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record PersistenceDiagnosticsView(@Nonnull String databasePath,
                                         long sqliteBytes,
                                         long walBytes,
                                         long shmBytes,
                                         long totalBytes,
                                         @Nonnull QueueMetricsView queueMetrics,
                                         @Nonnull HealthView health) {
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
