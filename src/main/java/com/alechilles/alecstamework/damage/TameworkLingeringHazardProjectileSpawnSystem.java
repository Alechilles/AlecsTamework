package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

public final class TameworkLingeringHazardProjectileSpawnSystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, TameworkLingeringHazardProjectileComponent> lingeringProjectileType;
    private final ComponentType<EntityStore, TameworkLingeringHazardComponent> lingeringHazardType;
    private final ComponentType<EntityStore, ProjectileComponent> projectileType;
    private final ComponentType<EntityStore, TransformComponent> transformType;

    public TameworkLingeringHazardProjectileSpawnSystem(
            ComponentType<EntityStore, TameworkLingeringHazardProjectileComponent> lingeringProjectileType,
            ComponentType<EntityStore, TameworkLingeringHazardComponent> lingeringHazardType,
            ComponentType<EntityStore, ProjectileComponent> projectileType,
            ComponentType<EntityStore, TransformComponent> transformType) {
        this.lingeringProjectileType = lingeringProjectileType;
        this.lingeringHazardType = lingeringHazardType;
        this.projectileType = projectileType;
        this.transformType = transformType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (reason != RemoveReason.REMOVE) {
            return;
        }
        if (lingeringProjectileType == null || lingeringHazardType == null || projectileType == null || transformType == null) {
            return;
        }

        TameworkLingeringHazardProjectileComponent lingeringProjectile = store.getComponent(reference, lingeringProjectileType);
        TransformComponent transform = store.getComponent(reference, transformType);
        ProjectileComponent projectile = store.getComponent(reference, projectileType);
        if (lingeringProjectile == null || transform == null || projectile == null || !lingeringProjectile.isEnabled()) {
            return;
        }

        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.putComponent(transformType, transform.clone());
        holder.putComponent(
                lingeringHazardType,
                new TameworkLingeringHazardComponent(
                        lingeringProjectile.getRadius(),
                        lingeringProjectile.getDurationSeconds(),
                        lingeringProjectile.getTickIntervalSeconds(),
                        0.0,
                        lingeringProjectile.getDamagePerTick(),
                        lingeringProjectile.isExcludeSource(),
                        lingeringProjectile.getSourceTypeId(),
                        lingeringProjectile.getSourceEntityUuid()
                )
        );
        commandBuffer.addEntity(holder, AddReason.SPAWN);
    }

    @Override
    public Query<EntityStore> getQuery() {
        if (lingeringProjectileType == null || projectileType == null || transformType == null) {
            return Query.any();
        }
        return Query.and(lingeringProjectileType, projectileType, transformType);
    }
}
