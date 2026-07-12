package com.alechilles.alecstamework.persistence.sqlite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceWriteQueueMetricsTest {
    @Test
    void snapshotsBatchAndLifecycleAccountingWithoutOwningQueueExecution() {
        PersistenceWriteQueueMetrics metrics = new PersistenceWriteQueueMetrics();
        metrics.recordBatchSuccess(3, 6_000_000L);
        metrics.recordRetry();
        metrics.recordAcceptedFailures(2);
        metrics.recordBatchFailure("intentional_failure");

        PersistenceWriteQueue.QueueMetrics queue = metrics.snapshot(4);
        assertEquals(4, queue.queueDepth());
        assertEquals(3, queue.lastBatchSize());
        assertEquals(3, queue.maxBatchSize());
        assertEquals(1L, queue.batchesProcessed());
        assertEquals(3L, queue.operationsProcessed());
        assertEquals(1L, queue.retryAttempts());
        assertEquals(1L, queue.failedBatches());
        assertEquals(3.0, queue.averageBatchSize());
        assertEquals(6.0, queue.averageWriteMs());
        assertEquals("intentional_failure", queue.lastFailureReason());
        assertTrue(queue.lastFailureAtMs() > 0L);

        PersistenceWriteQueue.QueueLifecycleMetrics lifecycle = metrics.lifecycleSnapshot(
                PersistenceWriteQueue.QueueState.DRAINING,
                5,
                2,
                true
        );
        assertEquals(PersistenceWriteQueue.QueueState.DRAINING, lifecycle.state());
        assertEquals(5, lifecycle.pendingTaskCount());
        assertEquals(2, lifecycle.activeBatchSize());
        assertEquals(2L, lifecycle.failedAcceptedTasks());
        assertTrue(lifecycle.drainTimedOut());
    }
}
