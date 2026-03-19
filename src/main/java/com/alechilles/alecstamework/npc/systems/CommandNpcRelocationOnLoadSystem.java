package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.CommandNpcRelocationService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
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
    private final CommandLinkedNpcDeathService deathService;
    private final CommandLinkedNpcLostService lostService;

    public CommandNpcRelocationOnLoadSystem(CommandNpcRelocationService relocationService,
                                            CommandLinkedNpcDeathService deathService,
                                            CommandLinkedNpcLostService lostService) {
        this.relocationService = relocationService;
        this.deathService = deathService;
        this.lostService = lostService;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (relocationService == null) {
            if (deathService != null) {
                deathService.onNpcAdded(reference, store);
            }
            if (lostService != null) {
                lostService.onNpcAdded(reference, store);
            }
            return;
        }
        relocationService.onNpcAdded(reference, store);
        if (deathService != null) {
            deathService.onNpcAdded(reference, store);
        }
        if (lostService != null) {
            lostService.onNpcAdded(reference, store);
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (relocationService != null) {
            relocationService.onNpcRemoved(reference, store);
        }
        if (deathService != null) {
            deathService.onNpcRemoved(reference, reason, store);
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }
}
