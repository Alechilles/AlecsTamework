package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Records attacker -> victim hit relationships from applied damage events.
 */
public final class DamageTargetMemorySystem extends DamageEventSystem {
    private static final float MIN_RECORDED_DAMAGE = 0.0f;
    private final DamageTargetMemoryService memoryService = DamageTargetMemoryService.getInstance();

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index,
                       ArchetypeChunk<EntityStore> chunk,
                       Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer,
                       Damage damage) {
        if (damage == null || damage.isCancelled() || damage.getAmount() <= MIN_RECORDED_DAMAGE) {
            return;
        }
        Damage.Source source = damage.getSource();
        if (!(source instanceof Damage.EntitySource entitySource)) {
            return;
        }
        Ref<EntityStore> attackerRef = entitySource.getRef();
        Ref<EntityStore> victimRef = chunk.getReferenceTo(index);
        if (attackerRef == null || victimRef == null || !attackerRef.isValid() || !victimRef.isValid()) {
            return;
        }
        if (attackerRef.equals(victimRef)) {
            return;
        }
        memoryService.recordHit(attackerRef, victimRef, resolveCurrentTimeMs(store));
    }

    private long resolveCurrentTimeMs(@Nonnull Store<EntityStore> store) {
        WorldTimeResource worldTime = store.getResource(WorldTimeResource.getResourceType());
        return worldTime != null ? worldTime.getGameTime().toEpochMilli() : System.currentTimeMillis();
    }
}
