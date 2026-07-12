package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Guarantees canonical-reload cleanup for synchronous and asynchronous completion failures. */
final class CanonicalReloadCompletion {
    private CanonicalReloadCompletion() {
    }

    @Nonnull
    static <T> CompletableFuture<T> run(
            @Nonnull Supplier<CompletableFuture<T>> work,
            @Nonnull Runnable cleanup) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(cleanup, "cleanup");
        final CompletableFuture<T> future;
        try {
            future = Objects.requireNonNull(work.get(), "canonical reload work future");
        } catch (Throwable failure) {
            return finish(null, failure, cleanup);
        }
        return future.handle(Outcome<T>::new)
                .thenCompose(outcome -> finish(outcome.value(), outcome.failure(), cleanup));
    }

    @Nonnull
    private static <T> CompletableFuture<T> finish(
            @Nullable T value,
            @Nullable Throwable failure,
            Runnable cleanup) {
        Throwable combined = failure == null ? null : rootCause(failure);
        try {
            cleanup.run();
        } catch (Throwable cleanupFailure) {
            combined = combine(combined, cleanupFailure);
        }
        return combined == null
                ? CompletableFuture.completedFuture(value)
                : CompletableFuture.failedFuture(combined);
    }

    @Nonnull
    private static Throwable combine(
            @Nullable Throwable current,
            @Nonnull Throwable next) {
        Throwable root = rootCause(next);
        if (current == null) {
            return root;
        }
        if (current != root) {
            current.addSuppressed(root);
        }
        return current;
    }

    @Nonnull
    private static Throwable rootCause(@Nonnull Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record Outcome<T>(@Nullable T value, @Nullable Throwable failure) {
    }
}
