package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRuntimeClock;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
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
 * Runs periodic hunger/thirst progression updates for tamed companions.
 */
public final class CompanionNeedsSystem extends TickingSystem<EntityStore> {
    private static final long SYSTEM_SWEEP_INTERVAL_MS = 2_000L;

    private long nextSweepAtMs;

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        CompanionRuntimeClock.advanceByDeltaSeconds(dt);
        long nowMs = System.currentTimeMillis();
        if (nowMs < nextSweepAtMs) {
            return;
        }
        nextSweepAtMs = nowMs + SYSTEM_SWEEP_INTERVAL_MS;

        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (npcType == null || tamedType == null) {
            return;
        }
        List<NeedsCandidate> candidates = new ArrayList<>();
        store.forEachChunk(
                Query.and(npcType, tamedType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        TameworkTamedComponent tamed = chunk.getComponent(i, tamedType);
                        if (ref == null || !ref.isValid() || tamed == null || !tamed.isTamed()) {
                            continue;
                        }
                        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
                        candidates.add(new NeedsCandidate(ref, roleId));
                    }
                }
        );
        for (NeedsCandidate candidate : candidates) {
            if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
                continue;
            }
            CompanionNeedsService.tickNeeds(candidate.ref, store, candidate.roleId);
        }
    }

    private static final class NeedsCandidate {
        private final Ref<EntityStore> ref;
        private final String roleId;

        private NeedsCandidate(Ref<EntityStore> ref, String roleId) {
            this.ref = ref;
            this.roleId = roleId;
        }
    }
}
