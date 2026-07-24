package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.persistence.DormantCompanionEcsBridge;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
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
 * Freezes saved death evidence for linked NPCs when DeathComponent is added.
 */
public final class CompanionDormantDeathSystem
        extends DeathSystems.OnDeathSystem {
    private final DormantCompanionEcsBridge bridge;
    private final Query<EntityStore> query;

    public CompanionDormantDeathSystem(
            @Nonnull DormantCompanionEcsBridge bridge,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
            @Nonnull ComponentType<EntityStore, TameworkCommandLinksComponent>
                    linksType
    ) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.query = Query.and(
                Objects.requireNonNull(npcType, "npcType"),
                Objects.requireNonNull(linksType, "linksType")
        );
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        bridge.onDeath(reference, component, store);
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }
}
