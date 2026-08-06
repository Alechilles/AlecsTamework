package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.spawning.CompanionSpawnAuthorityService;
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
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;
import javax.annotation.Nonnull;

/** Load-time repair systems for both sides of companion spawn authority. */
public final class CompanionSpawnAuthorityCleanupSystems {
    private CompanionSpawnAuthorityCleanupSystems() {
    }

    /** Detaches every loaded tamed NPC, including companions saved before the fix. */
    public static final class Npc extends RefSystem<EntityStore> {
        private final ComponentType<EntityStore, NPCEntity> npcType;
        private final ComponentType<EntityStore, TameworkTamedComponent> tamedType;
        private final Query<EntityStore> query;

        public Npc(
                ComponentType<EntityStore, NPCEntity> npcType,
                ComponentType<EntityStore, TameworkTamedComponent> tamedType
        ) {
            this.npcType = npcType;
            this.tamedType = tamedType;
            this.query = Query.and(npcType, tamedType);
        }

        @Override
        public void onEntityAdded(
                @Nonnull Ref<EntityStore> reference,
                @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer
        ) {
            TameworkTamedComponent tamed = store.getComponent(
                    reference, tamedType
            );
            if (store.getComponent(reference, npcType) == null
                    || tamed == null || !tamed.isTamed()) {
                return;
            }
            commandBuffer.run(bufferStore -> {
                TameworkTamedComponent latest = reference.isValid()
                        ? bufferStore.getComponent(reference, tamedType)
                        : null;
                if (latest != null && latest.isTamed()) {
                    CompanionSpawnAuthorityService.detach(
                            reference, bufferStore
                    );
                }
            });
        }

        @Override
        public void onEntityRemove(
                @Nonnull Ref<EntityStore> reference,
                @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer
        ) {
            // No-op.
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }
    }

    /** Repairs marker-first load ordering by auditing loaded reverse members. */
    public static final class Marker extends RefSystem<EntityStore> {
        private final ComponentType<EntityStore, SpawnMarkerEntity> markerType;
        private final ComponentType<EntityStore, TameworkTamedComponent> tamedType;
        private final Query<EntityStore> query;

        public Marker(
                ComponentType<EntityStore, SpawnMarkerEntity> markerType,
                ComponentType<EntityStore, TameworkTamedComponent> tamedType
        ) {
            this.markerType = markerType;
            this.tamedType = tamedType;
            this.query = Query.and(markerType);
        }

        @Override
        public void onEntityAdded(
                @Nonnull Ref<EntityStore> reference,
                @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer
        ) {
            commandBuffer.run(bufferStore -> {
                if (reference.isValid()) {
                    CompanionSpawnAuthorityService.detachLoadedTamedMembers(
                            reference, bufferStore, markerType, tamedType
                    );
                }
            });
        }

        @Override
        public void onEntityRemove(
                @Nonnull Ref<EntityStore> reference,
                @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer
        ) {
            // No-op.
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }
    }
}
