package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry;
import com.alechilles.alecstamework.npc.breeding.BreedingJobScheduler;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/** Dispatches delayed job-ID-only callbacks onto the currently loaded world thread. */
final class HytaleBreedingJobScheduler implements BreedingJobScheduler {
    private static final long FAILURE_RETRY_DELAY_MS = 1000L;

    private final BreedingBirthJobRegistry registry;
    private volatile Consumer<UUID> handler;
    private volatile Consumer<UUID> failureHandler;

    HytaleBreedingJobScheduler(@Nonnull BreedingBirthJobRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    void bind(@Nonnull Consumer<UUID> handler, @Nonnull Consumer<UUID> failureHandler) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(failureHandler, "failureHandler");
        if (this.handler != null || this.failureHandler != null) {
            throw new IllegalStateException("Breeding job scheduler is already bound");
        }
        this.handler = handler;
        this.failureHandler = failureHandler;
    }

    @Override
    public void schedule(@Nonnull UUID jobId, long delayMs) {
        Objects.requireNonNull(jobId, "jobId");
        CompletableFuture.runAsync(
                () -> dispatch(jobId),
                CompletableFuture.delayedExecutor(Math.max(0L, delayMs), TimeUnit.MILLISECONDS)
        ).exceptionally(error -> {
            logWarning("Delayed breeding job dispatch failed for " + jobId + ".", error);
            dispatchFailure(jobId);
            return null;
        });
    }

    private void dispatch(UUID jobId) {
        Consumer<UUID> currentHandler = handler;
        if (currentHandler == null) {
            dispatchFailure(jobId);
            return;
        }
        BreedingBirthJobRegistry.LocatedJob located = registry.locate(jobId).orElse(null);
        if (located == null) {
            return;
        }
        Universe universe = Universe.get();
        World world = universe != null ? universe.getWorld(located.worldId()) : null;
        if (world == null) {
            // With no owning world thread available, terminate conservatively and retain the
            // provisional cooldown. Calling the runtime handler here would touch world state from
            // the delayed executor if the world raced back into existence.
            registry.fail(jobId);
            return;
        }
        try {
            world.execute(() -> currentHandler.accept(jobId));
        } catch (RuntimeException exception) {
            logWarning("Could not enter the world thread for breeding job " + jobId + ".", exception);
            retryFailureDispatch(jobId);
        }
    }

    private void dispatchFailure(UUID jobId) {
        Consumer<UUID> currentFailureHandler = failureHandler;
        if (currentFailureHandler == null) {
            registry.fail(jobId);
            return;
        }
        BreedingBirthJobRegistry.LocatedJob located = registry.locate(jobId).orElse(null);
        if (located == null || located.job().state().isTerminal()) {
            return;
        }
        Universe universe = Universe.get();
        World world = universe != null ? universe.getWorld(located.worldId()) : null;
        if (world == null) {
            // World unload/crash keeps the applied cooldown by policy. Never attempt rollback from
            // the delayed executor without an owning world-thread callback.
            registry.fail(jobId);
            return;
        }
        try {
            world.execute(() -> currentFailureHandler.accept(jobId));
        } catch (RuntimeException exception) {
            logWarning("Could not enter the world thread to fail breeding job " + jobId + ".", exception);
            retryFailureDispatch(jobId);
        }
    }

    private void retryFailureDispatch(UUID jobId) {
        CompletableFuture.runAsync(
                () -> dispatchFailure(jobId),
                CompletableFuture.delayedExecutor(FAILURE_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
        ).exceptionally(error -> {
            logWarning("Could not retry failed breeding job dispatch for " + jobId + ".", error);
            dispatchFailure(jobId);
            return null;
        });
    }

    private static void logWarning(String message, Throwable error) {
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().at(Level.WARNING).withCause(error).log(message);
        }
    }
}
