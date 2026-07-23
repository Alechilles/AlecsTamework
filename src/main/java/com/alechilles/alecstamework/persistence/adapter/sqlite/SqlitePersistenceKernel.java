package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceCancellation;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceShutdownResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

/**
 * Replacement SQLite kernel composition with ordered writer, checkpoint, and read shutdown.
 *
 * <p>If writer drain times out, read lanes remain open for accepted operations' exact commit
 * readbacks. A later shutdown call resumes at the same safe boundary.</p>
 */
public final class SqlitePersistenceKernel implements AutoCloseable {
    private final SqliteSingleWriter writer;
    private final SqliteReadExecutor reads;
    private final SqliteUnitOfWorkRunner units;
    private final SqliteCheckpointService checkpoints;
    private final PersistenceKernelMetrics metrics;
    private final long defaultShutdownTimeoutMs;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);

    public SqlitePersistenceKernel(@Nonnull SqliteConnectionFactory connections) {
        this(
                connections,
                SqliteWriterConfiguration.DEFAULT,
                SqliteReadExecutorConfiguration.DEFAULT,
                PersistenceKernelMetrics.NO_OP,
                10_000
        );
    }

    public SqlitePersistenceKernel(
            @Nonnull SqliteConnectionFactory connections,
            @Nonnull PersistenceKernelMetrics metrics
    ) {
        this(
                connections,
                SqliteWriterConfiguration.DEFAULT,
                SqliteReadExecutorConfiguration.DEFAULT,
                metrics,
                10_000
        );
    }

    public SqlitePersistenceKernel(@Nonnull SqliteConnectionFactory connections,
                                   @Nonnull SqliteWriterConfiguration writerConfiguration,
                                   @Nonnull SqliteReadExecutorConfiguration readConfiguration,
                                   @Nonnull PersistenceKernelMetrics metrics,
                                   long defaultShutdownTimeoutMs) {
        if (connections == null || writerConfiguration == null || readConfiguration == null
                || metrics == null || defaultShutdownTimeoutMs < 1) {
            throw new IllegalArgumentException("Complete SQLite kernel configuration is required");
        }
        this.writer = new SqliteSingleWriter(connections, writerConfiguration, metrics);
        this.reads = new SqliteReadExecutor(
                connections, readConfiguration, metrics
        );
        this.units = new SqliteUnitOfWorkRunner(writer, reads, metrics);
        this.checkpoints = new SqliteCheckpointService(connections);
        this.metrics = metrics;
        this.defaultShutdownTimeoutMs = defaultShutdownTimeoutMs;
    }

    /** Executes one complete unit of work through the kernel boundary. */
    @Nonnull
    public <T> SqliteUnitOfWorkRunner.Submission<T> execute(
            @Nonnull SqliteUnitOfWork<T> unitOfWork,
            @Nonnull PersistenceCancellation cancellation
    ) {
        return units.execute(unitOfWork, cancellation);
    }

    /** Executes one complete unit of work without a cancellation signal. */
    @Nonnull
    public <T> SqliteUnitOfWorkRunner.Submission<T> execute(
            @Nonnull SqliteUnitOfWork<T> unitOfWork
    ) {
        return units.execute(unitOfWork);
    }

    /** Executes one typed read through its isolated lane. */
    @Nonnull
    public <T> CompletionStage<PersistenceReadResult<T>> read(@Nonnull SqliteReadCommand<T> command) {
        return reads.execute(command);
    }

    /** Returns the current coordinated lifecycle state. */
    public State state() {
        return state.get();
    }

    SqliteReadExecutor reads() {
        return reads;
    }

    SqliteUnitOfWorkRunner units() {
        return units;
    }

    /** Stops admission and closes components in writer, checkpoint, then read order. */
    @Nonnull
    public synchronized SqliteKernelShutdownReport shutdown(@Nonnull Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("Kernel shutdown timeout is required and non-negative");
        }
        state.compareAndSet(State.OPEN, State.DRAINING);
        long deadline = System.nanoTime() + timeout.toNanos();
        PersistenceShutdownResult writerResult = writer.shutdown(remaining(deadline));
        if (writerResult.status() == PersistenceShutdownResult.Status.TIMED_OUT) {
            return new SqliteKernelShutdownReport(
                    writerResult,
                    new SqliteKernelShutdownReport.CheckpointOutcome(
                            SqliteKernelShutdownReport.CheckpointStatus.SKIPPED_WRITER_ACTIVE,
                            null
                    ),
                    new PersistenceShutdownResult(
                            PersistenceShutdownResult.Status.DEFERRED,
                            reads.outstandingReads()
                    )
            );
        }

        SqliteCheckpointResult checkpointResult = checkpoints.checkpoint();
        SqliteKernelShutdownReport.CheckpointStatus checkpointStatus =
                checkpointResult instanceof SqliteCheckpointResult.Completed
                        ? SqliteKernelShutdownReport.CheckpointStatus.COMPLETED
                        : SqliteKernelShutdownReport.CheckpointStatus.FAILED;
        if (checkpointResult instanceof
                SqliteCheckpointResult.Failed failed) {
            recordCheckpointFailure(failed.failure());
        }
        PersistenceShutdownResult readResult = reads.shutdown(remaining(deadline));
        if (readResult.status()
                == PersistenceShutdownResult.Status.TIMED_OUT) {
            recordShutdownTimedOut(readResult.outstandingOperations());
        }
        if (readResult.status() != PersistenceShutdownResult.Status.TIMED_OUT) {
            state.set(State.CLOSED);
        }
        return new SqliteKernelShutdownReport(
                writerResult,
                new SqliteKernelShutdownReport.CheckpointOutcome(
                        checkpointStatus,
                        checkpointResult
                ),
                readResult
        );
    }

    @Override
    public void close() {
        shutdown(Duration.ofMillis(defaultShutdownTimeoutMs));
    }

    private Duration remaining(long deadlineNs) {
        return Duration.ofNanos(Math.max(0, deadlineNs - System.nanoTime()));
    }

    private void recordCheckpointFailure(StorageFailure failure) {
        Throwable cause = failure.cause() == null
                ? new IllegalStateException(failure.code())
                : failure.cause();
        try {
            metrics.checkpointFailure(
                    "wal_checkpoint",
                    cause
            );
        } catch (RuntimeException ignored) {
            // Passive metrics cannot change shutdown checkpoint semantics.
        }
    }

    private void recordShutdownTimedOut(int outstanding) {
        try {
            metrics.shutdownTimedOut(outstanding);
        } catch (RuntimeException ignored) {
            // Passive metrics cannot change shutdown ownership.
        }
    }

    public enum State {
        OPEN,
        DRAINING,
        CLOSED
    }
}
