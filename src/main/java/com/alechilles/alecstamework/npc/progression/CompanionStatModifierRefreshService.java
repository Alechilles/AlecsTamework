package com.alechilles.alecstamework.npc.progression;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Reapplies progression stat modifiers for loaded NPCs after global runtime settings change.
 */
public final class CompanionStatModifierRefreshService {
    private CompanionStatModifierRefreshService() {
    }

    public static int refreshLoadedNpcStatModifiers(@Nullable Store<EntityStore> store) {
        if (store == null) {
            return 0;
        }
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();
        if (npcType == null || statType == null) {
            return 0;
        }

        List<Ref<EntityStore>> candidates = new ArrayList<>();
        store.forEachChunk(
                Query.and(npcType, statType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        if (ref != null && ref.isValid()) {
                            candidates.add(ref);
                        }
                    }
                }
        );

        int refreshed = 0;
        for (Ref<EntityStore> ref : candidates) {
            if (ref == null || !ref.isValid() || store.getComponent(ref, npcType) == null) {
                continue;
            }
            CompanionStatModifierService.applyTraitModifiers(ref, store);
            refreshed++;
        }
        return refreshed;
    }
}
