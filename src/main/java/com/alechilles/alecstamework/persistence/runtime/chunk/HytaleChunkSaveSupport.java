package com.alechilles.alecstamework.persistence.runtime.chunk;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Shared asynchronous save-then-flush mapping for exact Hytale chunk receipts. */
public final class HytaleChunkSaveSupport {
    private HytaleChunkSaveSupport() {
    }

    /** Maps both the save and durable flush into one typed result. */
    @Nonnull
    public static CompletionStage<Outcome> saveAndFlush(
            @Nullable CompletionStage<Void> save,
            @Nullable ChunkFlusher flusher,
            @Nullable Executor ioExecutor
    ) {
        if (save == null || flusher == null || ioExecutor == null) {
            return CompletableFuture.completedFuture(
                    Outcome.retryable(null)
            );
        }
        CompletableFuture<Outcome> completion = new CompletableFuture<>();
        save.whenComplete((ignored, failure) -> {
            if (failure != null) {
                completion.complete(Outcome.retryable(failure));
                return;
            }
            CompletableFuture.runAsync(() -> {
                try {
                    flusher.flush();
                } catch (Exception | LinkageError flushFailure) {
                    throw new CompletionException(flushFailure);
                }
            }, ioExecutor).whenComplete((unused, flushFailure) ->
                    completion.complete(flushFailure == null
                            ? Outcome.success()
                            : Outcome.retryable(flushFailure))
            );
        });
        return completion;
    }

    /** Exact chunk durability result. */
    public record Outcome(
            boolean saved,
            @Nullable Throwable failure
    ) {
        public static Outcome success() {
            return new Outcome(true, null);
        }

        public static Outcome retryable(@Nullable Throwable failure) {
            return new Outcome(false, failure);
        }
    }

    /** Potentially blocking chunk-store flush invoked on the supplied executor. */
    @FunctionalInterface
    public interface ChunkFlusher {
        void flush() throws Exception;
    }
}
