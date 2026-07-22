package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

/**
 * Runtime-owned bounded executor for connection-opening SQLite reads.
 *
 * <p>Rejection completes the submitted stage exceptionally; it never falls back to the caller,
 * because callers include world ticks and player event threads.</p>
 */
public final class PersistenceReadExecutor implements AutoCloseable {
    private static final int THREAD_COUNT = 2;
    private static final int QUEUE_CAPACITY = 256;

    private final ThreadPoolExecutor executor;

    public PersistenceReadExecutor(@Nonnull String threadNamePrefix) {
        String prefix = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix").trim();
        if (prefix.isEmpty()) throw new IllegalArgumentException("threadNamePrefix is required");
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(
                THREAD_COUNT, THREAD_COUNT, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), threads,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Nonnull
    public <T> CompletionStage<T> submit(@Nonnull CheckedSupplier<T> work) {
        Objects.requireNonNull(work, "work");
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    result.complete(work.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
