package com.alechilles.alecstamework.ownership;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;

/** Periodically closes expired public population capabilities through their durable coordinator. */
final class PublicPopulationCapabilityMaintenance implements AutoCloseable {
    private static final long DEFAULT_INTERVAL_MS = 1_000L;

    private final RuntimePopulationPolicyAuthority authority;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean cleanupInFlight = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;

    PublicPopulationCapabilityMaintenance(
            @Nonnull RuntimePopulationPolicyAuthority authority,
            @Nonnull ScheduledExecutorService executor
    ) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Starts the bounded runtime maintenance loop on its own daemon executor. */
    @Nonnull
    static PublicPopulationCapabilityMaintenance start(
            @Nonnull RuntimePopulationPolicyAuthority authority
    ) {
        ScheduledExecutorService executor = newExecutor();
        PublicPopulationCapabilityMaintenance maintenance =
                new PublicPopulationCapabilityMaintenance(authority, executor);
        try {
            maintenance.scheduled = executor.scheduleWithFixedDelay(
                    maintenance::runScheduledCleanup,
                    DEFAULT_INTERVAL_MS,
                    DEFAULT_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );
            return maintenance;
        } catch (RuntimeException failure) {
            executor.shutdownNow();
            throw failure;
        }
    }

    /** Executes the same non-overlapping bounded cleanup used by the scheduled loop. */
    @Nonnull
    CompletionStage<Integer> cleanupOnce() {
        if (closed.get() || !cleanupInFlight.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(0);
        }
        CompletionStage<Integer> cleanup;
        try {
            cleanup = authority.cleanupExpired();
            if (cleanup == null) {
                throw new IllegalStateException("Population capability cleanup returned no stage.");
            }
        } catch (RuntimeException | LinkageError failure) {
            cleanupInFlight.set(false);
            return CompletableFuture.failedFuture(failure);
        }

        CompletableFuture<Integer> completion = new CompletableFuture<>();
        cleanup.whenComplete((count, failure) -> {
            cleanupInFlight.set(false);
            if (failure == null) {
                completion.complete(count == null ? 0 : count);
            } else {
                completion.completeExceptionally(failure);
            }
        });
        return completion;
    }

    private void runScheduledCleanup() {
        cleanupOnce().whenComplete((ignored, failure) -> {
            // Cleanup owns readiness degradation; the maintenance loop must remain scheduled.
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> current = scheduled;
        if (current != null) {
            current.cancel(false);
        }
        executor.shutdownNow();
    }

    @Nonnull
    private static ScheduledExecutorService newExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tamework-public-population-maintenance");
            thread.setDaemon(true);
            return thread;
        });
    }
}
