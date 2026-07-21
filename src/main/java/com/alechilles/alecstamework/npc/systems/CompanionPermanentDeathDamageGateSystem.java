package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.CompanionRevivePolicy;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.ownership.CompanionPermanentDeathCoordinator;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Pauses finalized lethal damage until the permanent owner-release journal is APPLYING. */
public final class CompanionPermanentDeathDamageGateSystem extends DamageEventSystem {
    private final CompanionPermanentDeathCoordinator coordinator;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final ComponentType<EntityStore, TameworkCommandLinksComponent> linksType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final ComponentType<EntityStore, EntityStatMap> statsType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies;

    public CompanionPermanentDeathDamageGateSystem(
            @Nonnull CompanionPermanentDeathCoordinator coordinator,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.linksType = TameworkCommandLinksComponent.getComponentType();
        this.uuidType = Objects.requireNonNull(UUIDComponent.getComponentType(), "uuidType");
        this.statsType = Objects.requireNonNull(EntityStatMap.getComponentType(), "statsType");
        this.query = Query.and(
                NPCEntity.getComponentType(), ownerType, uuidType, statsType
        );
        this.dependencies = Set.of(
                new SystemGroupDependency<>(Order.AFTER, DamageModule.get().getFilterDamageGroup()),
                new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class)
        );
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> chunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull Damage damage) {
        if (damage.isCancelled() || !(damage.getAmount() > 0.0f)) {
            return;
        }
        TameworkOwnerComponent owner = chunk.getComponent(index, ownerType);
        UUIDComponent identity = chunk.getComponent(index, uuidType);
        EntityStatMap stats = chunk.getComponent(index, statsType);
        if (owner == null || owner.getOwnerId() == null
                || identity == null || identity.getUuid() == null || stats == null) {
            return;
        }
        EntityStatValue health = stats.get(DefaultEntityStatTypes.getHealth());
        float finalDamage = Math.round(damage.getAmount());
        if (health == null || !(finalDamage > 0.0f)
                || health.get() - finalDamage > health.getMin()) {
            return;
        }
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        TameworkCommandLinksComponent links = linksType == null
                ? null : chunk.getComponent(index, linksType);
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        if (CompanionRevivePolicy.supportsRevive(roleId, links, identity.getUuid())) {
            return;
        }
        coordinator.interceptLethalDamage(
                ref, store, identity.getUuid(), owner.getOwnerId(), damage, finalDamage
        );
        damage.setAmount(0.0f);
        damage.setCancelled(true);
    }
}
