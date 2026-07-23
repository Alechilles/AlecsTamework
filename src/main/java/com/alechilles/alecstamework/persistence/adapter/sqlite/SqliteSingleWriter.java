package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceCancellation;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpointHook;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.PersistenceShutdownResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceWriteRejection;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

/**
 * Bounded replacement writer that executes exactly one logical operation per transaction.
 *
 * <p>Accepted operations are never cancelled by a later caller signal. Busy retry is limited to
 * commands explicitly safe to replay after a known rollback, and unknown commit outcomes are
 * returned immediately for exact domain readback.</p>
 */
public final class SqliteSingleWriter implements AutoCloseable {
    private static final long POLL_INTERVAL_MS = 10;

    private final SqliteConnectionFactory connections;
    private final SqliteWriterConfiguration configuration;
    private final PersistenceCheckpointHook checkpoints;
    private final PersistenceKernelMetrics metrics;
    private final ArrayBlockingQueue<Task<?>> queue;
    private final Object lifecycleLock = new Object();
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final AtomicInteger outstanding = new AtomicInteger();
    private final AtomicReference<Task<?>> active = new AtomicReference<>();
    private final Thread worker;

    public SqliteSingleWriter(@Nonnull SqliteConnectionFactory connections) {
        this(connections, SqliteWriterConfiguration.DEFAULT,
                PersistenceCheckpointHook.NO_OP, PersistenceKernelMetrics.NO_OP);
    }

    public SqliteSingleWriter(@Nonnull SqliteConnectionFactory connections,
                              @Nonnull SqliteWriterConfiguration configuration,
                              @Nonnull PersistenceKernelMetrics metrics) {
        this(connections, configuration, PersistenceCheckpointHook.NO_OP, metrics);
    }

    /** Test-only boundary injection constructor; runtime composition uses the hook-free overload. */
    public SqliteSingleWriter(@Nonnull SqliteConnectionFactory connections,
                              @Nonnull SqliteWriterConfiguration configuration,
                              @Nonnull PersistenceCheckpointHook checkpoints,
                              @Nonnull PersistenceKernelMetrics metrics) {
        if (connections == null || configuration == null || checkpoints == null || metrics == null) {
            throw new IllegalArgumentException("Complete SQLite writer dependencies are required");
        }
        this.connections = connections;
        this.configuration = configuration;
        this.checkpoints = checkpoints;
        this.metrics = metrics;
        this.queue = new ArrayBlockingQueue<>(configuration.queueCapacity());
        this.worker = new Thread(this::workerLoop, "tamework-replacement-persistence-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** Attempts to accept one complete operation for serialized execution. */
    @Nonnull
    public <T> WriteSubmission<T> submit(@Nonnull SqliteTransactionCommand<T> command,
                                         @Nonnull PersistenceCancellation cancellation) {
        if (command == null || cancellation == null) {
            throw new IllegalArgumentException("Transaction command and cancellation are required");
        }
        if (cancellation.isCancelled()) {
            return rejected(command, PersistenceWriteRejection.CANCELLED_BEFORE_ACCEPTANCE);
        }
        synchronized (lifecycleLock) {
            if (cancellation.isCancelled()) {
                return rejected(command, PersistenceWriteRejection.CANCELLED_BEFORE_ACCEPTANCE);
            }
            State current = state.get();
            if (current != State.OPEN) {
                return rejected(command, current == State.DRAINING
                        ? PersistenceWriteRejection.DRAINING
                        : PersistenceWriteRejection.CLOSED);
            }
            Task<T> task = new Task<>(command);
            if (!queue.offer(task)) {
                return rejected(command, PersistenceWriteRejection.SATURATED);
            }
            outstanding.incrementAndGet();
            recordWriteAccepted(command);
            return new WriteSubmission<>(WriteAcceptance.ACCEPTED, task.completion);
        }
    }

    /** Submits without a cancellation signal. */
    @Nonnull
    public <T> WriteSubmission<T> submit(@Nonnull SqliteTransactionCommand<T> command) {
        return submit(command, PersistenceCancellation.NONE);
    }

    /** Returns the current admission/drain state. */
    @Nonnull
    public State state() {
        return state.get();
    }

    /** Returns the number of accepted operations not yet completed. */
    public int outstandingOperations() {
        return outstanding.get();
    }

    /** Stops admission and waits up to the supplied duration for accepted operations to drain. */
    @Nonnull
    public PersistenceShutdownResult shutdown(@Nonnull Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("Writer shutdown timeout is required and non-negative");
        }
        synchronized (lifecycleLock) {
            if (state.get() == State.CLOSED) {
                return new PersistenceShutdownResult(PersistenceShutdownResult.Status.ALREADY_CLOSED, 0);
            }
            state.compareAndSet(State.OPEN, State.DRAINING);
        }
        hitCloseCheckpoint();
        joinWorker(timeout.toMillis());
        if (!worker.isAlive()) {
            return new PersistenceShutdownResult(PersistenceShutdownResult.Status.DRAINED, 0);
        }
        int remaining = outstanding.get();
        recordShutdownTimedOut(remaining);
        return new PersistenceShutdownResult(PersistenceShutdownResult.Status.TIMED_OUT, remaining);
    }

    @Override
    public void close() {
        shutdown(Duration.ofMillis(configuration.defaultShutdownTimeoutMs()));
    }

    private void workerLoop() {
        try {
            while (true) {
                if (state.get() == State.DRAINING && queue.isEmpty() && active.get() == null) {
                    break;
                }
                Task<?> task = queue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (task != null) {
                    executeTask(task);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            state.set(State.CLOSED);
        }
    }

    private <T> void executeTask(Task<T> task) {
        active.set(task);
        PersistenceTransactionResult<T> result;
        try {
            result = executeWithBusyRetry(task.command);
        } catch (Throwable failure) {
            result = new PersistenceTransactionResult.Unknown<>(
                    SqliteFailureClassifier.classify(failure, task.command.kind().value())
            );
        }
        try {
            task.completion.complete(result);
            recordWriteCompleted(task.command, result);
        } finally {
            active.set(null);
            outstanding.decrementAndGet();
        }
    }

    private <T> PersistenceTransactionResult<T> executeWithBusyRetry(
            SqliteTransactionCommand<T> command
    ) {
        int retries = 0;
        while (true) {
            PersistenceTransactionResult<T> result = executeOnce(command);
            if (!canRetryBusy(command, result) || retries >= configuration.maxBusyRetries()) {
                return result;
            }
            retries++;
            recordBusyRetry(command, retries);
            if (!delayBeforeRetry()) {
                return result;
            }
        }
    }

    private <T> PersistenceTransactionResult<T> executeOnce(SqliteTransactionCommand<T> command) {
        try {
            checkpoints.hit(PersistenceCheckpoint.BEFORE_BEGIN, command.operationId());
        } catch (Exception failure) {
            return rolledBack(command, failure);
        }

        Connection connection;
        try {
            connection = connections.openWriterConnection();
        } catch (Exception openFailure) {
            return rolledBack(command, openFailure);
        }
        try {
            connection.setAutoCommit(false);
            try {
                checkpoints.hit(PersistenceCheckpoint.AFTER_BEGIN, command.operationId());
                T value = command.work().execute(connection);
                checkpoints.hit(PersistenceCheckpoint.BEFORE_COMMIT, command.operationId());
                try {
                    connection.commit();
                    checkpoints.hit(PersistenceCheckpoint.COMMIT_RETURNED, command.operationId());
                } catch (Exception commitFailure) {
                    return unknown(command, commitFailure);
                }
                try {
                    checkpoints.hit(PersistenceCheckpoint.AFTER_COMMIT, command.operationId());
                } catch (Exception observationFailure) {
                    recordCheckpointFailure(PersistenceCheckpoint.AFTER_COMMIT, observationFailure);
                }
                return new PersistenceTransactionResult.Committed<>(value);
            } catch (Exception beforeCommitFailure) {
                return rollback(connection, command, beforeCommitFailure);
            }
        } catch (Exception beginFailure) {
            return rolledBack(command, beginFailure);
        } finally {
            closeConnection(connection);
        }
    }

    private <T> PersistenceTransactionResult<T> rollback(Connection connection,
                                                         SqliteTransactionCommand<T> command,
                                                         Exception originalFailure) {
        try {
            connection.rollback();
            return rolledBack(command, originalFailure);
        } catch (SQLException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
            return unknown(command, originalFailure);
        }
    }

    private <T> PersistenceTransactionResult<T> rolledBack(SqliteTransactionCommand<T> command,
                                                           Throwable failure) {
        return new PersistenceTransactionResult.RolledBack<>(
                SqliteFailureClassifier.classify(failure, command.kind().value())
        );
    }

    private <T> PersistenceTransactionResult<T> unknown(SqliteTransactionCommand<T> command,
                                                        Throwable failure) {
        StorageFailure classified = SqliteFailureClassifier.classify(failure, command.kind().value());
        StorageFailure unknown = new StorageFailure(
                classified.kind() == StorageFailureKind.BUSY
                        ? StorageFailureKind.BUSY
                        : StorageFailureKind.UNKNOWN,
                "sqlite_commit_outcome_unknown",
                classified.operation(),
                false,
                failure
        );
        return new PersistenceTransactionResult.Unknown<>(unknown);
    }

    private boolean canRetryBusy(SqliteTransactionCommand<?> command,
                                 PersistenceTransactionResult<?> result) {
        return command.replayPolicy() == TransactionReplayPolicy.SAFE_DATABASE_ONLY
                && result instanceof PersistenceTransactionResult.RolledBack<?> rolledBack
                && rolledBack.failure().kind() == StorageFailureKind.BUSY
                && rolledBack.failure().retryable();
    }

    private boolean delayBeforeRetry() {
        try {
            Thread.sleep(configuration.busyRetryDelayMs());
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private <T> WriteSubmission<T> rejected(SqliteTransactionCommand<T> command,
                                            PersistenceWriteRejection reason) {
        recordWriteRejected(command, reason);
        return new WriteSubmission<>(
                reason == PersistenceWriteRejection.CANCELLED_BEFORE_ACCEPTANCE
                        ? WriteAcceptance.CANCELLED_BEFORE_ACCEPTANCE
                        : WriteAcceptance.REJECTED,
                CompletableFuture.completedFuture(new PersistenceTransactionResult.Rejected<>(reason))
        );
    }

    private void hitCloseCheckpoint() {
        try {
            checkpoints.hit(PersistenceCheckpoint.CLOSE, null);
        } catch (Exception failure) {
            recordCheckpointFailure(PersistenceCheckpoint.CLOSE, failure);
        }
    }

    private void closeConnection(Connection connection) {
        try {
            connection.close();
        } catch (SQLException failure) {
            recordCheckpointFailure(PersistenceCheckpoint.CLOSE, failure);
        }
    }

    private void recordWriteAccepted(SqliteTransactionCommand<?> command) {
        try {
            metrics.writeAccepted(command.operationId());
        } catch (RuntimeException ignored) {
            // Passive metrics cannot change admission ownership.
        }
    }

    private void recordWriteRejected(SqliteTransactionCommand<?> command,
                                     PersistenceWriteRejection reason) {
        try {
            metrics.writeRejected(command.operationId(), reason);
        } catch (RuntimeException ignored) {
            // Passive metrics cannot change a rejection outcome.
        }
    }

    private void recordBusyRetry(SqliteTransactionCommand<?> command, int retryNumber) {
        try {
            metrics.busyRetry(command.operationId(), retryNumber);
        } catch (RuntimeException ignored) {
            // Passive metrics cannot change transaction control flow.
        }
    }

    private void recordWriteCompleted(SqliteTransactionCommand<?> command,
                                      PersistenceTransactionResult<?> result) {
        try {
            metrics.writeCompleted(command.operationId(), result);
        } catch (RuntimeException ignored) {
            // Passive metrics cannot change a durable outcome.
        }
    }

    private void recordCheckpointFailure(PersistenceCheckpoint checkpoint, Throwable failure) {
        try {
            metrics.checkpointFailure(checkpoint, failure);
        } catch (RuntimeException ignored) {
            // Passive metrics cannot change checkpoint semantics.
        }
    }

    private void recordShutdownTimedOut(int remaining) {
        try {
            metrics.shutdownTimedOut(remaining);
        } catch (RuntimeException ignored) {
            // Passive metrics cannot change shutdown ownership.
        }
    }

    private void joinWorker(long timeoutMs) {
        if (Thread.currentThread() == worker || timeoutMs <= 0) {
            return;
        }
        try {
            worker.join(timeoutMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public enum State {
        OPEN,
        DRAINING,
        CLOSED
    }

    public enum WriteAcceptance {
        ACCEPTED,
        CANCELLED_BEFORE_ACCEPTANCE,
        REJECTED
    }

    /** Acceptance and immutable completion handle for one submitted operation. */
    public record WriteSubmission<T>(@Nonnull WriteAcceptance acceptance,
                                     @Nonnull CompletionStage<PersistenceTransactionResult<T>> completion) {
        public WriteSubmission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException("Write submission requires acceptance and completion");
            }
        }
    }

    private static final class Task<T> {
        private final SqliteTransactionCommand<T> command;
        private final CompletableFuture<PersistenceTransactionResult<T>> completion =
                new CompletableFuture<>();

        private Task(SqliteTransactionCommand<T> command) {
            this.command = command;
        }
    }
}
