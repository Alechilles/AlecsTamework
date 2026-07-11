package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Holds one accepted write operation and its exactly-once terminal completion. */
final class PersistenceWriteTask<T> {
    private final String operationName;
    private final PersistenceWriteQueue.SqlWork<T> work;
    @Nullable
    private final Consumer<T> afterCommit;
    private final CompletableFuture<PersistenceWriteQueue.WriteOutcome<T>> completion = new CompletableFuture<>();
    @Nullable
    private T result;

    PersistenceWriteTask(@Nonnull String operationName,
                         @Nonnull PersistenceWriteQueue.SqlWork<T> work,
                         @Nullable Consumer<T> afterCommit) {
        this.operationName = operationName;
        this.work = work;
        this.afterCommit = afterCommit;
    }

    @Nonnull
    String operationName() {
        return operationName;
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
