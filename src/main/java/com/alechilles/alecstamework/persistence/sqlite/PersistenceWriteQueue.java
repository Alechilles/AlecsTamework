package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.operation.PersistenceOperationMetadata;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpointHook;
import com.alechilles.alecstamework.persistence.operation.PersistenceWriteFailureHandler;
import com.hypixel.hytale.logger.HytaleLogger;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Serializes DB mutations on one worker and drains every accepted mutation before clean shutdown.
 */
public final class PersistenceWriteQueue implements AutoCloseable {
    private static final int MAX_BATCH_SIZE = 256;
    private static final long FLUSH_INTERVAL_MS = 10L;
    private static final long DEFAULT_CLOSE_JOIN_TIMEOUT_MS = 2_000L;

    @FunctionalInterface
    public interface SqlTransaction {
        void run(@Nonnull Connection connection) throws Exception;
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(@Nonnull Connection connection) throws Exception;
    }

    public enum QueueState {
        OPEN,
        DRAINING,
        CLOSED
    }

    public enum WriteStatus {
        COMMITTED,
        FAILED,
        REJECTED,
        DRAIN_TIMED_OUT_UNKNOWN
    }

    private final SqliteConnectionManager connectionManager;
    private final PersistenceHealthService healthService;
    private final long closeJoinTimeoutMs;
    private final Object lifecycleLock = new Object();
    private final LinkedBlockingQueue<PersistenceWriteTask<?>> queue = new LinkedBlockingQueue<>();
    private final AtomicReference<List<PersistenceWriteTask<?>>> activeTasks = new AtomicReference<>(List.of());
    private final AtomicReference<QueueState> state = new AtomicReference<>(QueueState.OPEN);
    private final Thread workerThread;
    private final PersistenceWriteQueueMetrics metrics = new PersistenceWriteQueueMetrics();
    private final AtomicReference<PersistenceWriteFailureHandler> failureHandler =
            new AtomicReference<>(PersistenceWriteFailureHandler.NO_OP);
    private final PersistenceWriteBatchExecutor batchExecutor;
    private final PersistenceCheckpointHook checkpoints;
    private final AtomicInteger activeBatchSize = new AtomicInteger();
    private final AtomicInteger pendingTaskCount = new AtomicInteger();
    private final AtomicBoolean drainTimedOut = new AtomicBoolean();

    public PersistenceWriteQueue(@Nonnull SqliteConnectionManager connectionManager,
                                 @Nonnull PersistenceHealthService healthService,
                                 @Nullable HytaleLogger logger) {
        this(connectionManager, healthService, logger, DEFAULT_CLOSE_JOIN_TIMEOUT_MS);
    }

    PersistenceWriteQueue(@Nonnull SqliteConnectionManager connectionManager,
                          @Nonnull PersistenceHealthService healthService,
                          @Nullable HytaleLogger logger,
                          long closeJoinTimeoutMs) {
        this(connectionManager, healthService, logger, closeJoinTimeoutMs,
                PersistenceCheckpointHook.NO_OP);
    }

    PersistenceWriteQueue(@Nonnull SqliteConnectionManager connectionManager,
                          @Nonnull PersistenceHealthService healthService,
                          @Nullable HytaleLogger logger,
                          long closeJoinTimeoutMs,
                          @Nonnull PersistenceCheckpointHook checkpoints) {
        this.connectionManager = connectionManager;
        this.healthService = healthService;
        this.closeJoinTimeoutMs = Math.max(1L, closeJoinTimeoutMs);
        this.checkpoints = checkpoints;
        this.batchExecutor = new PersistenceWriteBatchExecutor(
                connectionManager, healthService, metrics, failureHandler, logger, checkpoints);
        this.workerThread = new Thread(this::workerLoop, "tamework-persistence-writer");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    public boolean submit(@Nonnull String operationName, @Nonnull SqlTransaction transaction) {
        return submit(operationName, transaction, null);
    }

    public boolean submit(@Nonnull String operationName,
                          @Nonnull SqlTransaction transaction,
                          @Nullable Runnable afterCommit) {
        return submitTracked(
                operationName,
                connection -> {
                    transaction.run(connection);
                    return null;
                },
                ignored -> {
                    if (afterCommit != null) {
                        afterCommit.run();
                    }
                }
        ).accepted();
    }

    @Nonnull
    public CompletableFuture<WriteResult> submitWithCompletion(@Nonnull String operationName,
                                                                @Nonnull SqlTransaction transaction) {
        return submitWithCompletion(operationName, transaction, null);
    }

    @Nonnull
    public CompletableFuture<WriteResult> submitWithCompletion(@Nonnull String operationName,
                                                                @Nonnull SqlTransaction transaction,
                                                                @Nullable Runnable afterCommit) {
        WriteSubmission<Void> submission = submitTracked(
                operationName,
                connection -> {
                    transaction.run(connection);
                    return null;
                },
                ignored -> {
                    if (afterCommit != null) {
                        afterCommit.run();
                    }
                }
        );
        return submission.completion().thenApply(WriteResult::fromOutcome);
    }

    /**
     * Returns both queue acceptance and an outcome completed after commit/callback or terminal failure.
     */
    @Nonnull
    public <T> WriteSubmission<T> submitTracked(@Nonnull String operationName,
                                                 @Nonnull SqlWork<T> work,
                                                 @Nullable Consumer<T> afterCommit) {
        return submitTracked(PersistenceOperationMetadata.legacy(operationName), work, afterCommit);
    }

    /** Accepts a lifecycle-critical write with exact isolation and read-back metadata. */
    @Nonnull
    public <T> WriteSubmission<T> submitTracked(@Nonnull PersistenceOperationMetadata metadata,
                                                 @Nonnull SqlWork<T> work,
                                                 @Nullable Consumer<T> afterCommit) {
        synchronized (lifecycleLock) {
            if (state.get() != QueueState.OPEN || !healthService.isHealthy()) {
                return WriteSubmission.rejected(rejectionReason());
            }
            PersistenceWriteTask<T> task = new PersistenceWriteTask<>(metadata, work, afterCommit);
            pendingTaskCount.incrementAndGet();
            if (!queue.offer(task)) {
                decrementPendingTaskCount(1);
                return WriteSubmission.rejected("write_queue_offer_failed");
            }
            return new WriteSubmission<>(true, task.completion());
        }
    }

    /** Installs the passive classifier after runtime composition resolves the queue/reporter cycle. */
    public void setFailureHandler(@Nullable PersistenceWriteFailureHandler handler) {
        failureHandler.set(handler == null ? PersistenceWriteFailureHandler.NO_OP : handler);
    }

    @Nonnull
    public QueueState getState() {
        return state.get();
    }

    public boolean awaitIdle(long timeoutMs) {
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        while (System.nanoTime() <= deadlineNs) {
            if (isIdle()) {
                return true;
            }
            sleepQuietly(10L);
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isIdle();
    }

    /**
     * Retains the established metrics record shape used by the public diagnostics mapper.
     */
    @Nonnull
    public QueueMetrics getMetrics() {
        return metrics.snapshot(queue.size());
    }

    @Nonnull
    public QueueLifecycleMetrics getLifecycleMetrics() {
        return metrics.lifecycleSnapshot(
                state.get(),
                pendingTaskCount.get(),
                activeBatchSize.get(),
                drainTimedOut.get()
        );
    }

    private void workerLoop() {
        try {
            while (state.get() != QueueState.CLOSED) {
                if (state.get() == QueueState.DRAINING && queue.isEmpty()) {
                    state.compareAndSet(QueueState.DRAINING, QueueState.CLOSED);
                    break;
                }
                if (!healthService.isHealthy()) {
                    sleepQuietly(FLUSH_INTERVAL_MS);
                    continue;
                }
                PersistenceWriteTask<?> first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<PersistenceWriteTask<?>> batch = new ArrayList<>(MAX_BATCH_SIZE);
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH_SIZE - 1);
                runBatch(batch);
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            healthService.markDegraded("sqlite_write_worker_failed");
            metrics.recordBatchFailure("sqlite_write_worker_failed");
        } finally {
            settleQueued(WriteStatus.FAILED, "write_queue_closed_before_commit");
            state.set(QueueState.CLOSED);
        }
    }

    private void runBatch(@Nonnull List<PersistenceWriteTask<?>> batch) {
        if (batch.isEmpty()) {
            return;
        }
        activeTasks.set(List.copyOf(batch));
        activeBatchSize.set(batch.size());
        try {
            batchExecutor.execute(batch, drainTimedOut.get());
        } finally {
            activeTasks.set(List.of());
            activeBatchSize.set(0);
            decrementPendingTaskCount(batch.size());
        }
    }

    private void completeBatch(@Nonnull List<PersistenceWriteTask<?>> batch,
                               @Nonnull WriteStatus status,
                               @Nullable String reason,
                               @Nullable Throwable failure) {
        if (status != WriteStatus.COMMITTED) {
            metrics.recordAcceptedFailures(batch.size());
        }
        for (PersistenceWriteTask<?> task : batch) {
            task.complete(status, reason, failure);
        }
    }

    private void settleQueued(@Nonnull WriteStatus status, @Nonnull String reason) {
        List<PersistenceWriteTask<?>> tasks = new ArrayList<>();
        queue.drainTo(tasks);
        if (tasks.isEmpty()) {
            return;
        }
        completeBatch(tasks, status, reason, new IllegalStateException(reason));
        decrementPendingTaskCount(tasks.size());
    }

    private boolean isIdle() {
        return pendingTaskCount.get() == 0 && activeBatchSize.get() == 0 && queue.isEmpty();
    }

    private void beginDraining() {
        synchronized (lifecycleLock) {
            state.compareAndSet(QueueState.OPEN, QueueState.DRAINING);
        }
    }

    @Nonnull
    private String rejectionReason() {
        return !healthService.isHealthy()
                ? "persistence_unhealthy"
                : "write_queue_" + state.get().name().toLowerCase(Locale.ROOT);
    }

    private void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(Math.max(0L, delayMs));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (state.get() == QueueState.CLOSED) {
            return;
        }
        try {
            checkpoints.hit(PersistenceCheckpoint.DURING_SHUTDOWN_RESTART_RECONSTRUCTION, null);
        } catch (Exception failure) {
            healthService.markDegraded("persistence_shutdown_checkpoint_failed");
        }
        beginDraining();
        joinWorker(closeJoinTimeoutMs);
        if (!workerThread.isAlive()) {
            return;
        }
        drainTimedOut.set(true);
        String reason = "persistence_shutdown_drain_timeout";
        healthService.markDegraded(reason);
        metrics.recordFailureReason(reason);
        state.set(QueueState.CLOSED);
        completeBatch(activeTasks.get(), WriteStatus.DRAIN_TIMED_OUT_UNKNOWN, reason, null);
        settleQueued(WriteStatus.DRAIN_TIMED_OUT_UNKNOWN, reason);
        workerThread.interrupt();
        joinWorker(Math.min(250L, closeJoinTimeoutMs));
    }

    private void joinWorker(long timeoutMs) {
        if (Thread.currentThread() == workerThread) {
            return;
        }
        try {
            workerThread.join(Math.max(1L, timeoutMs));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private void decrementPendingTaskCount(int count) {
        if (count > 0) {
            pendingTaskCount.updateAndGet(current -> Math.max(0, current - count));
        }
    }

    public record WriteSubmission<T>(boolean accepted,
                                     @Nonnull CompletableFuture<WriteOutcome<T>> completion) {
        @Nonnull
        private static <T> WriteSubmission<T> rejected(@Nonnull String reason) {
            return new WriteSubmission<>(
                    false,
                    CompletableFuture.completedFuture(
                            new WriteOutcome<>(WriteStatus.REJECTED, null, reason, null)
                    )
            );
        }
    }

    public record WriteOutcome<T>(@Nonnull WriteStatus status,
                                  @Nullable T value,
                                  @Nullable String failureReason,
                                  @Nullable Throwable failure) {
        public boolean isCommitted() {
            return status == WriteStatus.COMMITTED;
        }
        public boolean isTransientFailure() {
            return status == WriteStatus.FAILED && failure != null
                    && SqliteBusyFailureClassifier.isTransient(failure);
        }
    }

    public record WriteResult(@Nonnull WriteStatus status,
                              @Nullable String failureReason,
                              @Nullable Throwable failure) {
        @Nonnull
        private static WriteResult fromOutcome(@Nonnull WriteOutcome<?> outcome) {
            return new WriteResult(outcome.status(), outcome.failureReason(), outcome.failure());
        }

        public boolean isCommitted() {
            return status == WriteStatus.COMMITTED;
        }
    }

    public record QueueMetrics(int queueDepth,
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

    public record QueueLifecycleMetrics(@Nonnull QueueState state,
                                        int pendingTaskCount,
                                        int activeBatchSize,
                                        long failedAcceptedTasks,
                                        boolean drainTimedOut) {
    }
}
