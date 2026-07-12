package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentitySnapshot;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Waits until Universe startup has settled before binding reconciliation to the current loaded-NPC
 * identity bootstrap generation.
 */
final class LoadedNpcIdentityStartupGate {
    private final LoadedNpcIdentityIndex identityIndex;
    private final Executor executor;
    private final BooleanSupplier closed;

    LoadedNpcIdentityStartupGate(@Nonnull LoadedNpcIdentityIndex identityIndex,
                                 @Nonnull Executor executor,
                                 @Nonnull BooleanSupplier closed) {
        this.identityIndex = Objects.requireNonNull(identityIndex, "identityIndex");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.closed = Objects.requireNonNull(closed, "closed");
    }

    /** Resolves the bootstrap source after Universe-ready and follows any superseding generation. */
    @Nonnull
    CompletableFuture<LoadedNpcIdentitySnapshot> awaitAfter(
            @Nonnull CompletableFuture<?> universeReady,
            @Nonnull Supplier<CompletableFuture<LoadedNpcIdentitySnapshot>> currentBootstrap
    ) {
        Objects.requireNonNull(universeReady, "universeReady");
        Objects.requireNonNull(currentBootstrap, "currentBootstrap");
        return universeReady.thenComposeAsync(
                ignored -> awaitCurrent(currentBootstrap), executor
        );
    }

    @Nonnull
    private CompletableFuture<LoadedNpcIdentitySnapshot> awaitCurrent(
            @Nonnull Supplier<CompletableFuture<LoadedNpcIdentitySnapshot>> currentBootstrap
    ) {
        if (closed.getAsBoolean()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Population reconciliation runtime is closed.")
            );
        }
        CompletableFuture<LoadedNpcIdentitySnapshot> future;
        try {
            future = Objects.requireNonNull(
                    currentBootstrap.get(), "Loaded NPC identity bootstrap future"
            );
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return future.thenComposeAsync(snapshot -> resolveOrRebind(snapshot, currentBootstrap), executor);
    }

    @Nonnull
    private CompletableFuture<LoadedNpcIdentitySnapshot> resolveOrRebind(
            LoadedNpcIdentitySnapshot completed,
            @Nonnull Supplier<CompletableFuture<LoadedNpcIdentitySnapshot>> currentBootstrap
    ) {
        if (completed == null || !completed.initializationComplete()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Loaded NPC identity bootstrap completed incompletely.")
            );
        }
        LoadedNpcIdentitySnapshot current = identityIndex.snapshot();
        if (current.initializationComplete()) {
            return CompletableFuture.completedFuture(current);
        }
        return awaitCurrent(currentBootstrap);
    }
}
