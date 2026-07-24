package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.CompanionRevivePolicy;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.NPCSystems;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/** Removes live revivable corpses from claim occupancy as soon as death becomes observable. */
public final class CompanionRevivableDeathPopulationSystem extends DeathSystems.OnDeathSystem {
    private final CompanionDeathPopulationProjector projector;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final ComponentType<EntityStore, TameworkCommandLinksComponent> linksType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, NPCSystems.OnDeathSystem.class)
    );

    public CompanionRevivableDeathPopulationSystem(
            @Nonnull CompanionPopulationRuntimeReconciler reconciler,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType
    ) {
        this.projector = new CompanionDeathPopulationProjector(reconciler);
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        this.uuidType = Objects.requireNonNull(uuidType, "uuidType");
        this.transformType = Objects.requireNonNull(transformType, "transformType");
        this.linksType = TameworkCommandLinksComponent.getComponentType();
        this.query = Query.and(
                NPCEntity.getComponentType(),
                this.ownerType,
                this.uuidType,
                this.transformType
        );
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        CompanionPopulationEntityObservation observation =
                CompanionPopulationEntityObservation.fromStore(
                        ref, store, ownerType, uuidType, transformType, null
                );
        if (observation == null || observation.ownerUuid() == null) {
            return;
        }
        TameworkCommandLinksComponent links = linksType == null
                ? null
                : commandBuffer.getComponent(ref, linksType);
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        projector.observeRevivableDeath(
                observation,
                CompanionRevivePolicy.supportsRevive(roleId, links)
        );
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }
}
