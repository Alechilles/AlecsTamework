package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Applies timed happiness loss impulses when NPCs take real incoming damage.
 */
public final class CompanionHappinessDamageImpulseSystem extends DamageEventSystem {

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
        if (damage == null || damage.isCancelled() || !(damage.getAmount() > 0.0f)) {
            return;
        }
        if (chunk == null || store == null) {
            return;
        }
        Ref<EntityStore> targetRef = chunk.getReferenceTo(index);
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }
        CompanionHappinessService.applyDamageLoss(targetRef, store, damage);
    }
}
