package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.CompanionRevivePolicy;
import com.alechilles.alecstamework.items.CompanionReviveEligibilityService;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
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
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Suppresses NPC death drops for linked companions that are configured as dead-respawn enabled.
 */
public final class CommandLinkedRevivableDropSuppressionSystem extends DeathSystems.OnDeathSystem {
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        if (npcType != null && linksType != null) {
            return Query.and(npcType, linksType);
        }
        if (npcType != null) {
            return Query.and(npcType);
        }
        if (linksType != null) {
            return Query.and(linksType);
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
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        if (linksType == null) {
            return;
        }
        TameworkCommandLinksComponent links = commandBuffer.getComponent(ref, linksType);
        UUIDComponent identity = UUIDComponent.getComponentType() == null
                ? null : commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        boolean deadRespawnEnabled = CompanionRevivePolicy.featureEnabled(roleId);
        boolean canonicalAuthority = identity != null
                && CompanionReviveEligibilityService.current()
                .protectsFromPermanentDeath(identity.getUuid());
        if (!shouldSuppressDrops(links, deadRespawnEnabled, canonicalAuthority)) {
            return;
        }
        component.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);
    }

    static boolean shouldSuppressDrops(@Nullable TameworkCommandLinksComponent links,
                                       boolean deadRespawnEnabled) {
        return shouldSuppressDrops(links, deadRespawnEnabled, false);
    }

    static boolean shouldSuppressDrops(@Nullable TameworkCommandLinksComponent links,
                                       boolean deadRespawnEnabled,
                                       boolean canonicalAuthority) {
        if (!deadRespawnEnabled) {
            return false;
        }
        if (canonicalAuthority) return true;
        if (links == null) return false;
        String[] toolIds = links.getToolIds();
        return toolIds != null && toolIds.length > 0;
    }
}
