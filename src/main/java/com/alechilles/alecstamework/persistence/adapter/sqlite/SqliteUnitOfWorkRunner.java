package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceCancellation;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Executes a complete replacement unit of work and resolves commit ambiguity exactly once.
 *
 * <p>It never retries an unknown transaction. The correlated readback must first prove committed
 * or absent; a failed readback preserves the unknown outcome for containment.</p>
 */
public final class SqliteUnitOfWorkRunner {
    private final SqliteSingleWriter writer;
    private final SqliteReadExecutor reads;

    public SqliteUnitOfWorkRunner(@Nonnull SqliteSingleWriter writer,
                                  @Nonnull SqliteReadExecutor reads) {
        if (writer == null || reads == null) {
            throw new IllegalArgumentException("Unit-of-work writer and read executor are required");
        }
        this.writer = writer;
        this.reads = reads;
    }

    /** Submits one unit of work and exposes writer acceptance plus its readback-resolved outcome. */
    @Nonnull
    public <T> Submission<T> execute(@Nonnull SqliteUnitOfWork<T> unitOfWork,
                                     @Nonnull PersistenceCancellation cancellation) {
        if (unitOfWork == null || cancellation == null) {
            throw new IllegalArgumentException("Unit of work and cancellation are required");
        }
        SqliteSingleWriter.WriteSubmission<T> write =
                writer.submit(unitOfWork.transaction(), cancellation);
        CompletionStage<PersistenceTransactionResult<T>> resolved =
                write.completion().thenCompose(result -> resolveUnknown(unitOfWork, result));
        return new Submission<>(write.acceptance(), resolved);
    }

    /** Executes without a cancellation signal. */
    @Nonnull
    public <T> Submission<T> execute(@Nonnull SqliteUnitOfWork<T> unitOfWork) {
        return execute(unitOfWork, PersistenceCancellation.NONE);
    }

    private <T> CompletionStage<PersistenceTransactionResult<T>> resolveUnknown(
            SqliteUnitOfWork<T> unitOfWork,
            PersistenceTransactionResult<T> transactionResult
    ) {
        if (!(transactionResult instanceof PersistenceTransactionResult.Unknown<T> unknown)) {
            return java.util.concurrent.CompletableFuture.completedFuture(transactionResult);
        }
        SqliteReadCommand<T> readback = new SqliteReadCommand<>(
                unitOfWork.readbackKind(),
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                unitOfWork.unknownCommitReadback()
        );
        return reads.execute(readback).thenApply(result -> mapReadback(unitOfWork, unknown, result));
    }

    private <T> PersistenceTransactionResult<T> mapReadback(
            SqliteUnitOfWork<T> unitOfWork,
            PersistenceTransactionResult.Unknown<T> unknown,
            PersistenceReadResult<T> readback
    ) {
        if (readback instanceof PersistenceReadResult.Found<T> found) {
            return new PersistenceTransactionResult.Committed<>(found.value());
        }
        if (readback instanceof PersistenceReadResult.Absent<T>) {
            return new PersistenceTransactionResult.RolledBack<>(new StorageFailure(
                    StorageFailureKind.UNKNOWN,
                    "unknown_commit_proven_absent",
                    unitOfWork.transaction().kind().value(),
                    false,
                    unknown.failure().cause()
            ));
        }
        PersistenceReadResult.Failed<T> failed = (PersistenceReadResult.Failed<T>) readback;
        return new PersistenceTransactionResult.Unknown<>(new StorageFailure(
                failed.failure().kind(),
                "unknown_commit_readback_failed",
                unitOfWork.transaction().kind().value(),
                false,
                failed.failure().cause()
        ));
    }

    /** Writer acceptance and exact final outcome of one unit of work. */
    public record Submission<T>(@Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
                                @Nonnull CompletionStage<PersistenceTransactionResult<T>> completion) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException("Unit-of-work submission is incomplete");
            }
        }
    }
}
