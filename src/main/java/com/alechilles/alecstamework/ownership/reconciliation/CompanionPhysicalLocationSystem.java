package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Staggered, allocation-light natural chunk movement observer with no ECS writes or player scans. */
public final class CompanionPhysicalLocationSystem extends EntityTickingSystem<EntityStore> {
    static final long BUCKET_INTERVAL_MS = 250L;
    static final int BUCKET_COUNT = 8;

    private final CompanionPopulationRuntimeReconciler reconciler;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final Query<EntityStore> query;

    public CompanionPhysicalLocationSystem(
            @Nonnull CompanionPopulationRuntimeReconciler reconciler,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformType
    ) {
        this.reconciler = reconciler;
        this.ownerType = ownerType;
        this.uuidType = uuidType;
        this.transformType = transformType;
        this.query = Query.and(ownerType, uuidType, transformType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        UUIDComponent identity = chunk.getComponent(index, uuidType);
        if (identity == null || identity.getUuid() == null || !isScheduled(identity, System.currentTimeMillis())) {
            return;
        }
        CompanionPopulationEntityObservation observation = CompanionPopulationEntityObservation.fromComponents(
                identity,
                chunk.getComponent(index, ownerType),
                chunk.getComponent(index, transformType),
                store
        );
        if (observation != null) {
            reconciler.observePhysical(
                    observation.npcUuid(), observation.ownerUuid(), observation.worldName(),
                    observation.chunkX(), observation.chunkZ(), CompanionLifecycleState.ACTIVE,
                    "natural-chunk-movement"
            );
        }
    }

    static boolean isScheduled(@Nonnull UUIDComponent identity, long nowMs) {
        int entityBucket = Math.floorMod(identity.getUuid().hashCode(), BUCKET_COUNT);
        int currentBucket = Math.floorMod(nowMs / BUCKET_INTERVAL_MS, BUCKET_COUNT);
        return entityBucket == currentBucket;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }
}
