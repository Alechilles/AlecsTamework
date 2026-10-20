package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles {@link RemoveWorldEvent} for loaded companions (spec 8.8). Register it at the maximum
 * short priority so it sees the final cancelled state (lesson 2026-07-14).
 *
 * <p>World removal dispatches no per-entity removal callbacks. For a delete-on-remove world
 * (portal and instance worlds) each loaded companion is recorded LOST with a snapshot; for any
 * other world each record is refreshed as on UNLOAD. In both cases the world's bodies then leave
 * the loaded map (lesson 2026-07-20). The work runs on the world thread. The event can fire on
 * any thread; off the world thread the work is queued with {@code world.execute} and never waited
 * on. The world drains its task queue in {@code World#onShutdown} before its stores shut down, so
 * the queued work still sees the bodies.</p>
 */
public final class CompanionWorldRemovalListener {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final CompanionBodyLifecycle lifecycle;
    private final CompanionIndex index;
    private final LoadedBodies<Ref<EntityStore>> loaded;

    public CompanionWorldRemovalListener(@Nonnull CompanionBodyLifecycle lifecycle, @Nonnull CompanionIndex index,
                                         @Nonnull LoadedBodies<Ref<EntityStore>> loaded) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.index = Objects.requireNonNull(index, "index");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    public void onRemoveWorld(@Nullable RemoveWorldEvent event) {
        if (event == null || event.getWorld() == null) {
            return;
        }
        // An exceptional removal (world thread crash) removes the world even when cancelled.
        if (event.isCancelled() && event.getRemovalReason() != RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
            return;
        }
        World world = event.getWorld();
        WorldConfig config = world.getWorldConfig();
        boolean deleted = config != null && config.isDeleteOnRemove();
        if (world.isInThread()) {
            settle(world, deleted);
            return;
        }
        try {
            world.execute(() -> settle(world, deleted));
        } catch (RuntimeException e) {
            // The world no longer accepts tasks, so its bodies cannot be read.
            LOGGER.at(Level.WARNING).withCause(e).log(
                    "Could not update companions in removed world %s", world.getName());
            dropWorld(world);
        }
    }

    /** World thread only. */
    private void settle(World world, boolean deleted) {
        try {
            for (Map.Entry<UUID, Ref<EntityStore>> entry : bodiesIn(world).entrySet()) {
                Ref<EntityStore> ref = entry.getValue();
                try {
                    if (deleted) {
                        lifecycle.worldRemoved(ref, ref.getStore(), entry.getKey());
                    } else {
                        lifecycle.worldUnloaded(ref, ref.getStore(), entry.getKey());
                    }
                } catch (RuntimeException e) {
                    LOGGER.at(Level.WARNING).withCause(e).log(
                            "Could not update companion %s in removed world %s", entry.getKey(), world.getName());
                }
            }
        } finally {
            dropWorld(world);
        }
    }

    /** The valid loaded bodies of {@code world}, read under the index lock. */
    private Map<UUID, Ref<EntityStore>> bodiesIn(World world) {
        Map<UUID, Ref<EntityStore>> bodies = new LinkedHashMap<>();
        index.atomically(() -> {
            loaded.forEach((id, ref) -> {
                if (ref.isValid() && ref.getStore().getExternalData().getWorld() == world) {
                    bodies.put(id, ref);
                }
            });
            return null;
        });
        return bodies;
    }

    private void dropWorld(World world) {
        index.atomically(() -> {
            loaded.removeIf((id, ref) -> !ref.isValid() || ref.getStore().getExternalData().getWorld() == world);
            return null;
        });
    }
}
