package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Disables vanilla spawn-configuration driven despawn checks for protected companions.
 *
 * <p>Vanilla overpopulation despawn is evaluated via NPC spawn configuration. We opt protected NPCs out by setting
 * their spawn configuration to {@code Integer.MIN_VALUE} whenever they are tamed or have a persisted custom name.
 */
public final class CompanionDespawnProtectionSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 1_000L;
    private static final int DISABLE_DESPAWN_SPAWN_CONFIGURATION = Integer.MIN_VALUE;

    private final StoreScopedState<TickState> statesByStore = new StoreScopedState<>(TickState::new);

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        TickState tickState = statesByStore.get(store);
        long nowMs = System.currentTimeMillis();
        if (nowMs < tickState.nextSweepAtMs) {
            return;
        }
        tickState.nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;

        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (npcType == null) {
            return;
        }
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (tamedType == null && nameType == null) {
            return;
        }

        List<Ref<EntityStore>> candidates = new ArrayList<>();
        store.forEachChunk(
                Query.and(npcType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        NPCEntity npc = chunk.getComponent(i, npcType);
                        if (npc == null || npc.getSpawnConfiguration() == DISABLE_DESPAWN_SPAWN_CONFIGURATION) {
                            continue;
                        }

                        TameworkTamedComponent tamed = tamedType == null ? null : chunk.getComponent(i, tamedType);
                        TameworkNpcNameComponent named = nameType == null ? null : chunk.getComponent(i, nameType);
                        if (!isProtected(tamed, named)) {
                            continue;
                        }

                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        if (ref == null || !ref.isValid()) {
                            continue;
                        }
                        candidates.add(ref);
                    }
                }
        );

        for (Ref<EntityStore> ref : candidates) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            NPCEntity npc = store.getComponent(ref, npcType);
            if (npc == null || npc.getSpawnConfiguration() == DISABLE_DESPAWN_SPAWN_CONFIGURATION) {
                continue;
            }

            TameworkTamedComponent tamed = tamedType == null ? null : store.getComponent(ref, tamedType);
            TameworkNpcNameComponent named = nameType == null ? null : store.getComponent(ref, nameType);
            if (!isProtected(tamed, named)) {
                continue;
            }

            npc.setSpawnConfiguration(DISABLE_DESPAWN_SPAWN_CONFIGURATION);
        }
    }

    private static boolean isProtected(TameworkTamedComponent tamed, TameworkNpcNameComponent named) {
        return isTamed(tamed) || hasCustomName(named);
    }

    private static boolean isTamed(TameworkTamedComponent tamed) {
        return tamed != null && tamed.isTamed();
    }

    private static boolean hasCustomName(TameworkNpcNameComponent named) {
        if (named == null) {
            return false;
        }
        String value = named.getName();
        return value != null && !value.isBlank();
    }

    private static final class TickState {
        private long nextSweepAtMs;
    }
}
