package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.CompanionRevivePolicy;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.ownership.CompanionPermanentDeathCoordinator;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.NPCSystems;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Retains direct-death corpses until their permanent owner release reaches durable APPLYING state.
 */
public final class CompanionPermanentDeathFallbackSystem extends DeathSystems.OnDeathSystem {
    private final CompanionPermanentDeathCoordinator coordinator;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final ComponentType<EntityStore, TameworkCommandLinksComponent> linksType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            // Queue the durability hold after vanilla queues its role-authored corpse timer.
            new SystemDependency<>(Order.AFTER, NPCSystems.OnDeathSystem.class)
    );

    public CompanionPermanentDeathFallbackSystem(
            @Nonnull CompanionPermanentDeathCoordinator coordinator,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.linksType = TameworkCommandLinksComponent.getComponentType();
        this.uuidType = Objects.requireNonNull(UUIDComponent.getComponentType(), "uuidType");
        this.query = Query.and(NPCEntity.getComponentType(), ownerType, uuidType);
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
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull DeathComponent component,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        UUIDComponent identity = commandBuffer.getComponent(ref, uuidType);
        TameworkOwnerComponent owner = commandBuffer.getComponent(ref, ownerType);
        if (identity == null || identity.getUuid() == null
                || owner == null || owner.getOwnerId() == null) {
            return;
        }
        TameworkCommandLinksComponent links = linksType == null
                ? null : commandBuffer.getComponent(ref, linksType);
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        if (CompanionRevivePolicy.supportsRevive(roleId, links)) {
            return;
        }
        coordinator.interceptExistingDeath(
                ref, store, identity.getUuid(), owner.getOwnerId(), component
        );
        String deathParticles = deathParticles(ref, store);
        commandBuffer.putComponent(
                ref,
                DeferredCorpseRemoval.getComponentType(),
                CompanionPermanentDeathHold.create(deathParticles)
        );
    }

    private static String deathParticles(@Nonnull Ref<EntityStore> ref,
                                         @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        return npc == null || npc.getRole() == null ? null : npc.getRole().getDeathParticles();
    }
}
