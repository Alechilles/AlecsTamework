package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionSpawnPreparationResult;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Retries command-spawn preparation during the bounded startup canonical-reload window. */
final class CanonicalReloadSpawnPreparationRetry {
    static final String TRANSIENT_REASON = "owner-population-canonical-reload";
    private static final int DEFAULT_MAX_ATTEMPTS = 20;
    private static final long DEFAULT_RETRY_DELAY_MS = 100L;

    private final Executor retryExecutor;
    private final int maxAttempts;

    CanonicalReloadSpawnPreparationRetry() {
        this(
                CompletableFuture.delayedExecutor(
                        DEFAULT_RETRY_DELAY_MS, TimeUnit.MILLISECONDS
                ),
                DEFAULT_MAX_ATTEMPTS
        );
    }

    CanonicalReloadSpawnPreparationRetry(@Nonnull Executor retryExecutor, int maxAttempts) {
        this.retryExecutor = Objects.requireNonNull(retryExecutor, "retryExecutor");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    @Nonnull
    CompletableFuture<CompanionSpawnPreparationResult> prepare(
            @Nonnull Supplier<CompletableFuture<CompanionSpawnPreparationResult>> attempt
    ) {
        Objects.requireNonNull(attempt, "attempt");
        CompletableFuture<CompanionSpawnPreparationResult> completion = new CompletableFuture<>();
        startAttempt(attempt, completion, 1);
        return completion;
    }

    private void startAttempt(
            @Nonnull Supplier<CompletableFuture<CompanionSpawnPreparationResult>> attempt,
            @Nonnull CompletableFuture<CompanionSpawnPreparationResult> completion,
            int attemptNumber
    ) {
        final CompletableFuture<CompanionSpawnPreparationResult> stage;
        try {
            stage = Objects.requireNonNull(attempt.get(), "spawn preparation stage");
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
            return;
        }
        stage.whenComplete((result, failure) -> {
            if (failure != null || !retryable(result) || attemptNumber >= maxAttempts) {
                complete(completion, result, failure);
                return;
            }
            try {
                retryExecutor.execute(() -> startAttempt(
                        attempt, completion, attemptNumber + 1
                ));
            } catch (RuntimeException | LinkageError rejected) {
                completion.completeExceptionally(rejected);
            }
        });
    }

    private static boolean retryable(CompanionSpawnPreparationResult result) {
        return result != null && !result.allowed() && TRANSIENT_REASON.equals(result.reason());
    }

    private static void complete(
            CompletableFuture<CompanionSpawnPreparationResult> completion,
            CompanionSpawnPreparationResult result,
            Throwable failure
    ) {
        if (failure != null) {
            completion.completeExceptionally(failure);
        } else if (result == null) {
            completion.completeExceptionally(
                    new IllegalStateException("Spawn preparation completed without a result.")
            );
        } else {
            completion.complete(result);
        }
    }
}
