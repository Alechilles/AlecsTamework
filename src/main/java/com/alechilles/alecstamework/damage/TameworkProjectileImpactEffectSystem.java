package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

public final class TameworkProjectileImpactEffectSystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, TameworkProjectileImpactEffectComponent> impactEffectType;
    private final ComponentType<EntityStore, ProjectileComponent> projectileType;
    private final ComponentType<EntityStore, TransformComponent> transformType;

    public TameworkProjectileImpactEffectSystem(
            ComponentType<EntityStore, TameworkProjectileImpactEffectComponent> impactEffectType,
            ComponentType<EntityStore, ProjectileComponent> projectileType,
            ComponentType<EntityStore, TransformComponent> transformType) {
        this.impactEffectType = impactEffectType;
        this.projectileType = projectileType;
        this.transformType = transformType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull com.hypixel.hytale.component.AddReason reason,
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
        if (impactEffectType == null || projectileType == null || transformType == null) {
            return;
        }

        TameworkProjectileImpactEffectComponent impactEffect = store.getComponent(reference, impactEffectType);
        TransformComponent transform = store.getComponent(reference, transformType);
        ProjectileComponent projectile = store.getComponent(reference, projectileType);
        if (impactEffect == null || transform == null || projectile == null || !impactEffect.isEnabled()) {
            return;
        }

        applyEffectSweep(impactEffect, transform, store, commandBuffer);
    }

    private void applyEffectSweep(@Nonnull TameworkProjectileImpactEffectComponent impactEffect,
                                  @Nonnull TransformComponent transform,
                                  @Nonnull Store<EntityStore> store,
                                  @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ComponentType<EntityStore, EffectControllerComponent> effectType = EffectControllerComponent.getComponentType();
        if (effectType == null) {
            return;
        }

        double radiusSq = impactEffect.getRadius() * impactEffect.getRadius();
        String sourceEntityUuid = impactEffect.getSourceEntityUuid();
        store.forEachChunk(
                Query.and(effectType, transformType),
                (ArchetypeChunk<EntityStore> targets, CommandBuffer<EntityStore> ignored) -> {
                    int targetCount = targets.size();
                    for (int j = 0; j < targetCount; j++) {
                        Ref<EntityStore> targetRef = targets.getReferenceTo(j);
                        if (targetRef == null || !targetRef.isValid()) {
                            continue;
                        }
                        TransformComponent targetTransform = targets.getComponent(j, transformType);
                        if (targetTransform == null || targetTransform.getPosition().distanceSquared(transform.getPosition()) > radiusSq) {
                            continue;
                        }
                        if (impactEffect.isExcludeSource() && sourceEntityUuid != null && isSourceEntity(targetRef, sourceEntityUuid, store)) {
                            continue;
                        }
                        TameworkEntityEffectService.applyEffect(targetRef, impactEffect.getEffectId(), commandBuffer);
                    }
                }
        );
    }

    private boolean isSourceEntity(@Nonnull Ref<EntityStore> targetRef,
                                   @Nonnull String sourceEntityUuid,
                                   @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        if (uuidType == null) {
            return false;
        }
        UUIDComponent uuidComponent = store.getComponent(targetRef, uuidType);
        return uuidComponent != null && sourceEntityUuid.equals(uuidComponent.getUuid().toString());
    }

    @Override
    public Query<EntityStore> getQuery() {
        if (impactEffectType == null || projectileType == null || transformType == null) {
            return Query.any();
        }
        return Query.and(impactEffectType, projectileType, transformType);
    }
}
