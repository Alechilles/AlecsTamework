package com.alechilles.alecstamework.ownership.reconciliation;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Advances the startup evidence fence whenever an online player's inventory changes. */
public final class CompanionLiveInventoryEvidenceSystem
        extends EntityEventSystem<EntityStore, InventoryChangeEvent> {
    private final CompanionLiveEvidenceRevision liveEvidenceRevision;

    public CompanionLiveInventoryEvidenceSystem(
            @Nonnull CompanionLiveEvidenceRevision liveEvidenceRevision) {
        super(InventoryChangeEvent.class);
        this.liveEvidenceRevision = Objects.requireNonNull(
                liveEvidenceRevision, "liveEvidenceRevision");
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InventoryChangeEvent event) {
        liveEvidenceRevision.advance();
    }
}
