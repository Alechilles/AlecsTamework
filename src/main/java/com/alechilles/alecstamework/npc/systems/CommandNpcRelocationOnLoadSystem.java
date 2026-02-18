package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.CommandNpcRelocationService;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Attempts queued command relocation requests when NPC entities are added back into the world store.
 */
public final class CommandNpcRelocationOnLoadSystem extends RefSystem<EntityStore> {
    private final CommandNpcRelocationService relocationService;

    public CommandNpcRelocationOnLoadSystem(CommandNpcRelocationService relocationService) {
        this.relocationService = relocationService;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (relocationService == null) {
            return;
        }
        relocationService.onNpcAdded(reference, store);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // No-op.
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }
}
