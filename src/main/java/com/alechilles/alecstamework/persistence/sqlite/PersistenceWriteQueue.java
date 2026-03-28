package com.alechilles.alecstamework.persistence.sqlite;

import com.hypixel.hytale.logger.HytaleLogger;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Serializes DB mutations on one worker thread and marks persistence unhealthy on write failures.
 */
public final class PersistenceWriteQueue implements AutoCloseable {
    private static final int MAX_BATCH_SIZE = 256;
    private static final long FLUSH_INTERVAL_MS = 10L;
    private static final int MAX_TRANSIENT_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS = 20L;

    @FunctionalInterface
    public interface SqlTransaction {
        void run(@Nonnull Connection connection) throws Exception;
    }

    private final SqliteConnectionManager connectionManager;
    private final PersistenceHealthService healthService;
    @Nullable
    private final HytaleLogger logger;
    private final LinkedBlockingQueue<WriteTask> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread workerThread;
    private final AtomicLong batchesProcessed = new AtomicLong(0L);
    private final AtomicLong operationsProcessed = new AtomicLong(0L);
    private final AtomicLong retryAttempts = new AtomicLong(0L);
    private final AtomicLong failedBatches = new AtomicLong(0L);
    private final AtomicLong totalBatchSize = new AtomicLong(0L);
    private final AtomicLong totalWriteDurationNs = new AtomicLong(0L);
    private final AtomicInteger lastBatchSize = new AtomicInteger(0);
    private final AtomicLong lastBatchDurationNs = new AtomicLong(0L);
    private final AtomicInteger maxBatchSize = new AtomicInteger(0);
    private final AtomicReference<String> lastFailureReason = new AtomicReference<>(null);
    private final AtomicLong lastFailureAtMs = new AtomicLong(0L);

    public PersistenceWriteQueue(@Nonnull SqliteConnectionManager connectionManager,
                                 @Nonnull PersistenceHealthService healthService,
                                 @Nullable HytaleLogger logger) {
        this.connectionManager = connectionManager;
        this.healthService = healthService;
        this.logger = logger;
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
        if (closed.get() || !healthService.isHealthy()) {
            return false;
        }
        return queue.offer(new WriteTask(operationName, transaction, afterCommit));
    }

    @Nonnull
    public QueueMetrics getMetrics() {
        long processedBatches = batchesProcessed.get();
        long processedOperations = operationsProcessed.get();
        long totalBatchSizes = totalBatchSize.get();
        long totalDurationNs = totalWriteDurationNs.get();
        double averageBatchSize = processedBatches > 0L
                ? (double) totalBatchSizes / (double) processedBatches
                : 0.0;
        double averageWriteMs = processedBatches > 0L
                ? (double) totalDurationNs / 1_000_000.0 / (double) processedBatches
                : 0.0;
        return new QueueMetrics(
                queue.size(),
                lastBatchSize.get(),
                maxBatchSize.get(),
                processedBatches,
                processedOperations,
                retryAttempts.get(),
                failedBatches.get(),
                averageBatchSize,
                averageWriteMs,
                lastBatchDurationNs.get() / 1_000_000.0,
                lastFailureReason.get(),
                lastFailureAtMs.get()
        );
    }

    private void workerLoop() {
        while (!closed.get()) {
            try {
                WriteTask first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<WriteTask> batch = new ArrayList<>(MAX_BATCH_SIZE);
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH_SIZE - 1);
                runBatchWithRetry(batch);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                return;
            }
        }
    }

    private void runBatchWithRetry(@Nonnull List<WriteTask> batch) throws Exception {
        if (batch.isEmpty()) {
            return;
        }
        int attempt = 0;
        while (attempt <= MAX_TRANSIENT_RETRIES && !closed.get()) {
            attempt++;
            long startedNs = System.nanoTime();
            try {
                runBatchOnce(batch);
                long durationNs = Math.max(0L, System.nanoTime() - startedNs);
                recordBatchSuccess(batch.size(), durationNs);
                return;
            } catch (Exception ex) {
                if (isTransientBusyFailure(ex) && attempt <= MAX_TRANSIENT_RETRIES) {
                    retryAttempts.incrementAndGet();
                    sleepQuietly(RETRY_BACKOFF_MS * attempt);
                    continue;
                }
                String operation = batch.get(0).operationName;
                markFailure("sqlite_write_failed:" + operation + ":" + ex.getClass().getSimpleName(), ex);
                throw ex;
            }
        }
        markFailure("sqlite_write_batch_failed", new IllegalStateException("write queue retry exhausted"));
        throw new IllegalStateException("write queue retry exhausted");
    }

    private void runBatchOnce(@Nonnull List<WriteTask> batch) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (WriteTask task : batch) {
                    task.transaction.run(connection);
                }
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        for (WriteTask task : batch) {
            try {
                task.runAfterCommit();
            } catch (Exception ex) {
                if (logger != null) {
                    logger.at(Level.SEVERE).log(
                            "SQLite after-commit callback failed (" + task.operationName + "): " + ex.getMessage()
                    );
                }
            }
        }
    }

    private void markFailure(@Nonnull String reason, @Nonnull Exception ex) {
        healthService.markDegraded(reason);
        failedBatches.incrementAndGet();
        lastFailureReason.set(reason);
        lastFailureAtMs.set(System.currentTimeMillis());
        if (logger != null) {
            logger.at(Level.SEVERE).log("SQLite write failed (" + reason + "): " + ex.getMessage());
        }
    }

    private void recordBatchSuccess(int batchSize, long durationNs) {
        batchesProcessed.incrementAndGet();
        operationsProcessed.addAndGet(Math.max(0, batchSize));
        totalBatchSize.addAndGet(Math.max(0, batchSize));
        totalWriteDurationNs.addAndGet(Math.max(0L, durationNs));
        lastBatchSize.set(Math.max(0, batchSize));
        lastBatchDurationNs.set(Math.max(0L, durationNs));
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

    private void sleepQuietly(long delayMs) {
        if (delayMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        workerThread.interrupt();
        queue.clear();
    }

    private static final class WriteTask {
        private final String operationName;
        private final SqlTransaction transaction;
        @Nullable
        private final Runnable afterCommit;

        private WriteTask(@Nonnull String operationName,
                          @Nonnull SqlTransaction transaction,
                          @Nullable Runnable afterCommit) {
            this.operationName = Objects.requireNonNull(operationName);
            this.transaction = Objects.requireNonNull(transaction);
            this.afterCommit = afterCommit;
        }

        private void runAfterCommit() {
            if (afterCommit != null) {
                afterCommit.run();
            }
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
}
