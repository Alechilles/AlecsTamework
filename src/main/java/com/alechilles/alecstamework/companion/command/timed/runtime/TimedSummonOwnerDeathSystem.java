package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Begins durable timed-companion storage as soon as an owning player dies. */
public final class TimedSummonOwnerDeathSystem extends DeathSystems.OnDeathSystem {
    private final TimedSummonOwnerLifecycleService lifecycle;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final Query<EntityStore> query;

    public TimedSummonOwnerDeathSystem(
            @Nonnull TimedSummonOwnerLifecycleService lifecycle,
            @Nonnull ComponentType<EntityStore, Player> playerType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType
    ) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "Lifecycle service is required");
        this.uuidType = Objects.requireNonNull(uuidType, "UUID component type is required");
        this.query = Query.and(
                Objects.requireNonNull(playerType, "Player component type is required"),
                this.uuidType
        );
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        UUIDComponent uuid = store.getComponent(reference, uuidType);
        if (uuid != null) {
            lifecycle.onOwnerDeath(uuid.getUuid());
        }
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }
}
