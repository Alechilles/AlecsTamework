package com.alechilles.alecstamework.companion.flow;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** World-thread hand-off for companion bodies held by ref. Safe to call from any thread. */
public final class CompanionBodies {
    private CompanionBodies() {
    }

    /** The world that owns the body's store, or null when it cannot be resolved. Reads no entity data. */
    @Nullable
    public static World worldOf(@Nonnull Ref<EntityStore> body) {
        Store<EntityStore> store = body.getStore();
        return store == null || store.getExternalData() == null ? null : store.getExternalData().getWorld();
    }

    /**
     * Removes the body (REMOVE) on its own world thread if the ref is still valid there. Does
     * nothing when its world is gone or no longer accepts tasks; a body saved with its chunk is
     * then removed by the generation fence when it loads.
     */
    public static void removeOnOwnWorld(@Nonnull Ref<EntityStore> body) {
        World world = worldOf(body);
        if (world == null || !world.isAlive()) {
            return;
        }
        try {
            world.execute(() -> {
                if (body.isValid()) {
                    body.getStore().removeEntity(body, RemoveReason.REMOVE);
                }
            });
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks; the task was not queued.
        }
    }
}
