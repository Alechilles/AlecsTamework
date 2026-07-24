package com.alechilles.alecstamework.items.persistence;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Freezes loaded companion state at the authoritative delete-on-remove world boundary.
 *
 * <p>The global event fires before Hytale stops and deletes the world. This adapter queues the
 * scan on the world executor when necessary and never waits in the lifecycle callback. The scan
 * freezes only currently loaded NPC evidence before authoring, while its completion path retains
 * only the immutable world key. Ordinary world removal and cancelled events are not
 * world-deletion evidence.</p>
 */
public final class DormantCompanionWorldRemovalBridge {
    private final DormantCompanionEcsBridge bridge;
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final Consumer<Warning> warnings;
    private final Executor completionExecutor;

    public DormantCompanionWorldRemovalBridge(
            @Nonnull DormantCompanionEcsBridge bridge,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
            @Nonnull Consumer<Warning> warnings
    ) {
        this(bridge, npcType, warnings, ForkJoinPool.commonPool());
    }

    DormantCompanionWorldRemovalBridge(
            DormantCompanionEcsBridge bridge,
            ComponentType<EntityStore, NPCEntity> npcType,
            Consumer<Warning> warnings,
            Executor completionExecutor
    ) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.npcType = Objects.requireNonNull(npcType, "npcType");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
        this.completionExecutor = Objects.requireNonNull(
                completionExecutor, "completionExecutor"
        );
    }

    /**
     * Handles one terminal-priority world event without waiting on cross-thread ECS work.
     */
    public void onWorldRemoved(@Nullable RemoveWorldEvent event) {
        if (!authoritativeWorldDeletion(event)) {
            return;
        }
        World world = event.getWorld();
        String worldKey = world.getName();
        try {
            if (world.isInThread()) {
                scan(world);
            } else {
                CompletableFuture.runAsync(() -> scan(world), world)
                        .whenCompleteAsync(
                                (ignored, failure) -> reportFailure(
                                        worldKey, failure
                                ),
                                completionExecutor
                        );
            }
        } catch (RuntimeException | LinkageError failure) {
            reportFailureAsync(worldKey, failure);
        }
    }

    private void scan(World world) {
        if (world.getEntityStore() == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return;
        }
        store.forEachChunk(
                npcType,
                (chunk, commandBuffer) -> {
                    for (int index = 0; index < chunk.size(); index++) {
                        bridge.onWorldDeletion(
                                chunk.getReferenceTo(index), store
                        );
                    }
                }
        );
    }

    private void reportFailureAsync(String worldKey, Throwable failure) {
        completionExecutor.execute(() -> reportFailure(worldKey, failure));
    }

    private void reportFailure(String worldKey, @Nullable Throwable failure) {
        if (failure == null) {
            return;
        }
        warnings.accept(new Warning(
                "world_deletion_snapshot_failed",
                worldKey,
                unwrap(failure)
        ));
    }

    static boolean authoritativeWorldDeletion(
            @Nullable RemoveWorldEvent event
    ) {
        return event != null
                && !event.isCancelled()
                && event.getWorld() != null
                && event.getWorld().getWorldConfig() != null
                && event.getWorld().getWorldConfig().isDeleteOnRemove();
    }

    private Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException
                && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    /** Bounded warning context that does not retain a World or Store. */
    public record Warning(
            @Nonnull String code,
            @Nullable String worldKey,
            @Nonnull Throwable failure
    ) {
        public Warning {
            if (code == null || code.isBlank() || failure == null) {
                throw new IllegalArgumentException(
                        "World-deletion warning code and failure are required"
                );
            }
            code = code.trim();
            worldKey = worldKey == null || worldKey.isBlank()
                    ? null
                    : worldKey.trim();
        }
    }
}
