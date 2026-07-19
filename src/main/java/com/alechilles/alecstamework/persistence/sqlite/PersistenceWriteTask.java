package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.operation.PersistenceOperationMetadata;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Holds one accepted write operation and its exactly-once terminal completion. */
final class PersistenceWriteTask<T> {
    private final PersistenceOperationMetadata metadata;
    private final PersistenceWriteQueue.SqlWork<T> work;
    @Nullable
    private final Consumer<T> afterCommit;
    private final CompletableFuture<PersistenceWriteQueue.WriteOutcome<T>> completion = new CompletableFuture<>();
    @Nullable
    private T result;

    PersistenceWriteTask(@Nonnull PersistenceOperationMetadata metadata,
                         @Nonnull PersistenceWriteQueue.SqlWork<T> work,
                         @Nullable Consumer<T> afterCommit) {
        this.metadata = metadata;
        this.work = work;
        this.afterCommit = afterCommit;
    }

    @Nonnull
    String operationName() {
        return metadata.taskName();
    }

    @Nonnull
    PersistenceOperationMetadata metadata() {
        return metadata;
    }

    @Nonnull
    CompletableFuture<PersistenceWriteQueue.WriteOutcome<T>> completion() {
        return completion;
    }

    void runWork(@Nonnull Connection connection) throws Exception {
        result = work.run(connection);
    }

    void runAfterCommit() {
        if (afterCommit != null) {
            afterCommit.accept(result);
        }
    }

    void complete(@Nonnull PersistenceWriteQueue.WriteStatus status,
                  @Nullable String reason,
                  @Nullable Throwable failure) {
        completion.complete(new PersistenceWriteQueue.WriteOutcome<>(status, result, reason, failure));
    }
}
