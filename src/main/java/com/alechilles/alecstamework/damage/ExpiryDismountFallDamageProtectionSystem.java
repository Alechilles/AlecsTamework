package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.alechilles.alecstamework.effects.TameworkEntityEffectService;

/** Cancels the landing fall damage caused by an expiring companion dismount. */
public final class ExpiryDismountFallDamageProtectionSystem
        extends DamageEventSystem {
    private static final String FALL_CAUSE_ID = "Fall";

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(
            int index,
            ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer,
            Damage damage
    ) {
        if (damage == null || damage.isCancelled() || damage.getAmount() <= 0.0f
                || !isFallDamage(damage) || store == null || chunk == null) {
            return;
        }
        Ref<EntityStore> target = chunk.getReferenceTo(index);
        if (target == null || !target.isValid()
                || store.getComponent(target, Player.getComponentType()) == null) {
            return;
        }
        UUIDComponent uuid = store.getComponent(
                target, UUIDComponent.getComponentType());
        if (uuid == null || uuid.getUuid() == null
                || !ExpiryDismountFallProtectionService.getInstance()
                        .isProtected(uuid.getUuid(), System.currentTimeMillis())) {
            return;
        }
        damage.setAmount(0.0f);
        damage.setCancelled(true);
        ExpiryDismountFallProtectionService.getInstance().clear(uuid.getUuid());
        TameworkEntityEffectService.removeEffect(target,
                ExpiryDismountFallProtectionService.EFFECT_ID, commandBuffer);
    }

    private static boolean isFallDamage(Damage damage) {
        return damage.getCause() != null && damage.getCause().getId() != null
                && FALL_CAUSE_ID.equalsIgnoreCase(
                        String.valueOf(damage.getCause().getId()));
    }
}
