package com.alechilles.alecstamework.ownership.reconciliation;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.component.ChunkSavingSystems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Holds the same world-save barrier used by Hytale 0.5.6 backups while detached chunk files are
 * scanned. Gameplay may continue, but saving stays paused so cursor batches observe one disk
 * snapshot instead of a mixture of pre- and post-movement holders.
 */
final class HytaleSavedWorldScanBarrier {
    private final SavingAccess access;

    HytaleSavedWorldScanBarrier(@Nonnull Universe universe) {
        this(new RuntimeSavingAccess(universe));
    }

    HytaleSavedWorldScanBarrier(@Nonnull SavingAccess access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    /** Acquires universe/world locks, pauses background writers, and drains in-flight saves. */
    @Nonnull
    CompletableFuture<Lease> acquireAsync() {
        if (!access.tryLockUniverse()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Another manual saved-world file access is active.")
            );
        }
        List<WorldSavingAccess> worlds;
        try {
            worlds = List.copyOf(access.worlds());
        } catch (Throwable failure) {
            return unlockAfterFailure(access, failure);
        }
        final CompletableFuture<Void> acquired;
        try {
            acquired = requireFuture(
                    access.awaitUniverseWrites(), "universe save-drain future")
                    .thenCompose(ignored -> pauseAll(worlds));
        } catch (Throwable failure) {
            return releaseAfterFailure(access, worlds, failure);
        }
        CompletableFuture<Lease> result = new CompletableFuture<>();
        acquired.whenComplete((ignored, failure) -> {
            if (failure == null) {
                result.complete(new Lease(access, worlds));
                return;
            }
            releaseAll(access, worlds).whenComplete((released, releaseFailure) -> {
                Throwable root = rootCause(failure);
                if (releaseFailure != null) {
                    root.addSuppressed(rootCause(releaseFailure));
                }
                result.completeExceptionally(root);
            });
        });
        return result;
    }

    @Nonnull
    private static CompletableFuture<Void> releaseAll(
            @Nonnull SavingAccess access,
            @Nonnull List<WorldSavingAccess> worlds
    ) {
        List<CompletableFuture<Throwable>> attempts = new ArrayList<>(worlds.size());
        for (WorldSavingAccess world : worlds) {
            attempts.add(observeCleanup(() -> world.resumeAsync(), "world resume future"));
        }
        return CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
                .thenCompose(ignored -> {
                    Throwable failure = null;
                    for (CompletableFuture<Throwable> attempt : attempts) {
                        failure = combine(failure, attempt.join());
                    }
                    try {
                        access.unlockUniverse();
                    } catch (Throwable unlockFailure) {
                        failure = combine(failure, unlockFailure);
                    }
                    return failure == null
                            ? CompletableFuture.completedFuture(null)
                            : CompletableFuture.failedFuture(failure);
                });
    }

    @Nonnull
    private static CompletableFuture<Void> pauseAll(
            @Nonnull List<WorldSavingAccess> worlds) {
        List<CompletableFuture<Void>> pauses = new ArrayList<>(worlds.size());
        for (WorldSavingAccess world : worlds) {
            try {
                pauses.add(requireFuture(
                        world.pauseAndDrainAsync(), "world save-drain future"));
            } catch (Throwable failure) {
                pauses.add(CompletableFuture.failedFuture(failure));
            }
        }
        return CompletableFuture.allOf(pauses.toArray(CompletableFuture[]::new));
    }

    @Nonnull
    private static CompletableFuture<Throwable> observeCleanup(
            @Nonnull Supplier<CompletableFuture<Void>> action,
            @Nonnull String futureName) {
        try {
            return requireFuture(action.get(), futureName)
                    .handle((ignored, failure) -> failure == null ? null : rootCause(failure));
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(rootCause(failure));
        }
    }

    @Nonnull
    private static <T> CompletableFuture<T> releaseAfterFailure(
            SavingAccess access,
            List<WorldSavingAccess> worlds,
            Throwable failure) {
        Throwable root = rootCause(failure);
        return releaseAll(access, worlds).handle((ignored, releaseFailure) -> {
            if (releaseFailure != null) {
                root.addSuppressed(rootCause(releaseFailure));
            }
            throw new CompletionException(root);
        });
    }

    @Nonnull
    private static <T> CompletableFuture<T> unlockAfterFailure(
            SavingAccess access,
            Throwable failure) {
        Throwable root = rootCause(failure);
        try {
            access.unlockUniverse();
        } catch (Throwable unlockFailure) {
            root.addSuppressed(rootCause(unlockFailure));
        }
        return CompletableFuture.failedFuture(root);
    }

    @Nonnull
    private static <T> CompletableFuture<T> requireFuture(
            CompletableFuture<T> future,
            String name) {
        return Objects.requireNonNull(future, name);
    }

    @Nullable
    private static Throwable combine(@Nullable Throwable current, @Nullable Throwable next) {
        if (next == null) {
            return current;
        }
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

    /** Idempotent ownership token for the acquired save barrier. */
    static final class Lease {
        private final SavingAccess access;
        private final List<WorldSavingAccess> worlds;
        private boolean released;
        @Nullable
        private CompletableFuture<Void> releaseInFlight;

        private Lease(SavingAccess access, List<WorldSavingAccess> worlds) {
            this.access = access;
            this.worlds = worlds;
        }

        @Nonnull
        synchronized CompletableFuture<Void> releaseAsync() {
            if (released) {
                return CompletableFuture.completedFuture(null);
            }
            if (releaseInFlight != null) {
                return releaseInFlight;
            }
            CompletableFuture<Void> attempt = releaseAll(access, worlds);
            releaseInFlight = attempt;
            attempt.whenComplete((ignored, failure) -> {
                synchronized (this) {
                    released = failure == null;
                    releaseInFlight = null;
                }
            });
            return attempt;
        }
    }

    interface SavingAccess {
        boolean tryLockUniverse();

        void unlockUniverse();

        @Nonnull
        CompletableFuture<Void> awaitUniverseWrites();

        @Nonnull
        List<WorldSavingAccess> worlds();
    }

    interface WorldSavingAccess {
        @Nonnull
        CompletableFuture<Void> pauseAndDrainAsync();

        @Nonnull
        CompletableFuture<Void> resumeAsync();
    }

    private static final class RuntimeSavingAccess implements SavingAccess {
        private final Universe universe;
        private final AtomicBoolean locked = new AtomicBoolean();

        private RuntimeSavingAccess(Universe universe) {
            this.universe = Objects.requireNonNull(universe, "universe");
        }

        @Override
        public boolean tryLockUniverse() {
            if (universe.isSavingLocked()) {
                return false;
            }
            universe.lockSaving();
            locked.set(true);
            return true;
        }

        @Override
        public void unlockUniverse() {
            if (locked.get()) {
                universe.unlockSaving();
                locked.set(false);
            }
        }

        @Nonnull
        @Override
        public CompletableFuture<Void> awaitUniverseWrites() {
            return universe.getStorageManager().pendingOperations();
        }

        @Nonnull
        @Override
        public List<WorldSavingAccess> worlds() {
            List<World> snapshot = new ArrayList<>(universe.getWorlds().values());
            snapshot.sort(Comparator.comparing(World::getName));
            return snapshot.stream()
                    .<WorldSavingAccess>map(RuntimeWorldSavingAccess::new)
                    .toList();
        }
    }

    private static final class RuntimeWorldSavingAccess implements WorldSavingAccess {
        private final World world;
        private final AtomicBoolean locked = new AtomicBoolean();
        private final AtomicBoolean backgroundPaused = new AtomicBoolean();

        private RuntimeWorldSavingAccess(World world) {
            this.world = Objects.requireNonNull(world, "world");
        }

        @Nonnull
        @Override
        public CompletableFuture<Void> pauseAndDrainAsync() {
            return CompletableFuture.supplyAsync(() -> {
                if (world.isSavingLocked()) {
                    throw new IllegalStateException(
                            "World saving is already locked: " + world.getName()
                    );
                }
                ChunkSavingSystems.Data data = world.getChunkStore().getStore()
                        .getResource(ChunkStore.SAVE_RESOURCE);
                if (data == null) {
                    throw new IllegalStateException(
                            "World chunk-saving state is unavailable: " + world.getName()
                    );
                }
                world.getChunkStore().pauseBackgroundSaving(data);
                backgroundPaused.set(true);
                world.lockSaving();
                locked.set(true);
                return data;
            }, world).thenCompose(ChunkSavingSystems.Data::waitForSavingChunks);
        }

        @Nonnull
        @Override
        public CompletableFuture<Void> resumeAsync() {
            if (!locked.get() && !backgroundPaused.get()) {
                return CompletableFuture.completedFuture(null);
            }
            try {
                return CompletableFuture.runAsync(() -> releaseWorldState(
                        backgroundPaused,
                        locked,
                        () -> world.getChunkStore().resumeBackgroundSaving(),
                        world::unlockSaving), world);
            } catch (Throwable schedulingFailure) {
                return CompletableFuture.failedFuture(schedulingFailure);
            }
        }
    }

    /** Attempts both independent world cleanup actions and preserves retryable state on failure. */
    static void releaseWorldState(
            @Nonnull AtomicBoolean backgroundPaused,
            @Nonnull AtomicBoolean locked,
            @Nonnull Runnable resumeBackground,
            @Nonnull Runnable unlockWorld) {
        Throwable failure = null;
        if (backgroundPaused.get()) {
            try {
                resumeBackground.run();
                backgroundPaused.set(false);
            } catch (Throwable backgroundFailure) {
                failure = combine(failure, backgroundFailure);
            }
        }
        if (locked.get()) {
            try {
                unlockWorld.run();
                locked.set(false);
            } catch (Throwable unlockFailure) {
                failure = combine(failure, unlockFailure);
            }
        }
        if (failure != null) {
            throw new CompletionException(failure);
        }
    }
}
