package com.alechilles.alecstamework.persistence.sqlite;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

/**
 * Owns write-queue accounting so queue orchestration stays focused on lifecycle and execution.
 */
final class PersistenceWriteQueueMetrics {
    private final AtomicLong batchesProcessed = new AtomicLong();
    private final AtomicLong operationsProcessed = new AtomicLong();
    private final AtomicLong retryAttempts = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();
    private final AtomicLong failedAcceptedTasks = new AtomicLong();
    private final AtomicLong totalBatchSize = new AtomicLong();
    private final AtomicLong totalWriteDurationNs = new AtomicLong();
    private final AtomicInteger lastBatchSize = new AtomicInteger();
    private final AtomicLong lastBatchDurationNs = new AtomicLong();
    private final AtomicInteger maxBatchSize = new AtomicInteger();
    private final AtomicReference<String> lastFailureReason = new AtomicReference<>();
    private final AtomicLong lastFailureAtMs = new AtomicLong();

    @Nonnull
    PersistenceWriteQueue.QueueMetrics snapshot(int queueDepth) {
        long processedBatches = batchesProcessed.get();
        long totalDurationNs = totalWriteDurationNs.get();
        return new PersistenceWriteQueue.QueueMetrics(
                queueDepth,
                lastBatchSize.get(),
                maxBatchSize.get(),
                processedBatches,
                operationsProcessed.get(),
                retryAttempts.get(),
                failedBatches.get(),
                processedBatches > 0L ? (double) totalBatchSize.get() / processedBatches : 0.0,
                processedBatches > 0L ? (double) totalDurationNs / 1_000_000.0 / processedBatches : 0.0,
                lastBatchDurationNs.get() / 1_000_000.0,
                lastFailureReason.get(),
                lastFailureAtMs.get()
        );
    }

    @Nonnull
    PersistenceWriteQueue.QueueLifecycleMetrics lifecycleSnapshot(
            @Nonnull PersistenceWriteQueue.QueueState state,
            int pendingTaskCount,
            int activeBatchSize,
            boolean drainTimedOut
    ) {
        return new PersistenceWriteQueue.QueueLifecycleMetrics(
                state,
                pendingTaskCount,
                activeBatchSize,
                failedAcceptedTasks.get(),
                drainTimedOut
        );
    }

    void recordBatchSuccess(int batchSize, long durationNs) {
        int safeBatchSize = Math.max(0, batchSize);
        batchesProcessed.incrementAndGet();
        operationsProcessed.addAndGet(safeBatchSize);
        totalBatchSize.addAndGet(safeBatchSize);
        totalWriteDurationNs.addAndGet(durationNs);
        lastBatchSize.set(safeBatchSize);
        lastBatchDurationNs.set(durationNs);
        maxBatchSize.accumulateAndGet(safeBatchSize, Math::max);
    }

    void recordRetry() {
        retryAttempts.incrementAndGet();
    }

    void recordAcceptedFailures(int count) {
        failedAcceptedTasks.addAndGet(Math.max(0, count));
    }

    void recordBatchFailure(@Nonnull String reason) {
        failedBatches.incrementAndGet();
        recordFailureReason(reason);
    }

    void recordFailureReason(@Nonnull String reason) {
        lastFailureReason.set(reason);
        lastFailureAtMs.set(System.currentTimeMillis());
    }
}
