package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;

public final class TameworkLingeringHazardSystem extends TickingSystem<EntityStore> {
    private final ComponentType<EntityStore, TameworkLingeringHazardComponent> hazardType;
    private final ComponentType<EntityStore, TransformComponent> transformType;

    public TameworkLingeringHazardSystem(ComponentType<EntityStore, TameworkLingeringHazardComponent> hazardType,
                                         ComponentType<EntityStore, TransformComponent> transformType) {
        this.hazardType = hazardType;
        this.transformType = transformType;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        if (hazardType == null || transformType == null) {
            return;
        }
        store.forEachChunk(
                Query.and(hazardType, transformType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        Ref<EntityStore> hazardRef = chunk.getReferenceTo(i);
                        if (hazardRef == null || !hazardRef.isValid()) {
                            continue;
                        }
                        TameworkLingeringHazardComponent hazard = chunk.getComponent(i, hazardType);
                        TransformComponent transform = chunk.getComponent(i, transformType);
                        if (hazard == null || transform == null || !hazard.isActive()) {
                            commandBuffer.removeEntity(hazardRef, RemoveReason.REMOVE);
                            continue;
                        }

                        double remaining = hazard.getRemainingDurationSeconds() - dt;
                        hazard.setRemainingDurationSeconds(remaining);
                        if (!(remaining > 0.0)) {
                            commandBuffer.removeEntity(hazardRef, RemoveReason.REMOVE);
                            continue;
                        }

                        double countdown = hazard.getTickCountdownSeconds() - dt;
                        hazard.setTickCountdownSeconds(countdown);
                        if (countdown <= 0.0) {
                            applyDamageSweep(hazardRef, hazard, transform, store, commandBuffer);
                            hazard.setTickCountdownSeconds(hazard.getTickIntervalSeconds());
                        }
                        commandBuffer.putComponent(hazardRef, hazardType, hazard);
                    }
                }
        );
    }

    private void applyDamageSweep(@Nonnull Ref<EntityStore> hazardRef,
                                  @Nonnull TameworkLingeringHazardComponent hazard,
                                  @Nonnull TransformComponent transform,
                                  @Nonnull Store<EntityStore> store,
                                  @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ComponentType<EntityStore, EntityStatMap> statMapType = EntityStatMap.getComponentType();
        if (statMapType == null) {
            return;
        }

        double radiusSq = hazard.getRadius() * hazard.getRadius();
        String sourceEntityUuid = hazard.getSourceEntityUuid();

        store.forEachChunk(
                Query.and(statMapType, transformType),
                (ArchetypeChunk<EntityStore> targets, CommandBuffer<EntityStore> ignored) -> {
                    int targetCount = targets.size();
                    for (int j = 0; j < targetCount; j++) {
                        Ref<EntityStore> targetRef = targets.getReferenceTo(j);
                        if (targetRef == null || !targetRef.isValid() || targetRef.equals(hazardRef)) {
                            continue;
                        }
                        TransformComponent targetTransform = targets.getComponent(j, transformType);
                        if (targetTransform == null || targetTransform.getPosition().distanceSquaredTo(transform.getPosition()) > radiusSq) {
                            continue;
                        }
                        if (hazard.isExcludeSource() && sourceEntityUuid != null && isSourceEntity(targetRef, sourceEntityUuid, store)) {
                            continue;
                        }
                        DamageSystems.executeDamage(
                                targetRef,
                                commandBuffer,
                                new Damage(resolveDamageSource(hazard, store), resolveDamageCause(), (float) hazard.getDamagePerTick())
                        );
                        TameworkEntityEffectService.applyEffect(targetRef, hazard.getEffectId(), commandBuffer);
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

    @Nonnull
    private Damage.Source resolveDamageSource(@Nonnull TameworkLingeringHazardComponent hazard,
                                             @Nonnull Store<EntityStore> store) {
        String sourceEntityUuid = hazard.getSourceEntityUuid();
        if (sourceEntityUuid != null && !sourceEntityUuid.isBlank()) {
            try {
                EntityStore entityStore = store.getExternalData();
                if (entityStore != null) {
                    Ref<EntityStore> sourceRef = entityStore.getRefFromUUID(UUID.fromString(sourceEntityUuid));
                    if (sourceRef != null && sourceRef.isValid()) {
                        return new Damage.EntitySource(sourceRef);
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new Damage.EnvironmentSource(hazard.getSourceTypeId());
    }

    @Nonnull
    private static DamageCause resolveDamageCause() {
        if (DamageCause.ENVIRONMENT != null) {
            return DamageCause.ENVIRONMENT;
        }
        if (DamageCause.PHYSICAL != null) {
            return DamageCause.PHYSICAL;
        }
        return DamageCause.COMMAND;
    }
}
