package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.items.CompanionRevivePolicy;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Suppresses NPC death drops for companion bodies (stamped with {@link TameworkCompanionComponent})
 * whose role has dead-respawn enabled, so a revived companion keeps its items (spec 8.6 step 2).
 * An old-age death still drops.
 */
public final class CommandLinkedRevivableDropSuppressionSystem extends DeathSystems.OnDeathSystem {
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TameworkCompanionComponent> stampType =
                TameworkCompanionComponent.getComponentType();
        if (npcType != null && stampType != null) {
            return Query.and(npcType, stampType);
        }
        if (npcType != null) {
            return Query.and(npcType);
        }
        if (stampType != null) {
            return Query.and(stampType);
        }
        return Query.any();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.BEFORE, NPCDamageSystems.DropDeathItems.class));
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull DeathComponent component,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ComponentType<EntityStore, TameworkCompanionComponent> stampType =
                TameworkCompanionComponent.getComponentType();
        if (stampType == null) {
            return;
        }
        TameworkCompanionComponent stamp = commandBuffer.getComponent(ref, stampType);
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        boolean deadRespawnEnabled = CompanionRevivePolicy.featureEnabled(roleId);
        if (CompanionRevivePolicy.isOldAgeDeath(component)
                || !shouldSuppressDrops(stamp, deadRespawnEnabled)) {
            return;
        }
        component.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);
    }

    static boolean shouldSuppressDrops(@Nullable TameworkCompanionComponent stamp,
                                       boolean deadRespawnEnabled) {
        return deadRespawnEnabled && stamp != null;
    }
}
