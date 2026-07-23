package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceShutdownResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

/**
 * Bounded, lane-isolated executor for typed replacement persistence reads.
 *
 * <p>It never converts saturation, connection, query, or decode failure into absence. Shutdown
 * stops admission and drains accepted reads without using {@code shutdownNow()}.</p>
 */
public final class SqliteReadExecutor implements AutoCloseable {
    private final SqliteConnectionFactory connections;
    private final SqliteReadExecutorConfiguration configuration;
    private final ThreadPoolExecutor gameplay;
    private final ThreadPoolExecutor diagnostic;
    private final AtomicBoolean admissionOpen = new AtomicBoolean(true);
    private final AtomicInteger outstanding = new AtomicInteger();

    public SqliteReadExecutor(@Nonnull SqliteConnectionFactory connections) {
        this(connections, SqliteReadExecutorConfiguration.DEFAULT);
    }

    public SqliteReadExecutor(@Nonnull SqliteConnectionFactory connections,
                              @Nonnull SqliteReadExecutorConfiguration configuration) {
        if (connections == null || configuration == null) {
            throw new IllegalArgumentException("SQLite read executor dependencies are required");
        }
        this.connections = connections;
        this.configuration = configuration;
        this.gameplay = executor(
                configuration.gameplayThreads(),
                configuration.gameplayQueueCapacity(),
                "tamework-replacement-read-gameplay"
        );
        this.diagnostic = executor(
                configuration.diagnosticThreads(),
                configuration.diagnosticQueueCapacity(),
                "tamework-replacement-read-diagnostic"
        );
    }

    /** Executes one typed read on its declared isolated lane. */
    @Nonnull
    public <T> CompletionStage<PersistenceReadResult<T>> execute(
            @Nonnull SqliteReadCommand<T> command
    ) {
        if (command == null) {
            throw new IllegalArgumentException("SQLite read command is required");
        }
        if (!admissionOpen.get()) {
            return completedFailure(command, "read_executor_closed");
        }

        CompletableFuture<PersistenceReadResult<T>> completion = new CompletableFuture<>();
        outstanding.incrementAndGet();
        try {
            lane(command.priority()).execute(() -> run(command, completion));
        } catch (RejectedExecutionException rejected) {
            outstanding.decrementAndGet();
            completion.complete(failure(
                    command,
                    "read_executor_saturated",
                    StorageFailureKind.UNAVAILABLE,
                    true,
                    rejected
            ));
        }
        return completion;
    }

    /** Stops admission and waits for all accepted reads up to the supplied duration. */
    @Nonnull
    public PersistenceShutdownResult shutdown(@Nonnull Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("Read shutdown timeout is required and non-negative");
        }
        if (!admissionOpen.getAndSet(false) && gameplay.isTerminated() && diagnostic.isTerminated()) {
            return new PersistenceShutdownResult(PersistenceShutdownResult.Status.ALREADY_CLOSED, 0);
        }
        gameplay.shutdown();
        diagnostic.shutdown();
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean gameplayDone = await(gameplay, deadline);
        boolean diagnosticDone = await(diagnostic, deadline);
        if (gameplayDone && diagnosticDone) {
            return new PersistenceShutdownResult(PersistenceShutdownResult.Status.DRAINED, 0);
        }
        return new PersistenceShutdownResult(
                PersistenceShutdownResult.Status.TIMED_OUT,
                outstanding.get()
        );
    }

    /** Returns the number of accepted reads not yet completed. */
    public int outstandingReads() {
        return outstanding.get();
    }

    @Override
    public void close() {
        shutdown(Duration.ofMillis(configuration.defaultShutdownTimeoutMs()));
    }

    private <T> void run(SqliteReadCommand<T> command,
                         CompletableFuture<PersistenceReadResult<T>> completion) {
        try (Connection connection = connections.openReadConnection()) {
            PersistenceReadResult<T> result = command.work().execute(connection);
            completion.complete(result == null
                    ? failure(command, "read_contract_returned_null",
                    StorageFailureKind.DECODE, false, null)
                    : result);
        } catch (Throwable failure) {
            completion.complete(PersistenceReadResult.failed(
                    SqliteFailureClassifier.classify(failure, command.kind().value())
            ));
        } finally {
            outstanding.decrementAndGet();
        }
    }

    private ThreadPoolExecutor lane(PersistenceReadPriority priority) {
        return priority == PersistenceReadPriority.GAMEPLAY_CRITICAL ? gameplay : diagnostic;
    }

    private <T> CompletionStage<PersistenceReadResult<T>> completedFailure(
            SqliteReadCommand<T> command,
            String code
    ) {
        return CompletableFuture.completedFuture(
                failure(command, code, StorageFailureKind.UNAVAILABLE, false, null)
        );
    }

    private <T> PersistenceReadResult<T> failure(SqliteReadCommand<T> command,
                                                 String code,
                                                 StorageFailureKind kind,
                                                 boolean retryable,
                                                 Throwable cause) {
        return PersistenceReadResult.failed(new StorageFailure(
                kind,
                code,
                command.kind().value(),
                retryable,
                cause
        ));
    }

    private boolean await(ThreadPoolExecutor executor, long deadlineNs) {
        long remaining = deadlineNs - System.nanoTime();
        if (remaining <= 0) {
            return executor.isTerminated();
        }
        try {
            return executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }

    private ThreadPoolExecutor executor(int threads, int capacity, String namePrefix) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                threads,
                threads,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
