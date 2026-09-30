package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Records a companion death when DeathComponent is added to a stamped NPC (spec 8.6). A body
 * saved while dying does not fire this again on load; the body system handles that corpse.
 */
public final class CompanionDeathSystem extends DeathSystems.OnDeathSystem {
    private final CompanionBodyLifecycle lifecycle;
    private final Query<EntityStore> query;

    public CompanionDeathSystem(@Nonnull CompanionBodyLifecycle lifecycle,
                                @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
                                @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stampType) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.query = Query.and(Objects.requireNonNull(npcType, "npcType"),
                Objects.requireNonNull(stampType, "stampType"));
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent component,
                                 @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
        lifecycle.died(ref, component, store);
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }
}
