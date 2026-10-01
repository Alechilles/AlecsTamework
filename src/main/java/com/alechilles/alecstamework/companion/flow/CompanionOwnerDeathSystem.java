package com.alechilles.alecstamework.companion.flow;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Hands a dying player's UUID to {@code onOwnerDeath} when DeathComponent is added (spec 8.4:
 * timed summons are stored when their owner dies). The callback runs in this world's ECS
 * callback and must only start work that hops to other threads itself; it writes no entity.
 */
public final class CompanionOwnerDeathSystem extends DeathSystems.OnDeathSystem {
    private final Consumer<UUID> onOwnerDeath;
    private final Query<EntityStore> query = Query.and(Player.getComponentType(), UUIDComponent.getComponentType());

    public CompanionOwnerDeathSystem(@Nonnull Consumer<UUID> onOwnerDeath) {
        this.onOwnerDeath = Objects.requireNonNull(onOwnerDeath, "onOwnerDeath");
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent component,
                                 @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
        UUIDComponent uuid = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuid != null && uuid.getUuid() != null) {
            onOwnerDeath.accept(uuid.getUuid());
        }
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }
}
