package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Observes direct owner-component mutations and adopts them conservatively after in-flight guards. */
public final class CompanionOwnerComponentReconciliationSystem
        extends RefChangeSystem<EntityStore, TameworkOwnerComponent> {
    private final CompanionPopulationRuntimeReconciler reconciler;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final Query<EntityStore> query;

    public CompanionOwnerComponentReconciliationSystem(
            @Nonnull CompanionPopulationRuntimeReconciler reconciler,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType
    ) {
        this.reconciler = reconciler;
        this.ownerType = ownerType;
        this.uuidType = uuidType;
        this.transformType = transformType;
        this.query = Query.and(uuidType, transformType);
    }

    @Override
    public ComponentType<EntityStore, TameworkOwnerComponent> componentType() {
        return ownerType;
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull TameworkOwnerComponent component,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        observe(ref, component, store, "owner-component-added");
    }

    @Override
    public void onComponentSet(@Nonnull Ref<EntityStore> ref,
                               @Nonnull TameworkOwnerComponent previous,
                               @Nonnull TameworkOwnerComponent component,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        observe(ref, component, store, "owner-component-set");
    }

    @Override
    public void onComponentRemoved(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull TameworkOwnerComponent component,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        observe(ref, null, store, "owner-component-removed");
    }

    private void observe(@Nonnull Ref<EntityStore> ref,
                         @Nullable TameworkOwnerComponent owner,
                         @Nonnull Store<EntityStore> store,
                         @Nonnull String source) {
        CompanionPopulationEntityObservation observation = CompanionPopulationEntityObservation.fromStore(
                ref, store, ownerType, uuidType, transformType,
                owner == null ? new TameworkOwnerComponent(null, null) : owner
        );
        if (observation != null) {
            reconciler.observeOwnerComponentPhysical(
                    observation.npcUuid(), observation.ownerUuid(), observation.worldName(),
                    observation.chunkX(), observation.chunkZ(), source
            );
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }
}
