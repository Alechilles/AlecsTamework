package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.persistence.DormantCompanionEcsBridge;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Forwards explicit linked-NPC destruction to the positive-evidence dormant boundary.
 */
public final class CompanionDormantRemovalSystem extends RefSystem<EntityStore> {
    private final DormantCompanionEcsBridge bridge;
    private final Query<EntityStore> query;

    public CompanionDormantRemovalSystem(
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
    public void onEntityAdded(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        // The dormant boundary has no load/appearance inference.
    }

    @Override
    public void onEntityRemove(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        if (reason == RemoveReason.REMOVE) {
            bridge.onRemoval(reference, reason, store);
        }
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }
}
