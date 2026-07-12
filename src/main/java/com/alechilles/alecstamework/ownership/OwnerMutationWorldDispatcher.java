package com.alechilles.alecstamework.ownership;

import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/** Dispatches deferred owner-mutation work without letting world shutdown strand cleanup. */
final class OwnerMutationWorldDispatcher {
    private OwnerMutationWorldDispatcher() {
    }

    static void execute(@Nonnull World world, @Nonnull Runnable task) {
        execute(world, task, () -> {
        });
    }

    static void execute(@Nonnull World world,
                        @Nonnull Runnable task,
                        @Nonnull Runnable rejected) {
        Objects.requireNonNull(world, "world");
        execute(world::isAlive, world::execute, task, rejected);
    }

    static void execute(@Nonnull BooleanSupplier alive,
                        @Nonnull Consumer<Runnable> dispatcher,
                        @Nonnull Runnable task,
                        @Nonnull Runnable rejected) {
        Objects.requireNonNull(alive, "alive");
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(rejected, "rejected");
        AtomicBoolean taskStarted = new AtomicBoolean();
        try {
            if (!alive.getAsBoolean()) {
                runRejected(rejected);
                return;
            }
            dispatcher.accept(() -> {
                taskStarted.set(true);
                task.run();
            });
        } catch (RuntimeException | LinkageError failure) {
            if (!taskStarted.get()) {
                runRejected(rejected);
            }
        }
    }

    private static void runRejected(@Nonnull Runnable rejected) {
        try {
            rejected.run();
        } catch (RuntimeException | LinkageError ignored) {
            // Rejection is already terminal; callback failures must not trigger it twice.
        }
    }
}
