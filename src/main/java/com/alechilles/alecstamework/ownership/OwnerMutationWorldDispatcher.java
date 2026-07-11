package com.alechilles.alecstamework.ownership;

import com.hypixel.hytale.server.core.universe.world.World;
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
        try {
            if (!world.isAlive()) {
                rejected.run();
            } else {
                world.execute(task);
            }
        } catch (RuntimeException | LinkageError failure) {
            rejected.run();
        }
    }
}
