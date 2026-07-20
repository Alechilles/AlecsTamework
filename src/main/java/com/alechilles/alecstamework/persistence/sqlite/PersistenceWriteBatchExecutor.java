package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.persistence.incidents.PersistenceTransactionOutcome;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.operation.PersistenceOperationMetadata;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpointHook;
import com.alechilles.alecstamework.persistence.operation.PersistenceWriteFailureHandler;
import com.hypixel.hytale.logger.HytaleLogger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Executes queue batches with bounded retry, rollback-aware isolation, and commit-safe publication. */
final class PersistenceWriteBatchExecutor {
    private static final int MAX_TRANSIENT_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS = 20L;
    private static final long RECOVERABLE_FAILURE_REPORT_INTERVAL_MS = 30_000L;

    private final SqliteConnectionManager connections;
    private final PersistenceHealthService health;
    private final PersistenceWriteQueueMetrics metrics;
    private final AtomicReference<PersistenceWriteFailureHandler> failureHandler;
    private final PersistenceCheckpointHook checkpoints;
    @Nullable
    private final HytaleLogger logger;
    private long lastRecoverableFailureReportAtMs;

    PersistenceWriteBatchExecutor(SqliteConnectionManager connections,
                                  PersistenceHealthService health,
                                  PersistenceWriteQueueMetrics metrics,
                                  AtomicReference<PersistenceWriteFailureHandler> failureHandler,
                                  HytaleLogger logger) {
        this(connections, health, metrics, failureHandler, logger, PersistenceCheckpointHook.NO_OP);
    }

    PersistenceWriteBatchExecutor(SqliteConnectionManager connections,
                                  PersistenceHealthService health,
                                  PersistenceWriteQueueMetrics metrics,
                                  AtomicReference<PersistenceWriteFailureHandler> failureHandler,
                                  HytaleLogger logger,
                                  PersistenceCheckpointHook checkpoints) {
        this.connections = connections;
        this.health = health;
        this.metrics = metrics;
        this.failureHandler = failureHandler;
        this.logger = logger;
        this.checkpoints = checkpoints == null ? PersistenceCheckpointHook.NO_OP : checkpoints;
    }

    void execute(@Nonnull List<PersistenceWriteTask<?>> batch, boolean suppressPublication) {
        if (batch.isEmpty()) return;
        AttemptResult result = attemptWithRetry(batch);
        if (result.committed()) {
            publishAndComplete(batch, suppressPublication);
            return;
        }
        if (result.transientFailure()) {
            failRecoverable(batch, result.failure());
            return;
        }
        if (result.outcome() == PersistenceTransactionOutcome.ROLLED_BACK && batch.size() > 1) {
            isolateConfirmedRollback(batch, suppressPublication);
            return;
        }
        failUnit(batch, result);
    }

    private AttemptResult attemptWithRetry(List<PersistenceWriteTask<?>> tasks) {
        int attempt = 0;
        while (attempt <= MAX_TRANSIENT_RETRIES) {
            attempt++;
            long startedNs = System.nanoTime();
            try {
                runTransaction(tasks);
                metrics.recordBatchSuccess(tasks.size(), Math.max(0L, System.nanoTime() - startedNs));
                return AttemptResult.success();
            } catch (TransactionFailure failure) {
                if (failure.outcome() == PersistenceTransactionOutcome.COMMITTED) {
                    metrics.recordBatchSuccess(tasks.size(), Math.max(0L, System.nanoTime() - startedNs));
                    return AttemptResult.success();
                }
                boolean transientFailure = SqliteBusyFailureClassifier.isTransient(failure.failure());
                if (transientFailure && attempt <= MAX_TRANSIENT_RETRIES) {
                    metrics.recordRetry();
                    sleepQuietly(RETRY_BACKOFF_MS * attempt);
                    continue;
                }
                return AttemptResult.failed(failure.outcome(), failure.failure(), transientFailure);
            }
        }
        return AttemptResult.failed(PersistenceTransactionOutcome.UNKNOWN,
                new IllegalStateException("write_retry_loop_exhausted"), false);
    }

    private void runTransaction(List<PersistenceWriteTask<?>> tasks) throws TransactionFailure {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            boolean commitStarted = false;
            try {
                checkpoints.hit(PersistenceCheckpoint.BEFORE_FIRST_SQL_STATEMENT,
                        tasks.getFirst().metadata());
                for (PersistenceWriteTask<?> task : tasks) {
                    hitBeforeLogicalMutation(task.metadata());
                    task.runWork(connection);
                    checkpoints.hit(PersistenceCheckpoint.AFTER_LOGICAL_SQL_MUTATION, task.metadata());
                }
                checkpoints.hit(PersistenceCheckpoint.BEFORE_COMMIT, tasks.getFirst().metadata());
                commitStarted = true;
                connection.commit();
            } catch (Exception failure) {
                PersistenceTransactionOutcome outcome = rollbackOutcome(connection, commitStarted);
                throw new TransactionFailure(outcome, failure);
            } finally {
                restoreAutoCommit(connection);
            }
        } catch (TransactionFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TransactionFailure(PersistenceTransactionOutcome.NOT_STARTED, failure);
        }
        try {
            checkpoints.hit(PersistenceCheckpoint.AFTER_COMMIT_RETURN, tasks.getFirst().metadata());
            for (PersistenceWriteTask<?> task : tasks) hitAfterLogicalCommit(task.metadata());
        } catch (Exception committedFailure) {
            throw new TransactionFailure(PersistenceTransactionOutcome.COMMITTED, committedFailure);
        }
    }

    private void hitBeforeLogicalMutation(PersistenceOperationMetadata metadata) throws Exception {
        if (metadata.phase() == PersistenceOperationPhase.SOURCE_FINALIZATION) {
            checkpoints.hit(PersistenceCheckpoint.BEFORE_SOURCE_ITEM_FINALIZATION, metadata);
            checkpoints.hit(PersistenceCheckpoint.BEFORE_JOURNAL_TERMINALIZATION, metadata);
        } else if (metadata.phase() == PersistenceOperationPhase.TERMINAL) {
            checkpoints.hit(PersistenceCheckpoint.BEFORE_JOURNAL_TERMINALIZATION, metadata);
        }
    }

    private void hitAfterLogicalCommit(PersistenceOperationMetadata metadata) throws Exception {
        if (metadata.phase() == PersistenceOperationPhase.PREPARED) {
            checkpoints.hit(PersistenceCheckpoint.AFTER_OPERATION_PREPARATION, metadata);
        } else if (metadata.phase() == PersistenceOperationPhase.SOURCE_FINALIZATION) {
            checkpoints.hit(PersistenceCheckpoint.AFTER_SOURCE_ITEM_FINALIZATION, metadata);
        }
    }

    private PersistenceTransactionOutcome rollbackOutcome(Connection connection, boolean commitStarted) {
        try {
            connection.rollback();
            return commitStarted
                    ? PersistenceTransactionOutcome.UNKNOWN
                    : PersistenceTransactionOutcome.ROLLED_BACK;
        } catch (Exception ignored) {
            return PersistenceTransactionOutcome.UNKNOWN;
        }
    }

    private void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (Exception ignored) {
            // The connection is closing; the original outcome remains authoritative.
        }
    }

    private void isolateConfirmedRollback(List<PersistenceWriteTask<?>> batch,
                                          boolean suppressPublication) {
        for (List<PersistenceWriteTask<?>> group : contiguousAtomicGroups(batch)) {
            if (!health.isHealthy()) {
                complete(group, PersistenceWriteQueue.WriteStatus.FAILED,
                        "persistence_read_only_before_isolated_replay", null);
                continue;
            }
            AttemptResult isolated = attemptWithRetry(group);
            if (isolated.committed()) {
                publishAndComplete(group, suppressPublication);
            } else if (isolated.transientFailure()) {
                failRecoverable(group, isolated.failure());
            } else {
                failUnit(group, isolated);
            }
        }
    }

    private List<List<PersistenceWriteTask<?>>> contiguousAtomicGroups(
            List<PersistenceWriteTask<?>> batch) {
        List<List<PersistenceWriteTask<?>>> groups = new ArrayList<>();
        List<PersistenceWriteTask<?>> current = new ArrayList<>();
        String currentId = null;
        for (PersistenceWriteTask<?> task : batch) {
            String groupId = task.metadata().atomicGroupId();
            if (currentId != null && !currentId.equals(groupId)) {
                groups.add(List.copyOf(current));
                current.clear();
            }
            currentId = groupId;
            current.add(task);
        }
        if (!current.isEmpty()) groups.add(List.copyOf(current));
        return List.copyOf(groups);
    }

    private void failUnit(List<PersistenceWriteTask<?>> tasks, AttemptResult result) {
        String reason = reason(tasks, result.failure());
        metrics.recordBatchFailure(reason);
        if (requiresGlobalReadOnly(result)) {
            notifyUnknown(tasks, result.failure());
            health.markDegraded(reason);
            recordStorageFailure(reason, result.failure());
        } else {
            for (PersistenceWriteTask<?> task : tasks) {
                notifyRolledBack(task.metadata(), result.failure());
            }
        }
        complete(tasks, PersistenceWriteQueue.WriteStatus.FAILED, reason, result.failure());
    }

    private boolean requiresGlobalReadOnly(AttemptResult result) {
        if (result.outcome() == PersistenceTransactionOutcome.UNKNOWN
                || result.outcome() == PersistenceTransactionOutcome.NOT_STARTED) return true;
        return isStorageFailure(result.failure());
    }

    private boolean isStorageFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (!(current instanceof SQLException)) continue;
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (message.contains("constraint") || message.contains("unique")) return false;
            return true;
        }
        return false;
    }

    private void failRecoverable(List<PersistenceWriteTask<?>> tasks, Throwable failure) {
        String reason = reason(tasks, failure);
        metrics.recordBatchFailure(reason);
        recordRecoverableFailure(reason, failure);
        for (PersistenceWriteTask<?> task : tasks) notifyRolledBack(task.metadata(), failure);
        complete(tasks, PersistenceWriteQueue.WriteStatus.FAILED, reason, failure);
    }

    private void publishAndComplete(List<PersistenceWriteTask<?>> tasks, boolean suppressPublication) {
        if (!suppressPublication) {
            for (PersistenceWriteTask<?> task : tasks) runAfterCommit(task);
        }
        complete(tasks, PersistenceWriteQueue.WriteStatus.COMMITTED, null, null);
    }

    private void runAfterCommit(PersistenceWriteTask<?> task) {
        try {
            checkpoints.hit(PersistenceCheckpoint.BEFORE_RUNTIME_INDEX_PUBLICATION, task.metadata());
            task.runAfterCommit();
            checkpoints.hit(PersistenceCheckpoint.AFTER_RUNTIME_INDEX_PUBLICATION, task.metadata());
        } catch (Exception failure) {
            notifyPublication(task.metadata(), failure);
            if (logger != null) {
                logger.at(Level.SEVERE).log("SQLite after-commit publication failed ("
                        + task.operationName() + "): " + failure.getMessage());
            }
        }
    }

    private void complete(List<PersistenceWriteTask<?>> tasks,
                          PersistenceWriteQueue.WriteStatus status,
                          String reason, Throwable failure) {
        if (status != PersistenceWriteQueue.WriteStatus.COMMITTED) {
            metrics.recordAcceptedFailures(tasks.size());
        }
        for (PersistenceWriteTask<?> task : tasks) task.complete(status, reason, failure);
    }

    private void notifyRolledBack(PersistenceOperationMetadata metadata, Throwable failure) {
        safely(() -> failureHandler.get().rolledBack(metadata, failure));
    }

    private void notifyUnknown(List<PersistenceWriteTask<?>> tasks, Throwable failure) {
        List<PersistenceOperationMetadata> metadata = tasks.stream()
                .map(PersistenceWriteTask::metadata).toList();
        safely(() -> failureHandler.get().commitOutcomeUnknown(metadata, failure));
    }

    private void notifyPublication(PersistenceOperationMetadata metadata, Throwable failure) {
        safely(() -> failureHandler.get().publicationFailed(metadata, failure));
    }

    private void safely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Failure classification/reporting cannot destabilize the writer.
        }
    }

    private String reason(List<PersistenceWriteTask<?>> tasks, Throwable failure) {
        return "sqlite_write_failed:" + tasks.getFirst().operationName()
                + ":" + failure.getClass().getSimpleName();
    }

    private void recordStorageFailure(String reason, Throwable failure) {
        try {
            checkpoints.hit(PersistenceCheckpoint.BEFORE_TELEMETRY_DIAGNOSTICS_EMISSION, null);
        } catch (Exception diagnosticsFailure) {
            return;
        }
        TameworkTelemetryEvents.recordErrorIfAvailable(
                "persistence_write_failed", failure,
                TameworkTelemetryContext.persistence(
                        "write_queue", "write_batch", reason, "SQLite write failed.").build());
        if (logger != null) {
            logger.at(Level.SEVERE).log("SQLite write failed (" + reason + "): " + failure.getMessage());
        }
    }

    private void recordRecoverableFailure(String reason, Throwable failure) {
        long now = System.currentTimeMillis();
        if (lastRecoverableFailureReportAtMs != 0L
                && now >= lastRecoverableFailureReportAtMs
                && now - lastRecoverableFailureReportAtMs < RECOVERABLE_FAILURE_REPORT_INTERVAL_MS) return;
        lastRecoverableFailureReportAtMs = now;
        if (logger != null) {
            logger.at(Level.WARNING).log("SQLite write remained busy after retries; affected work "
                    + "was retained as a terminal retry result (" + reason + "): " + failure.getMessage());
        }
    }

    private void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(Math.max(0L, delayMs));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class TransactionFailure extends Exception {
        private final PersistenceTransactionOutcome outcome;
        private final Exception failure;

        private TransactionFailure(PersistenceTransactionOutcome outcome, Exception failure) {
            super(failure);
            this.outcome = outcome;
            this.failure = failure;
        }

        private PersistenceTransactionOutcome outcome() {
            return outcome;
        }

        private Exception failure() {
            return failure;
        }
    }

    private record AttemptResult(boolean committed,
                                 @Nonnull PersistenceTransactionOutcome outcome,
                                 @Nullable Exception failure,
                                 boolean transientFailure) {
        static AttemptResult success() {
            return new AttemptResult(true, PersistenceTransactionOutcome.COMMITTED, null, false);
        }

        static AttemptResult failed(PersistenceTransactionOutcome outcome,
                                    Exception failure, boolean transientFailure) {
            return new AttemptResult(false, outcome, failure, transientFailure);
        }
    }
}
