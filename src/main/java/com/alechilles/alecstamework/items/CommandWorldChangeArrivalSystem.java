package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Starts authorized companion travel at the exact tick where the destination player becomes live. */
public final class CommandWorldChangeArrivalSystem extends RefSystem<EntityStore> {
    private final CommandWorldChangeTravelEventHandler travelEvents;

    public CommandWorldChangeArrivalSystem(
            @Nonnull CommandWorldChangeTravelEventHandler travelEvents) {
        this.travelEvents = Objects.requireNonNull(travelEvents, "travelEvents");
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Player player = store.getComponent(reference, Player.getComponentType());
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        UUID playerUuid = player != null ? player.getUuid() : null;
        travelEvents.onPlayerAdded(world, playerUuid);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // World-change travel is admitted only on destination arrival.
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
}
