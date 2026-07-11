package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Serializes DB mutations on one worker and drains every accepted mutation before clean shutdown.
 */
public final class PersistenceWriteQueue implements AutoCloseable {
    private static final int MAX_BATCH_SIZE = 256;
    private static final long FLUSH_INTERVAL_MS = 10L;
    private static final int MAX_TRANSIENT_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS = 20L;
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
    @Nullable
    private final HytaleLogger logger;
    private final long closeJoinTimeoutMs;
    private final Object lifecycleLock = new Object();
    private final LinkedBlockingQueue<WriteTask<?>> queue = new LinkedBlockingQueue<>();
    private final AtomicReference<List<WriteTask<?>>> activeTasks = new AtomicReference<>(List.of());
    private final AtomicReference<QueueState> state = new AtomicReference<>(QueueState.OPEN);
    private final Thread workerThread;
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
    private final AtomicInteger activeBatchSize = new AtomicInteger();
    private final AtomicInteger pendingTaskCount = new AtomicInteger();
    private final AtomicReference<String> lastFailureReason = new AtomicReference<>();
    private final AtomicLong lastFailureAtMs = new AtomicLong();
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
        this.connectionManager = connectionManager;
        this.healthService = healthService;
        this.logger = logger;
        this.closeJoinTimeoutMs = Math.max(1L, closeJoinTimeoutMs);
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
        synchronized (lifecycleLock) {
            if (state.get() != QueueState.OPEN || !healthService.isHealthy()) {
                return WriteSubmission.rejected(rejectionReason());
            }
            WriteTask<T> task = new WriteTask<>(operationName, work, afterCommit);
            pendingTaskCount.incrementAndGet();
            if (!queue.offer(task)) {
                decrementPendingTaskCount(1);
                return WriteSubmission.rejected("write_queue_offer_failed");
            }
            return new WriteSubmission<>(true, task.completion);
        }
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
        long processedBatches = batchesProcessed.get();
        long totalDurationNs = totalWriteDurationNs.get();
        return new QueueMetrics(
                queue.size(),
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
    public QueueLifecycleMetrics getLifecycleMetrics() {
        return new QueueLifecycleMetrics(
                state.get(),
                pendingTaskCount.get(),
                activeBatchSize.get(),
                failedAcceptedTasks.get(),
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
                WriteTask<?> first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<WriteTask<?>> batch = new ArrayList<>(MAX_BATCH_SIZE);
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH_SIZE - 1);
                runBatchWithRetry(batch);
                if (!healthService.isHealthy()) {
                    beginDraining();
                    settleQueued(WriteStatus.FAILED, "persistence_unhealthy");
                }
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            markFailure("sqlite_write_worker_failed", exception);
        } finally {
            settleQueued(WriteStatus.FAILED, "write_queue_closed_before_commit");
            state.set(QueueState.CLOSED);
        }
    }

    private void runBatchWithRetry(@Nonnull List<WriteTask<?>> batch) {
        if (batch.isEmpty()) {
            return;
        }
        activeTasks.set(List.copyOf(batch));
        activeBatchSize.set(batch.size());
        try {
            int attempt = 0;
            while (attempt <= MAX_TRANSIENT_RETRIES && state.get() != QueueState.CLOSED) {
                attempt++;
                long startedNs = System.nanoTime();
                try {
                    runBatchOnce(batch);
                    recordBatchSuccess(batch.size(), Math.max(0L, System.nanoTime() - startedNs));
                    completeBatch(batch, WriteStatus.COMMITTED, null, null);
                    return;
                } catch (Exception exception) {
                    if (isTransientBusyFailure(exception) && attempt <= MAX_TRANSIENT_RETRIES) {
                        retryAttempts.incrementAndGet();
                        sleepQuietly(RETRY_BACKOFF_MS * attempt);
                        continue;
                    }
                    String reason = "sqlite_write_failed:" + batch.getFirst().operationName
                            + ":" + exception.getClass().getSimpleName();
                    markFailure(reason, exception);
                    completeBatch(batch, WriteStatus.FAILED, reason, exception);
                    return;
                }
            }
            completeBatch(batch, WriteStatus.FAILED, "write_queue_closed_before_commit", null);
        } finally {
            activeTasks.set(List.of());
            activeBatchSize.set(0);
            decrementPendingTaskCount(batch.size());
        }
    }

    private void runBatchOnce(@Nonnull List<WriteTask<?>> batch) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (WriteTask<?> task : batch) {
                    task.runWork(connection);
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        if (!drainTimedOut.get()) {
            for (WriteTask<?> task : batch) {
                runAfterCommit(task);
            }
        }
    }

    private void runAfterCommit(@Nonnull WriteTask<?> task) {
        try {
            task.runAfterCommit();
        } catch (Exception exception) {
            if (logger != null) {
                logger.at(Level.SEVERE).log(
                        "SQLite after-commit callback failed (" + task.operationName + "): " + exception.getMessage()
                );
            }
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "persistence_after_commit_callback_failed",
                    exception,
                    TameworkTelemetryContext.persistence(
                                    "write_queue", "after_commit_callback", "callback_failed",
                                    "SQLite after-commit callback failed."
                            )
                            .detail("writeOperation", TameworkTelemetryContext.normalizeToken(task.operationName))
                            .build()
            );
        }
    }

    private void completeBatch(@Nonnull List<WriteTask<?>> batch,
                               @Nonnull WriteStatus status,
                               @Nullable String reason,
                               @Nullable Throwable failure) {
        if (status != WriteStatus.COMMITTED) {
            failedAcceptedTasks.addAndGet(batch.size());
        }
        for (WriteTask<?> task : batch) {
            task.complete(status, reason, failure);
        }
    }

    private void settleQueued(@Nonnull WriteStatus status, @Nonnull String reason) {
        List<WriteTask<?>> tasks = new ArrayList<>();
        queue.drainTo(tasks);
        if (tasks.isEmpty()) {
            return;
        }
        completeBatch(tasks, status, reason, new IllegalStateException(reason));
        decrementPendingTaskCount(tasks.size());
    }

    private void markFailure(@Nonnull String reason, @Nonnull Exception exception) {
        healthService.markDegraded(reason);
        failedBatches.incrementAndGet();
        lastFailureReason.set(reason);
        lastFailureAtMs.set(System.currentTimeMillis());
        TameworkTelemetryEvents.recordErrorIfAvailable(
                "persistence_write_failed",
                exception,
                TameworkTelemetryContext.persistence(
                        "write_queue", "write_batch", reason, "SQLite write failed."
                ).build()
        );
        if (logger != null) {
            logger.at(Level.SEVERE).log("SQLite write failed (" + reason + "): " + exception.getMessage());
        }
    }

    private void recordBatchSuccess(int batchSize, long durationNs) {
        batchesProcessed.incrementAndGet();
        operationsProcessed.addAndGet(Math.max(0, batchSize));
        totalBatchSize.addAndGet(Math.max(0, batchSize));
        totalWriteDurationNs.addAndGet(durationNs);
        lastBatchSize.set(Math.max(0, batchSize));
        lastBatchDurationNs.set(durationNs);
        maxBatchSize.accumulateAndGet(Math.max(0, batchSize), Math::max);
    }

    private boolean isTransientBusyFailure(@Nonnull Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("sqlite_busy")
                        || normalized.contains("sqlite_locked")
                        || normalized.contains("database is locked")
                        || normalized.contains("database is busy")
                        || normalized.contains("database table is locked")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
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
        beginDraining();
        joinWorker(closeJoinTimeoutMs);
        if (!workerThread.isAlive()) {
            return;
        }
        drainTimedOut.set(true);
        String reason = "persistence_shutdown_drain_timeout";
        healthService.markDegraded(reason);
        lastFailureReason.set(reason);
        lastFailureAtMs.set(System.currentTimeMillis());
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

    private static final class WriteTask<T> {
        private final String operationName;
        private final SqlWork<T> work;
        @Nullable
        private final Consumer<T> afterCommit;
        private final CompletableFuture<WriteOutcome<T>> completion = new CompletableFuture<>();
        @Nullable
        private T result;

        private WriteTask(@Nonnull String operationName,
                          @Nonnull SqlWork<T> work,
                          @Nullable Consumer<T> afterCommit) {
            this.operationName = operationName;
            this.work = work;
            this.afterCommit = afterCommit;
        }

        private void runWork(@Nonnull Connection connection) throws Exception {
            result = work.run(connection);
        }

        private void runAfterCommit() {
            if (afterCommit != null) {
                afterCommit.accept(result);
            }
        }

        private void complete(@Nonnull WriteStatus status,
                              @Nullable String reason,
                              @Nullable Throwable failure) {
            completion.complete(new WriteOutcome<>(status, result, reason, failure));
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

