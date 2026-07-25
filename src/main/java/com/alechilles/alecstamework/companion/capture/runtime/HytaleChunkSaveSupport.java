package com.alechilles.alecstamework.companion.capture.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Shared asynchronous save-then-flush mapping for exact Hytale chunk receipts. */
final class HytaleChunkSaveSupport {
    private HytaleChunkSaveSupport() {
    }

    static CompletionStage<Outcome> saveAndFlush(
            CompletionStage<Void> save,
            ChunkFlusher flusher,
            Executor ioExecutor
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

    record Outcome(boolean saved, Throwable failure) {
        static Outcome success() {
            return new Outcome(true, null);
        }

        static Outcome retryable(Throwable failure) {
            return new Outcome(false, failure);
        }
    }

    @FunctionalInterface
    interface ChunkFlusher {
        void flush() throws Exception;
    }
}
